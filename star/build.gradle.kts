plugins {
    kotlin("jvm")
    id("g.build.versioning")
    application
}

application {
    mainClass.set("g.erp.star.Star")
}

dependencies {
    implementation(project(":swrepo:spi"))
    implementation(project(":swrepo:members"))
    implementation(project(":swrepo:inventory"))
    implementation(project(":swrepo:finances"))
    implementation(project(":swrepo:chores"))
}