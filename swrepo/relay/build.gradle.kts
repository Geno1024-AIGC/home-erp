plugins {
    kotlin("jvm") version "2.3.0"
    id("g.build.versioning")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":swrepo:spi"))
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("g.sw.relay.RelaySmoke")
    classpath = sourceSets.named("test").get().runtimeClasspath
}