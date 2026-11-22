package org.lambdaproject.lambda;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Bounded receive-only decoder. It never interprets packet text as commands. */
public final class WifiFrames {
    public static final class Frame {
        public String bssid="",station="",ssid="",security="",nonce="",replay="";
        public int channel,signal=-127,message,descriptorType,descriptorVersion;
        public long receivedNanos=System.nanoTime();
        public boolean beacon,mic;
    }
    private static int u(byte[] b,int p){return b[p]&255;}
    private static int le16(byte[] b,int p){return u(b,p)|u(b,p+1)<<8;}
    private static int be16(byte[] b,int p){return u(b,p)<<8|u(b,p+1);}
    private static String hex(byte[] b,int p,int n,String sep){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++){if(i>0)s.append(sep);s.append(String.format(Locale.US,"%02x",u(b,p+i)));}return s.toString();}
    private static boolean zero(byte[] b,int p,int n){for(int i=0;i<n;i++)if(b[p+i]!=0)return false;return true;}
    public static Frame decode(byte[] b,int link){
        try {
            int off=0,signal=-127;
            if(link==127){
                if(b.length<8||b[0]!=0)return null;off=le16(b,2);if(off<8||off+24>b.length)return null;
                int present=u(b,4)|u(b,5)<<8|u(b,6)<<16|u(b,7)<<24,pos=8,bits=present;
                while((bits&0x80000000)!=0){if(pos+4>off)return null;bits=u(b,pos)|u(b,pos+1)<<8|u(b,pos+2)<<16|u(b,pos+3)<<24;pos+=4;}
                int[] align={8,1,1,2,2,1},size={8,1,1,4,2,1};
                for(int i=0;i<=5;i++)if((present&(1<<i))!=0){pos=(pos+align[i]-1)&~(align[i]-1);if(pos+size[i]>off)return null;if(i==1&&(u(b,pos)&0x40)!=0)return null;if(i==5)signal=b[pos];pos+=size[i];}
            }else if(link!=105)return null;
            if(off+24>b.length)return null;
            int fc=le16(b,off),type=(fc>>2)&3,sub=(fc>>4)&15;
            if((fc&3)!=0)return null;
            Frame f=new Frame();f.signal=signal<0&&signal>=-126?signal:-127;
            if(type==0&&(sub==8||sub==5)){
                if(off+36>b.length)return null;
                if((u(b,off+16)&1)!=0||zero(b,off+16,6))return null;
                f.beacon=true;f.bssid=hex(b,off+16,6,":");f.security=(le16(b,off+34)&16)!=0?"WEP":"OPEN";
                boolean psk=false,sae=false,rsn=false,wpa=false;
                for(int p=off+36;p+2<=b.length;){int id=u(b,p),len=u(b,p+1);p+=2;if(p+len>b.length)break;
                    if(id==0)f.ssid=new String(b,p,Math.min(32,len),StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}]","");
                    if(id==3&&len>=1)f.channel=u(b,p);
                    if(id==61&&len>=1&&f.channel==0)f.channel=u(b,p);
                    if(id==48&&len>=8){rsn=true;int q=p+6,end=p+len;if(q+2<=end){int count=le16(b,q);q+=2+count*4;if(q+2<=end){int n=le16(b,q);q+=2;for(int j=0;j<n&&q+4<=end;j++,q+=4){if(u(b,q)==0&&u(b,q+1)==15&&u(b,q+2)==172){int akm=u(b,q+3);psk|=akm==2||akm==4||akm==6;sae|=akm==8||akm==9;}}}}}
                    if(id==221&&len>=4&&u(b,p)==0&&u(b,p+1)==80&&u(b,p+2)==242&&u(b,p+3)==1)wpa=true;p+=len;
                }
                if(rsn)f.security=psk?(sae?"WPA2/WPA3":"WPA2"):(sae?"WPA3":"WPA2-EAP");else if(wpa)f.security="WPA";
                return f;
            }
            // Fragment reassembly is not implemented: never count a fragment as
            // a complete EAPOL frame even if its prefix happens to look valid.
            if(type!=2||(fc&0x4400)!=0||(fc&0x0300)==0x0300||(le16(b,off+22)&15)!=0)return null;
            boolean to=(fc&0x0100)!=0,from=(fc&0x0200)!=0;
            if(!to&&!from)return null;
            int h=24+((sub&8)!=0?2:0);if((sub&8)!=0&&(fc&0x8000)!=0)h+=4;
            int llc=off+h,e=llc+8;if(e+99>b.length)return null;
            if(u(b,llc)!=170||u(b,llc+1)!=170||u(b,llc+2)!=3||u(b,llc+3)!=0||u(b,llc+4)!=0||u(b,llc+5)!=0||be16(b,llc+6)!=0x888e)return null;
            int bodyLength=be16(b,e+2);
            if(u(b,e+1)!=3||bodyLength<95||e+4+bodyLength>b.length)return null;
            // WPA/RSN descriptors with a 16-byte MIC carry Key Data Length at
            // offset 97 from the EAPOL header. Match AOSP's overflow check.
            if(be16(b,e+97)>bodyLength-95)return null;
            if(u(b,e+4)!=2&&u(b,e+4)!=254)return null;
            int ki=be16(b,e+5);boolean pair=(ki&8)!=0,ack=(ki&128)!=0,mic=(ki&256)!=0;
            if(!pair||(ki&0x0c00)!=0||((ki&7)==0)||((ki&7)>3))return null;
            f.descriptorType=u(b,e+4);f.descriptorVersion=ki&7;
            f.bssid=hex(b,off+(to?4:10),6,":");f.station=hex(b,off+(to?10:4),6,":");
            if((u(b,off+(to?10:4))&1)!=0||zero(b,off+(to?10:4),6)||(u(b,off+(to?4:10))&1)!=0||zero(b,off+(to?4:10),6))return null;
            f.replay=hex(b,e+9,8,"");f.nonce=hex(b,e+17,32,"");f.mic=mic&&!zero(b,e+81,16);
            if(from&&ack&&!mic&&!zero(b,e+17,32))f.message=1;
            // AOSP can set Secure on M2 during rekeying. A nonzero SNonce,
            // rather than that flag alone, distinguishes it from M4.
            else if(to&&!ack&&mic&&!zero(b,e+17,32)&&f.mic)f.message=2;
            else if(from&&ack&&mic&&!zero(b,e+17,32)&&f.mic)f.message=3;
            else if(to&&!ack&&mic&&zero(b,e+17,32)&&f.mic)f.message=4;
            return f.message>0?f:null;
        }catch(IndexOutOfBoundsException|IllegalArgumentException e){return null;}
    }
    /** A same-client M1/M2 pair, matched replay counter, nonzero nonces and MIC.
     * This is structural evidence, not a password/MIC cryptographic validation. */
    public static final class Exchange {
        private final LinkedHashMap<String,Frame[]> sessions=new LinkedHashMap<>();
        public boolean pair;public int messages;public String station="";
        public void add(Frame f){
            if(f==null||f.message<1)return;
            String key=f.bssid+"/"+f.station+"/"+f.replay+"/"+f.descriptorType+"/"+f.descriptorVersion;
            Frame[] frames=sessions.get(key);if(frames==null){frames=new Frame[5];sessions.put(key,frames);}
            frames[f.message]=f;
            if(sessions.size()>128)sessions.remove(sessions.keySet().iterator().next());
            int count=0;for(int i=1;i<=4;i++)if(frames[i]!=null)count++;
            messages=Math.max(messages,count);
            if(frames[1]!=null&&frames[2]!=null&&Math.abs(frames[1].receivedNanos-frames[2].receivedNanos)<=5_000_000_000L&&!frames[1].nonce.equals(frames[2].nonce)){pair=true;station=f.station;}
        }
    }
}
