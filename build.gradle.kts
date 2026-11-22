plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "agent"
    version = "1.24"

    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}
