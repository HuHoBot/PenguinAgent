plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") apply false
    kotlin("plugin.lombok")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8)) // 设置 JDK 8
    }

    // qqpd-bot-java is kept as a Git submodule and is a Maven project.
    // Compile its sources as part of this module so local submodule changes
    // are used immediately without importing its Gradle build.
    sourceSets {
        named("main") {
            java.srcDir(rootProject.file("deps/qqpd-bot-java/src/main/java"))
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    // Dependencies declared by deps/qqpd-bot-java/pom.xml.
    implementation("io.github.kloping:SpringTool:0.6.4") {
        exclude(group = "io.github.Kloping", module = "JvUtils")
    }
    implementation("io.github.kloping:JvUtils:0.4.9-Beta1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.12")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.15.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.fusesource.jansi:jansi:2.4.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    // Used by both the submodule sources and this module's Kotlin sources.
    implementation("com.alibaba:fastjson:2.0.32")

    compileOnly("org.projectlombok:lombok:1.18.26")
    annotationProcessor("org.projectlombok:lombok:1.18.26")

    testImplementation(kotlin("test"))
}

repositories {
    mavenCentral()
}
