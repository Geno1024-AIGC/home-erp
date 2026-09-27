pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "home-erp"

include("swrepo:spi")
include("swrepo:relay")
include("swrepo:topology")
include("swrepo:auth")
include("swrepo:members")
include("swrepo:inventory")
include("swrepo:finances")
include("swrepo:chores")
include("swrepo:db")
include("swrepo:gef")
include("star")
include("planet")
include("satellite:android")