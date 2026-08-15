plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":common-Bot"))
    api("org.yaml:snakeyaml:1.33")
}

kotlin {
    jvmToolchain(8)
}
