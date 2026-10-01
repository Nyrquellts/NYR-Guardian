// NYR Guardian: NYR CombatTag Pro, ChunkHopper, SmartTick and DupeSentry, each its own jar and download, and all of them
// together in the NYR Guardian Suite download.
//
//   ./gradlew build          every plugin's jar, tests included
//   ./gradlew saleZips       every plugin's download and the suite's, once each jar has passed its jar test

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nyr-guardian"

include("common", "linkage", "combattag", "chunkhopper", "smarttick", "dupesentry")
