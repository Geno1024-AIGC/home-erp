plugins {
    kotlin("jvm") version "2.3.0"
    id("g.build.versioning")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":swrepo:spi"))
    implementation(project(":swrepo:gef"))
}

tasks.register<JavaExec>("pack") {
    group = "build"
    description = "Pack the satellite HTML GEF feature packages into build/gefs"
    mainClass.set("g.erp.gefs.FeaturePack")
    classpath = sourceSets.main.get().runtimeClasspath
    args(
        project.layout.buildDirectory.dir("gefs").get().asFile.absolutePath,
        project.version.toString(),
    )
    outputs.dir(project.layout.buildDirectory.dir("gefs"))
}