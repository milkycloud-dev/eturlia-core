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


val creliaMixinSources = fileTree("src/main/java") { include("crelia/**/*.java") }

val compileCreliaMixins by tasks.registering(JavaCompile::class) {
    group = "build"
    description = "Compile Crelia region-threading mixins (requires Folia-Server jars)"
    onlyIf {
        rootProject.file("Folia-Server/build/libs").isDirectory && creliaMixinSources.files.isNotEmpty()
    }
    source = creliaMixinSources
    classpath = configurations.compileClasspath.get() +
        files(rootProject.fileTree("Folia-Server/build/libs") { include("*.jar") })
    destinationDirectory.set(layout.buildDirectory.dir("classes/creliaMixins"))
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("crelia-neoforge-extras")
    from(compileCreliaMixins.map { it.destinationDirectory })
    dependsOn(compileCreliaMixins)
    manifest {
        attributes(
            "Automatic-Module-Name" to "crelia.neoforge.extras",
            "FMLModType" to "GAMELIBRARY",
            "Specification-Title" to "crelia-neoforge-extras",
            "Implementation-Title" to "crelia-neoforge-extras",
            "Implementation-Version" to project.version,
            "Crelia-NeoForge-Version" to neoforgeVersion,
        )
    }
}

tasks.register<Jar>("neoforgeResourcesJar") {
    group = "build"
    description = "Package NeoForge/Crelia resources (mixins configs, mods.toml overlays)"
    archiveFileName.set("crelia-neoforge-resources.jar")
    from(sourceSets.main.get().output.resourcesDir)
    dependsOn(tasks.named("processResources"))
}

// neoforgeUniversal configuration is resolved by the standalone packager
