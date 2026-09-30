plugins {
    java
    id("com.gradleup.shadow")
}

// GraalPy 引擎包。不是一个 Bukkit 插件：没有 plugin.yml，也不注册命令。
// 构建后把 jar 放到 plugins/HuHoBotPenguin/engines/，主插件启动时用自己的
// classloader 把里面的引擎类加载进来，.py 脚本就能用 GraalPy（Python 3）执行。
// 不放这个 jar 时，主插件照常运行，只是 .py 脚本会报「未安装 GraalPy 引擎」。

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

// GraalPy 的 polyglot/truffle 运行时需要 Java 17；主插件仍是 Java 8，
// 所以这里的 toolchain 只作用于引擎包自己。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val graal = "24.1.2"

dependencies {
    // polyglot 与 truffle 运行时一起打进引擎包。主插件已经不再携带它们：
    // GraalJS 与 GraalPy 都拆成了独立 jar，各带一份运行时，互不依赖。
    implementation("org.graalvm.polyglot:polyglot:$graal")
    implementation("org.graalvm.python:python-language:$graal")
    implementation("org.graalvm.python:python-resources:$graal")
    implementation("org.graalvm.truffle:truffle-runtime:$graal")
}

tasks.shadowJar {
    archiveFileName.set("HuHoBot-Engine-GraalPy-${project.version}.jar")
    mergeServiceFiles()
    exclude(
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "module-info.class", "**/module-info.class", "META-INF/versions/**"
    )
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
