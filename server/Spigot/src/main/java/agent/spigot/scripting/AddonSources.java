package agent.spigot.scripting;

import agent.spigot.onlinelist.SkinSources;
import agent.update.FetchRoutes;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Explicit administrator installation. Downloading never activates code; a separate reload is required. */
public final class AddonSources {
    private static final Set<SkinSources> clients=ConcurrentHashMap.newKeySet();
    private static final Semaphore requests=new Semaphore(4);
    private AddonSources(){}
    public static void closeAll(){for(SkinSources c:clients)c.close();clients.clear();}
    public static byte[] download(List<String> addresses,String expected)throws Exception {
        if(expected==null||!expected.matches("(?i)[0-9a-f]{64}"))throw new IllegalArgumentException("必须提供可信的 SHA-256");
        if(!requests.tryAcquire())throw new IOException("已有四项扩展下载正在进行，请稍后重试");
        SkinSources client=new SkinSources(Collections.emptyList(),5000,15000);clients.add(client);
        try{
            for(String original:addresses){
                if(!original.startsWith("https://"))continue;
                for(String url:FetchRoutes.INSTANCE.candidates(original)){
                    byte[] data=client.fetchUrl(url,32*1024*1024);
                    if(data!=null&&digest(data).equalsIgnoreCase(expected))return data;
                }
            }
            throw new IOException("所有下载来源均失败或 SHA-256 不匹配");
        }finally{clients.remove(client);client.close();requests.release();}
    }
    public static String digest(byte[] data)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format("%02x",b&255));return out.toString();
    }
    public static File installZip(File addons,String name,List<String> urls,String sha)throws Exception {
        return unpack(addons,name,download(urls,sha));
    }
    /** Bounded ZIP extractor with canonical path checks. Does not overwrite an installed extension. */
    public static File unpack(File addons,String name,byte[] zip)throws Exception {
        if(name==null||!name.matches("[A-Za-z0-9_-]{1,64}")||Arrays.asList("data","config","engines","files").contains(name.toLowerCase()))throw new IllegalArgumentException("无效或保留的扩展目录名");
        addons.mkdirs();Path target=new File(addons,name).toPath();if(Files.exists(target))throw new IOException("扩展已存在，未覆盖: "+name);
        Path staging=Files.createTempDirectory(addons.toPath(),".download-");
        try{
            long total=0;int count=0;
            try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(zip))){
                for(ZipEntry e;(e=input.getNextEntry())!=null;){
                    if(++count>1000)throw new IOException("扩展归档超过 1000 个条目");
                    String item=e.getName().replace('\\','/');
                    if(item.contains(":")||item.startsWith("/"))throw new IOException("归档含绝对路径");
                    Path out=staging.resolve(item).normalize();if(!out.startsWith(staging))throw new IOException("归档路径越界");
                    if(e.isDirectory()){Files.createDirectories(out);continue;}
                    Files.createDirectories(out.getParent());
                    try(OutputStream stream=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){
                        byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))>=0;){
                            total+=n;if(total>64L*1024*1024)throw new IOException("解压后大小超过 64 MiB");stream.write(buffer,0,n);
                        }
                    }
                }
            }
            File content=staging.toFile();
            if(!hasEntry(content)){
                File[] children=content.listFiles();if(children!=null&&children.length==1&&children[0].isDirectory())content=children[0];
            }
            if(!hasEntry(content))throw new IOException("找不到扩展入口（main.js / main.lua / Main.java 或 metadata.yaml）");
            Files.move(content.toPath(),target);return target.toFile();
        }finally{delete(staging.toFile());}
    }
    private static boolean hasEntry(File folder){
        for(String file:Arrays.asList("main.js","main.lua","Main.java"))if(new File(folder,file).isFile())return true;
        String entry=AddonManifest.from(folder).entry();
        if(entry==null||!(entry.endsWith(".js")||entry.endsWith(".lua")||entry.endsWith(".java")))return false;
        try{File f=new File(folder,entry);return f.isFile()&&f.getCanonicalPath().startsWith(folder.getCanonicalPath()+File.separator);}catch(IOException e){return false;}
    }
    private static void delete(File f){if(!f.exists())return;if(f.isDirectory()){File[] files=f.listFiles();if(files!=null)for(File child:files)delete(child);}f.delete();}
}
