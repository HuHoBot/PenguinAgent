package agentcheck

import agent.spigot.manager.ConfigUpgrade
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.*

class ConfigRetentionTest {
    private val configText=javaClass.getResourceAsStream("/config.yml")!!.bufferedReader().use { it.readText() }

    @Test fun bundledDefaultsAreUniqueAndAlreadyAcceptANoopUpgrade() {
        val upgraded = ConfigUpgrade.transform(configText, configText)
        val c = YamlConfiguration().apply { loadFromString(upgraded) }
        assertEquals(15,c.getInt("agent.max-steps"))
        assertEquals(300,c.getInt("agent.max-wait-seconds"))
        assertEquals(emptyList<String>(),c.getStringList("agent.tools"))
        assertEquals(emptyList<String>(),c.getStringList("message.passive-reply.enhanced-groups"))
        assertEquals(upgraded,ConfigUpgrade.transform(upgraded,configText))
    }

    @Test fun explicitAndLegacyOptionsAreNeverDeletedOnReload() {
        val raw="""# 我自己的服务器配置，不能删
config-version: 18
serverName: '<gradient:#22ccee:#aa66ee>我的服务器</gradient>'
online-list:
  address: ''
  custom-lines: []
  render:
    theme: light
  display:
    name-color: '&d'
  skin:
    connect-timeout-ms: 1234
    read-timeout-ms: 5678
update:
  enabled: false
  custom-setting: keep-me
my-own-key: keep-me-too
"""
        val out=ConfigUpgrade.transform(raw,configText)
        val c=YamlConfiguration().apply { loadFromString(out) }
        assertEquals("<gradient:#22ccee:#aa66ee>我的服务器</gradient>",c.getString("serverName"))
        assertEquals("light",c.getString("online-list.render.theme"))
        assertEquals("&d",c.getString("online-list.display.name-color"))
        assertEquals(1234,c.getInt("online-list.skin.connect-timeout-ms"))
        assertEquals(5678,c.getInt("online-list.skin.read-timeout-ms"))
        assertEquals("keep-me",c.getString("update.custom-setting"))
        assertEquals("keep-me-too",c.getString("my-own-key"))
        assertTrue(out.contains("我自己的服务器配置"))
        assertTrue(out.contains("title: '<gradient:#62d6df:#ad9cff>在线列表</gradient>'"))
        assertEquals(19,c.getInt("config-version"))
        assertEquals(out,ConfigUpgrade.transform(out,configText))
    }

    @Test fun earliestConfigUpgradesToEveryCurrentKeyWithoutLosingValues() {
        val earliest = "config-version: 1\nserverName: 'Legacy Name'\nbot:\n  app-id: '123456'\n  groups:\n  - OLDGROUP\n"
        val upgraded = ConfigUpgrade.transform(earliest, configText)
        val current = YamlConfiguration().apply { loadFromString(upgraded) }
        val defaults = YamlConfiguration().apply { loadFromString(configText) }
        val missing = defaults.getValues(true).keys.filter { key ->
            defaults.get(key) !is org.bukkit.configuration.ConfigurationSection &&
                !key.contains('.') || true
        }.filter { key ->
            defaults.get(key) !is org.bukkit.configuration.ConfigurationSection &&
                !current.contains(key, true) && !current.contains(key, false)
        }
        assertTrue(missing.isEmpty(), "missing keys after upgrade: ${missing.take(10)}")
        assertEquals("Legacy Name", current.getString("serverName"))
        assertEquals("123456", current.getString("bot.app-id"))
        assertEquals(listOf("OLDGROUP"), current.getStringList("bot.groups"))
        assertEquals(ConfigUpgrade.VERSION, current.getInt("config-version"))
    }

    @Test fun patchingZeroIndentListsAndFoldedScalarsStaysValidYaml() {
        val raw = """config-version: 19
bot:
  groups:
  - one
  - two
agent:
  system-prompt: |-
    first line
    second line
"""
        val patched = ConfigUpgrade.patch(raw, mapOf(
            "bot.groups" to listOf("a", "b"),
            "agent.system-prompt" to "single line"
        ))
        val c = YamlConfiguration().apply { loadFromString(patched) }
        assertEquals(listOf("a", "b"), c.getStringList("bot.groups"))
        assertEquals("single line", c.getString("agent.system-prompt"))
        // Applying the same patch again must remain idempotent and parseable.
        assertNotNull(YamlConfiguration().apply { loadFromString(ConfigUpgrade.patch(patched, mapOf("bot.groups" to listOf("a")))) }
            .getStringList("bot.groups"))
    }

    @Test fun legacyAmpersandColourIsQuotedInsteadOfParsedAsYamlAnchor() {
        val out=ConfigUpgrade.transform("config-version: 18\nserverName: &b我的服务器\n",configText)
        val c=YamlConfiguration().apply { loadFromString(out) }
        assertEquals("&b我的服务器",c.getString("serverName"))
        assertTrue(out.contains("serverName: '&b我的服务器'"))
    }

    @Test fun invalidYamlIsRejectedBeforeTheFileCanBeReplaced() {
        assertFails { ConfigUpgrade.transform("config-version: 18\nserverName: [bad\n",configText) }
    }

    @Test fun configFieldsHaveNearbyChineseHelpRatherThanOnlyAnExternalManual() {
        val rows=configText.lines();val keys=rows.indices.filter { rows[it].matches(Regex("^ *[A-Za-z][A-Za-z0-9_-]*:.*")) }
        val covered=keys.count { i-> i>0 && rows.take(i).asReversed().dropWhile { it.isBlank() }.firstOrNull()?.trimStart()?.startsWith("#")==true }
        assertTrue(covered>=keys.size*0.9,"only $covered / ${keys.size} fields have nearby comments")
        assertTrue(configText.contains("serverName: '"))
    }

    @Test fun onlineListHeadingIsConfigurableRichText() {
        val c=YamlConfiguration().apply { loadFromString(configText) }
        assertTrue(c.getString("online-list.title").orEmpty().isNotBlank())
    }
    @Test fun apiPatchPreservesUnchangedCommentsUnknownKeysAndUnicodeOffsets() {
        val raw = "# 私人注释 😀\nserverName: '&b原名' # 名称备注\nbot:\n  secret: 'old' # 密钥备注\nunknown: keep\n"
        val changed = ConfigUpgrade.patch(raw, mapOf("bot.secret" to "new", "online-list.title" to "<red>标题</red>"))
        val c = YamlConfiguration().apply { loadFromString(changed) }
        assertEquals("new",c.getString("bot.secret"))
        assertEquals("<red>标题</red>",c.getString("online-list.title"))
        assertTrue(changed.contains("# 私人注释 😀"))
        assertTrue(changed.contains("# 密钥备注"))
        assertTrue(changed.contains("serverName: '&b原名' # 名称备注"))
        assertTrue(changed.contains("unknown: keep"))
    }

    @Test fun multilineNameAndPromptBodiesAreNotRewritten() {
        val raw = "config-version: 19\nserverName: |-\n  我的服务器\n  第二行\nagent:\n  system-prompt: |\n    serverName: &b这只是提示词示例\n"
        val output = ConfigUpgrade.transform(raw,configText)
        val c = YamlConfiguration().apply { loadFromString(output) }
        assertEquals("我的服务器\n第二行",c.getString("serverName"))
        assertTrue(c.getString("agent.system-prompt")!!.contains("serverName: &b这只是提示词示例"))
    }

    @Test fun ambiguousDuplicateKeysAndRecursiveAliasesAreRejectedWithoutWriting() {
        assertFails { ConfigUpgrade.transform("serverName: first\nserverName: second\n",configText) }
        assertFails { ConfigUpgrade.transform("loop: &loop {next: *loop}\n",configText) }
        assertFails { ConfigUpgrade.transform("- not-a-root-map\n",configText) }
    }

    @Test fun invalidFileDoesNotCreateABackupOrOverwriteOriginal() {
        val root=java.nio.file.Files.createTempDirectory("config-invalid-file").toFile()
        val file=root.resolve("config.yml")
        val original="config-version: 18\nserverName: [invalid\n"
        file.writeText(original)
        assertFails { ConfigUpgrade.apply(file,configText) }
        assertEquals(original,file.readText())
        assertFalse(root.resolve("data/backups").exists())
    }

    @Test fun unchangedReloadReusesPreparedInventoryBackgrounds() {
        val folder=java.nio.file.Files.createTempDirectory("reuse-inventory-backgrounds").toFile()
        agent.spigot.inventory.InventoryRenderer.init(folder)
        val count=agent.spigot.inventory.InventoryRenderer.backgroundPreparations.get()
        repeat(3) { agent.spigot.inventory.InventoryRenderer.init(folder) }
        assertEquals(count,agent.spigot.inventory.InventoryRenderer.backgroundPreparations.get())
    }

    @Test fun closingSkinConnectionsDoesNotWaitForHttpReadLocks() {
        val sources=agent.spigot.onlinelist.SkinSources()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val connection=object:java.net.HttpURLConnection(java.net.URL("https://example.com/")) {
            override fun disconnect() { entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS) }
            override fun usingProxy()=false
            override fun connect() {}
        }
        val field=sources.javaClass.getDeclaredField("connections").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") val connections=field.get(sources) as MutableSet<java.net.HttpURLConnection>
        connections.add(connection)
        val call=java.util.concurrent.CompletableFuture.runAsync { sources.close() }
        try {
            call.get(500,java.util.concurrent.TimeUnit.MILLISECONDS)
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

}
