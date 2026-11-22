package io.github.kloping.qqbot.network.hookauth

import com.google.gson.JsonParser
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.network.WssWorker
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Hex
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookValidationTest {
    private val secret = "local-fixture-key"
    private val key = Ed25519PrivateKeyParameters(secret.repeat(2).toByteArray(), 0)
    private val time = "1795171200"
    private fun sign(value: ByteArray): String {
        val signer = Ed25519Signer().apply { init(true, key); update(value, 0, value.size) }
        return Hex.toHexString(signer.generateSignature())
    }
    private fun scoped(test: (HookAuth, AtomicInteger) -> Unit) {
        val n = AtomicInteger()
        val hook = HookAuth().apply {
            config = Starter.Config().apply { appid = "local-test"; setSecret(this@WebhookValidationTest.secret); webhookpath = "/fixture" }
            wssWorker = WssWorker().also { worker -> worker.getOnPackReceives().add { n.incrementAndGet(); false } }
        }
        hook.start(InetSocketAddress("127.0.0.1", 0))
        try { test(hook, n) } finally { hook.webhookServerStop() }
        assertNull(hook.httpServer)
    }
    private fun request(hook: HookAuth, data: ByteArray, signed: Boolean = true, chunked: Boolean = false): Pair<Int, String> {
        val con = URL("http://127.0.0.1:${hook.httpServer.address.port}/fixture").openConnection() as HttpURLConnection
        con.connectTimeout = 3000
        con.readTimeout = 3000
        con.requestMethod = "POST"
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json")
        if (signed) {
            con.setRequestProperty("X-Signature-Timestamp", time)
            con.setRequestProperty("X-Signature-Ed25519", sign(time.toByteArray() + data))
        }
        if (chunked) con.setChunkedStreamingMode(8192) else con.setFixedLengthStreamingMode(data.size)
        return try {
            try { con.outputStream.use { it.write(data) } } catch (e: java.io.IOException) {
                if (data.size <= HookAuth.MAX_BODY_BYTES) throw e
            }
            val status = con.responseCode
            val stream = if (status >= 400) con.errorStream else con.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { con.disconnect() }
    }
    @Test fun signedEventConservesUnicodeAndReturnsProtocolAck() = scoped { hook, n ->
        val data = """{ "op": 0, "id": "event-1", "t": "LOCAL_FIXTURE", "d": { "text": "中🌿" } }""".toByteArray()
        assertEquals(200 to "{\"op\":12,\"d\":0}", request(hook, data))
        assertEquals(200 to "{\"op\":12,\"d\":0}", request(hook, data))
        assertEquals(1, n.get(), "a valid signed retry should be acknowledged without redispatch")
    }
    @Test fun missingSignatureCannotReachTheDispatcher() = scoped { hook, n ->
        val data = """{"op":0,"t":"LOCAL_FIXTURE","d":{}}""".toByteArray()
        assertEquals(403, request(hook, data, signed = false).first)
        assertEquals(0, n.get())
    }
    @Test fun signedChallengeReturnsEscapedJsonAndTheCorrectSignature() = scoped { hook, n ->
        val data = """{"op":13,"d":{"plain_token":"quote\"-测试","event_ts":"$time"}}""".toByteArray()
        val (status, body) = request(hook, data)
        val returned = JsonParser.parseString(body).asJsonObject
        assertEquals(200, status)
        val token = "quote\"-测试"
        assertEquals(token, returned.get("plain_token").asString)
        assertEquals(sign((time + token).toByteArray()), returned.get("signature").asString)
        assertEquals(0, n.get())
    }
    @Test fun modifiedRawBodyCannotReachTheEventDispatcher() = scoped { hook, n ->
        val original = """{\"op\":0,\"t\":\"LOCAL_FIXTURE\",\"d\":{}}""".toByteArray()
        val tampered = """{\"op\":0,\"t\":\"FORGED\",\"d\":{}}""".toByteArray()
        val con = URL("http://127.0.0.1:${hook.httpServer.address.port}/fixture").openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true
        con.setRequestProperty("X-Signature-Timestamp", time)
        con.setRequestProperty("X-Signature-Ed25519", sign(time.toByteArray() + original))
        con.setFixedLengthStreamingMode(tampered.size)
        try { con.outputStream.use { it.write(tampered) }; assertEquals(403, con.responseCode) } finally { con.disconnect() }
        assertEquals(0, n.get())
    }
    @Test fun repeatedEventIdWithinRetryWindowIsDispatchedOnlyOnce() = scoped { hook, n ->
        val data = """{ "op": 0, "id": "retry-me", "t": "LOCAL_FIXTURE", "d": {} }""".toByteArray()
        repeat(4) { assertEquals(200, request(hook, data).first) }
        assertEquals(1, n.get())
    }
    @Test fun invalidSignatureCannotDispatchEvenWhenPayloadIsOtherwiseValid() = scoped { hook,n ->
        val data="""{"op":0,"id":"forged","t":"LOCAL_FIXTURE","d":{}}""".toByteArray()
        val con=URL("http://127.0.0.1:${hook.httpServer.address.port}/fixture").openConnection() as HttpURLConnection
        con.requestMethod="POST";con.doOutput=true
        con.setRequestProperty("X-Signature-Timestamp",time)
        con.setRequestProperty("X-Signature-Ed25519","00".repeat(64))
        con.setFixedLengthStreamingMode(data.size)
        try{con.outputStream.use{it.write(data)};assertEquals(403,con.responseCode)}finally{con.disconnect()}
        assertEquals(0,n.get())
    }
    @Test fun dispatchPreservesSdkHttpFanOutSemantics() = scoped { hook,n ->
        hook.wssWorker.getOnPackReceives().clear()
        hook.wssWorker.getOnPackReceives().add { n.incrementAndGet(); true }
        hook.wssWorker.getOnPackReceives().add { n.incrementAndGet(); false }
        val data="""{"op":0,"id":"fanout","t":"LOCAL_FIXTURE","d":{}}""".toByteArray()
        assertEquals(200 to "{\"op\":12,\"d\":0}",request(hook,data))
        assertEquals(2,n.get())
    }
    @Test fun bouncyCastleJceProviderRemainsAvailableToExistingSdkUsers() {
        HookAuth()
        assertTrue(Security.getProvider("BC") is org.bouncycastle.jce.provider.BouncyCastleProvider)
        assertEquals("BC", Security.getProvider("BC").name)
    }
    @Test fun signatureIsStrictlyBoundToTimestampAndRawBytes() {
        val hook = HookAuth()
        val data = "验证".toByteArray()
        val signature = sign(time.toByteArray() + data)
        val pub = key.generatePublicKey().encoded
        assertTrue(hook.verifySignature(signature, time, data, pub))
        assertEquals(false, hook.verifySignature(signature, "1795171201", data, pub))
        assertEquals(false, hook.verifySignature(signature, time, "验证 ".toByteArray(), pub))
    }
    @Test fun oversizedCallbackIsRejectedEvenWithoutContentLength() = scoped { hook, n ->
        assertEquals(413, request(hook, ByteArray(HookAuth.MAX_BODY_BYTES + 1) { 32 }, chunked = true).first)
        assertEquals(0, n.get())
    }
    @Test fun malformedSignedPayloadAndHeartbeatAreHandledWithoutDispatch() = scoped { hook, n ->
        assertEquals(400, request(hook, "not JSON".toByteArray()).first)
        assertEquals(200 to "{\"op\":11,\"d\":12}", request(hook, """{"op":1,"d":12}""".toByteArray()))
        assertEquals(0, n.get())
    }
}
