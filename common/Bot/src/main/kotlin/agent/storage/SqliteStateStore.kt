package agent.storage

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite 状态存储（可选后端）。
 *
 * 设计：
 * - 只承担「绑定 / 管理员 / 认证用户 / 群设置 / 角色缓存」这类键值型状态；
 * - 表结构与 command-state.ini 的段一一对应，INI 文件仍然照常写盘（人肉可读、兜底可导），
 *   SQLite 作为优先数据源（storage.type=sqlite 时 load 优先从库读）；
 * - 单连接 + 锁：MC 插件进程内写频率不高（绑定变更 / 角色缓存刷新），单连接串行足够，
 *   避免多连接带来的 WAL 配置与文件锁问题；开启 WAL 提升读并发。
 * - 所有异常都不抛出调用链：写失败仅记日志，内存快照仍是权威，保证“存储坏了不影响运行”。
 */
object SqliteStateStore {

    private const val SCHEMA_VERSION = 1

    private val lock = ReentrantLock()
    private var connection: Connection? = null
    private var available = false

    fun open(dataDirectory: File, logWarning: (String) -> Unit = {}, logInfo: (String) -> Unit = {}): Boolean =
        lock.withLock {
            if (available) return@withLock true
            var candidate:Connection?=null
            try {
                val file = File(dataDirectory, "storage.db")
                file.parentFile?.mkdirs()
                // sqlite-jdbc 通过 ServiceLoader 注册驱动；显式加载一次保证老 JDK 也可用
                try { Class.forName("org.sqlite.JDBC") } catch (_: Throwable) {}
                val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
                candidate=conn
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA synchronous=NORMAL")
                    st.execute("PRAGMA busy_timeout=5000")
                    st.execute("PRAGMA cache_size=-4096")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)"
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS kv_sections (" +
                            "section TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, " +
                            "PRIMARY KEY (section, key))"
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS user_bindings (" +
                            "openid TEXT PRIMARY KEY, player_names TEXT NOT NULL, " +
                            "qq_mode TEXT NOT NULL, mc_mode TEXT NOT NULL, qq_username TEXT NOT NULL DEFAULT '', " +
                            "locked_names TEXT NOT NULL DEFAULT '')"
                    )
                }
                val schema=conn.prepareStatement("SELECT v FROM meta WHERE k='schema-version'").use { q -> q.executeQuery().use { r -> if(r.next())r.getString(1).toIntOrNull()?:0 else 0 } }
                if(schema>SCHEMA_VERSION)throw IllegalStateException("数据库结构 $schema 高于当前可读版本 $SCHEMA_VERSION，未降写数据库")
                connection = conn
                available = true
                logInfo("SQLite 存储已启用: ${file.name}")
                true
            } catch (error: Throwable) {
                runCatching { candidate?.close() }
                logWarning("SQLite 存储初始化失败（回退到文件存储）: ${error.message}")
                available = false
                false
            }
        }

    fun isAvailable(): Boolean = available

    fun close() = lock.withLock {
        runCatching { connection?.close() }
        connection = null
        available = false
    }

    /** 读取一个 section 的全部键值（INI 段兼容：administrators / authenticated-users / ...）。 */
    fun readSection(section: String): LinkedHashMap<String, String> = lock.withLock {
        val out = LinkedHashMap<String, String>()
        if (!available) return@withLock out
        try {
            connection?.prepareStatement("SELECT key, value FROM kv_sections WHERE section = ?")?.use { st ->
                st.setString(1, section)
                st.executeQuery().use { rs ->
                    while (rs.next()) out[rs.getString(1)] = rs.getString(2) ?: ""
                }
            }
        } catch (_: Throwable) {}
        out
    }

    private fun transaction(block:(Connection)->Unit):Boolean = lock.withLock {
        val conn=connection?:return@withLock false
        if(!available)return@withLock false
        try {
            conn.autoCommit=false
            block(conn)
            conn.commit();true
        }catch(_:Throwable){
            runCatching { conn.rollback() };false
        }finally{runCatching { conn.autoCommit=true }}
    }
    private fun replaceSection(conn:Connection,section:String,values:Map<String,String>){
        conn.prepareStatement("DELETE FROM kv_sections WHERE section=?").use { it.setString(1,section);it.executeUpdate() }
        conn.prepareStatement("INSERT INTO kv_sections(section,key,value)VALUES(?,?,?)").use { st ->
            values.forEach { (k,v)->st.setString(1,section);st.setString(2,k);st.setString(3,v);st.addBatch() };st.executeBatch()
        }
    }
    private fun replaceBindings(conn:Connection,bindings:Map<String,BindingRow>){
        conn.createStatement().use { it.executeUpdate("DELETE FROM user_bindings") }
        conn.prepareStatement("INSERT INTO user_bindings(openid,player_names,qq_mode,mc_mode,qq_username,locked_names)VALUES(?,?,?,?,?,?)").use { st ->
            bindings.forEach { (id,row)->
                st.setString(1,id);st.setString(2,row.playerNames.joinToString(","));st.setString(3,row.qqMode)
                st.setString(4,row.mcMode);st.setString(5,row.qqUsername);st.setString(6,row.lockedNames.joinToString(","));st.addBatch()
            };st.executeBatch()
        }
    }
    fun writeSection(section:String,values:Map<String,String>):Boolean=transaction { replaceSection(it,section,values) }
    fun writeBindings(bindings:Map<String,BindingRow>):Boolean=transaction { replaceBindings(it,bindings) }

    /** One transaction for all state tables: errors never commit half a snapshot. */
    fun writeSnapshot(sections:Map<String,Map<String,String>>,bindings:Map<String,BindingRow>):Boolean=transaction { conn ->
        sections.forEach { (name,values)->replaceSection(conn,name,values) }
        replaceBindings(conn,bindings)
        conn.prepareStatement("INSERT OR REPLACE INTO meta(k,v)VALUES(?,?)").use { st ->
            for((k,v)in listOf("schema-version" to SCHEMA_VERSION.toString(),"state-initialized" to "1")){
                st.setString(1,k);st.setString(2,v);st.addBatch()
            };st.executeBatch()
        }
    }
    fun hasState():Boolean=lock.withLock {
        val conn=connection?:return@withLock false
        runCatching {
            conn.createStatement().use { st -> st.executeQuery("SELECT 1 FROM meta WHERE k='state-initialized' AND v='1' UNION ALL SELECT 1 FROM kv_sections UNION ALL SELECT 1 FROM user_bindings LIMIT 1").use { it.next() } }
        }.getOrDefault(false)
    }

    fun readBindings(): LinkedHashMap<String, BindingRow> = lock.withLock {
        val out = LinkedHashMap<String, BindingRow>()
        if (!available) return@withLock out
        try {
            connection?.prepareStatement(
                "SELECT openid, player_names, qq_mode, mc_mode, qq_username, locked_names FROM user_bindings"
            )?.use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        out[rs.getString(1)] = BindingRow(
                            (rs.getString(2) ?: "").split(',').filter(String::isNotEmpty),
                            rs.getString(3) ?: "QQ",
                            rs.getString(4) ?: "MC",
                            rs.getString(5) ?: "",
                            (rs.getString(6) ?: "").split(',').filter(String::isNotEmpty)
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
        out
    }

    data class BindingRow(
        val playerNames: List<String>,
        val qqMode: String,
        val mcMode: String,
        val qqUsername: String,
        val lockedNames: List<String> = emptyList()
    )
}
