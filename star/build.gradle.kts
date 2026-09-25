plugins {
    kotlin("jvm") version "2.3.0"
    id("g.build.versioning")
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("g.erp.star.Star")
}

dependencies {
    implementation(project(":swrepo:spi"))
    implementation(project(":swrepo:db"))
    implementation(project(":swrepo:auth"))
    implementation(project(":swrepo:members"))
    implementation(project(":swrepo:inventory"))
    implementation(project(":swrepo:finances"))
    implementation(project(":swrepo:chores"))
}