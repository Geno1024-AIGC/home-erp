plugins {
    kotlin("jvm")
    id("g.build.versioning")
}

tasks.test {
    failOnNoDiscoveredTests = false
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("g.sw.db.DbSmoke")
    classpath = sourceSets.named("test").get().runtimeClasspath
}