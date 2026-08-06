plugins {
    `java-library`
}

val fmlLoaderVersion: String = rootProject.property("fmlLoaderVersion").toString()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
}

dependencies {
    compileOnly("net.neoforged.fancymodloader:loader:$fmlLoaderVersion")
    compileOnly("cpw.mods:modlauncher:11.0.5")
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("crelia-neoforge-coremods")
    manifest {
        attributes(
            "Automatic-Module-Name" to "crelia.neoforge.coremods",
            "FMLModType" to "LIBRARY",
            "Specification-Title" to "crelia-neoforge-coremods",
            "Implementation-Title" to "crelia-neoforge-coremods",
            "Implementation-Version" to project.version,
        )
    }
}
