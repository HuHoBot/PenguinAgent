package com.velocitypowered.api.plugin;

/** 编译桩：运行时由 Velocity 提供。仅编译期可见，不入包。 */
public @interface Plugin {
    String id();
    String name() default "";
    String version() default "";
    String description() default "";
    String[] authors() default {};
}
