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

tasks.test {
    failOnNoDiscoveredTests = false
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("g.sw.heds.HedsSmoke")
    classpath = sourceSets.named("test").get().runtimeClasspath
}