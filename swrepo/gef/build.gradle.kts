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

tasks.register<JavaExec>("generateSamples") {
    group = "verification"
    mainClass.set("g.sw.gef.GenSample")
    classpath = sourceSets.main.get().runtimeClasspath
    args(project.layout.projectDirectory.dir("samples").asFile.absolutePath)
    outputs.dir(project.layout.projectDirectory.dir("samples"))
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("g.sw.gef.GefSmoke")
    classpath = sourceSets.named("test").get().runtimeClasspath
}