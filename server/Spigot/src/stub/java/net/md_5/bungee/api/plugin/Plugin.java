package net.md_5.bungee.api.plugin;

import net.md_5.bungee.api.ProxyServer;

import java.util.logging.Logger;

/** 编译桩：运行时由 BungeeCord 提供。仅编译期可见，不入包。 */
public abstract class Plugin {
    public PluginDescription getDescription() { throw new UnsupportedOperationException(); }
    public ProxyServer getProxy() { throw new UnsupportedOperationException(); }
    public Logger getLogger() { throw new UnsupportedOperationException(); }
    public void onEnable() { }
}
