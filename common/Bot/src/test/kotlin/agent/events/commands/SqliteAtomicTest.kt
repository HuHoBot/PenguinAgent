package agent.events.commands

import agent.storage.SqliteStateStore as Store
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SqliteAtomicTest {
    @AfterTest fun close(){Store.close()}
    @Test fun walSnapshotSurvivesReopenAndEmptyBindingsAreStillAnInitialisedState(){
        val dir=Files.createTempDirectory("sql-state").toFile()
        assertTrue(Store.open(dir))
        assertFalse(Store.hasState())
        assertTrue(Store.writeSnapshot(mapOf("administrators" to mapOf("group" to "member")),emptyMap()))
        assertTrue(Store.hasState());Store.close();assertTrue(Store.open(dir))
        assertTrue(Store.hasState());assertTrue(Store.readBindings().isEmpty())
        assertEquals(mapOf("group" to "member"),Store.readSection("administrators"))
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("storage.db")}").use { conn ->
            conn.createStatement().use { st->st.executeQuery("PRAGMA journal_mode").use { assertTrue(it.next());assertEquals("wal",it.getString(1)) } }
        }
    }
    @Test fun failedBindingInsertRollsBackOtherTablesInSameSnapshot(){
        val dir=Files.createTempDirectory("sql-rollback").toFile();assertTrue(Store.open(dir))
        assertTrue(Store.writeSnapshot(mapOf("administrators" to mapOf("old" to "kept")),emptyMap()))
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("storage.db")}").use { c->c.createStatement().use {
            it.execute("CREATE TRIGGER reject_bad BEFORE INSERT ON user_bindings WHEN NEW.openid='bad' BEGIN SELECT RAISE(ABORT,'rejected');END;")
        } }
        assertFalse(Store.writeSnapshot(mapOf("administrators" to mapOf("new" to "must rollback")),mapOf("bad" to Store.BindingRow(listOf("Astra"),"QQ","MC","Test"))))
        assertEquals(mapOf("old" to "kept"),Store.readSection("administrators"));assertTrue(Store.readBindings().isEmpty())
    }
    @Test fun unknownFutureSchemaIsNotDowngraded(){
        val dir=Files.createTempDirectory("sql-future").toFile();assertTrue(Store.open(dir));Store.close()
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("storage.db")}").use { c->c.createStatement().use { it.execute("INSERT OR REPLACE INTO meta(k,v)VALUES('schema-version','99')") } }
        assertFalse(Store.open(dir))
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("storage.db")}").use { c->c.createStatement().use { st->st.executeQuery("SELECT v FROM meta WHERE k='schema-version'").use { assertTrue(it.next());assertEquals("99",it.getString(1)) } } }
    }
}
