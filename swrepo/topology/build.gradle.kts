plugins {
    kotlin("jvm") version "2.3.0"
    id("g.build.versioning")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":swrepo:spi"))
    implementation(project(":swrepo:db"))
}

tasks.test {
    failOnNoDiscoveredTests = false
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("g.sw.erp.topology.TopologySmoke")
    classpath = sourceSets.named("test").get().runtimeClasspath
}