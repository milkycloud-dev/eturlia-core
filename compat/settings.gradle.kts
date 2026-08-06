/*
 * Eturlia Compat — Multi-module Gradle Settings
 *
 * This settings file defines the subprojects for the Eturlia compatibility module suite.
 * Each subproject is a standalone NeoForge mod that provides region-threading compatibility
 * for a specific target mod on Folia-based (Eturlia) servers.
 *
 * To add a new compat module:
 *   1. Create a new directory alongside the existing modules
 *   2. Add a `build.gradle.kts` following the existing pattern
 *   3. Include it below via `include(":eturlia-compat-<name>")`
 */

rootProject.name = "eturlia-compat"

include(
    ":eturlia-compat-create",
    ":eturlia-compat-sable"
)
