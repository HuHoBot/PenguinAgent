pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("plugin.lombok") version "2.2.20"
    }
}

include(":common-Bot")
project(":common-Bot").projectDir = file("common/Bot")

include(":server-AdapterCommon")
project(":server-AdapterCommon").projectDir = file("server/AdapterCommon")

include(":server-Spigot")
project(":server-Spigot").projectDir = file("server/Spigot")

include(":server-Proxy")
project(":server-Proxy").projectDir = file("server/Proxy")

// 可选引擎包：不进主插件产物。构建后放到 plugins/HuHoBotPenguin/engines/。
//   ./gradlew :addon-GraalJs:shadowJar   Spigot 的 GraalJS 引擎
//   ./gradlew :addon-GraalPy:shadowJar   Spigot 的 GraalPy 引擎
include(":addon-GraalJs")
project(":addon-GraalJs").projectDir = file("addon/GraalJs")

include(":addon-GraalPy")
project(":addon-GraalPy").projectDir = file("addon/GraalPy")

rootProject.name = "HuHoBotPenguin"
