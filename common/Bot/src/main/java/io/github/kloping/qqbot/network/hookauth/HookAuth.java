package io.github.kloping.qqbot.network.hookauth;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kloping.qqbot.Starter;
import io.github.kloping.qqbot.entities.Pack;
import io.github.kloping.qqbot.impl.BaseConnectedEvent;
import io.github.kloping.qqbot.interfaces.OnPackReceive;
import io.github.kloping.qqbot.network.AuthAndHeartbeat;
import io.github.kloping.qqbot.network.WssWorker;
import io.github.kloping.spt.annotations.AutoStand;
import io.github.kloping.spt.annotations.Entity;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static io.github.kloping.qqbot.Resource.GSON;

/** Authenticates the exact bytes Tencent signed before parsing or dispatching an event. */
@Entity
@Slf4j
public class HookAuth {
    static {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,20}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9A-Fa-f]{128}");
    private static final String ACK_OK = "{\"op\":12,\"d\":0}";
    private static final String ACK_RETRY = "{\"op\":12,\"d\":1}";

    @AutoStand WssWorker wssWorker;
    @AutoStand Starter.Config config;

    @Getter private volatile HttpServer httpServer;
    private ThreadPoolExecutor executor;
    private boolean closed;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> inFlight = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Long> processed = new LinkedHashMap<>(128, .75f, true);
    private static final int DEDUPE_CAPACITY = 4096;
    private static final long DEDUPE_WINDOW_MS = TimeUnit.HOURS.toMillis(1);

    public void webhookServerStart() {
        try {
            start(new InetSocketAddress(config.getWebhookport()));
        } catch (IOException | IllegalArgumentException e) {
            log.error("WebHook 服务无法启动", e);
        }
    }

    synchronized void start(InetSocketAddress bind) throws IOException {
        if (closed || httpServer != null) return;
        final String path = config.getWebhookpath();
        if (path == null || !path.startsWith("/") || "/health".equals(path)) {
            throw new IllegalArgumentException("无效 WebHook 路径");
        }
        // Refuse to open an endpoint with no verification key.
        if (privateKey(config.getSecret()) == null) throw new IllegalArgumentException("未配置 WebHook 验签密钥");
        HttpServer server = HttpServer.create(bind, 64);
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), task -> {
                    Thread thread = new Thread(task, "HuHoBot-QQ-Webhook");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            server.setExecutor(pool);
            server.createContext(path, exchange -> handleWebhook(exchange, path));
            server.createContext("/health", exchange -> {
                try {
                    boolean correctPath = "/health".equals(exchange.getRequestURI().getRawPath());
                    writeResponse(exchange, correctPath ? 200 : 404, correctPath ? "{\"status\":\"ok\"}" : "{}");
                } finally { exchange.close(); }
            });
            server.start();
            executor = pool;
            httpServer = server;
            log.info(BaseConnectedEvent.FORMAT_SERVER, config.getAppid());
        } catch (RuntimeException error) {
            server.stop(0);
            pool.shutdownNow();
            throw error;
        }
    }

    public synchronized void webhookServerStop() {
        closed = true;
        try {
            if (httpServer != null) httpServer.stop(0);
        } finally {
            httpServer = null;
            if (executor != null) executor.shutdownNow();
            executor = null;
        }
    }

    private void handleWebhook(HttpExchange exchange, String path) throws IOException {
        try {
            if (!path.equals(exchange.getRequestURI().getRawPath())) {
                writeResponse(exchange, 404, "{}");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                writeResponse(exchange, 405, "{}");
                return;
            }
            String sig = singleHeader(exchange, "X-Signature-Ed25519");
            String ts = singleHeader(exchange, "X-Signature-Timestamp");
            if (sig == null || ts == null || !SIGNATURE.matcher(sig).matches() || !TIMESTAMP.matcher(ts).matches()) {
                writeResponse(exchange, 403, "{}");
                return;
            }
            String declared = exchange.getRequestHeaders().getFirst("Content-Length");
            if (declared != null) {
                long size;
                try { size = Long.parseLong(declared); }
                catch (NumberFormatException invalid) { writeResponse(exchange, 400, "{}"); return; }
                if (size < 0) { writeResponse(exchange, 400, "{}"); return; }
                if (size > MAX_BODY_BYTES) { writeResponse(exchange, 413, "{}"); return; }
            }
            byte[] body = readBody(exchange.getRequestBody());
            if (body == null) { writeResponse(exchange, 413, "{}"); return; }
            Ed25519PrivateKeyParameters privateKey = privateKey(config.getSecret());
            if (privateKey == null || !verifySignature(sig, ts, body, privateKey.generatePublicKey().getEncoded())) {
                writeResponse(exchange, 403, "{}");
                return;
            }
            Pack pack;
            try { pack = GSON.fromJson(new String(body, StandardCharsets.UTF_8), Pack.class); }
            catch (JsonParseException malformed) { writeResponse(exchange, 400, "{}"); return; }
            if (pack == null || pack.getOp() == null) { writeResponse(exchange, 400, "{}"); return; }
            switch (pack.getOp()) {
                case 13:
                    String token = pack.dAsMapGet("plain_token", String.class);
                    String eventTs = pack.dAsMapGet("event_ts", String.class);
                    if (token == null || token.isEmpty() || token.length() > 1024 || eventTs == null || !TIMESTAMP.matcher(eventTs).matches()) {
                        writeResponse(exchange, 400, "{}");
                        return;
                    }
                    Ed25519Signer signer = new Ed25519Signer();
                    signer.init(true, privateKey);
                    byte[] message = (eventTs + token).getBytes(StandardCharsets.UTF_8);
                    signer.update(message, 0, message.length);
                    Map<String, String> response = new LinkedHashMap<>();
                    response.put("plain_token", token);
                    response.put("signature", Hex.toHexString(signer.generateSignature()));
                    writeResponse(exchange, 200, GSON.toJson(response));
                    return;
                case 0:
                    if (isRecentlyProcessed(dedupeKey(pack, sig))) {
                        writeResponse(exchange, 200, ACK_OK);
                        return;
                    }
                    CompletableFuture<Boolean> mine = new CompletableFuture<>();
                    String key = dedupeKey(pack, sig);
                    CompletableFuture<Boolean> active = inFlight.putIfAbsent(key, mine);
                    if (active != null) {
                        try {
                            writeResponse(exchange, 200, active.get(4, TimeUnit.SECONDS) ? ACK_OK : ACK_RETRY);
                        } catch (Exception inProgress) {
                            writeResponse(exchange, 200, ACK_RETRY);
                        }
                        return;
                    }
                    try {
                        // HTTP callbacks historically fan out to every registered event receiver;
                        // unlike the WebSocket chain, a consumer return value does not stop delivery here.
                        for (OnPackReceive handler : wssWorker.getOnPackReceives()) {
                            if (!(handler instanceof AuthAndHeartbeat)) handler.onReceive(pack);
                        }
                        rememberProcessed(key);
                        mine.complete(true);
                        writeResponse(exchange, 200, ACK_OK);
                    } catch (RuntimeException failure) {
                        mine.complete(false);
                        log.warn("WebHook 事件处理失败; 已返回重试 ACK ({})", failure.getClass().getSimpleName());
                        writeResponse(exchange, 200, ACK_RETRY);
                    } finally {
                        inFlight.remove(key, mine);
                    }
                    return;
                case 1:
                    double seq = pack.getD() instanceof Number ? ((Number) pack.getD()).doubleValue() : -1;
                    if (!(seq >= 0 && seq <= 0xffffffffL && seq == Math.floor(seq))) {
                        writeResponse(exchange, 400, "{}");
                        return;
                    }
                    writeResponse(exchange, 200, "{\"op\":11,\"d\":" + (long) seq + "}");
                    return;
                default:
                    writeResponse(exchange, 400, "{}");
            }
        } finally { exchange.close(); }
    }


    private String dedupeKey(Pack pack, String signature) {
        String id = pack.getId();
        return (id == null || id.isEmpty()) ? "sig:" + signature : "id:" + id;
    }

    private synchronized boolean isRecentlyProcessed(String key) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = processed.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue() < now) iterator.remove();
        Long expiry = processed.get(key);
        return expiry != null && expiry > now;
    }

    private synchronized void rememberProcessed(String key) {
        long now = System.currentTimeMillis();
        processed.put(key, now + DEDUPE_WINDOW_MS);
        while (processed.size() > DEDUPE_CAPACITY) processed.remove(processed.keySet().iterator().next());
    }

    private static String singleHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static byte[] readBody(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (out.size() + n > MAX_BODY_BYTES) return null;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    public boolean verifySignature(String signatureHex, String timestamp, byte[] body, byte[] publicKey) {
        if (signatureHex == null || timestamp == null || body == null || publicKey == null || publicKey.length != 32
                || !SIGNATURE.matcher(signatureHex).matches() || !TIMESTAMP.matcher(timestamp).matches()) return false;
        try {
            byte[] signature = Hex.decodeStrict(signatureHex);
            if ((signature[63] & 0xe0) != 0) return false;
            byte[] time = timestamp.getBytes(StandardCharsets.US_ASCII);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
            signer.update(time, 0, time.length);
            signer.update(body, 0, body.length);
            return signer.verifySignature(signature);
        } catch (RuntimeException malformed) { return false; }
    }

    private static Ed25519PrivateKeyParameters privateKey(String secret) {
        if (secret == null || secret.trim().isEmpty()) return null;
        byte[] source = secret.getBytes(StandardCharsets.UTF_8);
        byte[] seed = new byte[32];
        try {
            for (int i = 0; i < seed.length; i++) seed[i] = source[i % source.length];
            return new Ed25519PrivateKeyParameters(seed, 0);
        } finally {
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(source, (byte) 0);
        }
    }

    /** Kept for callers compiled against the previous standalone SDK. */
    public static String repeat(String str, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(str);
        return out.toString();
    }
}
