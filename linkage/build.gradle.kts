// :linkage -- a build tool, never shipped: checks each plugin jar's server API calls against real server API jars.
plugins {
    java
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
}

// The mutation test needs two real API jars whose types differ (1.20.6 and 26.1.2), as the live testbed's servers unpacked
// them under testbed/run (-Pnyr.guardian.serverRun=DIR names another run directory). Without them it does not run.
tasks.test {
    val run = providers.gradleProperty("nyr.guardian.serverRun").map { File(it) }.orNull ?: rootProject.file("testbed/run")
    val paper1206 = File(run, "paper-1.20.6/libraries/io/papermc/paper/paper-api/1.20.6-R0.1-SNAPSHOT/paper-api-1.20.6-R0.1-SNAPSHOT.jar")
    val paper2612 = File(run, "paper-26.1.2/libraries/io/papermc/paper/paper-api/26.1.2.build.74-stable/paper-api-26.1.2.build.74-stable.jar")
    systemProperty("nyr.linkage.paper1206", paper1206.absolutePath)
    systemProperty("nyr.linkage.paper2612", paper2612.absolutePath)
    val present = paper1206.isFile && paper2612.isFile
    onlyIf("the 1.20.6 and 26.1.2 server API jars are present") { present }
}
