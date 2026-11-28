package com.velocitypowered.api.proxy;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import java.net.InetSocketAddress;

/** 编译桩：运行时由 Velocity 提供。 */
public interface Player extends MessageChannel {
    InetSocketAddress getRemoteAddress();
}
