package net.md_5.bungee.api;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.PluginManager;

import java.util.Map;

/** 编译桩：运行时由 BungeeCord 提供。 */
public interface ProxyServer {
    void registerChannel(String channel);
    Map<String, ServerInfo> getServers();
    PluginManager getPluginManager();
}
