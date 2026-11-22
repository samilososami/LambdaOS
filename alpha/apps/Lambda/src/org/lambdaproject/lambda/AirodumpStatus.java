package org.lambdaproject.lambda;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Incremental reader for the pinned airodump console header, never SSID rows.
 * The native indication is evidence of detection, not a password/MIC validation.
 */
final class AirodumpStatus implements Closeable {
    private static final Pattern ANSI=Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern HANDSHAKE=Pattern.compile(
        "^ CH [0-9, ]+.*\\[ Elapsed: [^\\r\\n]+\\]\\[ [0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2} \\]\\[ WPA handshake: ([0-9A-Fa-f:]{17}) *$");
    private final File file;private final String bssid;
    private RandomAccessFile in;private final StringBuilder line=new StringBuilder();
    private boolean overlong,detected;
    AirodumpStatus(File file,String bssid){
        if(!bssid.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new IllegalArgumentException("invalid_bssid");
        this.file=file;this.bssid=bssid;
    }
    static boolean headerMatches(String text,String bssid){
        if(text.length()>4096)return false;
        Matcher match=HANDSHAKE.matcher(ANSI.matcher(text).replaceAll(""));
        return match.matches()&&match.group(1).equalsIgnoreCase(bssid);
    }
    boolean poll()throws IOException {
        if(!file.exists())return detected;
        if(in==null)in=new RandomAccessFile(file,"r");
        if(in.length()>2*1024*1024)throw new IOException("airodump_log_size_limit");
        if(in.length()<in.getFilePointer())throw new IOException("airodump_log_truncated");
        byte[] bytes=new byte[8192];int budget=131072;
        while(budget>0){int n=in.read(bytes,0,Math.min(bytes.length,budget));if(n<0)break;budget-=n;
            String chunk=new String(bytes,0,n,StandardCharsets.ISO_8859_1);
            for(int i=0;i<chunk.length();i++){char c=chunk.charAt(i);
                if(c=='\n'){if(!overlong&&headerMatches(line.toString(),bssid))detected=true;line.setLength(0);overlong=false;}
                else if(!overlong){if(line.length()>=4096){line.setLength(0);overlong=true;}else line.append(c);}
            }
        }
        return detected;
    }
    public void close()throws IOException{if(in!=null)in.close();}
}
