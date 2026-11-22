package org.lambdaproject.lambda;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** App-private, bounded dictionaries. IDs never resolve arbitrary caller paths. */
final class CrackStore {
    static final int MAX_BYTES=64*1024*1024, MAX_WORDS=4000000;
    static File folder(Context c)throws IOException {File f=new File(c.getFilesDir(),"crack-dictionaries");if(!f.isDirectory()&&!f.mkdirs())throw new IOException("dictionary_storage_unavailable");return f;}
    static boolean valid(byte[] word){if(word.length<8||word.length>15)return false;for(byte b:word)if(b<32||b>126)return false;return true;}
    static String hash(File f)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static JSONObject quick()throws Exception {return new JSONObject().put("id","quick").put("name","Probable WPA · 4.800").put("candidates",4800).put("sha256","5dc33214cad9a11eb926de00f5ded20b1a5fc12ba3332319e054713265a35c51").put("path",CrackBenchmark.FIX+"quick-4800.txt");}
    static JSONObject dedicated()throws Exception {return new JSONObject().put("id","probable-long").put("name","Probable WPA · 203.109").put("candidates",203109).put("sha256","5ac6754f8411ce895f69a43ab741606d8634fa6ac88018555aca4d5d745b184d").put("path",CrackBenchmark.FIX+"dedicated-8-15.txt");}
    static JSONObject selected(Context c,String id)throws Exception {
        if(id.equals("quick"))return quick();
        if(id.equals("probable-long"))return dedicated();
        if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid_dictionary_id");
        File root=folder(c),words=new File(root,id+".txt"),meta=new File(root,id+".json");
        if(!words.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!meta.isFile())throw new IOException("dictionary_not_found");
        JSONObject r=new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));
        if(!id.equals(r.optString("sha256"))||!hash(words).equals(id))throw new IOException("dictionary_hash_mismatch");
        return r.put("id",id).put("path",words.getPath());
    }
    static JSONArray list(Context c)throws Exception {JSONArray rows=new JSONArray();JSONObject q=quick();q.remove("path");rows.put(q);JSONObject d=dedicated();d.remove("path");rows.put(d);
        File[] files=folder(c).listFiles();if(files!=null)for(File f:files)if(f.getName().matches("[a-f0-9]{64}\\.json")){
            JSONObject r=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));r.remove("path");rows.put(r);}
        return rows;
    }
    static JSONObject importText(Context c,InputStream input)throws Exception {
        File root=folder(c);File[] existing=root.listFiles((d,n)->n.endsWith(".json"));if(existing!=null&&existing.length>=10)throw new IOException("dictionary_limit_10");
        File tmp=File.createTempFile("import-",".tmp",root);int bytes=0,count=0,skipped=0;ByteArrayOutputStream line=new ByteArrayOutputStream();boolean overflow=false;
        try {
            try(InputStream in=new BufferedInputStream(input);OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){int ch;
                while((ch=in.read())!=-1){if(++bytes>MAX_BYTES)throw new IOException("dictionary_limit_64MiB");
                    if(ch!=10){if(line.size()<128)line.write(ch);else overflow=true;continue;}
                    byte[] word=line.toByteArray();if(word.length>0&&word[word.length-1]==13)word=Arrays.copyOf(word,word.length-1);
                    if(!overflow&&valid(word)){out.write(word);out.write(10);if(++count>MAX_WORDS)throw new IOException("dictionary_limit_4million");}else skipped++;
                    line.reset();overflow=false;
                }
                if(line.size()>0||overflow){byte[] word=line.toByteArray();if(word.length>0&&word[word.length-1]==13)word=Arrays.copyOf(word,word.length-1);if(!overflow&&valid(word)){out.write(word);out.write(10);if(++count>MAX_WORDS)throw new IOException("dictionary_limit_4million");}else skipped++;}
            }
            if(count==0)throw new IOException("dictionary_no_valid_8_15_ascii");
            String hash=hash(tmp);File words=new File(root,hash+".txt");
            if(words.exists())tmp.delete();else if(!tmp.renameTo(words))throw new IOException("dictionary_commit_failed");
            JSONObject result=new JSONObject().put("id",hash).put("sha256",hash).put("name","Diccionario · "+count).put("candidates",count).put("skipped",skipped).put("bytes",words.length());
            android.util.AtomicFile metadata=new android.util.AtomicFile(new File(root,hash+".json"));FileOutputStream output=null;
            try{output=metadata.startWrite();output.write(result.toString().getBytes(StandardCharsets.UTF_8));metadata.finishWrite(output);}
            catch(Exception e){if(output!=null)metadata.failWrite(output);throw e;}
            return result;
        }finally{tmp.delete();}
    }
}
