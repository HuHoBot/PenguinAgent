plugins {
    kotlin("jvm") version "2.2.20" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
}

allprojects {
    group = "cn.huohuas001"
    version = "1.3.0-beta.2"

    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}
