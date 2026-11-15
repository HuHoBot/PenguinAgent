package com.velocitypowered.api.proxy.messages;

/** 编译桩：运行时由 Velocity 提供。 */
public final class MinecraftChannelIdentifier implements ChannelIdentifier {
    private final String namespace;
    private final String path;
    private MinecraftChannelIdentifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }
    public static MinecraftChannelIdentifier create(String namespace, String path) {
        return new MinecraftChannelIdentifier(namespace, path);
    }
    @Override
    public String getId() {
        return namespace + ":" + path;
    }
}
