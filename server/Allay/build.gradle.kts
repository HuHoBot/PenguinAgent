plugins {
    java
    kotlin("jvm")
    id("org.allaymc.gradle.plugin") version "0.2.1"
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
    maven("https://storehouse.okaeri.eu/repository/maven-public/")
}

dependencies {
    implementation(project(":server-AdapterCommon"))
    implementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
kotlin.jvmToolchain(21)

allay {
    api = "0.17.0"
    apiOnly = true
    plugin {
        entrance = "cn.huohuas001.huhobotPenguin.allay.HuHoBotAllay"
        apiVersion = ">=0.17.0"
        name = "HuHoBotPenguin"
        authors += "HuoHuas001"
    }
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val pluginVersion = rootProject.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.json") {
        expand("version" to pluginVersion)
    }
}

val gatherJar by tasks.registering(Copy::class) {
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("gather-jar"))
}

tasks.shadowJar {
    archiveFileName.set("HuHoBot-Penguin_Allay-${project.version}.jar")
    finalizedBy(gatherJar)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
