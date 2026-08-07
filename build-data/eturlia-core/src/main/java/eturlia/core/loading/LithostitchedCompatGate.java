/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.loading;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Blocks known-broken Lithostitched builds on Folia/Eturlia.
 *
 * <p>{@code lithostitched-1.7.10+beta4} boots and applies mixins, then crashes
 * chunk generation with {@code NoSuchElementException} in
 * {@code TemplateLists.getRandom} (Ruined Portal / Terralith+Incendium).
 * Require {@code ≥ 1.7.13}.</p>
 */
public final class LithostitchedCompatGate {

    private static final Logger LOGGER = Logger.getLogger("EturliaLithostitched");
    /** Minimum accepted release (no prerelease suffixes). */
    public static final String MIN_VERSION = "1.7.13";

    private LithostitchedCompatGate() {}

    /**
     * If Lithostitched is present and too old / prerelease, abort with a clear message.
     *
     * @return {@code true} if OK to continue (absent or supported)
     */
    public static boolean validateOrAbort() {
        Optional<String> version = findLithostitchedVersion();
        if (version.isEmpty()) {
            return true;
        }
        String raw = version.get();
        if (isSupported(raw)) {
            LOGGER.info("Lithostitched " + raw + " OK (require ≥ " + MIN_VERSION + ", no beta/alpha)");
            return true;
        }
        String msg = """
            ================================================================================
            ETURLIA REFUSED TO START: unsupported Lithostitched version

              Found:    lithostitched %s
              Required: ≥ %s (stable release, not beta/alpha)

            lithostitched-1.7.10+beta4 loads, then crashes Folia chunk generation:
              java.util.NoSuchElementException in TemplateLists.getRandom
              (RuinedPortalStructure + Terralith/Incendium)

            Fix:
              1) Replace lithostitched-*-beta*.jar with lithostitched ≥ 1.7.13
                 (same mod — update the jar, do not leave the pack without Lithostitched)
              2) Keep Terralith + Incendium as documented in PACK_COMPAT

            Override (NOT recommended): -Deturlia.lithostitched.allow-unsafe=true
            ================================================================================
            """.formatted(raw, MIN_VERSION);
        LOGGER.severe(msg);
        System.err.println(msg);
        if (Boolean.getBoolean("eturlia.lithostitched.allow-unsafe")) {
            LOGGER.warning("eturlia.lithostitched.allow-unsafe=true — continuing; expect chunk crashes");
            return true;
        }
        // Hard abort before world gen
        throw new IllegalStateException("Unsupported Lithostitched " + raw + "; need ≥ " + MIN_VERSION);
    }

    static boolean isSupported(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return false;
        }
        String v = rawVersion.trim().toLowerCase(Locale.ROOT);
        if (v.contains("beta") || v.contains("alpha") || v.contains("rc") || v.contains("snapshot")) {
            return false;
        }
        // strip build metadata after + or -
        int cut = indexOfMeta(v);
        String numeric = cut >= 0 ? v.substring(0, cut) : v;
        int[] got = parseSemver(numeric);
        int[] min = parseSemver(MIN_VERSION);
        if (got == null || min == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (got[i] > min[i]) {
                return true;
            }
            if (got[i] < min[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfMeta(String v) {
        int plus = v.indexOf('+');
        int dash = v.indexOf('-');
        if (plus < 0) {
            return dash;
        }
        if (dash < 0) {
            return plus;
        }
        return Math.min(plus, dash);
    }

    private static int[] parseSemver(String numeric) {
        String[] parts = numeric.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2].replaceAll("[^0-9].*$", "")) : 0;
            return new int[]{major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Optional<String> findLithostitchedVersion() {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Object modList = modListClass.getMethod("get").invoke(null);
            if (modList == null) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Optional<Object> modContainer = (Optional<Object>) modListClass
                    .getMethod("getModContainerById", String.class)
                    .invoke(modList, "lithostitched");
            if (modContainer == null || modContainer.isEmpty()) {
                return Optional.empty();
            }
            Object container = modContainer.get();
            Object modInfo = container.getClass().getMethod("getModInfo").invoke(container);
            Object ver = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
            return Optional.ofNullable(ver != null ? ver.toString() : null);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.FINE, "ModList not available yet", e);
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to query lithostitched version", e);
            return Optional.empty();
        }
    }
}
