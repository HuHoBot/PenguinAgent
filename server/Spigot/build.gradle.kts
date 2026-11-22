import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.Deflater
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("jvm")
    id("java")
    id("com.gradleup.shadow")
}

// Bukkit/Paper 1.18+ runtime floor is Java 17; this must match the embedded proxy module's variant.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.17.0")
    testImplementation(kotlin("test"))
    testImplementation("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
    implementation(project(":common-Bot"))
    implementation(project(":server-AdapterCommon"))
    // 单 jar 多端：把 BungeeCord / Velocity 代理端适配并入主产物（compileOnly 的
    // velocity-api / bungeecord-api 不会进入 jar），Bukkit / BungeeCord / Velocity
    // 各自只加载自己的入口类与描述文件，互不干扰。
    implementation(project(":server-Proxy"))
    implementation("com.alibaba:fastjson:2.0.66")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-api:2.17.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.1")
    // 代理端兼容(同一 jar 可装入 BungeeCord/Velocity)：
    // Velocity 用本仓库 src/stub/java 的 API 桩编译(不入包,运行时用代理端自带类,保持 Java8 产物)
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // SQLite 可选存储后端(org.xerial 纯 JDBC 驱动；shadowJar 只保留主流平台 native 控制体积)
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Small, bundled runtimes; addons require no external VM or standard-library extraction.
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("org.tukaani:xz:1.12")
    compileOnly(files("libs/rhino-1.9.1.jar"))
    runtimeOnly(files("libs/rhino-1.9.1.jar"))
    runtimeOnly(files("libs/rhino-engine-1.9.1.jar"))
}

kotlin {
    jvmToolchain(17)
}

// Velocity API 编译桩：仅编译期可见，shadowJar 里排除，运行时使用代理端自带的真实 API
sourceSets {
    main {
        java {
            srcDir("src/stub/java")
        }
    }
}

tasks {
    val gatherJar by registering(Copy::class) {
        group = "build"
        description = "Collects the packaged Spigot plugin into build/gather-jar."

        from(shadowJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("gather-jar"))
    }

    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        doLast {
            val target=archiveFile.get().asFile
            val temporary=target.resolveSibling(target.name+".compressed")
            val before=target.length()
            ZipInputStream(target.inputStream().buffered()).use { input ->
                ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.setLevel(Deflater.BEST_COMPRESSION)
                    val seen=mutableSetOf<String>()
                    while(true){
                        val original=input.nextEntry?:break
                        if(!seen.add(original.name)){input.closeEntry();continue}
                        val entry=ZipEntry(original.name)
                        entry.time=original.time
                        output.putNextEntry(entry)
                        input.copyTo(output,65536)
                        output.closeEntry();input.closeEntry()
                    }
                }
            }
            if(temporary.length()<before) Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
            else temporary.delete()
            logger.lifecycle("Lossless JAR compression: $before -> ${target.length()} bytes (kept smaller archive)")
        }
        exclude("com/velocitypowered/**")
        exclude("com/google/inject/**")
        exclude("org/slf4j/**")
        exclude("io/netty/**")
        exclude("net/md_5/**")
        archiveFileName.set("HuHoBot-Penguin-${project.version}.jar")
        finalizedBy(gatherJar)
        relocate("org.bstats", "${project.group}.bstats")
        // 自带一份 Adventure，避免和 Paper / 其他插件的版本冲突。
        relocate("net.kyori", "${project.group}.libs.kyori")
        // sqlite-jdbc 自带全部平台 native；只保留主流平台（路径按 jar 内真实目录）
        exclude("org/sqlite/native/Linux-Android/**")
        exclude("org/sqlite/native/Linux-Musl/**")
        exclude("org/sqlite/native/Linux/arm/**")
        exclude("org/sqlite/native/Linux/armv6/**")
        exclude("org/sqlite/native/Linux/armv7/**")
        exclude("org/sqlite/native/Linux/x86/**")
        exclude("org/sqlite/native/Linux/ppc64/**")
        exclude("org/sqlite/native/Linux/s390x/**")
        exclude("org/sqlite/native/Linux/riscv64/**")
        exclude("org/sqlite/native/FreeBSD/**")
        exclude("org/sqlite/native/Windows/armv7/**")
        exclude("org/sqlite/native/Windows/aarch64/**")
        exclude("org/sqlite/native/Windows/x86/**")
        // BouncyCastle 的 JCE Provider 初始化会反射加载全部算法注册（含 PQC 的
        // SPHINCSPlus），裁剪 org/bouncycastle/pqc/** 会导致 QQ 客户端启动时
        // NoClassDefFoundError（真机 Leaf + 生产插件环境下已复现），绝不能裁。
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        mergeServiceFiles()
        exclude(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "module-info.class", "**/module-info.class", "META-INF/versions/**"
        )
    }

    processResources {
        val ver = project.version.toString()
        // 必须把版本号声明为任务输入：否则只改版本号时该任务会被判定为 up-to-date，
        // plugin.yml 里的 @version@ 不会被重新替换，打出来的 jar 会带着上一个版本号。
        inputs.property("version", ver)
        filesMatching("plugin.yml") {
            filter(org.apache.tools.ant.filters.ReplaceTokens::class, mapOf(
                "tokens" to mapOf("version" to ver)
            ))
        }
    }
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize="600m"
}

tasks.processResources {
    from("libs/licenses") { into("licenses/javascript") }
}

val verifyPackagedJavaScript by tasks.registering(JavaExec::class) {
    group="verification"
    dependsOn(tasks.shadowJar,tasks.testClasses)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    classpath=files(tasks.shadowJar.flatMap { it.archiveFile },sourceSets.test.get().output)
    mainClass.set("agentcheck.ShadedEngineSmoke")
}
tasks.check { dependsOn(verifyPackagedJavaScript) }
