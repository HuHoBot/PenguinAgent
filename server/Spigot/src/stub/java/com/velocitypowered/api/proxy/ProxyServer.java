package com.velocitypowered.api.proxy;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import java.util.Collection;

/** 编译桩：运行时由 Velocity 提供。 */
public interface ProxyServer {
    Collection<Player> getAllPlayers();
    ChannelRegistrar getChannelRegistrar();
}
