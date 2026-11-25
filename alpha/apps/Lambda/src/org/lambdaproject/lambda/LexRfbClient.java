package org.lambdaproject.lambda;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Native RFB 3.3/3.7/3.8 framebuffer client for the LEX Xvnc session.
 *  VncAuth, then a 32bpp little-endian true-colour framebuffer decoded from
 *  Raw/Hextile/CopyRect into an ARGB_8888 int[] (0xFF alpha, no per-pixel
 *  conversion beyond the alpha OR). Exactly one FramebufferUpdateRequest is
 *  in flight: the next one is sent only after the current update has been
 *  decoded and delivered, so the buffer is stable during the callback.
 *
 *  One instance is one connection; reconnect with backoff is the caller's
 *  job. This class is pure Java (no android.*) so it can be tested on the
 *  host JVM. The password is only used to compute the VncAuth response; it
 *  is never logged, stored or returned.
 *
 *  DesktopSize (-223) pseudo-rectangles are ignored: Xvnc runs a fixed
 *  geometry, so a resize is not followed. */
public final class LexRfbClient {
    public interface Listener {
        /** Called on the RFB thread after authentication. pixels is ARGB_8888. */
        void onReady(int width,int height,int[] pixels);
        /** Called on the RFB thread after an update is fully decoded. damage
         *  holds count/4 quads (x,y,w,h); the pixel buffer is not modified
         *  again until this returns. */
        void onFramebufferUpdate(int[] pixels,int[] damage,int count);
        /** Called at most once per session, never after close(). */
        void onError(String detail);
    }

    public static final int ENCODING_RAW=0,ENCODING_COPY_RECT=1,ENCODING_HEXTILE=5;
    public static final int KEY_BACKSPACE=0xff08,KEY_TAB=0xff09,KEY_RETURN=0xff0d;
    public static final int KEY_ESCAPE=0xff1b,KEY_SHIFT=0xffe1,KEY_CONTROL=0xffe3;
    public static final int KEY_ALT=0xffe9,KEY_SUPER=0xffeb;

    private static final int PSEUDO_DESKTOP_SIZE=-223;
    private static final int MAX_DIMENSION=8192;
    private static final long MAX_PIXELS=4_000_000L;

    private final Object writeLock=new Object();
    private volatile OutputStream out;
    private volatile Socket socket;
    private volatile Listener listener;
    private volatile boolean closed;
    private volatile int[] pixels;
    private volatile int width,height;
    private byte[] row;
    private int[] damage=new int[64*4];
    private int damageCount;
    private int lastX,lastY;

    public void connect(final String host,final int port,final String password,final Listener target){
        if(listener!=null||socket!=null)throw new IllegalStateException("already_connected");
        listener=target;
        Thread thread=new Thread(()->session(host,port,password),"lex-rfb-client");
        thread.setDaemon(true);
        thread.start();
    }

    private void session(String host,int port,String password){
        Socket s=null;
        try{
            s=new Socket();
            s.connect(new InetSocketAddress(host,port),4000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(8000);
            DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream(),16384));
            OutputStream o=s.getOutputStream();
            int[] size=handshake(in,o,password);
            int w=size[0],h=size[1];
            if(w<=0||h<=0||w>MAX_DIMENSION||h>MAX_DIMENSION||(long)w*h>MAX_PIXELS)
                throw new IOException("vnc_server_init_invalid");
            width=w;height=h;
            pixels=new int[w*h];
            row=new byte[w*4];
            s.setSoTimeout(0);
            synchronized(writeLock){
                if(closed){closeSocket(s);return;}
                socket=s;out=o;
            }
            // 32bpp, depth 24, little-endian, true-colour, R16 G8 B0.
            send(new byte[]{0,0,0,0,32,24,0,1,0,(byte)255,0,(byte)255,0,(byte)255,16,8,0,0,0,0});
            // Hextile, CopyRect, Raw, in that preference order.
            send(new byte[]{2,0,0,3,0,0,0,5,0,0,0,1,0,0,0,0});
            send(framebufferRequest(false));
            Listener target=listener;
            if(closed||target==null)return;
            target.onReady(w,h,pixels);
            while(!closed){
                int type=in.readUnsignedByte();
                if(type==0)framebufferUpdate(in);
                else if(type==1){ // SetColourMapEntries: pad, first, count, RGB triples
                    skipFully(in,5);
                    skipFully(in,6L*in.readUnsignedShort());
                }else if(type==2){ // Bell
                }else if(type==3){ // ServerCutText
                    skipFully(in,3);
                    skipFully(in,in.readInt()&0xffffffffL);
                }else throw new IOException("vnc_unexpected_message_"+type);
            }
        }catch(Exception e){
            fail(detail(e));
        }finally{
            closeSocket(s);
        }
    }

    private void framebufferUpdate(DataInputStream in)throws IOException{
        in.readUnsignedByte();
        int count=in.readUnsignedShort();
        damageCount=0;
        for(int i=0;i<count;i++){
            int x=in.readUnsignedShort(),y=in.readUnsignedShort();
            int w=in.readUnsignedShort(),h=in.readUnsignedShort();
            int encoding=in.readInt();
            if(encoding==PSEUDO_DESKTOP_SIZE)continue;
            checkRect(x,y,w,h);
            if(encoding==ENCODING_RAW)decodeRaw(in,x,y,w,h,pixels,width,row);
            else if(encoding==ENCODING_HEXTILE)decodeHextile(in,x,y,w,h,pixels,width,row);
            else if(encoding==ENCODING_COPY_RECT)copyRect(in,x,y,w,h,pixels,width,width,height);
            else throw new IOException("vnc_encoding_unsupported_"+encoding);
            addDamage(x,y,w,h);
        }
        Listener target=listener;
        if(damageCount>0&&!closed&&target!=null)target.onFramebufferUpdate(pixels,damage,damageCount);
        // Sent after delivery, so at most one request is ever outstanding and
        // the array seen by the callback cannot change underneath it.
        send(framebufferRequest(true));
    }

    private void checkRect(int x,int y,int w,int h)throws IOException{
        if(x<0||y<0||w<=0||h<=0||x+w>width||y+h>height)
            throw new IOException("vnc_rect_out_of_bounds");
    }

    private void addDamage(int x,int y,int w,int h){
        if(damageCount+4>damage.length){
            int[] grown=new int[Math.max(damage.length*2,damageCount+4)];
            System.arraycopy(damage,0,grown,0,damageCount);
            damage=grown;
        }
        damage[damageCount++]=x;damage[damageCount++]=y;
        damage[damageCount++]=w;damage[damageCount++]=h;
    }

    /** Raw rows are B,G,R,X in little-endian order, i.e. 0x00RRGGBB as an int. */
    static void decodeRaw(DataInputStream in,int x,int y,int w,int h,
                          int[] fb,int stride,byte[] row)throws IOException{
        for(int r=0;r<h;r++){
            in.readFully(row,0,w*4);
            int base=(y+r)*stride+x;
            for(int i=0,o=0;i<w;i++,o+=4)
                fb[base+i]=0xff000000|(row[o]&0xff)|((row[o+1]&0xff)<<8)|((row[o+2]&0xff)<<16);
        }
    }

    static void decodeHextile(DataInputStream in,int rx,int ry,int rw,int rh,
                              int[] fb,int stride,byte[] row)throws IOException{
        int bg=0,fg=0; // Hextile background/foreground persist across tiles
        for(int ty=ry;ty<ry+rh;ty+=16){
            int th=Math.min(16,ry+rh-ty);
            for(int tx=rx;tx<rx+rw;tx+=16){
                int tw=Math.min(16,rx+rw-tx);
                int sub=in.readUnsignedByte();
                if((sub&1)!=0){
                    for(int r=0;r<th;r++){
                        in.readFully(row,0,tw*4);
                        int base=(ty+r)*stride+tx;
                        for(int i=0,o=0;i<tw;i++,o+=4)
                            fb[base+i]=0xff000000|(row[o]&0xff)|((row[o+1]&0xff)<<8)|((row[o+2]&0xff)<<16);
                    }
                    continue;
                }
                if((sub&2)!=0)bg=readPixel(in);
                if((sub&4)!=0)fg=readPixel(in);
                fillTile(fb,stride,tx,ty,tw,th,bg);
                if((sub&8)==0)continue;
                int n=in.readUnsignedByte();
                boolean coloured=(sub&16)!=0;
                for(int i=0;i<n;i++){
                    int colour=coloured?readPixel(in):fg;
                    int xy=in.readUnsignedByte(),wh=in.readUnsignedByte();
                    int x0=Math.max(tx+(xy>>4),tx),x1=Math.min(tx+(xy>>4)+((wh>>4)+1),tx+tw);
                    int y0=Math.max(ty+(xy&15),ty),y1=Math.min(ty+(xy&15)+((wh&15)+1),ty+th);
                    for(int r=y0;r<y1;r++){
                        int base=r*stride;
                        for(int c=x0;c<x1;c++)fb[base+c]=colour;
                    }
                }
            }
        }
    }

    private static void fillTile(int[] fb,int stride,int x,int y,int w,int h,int colour){
        for(int r=y;r<y+h;r++){
            int base=r*stride;
            for(int c=x;c<x+w;c++)fb[base+c]=colour;
        }
    }

    private static int readPixel(DataInputStream in)throws IOException{
        int b=in.readUnsignedByte(),g=in.readUnsignedByte(),r=in.readUnsignedByte();
        in.readUnsignedByte();
        return 0xff000000|b|(g<<8)|(r<<16);
    }

    static void copyRect(DataInputStream in,int dx,int dy,int w,int h,
                         int[] fb,int stride,int fbWidth,int fbHeight)throws IOException{
        int sx=in.readUnsignedShort(),sy=in.readUnsignedShort();
        if(sx+w>fbWidth||sy+h>fbHeight)throw new IOException("vnc_rect_out_of_bounds");
        if(dy<=sy){ // destination above source: top-down cannot clobber a later row
            for(int r=0;r<h;r++)
                System.arraycopy(fb,(sy+r)*stride+sx,fb,(dy+r)*stride+dx,w);
        }else{ // destination below source: copy bottom-up
            for(int r=h-1;r>=0;r--)
                System.arraycopy(fb,(sy+r)*stride+sx,fb,(dy+r)*stride+dx,w);
        }
    }

    /** PointerEvent at absolute framebuffer coordinates. Mask bits 1/2/4 are
     *  left/middle/right; 8/16 are wheel up/down. */
    public void pointer(int x,int y,int mask){
        lastX=x;lastY=y;
        synchronized(writeLock){
            OutputStream o=out;
            if(o==null||closed)return;
            try{
                o.write(5);o.write(mask);
                o.write(x>>8);o.write(x);o.write(y>>8);o.write(y);
                o.flush();
            }catch(IOException e){fail("vnc_disconnected");}
        }
    }

    /** Wheel at the last pointer position; positive scrolls down. */
    public void scroll(int steps){
        int count=Math.max(1,Math.abs(steps));
        int mask=steps>=0?16:8;
        for(int i=0;i<count;i++){
            pointer(lastX,lastY,mask);
            pointer(lastX,lastY,0);
        }
    }

    /** KeyEvent with an X11 keysym (printable Latin-1 or an X keysym). */
    public void key(int keysym,boolean down){
        synchronized(writeLock){
            OutputStream o=out;
            if(o==null||closed)return;
            try{
                o.write(4);o.write(down?1:0);o.write(0);o.write(0);
                o.write(keysym>>24);o.write(keysym>>16);o.write(keysym>>8);o.write(keysym);
                o.flush();
            }catch(IOException e){fail("vnc_disconnected");}
        }
    }

    /** X11 keysym for a Unicode code point typed on the soft keyboard. */
    public static int keysym(int codePoint){
        if(codePoint=='\n'||codePoint=='\r')return KEY_RETURN;
        if(codePoint=='\b')return KEY_BACKSPACE;
        if(codePoint=='\t')return KEY_TAB;
        if(codePoint>=0x20&&codePoint<=0xff)return codePoint;
        if(codePoint>0xff)return 0x01000000|codePoint;
        return 0;
    }

    public void close(){
        Socket s;
        synchronized(this){
            if(closed)return;
            closed=true;
            out=null;
            s=socket;
            socket=null;
            listener=null;
        }
        closeSocket(s);
    }

    private void send(byte[] message){
        synchronized(writeLock){
            OutputStream o=out;
            if(o==null||closed)return;
            try{
                o.write(message);
                o.flush();
            }catch(IOException e){fail("vnc_disconnected");}
        }
    }

    private byte[] framebufferRequest(boolean incremental){
        return new byte[]{3,(byte)(incremental?1:0),0,0,0,0,
            (byte)(width>>8),(byte)width,(byte)(height>>8),(byte)height};
    }

    private void fail(String detail){
        Listener target;
        Socket s;
        synchronized(this){
            if(closed)return;
            closed=true;
            out=null;
            s=socket;
            socket=null;
            target=listener;
            listener=null;
        }
        closeSocket(s);
        if(target!=null)target.onError(detail);
    }

    private static int[] handshake(DataInputStream in,OutputStream out,String password)throws Exception{
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        byte[] version=new byte[12];
        in.readFully(version);
        String text=new String(version,StandardCharsets.US_ASCII);
        if(!text.startsWith("RFB "))throw new IOException("vnc_protocol_mismatch");
        int major,minor;
        try{
            major=Integer.parseInt(text.substring(4,7));
            minor=Integer.parseInt(text.substring(8,11));
        }catch(NumberFormatException e){throw new IOException("vnc_protocol_mismatch");}
        if(major<3||(major==3&&minor<3))throw new IOException("vnc_protocol_too_old");
        boolean modern=major>3||minor>=7;
        if(modern){
            int count=in.readUnsignedByte();
            if(count==0)throw new IOException("vnc_auth_rejected"+reason(in,minor>=8));
            byte[] types=new byte[count];
            in.readFully(types);
            boolean vncAuth=false;
            for(byte type:types)if(type==2)vncAuth=true;
            if(!vncAuth)throw new IOException("vnc_auth_unsupported");
            out.write(2);
            out.flush();
        }else{
            int type=in.readInt();
            if(type==0)throw new IOException("vnc_auth_rejected"+reason(in,false));
            if(type!=2)throw new IOException("vnc_auth_unsupported");
        }
        byte[] challenge=new byte[16];
        in.readFully(challenge);
        out.write(vncResponse(challenge,password));
        out.flush();
        if(modern){
            int result=in.readInt();
            if(result!=0)throw new IOException("vnc_auth_failed"+reason(in,minor>=8));
        }
        out.write(1); // ClientInit: shared session
        out.flush();
        int width=in.readUnsignedShort(),height=in.readUnsignedShort();
        in.readFully(new byte[16]); // ServerInit pixel format: we set our own
        int nameLength=in.readInt();
        if(nameLength<0||nameLength>65536)throw new IOException("vnc_server_init_invalid");
        skipFully(in,nameLength);
        return new int[]{width,height};
    }

    private static String reason(DataInputStream in,boolean available){
        if(!available)return "";
        try{
            int length=in.readInt();
            if(length<=0||length>1024)return "";
            byte[] text=new byte[length];
            in.readFully(text);
            String message=new String(text,StandardCharsets.UTF_8).trim().replace('\n',' ');
            return message.isEmpty()?"":": "+message;
        }catch(IOException e){return "";}
    }

    /** VncAuth response: DES-ECB of the 16-byte challenge with the first eight
     *  password bytes as key, each key byte bit-reversed. KAT: password
     *  "01234567", challenge 00..0f gives e78701495117e1afd62fb5d65bb98b20. */
    static byte[] vncResponse(byte[] challenge,String password)throws Exception{
        byte[] key=new byte[8];
        byte[] raw=password==null?new byte[0]:password.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw,0,key,0,Math.min(8,raw.length));
        for(int i=0;i<8;i++)key[i]=(byte)(Integer.reverse(key[i]&0xff)>>>24);
        Cipher cipher=Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"DES"));
        return cipher.doFinal(challenge);
    }

    private static void skipFully(DataInputStream in,long count)throws IOException{
        while(count>0){
            int skipped=in.skipBytes((int)Math.min(count,65536));
            if(skipped<=0){
                if(in.read()<0)throw new EOFException();
                skipped=1;
            }
            count-=skipped;
        }
    }

    private static String detail(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "vnc_timeout";
        if(e instanceof java.net.ConnectException)return "vnc_unreachable";
        if(e instanceof java.net.SocketException)return "vnc_disconnected";
        String message=e.getMessage();
        return message==null||message.trim().isEmpty()?e.getClass().getSimpleName():message.trim();
    }

    private static void closeSocket(Socket socket){
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
    }
}
