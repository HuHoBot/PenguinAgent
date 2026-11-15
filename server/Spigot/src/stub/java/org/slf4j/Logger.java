package org.slf4j;

/** 编译桩：运行时由 Velocity 自带的 SLF4J 提供。 */
public interface Logger {
    void info(String message);
    void warn(String message);
    void error(String message);
}
