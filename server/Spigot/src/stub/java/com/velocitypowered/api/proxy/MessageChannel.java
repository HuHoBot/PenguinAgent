package com.velocitypowered.api.proxy;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

/** 编译桩：运行时由 Velocity 提供。 */
public interface MessageChannel {
    void sendPluginMessage(ChannelIdentifier identifier, byte[] data);
}
