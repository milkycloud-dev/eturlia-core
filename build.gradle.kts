import io.papermc.paperweight.tasks.RebuildGitPatches
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// ============================================================================
// Crelia-NeoForge: Folia 1.21.1 + NeoForge 21.1.x hybrid server
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
        maven("https://maven.neoforged.net/releases") // Crelia-NeoForge: FancyModLoader
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
            // Crelia-NeoForge: API patches (Folia region scheduler API + NeoForge hooks)
            apiPatchDir.set(layout.projectDirectory.dir("patches/api"))
            apiOutputDir.set(layout.projectDirectory.dir("Folia-API"))

            // Crelia-NeoForge: Server patches (Folia region threading + NeoForge event hooks)
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
// Crelia-NeoForge: Standalone JAR builder (creliatest2.jar)
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
            logger.lifecycle("Crelia: Folia-Server not generated yet — run ./gradlew applyPatches first")
            return@afterEvaluate
        }

        dependencies {
            "compileOnly"("net.neoforged:neoforge:${providers.gradleProperty("neoforgeVersion").get()}:universal")
            "runtimeOnly"("net.neoforged:neoforge:${providers.gradleProperty("neoforgeVersion").get()}:universal")
            "compileOnly"("net.neoforged.fancymodloader:loader:${providers.gradleProperty("fmlLoaderVersion").get()}")
            "runtimeOnly"("net.neoforged.fancymodloader:loader:${providers.gradleProperty("fmlLoaderVersion").get()}")
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
        }

        // Prefer published NeoForge universal (mojmap) for EventHooks / CommonHooks / etc.
        // Shims remain available under build-data/crelia-neoforge-shims for documentation
        // and for optional offline stubbing, but they reference Minecraft types and cannot
        // compile on an empty classpath.
        val compileJava = tasks.findByName("compileJava") as? JavaCompile
        if (compileJava != null) {
            logger.lifecycle("Crelia: compiling Folia-Server against NeoForge {} universal", providers.gradleProperty("neoforgeVersion").get())
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
        logger.warn("Crelia: neoforgeResourcesJar missing — skipping standalone jar wiring")
        return@projectsEvaluated
    }

    val serverJar = server.tasks.named("jar", Jar::class.java)
    val neoforgeResourcesJar = neoforge.tasks.named("neoforgeResourcesJar", Jar::class.java)
    val neoforgeExtrasJar = neoforge.tasks.named("jar", Jar::class.java)
    val neoforgeCoremodsJar = coremods.tasks.named("jar", Jar::class.java)
    val fmlLoaderConfig = server.configurations.findByName("fmlLoader")
    val runtimeClasspath = server.configurations.named("runtimeClasspath")
    val paperTransformerJarPrefixes = listOf("folia-api-", "spark-api-", "spark-paper-")
    val stagingDir = server.layout.buildDirectory.dir("crelia/standalone")
    val neoforgeVersion = providers.gradleProperty("neoforgeVersion").get()

    val creliaLauncherSources = fileTree("build-data/crelia-launcher/src/main/java") { include("**/*.java") }
    val creliaCoreSources = fileTree("build-data/crelia-core/src/main/java") { include("**/*.java") }
    val creliaServerTemplateSources = fileTree("build-data/crelia-server-templates/src/main/java") { include("**/*.java") }

    val compileCreliaLauncher = server.tasks.register("compileCreliaLauncher", JavaCompile::class.java) {
        description = "Compile the Crelia jar launcher"
        source(creliaLauncherSources)
        classpath = files()
        destinationDirectory.set(server.layout.buildDirectory.dir("crelia/launcher-classes"))
        options.release.set(21)
    }

    val compileCreliaCore = server.tasks.register("compileCreliaCore", JavaCompile::class.java) {
        description = "Compile Crelia core runtime"
        source(creliaCoreSources)
        classpath = files(serverJar.flatMap { it.archiveFile })
        destinationDirectory.set(server.layout.buildDirectory.dir("crelia/core-classes"))
        options.release.set(21)
        dependsOn(compileCreliaLauncher)
    }

    val compileCreliaServerTemplates = server.tasks.register("compileCreliaServerTemplates", JavaCompile::class.java) {
        description = "Compile Crelia server template classes"
        source(creliaServerTemplateSources)
        classpath = files(serverJar.flatMap { it.archiveFile }, server.layout.buildDirectory.dir("crelia/core-classes"))
        destinationDirectory.set(server.layout.buildDirectory.dir("crelia/server-template-classes"))
        options.release.set(21)
        dependsOn(compileCreliaCore)
    }

    val creliaCoreResources = fileTree("build-data/crelia-core/src/main/resources") { include("**/*") }

    val creliaCoreJar = server.tasks.register("creliaCoreJar", Jar::class.java) {
        from(server.layout.buildDirectory.dir("crelia/core-classes"))
        from(creliaCoreResources)
        archiveFileName.set("crelia-core.jar")
        destinationDirectory.set(server.layout.buildDirectory.dir("crelia/intermediate-jars"))
        dependsOn(compileCreliaCore)
    }

    val creliaServerTemplateJar = server.tasks.register("creliaServerTemplateJar", Jar::class.java) {
        from(server.layout.buildDirectory.dir("crelia/server-template-classes"))
        archiveFileName.set("crelia-server-templates.jar")
        destinationDirectory.set(server.layout.buildDirectory.dir("crelia/intermediate-jars"))
        dependsOn(compileCreliaServerTemplates)
    }

    val prepareCreliaStandalone = server.tasks.register("prepareCreliaStandalone") {
        description = "Stage Folia server + NeoForge FML classpath as nested jars"
        dependsOn(serverJar, creliaCoreJar, creliaServerTemplateJar, neoforgeResourcesJar, neoforgeExtrasJar, neoforgeCoremodsJar)
        inputs.file(serverJar.flatMap { it.archiveFile })
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
                add(serverJar.get().archiveFile.get().asFile)
                add(neoforgeResourcesJar.get().archiveFile.get().asFile)
                add(neoforgeExtrasJar.get().archiveFile.get().asFile)
                add(neoforgeCoremodsJar.get().archiveFile.get().asFile)
                addAll(universalFiles)
                add(creliaCoreJar.get().archiveFile.get().asFile)
                add(creliaServerTemplateJar.get().archiveFile.get().asFile)
                addAll(runtimeClasspath.get().files.filterNot { file ->
                    paperTransformerJarPrefixes.any { prefix -> file.name.startsWith(prefix) }
                })
                if (fmlLoaderConfig != null) addAll(fmlLoaderConfig.files)
            }
            val seenPaths = mutableSetOf<String>()
            val classpathFiles = candidates.filter { file ->
                file.exists() && seenPaths.add(file.absoluteFile.normalize().path)
            }
            val indexLines = classpathFiles.mapIndexed { index, file ->
                val embeddedName = "%03d-%s%s".format(index, file.name, if (file.isDirectory) ".jar" else "")
                val embeddedFile = librariesDir.resolve(embeddedName)
                if (file.isDirectory) jarDirectory(file, embeddedFile) else file.copyTo(embeddedFile, overwrite = true)
                "${sha256(embeddedFile)}\t$embeddedName"
            }
            outputDir.resolve("crelia-libraries.index")
                .writeText(indexLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    server.tasks.register("creliaStandaloneJar", Jar::class.java) {
        group = "build"
        description = "Build crelia-1.21.1-neoforge.jar with Folia + NeoForge FML nested inside"
        dependsOn(compileCreliaLauncher, prepareCreliaStandalone)
        archiveFileName.set("crelia-1.21.1-neoforge-$neoforgeVersion.jar")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
        from(compileCreliaLauncher.flatMap { it.destinationDirectory })
        from(stagingDir.map { it.dir("libraries") }) { into("META-INF/crelia-libraries") }
        from(stagingDir.map { it.file("crelia-libraries.index") }) { into("META-INF") }
        from(rootProject.file("folia-server/crelia-supported.json")) { into("META-INF") }
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "crelia.launcher.Main",
                    "Enable-Native-Access" to "ALL-UNNAMED",
                    "Crelia-NeoForge" to "true",
                    "Crelia-MC-Version" to "1.21.1",
                    "Crelia-NeoForge-Version" to neoforgeVersion,
                )
            )
        }
    }
}
