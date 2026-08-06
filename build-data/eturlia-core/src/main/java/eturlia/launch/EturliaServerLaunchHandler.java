package eturlia.launch;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.fml.loading.moddiscovery.locators.UserdevLocator;
import net.neoforged.fml.loading.targets.CommonDevLaunchHandler;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ModLauncher launch target {@code eturliaserver}.
 *
 * <p>Discovers Folia AT + NeoForge universal via {@link EturliaGameLocator}, scans {@code mods/}
 * through FML's {@code ModsFolderLocator}, then starts Folia CraftBukkit
 * ({@code org.bukkit.craftbukkit.Main}) instead of {@code net.minecraft.server.Main}.</p>
 */
public final class EturliaServerLaunchHandler extends CommonDevLaunchHandler {

    @Override
    public String name() {
        return "eturliaserver";
    }

    @Override
    public Dist getDist() {
        return Dist.DEDICATED_SERVER;
    }

    @Override
    public void collectAdditionalModFileLocators(
            VersionInfo versionInfo,
            Consumer<IModFileCandidateLocator> additionalLocators
    ) {
        Path serverJar = requiredJarProperty("eturlia.serverJar");
        Path neoForgeJar = requiredJarProperty("eturlia.neoforgeJar");
        Path apiJar = optionalJarProperty("eturlia.apiJar");
        Path commonsLang2 = optionalJarProperty("eturlia.commonsLang2Jar");
        Path brigadier = optionalJarProperty("eturlia.brigadierJar");
        java.util.List<Path> sparkJars = new java.util.ArrayList<>();
        String sparkProp = System.getProperty("eturlia.sparkJars");
        if (sparkProp != null && !sparkProp.isBlank()) {
            for (String part : sparkProp.split(java.io.File.pathSeparator)) {
                if (part.isBlank()) continue;
                Path p = Path.of(part).toAbsolutePath().normalize();
                if (java.nio.file.Files.isRegularFile(p)) {
                    sparkJars.add(p);
                }
            }
        }
        System.out.println("[eturlia] FML game locator: folia=" + serverJar
                + " api=" + apiJar + " neoforge=" + neoForgeJar
                + " commons-lang2=" + commonsLang2 + " brigadier=" + brigadier
                + " spark=" + sparkJars);
        additionalLocators.accept(new EturliaGameLocator(
                serverJar, apiJar, neoForgeJar, commonsLang2, brigadier, sparkJars));
        Map<String, List<Path>> folders = getGroupedModFolders();
        additionalLocators.accept(new UserdevLocator(folders));
    }

    private static Path requiredJarProperty(String key) {
        Path path = optionalJarProperty(key);
        if (path == null) {
            throw new IllegalStateException(
                    "Missing system property " + key + " — Eturlia launcher must set game jar paths");
        }
        return path;
    }

    private static Path optionalJarProperty(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(key + " does not exist or is not a file: " + path);
        }
        return path;
    }

    @Override
    public void runService(String[] arguments, ModuleLayer gameLayer) throws Throwable {
        Module minecraft = gameLayer.findModule("minecraft")
                .orElseThrow(() -> new IllegalStateException(
                        "Module 'minecraft' missing from game layer — Folia/NeoForge discovery failed"));
        Class<?> craftMain = Class.forName(minecraft, "org.bukkit.craftbukkit.Main");
        if (craftMain == null) {
            throw new ClassNotFoundException("org.bukkit.craftbukkit.Main not in module minecraft");
        }
        Method main = craftMain.getMethod("main", String[].class);
        String[] foliaArgs = stripFmlArgs(arguments);
        try {
            main.invoke(null, (Object) foliaArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause;
        }
    }

    /** CraftBukkit's joptsimple rejects unknown {@code --fml.*} flags. */
    static String[] stripFmlArgs(String[] args) {
        List<String> out = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--fml.")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    i++;
                }
                continue;
            }
            if (arg.equals("--launchTarget") || arg.equals("--gameDir")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    i++;
                }
                continue;
            }
            out.add(arg);
        }
        return out.toArray(new String[0]);
    }
}
