package cn.huohuas001.huhobotPenguin.proxy;

import com.alibaba.fastjson.JSON;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.event.EventHandler;

import java.nio.charset.StandardCharsets;

/**
 * BungeeCord 兼容入口：同一个 jar 装入代理端时可正常加载。
 * 提供 huhobot:bridge 插件消息通道，把子服转发来的聊天广播给其余子服；
 * 代理侧的 QQ 桥接由后续内测版本接入。
 */
public class BungeeEntry extends Plugin {

    public static final String CHANNEL = "huhobot:bridge";

    @Override
    public void onEnable() {
        getLogger().info("HuHoBot Penguin " + getDescription().getVersion() + " (proxy compatibility entry) loaded");
        getProxy().registerChannel(CHANNEL);
        PluginManager pm = getProxy().getPluginManager();
        pm.registerListener(this, new Listener() {
            @EventHandler
            public void onMessage(PluginMessageEvent event) {
                if (!CHANNEL.equals(event.getTag())) return;
                event.setCancelled(true);
                try {
                    String payload = new String(event.getData(), StandardCharsets.UTF_8);
                    com.alibaba.fastjson.JSONObject json = JSON.parseObject(payload);
                    if (json == null || !"chat".equals(json.get("type"))) return;
                    String player = String.valueOf(json.getOrDefault("player", ""));
                    String message = String.valueOf(json.getOrDefault("message", ""));
                    String relay = "[跨服] " + player + ": " + message;
                    byte[] out = JSON.toJSONString(new com.alibaba.fastjson.JSONObject(java.util.Collections.singletonMap("message", (Object) relay)))
                        .getBytes(StandardCharsets.UTF_8);
                    Object sender = event.getSender();
                    String senderName = sender instanceof ServerInfo ? ((ServerInfo) sender).getName() : "";
                    for (ServerInfo server : getProxy().getServers().values()) {
                        if (server.getName().equals(senderName)) continue;
                        server.sendData(CHANNEL, out);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }
}
