package io.netty.buffer;

/** 编译桩：运行时由 Netty(Velocity 自带)提供。 */
public abstract class ByteBuf {
    public abstract java.nio.ByteBuffer nioBuffer();
}
