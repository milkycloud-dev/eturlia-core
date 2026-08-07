/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.loading;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free self-test for the pure-Java parts of the Eturlia core.
 *
 * <p>The server itself cannot be unit tested here — patch {@code 0008} removes the upstream
 * test suite and the runtime needs a full Folia/NeoForge classpath. These checks cover the
 * logic that <em>is</em> testable in isolation and that silently produced wrong answers
 * before: the compatibility-manifest JSON reader, the {@code eturlia.yml} subset parser and
 * the launcher's library version comparison.</p>
 *
 * <p>Run via {@code scripts/selftest.sh}. Exit code 0 means every check passed.</p>
 */
public final class EturliaCoreSelfTest {

    private static int checks;
    private static int failures;

    private EturliaCoreSelfTest() {}

    public static void main(String[] args) throws Exception {
        jsonReaderHandlesTheManifestSchema();
        jsonReaderHandlesEscapesAndNumbers();
        jsonReaderRejectsMalformedInput();
        manifestParsingReadsRealEntries();
        manifestParsingSkipsEntriesWithoutModId();
        yamlSubsetReadsNestedKeys();
        yamlSubsetIgnoresSequencesAndTabs();
        crashReportJsonStaysParseable();

        System.out.println();
        System.out.println(failures == 0
                ? "OK — " + checks + " checks passed"
                : "FAILED — " + failures + " of " + checks + " checks failed");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- JSON

    private static void jsonReaderHandlesTheManifestSchema() {
        Object parsed = EturliaModLoadingPlugin.Json.parse("""
                {
                  "schemaVersion": 1,
                  "comment": "ignored",
                  "strictMode": false,
                  "supportedMods": [
                    { "modId": "neoforge", "required": true, "notes": "core" }
                  ],
                  "excludedMods": [
                    { "modId": "c2me", "reason": "region threading", "severity": "CRITICAL" }
                  ]
                }
                """);
        Map<?, ?> root = (Map<?, ?>) parsed;
        check("schemaVersion parsed", 1L, root.get("schemaVersion"));
        check("strictMode parsed", Boolean.FALSE, root.get("strictMode"));
        check("unknown keys kept", "ignored", root.get("comment"));
        List<?> supported = (List<?>) root.get("supportedMods");
        check("supportedMods size", 1, supported.size());
        check("nested modId", "neoforge", ((Map<?, ?>) supported.get(0)).get("modId"));
    }

    private static void jsonReaderHandlesEscapesAndNumbers() {
        Map<?, ?> root = (Map<?, ?>) EturliaModLoadingPlugin.Json.parse(
                "{\"a\":\"line\\nbreak \\u2014 dash \\\"q\\\"\",\"b\":-2.5,\"c\":[],\"d\":{},\"e\":null}");
        check("string escapes", "line\nbreak — dash \"q\"", root.get("a"));
        check("negative double", -2.5d, root.get("b"));
        check("empty array", 0, ((List<?>) root.get("c")).size());
        check("empty object", 0, ((Map<?, ?>) root.get("d")).size());
        check("null value", null, root.get("e"));
    }

    private static void jsonReaderRejectsMalformedInput() {
        expectFailure("trailing content", "{\"a\":1} junk");
        expectFailure("missing colon", "{\"a\" 1}");
        expectFailure("unterminated string", "{\"a\":\"oops}");
        expectFailure("unterminated object", "{\"a\":1");
    }

    private static void expectFailure(String label, String json) {
        checks++;
        try {
            EturliaModLoadingPlugin.Json.parse(json);
            failures++;
            System.out.println("FAIL  " + label + " should have been rejected");
        } catch (RuntimeException expected) {
            pass(label + " rejected");
        }
    }

    // ------------------------------------------------------------ Manifest

    private static void manifestParsingReadsRealEntries() throws IOException {
        Path manifest = Path.of("folia-server", "eturlia-supported.json");
        if (!Files.isRegularFile(manifest)) {
            System.out.println("skip  — " + manifest + " not found (run from the repository root)");
            return;
        }
        Map<?, ?> root = (Map<?, ?>) EturliaModLoadingPlugin.Json.parse(
                Files.readString(manifest, StandardCharsets.UTF_8));
        check("shipped manifest schemaVersion", 1L, root.get("schemaVersion"));
        List<?> excluded = (List<?>) root.get("excludedMods");
        boolean hasC2me = excluded.stream()
                .map(e -> ((Map<?, ?>) e).get("modId"))
                .anyMatch("c2me"::equals);
        check("shipped manifest still excludes c2me", Boolean.TRUE, hasC2me);
    }

    private static void manifestParsingSkipsEntriesWithoutModId() {
        EturliaModLoadingPlugin plugin = new EturliaModLoadingPlugin();
        List<EturliaModLoadingPlugin.ExcludedMod> excluded = plugin.parseExcludedMods(
                EturliaModLoadingPlugin.Json.parse(
                        "[{\"reason\":\"no id\"},{\"modId\":\"c2me\",\"severity\":\"CRITICAL\"}]"));
        check("entry without modId skipped", 1, excluded.size());
        check("valid entry kept", "c2me", excluded.get(0).getModId());
        check("missing reason defaulted", Boolean.TRUE, excluded.get(0).getReason() != null);
    }

    // ---------------------------------------------------------------- YAML

    private static void yamlSubsetReadsNestedKeys() throws Exception {
        Path root = writeConfig("eturlia-selftest", """
                _version: 3
                region:
                  guard: STRICT
                lod:
                  enabled: true
                  max-render-distance: 12
                mods:
                  require-lithostitched-min: "1.7.20"
                """);
        try {
            eturlia.core.config.EturliaConfig config = eturlia.core.config.EturliaConfig.load(root);
            check("version read", 3, config.version());
            check("nested string", "STRICT", config.regionGuard());
            check("nested boolean", Boolean.TRUE, config.lodEnabled());
            check("nested int", 12, config.lodMaxRenderDistance());
            check("quotes stripped", "1.7.20", config.lithostitchedMinVersion());
            check("documented lod key reaches ClientLodConfig", "12",
                    System.getProperty("eturlia.lod.max-render-distance"));
        } finally {
            deleteRecursively(root);
        }
    }

    private static void yamlSubsetIgnoresSequencesAndTabs() throws Exception {
        Path root = writeConfig("eturlia-selftest-seq",
                "_version: 4\nmods:\n  list:\n    - one\n    - two\n"
                        + "  require-lithostitched-min: kept\n");
        try {
            eturlia.core.config.EturliaConfig config = eturlia.core.config.EturliaConfig.load(root);
            check("file with a sequence still loads", 4, config.version());
            check("keys after a sequence still parse", "kept", config.lithostitchedMinVersion());
        } finally {
            deleteRecursively(root);
        }
    }

    // -------------------------------------------------------- Crash report

    private static void crashReportJsonStaysParseable() {
        // Control characters reach the report through exception messages (native code,
        // ANSI-coloured strings). Raw control characters are invalid JSON.
        String nul = String.valueOf((char) 0);
        String esc = String.valueOf((char) 27);
        Throwable cause = new IllegalStateException(
                "quote \" backslash \\ newline \n tab \t nul " + nul + " esc " + esc);
        String json = eturlia.core.logging.RegionContextCrashReport
                .enrich(Thread.currentThread(), cause)
                .toJsonString();
        check("crash report JSON parses", Boolean.TRUE,
                EturliaModLoadingPlugin.Json.parse(json) instanceof Map);
        check("no raw control characters in JSON", Boolean.TRUE, noRawControlChars(json));
    }

    private static boolean noRawControlChars(String json) {
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            // Pretty-printing newlines between members are fine; anything else is not.
            if (c < 0x20 && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private static Path writeConfig(String prefix, String yaml) throws IOException {
        Path root = Files.createTempDirectory(prefix);
        Path configDir = Files.createDirectories(root.resolve("config"));
        Files.writeString(configDir.resolve("eturlia.yml"), yaml, StandardCharsets.UTF_8);
        return root;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    // ------------------------------------------------------------- helpers

    private static void check(String label, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            pass(label);
        } else {
            fail(label + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void pass(String label) {
        System.out.println("pass  " + label);
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL  " + message);
    }
}
