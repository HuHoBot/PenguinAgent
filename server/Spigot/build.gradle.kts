import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("jvm")
    id("java")
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":common-Bot"))
    implementation(project(":server-AdapterCommon"))
    implementation("com.alibaba:fastjson:2.0.32")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-api:2.17.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // 脚本扩展引擎。
    // LUA：LuaJ，打进主 jar。
    // JS：GraalJS 解压后约 37 MB，不在主包里。放在 :addon-GraalJs，运行时从
    //     plugins/HuHoBotPenguin/engines/ 加载。主模块对它没有任何编译期引用。
    // PY：GraalPy 解压后约 180 MB，同样不在主包里，放在 :addon-GraalPy，
    //     运行时从同一个 engines/ 目录加载。
    implementation("org.luaj:luaj-jse:3.0.1")
}

kotlin {
    jvmToolchain(8)
}

tasks {
    val compiledPackageRoot = layout.buildDirectory.dir(
        "classes/kotlin/main/cn/huohuas001"
    )

    val normalizeSpigotPackageDirectory by registering {
        group = "build"
        description = "Normalizes the Spigot package directory casing before packaging."
        dependsOn(classes)

        doLast {
            val packageRoot = compiledPackageRoot.get().asFile
            val incorrectlyCasedDirectory = packageRoot.resolve("huHoBotPenguin")
            if (!incorrectlyCasedDirectory.exists()) return@doLast

            val temporaryDirectory = packageRoot.resolve("__huhobotPenguin_case_fix__")
            val correctlyCasedDirectory = packageRoot.resolve("huhobotPenguin")

            Files.move(
                incorrectlyCasedDirectory.toPath(),
                temporaryDirectory.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            Files.move(
                temporaryDirectory.toPath(),
                correctlyCasedDirectory.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

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
        dependsOn(normalizeSpigotPackageDirectory)
        archiveFileName.set("HuHoBot-Penguin_Spigot-${project.version}.jar")
        finalizedBy(gatherJar)
        relocate("org.bstats", "${project.group}.bstats")
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
