plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        register("versioning") {
            id = "g.build.versioning"
            implementationClass = "g.build.versioning.VersioningPlugin"
        }
    }
}