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
    // Sable / Create Aeronautics have no published Maven coordinates that resolve; the
    // placeholder repository below never hosted them, so this module could not resolve
    // its dependencies at all.
}

dependencies {
    // NeoForge API — provides the mod loading framework and event bus
    implementation("net.neoforged:neoforge:21.1.248")

    // TODO: add Sable / Create Aeronautics as local file dependencies (or real coordinates
    // once published) before re-enabling. Until then this module compiles against NeoForge
    // only and its handlers stay stubs — see compat/README.md.
    // compileOnly(files("libs/sable-<version>.jar"))
    // compileOnly(files("libs/create_aeronautics-<version>.jar"))
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
            systemProperty("neoforge.enabledGameTestNamespaces", "eturlia_compat_sable")
        }
        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", "eturlia_compat_sable")
        }
    }
}
