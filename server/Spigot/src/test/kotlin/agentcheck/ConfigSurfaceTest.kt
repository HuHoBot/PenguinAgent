package agentcheck

import agent.web.WebUiSchema
import com.alibaba.fastjson.JSON
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.*

/** Guards the gap the owner keeps hitting: a WebUI field or guide that does not exist in the jar. */
class ConfigSurfaceTest {
    private val config = YamlConfiguration().apply {
        loadFromString(javaClass.getResourceAsStream("/config.yml")!!.bufferedReader().use { it.readText() })
    }

    @Test fun everyEditableWebUiPathExistsInTheShippedConfig() {
        // custom-commands ships as null (an empty opt-in), which Bukkit contains() reports as absent.
        val virtual = setOf("command", "key", "permission", "pushMenu", "custom-commands")
        val paths = mutableListOf<String>()
        fun walk(fields: com.alibaba.fastjson.JSONArray?) {
            if (fields == null) return
            for (i in 0 until fields.size) {
                val field = fields.getJSONObject(i)
                paths.add(field.getString("path"))
                walk(field.getJSONArray("fields"))
            }
        }
        val parsed = JSON.parse(WebUiSchema.toJson())
        val sections = when (parsed) {
            is com.alibaba.fastjson.JSONArray -> parsed
            is com.alibaba.fastjson.JSONObject -> parsed.getJSONArray("sections") ?: parsed.getJSONArray("SECTIONS")
            else -> null
        }
        assertNotNull(sections, "WebUI schema must be an array of sections")
        for (i in 0 until sections.size) walk(sections.getJSONObject(i).getJSONArray("fields"))
        val missing = paths.filterNot { it in virtual || config.contains(it, true) }
        assertTrue(missing.isEmpty(), "WebUI exposes paths missing from config.yml: $missing")
    }

    @Test fun newRuntimeSettingsAndGuidesAreActuallyShipped() {
        for (path in listOf(
            "compatibility.server-mode", "chat-log.enabled", "chat-log.page-size", "chat-log.max-length",
            "chat-log.keep", "chat-log.cooldown-seconds", "online-list.skin.offline-default-skins",
            "message.passive-reply.enhanced-groups", "agent.max-steps", "agent.max-wait-seconds",
            "agent.max-tokens", "agent.timeout-seconds", "agent.system-prompt", "agent.tools"
        )) assertTrue(config.contains(path, true), "missing default: $path")
        assertEquals(15, config.getInt("agent.max-steps"))
        assertEquals(300, config.getInt("agent.max-wait-seconds"))
        assertTrue(config.getBoolean("online-list.skin.offline-default-skins"))
        assertEquals("auto", config.getString("compatibility.server-mode"))
        for (resource in listOf("/Q&A.md", "/Q&A.txt", "/guide/README.txt", "/guide/permission-groups.example.yml",
            "/guide/timed-tasks.example.yml", "/guide/metadata.example.yaml", "/guide/addons-README.txt",
            "/guide/addons-js.example", "/guide/addons-lua.example", "/guide/addons-java.example"))
            assertNotNull(javaClass.getResourceAsStream(resource), "missing resource $resource")
    }
}
