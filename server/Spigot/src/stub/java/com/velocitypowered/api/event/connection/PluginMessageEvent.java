package com.velocitypowered.api.event.connection;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;

/** 编译桩：运行时由 Velocity 提供。 */
public class PluginMessageEvent {
    public ChannelIdentifier getIdentifier() { throw new UnsupportedOperationException(); }
    public ByteBuf getContent() { throw new UnsupportedOperationException(); }
    public Object getSource() { throw new UnsupportedOperationException(); }
    public void setResult(ForwardResult result) { throw new UnsupportedOperationException(); }

    public static final class ForwardResult {
        public static ForwardResult handled() { throw new UnsupportedOperationException(); }
        public static ForwardResult forward() { throw new UnsupportedOperationException(); }
    }
}
