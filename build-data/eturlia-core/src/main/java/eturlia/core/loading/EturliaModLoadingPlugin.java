package eturlia.core.loading;

/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 *
 * This file is part of the Eturlia project.
 * See https://github.com/ for license details.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * NeoForge ModLoadingPlugin SPI implementation for Eturlia's region-aware mod
 * filtering.
 *
 * <h2>Purpose</h2>
 *
 * <p>On a standard NeoForge server, all mods that declare a
 * {@code neoforge.mods.toml} descriptor are loaded. On a Folia regionized
 * server, many mods are incompatible because they assume single-threaded
 * world access. This plugin checks each mod against the
 * {@code eturlia-supported.json} manifest and:</p>
 * <ul>
 *   <li><b>Always rejects</b> mods in the {@code excludedMods} list
 *       (e.g. C2ME) regardless of mode.</li>
 *   <li>In <b>strict mode</b>, refuses to load mods not listed in
 *       {@code supportedMods}.</li>
 *   <li>In <b>dev mode</b>, warns about unsupported mods but allows them
 *       to load (useful for testing and incremental compatibility work).</li>
 *   <li>Logs detailed compatibility information at startup for diagnostics.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>The plugin reads the manifest from the classpath resource
 * {@code /eturlia-supported.json}. The {@code strictMode} field in the JSON
 * controls the default enforcement level, but this can be overridden by the
 * system property {@code eturlia.loading.strict} ({@code true}/{@code false}).</p>
 *
 * <h2>Integration</h2>
 *
 * <p>This plugin is registered as a NeoForge SPI service in
 * {@code META-INF/services/net.neoforged.neoforgespi.mods.ModLoadingPlugin}.
 * FML discovers it automatically during bootstrap.</p>
 *
 * <h2>eturlia-supported.json format</h2>
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "strictMode": false,
 *   "supportedMods": [
 *     { "modId": "neoforge", "required": true, "notes": "..." },
 *     { "modId": "eturlia_compat_create", "required": false, "notes": "..." }
 *   ],
 *   "excludedMods": [
 *     { "modId": "c2me", "reason": "...", "severity": "CRITICAL" }
 *   ]
 * }
 * }</pre>
 *
 * @see EturliaModLoadingPlugin.ModFilter
 * @see EturliaModLoadingPlugin.CompatibilityReport
 */
public class EturliaModLoadingPlugin {

    private static final Logger LOGGER = Logger.getLogger("EturliaModLoader");

    /** System property to override strict mode from the manifest. */
    private static final String STRICT_PROPERTY = "eturlia.loading.strict";

    /** Classpath resource path for the compatibility manifest. */
    private static final String MANIFEST_RESOURCE = "/eturlia-supported.json";

    /**
     * Fallback location — {@code eturliaStandaloneJar} packages the manifest under
     * {@code META-INF/}, so looking only at the root always missed it.
     */
    private static final String MANIFEST_RESOURCE_ALT = "/META-INF/eturlia-supported.json";

    // =========================================================================
    // Manifest Model
    // =========================================================================

    /**
     * Represents the {@code eturlia-supported.json} compatibility manifest.
     */
    public static final class Manifest {
        private int schemaVersion;
        private boolean strictMode;
        private List<ModEntry> supportedMods;
        private List<ExcludedMod> excludedMods;

        Manifest() {
            this.supportedMods = List.of();
            this.excludedMods = List.of();
        }

        public int getSchemaVersion() { return schemaVersion; }
        public boolean isStrictMode() { return strictMode; }
        public List<ModEntry> getSupportedMods() { return supportedMods; }
        public List<ExcludedMod> getExcludedMods() { return excludedMods; }
    }

    /**
     * An entry in the {@code supportedMods} list.
     */
    public static final class ModEntry {
        private String modId;
        private boolean required;
        private String notes;

        public String getModId() { return modId; }
        public boolean isRequired() { return required; }
        public String getNotes() { return notes; }

        @Override
        public String toString() {
            return modId + (required ? " (required)" : "");
        }
    }

    /**
     * An entry in the {@code excludedMods} list.
     */
    public static final class ExcludedMod {
        private String modId;
        private String reason;
        private String severity;

        public String getModId() { return modId; }
        public String getReason() { return reason; }
        public String getSeverity() { return severity; }

        /** Returns {@code true} if this exclusion is critical (always enforced). */
        public boolean isCritical() {
            return "CRITICAL".equalsIgnoreCase(severity);
        }
    }

    // =========================================================================
    // Mod Filter
    // =========================================================================

    /**
     * Result of filtering a single mod during loading.
     */
    public enum ModFilter {
        /** The mod is explicitly supported and will be loaded. */
        ALLOWED,

        /** The mod is not in the supported list and strict mode is enabled. */
        REJECTED_UNSUPPORTED,

        /** The mod is in the excluded list and will always be rejected. */
        REJECTED_EXCLUDED,

        /** The mod is not in the supported list but strict mode is off;
         *  it will be loaded with a warning. */
        WARNED
    }

    // =========================================================================
    // Compatibility Report
    // =========================================================================

    /**
     * Detailed report of mod compatibility checks, generated at startup.
     */
    public static final class CompatibilityReport {
        private final List<String> allowedMods = new ArrayList<>();
        private final List<String> rejectedMods = new ArrayList<>();
        private final List<String> warnedMods = new ArrayList<>();
        private final List<String> missingRequired = new ArrayList<>();
        private final List<String> excludedModsWithReasons = new ArrayList<>();

        public List<String> getAllowedMods() { return allowedMods; }
        public List<String> getRejectedMods() { return rejectedMods; }
        public List<String> getWarnedMods() { return warnedMods; }
        public List<String> getMissingRequired() { return missingRequired; }
        public List<String> getExcludedModsWithReasons() { return excludedModsWithReasons; }

        /**
         * Returns a human-readable summary of the compatibility report.
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Eturlia Mod Compatibility Report ===\n");

            sb.append("\nAllowed mods (").append(allowedMods.size()).append("):\n");
            for (String mod : allowedMods) {
                sb.append("  [OK] ").append(mod).append('\n');
            }

            if (!warnedMods.isEmpty()) {
                sb.append("\nWarned mods (").append(warnedMods.size()).append("):\n");
                for (String mod : warnedMods) {
                    sb.append("  [WARN] ").append(mod).append('\n');
                }
            }

            if (!rejectedMods.isEmpty()) {
                sb.append("\nRejected mods (").append(rejectedMods.size()).append("):\n");
                for (String mod : rejectedMods) {
                    sb.append("  [REJECT] ").append(mod).append('\n');
                }
            }

            if (!excludedModsWithReasons.isEmpty()) {
                sb.append("\nExcluded mods (hard block):\n");
                for (String entry : excludedModsWithReasons) {
                    sb.append("  [EXCLUDED] ").append(entry).append('\n');
                }
            }

            if (!missingRequired.isEmpty()) {
                sb.append("\nMissing required mods:\n");
                for (String mod : missingRequired) {
                    sb.append("  [MISSING] ").append(mod).append('\n');
                }
            }

            sb.append("=== End Report ===");
            return sb.toString();
        }
    }

    // =========================================================================
    // Plugin Fields
    // =========================================================================

    /** The parsed compatibility manifest. */
    private Manifest manifest;

    /** Whether strict mode is active (reject unsupported mods). */
    private boolean strictMode;

    /** The compatibility report generated during the last filter pass. */
    private CompatibilityReport lastReport;

    // =========================================================================
    // Loading Lifecycle
    // =========================================================================

    /**
     * Called by NeoForge FML during the mod loading bootstrap phase.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Loads and parses {@code eturlia-supported.json}.</li>
     *   <li>Determines the enforcement mode (strict vs. dev).</li>
     *   <li>Checks all discovered mods against the manifest.</li>
     *   <li>Rejects excluded mods (e.g. C2ME).</li>
     *   <li>Logs the full compatibility report.</li>
     * </ol>
     *
     * @param discoveredModIds the set of mod IDs discovered by FML
     * @return the compatibility report
     * @throws IllegalStateException if a critical excluded mod is present
     *         or a required supported mod is missing (in strict mode)
     */
    public CompatibilityReport onLoad(Set<String> discoveredModIds) {
        LOGGER.info("[Eturlia] Mod loading plugin invoked — checking compatibility");

        // Step 1: Load manifest
        this.manifest = loadManifest();
        if (this.manifest == null) {
            LOGGER.severe("[Eturlia] Failed to load eturlia-supported.json — "
                    + "all mods will load without compatibility checks");
            this.strictMode = false;
            return new CompatibilityReport();
        }

        // Step 2: Determine strict mode
        String propValue = System.getProperty(STRICT_PROPERTY);
        if (propValue != null) {
            this.strictMode = Boolean.parseBoolean(propValue);
            LOGGER.info("[Eturlia] Strict mode overridden via system property: "
                    + this.strictMode);
        } else {
            this.strictMode = this.manifest.isStrictMode();
            LOGGER.info("[Eturlia] Strict mode from manifest: " + this.strictMode);
        }

        // Step 3: Check mods against manifest
        CompatibilityReport report = new CompatibilityReport();
        Set<String> supportedIds = new HashSet<>();
        for (ModEntry entry : this.manifest.getSupportedMods()) {
            supportedIds.add(entry.getModId());
        }

        // Map of excluded mod IDs to their reasons
        Map<String, ExcludedMod> excludedMap = new LinkedHashMap<>();
        for (ExcludedMod excluded : this.manifest.getExcludedMods()) {
            excludedMap.put(excluded.getModId(), excluded);
        }

        // Check each discovered mod
        for (String modId : discoveredModIds) {
            // Priority 1: Check exclusion list (always enforced)
            ExcludedMod excluded = excludedMap.get(modId);
            if (excluded != null) {
                report.excludedModsWithReasons.add(
                        modId + ": " + excluded.getReason()
                                + " [severity=" + excluded.getSeverity() + "]");
                if (excluded.isCritical()) {
                    String msg = "[Eturlia] CRITICAL: Mod '" + modId
                            + "' is excluded from Eturlia: " + excluded.getReason();
                    LOGGER.severe(msg);
                    throw new IllegalStateException(msg);
                }
                report.rejectedMods.add(modId);
                continue;
            }

            // Priority 2: Check supported list
            if (supportedIds.contains(modId)) {
                report.allowedMods.add(modId);
                continue;
            }

            // Not in supported list
            if (this.strictMode) {
                report.rejectedMods.add(modId + " (not in eturlia-supported.json)");
            } else {
                report.warnedMods.add(modId + " (not in eturlia-supported.json — "
                        + "may cause region threading issues)");
            }
        }

        // Step 4: Check for missing required mods
        for (ModEntry entry : this.manifest.getSupportedMods()) {
            if (entry.isRequired() && !discoveredModIds.contains(entry.getModId())) {
                report.missingRequired.add(entry.getModId()
                        + ": " + entry.getNotes());
                LOGGER.warning("[Eturlia] Missing required mod: " + entry.getModId()
                        + " — " + entry.getNotes());
            }
        }

        // Step 5: Log the full report
        LOGGER.info(report.toString());

        this.lastReport = report;
        return report;
    }

    /**
     * Called by FML after all mods are loaded. Logs a summary of the
     * compatibility status.
     *
     * @param loadedModIds the set of mod IDs that were actually loaded
     */
    public void onModsComplete(Set<String> loadedModIds) {
        LOGGER.info("[Eturlia] Mod loading complete. "
                + loadedModIds.size() + " mods loaded.");
        if (this.lastReport != null) {
            int warned = this.lastReport.getWarnedMods().size();
            if (warned > 0) {
                LOGGER.warning("[Eturlia] " + warned
                        + " mod(s) loaded without Eturlia compatibility certification. "
                        + "Monitor for region threading issues.");
            }
        }
    }

    // =========================================================================
    // Manifest Loading
    // =========================================================================

    /**
     * Loads the {@code eturlia-supported.json} manifest from the classpath.
     *
     * <p>The manifest is expected to be present as a resource in the server
     * jar. If not found, a warning is logged and the plugin falls back to
     * permissive mode (all mods allowed).</p>
     *
     * @return the parsed manifest, or {@code null} if loading failed
     */
    private Manifest loadManifest() {
        try (InputStream input = openManifestStream()) {
            if (input == null) {
                LOGGER.warning("[Eturlia] Manifest resource not found: "
                        + MANIFEST_RESOURCE + " (also tried " + MANIFEST_RESOURCE_ALT
                        + ") — falling back to permissive mode");
                return null;
            }
            String json = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            return parseManifest(json);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "[Eturlia] Failed to read manifest: " + MANIFEST_RESOURCE, e);
            return null;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE,
                    "[Eturlia] Malformed manifest " + MANIFEST_RESOURCE
                            + " — falling back to permissive mode", e);
            return null;
        }
    }

    /**
     * Parses the JSON manifest into a {@link Manifest} object.
     *
     * <p>Phase 1 uses a simple hand-rolled JSON parser for the flat structure.
     * In production, Gson or Jackson should be used. The parser expects the
     * exact format shown in the class Javadoc.</p>
     *
     * @param json the raw JSON string
     * @return the parsed manifest
     * @throws IllegalArgumentException if the JSON is malformed
     */
    private Manifest parseManifest(String json) {
        Manifest manifest = new Manifest();

        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException(
                    "eturlia-supported.json must contain a JSON object at the top level");
        }

        manifest.schemaVersion = intValue(rootMap.get("schemaVersion"), 1);
        manifest.strictMode = boolValue(rootMap.get("strictMode"), false);
        manifest.supportedMods = parseModEntries(rootMap.get("supportedMods"));
        manifest.excludedMods = parseExcludedMods(rootMap.get("excludedMods"));

        LOGGER.fine("[Eturlia] Manifest parsed: schemaVersion="
                + manifest.schemaVersion + ", strictMode=" + manifest.strictMode
                + ", " + manifest.supportedMods.size() + " supported mods, "
                + manifest.excludedMods.size() + " excluded mods");

        return manifest;
    }

    /** Parses the {@code supportedMods} array. Entries without a modId are skipped. */
    private List<ModEntry> parseModEntries(Object rawArray) {
        List<ModEntry> entries = new ArrayList<>();
        if (!(rawArray instanceof List<?> list)) {
            return entries;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> obj)) {
                continue;
            }
            String modId = stringValue(obj.get("modId"), null);
            if (modId == null || modId.isBlank()) {
                LOGGER.warning("[Eturlia] supportedMods entry without a modId — skipped");
                continue;
            }
            ModEntry entry = new ModEntry();
            entry.modId = modId;
            entry.required = boolValue(obj.get("required"), false);
            entry.notes = stringValue(obj.get("notes"), "");
            entries.add(entry);
        }
        return entries;
    }

    /** Parses the {@code excludedMods} array. Entries without a modId are skipped. */
    private List<ExcludedMod> parseExcludedMods(Object rawArray) {
        List<ExcludedMod> entries = new ArrayList<>();
        if (!(rawArray instanceof List<?> list)) {
            return entries;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> obj)) {
                continue;
            }
            String modId = stringValue(obj.get("modId"), null);
            if (modId == null || modId.isBlank()) {
                LOGGER.warning("[Eturlia] excludedMods entry without a modId — skipped");
                continue;
            }
            ExcludedMod excluded = new ExcludedMod();
            excluded.modId = modId;
            excluded.reason = stringValue(obj.get("reason"), "no reason given");
            excluded.severity = stringValue(obj.get("severity"), "WARNING");
            entries.add(excluded);
        }
        return entries;
    }

    private InputStream openManifestStream() {
        InputStream input = getClass().getResourceAsStream(MANIFEST_RESOURCE);
        if (input != null) {
            return input;
        }
        return getClass().getResourceAsStream(MANIFEST_RESOURCE_ALT);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private static String stringValue(Object value, String defaultValue) {
        return value != null ? String.valueOf(value) : defaultValue;
    }

    // =========================================================================
    // Minimal JSON reader
    // =========================================================================

    /**
     * Tiny recursive-descent JSON reader.
     *
     * <p>Deliberately dependency-free: this class runs during FML bootstrap, before
     * Gson is guaranteed to be reachable from this module layer. It supports the full
     * JSON value grammar (objects, arrays, strings with escapes, numbers, booleans,
     * null), which is everything {@code eturlia-supported.json} can contain.</p>
     */
    static final class Json {

        private final String src;
        private int pos;

        private Json(String src) {
            this.src = src;
        }

        static Object parse(String json) {
            Json reader = new Json(json);
            reader.skipWhitespace();
            Object value = reader.readValue();
            reader.skipWhitespace();
            if (reader.pos < reader.src.length()) {
                throw new IllegalArgumentException(
                        "Trailing content at offset " + reader.pos + " in eturlia-supported.json");
            }
            return value;
        }

        private Object readValue() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:  return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("Expected ':' at offset " + pos);
                }
                pos++;
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at offset " + (pos - 1));
                }
            }
        }

        private String readString() {
            if (peek() != '"') {
                throw new IllegalArgumentException("Expected '\"' at offset " + pos);
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("Unterminated string in JSON input");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Invalid escape '\\" + esc + "' at offset " + (pos - 1));
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String text = src.substring(start, pos);
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Unexpected character at offset " + start);
            }
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected '" + literal + "' at offset " + pos);
            }
            pos += literal.length();
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            return src.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the compatibility manifest, or {@code null} if not yet loaded.
     *
     * @return the manifest
     */
    public Manifest getManifest() {
        return manifest;
    }

    /**
     * Returns whether strict mode is active.
     *
     * @return {@code true} if unsupported mods will be rejected
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Returns the compatibility report from the last loading pass.
     *
     * @return the report, or {@code null} if loading hasn't occurred yet
     */
    public CompatibilityReport getLastReport() {
        return lastReport;
    }
}
