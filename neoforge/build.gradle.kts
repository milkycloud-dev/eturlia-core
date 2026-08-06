plugins {
    `java-library`
}

val neoforgeVersion = rootProject.property("neoforgeVersion").toString()
val fmlLoaderVersion = rootProject.property("fmlLoaderVersion").toString()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

configurations {
    create("neoforgeUniversal") {
        isCanBeConsumed = true
        isCanBeResolved = true
    }
}

dependencies {
    // Runtime NeoForge (includes embedded upstream coremods via JarJar)
    add("neoforgeUniversal", "net.neoforged:neoforge:$neoforgeVersion:universal")

    compileOnly("net.neoforged.fancymodloader:loader:$fmlLoaderVersion")
    compileOnly("net.neoforged:bus:8.0.5")
    compileOnly("org.spongepowered:mixin:0.8.7")
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("net.neoforged:neoforge:$neoforgeVersion:universal")

    api(project(":neoforge:coremods"))
}

sourceSets {
    main {
        // NeoForge classes come from the published universal jar.
        java.setSrcDirs(emptyList<File>())
        resources {
            srcDir("src/main/resources")
            srcDir("src/generated/resources")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}


val eturliaMixinSources = fileTree("src/main/java") { include("eturlia/**/*.java") }

val compileEturliaMixins by tasks.registering(JavaCompile::class) {
    group = "build"
    description = "Compile Eturlia region-threading mixins (optional; requires Folia-Server jars + -Peturlia.compileMixins)"
    onlyIf {
        project.hasProperty("eturlia.compileMixins") &&
            rootProject.file("Folia-Server/build/libs").isDirectory &&
            eturliaMixinSources.files.isNotEmpty()
    }
    source = eturliaMixinSources
    classpath = configurations.compileClasspath.get() +
        files(rootProject.fileTree("Folia-Server/build/libs") { include("*.jar") }) +
        files(rootProject.fileTree("Folia-API/build/libs") { include("*.jar") })
    destinationDirectory.set(layout.buildDirectory.dir("classes/eturliaMixins"))
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("eturlia-neoforge-extras")
    from(compileEturliaMixins.map { it.destinationDirectory })
    dependsOn(compileEturliaMixins)
    manifest {
        attributes(
            "Automatic-Module-Name" to "eturlia.neoforge.extras",
            "FMLModType" to "GAMELIBRARY",
            "Specification-Title" to "eturlia-neoforge-extras",
            "Implementation-Title" to "eturlia-neoforge-extras",
            "Implementation-Version" to project.version,
            "Eturlia-NeoForge-Version" to neoforgeVersion,
        )
    }
}

tasks.register<Jar>("neoforgeResourcesJar") {
    group = "build"
    description = "Package NeoForge/Eturlia resources (mixins configs, mods.toml overlays)"
    archiveFileName.set("eturlia-neoforge-resources.jar")
    from(sourceSets.main.get().output.resourcesDir)
    dependsOn(tasks.named("processResources"))
}

// neoforgeUniversal configuration is resolved by the standalone packager
