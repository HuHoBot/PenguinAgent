package agent.spigot.monitor

import java.io.File
import java.util.jar.JarFile
import org.bukkit.configuration.file.YamlConfiguration

/** Match the real installed JAR, not Paper's remapped classloader cache. Ambiguity fails closed. */
object ReleaseFiles {
    fun installedJar(folder:File,pluginName:String,currentVersion:String):File? {
        val matches=folder.listFiles { f -> f.isFile && f.extension.equals("jar",true) }.orEmpty().filter { file ->
            runCatching {
                JarFile(file).use { jar ->
                    val entry=jar.getJarEntry("plugin.yml")?:return@use false
                    if(entry.size>65536)return@use false
                    val text=jar.getInputStream(entry).bufferedReader().use { it.readText() }
                    val config=YamlConfiguration().apply { loadFromString(text) }
                    config.getString("name")==pluginName && config.getString("version")==currentVersion
                }
            }.getOrDefault(false)
        }
        return matches.singleOrNull()
    }
}
