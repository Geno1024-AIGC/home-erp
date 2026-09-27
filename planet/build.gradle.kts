plugins {
    kotlin("jvm") version "2.3.0"
    id("g.build.versioning")
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("g.erp.planet.Planet")
}

dependencies {
    implementation(project(":swrepo:spi"))
    implementation(project(":swrepo:relay"))
    implementation(project(":swrepo:topology"))
}