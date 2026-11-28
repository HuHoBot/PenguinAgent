package agentcheck

import agent.spigot.HuHoBotSpigot
import agent.spigot.manager.ConfigManager
import com.alibaba.fastjson.JSON
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.*

class WebConfigSerializationTest {
    @Test fun authenticatedConfigPayloadContainsOnlyJsonDataNotBukkitSectionObjects() {
        val unsafeField=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }
        val unsafe=unsafeField.get(null) as sun.misc.Unsafe
        val plugin=unsafe.allocateInstance(HuHoBotSpigot::class.java) as HuHoBotSpigot
        val manager=ConfigManager(plugin)
        val text=javaClass.getResourceAsStream("/config.yml")!!.bufferedReader().use { it.readText() }
        val config=YamlConfiguration().apply { loadFromString(text) }
        ConfigManager::class.java.getDeclaredField("active").apply { isAccessible=true }.set(manager,config)
        HuHoBotSpigot::class.java.getDeclaredField("configManager").apply { isAccessible=true }.set(plugin,manager)
        val values=plugin.getWebUiConfigValues()
        assertFalse(values.values.any { it is ConfigurationSection },"JSON must not traverse a Bukkit configuration object graph")
        val json=JSON.parseObject(JSON.toJSONString(values))
        assertEquals("HuHoBot",json.getString("serverName"))
        assertTrue(json.getString("online-list.title").contains("在线列表"))
        assertTrue(agent.web.WebUiSchema.toJson().contains("online-list.title"))
    }
}
