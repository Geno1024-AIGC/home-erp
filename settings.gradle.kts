pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "home-erp"

include("swrepo:spi")
include("swrepo:members")
include("swrepo:inventory")
include("swrepo:finances")
include("swrepo:chores")
include("swrepo:store")
include("star")