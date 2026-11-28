package agent.spigot.scripting;

import agent.update.FetchRoutes;
import java.awt.Font;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

/** Streaming optional-module installer. No network, hashing or font parsing may run on Bukkit's main thread. */
public final class ModuleDownloads {
    private static final Set<HttpURLConnection> active=ConcurrentHashMap.newKeySet();
    private static final Semaphore slots=new Semaphore(2);
    private static volatile boolean stopped;
    private static final java.util.concurrent.atomic.AtomicLong epoch=new java.util.concurrent.atomic.AtomicLong();
    public static void beginLifecycle(){epoch.incrementAndGet();stopped=false;}
    private ModuleDownloads(){}
    private static void workerOnly(){
        if(org.bukkit.Bukkit.getServer()!=null&&agent.spigot.manager.FoliaScheduler.isTickThread())throw new IllegalStateException("Module IO must run on a worker");
    }
    public static void stop(){stopped=true;epoch.incrementAndGet();for(HttpURLConnection c:active)c.disconnect();active.clear();}
    private static boolean validId(String id){return id.matches("font-[A-Za-z0-9_-]{1,48}");}
    private static void publicHttps(URL u)throws Exception {
        if(!"https".equals(u.getProtocol())||u.getUserInfo()!=null)throw new IOException("模块来源必须是无用户信息的 HTTPS 地址");
        for(InetAddress ip:InetAddress.getAllByName(u.getHost())){
            byte[] b=ip.getAddress();boolean ula=b.length==16&&(b[0]&254)==252;
            if(ula||ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isSiteLocalAddress()||ip.isLinkLocalAddress()||ip.isMulticastAddress())throw new IOException("模块下载不允许内网或本机地址");
        }
    }
    public static String stage(File data,String id,String version,List<String> sources,String sha)throws Exception {
        workerOnly();long token=epoch.get();
        if(!validId(id)||!sha.matches("(?i)[0-9a-f]{64}"))throw new IllegalArgumentException("模块名或 SHA-256 无效");
        if(!slots.tryAcquire())throw new IOException("已有两个大模块正在下载");
        File pending=new File(data,"modules/pending");pending.mkdirs();File temporary=File.createTempFile(id+"-",".part",pending);
        try{
            Exception last=new IOException("没有可用下载源");boolean success=false;
            outer:for(String source:sources)for(String route:FetchRoutes.INSTANCE.candidates(source)){
                if(stopped||token!=epoch.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("模块下载已取消");
                try{
                    download(route,temporary,sha,id.startsWith("font-")?48L*1024*1024:256L*1024*1024,token);
                    validate(id,temporary);success=true;break outer;
                }catch(Exception e){last=e;}
            }
            if(!success)throw last;
            File binary=new File(pending,id+".bin");move(temporary,binary);
            Properties meta=new Properties();meta.setProperty("id",id);meta.setProperty("sha256",sha.toLowerCase(Locale.ROOT));meta.setProperty("version",version);
            File markerTemp=new File(pending,id+".properties.tmp");try(OutputStream out=new FileOutputStream(markerTemp)){meta.store(out,"Verified pending module; applied only on restart");}
            move(markerTemp,new File(pending,id+".properties"));return id+" 已下载并校验，重启后启用（"+binary.length()+" 字节）";
        }finally{temporary.delete();slots.release();}
    }
    public static void downloadVerified(List<String> sources,File file,String sha,long max)throws Exception{
        workerOnly();if(!sha.matches("(?i)[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid checksum");
        long token=epoch.get();Exception last=new IOException("所有下载源均失败");
        for(String source:sources)for(String route:FetchRoutes.INSTANCE.candidates(source)){
            if(stopped||token!=epoch.get())throw new InterruptedIOException("插件正在停止");
            try{download(route,file,sha,Math.min(max,256L*1024*1024),token);return;}catch(Exception error){last=error;}
        }
        throw last;
    }
    private static void download(String address,File file,String sha,long max,long token)throws Exception {
        URL current=new URL(address);long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(8);
        for(int redirects=0;redirects<6;redirects++){
            publicHttps(current);HttpURLConnection c=(HttpURLConnection)current.openConnection();active.add(c);
            try{
                c.setConnectTimeout(5000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","HuHoBot-ModuleDownload");
                c.setRequestProperty("Accept",current.getHost().equals("api.github.com")&&current.getPath().contains("/contents/")?"application/vnd.github.raw+json":"application/octet-stream");
                int status=c.getResponseCode();
                if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("重定向缺少地址");current=new URL(current,location);continue;}
                if(status!=200)throw new IOException("模块服务器返回 HTTP "+status);
                if(c.getContentLengthLong()>max)throw new IOException("模块超过大小限制");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
                try(InputStream input=c.getInputStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){
                    byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;){
                        if(stopped||token!=epoch.get()||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new InterruptedIOException("模块下载超时或已取消");
                        total+=n;if(total>max)throw new IOException("模块超过大小限制");digest.update(buffer,0,n);out.write(buffer,0,n);
                    }
                }
                if(!hex(digest.digest()).equalsIgnoreCase(sha))throw new IOException("模块 SHA-256 不匹配，未启用");return;
            }finally{active.remove(c);c.disconnect();}
        }
        throw new IOException("模块重定向过多");
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
    private static String sha(File f)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)d.update(b,0,n);}return hex(d.digest());
    }
    private static void validate(String id,File file)throws Exception{
        try(InputStream in=new FileInputStream(file)){Font.createFont(Font.TRUETYPE_FONT,in);}
    }
    /** Called by startup workers before any external engines/fonts are opened. */
    public static synchronized void applyPending(File data,java.util.function.Consumer<String> warn){
        workerOnly();if(stopped)return;File folder=new File(data,"modules/pending");File[] markers=folder.listFiles((d,n)->n.endsWith(".properties"));if(markers==null)return;
        for(File marker:markers)try{
            Properties p=new Properties();try(InputStream in=new FileInputStream(marker)){p.load(in);}
            String id=p.getProperty("id","");String hash=p.getProperty("sha256","");String version=p.getProperty("version","");
            if(!validId(id)||!version.matches("[A-Za-z0-9_.-]{1,48}")||!hash.matches("[a-f0-9]{64}"))throw new IOException("待启用模块元数据无效");
            File source=new File(folder,id+".bin");if(!sha(source).equals(hash))throw new IOException("待启用模块内容已变化");validate(id,source);
            File target=new File(data,"fonts/"+id+".ttf");
            target.getParentFile().mkdirs();
            move(source,target);marker.delete();
        }catch(Exception error){warn.accept("模块暂存未启用: "+error.getMessage());}
    }
    private static void move(File from,File to)throws IOException{
        try{Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
}
