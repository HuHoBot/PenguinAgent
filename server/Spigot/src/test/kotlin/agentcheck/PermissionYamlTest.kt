package agentcheck

import agent.state.PermissionGroupsStore
import agent.state.SimpleYaml
import java.nio.file.Files
import kotlin.test.*

class PermissionYamlTest {
 @Test fun yamlReadersSeparateNestedAndInlineMemberListsForEachGroup(){
   val root=Files.createTempDirectory("permission-guide").toFile()
   val file=root.resolve("permission-groups.yml")
   file.writeText("""# operators and regular builders
 groups:
   - name: builders
     members:
       - 'member-one'
       - 'member-two'
     commands: ["查在线", "查信息"] # inline style
     admin: false
   - name: ops
     members: ['admin-one']
     commands: []
     admin: true
 """.trimIndent())
   PermissionGroupsStore.configure(root)
   assertEquals(listOf("member-one","member-two"),PermissionGroupsStore.all()[0].members)
   assertEquals(listOf("查在线","查信息"),PermissionGroupsStore.all()[0].commands)
   assertEquals(listOf("admin-one"),PermissionGroupsStore.all()[1].members)
   assertTrue(PermissionGroupsStore.isAdminByGroup("admin-one"))
   assertFalse(PermissionGroupsStore.isAdminByGroup("member-one"))
 }
}
