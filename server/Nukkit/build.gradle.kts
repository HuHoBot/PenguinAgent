plugins {
    java
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.lanink.cn/repository/maven-public/")
}

version = rootProject.version

dependencies {
    implementation(project(":server-AdapterCommon"))
    compileOnly("cn.nukkit:Nukkit:MOT-SNAPSHOT")
    implementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
kotlin.jvmToolchain(17)

tasks.processResources {
    val pluginVersion = rootProject.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}

val gatherJar by tasks.registering(Copy::class) {
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("gather-jar"))
}

tasks.shadowJar {
    archiveFileName.set("HuHoBot-Penguin_Nukkit-${project.version}.jar")
    finalizedBy(gatherJar)
}

tasks.build { dependsOn(tasks.shadowJar) }
