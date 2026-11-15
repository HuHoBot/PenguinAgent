package com.velocitypowered.api.event;

/** 编译桩：运行时由 Velocity 提供。 */
public @interface Subscribe {
    int priority() default 0;
    boolean async() default true;
}
