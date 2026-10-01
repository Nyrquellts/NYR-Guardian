// NYR Guardian build: shared conventions, then one shaded jar, one jar test, one API linkage check and one sale download per
// plugin, and the suite download that carries every plugin's jar byte for byte.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    alias(libs.plugins.shadow) apply false
}

/** One sellable plugin: its Gradle project (the directory), plugin name, main class and the file name buyers download. */
data class Product(val id: String, val pluginName: String, val mainClass: String, val fileName: String)

val products: List<Product> = listOf(
    Product("combattag", "NYR-CombatTagPro", "com.nyr.guardian.combattag.CombatTagPlugin", "NYR-CombatTagPro"),
    Product("chunkhopper", "NYR-ChunkHopper", "com.nyr.guardian.chunkhopper.ChunkHopperPlugin", "NYR-ChunkHopper"),
    Product("smarttick", "NYR-SmartTick", "com.nyr.guardian.smarttick.SmartTickPlugin", "NYR-SmartTick"),
    Product("dupesentry", "NYR-DupeSentry", "com.nyr.guardian.dupesentry.DupeSentryPlugin", "NYR-DupeSentry")
)

/**
 * The server API jars every plugin jar is checked against: the ones the live testbed's servers unpacked under testbed/run
 * once its matrix has run. -Pnyr.guardian.serverRun=DIR names another run directory and -Pnyr.guardian.apiJars=a.jar;b.jar
 * the jars themselves. Without them `check` warns and skips the API linkage check, and no sale download is built.
 */
val serverRun: File = providers.gradleProperty("nyr.guardian.serverRun").map { File(it) }.orNull ?: file("testbed/run")
val apiJarList: List<String> = providers.gradleProperty("nyr.guardian.apiJars").orNull?.split(';')?.filter { it.isNotBlank() }
    ?: listOf(
        "paper-1.20.6/libraries/io/papermc/paper/paper-api/1.20.6-R0.1-SNAPSHOT/paper-api-1.20.6-R0.1-SNAPSHOT.jar",
        "paper-1.21.11/libraries/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar",
        "paper-26.1.2/libraries/io/papermc/paper/paper-api/26.1.2.build.74-stable/paper-api-26.1.2.build.74-stable.jar",
        "paper-26.2/libraries/io/papermc/paper/paper-api/26.2.build.123-stable/paper-api-26.2.build.123-stable.jar",
        "spigot-1.21.11/bundler/libraries/spigot-api-1.21.11-R0.2-SNAPSHOT.jar"
    ).map { File(serverRun, it).absolutePath }

allprojects {
    group = "com.nyr.guardian"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://repo.tcoded.com/releases") { name = "tcoded" }
        maven("https://jitpack.io") { name = "jitpack" }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(rootProject.libs.versions.java.get()))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(rootProject.libs.versions.java.get().toInt())
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    }

    tasks.named<JavaCompile>("compileTestJava") {
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }

    dependencies {
        // The plugins compile against Paper 1.21.1's API. Everything newer than 1.20.6 is reached through reflection, and
        // the live matrix runs the jars on 1.20.6 through 26.2.
        "compileOnly"(rootProject.libs.paper.api)
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
        "testImplementation"(rootProject.libs.mockbukkit)
        "testImplementation"(rootProject.libs.paper.api)
    }

    // Paper API's transitive gson, slf4j and error_prone versions are pinned, so every build resolves the same ones. None of
    // them ships in a plugin jar.
    val aligned = mapOf(
        "com.google.code.gson:gson" to rootProject.libs.versions.gson.get(),
        "org.slf4j:slf4j-api" to rootProject.libs.versions.slf4j.get(),
        "com.google.errorprone:error_prone_annotations" to "2.27.0"
    )
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            aligned["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // MockBukkit throws UnimplementedOperationException, a JUnit TestAbortedException, for calls it cannot run: the test
        // is marked skipped and Gradle still passes. A skipped test proved nothing, so it fails the task here.
        val results = reports.junitXml.outputLocation
        doLast {
            val skipped = results.get().asFile.walk().filter { it.isFile && it.name.endsWith(".xml") }
                .flatMap { file -> Regex("""<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<skipped""").findAll(file.readText()).map { "${it.groupValues[2]}.${it.groupValues[1]}" } }
                .toList()
            if (skipped.isNotEmpty()) {
                throw GradleException("skipped tests prove nothing; make them run or remove them: " + skipped.joinToString(", "))
            }
        }
    }
}

// SmartTick's load-pressure and hysteresis rules are written in NYR-Lang (smarttick/src/main/nyr/*.nyr) and compiled by
// nyrc into PressureRules.java and HysteresisRules.java; nyr.json lists them. With NYRC naming the nyrc launcher, every
// plugin's check fails while a generated class is no longer exactly what its source compiles to (a hand edit, or a source
// changed without `nyrc --build`). Without NYRC the check warns and the committed classes are built as they are.
val nyrc: String? = providers.environmentVariable("NYRC").orNull
val nyrCheck = tasks.register<Exec>("nyrCheck") {
    group = "verification"
    description = "Fails when a class nyrc generated no longer matches its .nyr source (nyr.json)."
    val launcher = nyrc
    val manifest = file("nyr.json").absolutePath
    val windows = System.getProperty("os.name").startsWith("Windows")
    commandLine(if (windows) listOf("cmd", "/c", launcher ?: "nyrc", "--check", manifest) else listOf(launcher ?: "nyrc", "--check", manifest))
    doFirst {
        if (launcher == null) {
            logger.warn("nyrCheck skipped: NYRC is not set, so the generated SmartTick rule classes were not compared with their .nyr sources")
            throw StopExecutionException()
        }
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(nyrCheck) }
}

val saleZips = tasks.register("saleZips") {
    group = "distribution"
    description = "Builds every plugin's sale download, each only after its jar has passed its own jar test."
}

for (product in products) {
    project(":${product.id}") {
        apply(plugin = "com.gradleup.shadow")

        dependencies {
            "implementation"(project(":common"))
        }

        val sourceSets = extensions.getByType<SourceSetContainer>()
        val main = sourceSets.named("main")
        val test = sourceSets.named("test")
        val commonProject = project(":common")

        tasks.named<ProcessResources>("processResources") {
            val props = mapOf("version" to project.version.toString())
            inputs.properties(props)
            filesMatching("plugin.yml") { expand(props) }
        }

        // The jar is also the sale jar: nothing in it is GPL. FoliaLib (MIT) and :common are relocated under the plugin's
        // own package, because plugin class loaders share classes by name and two NYR plugins would otherwise share statics.
        val shadowJar = tasks.named<ShadowJar>("shadowJar") {
            archiveBaseName.set(product.fileName)
            archiveClassifier.set("")
            archiveVersion.set(project.version.toString())
            val base = "com.nyr.guardian.${product.id}.lib"
            relocate("com.nyr.guardian.common", "$base.common")
            relocate("com.tcoded.folialib", "$base.folialib")
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**", "module-info.class", "META-INF/versions/*/module-info.class")
        }
        tasks.named("jar") { enabled = false }
        tasks.named("assemble") { dependsOn(shadowJar) }

        val verifyJar = tasks.register("verifyJar") {
            group = "verification"
            description = "Checks ${product.fileName}'s jar: descriptor, bundled config, and that everything shared is relocated."
            val jar = shadowJar.flatMap { it.archiveFile }
            val version = project.version.toString()
            val id = product.id
            val pluginName = product.pluginName
            val mainClass = product.mainClass
            inputs.file(jar).withPropertyName("jar")
            doLast {
                val file: File = jar.get().asFile
                ZipFile(file).use { zip ->
                    val names: List<String> = zip.entries().toList().map { it.name }
                    val problems = mutableListOf<String>()
                    val descriptorEntry = zip.getEntry("plugin.yml")
                    val descriptor = if (descriptorEntry == null) "" else String(zip.getInputStream(descriptorEntry).readAllBytes(), Charsets.UTF_8)
                    if (!descriptor.contains("name: $pluginName\n") && !descriptor.contains("name: $pluginName\r\n")) problems.add("plugin.yml does not name $pluginName")
                    if (!descriptor.contains("main: $mainClass")) problems.add("plugin.yml does not start $mainClass")
                    if (!descriptor.contains("version: '$version'")) problems.add("plugin.yml does not carry version $version")
                    if (!descriptor.contains("folia-supported: true")) problems.add("plugin.yml does not declare folia-supported")
                    for (needed in listOf("${pluginName.lowercase()}/config.yml", mainClass.replace('.', '/') + ".class",
                            "com/nyr/guardian/$id/lib/common/GuardianPlugin.class", "com/nyr/guardian/$id/lib/folialib/FoliaLib.class")) {
                        if (!names.contains(needed)) problems.add("missing $needed")
                    }
                    for (prefix in listOf("com/nyr/guardian/common/", "com/tcoded/")) {
                        val stray = names.count { it.startsWith(prefix) && it.endsWith(".class") }
                        if (stray > 0) problems.add("$stray unrelocated class(es) under $prefix")
                    }
                    val foreign = names.filter { it.endsWith(".class") && !it.startsWith("com/nyr/guardian/$id/") }
                    if (foreign.isNotEmpty()) problems.add("${foreign.size} class(es) outside com/nyr/guardian/$id/, e.g. ${foreign.first()}")
                    if (names.contains("config.yml")) problems.add("carries a root config.yml another plugin could read")
                    if (problems.isNotEmpty()) throw GradleException("${file.name}:\n  " + problems.joinToString("\n  "))
                    logger.lifecycle("verified ${file.name}: ${names.size} entries, ${file.length() / 1024} KiB")
                }
            }
        }
        shadowJar.configure { finalizedBy(verifyJar) }

        // The unit tests run on compiled classes; jar tests (package <plugin>.jar) run only here, against the built jar, with
        // the plugin's own classes and :common kept off the class path so the plugin class loader must read the jar.
        tasks.named<Test>("test") {
            filter { excludeTestsMatching("com.nyr.guardian.${product.id}.jar.*") }
        }
        val jarTest = tasks.register<Test>("jarTest") {
            group = "verification"
            description = "Loads ${product.fileName}'s built jar into an in-memory server and plays the plugin through it."
            val jarFile = shadowJar.flatMap { it.archiveFile }
            inputs.file(jarFile).withPropertyName("jar")
            testClassesDirs = test.get().output.classesDirs
            val excluded = main.get().output.plus(commonProject.extensions.getByType<SourceSetContainer>().named("main").get().output)
            classpath = test.get().output + configurations.getByName("testRuntimeClasspath").filter { file ->
                !excluded.files.contains(file) && !file.name.startsWith("common") && !file.name.startsWith("FoliaLib")
            }
            filter { includeTestsMatching("com.nyr.guardian.${product.id}.jar.*") }
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dnyr.guardian.jar=" + jarFile.get().asFile.absolutePath) })
            shouldRunAfter(tasks.named("test"))
        }
        tasks.named("check") { dependsOn(jarTest) }

        // Every server API call in the jar must link on each server API a buyer may run: Paper 1.20.6 to 26.2 and Spigot.
        val linkageTool = configurations.create("linkageTool")
        dependencies { linkageTool(project(":linkage")) }
        val apiLinkage = tasks.register<JavaExec>("apiLinkage") {
            group = "verification"
            description = "Checks that every server API call in ${product.fileName}'s jar links on Paper 1.20.6 to 26.2 and Spigot."
            val jarFile = shadowJar.flatMap { it.archiveFile }
            val apiJars = apiJarList
            inputs.file(jarFile).withPropertyName("jar")
            inputs.files(apiJars).withPropertyName("apiJars").optional()
            classpath = linkageTool
            mainClass.set("com.nyr.guardian.linkage.ApiLinkage")
            argumentProviders.add(CommandLineArgumentProvider {
                listOf(jarFile.get().asFile.absolutePath, "com/nyr/guardian/${product.id}/lib/folialib/") + apiJars
            })
            doFirst {
                val missing = apiJars.filter { !File(it).isFile }
                if (missing.isNotEmpty()) {
                    logger.warn("apiLinkage skipped: no server API jar at " + missing.joinToString(", ") + ". Run the live matrix once " +
                        "(testbed/matrix.mjs), or pass -Pnyr.guardian.serverRun=DIR or -Pnyr.guardian.apiJars=a.jar;b.jar")
                    throw StopExecutionException()
                }
            }
        }
        tasks.named("check") { dependsOn(apiLinkage) }

        val version = project.version.toString()
        val saleZip = tasks.register<Zip>("saleZip") {
            group = "distribution"
            description = "Packs ${product.fileName}'s sale download, once its jar has passed its jar test."
            archiveBaseName.set(product.fileName)
            archiveVersion.set(version)
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            dependsOn(jarTest, apiLinkage)
            val apiJars = apiJarList
            doFirst {
                val missing = apiJars.filter { !File(it).isFile }
                if (missing.isNotEmpty()) throw GradleException("a sale download needs the API linkage check: no server API jar at " + missing.joinToString(", "))
            }
            into("${product.fileName}-$version") {
                from(shadowJar)
                from("src/dist") {
                    filteringCharset = "UTF-8"
                    filter { line: String -> line.replace("@version@", version) }
                }
                from(rootProject.file("THIRD-PARTY-NOTICES.txt"))
                into("defaults") {
                    from("src/main/resources/${product.pluginName.lowercase()}") { include("config.yml") }
                }
            }
        }

        // What a buyer downloads, checked as it ships: exactly these files, the jar inside it, and no file name or byte
        // naming the tools it was made with.
        val verifySaleZip = tasks.register("verifySaleZip") {
            group = "verification"
            description = "Checks ${product.fileName}'s sale download: its files, its jar and forbidden names in every byte."
            val zipFile = saleZip.flatMap { it.archiveFile }
            val checksum = layout.buildDirectory.file("distributions/${product.fileName}-$version.zip.sha256")
            val fileName = product.fileName
            val pluginName = product.pluginName
            inputs.file(zipFile).withPropertyName("saleZip")
            outputs.file(checksum).withPropertyName("checksum")
            doLast {
                val file: File = zipFile.get().asFile
                // stored encoded, so this build file does not name them either
                val forbidden = listOf("Y2xhdWRl", "YW50aHJvcGlj").map { String(Base64.getDecoder().decode(it), Charsets.US_ASCII) }
                val problems = mutableListOf<String>()
                fun mentions(what: String, bytes: ByteArray) {
                    val text = String(bytes, Charsets.ISO_8859_1).lowercase()
                    for (word in forbidden) {
                        val at = text.indexOf(word)
                        if (at >= 0) problems.add("$what contains \"$word\" at byte $at")
                    }
                }
                val root = "$fileName-$version/"
                val jarName = "$root$fileName-$version.jar"
                val expected = setOf(jarName, "${root}README.txt", "${root}THIRD-PARTY-NOTICES.txt", "${root}defaults/config.yml")
                val files = mutableSetOf<String>()
                ZipFile(file).use { zip ->
                    for (entry in zip.entries().toList()) {
                        mentions("the name ${entry.name}", entry.name.toByteArray(Charsets.UTF_8))
                        if (entry.isDirectory) continue
                        files.add(entry.name)
                        val bytes = zip.getInputStream(entry).readAllBytes()
                        mentions(entry.name, bytes)
                        if (String(bytes, Charsets.UTF_8).contains("@version@")) problems.add("${entry.name} still says @version@")
                        if (entry.name != jarName) continue
                        val inner = ZipInputStream(ByteArrayInputStream(bytes))
                        var descriptor = ""
                        while (true) {
                            val e = inner.nextEntry ?: break
                            val content = inner.readAllBytes()
                            mentions("$jarName!/${e.name} (name)", e.name.toByteArray(Charsets.UTF_8))
                            mentions("$jarName!/${e.name}", content)
                            if (e.name == "plugin.yml") descriptor = String(content, Charsets.UTF_8)
                        }
                        if (!descriptor.contains("name: $pluginName") || !descriptor.contains("version: '$version'")) {
                            problems.add("$jarName: plugin.yml is not $pluginName $version")
                        }
                    }
                }
                if (files != expected) problems.add("files are ${files.sorted()}, expected ${expected.sorted()}")
                if (problems.isNotEmpty()) throw GradleException("${file.name}:\n  " + problems.joinToString("\n  "))
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                checksum.get().asFile.writeText("$digest *${file.name}\n")
                logger.lifecycle("verified ${file.name}: ${files.size} files, ${file.length() / 1024} KiB, sha256 $digest")
            }
        }
        saleZip.configure { finalizedBy(verifySaleZip) }
        saleZips.configure { dependsOn(saleZip) }
    }
}

// NYR Guardian Suite: every plugin in one download. It carries the very jars the single downloads carry (the live matrix
// runs them all on one server), each plugin's guide without its own "In this download" section, and each default config.
run {
    val suiteProducts: List<Product> = products
    val suiteName = "NYR-GuardianSuite"
    val suiteVersion = project.version.toString()
    val suiteGuides = tasks.register("suiteGuides") {
        group = "distribution"
        description = "Writes each plugin's guide for the suite download."
        val sources: Map<String, File> = suiteProducts.associate { it.fileName to project(":${it.id}").file("src/dist/README.txt") }
        val out = layout.buildDirectory.dir("suite/guides")
        inputs.files(sources.values).withPropertyName("guides")
        inputs.property("version", suiteVersion)
        outputs.dir(out).withPropertyName("out")
        doLast {
            val dir = out.get().asFile
            dir.deleteRecursively()
            dir.mkdirs()
            for ((name, source) in sources) {
                val text = source.readText().replace("@version@", suiteVersion)
                val start = text.indexOf("In this download\n")
                val end = text.indexOf("Requirements\n", start)
                if (start < 0 || end < 0) throw GradleException("$source has no \"In this download\" section before \"Requirements\"")
                File(dir, "$name.txt").writeText(text.substring(0, start) + text.substring(end))
            }
        }
    }

    val suiteZip = tasks.register<Zip>("suiteZip") {
        group = "distribution"
        description = "Packs the NYR Guardian Suite download, once every plugin jar has passed its jar test and linkage check."
        archiveBaseName.set(suiteName)
        archiveVersion.set(suiteVersion)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dependsOn(suiteProducts.flatMap { listOf(":${it.id}:jarTest", ":${it.id}:apiLinkage") })
        val apiJars = apiJarList
        doFirst {
            val missing = apiJars.filter { !File(it).isFile }
            if (missing.isNotEmpty()) throw GradleException("a sale download needs the API linkage check: no server API jar at " + missing.joinToString(", "))
        }
        // copy filters are not task inputs: name what they write, so changing the wording rebuilds the zip
        inputs.property("wording", listOf(suiteVersion, "This plugin's jar includes", "Each plugin jar here includes"))
        into("$suiteName-$suiteVersion") {
            from("src/suite") {
                filteringCharset = "UTF-8"
                filter { line: String -> line.replace("@version@", suiteVersion) }
            }
            from("THIRD-PARTY-NOTICES.txt") {
                filteringCharset = "UTF-8"
                filter { line: String -> line.replace("This plugin's jar includes", "Each plugin jar here includes") }
            }
            into("guides") { from(suiteGuides) }
            for (product in suiteProducts) {
                val productProject = project(":${product.id}")
                into("plugins") { from(productProject.tasks.named("shadowJar")) }
                into("defaults/${product.pluginName}") {
                    from(productProject.file("src/main/resources/${product.pluginName.lowercase()}")) { include("config.yml") }
                }
            }
        }
    }

    // The suite as it ships: exactly its files, each jar byte for byte the one its single download carries, and no file name
    // or byte naming the tools it was made with.
    val verifySuiteZip = tasks.register("verifySuiteZip") {
        group = "verification"
        description = "Checks the NYR Guardian Suite download: its files, its jars against the built ones and forbidden names."
        val zipFile = suiteZip.flatMap { it.archiveFile }
        val checksum = layout.buildDirectory.file("distributions/$suiteName-$suiteVersion.zip.sha256")
        val builtJars: Map<String, File> = suiteProducts.associate { product ->
            "${product.fileName}-$suiteVersion.jar" to project(":${product.id}").layout.buildDirectory.file("libs/${product.fileName}-$suiteVersion.jar").get().asFile
        }
        val guideNames = suiteProducts.map { "${it.fileName}.txt" }
        val defaultDirs = suiteProducts.map { it.pluginName }
        inputs.file(zipFile).withPropertyName("suiteZip")
        inputs.files(builtJars.values).withPropertyName("builtJars")
        outputs.file(checksum).withPropertyName("checksum")
        doLast {
            val file: File = zipFile.get().asFile
            val problems = mutableListOf<String>()
            fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            fun mentions(what: String, bytes: ByteArray) {
                val text = String(bytes, Charsets.ISO_8859_1).lowercase()
                for (word in listOf("Y2xhdWRl", "YW50aHJvcGlj").map { String(Base64.getDecoder().decode(it), Charsets.US_ASCII) }) {
                    val at = text.indexOf(word)
                    if (at >= 0) problems.add("$what contains \"$word\" at byte $at")
                }
            }
            val root = "$suiteName-$suiteVersion/"
            val expected = mutableSetOf("${root}README.txt", "${root}THIRD-PARTY-NOTICES.txt")
            builtJars.keys.forEach { expected.add("${root}plugins/$it") }
            guideNames.forEach { expected.add("${root}guides/$it") }
            defaultDirs.forEach { expected.add("${root}defaults/$it/config.yml") }
            val files = mutableSetOf<String>()
            ZipFile(file).use { zip ->
                for (entry in zip.entries().toList()) {
                    mentions("the name ${entry.name}", entry.name.toByteArray(Charsets.UTF_8))
                    if (entry.isDirectory) continue
                    files.add(entry.name)
                    val bytes = zip.getInputStream(entry).readAllBytes()
                    mentions(entry.name, bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    if (text.contains("@version@")) problems.add("${entry.name} still says @version@")
                    if (entry.name.endsWith("THIRD-PARTY-NOTICES.txt") && !text.contains("Each plugin jar here includes")) {
                        problems.add("THIRD-PARTY-NOTICES.txt still speaks of one plugin's jar")
                    }
                    if (entry.name.contains("/guides/") && text.contains("In this download")) problems.add("${entry.name} keeps its own download list")
                    val built = builtJars[entry.name.substringAfterLast('/')] ?: continue
                    if (sha256(bytes) != sha256(built.readBytes())) problems.add("${entry.name} differs from ${built.name} as built")
                    val inner = ZipInputStream(ByteArrayInputStream(bytes))
                    while (true) {
                        val e = inner.nextEntry ?: break
                        mentions("${entry.name}!/${e.name} (name)", e.name.toByteArray(Charsets.UTF_8))
                        mentions("${entry.name}!/${e.name}", inner.readAllBytes())
                    }
                }
            }
            if (files != expected) problems.add("files are ${files.sorted()}, expected ${expected.sorted()}")
            if (problems.isNotEmpty()) throw GradleException("${file.name}:\n  " + problems.joinToString("\n  "))
            val digest = sha256(file.readBytes())
            checksum.get().asFile.writeText("$digest *${file.name}\n")
            logger.lifecycle("verified ${file.name}: ${files.size} files, ${file.length() / 1024} KiB, sha256 $digest")
        }
    }
    suiteZip.configure { finalizedBy(verifySuiteZip) }
    saleZips.configure { dependsOn(suiteZip) }
}
