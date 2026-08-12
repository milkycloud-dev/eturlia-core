import io.papermc.paperweight.tasks.RebuildGitPatches
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import org.gradle.process.CommandLineArgumentProvider

// ============================================================================
// Eturlia-NeoForge: Folia 1.21.1 + NeoForge 21.1.x hybrid server
// Uses paperweight-patcher 1.7.3 (same as Folia dev/1.21.1)
// ============================================================================

plugins {
    java
    id("io.papermc.paperweight.patcher") version "1.7.3"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

repositories {
    mavenCentral()
    maven(paperMavenPublicUrl) {
        content { onlyForConfigurations(configurations.paperclip.name) }
    }
}

dependencies {
    remapper("net.fabricmc:tiny-remapper:0.10.3:fat")
    decompiler("org.vineflower:vineflower:1.10.1")
    paperclip("io.papermc:paperclip:3.0.3")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}

subprojects {
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://maven.neoforged.net/releases") // Eturlia-NeoForge: FancyModLoader
        maven("https://maven.fabricmc.net/") // sponge-mixin (NeoForge 21.1 pins 0.15.2+)
        maven("https://repo.spongepowered.org/repository/maven-public/") // SpongePowered: Configurate
        maven("https://oss.sonatype.org/content/repositories/snapshots") // Spark snapshots
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") // Spigot / Spark
    }
}

paperweight {
    serverProject.set(project(":folia-server"))

    remapRepo.set(paperMavenPublicUrl)
    decompileRepo.set(paperMavenPublicUrl)

    usePaperUpstream(providers.gradleProperty("paperRef")) {
        withPaperPatcher {
            // Eturlia-NeoForge: API patches (Folia region scheduler API + NeoForge hooks)
            apiPatchDir.set(layout.projectDirectory.dir("patches/api"))
            apiOutputDir.set(layout.projectDirectory.dir("Folia-API"))

            // Eturlia-NeoForge: Server patches (Folia region threading + NeoForge event hooks)
            serverPatchDir.set(layout.projectDirectory.dir("patches/server"))
            serverOutputDir.set(layout.projectDirectory.dir("Folia-Server"))
        }
        patchTasks.register("generatedApi") {
            isBareDirectory = true
            upstreamDirPath = "paper-api-generator/generated"
            patchDir = layout.projectDirectory.dir("patches/generatedApi")
            outputDir = layout.projectDirectory.dir("paper-api-generator/generated")
        }
    }
}

tasks.generateDevelopmentBundle {
    apiCoordinates.set("dev.folia:folia-api")
    libraryRepositories.addAll(
        "https://repo.maven.apache.org/maven2/",
        paperMavenPublicUrl,
        "https://maven.neoforged.net/releases",
    )
}

tasks.withType<RebuildGitPatches> {
    filterPatches.set(false)
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printPaperVersion") {
    doLast {
        println(project.version)
    }
}

// ============================================================================
// Eturlia-NeoForge: Standalone JAR builder (eturliatest2.jar)
// ============================================================================

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun jarDirectory(sourceDir: File, destination: File) {
    JarOutputStream(FileOutputStream(destination)).use { output ->
        sourceDir.walkTopDown()
            .filter { file -> file.isFile }
            .sortedBy { file -> file.relativeTo(sourceDir).invariantSeparatorsPath }
            .forEach { file ->
                val entry = JarEntry(file.relativeTo(sourceDir).invariantSeparatorsPath)
                entry.time = 0
                output.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(output) }
                output.closeEntry()
            }
    }
}


project(":folia-server") {
    afterEvaluate {
        val foliaServerReady = projectDir.resolve("build.gradle.kts").exists() ||
            projectDir.resolve("src").exists()
        if (!foliaServerReady) {
            logger.lifecycle("Eturlia: Folia-Server not generated yet — run ./gradlew applyPatches first")
            return@afterEvaluate
        }

        val spongeMixin = "net.fabricmc:sponge-mixin:${providers.gradleProperty("spongeMixinVersion").get()}"
        dependencies {
            "compileOnly"("net.neoforged:neoforge:${providers.gradleProperty("neoforgeVersion").get()}:universal")
            "runtimeOnly"("net.neoforged:neoforge:${providers.gradleProperty("neoforgeVersion").get()}:universal")
            "compileOnly"("net.neoforged.fancymodloader:loader:${providers.gradleProperty("fmlLoaderVersion").get()}")
            "runtimeOnly"("net.neoforged.fancymodloader:loader:${providers.gradleProperty("fmlLoaderVersion").get()}")
            // Force NeoForge installer mixin (JAVA_21); FML 4.0.43 otherwise pulls 0.14.0 → JAVA_17 only.
            "runtimeOnly"(spongeMixin)
        }
        configurations.configureEach {
            resolutionStrategy {
                force(spongeMixin)
            }
        }
        if (configurations.findByName("fmlLoader") == null) {
            configurations.create("fmlLoader") {
                isCanBeResolved = true
                isCanBeConsumed = false
            }
            dependencies.add(
                "fmlLoader",
                "net.neoforged.fancymodloader:loader:${providers.gradleProperty("fmlLoaderVersion").get()}"
            )
            dependencies.add(
                "fmlLoader",
                "net.neoforged:neoforge:${providers.gradleProperty("neoforgeVersion").get()}:universal"
            )
            dependencies.add("fmlLoader", spongeMixin)
        }

        // Prefer published NeoForge universal (mojmap) for EventHooks / CommonHooks / etc.
        // Shims remain available under build-data/eturlia-neoforge-shims for documentation
        // and for optional offline stubbing, but they reference Minecraft types and cannot
        // compile on an empty classpath.
        val compileJava = tasks.findByName("compileJava") as? JavaCompile
        if (compileJava != null) {
            logger.lifecycle("Eturlia: compiling Folia-Server against NeoForge {} universal", providers.gradleProperty("neoforgeVersion").get())
        }
    }
}

// Standalone packager — registered only after ALL projects are evaluated
gradle.projectsEvaluated {
    val server = project(":folia-server")
    if (!server.projectDir.resolve("src").exists() || server.tasks.findByName("jar") == null) {
        return@projectsEvaluated
    }
    val neoforge = project(":neoforge")
    val coremods = project(":neoforge:coremods")
    if (neoforge.tasks.findByName("neoforgeResourcesJar") == null) {
        logger.warn("Eturlia: neoforgeResourcesJar missing — skipping standalone jar wiring")
        return@projectsEvaluated
    }

    // Prefer mojang-mapped Folia-Server jar (paperweight task: serverJar, a Zip/Jar archive).
    val serverArchiveTaskName = if (server.tasks.findByName("serverJar") != null) "serverJar" else "jar"
    val serverArchive = server.tasks.named(serverArchiveTaskName, org.gradle.api.tasks.bundling.AbstractArchiveTask::class.java)
    val apiJar = project(":folia-api").tasks.named("jar", Jar::class.java)
    val neoforgeResourcesJar = neoforge.tasks.named("neoforgeResourcesJar", Jar::class.java)
    val neoforgeExtrasJar = neoforge.tasks.named("jar", Jar::class.java)
    val neoforgeCoremodsJar = coremods.tasks.named("jar", Jar::class.java)
    val fmlLoaderConfig = server.configurations.findByName("fmlLoader")
    val runtimeClasspath = server.configurations.named("runtimeClasspath")
    // Fat classpath: keep EVERYTHING needed at runtime — including folia-api and spark-*.
    // Do not filter paper/spark jars out; Paper embeds spark (PaperClassLookup) and Folia needs API.
    val excludeNamePrefixes = listOf<String>()
    val stagingDir = server.layout.buildDirectory.dir("eturlia/standalone")
    val neoforgeVersion = providers.gradleProperty("neoforgeVersion").get()

    val eturliaLauncherSources = fileTree("build-data/eturlia-launcher/src/main/java") { include("**/*.java") }
    val eturliaCoreSources = fileTree("build-data/eturlia-core/src/main/java") { include("**/*.java") }
    val eturliaServerTemplateSources = fileTree("build-data/eturlia-server-templates/src/main/java") { include("**/*.java") }

    val compileEturliaLauncher = server.tasks.register("compileEturliaLauncher", JavaCompile::class.java) {
        description = "Compile the Eturlia jar launcher"
        source(eturliaLauncherSources)
        classpath = files()
        destinationDirectory.set(server.layout.buildDirectory.dir("eturlia/launcher-classes"))
        options.release.set(21)
    }

    val compileEturliaCore = server.tasks.register("compileEturliaCore", JavaCompile::class.java) {
        description = "Compile Eturlia core runtime (+ ModLauncher launch handler)"
        source(eturliaCoreSources)
        // Need FML loader + ModLauncher + securejarhandler APIs for eturlia.launch.*
        classpath = files(
            serverArchive.flatMap { it.archiveFile },
            fmlLoaderConfig?.files ?: emptySet<File>(),
            runtimeClasspath.map { cfg ->
                cfg.files.filter { f ->
                    val n = f.name
                    n.startsWith("modlauncher-")
                            || n.startsWith("mergetool-")
                            || n.contains("distmarker")
                            || n.startsWith("securejarhandler-")
                            || n.startsWith("JarJarFileSystems-")
                            || n.contains("nightconfig")
                            || n.startsWith("toml-")
                            || n.startsWith("core-3.") // nightconfig core
                            // log4j: the console noise filter installs a real Filter on Paper's
                            // console appender, so eturlia-core needs the API + core at compile time.
                            || n.startsWith("log4j-api-")
                            || n.startsWith("log4j-core-")
                            // mixin: EturliaMixinErrorHandler implements IMixinErrorHandler so a
                            // mod's unusable mixin can be skipped instead of aborting the boot.
                            || n.startsWith("sponge-mixin-")
                }
            },
            // Bootstrap copies (known versions) as a fallback if runtimeClasspath filter misses them
            rootProject.fileTree("build-data/eturlia-bootstrap/libs") {
                include("securejarhandler-*.jar", "JarJarFileSystems-*.jar")
            },
        )
        destinationDirectory.set(server.layout.buildDirectory.dir("eturlia/core-classes"))
        options.release.set(21)
        dependsOn(compileEturliaLauncher, serverArchive)
    }

    val compileEturliaServerTemplates = server.tasks.register("compileEturliaServerTemplates", JavaCompile::class.java) {
        description = "Compile Eturlia server template classes"
        source(eturliaServerTemplateSources)
        // log4j is needed here too: EturliaServer calls EturliaNoiseFilter, and javac must load
        // that class's supertype (log4j's AbstractFilter) to verify the call.
        classpath = files(
            serverArchive.flatMap { it.archiveFile },
            server.layout.buildDirectory.dir("eturlia/core-classes"),
            runtimeClasspath.map { cfg -> cfg.files.filter { it.name.startsWith("log4j-") } },
        )
        destinationDirectory.set(server.layout.buildDirectory.dir("eturlia/server-template-classes"))
        options.release.set(21)
        dependsOn(compileEturliaCore)
    }

    val eturliaCoreResources = fileTree("build-data/eturlia-core/src/main/resources") { include("**/*") }

    val eturliaCoreJar = server.tasks.register("eturliaCoreJar", Jar::class.java) {
        from(server.layout.buildDirectory.dir("eturlia/core-classes"))
        from(eturliaCoreResources)
        // The compatibility manifest has to live in *this* jar, not just the outer launcher
        // jar: EturliaModLoadingPlugin loads it with getResourceAsStream, and at runtime it
        // runs from the extracted eturlia-core.jar. The launcher jar is not on the server
        // JVM's classpath, so a copy only there is unreachable — the smoke log said exactly
        // that: "Manifest resource not found: /eturlia-supported.json".
        from(rootProject.file("build-data/eturlia-supported.json"))
        archiveFileName.set("eturlia-core.jar")
        destinationDirectory.set(server.layout.buildDirectory.dir("eturlia/intermediate-jars"))
        dependsOn(compileEturliaCore)
    }

    val eturliaServerTemplateJar = server.tasks.register("eturliaServerTemplateJar", Jar::class.java) {
        from(server.layout.buildDirectory.dir("eturlia/server-template-classes"))
        archiveFileName.set("eturlia-server-templates.jar")
        destinationDirectory.set(server.layout.buildDirectory.dir("eturlia/intermediate-jars"))
        dependsOn(compileEturliaServerTemplates)
    }


    // Apply NeoForge access transformers to the Folia mojang-mapped server jar so
    // NeoForge runtime (ServerLifecycleHooks, etc.) can access widened MC members
    // without ModLauncher. Required for Folia-first hybrid boots.
    val atApplySources = fileTree("build-data/eturlia-at-apply/src/main/java") { include("**/*.java") }
    val atApplyClasspath = files(fileTree("build-data/eturlia-at-apply/libs") { include("*.jar") })
    val compileAtApply = tasks.register("compileEturliaAtApply", JavaCompile::class.java) {
        source(atApplySources)
        classpath = atApplyClasspath
        destinationDirectory.set(layout.buildDirectory.dir("eturlia/at-apply-classes"))
        options.release.set(21)
        options.encoding = "UTF-8"
        doFirst {
            if (atApplyClasspath.isEmpty) {
                error("Missing jars in build-data/eturlia-at-apply/libs (accesstransformers, asm, antlr)")
            }
        }
    }
    val neoForgeAtCfg = layout.buildDirectory.file("eturlia/neoforge-accesstransformer.cfg")
    val extractNeoForgeAt = tasks.register("extractNeoForgeAccessTransformer") {
        val universal = neoforge.configurations.getByName("neoforgeUniversal")
        inputs.files(universal)
        outputs.file(neoForgeAtCfg)
        doLast {
            val universalJar = universal.files.single { it.name.contains("neoforge") && it.name.endsWith(".jar") }
            ZipFile(universalJar).use { zip ->
                val entry = zip.getEntry("META-INF/accesstransformer.cfg")
                    ?: error("META-INF/accesstransformer.cfg missing from $universalJar")
                zip.getInputStream(entry).use { input ->
                    neoForgeAtCfg.get().asFile.parentFile.mkdirs()
                    neoForgeAtCfg.get().asFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
    val atTransformedServerJar = server.layout.buildDirectory.file("eturlia/folia-server-neoforge-at.jar")
    val applyNeoForgeAts = server.tasks.register("applyNeoForgeAts", JavaExec::class.java) {
        group = "build"
        description = "Apply NeoForge access transformers to Folia mojang-mapped server jar"
        dependsOn(serverArchive, compileAtApply, extractNeoForgeAt)
        classpath(compileAtApply.map { it.destinationDirectory }, atApplyClasspath)
        mainClass.set("eturlia.at.AtApply")
        val inJar = serverArchive.flatMap { it.archiveFile }
        val atCfg = neoForgeAtCfg
        val outJar = atTransformedServerJar
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                inJar.get().asFile.absolutePath,
                atCfg.get().asFile.absolutePath,
                outJar.get().asFile.absolutePath,
            )
        })
        inputs.file(inJar)
        inputs.file(atCfg)
        outputs.file(outJar)
        doFirst {
            outJar.get().asFile.parentFile.mkdirs()
        }
    }

    val prepareEturliaStandalone = server.tasks.register("prepareEturliaStandalone") {
        description = "Stage Folia server + API + NeoForge FML classpath as nested jars"
        dependsOn(applyNeoForgeAts, apiJar, eturliaCoreJar, eturliaServerTemplateJar, neoforgeResourcesJar, neoforgeExtrasJar, neoforgeCoremodsJar)
        inputs.file(atTransformedServerJar)
        inputs.file(serverArchive.flatMap { it.archiveFile })
        inputs.file(apiJar.flatMap { it.archiveFile })
        inputs.file(neoforgeResourcesJar.flatMap { it.archiveFile })
        inputs.file(neoforgeExtrasJar.flatMap { it.archiveFile })
        inputs.file(neoforgeCoremodsJar.flatMap { it.archiveFile })
        inputs.files(runtimeClasspath)
        if (fmlLoaderConfig != null) inputs.files(fmlLoaderConfig)
        outputs.dir(stagingDir)

        doLast {
            val outputDir = stagingDir.get().asFile
            val librariesDir = outputDir.resolve("libraries")
            delete(outputDir)
            librariesDir.mkdirs()

            val universalFiles = neoforge.configurations.getByName("neoforgeUniversal").files
            val candidates = buildList {
                // Mojang-mapped Folia server with NeoForge ATs applied, then API (org.bukkit.*)
                add(atTransformedServerJar.get().asFile)
                add(apiJar.get().archiveFile.get().asFile)
                add(neoforgeResourcesJar.get().archiveFile.get().asFile)
                add(neoforgeExtrasJar.get().archiveFile.get().asFile)
                add(neoforgeCoremodsJar.get().archiveFile.get().asFile)
                addAll(universalFiles)
                add(eturliaCoreJar.get().archiveFile.get().asFile)
                add(eturliaServerTemplateJar.get().archiveFile.get().asFile)
                addAll(runtimeClasspath.get().files.filterNot { file ->
                    excludeNamePrefixes.any { prefix -> file.name.startsWith(prefix) }
                })
                if (fmlLoaderConfig != null) addAll(fmlLoaderConfig.files)
            }
            // Prefer NeoForge installer mixin (0.15.2+); drop stale FML-transitive 0.14.0 copies.
            val preferredMixin = candidates
                .filter { it.name.startsWith("sponge-mixin-") && it.isFile }
                .maxWithOrNull(compareBy({ it.name.contains("0.15.") }, { it.name }))
            val seenPaths = mutableSetOf<String>()
            val classpathFiles = candidates.filter { file ->
                if (!file.exists()) return@filter false
                if (file.name.startsWith("sponge-mixin-") && preferredMixin != null &&
                    file.absoluteFile.normalize() != preferredMixin.absoluteFile.normalize()
                ) {
                    return@filter false
                }
                seenPaths.add(file.absoluteFile.normalize().path)
            }
            // Use ORIGINAL jar names (no NNN- prefix) so JPMS automatic module names
            // match what ModLauncher/FML require (e.g. jopt.simple, not 072.jopt.simple).
            val usedNames = mutableSetOf<String>()
            val indexLines = classpathFiles.map { file ->
                var baseName = if (file.isDirectory) "${file.name}.jar" else file.name
                if (!usedNames.add(baseName)) {
                    // Content-identical paths already filtered; name clash from different paths.
                    var i = 2
                    val stem = baseName.removeSuffix(".jar")
                    while (!usedNames.add("$stem-$i.jar")) i++
                    baseName = "$stem-$i.jar"
                }
                val embeddedFile = librariesDir.resolve(baseName)
                if (file.isDirectory) jarDirectory(file, embeddedFile) else file.copyTo(embeddedFile, overwrite = true)
                "${sha256(embeddedFile)}\t$baseName"
            }
            outputDir.resolve("eturlia-libraries.index")
                .writeText(indexLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    val eturliaBootstrapLibs = rootProject.fileTree("build-data/eturlia-bootstrap/libs") { include("*.jar") }

    server.tasks.register("eturliaStandaloneJar", Jar::class.java) {
        group = "build"
        description = "Build eturlia-1.21.1-neoforge.jar with Folia + NeoForge FML nested inside"
        dependsOn(compileEturliaLauncher, prepareEturliaStandalone)
        archiveFileName.set("eturlia-1.21.1-neoforge-$neoforgeVersion.jar")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
        from(compileEturliaLauncher.flatMap { it.destinationDirectory })
        from(stagingDir.map { it.dir("libraries") }) { into("META-INF/eturlia-libraries") }
        from(stagingDir.map { it.file("eturlia-libraries.index") }) { into("META-INF") }
        from(eturliaBootstrapLibs) { into("META-INF/eturlia-bootstrap") }
        // build-data/, not folia-server/: on a case-insensitive filesystem the generated
        // Folia-Server tree lands on top of folia-server/ and wipes tracked files there.
        // That is how this manifest disappeared from the jar once already.
        from(rootProject.file("build-data/eturlia-supported.json")) { into("META-INF") }
        from(rootProject.file("build-data/eturlia-launcher/src/main/resources/eturlia")) { into("eturlia") }
        doFirst {
            if (eturliaBootstrapLibs.files.isEmpty()) {
                error("Missing build-data/eturlia-bootstrap/libs (bootstraplauncher, securejarhandler, asm, JarJarFileSystems)")
            }
        }
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "eturlia.launcher.Main",
                    "Enable-Native-Access" to "ALL-UNNAMED",
                    "Eturlia-NeoForge" to "true",
                    "Eturlia-MC-Version" to "1.21.1",
                    "Eturlia-NeoForge-Version" to neoforgeVersion,
                    "Eturlia-Launch-Target" to "eturliaserver",
                )
            )
        }
    }
}
