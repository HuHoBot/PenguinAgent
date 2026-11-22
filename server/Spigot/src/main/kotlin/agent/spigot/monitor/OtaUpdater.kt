package agent.spigot.monitor

import agent.spigot.HuHoBotSpigot
import agent.update.ReleasePolicy
import agent.update.FetchRoutes
import com.alibaba.fastjson.JSON
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/** 独立于普通更新提示的固定发布策略。只在服务端 update 目录暂存，运行时绝不替换自身。 */
class OtaUpdater(private val plugin:HuHoBotSpigot) {
    companion object {
        const val MANIFEST="https://raw.githubusercontent.com/HuHoBot/PenguinAgent/master/MEU.json"
        private const val MAX=80L*1024*1024
    }
    private var scheduler:java.util.concurrent.ScheduledExecutorService?=null
    @Volatile private var stopped=false
    @Volatile private var activeConnection:HttpURLConnection?=null
    @Volatile private var lastStatus=0
    fun start(){stop();stopped=false;scheduler=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"Agent-Release").apply { isDaemon=true } }.also {
        it.scheduleWithFixedDelay({runCatching { check() }},180,21600,TimeUnit.SECONDS)
    }}
    fun stop(){
        stopped=true
        activeConnection?.disconnect();activeConnection=null
        scheduler?.shutdownNow()
        runCatching { scheduler?.awaitTermination(5,TimeUnit.SECONDS) }
        scheduler=null
    }
    private fun check(){
        if(stopped)return
        // 404 为正常无策略；其它失败仅重试一次，不写日志、不读取任何服主开关。
        var bytes:ByteArray?=null
        for(route in FetchRoutes.candidates(MANIFEST)) {
            if(stopped)return
            bytes=fetch(route,65536)
            // 主站确认没有 MEU.json 时不能回退到镜像旧策略。
            if(route==MANIFEST && lastStatus==404)return
            if(bytes!=null)break
        }
        if(stopped)return
        val j=bytes?.let { runCatching { JSON.parseObject(String(it,Charsets.UTF_8)) }.getOrNull() } ?: return
        // MEU.json 本身就是强制 OTA 的开关：文件不存在/读取失败时静默忽略；
        // 存在有效版本、下载地址和发布校验时执行，不受 config.yml update-check 影响。
        if(j.getBoolean("enabled")==false)return
        val minimum=listOf("minimumVersion","minimum_version","minVersion","min_version","minimumPluginVersion","minimum_plugin_version","requiredVersion","required_version","minimum")
            .firstNotNullOfOrNull { j.getString(it) }
        val target=listOf("version","latestVersion","targetVersion","latest_version","pluginVersion","plugin_version","latest")
            .firstNotNullOfOrNull { j.getString(it) } ?: minimum ?: return
        val required=minimum ?: target
        if(!ReleasePolicy.needs(plugin.getPluginVersion(),required,target))return
        val keys= listOf("downloadUrl","download_url","jarUrl","jar_url","download","downloadLink","download_link","url")
        val url=keys.firstNotNullOfOrNull { j.getString(it) }?:return
        if(!FetchRoutes.releaseAsset(url))return
        val sha=listOf("sha256","SHA256","checksum").firstNotNullOfOrNull { j.getString(it) }?.lowercase()?:return
        if(!sha.matches(Regex("[a-f0-9]{64}")))return
        val stateFile=plugin.getDataDirectory()?.resolve("release-state.json") ?: plugin.dataFolder.resolve("data/release-state.json")
        val state=runCatching { JSON.parseObject(stateFile.readText()) }.getOrNull()
        if(state?.getString("version")==target && state.getString("sha256")==sha){
            if(state.getLongValue("retryAt")>System.currentTimeMillis()||state.getBooleanValue("pending"))return
        }
        // 在网络下载前落盘退避状态，失败/中断不会高频重复下载。
        stateFile.parentFile.mkdirs()
        val next=com.alibaba.fastjson.JSONObject().apply {
            put("version",target);put("minimumVersion",required);put("sha256",sha.orEmpty())
            put("retryAt",System.currentTimeMillis()+21600000L);put("pending",false)
        }
        atomic(stateFile,next.toJSONString().toByteArray())
        val temp=File(stateFile.parentFile,"release.jar.part")
        runCatching { agent.spigot.scripting.ModuleDownloads.downloadVerified(listOf(url),temp,sha,MAX) }.getOrElse {temp.delete();return}
        if(stopped){temp.delete();return}
        next["sha256"]=sha
        atomic(stateFile,next.toJSONString().toByteArray())
        try {
            val descriptor=JarFile(temp).use { jar ->
                val entry=jar.getJarEntry("plugin.yml")?:return
                org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(jar.getInputStream(entry).bufferedReader().use { it.readText() }) }
            }
            if(descriptor.getString("name")!=plugin.description.name||ReleasePolicy.compare(descriptor.getString("version").orEmpty(),target)!=0)return
            val plugins=plugin.dataFolder.parentFile
            val original=ReleaseFiles.installedJar(plugins,plugin.description.name,plugin.getPluginVersion())?:return
            val updateFolder=runCatching { org.bukkit.Bukkit.getUpdateFolderFile() }.getOrElse { plugins.resolve("update") }
            if(stopped || updateFolder.canonicalFile==plugins.canonicalFile)return
            updateFolder.mkdirs()
            val destination=File(updateFolder,original.name)
            val staged=File(destination.parentFile,destination.name+".part")
            try{
                Files.copy(temp.toPath(),staged.toPath(),StandardCopyOption.REPLACE_EXISTING)
                try{Files.move(staged.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)}
                catch(_:Exception){Files.move(staged.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING)}
            }finally{staged.delete()}
            next["pending"]=true;next["file"]=destination.absolutePath
            atomic(stateFile,next.toJSONString().toByteArray())
        } finally { temp.delete() }
    }
    private fun hex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun atomic(f:File,b:ByteArray){f.parentFile.mkdirs();val t=File(f.parentFile,f.name+".tmp");t.writeBytes(b);Files.move(t.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING)}
    private fun fetch(address:String,limit:Int,release:Boolean=false):ByteArray? {
        var url=address
        lastStatus=0
        repeat(5){
            val u=URL(url)
            if(u.protocol!="https")return null
            if(u.userInfo!=null)return null
            val permitted=listOf("github.com","raw.githubusercontent.com","cdn.jsdelivr.net","api.github.com","gh-proxy.org","ghfast.top","release-assets.githubusercontent.com","objects.githubusercontent.com")
            if(u.host !in permitted)return null
            if(stopped)return null
            val c=u.openConnection() as HttpURLConnection
            activeConnection=c
            try{
                c.connectTimeout=6000;c.readTimeout=15000;c.instanceFollowRedirects=false
                c.setRequestProperty("User-Agent","PenguinAgent")
                val status=c.responseCode
                lastStatus=status
                if(status in 300..399){url=URL(u,c.getHeaderField("Location")?:return null).toString()}
                else{
                    if(status !in 200..299||c.contentLengthLong>limit)return null
                    return c.inputStream.use { i -> val o=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);while(true){val n=i.read(buf);if(n<0)break;if(o.size()+n>limit)return null;o.write(buf,0,n)};o.toByteArray() }
                }
            }catch(_:Exception){return null}finally{activeConnection=null;c.disconnect()}
        };return null
    }
}
