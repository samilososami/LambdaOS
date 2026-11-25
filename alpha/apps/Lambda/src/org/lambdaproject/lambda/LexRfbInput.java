package org.lambdaproject.lambda;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Input-only RFB 3.3/3.7/3.8 client for the LEX Xvnc session: VncAuth, then
 *  PointerEvent/KeyEvent messages. It never requests a framebuffer update;
 *  the noVNC WebView does the painting, both against the same Xvnc on
 *  loopback. Single use: build a new instance for every reconnect.
 *
 *  The pointer/key writes happen on the caller's (UI) thread; one tiny
 *  message on loopback is cheaper than a queue. Nothing reads the socket
 *  after ServerInit, so a dead server is detected on the next write.
 *
 *  UNTESTED ON HARDWARE: the external-display path was not available. The
 *  VncAuth computation is checked against an independent DES known-answer
 *  vector; see alpha/docs/lex-modes-integration-2026-11-23.md. */
final class LexRfbInput {
    interface Listener {
        /** Called on the RFB thread after ServerInit. */
        void onReady(int width,int height);
        /** Called at most once, on the RFB or caller thread; never after close(). */
        void onError(String detail);
    }

    private volatile OutputStream out;
    private volatile Socket socket;
    private volatile Listener listener;
    private volatile boolean closed;
    private int lastX,lastY;

    void connect(final String host,final int port,final String password,final Listener target){
        listener=target;
        Thread thread=new Thread(()->session(host,port,password,target),"lex-rfb");
        thread.setDaemon(true);
        thread.start();
    }

    private void session(String host,int port,String password,Listener target){
        Socket s=null;
        try{
            s=new Socket();
            s.connect(new InetSocketAddress(host,port),4000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(8000);
            DataInputStream in=new DataInputStream(s.getInputStream());
            int[] size=handshake(in,s.getOutputStream(),password);
            s.setSoTimeout(0);
            if(closed){closeSocket(s);return;}
            socket=s;
            out=s.getOutputStream();
            if(closed){closeSocket(s);return;}
            target.onReady(size[0],size[1]);
        }catch(Exception e){
            closeSocket(s);
            fail(detail(e));
        }
    }

    /** PointerEvent: button mask 1/2/4 for left/middle/right, 8/16 for wheel up/down. */
    void pointer(int x,int y,int mask){
        lastX=x;lastY=y;
        send(new byte[]{5,(byte)mask,(byte)(x>>8),(byte)x,(byte)(y>>8),(byte)y});
    }

    /** One wheel step at the last pointer position: positive scrolls down. */
    void scroll(int steps){
        int count=Math.max(1,Math.abs(steps));
        int mask=steps>=0?16:8;
        for(int i=0;i<count;i++){
            send(new byte[]{5,(byte)mask,(byte)(lastX>>8),(byte)lastX,(byte)(lastY>>8),(byte)lastY});
            send(new byte[]{5,0,(byte)(lastX>>8),(byte)lastX,(byte)(lastY>>8),(byte)lastY});
        }
    }

    /** KeyEvent with an X11 keysym (printable ASCII or a control keysym). */
    void key(int keysym,boolean down){
        send(new byte[]{4,(byte)(down?1:0),0,0,
            (byte)(keysym>>24),(byte)(keysym>>16),(byte)(keysym>>8),(byte)keysym});
    }

    void close(){
        Socket s;
        synchronized(this){
            if(closed)return;
            closed=true;
            out=null;
            s=socket;socket=null;
        }
        listener=null;
        closeSocket(s);
    }

    private void send(byte[] message){
        OutputStream target=out;
        if(target==null||closed)return;
        try{
            target.write(message);
            target.flush();
        }catch(IOException e){
            fail("vnc_disconnected");
        }
    }

    private void fail(String detail){
        Listener target;
        Socket s;
        synchronized(this){
            if(closed)return;
            closed=true;
            out=null;
            s=socket;socket=null;
            target=listener;
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
        in.readFully(new byte[16]); // pixel format: unused, no framebuffer
        int nameLength=in.readInt();
        if(nameLength<0||nameLength>4096)throw new IOException("vnc_server_init_invalid");
        in.readFully(new byte[nameLength]);
        // Raw only; this client never asks the server for an update.
        out.write(new byte[]{2,0,0,1,0,0,0,0});
        out.flush();
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
     *  password bytes as key, each key byte bit-reversed. The DES key for
     *  password "01234567" is 0c8c4ccc2cac6cec; see the KAT in the doc. */
    static byte[] vncResponse(byte[] challenge,String password)throws Exception{
        byte[] key=new byte[8];
        byte[] raw=password==null?new byte[0]:password.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw,0,key,0,Math.min(8,raw.length));
        for(int i=0;i<8;i++)key[i]=(byte)(Integer.reverse(key[i]&0xff)>>>24);
        Cipher cipher=Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"DES"));
        return cipher.doFinal(challenge);
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
