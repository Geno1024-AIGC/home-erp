plugins {
    kotlin("jvm")
    id("g.build.versioning")
}

dependencies {
    implementation(project(":swrepo:spi"))
}