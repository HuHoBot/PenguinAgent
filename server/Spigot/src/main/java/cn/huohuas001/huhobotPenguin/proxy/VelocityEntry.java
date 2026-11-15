package cn.huohuas001.huhobotPenguin.proxy;

import com.alibaba.fastjson.JSON;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.MessageChannel;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Velocity 兼容入口：同一 jar 装入 Velocity 时加载。
 * 注册 huhobot:bridge 通道，转发子服聊天；代理侧 QQ 桥接由内测版本接入。
 */
@Plugin(id = "huhobotpenguin", name = "HuHoBotPenguin", version = "@version@", authors = {"Shabby-666"})
public class VelocityEntry {

    public static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("huhobot", "bridge");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public VelocityEntry(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        logger.info("HuHoBot Penguin (proxy compatibility entry) loaded");
        server.getChannelRegistrar().register(CHANNEL);
    }

    @Subscribe
    public void onMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        try {
            String payload = StandardCharsets.UTF_8.decode(event.getContent().nioBuffer()).toString();
            com.alibaba.fastjson.JSONObject json = JSON.parseObject(payload);
            if (json == null || !"chat".equals(json.get("type"))) return;
            String player = String.valueOf(json.getOrDefault("player", ""));
            String message = String.valueOf(json.getOrDefault("message", ""));
            String relay = "[跨服] " + player + ": " + message;
            byte[] out = JSON.toJSONString(new com.alibaba.fastjson.JSONObject(java.util.Collections.singletonMap("message", (Object) relay)))
                .getBytes(StandardCharsets.UTF_8);
            Object source = event.getSource();
            for (Player online : server.getAllPlayers()) {
                if (source != null && online.getRemoteAddress().equals(source)) continue;
                online.sendPluginMessage(CHANNEL, out);
            }
        } catch (Exception ignored) {
        }
    }
}
