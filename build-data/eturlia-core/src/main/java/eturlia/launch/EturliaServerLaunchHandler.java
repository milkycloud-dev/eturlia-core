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
        Path gameDir = resolveGameDir();
        EturliaModsFolderHygiene.apply(gameDir);

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

    private static Path resolveGameDir() {
        String prop = System.getProperty("eturlia.gameDir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gamedir = fmlPaths.getField("GAMEDIR").get(null);
            Path p = (Path) gamedir.getClass().getMethod("get").invoke(gamedir);
            if (p != null) {
                return p.toAbsolutePath().normalize();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // FMLPaths may not be ready yet, or GAMEDIR may not hold a Path on this build.
            // ClassCastException/NPE are not ReflectiveOperationException, and letting them
            // escape would abort mod-file discovery over a directory lookup.
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
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

        installEturliaRuntime(gameLayer, arguments);

        Method main = craftMain.getMethod("main", String[].class);
        String[] foliaArgs = stripFmlArgs(arguments);
        try {
            main.invoke(null, (Object) foliaArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause;
        }
    }

    /**
     * Installs the Eturlia runtime (startup banner, quiet console + diagnostics log,
     * region-aware event bus, region-annotated crash handler, mod compatibility report)
     * before handing control to CraftBukkit.
     *
     * <p>Previously nothing on the boot path referenced {@code eturlia.EturliaServer}, so all
     * of that was dead code while the README advertised it. It is called reflectively because
     * {@code eturlia-server-templates} is compiled after this module and is not on its compile
     * classpath.</p>
     *
     * <p>Failure here is logged and ignored: diagnostics must never stop a server booting.</p>
     */
    private static void installEturliaRuntime(ModuleLayer gameLayer, String[] arguments) {
        try {
            Class<?> eturliaServer = Class.forName(
                    "eturlia.EturliaServer", true, EturliaServerLaunchHandler.class.getClassLoader());
            Method install = eturliaServer.getMethod(
                    "installRuntime", Path.class, String.class, String.class);
            install.invoke(null, resolveGameDir(), argValue(arguments, "--fml.mcVersion"),
                    argValue(arguments, "--fml.neoForgeVersion"));
        } catch (ClassNotFoundException e) {
            System.out.println("[Eturlia] eturlia.EturliaServer not on the classpath — "
                    + "starting without the Eturlia runtime (no banner, no region crash reports)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                    ? e.getCause()
                    : e;
            System.out.println("[Eturlia] Eturlia runtime install failed (" + cause
                    + ") — continuing without it");
        }
    }

    /** Returns the value following {@code flag}, or an empty string when absent. */
    private static String argValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return "";
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
