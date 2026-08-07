/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Soft-disable known JPMS / Arclight conflicts in {@code mods/} without deleting jars.
 *
 * <p>Renames conflicting files to {@code *.jar.eturlia-skipped} so NeoForge will not
 * discover them. Operators can restore by renaming back. This is not "removing mods" —
 * Eturlia already provides Folia-safe equivalents (bundled spark; Sable needs no Arclight
 * AABB patch).</p>
 */
final class EturliaModsFolderHygiene {

    private static final String SKIP_SUFFIX = ".eturlia-skipped";

    private EturliaModsFolderHygiene() {}

    static void apply(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        try (Stream<Path> stream = Files.list(mods)) {
            stream.filter(Files::isRegularFile).forEach(EturliaModsFolderHygiene::maybeSkip);
        } catch (IOException e) {
            System.err.println("[eturlia] mods/ hygiene scan failed: " + e);
        }
    }

    private static void maybeSkip(Path jar) {
        String name = jar.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(SKIP_SUFFIX) || !lower.endsWith(".jar")) {
            return;
        }
        if (isSparkNeoforge(lower)) {
            skip(jar, "spark-neoforge conflicts with Eturlia's Folia-bundled spark (JPMS). /spark stays available.");
            return;
        }
        if (isOriginalArclightSable(lower)) {
            skip(jar, "Arclight sable AABB patch targets Arclight; Eturlia already has Folia bridges. Use arclight_sable_patch-*-eturlia-shim.jar if a modId placeholder is required.");
        }
    }

    private static boolean isSparkNeoforge(String lower) {
        return lower.startsWith("spark-") && lower.contains("neoforge");
    }

    private static boolean isOriginalArclightSable(String lower) {
        if (!lower.contains("arclight_sable_patch")) {
            return false;
        }
        // Keep eturlia-shim; skip only the upstream Arclight build.
        return !lower.contains("eturlia-shim") && !lower.contains("eturlia_shim");
    }

    private static void skip(Path jar, String reason) {
        Path target = jar.resolveSibling(jar.getFileName().toString() + SKIP_SUFFIX);
        try {
            Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[eturlia] Soft-skipped (not deleted): " + jar.getFileName()
                    + " → " + target.getFileName() + " — " + reason);
        } catch (IOException e) {
            System.err.println("[eturlia] Failed to soft-skip " + jar + ": " + e);
        }
    }
}
