package agentcheck

import agent.spigot.manager.ConfigGuide
import agent.state.SimpleYaml
import java.nio.file.Files
import kotlin.test.*

class GuideGenerationTest {
    @Test fun firstRunCreatesSeparateSafeGuidesAndNeverActivatesExamples() {
        val root = Files.createTempDirectory("config-guide").toFile()
        try {
            val data = root.resolve("data")
            ConfigGuide.ensure(data)
            val guide = data.resolve("guide")
            assertTrue(data.resolve("Q&A.txt").isFile)
            assertTrue(guide.resolve("README.txt").readText().contains("member_openid"))
            val groups = SimpleYaml.parse(guide.resolve("permission-groups.example.yml").readText())["groups"] as List<*>
            assertEquals(2,groups.size)
            assertEquals(false,(groups[0] as Map<*,*>)["admin"])
            assertEquals(true,(groups[1] as Map<*,*>)["admin"])
            assertFalse(data.resolve("permission-groups.yml").exists())
            assertFalse(data.resolve("timed-tasks.yml").exists())
            assertEquals(listOf("README.txt"),root.resolve("addons").listFiles()!!.map { it.name })
            for (name in listOf("addons-js.example","addons-lua.example","addons-java.example","metadata.example.yaml"))
                assertTrue(guide.resolve(name).length() > 100,name)
        } finally { root.deleteRecursively() }
    }

    @Test fun generatedHeadingDoesNotAllowOverwritingOwnerChanges() {
        val root = Files.createTempDirectory("config-guide-marker").toFile()
        try {
            val data = root.resolve("data")
            ConfigGuide.ensure(data)
            val file = data.resolve("guide/permission-groups.example.yml")
            val customised = file.readText()+"\n# My server's own instructions must survive.\n"
            file.writeText(customised)
            ConfigGuide.ensure(data)
            assertEquals(customised,file.readText())
            assertTrue(file.resolveSibling(file.name+".new").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun editedGuideRemainsInPlaceWithANewTemplateBesideIt() {
        val root = Files.createTempDirectory("config-guide-keep").toFile()
        try {
            val data = root.resolve("data")
            ConfigGuide.ensure(data)
            val file = data.resolve("guide/permission-groups.example.yml")
            file.writeText("# Owner instructions\ngroups: []\n")
            ConfigGuide.ensure(data)
            assertEquals("# Owner instructions\ngroups: []\n",file.readText())
            assertTrue(file.resolveSibling(file.name+".new").readText().contains("member_openid"))
        } finally { root.deleteRecursively() }
    }
}
