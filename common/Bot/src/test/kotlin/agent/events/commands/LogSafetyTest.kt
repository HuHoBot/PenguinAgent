package agent.events.commands
import agent.provider.LogSafety
import kotlin.test.*
class LogSafetyTest {
    @Test fun credentialsAreNotPrintedInSdkDiagnostics(){
        for(text in listOf("api-key=abc123, status=ok","{\"access_token\":\"abc123\"}","Authorization: Bearer abc123","password:abc123","secret='abc123'")){
            assertFalse(LogSafety.clean(text).contains("abc123"),text)
        }
        assertEquals("server TPS=20",LogSafety.clean("server TPS=20"))
    }
}
