package crelia.launch;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.fml.loading.moddiscovery.locators.NeoForgeDevProvider;
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
 * ModLauncher launch target {@code creliaserver}.
 *
 * <p>Uses NeoForge's <em>dev</em> discovery shape ({@link NeoForgeDevProvider}) so the
 * Folia AT-mapped server jar + NeoForge universal become the {@code minecraft}/{@code neoforge}
 * game modules and {@code mods/} is scanned via {@code ModsFolderLocator}. Starts Folia's
 * CraftBukkit entry instead of {@code net.minecraft.server.Main}.</p>
 *
 * <p>Unlike stock {@link CommonDevLaunchHandler}, game jars are taken from
 * {@code crelia.serverJar} / {@code crelia.neoforgeJar} (set by the outer launcher) instead of
 * classpath discovery — the Folia jar is only on {@code legacyClassPath}, not the bootstrap CL.</p>
 */
public final class CreliaServerLaunchHandler extends CommonDevLaunchHandler {

    @Override
    public String name() {
        return "creliaserver";
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
        List<Path> gameJars = resolveGameJars();
        System.out.println("[crelia] FML game jars for NeoForgeDevProvider: " + gameJars);
        additionalLocators.accept(new NeoForgeDevProvider(gameJars));
        // Keep UserdevLocator for any fml.modFolders extras; mods/ itself is ModsFolderLocator SPI
        Map<String, List<Path>> folders = getGroupedModFolders();
        additionalLocators.accept(new UserdevLocator(folders));
    }

    private static List<Path> resolveGameJars() {
        List<Path> paths = new ArrayList<>(2);
        Path server = requiredJarProperty("crelia.serverJar");
        paths.add(server);
        Path neoForge = optionalJarProperty("crelia.neoforgeJar");
        if (neoForge != null) {
            paths.add(neoForge);
        }
        return List.copyOf(paths);
    }

    private static Path requiredJarProperty(String key) {
        Path path = optionalJarProperty(key);
        if (path == null) {
            throw new IllegalStateException(
                    "Missing system property " + key + " — Crelia launcher must point at the Folia AT jar");
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
            // ModLauncher / FML also pass --launchTarget / --gameDir consumed earlier
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
