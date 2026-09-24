plugins {
    kotlin("jvm") version "2.3.0"
    id("g.build.versioning")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    failOnNoDiscoveredTests = false
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("g.sw.db.DbSmoke")
    classpath = sourceSets.named("test").get().runtimeClasspath
}