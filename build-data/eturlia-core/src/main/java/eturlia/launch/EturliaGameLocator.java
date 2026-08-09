package eturlia.launch;

import com.electronwill.nightconfig.core.Config;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.JarContentsBuilder;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.fml.loading.moddiscovery.NightConfigWrapper;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Path;
import java.util.List;

/**
 * Provides {@code minecraft} (Folia AT jar) + {@code neoforge} (universal) without
 * {@link net.neoforged.fml.loading.moddiscovery.locators.NeoForgeDevProvider}'s package leakage.
 *
 * <p>Folia embeds Mojang libs that also exist on {@code legacyClassPath}; those packages are
 * filtered out of the minecraft module so authlib/brigadier/DFU/logging stay separate modules.</p>
 */
final class EturliaGameLocator implements IModFileCandidateLocator {

    /** Resource prefixes to leave to dedicated legacy jars (avoid JPMS split packages). */
    private static final String[] EXCLUDED_FROM_FOLIA = {
            "com/mojang/authlib/",
            // brigadier is unioned into minecraft (Folia patches + stock classes); see roots below
            "com/mojang/datafixers/",
            "com/mojang/serialization/",
            "com/mojang/logging/",
    };

    private final Path foliaAtJar;
    private final Path foliaApiJar;
    private final Path neoForgeUniversalJar;
    private final Path commonsLang2Jar;
    private final Path brigadierJar;
    private final java.util.List<Path> sparkJars;

    EturliaGameLocator(
            Path foliaAtJar,
            Path foliaApiJar,
            Path neoForgeUniversalJar,
            Path commonsLang2Jar,
            Path brigadierJar,
            java.util.List<Path> sparkJars
    ) {
        this.foliaAtJar = foliaAtJar;
        this.foliaApiJar = foliaApiJar;
        this.neoForgeUniversalJar = neoForgeUniversalJar;
        this.commonsLang2Jar = commonsLang2Jar;
        this.brigadierJar = brigadierJar;
        this.sparkJars = sparkJars == null ? java.util.List.of() : sparkJars;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        System.out.println("[eturlia] EturliaGameLocator.findCandidates start");
        try {
            IModFile minecraft = buildMinecraftModFile();
            boolean mcOk = pipeline.addModFile(minecraft);
            System.out.println("[eturlia] minecraft ok=" + mcOk + " file=" + minecraft.getFileName()
                    + " type=" + minecraft.getType() + " module=" + minecraft.getModFileInfo().moduleName());

            JarContents neoContents = new JarContentsBuilder().paths(neoForgeUniversalJar).build();
            IModFile neo = JarModsDotTomlModFileReader.createModFile(
                    neoContents, ModFileDiscoveryAttributes.DEFAULT);
            if (neo == null) {
                throw new IllegalStateException("JarModsDotTomlModFileReader returned null for " + neoForgeUniversalJar);
            }
            boolean neoOk = pipeline.addModFile(neo);
            System.out.println("[eturlia] neoforge ok=" + neoOk + " file=" + neo.getFileName()
                    + " type=" + neo.getType() + " module=" + neo.getModFileInfo().moduleName());
        } catch (Throwable t) {
            System.err.println("[eturlia] EturliaGameLocator.findCandidates FAILED:");
            t.printStackTrace(System.err);
            throw t instanceof RuntimeException re ? re : new RuntimeException(t);
        }
    }

    private IModFile buildMinecraftModFile() {
        // Folia AT + Paper API share the minecraft module. commons-lang 2.x cannot be a JPMS
        // module (reserved package org.apache.commons.lang.enum) — union only safe packages in.
        //
        // JarContentsImpl.readManifestAndSigningData walks roots LAST→FIRST and returns the
        // first MANIFEST it finds (pathFilter does NOT apply). Folia AT must be last so its
        // Implementation-Vendor (build date) wins over commons-lang / folia-api.
        java.util.ArrayList<Path> roots = new java.util.ArrayList<>();
        if (commonsLang2Jar != null) {
            roots.add(commonsLang2Jar);
        }
        if (brigadierJar != null) {
            // Stock brigadier first; Folia AT overlays patched ArgumentBuilder/CommandNode last.
            roots.add(brigadierJar);
        }
        for (Path spark : sparkJars) {
            roots.add(spark);
        }
        if (foliaApiJar != null) {
            roots.add(foliaApiJar);
        }
        roots.add(foliaAtJar);
        JarContents contents = new JarContentsBuilder()
                .paths(roots.toArray(Path[]::new))
                .pathFilter(EturliaGameLocator::includeFoliaEntry)
                .build();
        ModJarMetadata metadata = new ModJarMetadata(contents);
        SecureJar secureJar = SecureJar.from(contents, metadata);
        IModFile modFile = IModFile.create(secureJar, EturliaGameLocator::buildMinecraftModInfo);
        metadata.setModFile(modFile);
        return modFile;
    }

    /** Same metadata as FML's package-private {@code MinecraftModInfo}. */
    private static IModFileInfo buildMinecraftModInfo(IModFile modFile) {
        ModFile file = (ModFile) modFile;
        Config config = Config.inMemory();
        config.set("modLoader", "minecraft");
        config.set("loaderVersion", "1");
        config.set("license", "Mojang Studios, All Rights Reserved");
        Config mod = Config.inMemory();
        mod.set("modId", "minecraft");
        mod.set("version", FMLLoader.versionInfo().mcVersion());
        mod.set("displayName", "Minecraft");
        mod.set("description", "Minecraft");
        config.set("mods", List.of(mod));
        NightConfigWrapper wrapper = new NightConfigWrapper(config);
        return new ModFileInfo(file, wrapper, wrapper::setFile, List.of());
    }

    /** MANIFEST and signature entries — the only META-INF content a secondary jar must not add. */
    private static boolean isManifestOrSignature(String path) {
        if (path.equals("META-INF") || path.equals("META-INF/")) {
            return true;
        }
        if (!path.startsWith("META-INF/")) {
            return false;
        }
        String name = path.substring("META-INF/".length());
        if (name.indexOf('/') >= 0) {
            return false; // nested data such as META-INF/maven/... is safe to keep
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static boolean includeFoliaEntry(String relativePath, Path basePath) {
        String path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        // Locale.ROOT: a default-locale toLowerCase() maps 'I' to a dotless 'ı' under tr_TR,
        // which would silently break these jar-name checks and therefore the classpath filter.
        String baseName = basePath == null
                ? ""
                : basePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        // Never let secondary jars overwrite Folia's MANIFEST (CraftBukkit parses
        // Implementation-Vendor as a build date). commons-lang 2.x also ships a
        // reserved JPMS package segment "enum" — drop it.
        if (baseName.contains("commons-lang-2")) {
            if (path.startsWith("META-INF/") || path.equals("META-INF")) {
                return false;
            }
            if (path.startsWith("org/apache/commons/lang/enum/")
                    || path.equals("org/apache/commons/lang/enum")) {
                return false;
            }
            return true;
        }
        if (baseName.contains("brigadier-")) {
            // Folia AT overlays patched classes; drop stock MANIFEST only.
            return !(path.startsWith("META-INF/") || path.equals("META-INF"));
        }
        if (baseName.contains("spark-")) {
            return !(path.startsWith("META-INF/") || path.equals("META-INF"));
        }
        for (String excluded : EXCLUDED_FROM_FOLIA) {
            if (path.startsWith(excluded)) {
                return false;
            }
        }
        // folia-api may also ship a MANIFEST — keep Folia AT jar's attributes.
        // Also avoid split packages: AT owns io.papermc.paper.brigadier (TagParse, etc.);
        // PaperBrigadier from the API jar is injected into the AT jar at launch.
        if (baseName.contains("folia-api")) {
            // Drop only the manifest and signatures, never the whole META-INF: Bukkit reads its
            // own version from META-INF/maven/dev.folia/folia-api/pom.properties, and throwing
            // that away makes getBukkitVersion() return "Unknown-Version". EssentialsX then dies
            // with "Unknown-Version is not in valid version format", which disables the plugin and
            // leaves every command it registered (help, list, ...) dead in the command map.
            if (isManifestOrSignature(path)) {
                return false;
            }
            if (path.startsWith("io/papermc/paper/brigadier/")
                    || path.equals("io/papermc/paper/brigadier")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "eturlia game provider (" + foliaAtJar.getFileName()
                + (foliaApiJar != null ? " + " + foliaApiJar.getFileName() : "")
                + " + " + neoForgeUniversalJar.getFileName() + ")";
    }
}
