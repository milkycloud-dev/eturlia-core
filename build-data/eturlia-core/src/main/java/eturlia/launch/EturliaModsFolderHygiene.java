/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Soft-disable known JPMS / Arclight conflicts in {@code mods/} without deleting jars.
 *
 * <p>Renames conflicting files to {@code *.jar.eturlia-skipped} so NeoForge will not
 * discover them. Operators can restore by renaming back. This is not "removing mods" —
 * Eturlia already provides Folia-safe equivalents (bundled spark; Sable needs no Arclight
 * AABB patch).</p>
 *
 * <p>Because this mutates the operator's {@code mods/} directory, it is configurable:</p>
 * <ul>
 *   <li>{@code -Deturlia.mods.hygiene=skip} — rename conflicting jars (default)</li>
 *   <li>{@code -Deturlia.mods.hygiene=warn} — only report them, change nothing</li>
 *   <li>{@code -Deturlia.mods.hygiene=off} — do not even scan</li>
 * </ul>
 */
final class EturliaModsFolderHygiene {

    private static final String SKIP_SUFFIX = ".eturlia-skipped";

    /** Selects rename / report-only / disabled behaviour. */
    private static final String PROP_MODE = "eturlia.mods.hygiene";

    enum Mode {
        SKIP, WARN, OFF;

        static Mode current() {
            String raw = System.getProperty(PROP_MODE);
            if (raw == null || raw.isBlank()) {
                return SKIP;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                System.err.println("[eturlia] Unknown " + PROP_MODE + "=" + raw
                        + " (expected skip|warn|off) — using skip");
                return SKIP;
            }
        }
    }

    private EturliaModsFolderHygiene() {}

    static void apply(Path gameDir) {
        Mode mode = Mode.current();
        if (mode == Mode.OFF) {
            return;
        }
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        try (Stream<Path> stream = Files.list(mods)) {
            stream.filter(Files::isRegularFile).forEach(jar -> maybeSkip(jar, mode));
        } catch (IOException e) {
            System.err.println("[eturlia] mods/ hygiene scan failed: " + e);
        }
    }

    private static void maybeSkip(Path jar, Mode mode) {
        String name = jar.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(SKIP_SUFFIX) || !lower.endsWith(".jar")) {
            return;
        }
        if (isSparkNeoforge(lower)) {
            skip(jar, mode, "spark-neoforge conflicts with Eturlia's Folia-bundled spark (JPMS). /spark stays available.");
            return;
        }
        if (isWorldEditNeoforge(lower)) {
            skip(jar, mode, "WorldEdit/FAWE as a NeoForge mod conflicts with Folia's region APIs."
                    + " Install the WorldEdit or FAWE Folia plugin in plugins/ instead.");
            return;
        }
        if (isStateTableReplacement(lower)) {
            skip(jar, mode, "it replaces the block-state tables Paper already replaced"
                    + " (Blocks.<clinit> dies with \"index_table is null\"). Paper's own tables"
                    + " already give you the memory saving this mod is for.");
            return;
        }
        if (isOriginalArclightSable(lower)) {
            skip(jar, mode, "Arclight sable AABB patch targets Arclight; Eturlia already has Folia bridges. Use arclight_sable_patch-*-eturlia-shim.jar if a modId placeholder is required.");
        }
    }

    private static boolean isSparkNeoforge(String lower) {
        return lower.startsWith("spark-") && lower.contains("neoforge");
    }

    /**
     * WorldEdit / FAWE shipped as a Forge-family mod. The Bukkit build belongs in
     * {@code plugins/}; the mod build drives chunk edits straight through APIs that assume a
     * single owning thread.
     */
    private static boolean isWorldEditNeoforge(String lower) {
        if (!(lower.contains("worldedit") || lower.contains("fastasyncworldedit")
                || lower.startsWith("fawe-"))) {
            return false;
        }
        return lower.contains("neoforge") || lower.contains("forge-") || lower.contains("fabric");
    }

    /**
     * Mods that reimplement {@code StateHolder}'s neighbour tables.
     *
     * <p>Paper replaced those tables with {@code ZeroCollidingReferenceStateTable}, so a mod that
     * swaps in its own is writing into a structure that no longer exists — the failure surfaces
     * far from the cause, inside {@code Blocks}' static initialiser. There is nothing to patch on
     * either side: both implementations are correct, and only one can own the table.</p>
     */
    private static boolean isStateTableReplacement(String lower) {
        String extra = System.getProperty("eturlia.compat.quarantine", "");
        for (String id : extra.split(",")) {
            String trimmed = id.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && lower.startsWith(trimmed)) {
                return true;
            }
        }
        return lower.startsWith("ferritecore");
    }

    private static boolean isOriginalArclightSable(String lower) {
        if (!lower.contains("arclight_sable_patch")) {
            return false;
        }
        // Keep eturlia-shim; skip only the upstream Arclight build.
        return !lower.contains("eturlia-shim") && !lower.contains("eturlia_shim");
    }

    private static void skip(Path jar, Mode mode, String reason) {
        if (mode == Mode.WARN) {
            System.out.println("[eturlia] " + jar.getFileName() + " is not compatible: " + reason
                    + " Left untouched (" + PROP_MODE + "=warn).");
            return;
        }
        Path target = jar.resolveSibling(jar.getFileName().toString() + SKIP_SUFFIX);
        if (Files.exists(target)) {
            // Never clobber an earlier soft-skip: that file is the operator's copy, and
            // REPLACE_EXISTING would silently discard it.
            System.out.println("[eturlia] " + jar.getFileName() + " is not compatible: " + reason
                    + " A previous soft-skip (" + target.getFileName() + ") already exists,"
                    + " so nothing was renamed — remove one of the two files.");
            return;
        }
        try {
            Files.move(jar, target);
            System.out.println("[eturlia] Soft-skipped (not deleted): " + jar.getFileName()
                    + " -> " + target.getFileName() + " — " + reason);
            System.out.println("[eturlia] Renaming it back re-triggers this on the next boot."
                    + " Remove the jar, or start with -D" + PROP_MODE + "=warn (report only)"
                    + " or -D" + PROP_MODE + "=off (disable this check).");
        } catch (IOException e) {
            System.err.println("[eturlia] Failed to soft-skip " + jar + ": " + e);
        }
    }
}
