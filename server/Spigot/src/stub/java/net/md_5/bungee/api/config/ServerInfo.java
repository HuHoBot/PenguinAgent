package net.md_5.bungee.api.config;

/** 编译桩：运行时由 BungeeCord 提供。 */
public interface ServerInfo {
    String getName();
    boolean sendData(String channel, byte[] data);
}
