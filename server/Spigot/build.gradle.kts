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
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-api:2.17.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
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
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
