plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.28-beta"
}

group = "com.eturlia"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven {
        name = "NeoForge"
        url = uri("https://maven.neoforged.net/releases/")
    }
    // Create publishes to https://maven.createmod.net (artifact create-1.21.1 for MC 1.21.1).
    // The old URL below never hosted Create and the coordinates below were for MC 1.18/1.20,
    // so this module could not resolve its dependencies at all.
    // maven { name = "Create"; url = uri("https://maven.createmod.net") }
}

dependencies {
    // NeoForge API — provides the mod loading framework and event bus
    implementation("net.neoforged:neoforge:21.1.248")

    // TODO: pin the real Create / Create Big Cannons coordinates for MC 1.21.1 before
    // re-enabling. Until then this module compiles against NeoForge only and its handlers
    // stay stubs — see compat/README.md.
    // compileOnly("com.simibubi.create:create-1.21.1:<version>")
    // compileOnly("rbasamoyai:createbigcannons-1.21.1:<version>")
}

tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(props)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
        }
    }
}

moddev {
    // NeoForge moddev configuration for local development
    neoForge {
        version = "21.1.248"
    }

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", "eturlia_compat_create")
        }
        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", "eturlia_compat_create")
        }
    }
}
