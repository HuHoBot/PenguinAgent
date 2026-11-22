package agent.state

import java.nio.file.Files
import kotlin.test.*

class DataLayoutTest {
    @Test fun legacyStateMovesUnderDataAndConflictsAreNeverOverwritten() {
        val root=Files.createTempDirectory("penguin-data-layout").toFile()
        root.resolve("command-state.ini").writeText("legacy-state")
        root.resolve("permission-groups.yml").writeText("groups: []")
        root.resolve("groups/old/config.yml").apply { parentFile.mkdirs(); writeText("old group") }
        root.resolve("groups/current/config.yml").apply { parentFile.mkdirs(); writeText("root copy") }
        root.resolve("data/groups/current/config.yml").apply { parentFile.mkdirs(); writeText("newer data copy") }
        root.resolve("addons/data/example/kv.properties").apply { parentFile.mkdirs(); writeText("x=1") }

        DataLayout.migrate(root)
        val data=root.resolve("data")
        assertEquals("legacy-state",data.resolve("command-state.ini").readText())
        assertEquals("groups: []",data.resolve("permission-groups.yml").readText())
        assertEquals("old group",data.resolve("groups/old/config.yml").readText())
        assertEquals("newer data copy",data.resolve("groups/current/config.yml").readText())
        assertEquals("root copy",root.resolve("groups/current/config.yml").readText())
        assertEquals("x=1",data.resolve("addons/data/example/kv.properties").readText())
        DataLayout.migrate(root)
        assertEquals("newer data copy",data.resolve("groups/current/config.yml").readText())
    }
    @Test fun migratedMarkdownIsReadThroughTheSameDataPath() {
        val root=Files.createTempDirectory("markdown-data-layout").toFile()
        root.resolve("Markdown/online.md").apply { parentFile.mkdirs();writeText("服主自定义模板") }
        DataLayout.migrate(root)
        val provider=object:agent.provider.ConfigProvider {
            override fun getConfigFile()=root.resolve("config.yml")
            override fun getBotName()="fixture"
            override fun getPlatform()="test"
            override fun getPluginVersion()="1.23"
            override fun getChatFormat()=agent.provider.ChatFormat("", "", false, "")
            override fun getMotd()=agent.provider.Motd("",0,"","",false,false)
        }
        assertEquals("服主自定义模板",provider.getMarkdown("queryOnline"))
        assertFalse(root.resolve("Markdown/online.md").exists())
    }

    @Test fun oldDatabaseJournalCannotAttachToANewerDestinationDatabase() {
        val root=Files.createTempDirectory("sqlite-data-conflict").toFile()
        root.resolve("storage.db").writeText("old database")
        root.resolve("storage.db-wal").writeText("old WAL")
        root.resolve("data/storage.db").apply { parentFile.mkdirs();writeText("new database") }
        DataLayout.migrate(root)
        assertEquals("new database",root.resolve("data/storage.db").readText())
        assertEquals("old WAL",root.resolve("storage.db-wal").readText())
        assertFalse(root.resolve("data/storage.db-wal").exists())
    }

}
