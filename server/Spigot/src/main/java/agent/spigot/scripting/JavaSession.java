package agent.spigot.scripting;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Runtime source compiler with per-addon/version/JDK cache and a closeable classloader. */
public final class JavaSession implements AutoCloseable {
    private final URLClassLoader loader;
    private final Class<?> entryType;
    private AddonProgram program;
    private JavaSession(URLClassLoader loader,Class<?> entryType){this.loader=loader;this.entryType=entryType;}
    public static JavaSession prepare(ScriptPackage pkg,String pluginVersion)throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("Java 源码扩展需要完整 JDK；当前环境仅提供 JRE");
        byte[] source=Files.readAllBytes(pkg.entryFile().toPath());
        if(source.length>1024*1024)throw new IOException("Java addon source exceeds 1 MiB");
        String text=new String(source,StandardCharsets.UTF_8);
        Matcher match=Pattern.compile("(?m)^[ \t]*package[ \t]+([A-Za-z_][A-Za-z0-9_.]*)[ \t]*;").matcher(text);
        String simple=pkg.entryFile().getName().substring(0,pkg.entryFile().getName().length()-5);
        String className=(match.find()?match.group(1)+".":"")+simple;
        File output=new File(pkg.dataDir(),"classes");output.mkdirs();
        File marker=new File(output,"source.sha256");
        StringBuilder key=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source))key.append(String.format("%02x",b&255));
        key.append('|').append(System.getProperty("java.version")).append('|').append(pluginVersion);
        String fingerprint=key.toString();
        File compiled=new File(output,className.replace('.',File.separatorChar)+".class");
        if(!marker.isFile()||!new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8).equals(fingerprint)||!compiled.isFile()){
            String cp=System.getProperty("java.class.path","")+File.pathSeparator+
                new File(BirdScriptApi.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath()+File.pathSeparator+
                new File(org.bukkit.Bukkit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
            // Paper/Leaf put API dependencies in a boot URL loader rather than java.class.path.
            for(ClassLoader cl=org.bukkit.Bukkit.class.getClassLoader();cl!=null;cl=cl.getParent())if(cl instanceof URLClassLoader)
                for(URL url:((URLClassLoader)cl).getURLs())if("file".equals(url.getProtocol()))cp+=File.pathSeparator+new File(url.toURI()).getAbsolutePath();
            File[] libraries=new File(pkg.directory(),"lib").listFiles((dir,name)->name.endsWith(".jar"));
            if(libraries!=null)for(File lib:libraries)cp+=File.pathSeparator+lib.getAbsolutePath();
            ByteArrayOutputStream errors=new ByteArrayOutputStream();
            int result=compiler.run(null,errors,errors,"-encoding","UTF-8","-classpath",cp,"-sourcepath",pkg.directory().getAbsolutePath(),"-d",output.getAbsolutePath(),pkg.entryFile().getAbsolutePath());
            if(result!=0)throw new IllegalArgumentException("Java addon 编译失败: "+new String(errors.toByteArray(),StandardCharsets.UTF_8));
            Files.write(marker.toPath(),fingerprint.getBytes(StandardCharsets.UTF_8));
        }
        List<URL> paths=new ArrayList<>();paths.add(output.toURI().toURL());
        File[] libraries=new File(pkg.directory(),"lib").listFiles((dir,name)->name.endsWith(".jar"));
        if(libraries!=null)for(File lib:libraries)paths.add(lib.toURI().toURL());
        URLClassLoader loader=new URLClassLoader(paths.toArray(new URL[0]),JavaSession.class.getClassLoader());
        try {
            return new JavaSession(loader,loader.loadClass(className));
        }catch(Throwable error){loader.close();throw error;}
    }
    public void start(BirdScriptApi api)throws Exception{program=(AddonProgram)entryType.getDeclaredConstructor().newInstance();program.start(api);}
    public void stopProgram()throws Exception{AddonProgram old=program;program=null;if(old!=null)old.stop();}
    public void close()throws Exception{loader.close();}
}
