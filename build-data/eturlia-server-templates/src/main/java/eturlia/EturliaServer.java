package eturlia;

/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 *
 * This file is part of the Eturlia project.
 * See https://github.com/ for license details.
 */

// IMPORTANT: This class compiles against ONLY standard Java (java.util.*, java.util.concurrent.*).
// Paper/Folia/NeoForge/Minecraft types are not available on the compile classpath.
// All Paper/MC-specific parameters use Object as placeholders. At runtime, when the
// full server jar is on the classpath, type-safe wrappers will cast to the real types.

import eturlia.core.event.RegionAwareEventBus;
import eturlia.core.logging.RegionContextCrashReport;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * EturliaServer — the FML server entry point for a Folia regionized server.
 *
 * <p>This class bootstraps NeoForge FML before Folia takes over threading.
 * It serves as the main-class replacement that:</p>
 * <ol>
 *   <li>Parses FML launch arguments from the command line</li>
 *   <li>Loads FMLLoader via reflection (gracefully degrading to vanilla+Folia
 *       mode if FML is absent)</li>
 *   <li>Installs the {@link RegionAwareEventBus} for region-safe event
 *       dispatch</li>
 *   <li>Installs a crash report handler that enriches crash output with
 *       region context via {@link RegionContextCrashReport}</li>
 *   <li>Delegates to {@code MinecraftServer.main} or
 *       {@code CraftBukkit.Main} to start the actual server</li>
 * </ol>
 *
 * <h2>Bootstrap Sequence</h2>
 * <pre>
 *   main(args)
 *     ├─ parseArgs(args)           → extract --fml.mcVersion, --fml.neoForgeVersion
 *     ├─ new EturliaServer()        → singleton
 *     ├─ logBanner()               → version info
 *     ├─ bootstrapFML(args)        → reflection-based FMLLoader setup
 *     ├─ installEventBus()         → RegionAwareEventBus
 *     ├─ installCrashHandler()     → enriched crash reports
 *     ├─ log("Delegating to Folia regionized server bootstrap")
 *     └─ runServer(args)           → MinecraftServer.main / CraftBukkit
 * </pre>
 *
 * @see RegionAwareEventBus
 * @see RegionContextCrashReport
 */
public final class EturliaServer {

    private static final Logger LOGGER = Logger.getLogger("EturliaServer");

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                    .withZone(ZoneId.systemDefault());

    /** Eturlia version injected at build time. */
    private static final String ETURLIA_VERSION = resolveEturliaVersion();

    // =========================================================================
    // Singleton
    // =========================================================================

    private static volatile EturliaServer instance;

    /** The MinecraftServer instance — Object placeholder for net.minecraft.server.MinecraftServer. */
    private volatile Object server; // TODO: MinecraftServer

    /** Region-aware event bus for safe event dispatch on Folia region threads. */
    private volatile RegionAwareEventBus eventBus;

    /** Whether FML was successfully bootstrapped. */
    private volatile boolean fmlBootstrapped;

    /** Whether FML was found on the classpath at all. */
    private volatile boolean fmlAvailable;

    /** Parsed FML arguments. */
    private volatile String mcVersion = "unknown";
    private volatile String neoForgeVersion = "unknown";

    /** Absolute game directory used for FMLPaths (defaults to cwd). */
    private volatile Path gameDir = Path.of(".").toAbsolutePath().normalize();

    /**
     * Whether minimal FML path/environment state was initialised for Folia-first
     * NeoForge hooks (does <em>not</em> mean mods were loaded via ModLauncher).
     */
    private volatile boolean fmlPathsReady;

    /** Number of mods loaded (populated after FML bootstrap). */
    private final AtomicInteger modCount = new AtomicInteger(0);

    /** Crash-reports output directory — TODO: resolve from server config at runtime. */
    private volatile File crashReportsDir = new File("crash-reports");

    // =========================================================================
    // Bootstrap
    // =========================================================================

    /**
     * Main entry point. Bootstraps FML, installs the region-aware event bus
     * and crash handler, then delegates to the Minecraft server.
     *
     * @param args command-line arguments (including --fml.mcVersion, --fml.neoForgeVersion)
     */
    public static void main(String[] args) {
        // 1. Parse FML arguments
        Map<String, String> parsedArgs = parseArgs(args);

        // 2. Create singleton
        EturliaServer cs = new EturliaServer(parsedArgs);
        EturliaServer.instance = cs;

        // 3. Log banner
        cs.logBanner();

        // 4. Bootstrap FML
        try {
            cs.bootstrapFML(args);
        } catch (Exception e) {
            // FML bootstrap failure is not fatal — degrade to vanilla+Folia mode.
            // The server can still run; mods just won't be loaded.
            LOGGER.warning("FML bootstrap failed — continuing in vanilla+Folia mode: "
                    + e.getMessage());
        }

        // 5. Install RegionAwareEventBus
        cs.installEventBus();

        // 6. Install crash report handler
        cs.installCrashHandler();

        // 7. Log delegation
        LOGGER.info("Delegating to Folia regionized server bootstrap");

        // 8. Run server
        try {
            cs.runServer(args);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Server crashed during startup", e);
            cs.handleFatalCrash(e);
            System.exit(1);
        }
    }

    /**
     * Returns the EturliaServer singleton.
     *
     * @return the singleton instance, or null if not yet initialised
     */
    public static EturliaServer getInstance() {
        return instance;
    }

    private EturliaServer(Map<String, String> parsedArgs) {
        this.mcVersion = parsedArgs.getOrDefault("fml.mcVersion", "unknown");
        this.neoForgeVersion = parsedArgs.getOrDefault("fml.neoForgeVersion", "unknown");
        String gameDirArg = parsedArgs.get("gameDir");
        if (gameDirArg != null && !gameDirArg.isEmpty()) {
            this.gameDir = Path.of(gameDirArg).toAbsolutePath().normalize();
        } else {
            this.gameDir = Path.of(".").toAbsolutePath().normalize();
        }
    }

    // =========================================================================
    // Argument Parsing
    // =========================================================================

    /**
     * Parses known FML launch arguments from the command line.
     *
     * <p>Recognised arguments:</p>
     * <ul>
     *   <li>{@code --fml.mcVersion <version>}</li>
     *   <li>{@code --fml.neoForgeVersion <version>}</li>
     *   <li>{@code --fml.forgeGroup <group>}</li>
     *   <li>{@code --gameDir <path>}</li>
     *   <li>{@code --modsDir <path>}</li>
     * </ul>
     *
     * @param args raw command-line arguments
     * @return a map of argument key to value
     */
    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") && i + 1 < args.length) {
                String value = args[i + 1];
                // Skip if the next arg looks like another flag
                if (!value.startsWith("--")) {
                    String key = arg.substring(2);
                    result.put(key, value);
                    i++; // consume the value
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Banner
    // =========================================================================

    private void logBanner() {
        StringBuilder banner = new StringBuilder();
        banner.append('\n');
        banner.append("  ██████╗ ██████╗ ███╗   ██╗████████╗███████╗██████╗ \n");
        banner.append(" ██╔════╝██╔═══██╗████╗  ██║╚══██╔══╝██╔════╝██╔══██╗\n");
        banner.append(" ██║     ██║   ██║██╔██╗ ██║   ██║   █████╗  ██║  ██║\n");
        banner.append(" ██║     ██║   ██║██║╚██╗██║   ██║   ██╔══╝  ██║  ██║\n");
        banner.append(" ╚██████╗╚██████╔╝██║ ╚████║   ██║   ███████╗██████╔╝\n");
        banner.append("  ╚═════╝ ╚═════╝ ╚═╝  ╚═══╝   ╚═╝   ╚══════╝╚═════╝ \n");
        banner.append('\n');
        banner.append("  Eturlia v").append(ETURLIA_VERSION).append(" — NeoForge FML on Folia\n");
        banner.append("  MC Version       : ").append(mcVersion).append('\n');
        banner.append("  NeoForge Version : ").append(neoForgeVersion).append('\n');
        banner.append("  JVM              : ").append(System.getProperty("java.version")).append('\n');
        banner.append("  OS               : ").append(System.getProperty("os.name")).append('\n');
        banner.append("  Time             : ").append(TIMESTAMP_FORMATTER.format(Instant.now())).append('\n');
        banner.append('\n');

        LOGGER.info(banner.toString());
    }

    // =========================================================================
    // FML Bootstrap (Reflection)
    // =========================================================================

    /**
     * Attempts to bootstrap NeoForge FML using reflection.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Tries to load {@code net.neoforged.fml.loading.FMLLoader} via
     *       {@link Class#forName(String)}</li>
     *   <li>If found, calls {@code FMLLoader.beginEarlyModsLaunch()} then
     *       {@code FMLLoader.beginModsLaunch()} to trigger the FML mod loading
     *       pipeline</li>
     *   <li>If not found, logs a warning and gracefully continues in
     *       vanilla+Folia mode</li>
     * </ol>
     *
     * <p>TODO[NEOFORGE]: At runtime when the full NeoForge jar is on the
     * classpath, this will actually invoke FML loading. The reflection is
     * necessary because the FML classes are not available at compile time in
     * the template module.</p>
     *
     * @param args the launch arguments to pass to FML
     * @throws Exception if FML loading fails unexpectedly
     */
    private void bootstrapFML(String[] args) throws Exception {
        LOGGER.info("Attempting FML detection (FancyModLoader 4.0.x / NeoForge 21.1)...");

        Class<?> fmlLoaderClass;
        try {
            fmlLoaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader");
        } catch (ClassNotFoundException e) {
            fmlAvailable = false;
            fmlBootstrapped = false;
            LOGGER.warning("FML not found on classpath — running in Folia-only mode");
            return;
        }

        fmlAvailable = true;
        LOGGER.info("FMLLoader found: " + fmlLoaderClass.getName()
                + " (from " + fmlLoaderClass.getProtectionDomain().getCodeSource().getLocation() + ")");

        // FML 4.0.x (NeoForge 21.1) is designed to run under ModLauncher:
        //   beginModScan(ILaunchContext) / completeScan(ILaunchContext, List<String>)
        // Those APIs require a real ModLauncher launch context. Eturlia's standalone
        // launcher currently boots Folia first and does NOT own the ModLauncher
        // pipeline, so we must NOT pretend mods were loaded.
        boolean hasLegacyLaunch = hasMethod(fmlLoaderClass, "beginEarlyModsLaunch")
                || hasMethod(fmlLoaderClass, "beginModsLaunch", String[].class);
        boolean hasModernScan = false;
        try {
            Class<?> launchContext = Class.forName("net.neoforged.neoforgespi.ILaunchContext");
            hasModernScan = hasMethod(fmlLoaderClass, "beginModScan", launchContext);
        } catch (ClassNotFoundException e) {
            LOGGER.fine("ILaunchContext not found while probing FML API");
        }

        if (hasLegacyLaunch) {
            LOGGER.warning("Legacy FML launch methods detected — this build expects FML 4.0.x");
        }

        if (hasModernScan) {
            LOGGER.info("Detected FML 4.0.x scan API (beginModScan/completeScan). "
                    + "Full NeoForge mod loading requires ModLauncher; "
                    + "Eturlia is starting in Folia-first mode without completing an FML mod scan.");
            fmlBootstrapped = false;
        } else if (!hasLegacyLaunch) {
            LOGGER.warning("Unrecognized FMLLoader API — continuing in Folia-first mode");
            fmlBootstrapped = false;
        }

        // Folia-first still needs FMLPaths / Dist / FMLConfig so NeoForge
        // ServerLifecycleHooks (ConfigTracker) does not NPE immediately.
        try {
            initializeFmlRuntimeForFoliaFirst(fmlLoaderClass);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to prime Folia-first FML runtime (paths/Dist): " + e.getMessage(), e);
        }

        // Report loading mod list if ModLauncher already populated it (usually empty here).
        try {
            Method getModList = fmlLoaderClass.getMethod("getLoadingModList");
            Object modList = getModList.invoke(null);
            if (modList != null) {
                try {
                    Method sizeMethod = modList.getClass().getMethod("size");
                    int count = (int) sizeMethod.invoke(modList);
                    this.modCount.set(count);
                    LOGGER.info("FML LoadingModList size=" + count);
                } catch (NoSuchMethodException ignored) {
                    LOGGER.info("FML LoadingModList present (size API unavailable)");
                }
            }
        } catch (Exception e) {
            LOGGER.fine("Could not query FML LoadingModList: " + e.getMessage());
        }

        if (fmlBootstrapped) {
            LOGGER.info("FML bootstrap complete — NeoForge mod loading pipeline initialised");
        } else {
            LOGGER.warning("FML is on the classpath but NOT bootstrapped via ModLauncher. "
                    + "FMLPaths/Dist were primed for Folia-first NeoForge hooks; "
                    + "mods will NOT be loaded until ModLauncher owns the launch.");
        }
    }

    /**
     * Primes the subset of FML static state that NeoForge server lifecycle hooks
     * require when ModLauncher did not run {@code FMLLoader.setupLaunchHandler}.
     *
     * <p>Without this, {@code ConfigTracker.&lt;clinit&gt;} NPEs on
     * {@code FMLPaths.GAMEDIR.get()} as soon as
     * {@code ServerLifecycleHooks.handleServerAboutToStart} runs.</p>
     */
    private void initializeFmlRuntimeForFoliaFirst(Class<?> fmlLoaderClass) throws Exception {
        Class<?> fmlPathsClass = Class.forName("net.neoforged.fml.loading.FMLPaths");
        Method loadAbsolutePaths = fmlPathsClass.getMethod("loadAbsolutePaths", Path.class);
        loadAbsolutePaths.invoke(null, this.gameDir);
        LOGGER.info("FMLPaths.loadAbsolutePaths(" + this.gameDir + ")");

        // Dedicated-server Dist + production BEFORE anything touches FMLEnvironment.
        // FMLEnvironment.<clinit> copies FMLLoader.getDist()/isProduction().
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends Enum> distClass =
                (Class<? extends Enum>) Class.forName("net.neoforged.api.distmarker.Dist");
        Object dedicatedServer = Enum.valueOf(distClass, "DEDICATED_SERVER");
        setStaticField(fmlLoaderClass, "dist", dedicatedServer);
        setStaticField(fmlLoaderClass, "production", Boolean.TRUE);
        setStaticField(fmlLoaderClass, "gamePath", this.gameDir);

        try {
            Class<?> fmlConfig = Class.forName("net.neoforged.fml.loading.FMLConfig");
            fmlConfig.getMethod("load").invoke(null);
            LOGGER.info("FMLConfig.load() completed for Folia-first mode");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            LOGGER.log(Level.WARNING, "FMLConfig.load() failed (continuing): " + cause.getMessage(), cause);
        }

        this.fmlPathsReady = true;
        LOGGER.info("Folia-first FML runtime primed (paths + Dist.DEDICATED_SERVER)");
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Returns whether NeoForge {@code ServerLifecycleHooks} should be attempted.
     * True once FMLPaths were primed (Folia-first) or full FML bootstrap completed.
     */
    public static boolean areNeoForgeLifecycleHooksEnabled() {
        EturliaServer cs = instance;
        return cs != null && (cs.fmlBootstrapped || cs.fmlPathsReady);
    }

    /**
     * Best-effort NeoForge lifecycle hook invocation. Failures are logged and
     * swallowed so Folia can still reach {@code Done} when FML is only partially
     * initialised (e.g. NeoForgeConfig not loaded → PermissionAPI throws).
     */
    public static void safeNeoForgeLifecycle(String hookName, Runnable action) {
        if (!areNeoForgeLifecycleHooksEnabled()) {
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                    "NeoForge lifecycle hook '" + hookName
                            + "' failed (Folia-first / FML incomplete): " + t,
                    t);
        }
    }

    // =========================================================================
    // Event Bus Installation
    // =========================================================================

    /**
     * Creates and installs the {@link RegionAwareEventBus}.
     *
     * <p>The validation mode is read from the system property
     * {@code eturlia.event.validation} (defaults to WARN).</p>
     *
     * <p>TODO[FOLIA]: After installation, the event bus delegate should be
     * wired to the real Guava EventBus once NeoForge has initialised it.</p>
     */
    private void installEventBus() {
        RegionAwareEventBus.ValidationMode mode =
                RegionAwareEventBus.ValidationMode.fromSystemProperty();
        this.eventBus = new RegionAwareEventBus(mode);
        LOGGER.info("Region-aware event bus installed in " + mode + " mode");
    }

    // =========================================================================
    // Crash Handler Installation
    // =========================================================================

    /**
     * Installs a default uncaught exception handler on all threads that
     * enriches crash output with region context information.
     *
     * <p>The handler uses {@link RegionContextCrashReport} to produce both
     * human-readable and JSON crash reports.</p>
     */
    private void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.log(Level.SEVERE, "Uncaught exception on thread '" + thread.getName()
                    + "' (id=" + thread.getId() + ")", throwable);

            RegionContextCrashReport report = RegionContextCrashReport.enrich(thread, throwable);

            System.err.println("==== Eturlia Crash Report ====");
            System.err.println(report.toHumanReadable());
            System.err.println();
            System.err.println("==== Structured JSON ====");
            System.err.println(report.toJsonString());

            // Always dump the original throwable so logs keep the real stack.
            throwable.printStackTrace(System.err);

            writeCrashReportToFile(report);
        });
        LOGGER.info("Crash report handler installed");
    }

    /**
     * Writes a crash report to the crash-reports directory.
     *
     * <p>TODO[PAPER]: At runtime, use the server's configured crash-reports
     * directory instead of the hardcoded default.</p>
     *
     * @param report the enriched crash report
     */
    private void writeCrashReportToFile(RegionContextCrashReport report) {
        File dir = this.crashReportsDir;
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warning("Could not create crash-reports directory: " + dir.getAbsolutePath());
            return;
        }

        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        String filename = "crash-report-eturlia-" + timestamp + ".txt";

        File file = new File(dir, filename);
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println(report.toHumanReadable());
            pw.println();
            pw.println("==== Structured JSON ====");
            pw.println(report.toJsonString());
            LOGGER.info("Crash report written to: " + file.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to write crash report to " + file.getAbsolutePath(), e);
        }
    }

    // =========================================================================
    // Server Delegation
    // =========================================================================

    /**
     * Delegates to the Minecraft server's main method.
     *
     * <p>Tries, in order:</p>
     * <ol>
     *   <li>{@code net.minecraft.server.MinecraftServer.main(String[])}</li>
     *   <li>{@code org.bukkit.craftbukkit.Main.main(String[])}</li>
     * </ol>
     *
     * <p>TODO[PAPER]: At runtime, this should invoke Folia's
     * {@code RegionizedServer} bootstrap. The reflection is necessary because
     * MinecraftServer is not on the compile classpath.</p>
     *
     * @param args command-line arguments to forward to the server
     * @throws Exception if the server cannot be started
     */
    private void runServer(String[] args) throws Exception {
        String[] foliaArgs = stripFmlArgs(args);

        // Attempt 1: MinecraftServer.main(String[])
        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            Method mainMethod = serverClass.getMethod("main", String[].class);
            LOGGER.info("Delegating to MinecraftServer.main()");
            mainMethod.invoke(null, (Object) foliaArgs);
            return;
        } catch (ClassNotFoundException e) {
            LOGGER.fine("MinecraftServer not found — trying CraftBukkit Main");
        } catch (NoSuchMethodException e) {
            LOGGER.fine("MinecraftServer.main(String[]) not found — trying CraftBukkit Main");
        }

        // Attempt 2: CraftBukkit Main.main(String[])
        try {
            Class<?> craftMainClass = Class.forName("org.bukkit.craftbukkit.Main");
            Method mainMethod = craftMainClass.getMethod("main", String[].class);
            LOGGER.info("Delegating to CraftBukkit Main.main() with Folia args (FML flags stripped)");
            mainMethod.invoke(null, (Object) foliaArgs);
            return;
        } catch (ClassNotFoundException e) {
            LOGGER.fine("CraftBukkit Main not found");
        } catch (NoSuchMethodException e) {
            LOGGER.fine("CraftBukkit Main.main(String[]) not found");
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof Exception) throw (Exception) target;
            throw new RuntimeException(target);
        }

        throw new IllegalStateException(
                "No server entry point found on classpath. Expected "
                        + "net.minecraft.server.MinecraftServer or "
                        + "org.bukkit.craftbukkit.Main. "
                        + "Ensure the Minecraft/Folia server jar is on the classpath.");
    }

    /**
     * Removes {@code --fml.*} flags (and their values) so CraftBukkit's joptsimple
     * parser does not reject unrecognized NeoForge launcher arguments.
     */
    static String[] stripFmlArgs(String[] args) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--fml.")) {
                // Skip optional value if present and not another flag
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    i++;
                }
                continue;
            }
            out.add(arg);
        }
        return out.toArray(new String[0]);
    }

    // =========================================================================
    // Server Lifecycle
    // =========================================================================

    /**
     * Dispatches a server lifecycle tick through the region-aware event bus.
     *
     * <p>NeoForge fires {@code ServerTickEvent.Pre} and {@code ServerTickEvent.Post}
     * during each server tick. On a Folia regionized server, these must be routed
     * through the event bus to ensure they fire on the correct thread.</p>
     *
     * <p>TODO[FOLIA]: At runtime, this should be called from a Mixin into
     * {@code MinecraftServer#runServer()} that wraps the tick methods. The
     * {@code hasTime} supplier should delegate to Folia's
     * {@code RegionizedServer#tickServer(BooleanSupplier)}.</p>
     *
     * @param hasTime supplier that returns false when the tick budget is exhausted
     * @param isPre   true for ServerTickEvent.Pre, false for ServerTickEvent.Post
     */
    @SuppressWarnings("unused")
    public void dispatchLifecycleTick(BooleanSupplier hasTime, boolean isPre) {
        if (this.server == null || this.eventBus == null) return;

        RegionAwareEventBus bus = this.eventBus;

        // TODO: At runtime, schedule on GlobalRegionScheduler:
        //   srv.getServerLevel(Level.OVERWORLD).getServer().execute(() -> { ... });
        // For compilation, dispatch synchronously as a GLOBAL event:
        String eventName = isPre ? "ServerTickEvent.Pre" : "ServerTickEvent.Post";
        bus.postLifecycleEvent(eventName, hasTime, null);
    }

    /**
     * Intercepts world loading to set up region ownership tracking.
     *
     * <p>Called by a Mixin into {@code ServerLevel} construction or
     * {@code MinecraftServer#loadLevel()}.</p>
     *
     * <p>TODO[PAPER]: At runtime, {@code level} is a {@code ServerLevel}
     * instance. The event bus will track it for region ownership validation.</p>
     *
     * @param level the loaded world/level (Object placeholder for ServerLevel)
     */
    @SuppressWarnings("unused")
    public void onWorldLoad(Object level) {
        if (this.eventBus == null) return;
        if (level == null) return;

        this.eventBus.registerWorld(level);

        LOGGER.info("World loaded — region-aware event bus tracking active: "
                + level.getClass().getName() + "@" + System.identityHashCode(level));

        // TODO[PAPER]: At runtime, extract dimension key from ServerLevel:
        //   ServerLevel serverLevel = (ServerLevel) level;
        //   ResourceKey<Level> dimKey = serverLevel.dimension();
        //   LOGGER.info("  Dimension: " + dimKey.location());
    }

    /**
     * Stores the MinecraftServer reference.
     *
     * <p>TODO[MC]: At runtime, {@code server} should be cast to
     * {@code MinecraftServer} and cached for later use.</p>
     *
     * @param server the MinecraftServer instance
     */
    @SuppressWarnings("unused")
    public void setServer(Object server) {
        this.server = server;
        LOGGER.info("MinecraftServer reference set: "
                + (server != null ? server.getClass().getName() : "null"));

        // TODO[NEOFORGE]: Wire the NeoForge EventBus delegate once available:
        //   if (server != null) {
        //       Object neoForgeBus = NeoForge.EVENT_BUS;
        //       this.eventBus.setDelegateBus(neoForgeBus);
        //   }
    }

    // =========================================================================
    // Region Validation
    // =========================================================================

    /**
     * Asserts that the caller is on the region thread that owns the given level.
     *
     * <p>If the assertion fails and the event bus is in STRICT mode, an
     * {@link IllegalStateException} is thrown.</p>
     *
     * <p>TODO[PAPER]: At runtime, cast {@code level} to {@code ServerLevel}
     * and use {@code RegionizedWorldThread} for actual thread ownership checks.</p>
     *
     * @param level the world/level (Object placeholder for ServerLevel)
     * @throws IllegalStateException if in STRICT mode and on the wrong thread
     */
    public static void assertRegionThread(Object level) {
        if (instance == null || instance.eventBus == null) return;
        if (level == null) return;

        boolean valid = instance.eventBus.validateRegionAccess(level);
        if (!valid && instance.eventBus.getValidationMode()
                == RegionAwareEventBus.ValidationMode.STRICT) {
            throw new IllegalStateException(
                    "Region thread assertion failed: thread '"
                            + Thread.currentThread().getName()
                            + "' is not the region thread for level "
                            + level);
        }
    }

    /**
     * Checks whether the caller is on the region thread that owns the given
     * chunk coordinates in the given level.
     *
     * <p>TODO[PAPER]: At runtime, restore the real
     * {@code RegionizedWorldThread.isOwnedBy(level, chunkX, chunkZ)}
     * check. The thread-name heuristic used here is a compile-time fallback.</p>
     *
     * @param level  the world/level (Object placeholder for ServerLevel)
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return true if the current thread appears to own the region for this chunk
     */
    public static boolean isCorrectRegionThread(Object level, int chunkX, int chunkZ) {
        if (instance == null || instance.server == null) return true;
        if (instance.eventBus == null) return true;

        // Delegate to the event bus's off-region detector
        // TODO[PAPER]: At runtime, the OffRegionDetector will be replaced
        // by a Paper-aware implementation via OffRegionDetector.setInstance()
        return RegionAwareEventBus.OffRegionDetector.getInstance()
                .detect(level, chunkX, chunkZ);
    }

    // =========================================================================
    // Crash Handling
    // =========================================================================

    /**
     * Handles a fatal server crash by producing an enriched crash report.
     *
     * <p>The report includes:</p>
     * <ul>
     *   <li>Region context (owning region thread, affected chunks)</li>
     *   <li>Mod stack (frames from non-vanilla, non-Eturlia packages)</li>
     *   <li>Full exception stack trace</li>
     *   <li>System context (JVM version, memory, OS, etc.)</li>
     * </ul>
     *
     * @param cause the fatal exception
     */
    private void handleFatalCrash(Throwable cause) {
        Thread crashThread = Thread.currentThread();
        RegionContextCrashReport report = RegionContextCrashReport.enrich(crashThread, cause);

        System.err.println("==== Eturlia Fatal Crash Report ====");
        System.err.println(report.toHumanReadable());
        System.err.println();
        System.err.println("==== Structured JSON ====");
        System.err.println(report.toJsonString());

        // Persist to file
        writeCrashReportToFile(report);

        // Also log the full stack trace for log aggregators
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        cause.printStackTrace(pw);
        LOGGER.severe(sw.toString());
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Returns diagnostic information about the server state.
     *
     * <p>Useful for health-check endpoints, console commands, and debugging.</p>
     *
     * @return a map of diagnostic key-value pairs
     */
    public Map<String, Object> getServerInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("eturliaVersion", ETURLIA_VERSION);
        info.put("mcVersion", mcVersion);
        info.put("neoForgeVersion", neoForgeVersion);
        info.put("fmlBootstrapped", fmlBootstrapped);
        info.put("fmlAvailable", fmlAvailable);
        info.put("serverInitialized", server != null);

        if (eventBus != null) {
            info.put("eventBusValidationMode", eventBus.getValidationMode().name());
            info.put("trackedWorldCount", eventBus.getTrackedWorlds().size());
            info.put("totalEventsDispatched", eventBus.getTotalEventsDispatched());
            info.put("totalViolations", eventBus.getTotalViolations());
        } else {
            info.put("trackedWorldCount", 0);
        }

        info.put("modCount", modCount.get());

        if (server != null) {
            // TODO[MC]: At runtime, extract real server metrics:
            //   MinecraftServer srv = (MinecraftServer) server;
            //   info.put("tickCount", srv.getTickCount());
            //   info.put("motd", srv.getMotd());
            //   info.put("playerCount", srv.getPlayerCount());
            info.put("serverClass", server.getClass().getName());
        }

        Runtime rt = Runtime.getRuntime();
        info.put("jvmVersion", System.getProperty("java.version", "unknown"));
        info.put("osName", System.getProperty("os.name", "unknown"));
        info.put("availableProcessors", rt.availableProcessors());
        info.put("maxMemoryMB", rt.maxMemory() / (1024 * 1024));
        info.put("totalMemoryMB", rt.totalMemory() / (1024 * 1024));
        info.put("freeMemoryMB", rt.freeMemory() / (1024 * 1024));

        return info;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the MinecraftServer instance.
     *
     * @return the server (Object placeholder for MinecraftServer), or null
     */
    public Object getServer() {
        return this.server;
    }

    /**
     * Returns the region-aware event bus.
     *
     * @return the event bus, or null if not yet installed
     */
    public RegionAwareEventBus getEventBus() {
        return this.eventBus;
    }

    /**
     * Returns whether FML was successfully bootstrapped.
     *
     * @return true if FML bootstrap completed without error
     */
    public boolean isFmlBootstrapped() {
        return this.fmlBootstrapped;
    }

    /**
     * Returns whether FML was found on the classpath.
     *
     * @return true if FMLLoader was loadable
     */
    public boolean isFmlAvailable() {
        return this.fmlAvailable;
    }

    /**
     * Returns the number of mods reported by FML.
     *
     * @return the mod count, or 0 if FML is not available
     */
    public int getModCount() {
        return this.modCount.get();
    }

    /**
     * Returns the MC version parsed from launch arguments.
     *
     * @return the MC version string
     */
    public String getMcVersion() {
        return this.mcVersion;
    }

    /**
     * Returns the NeoForge version parsed from launch arguments.
     *
     * @return the NeoForge version string
     */
    public String getNeoForgeVersion() {
        return this.neoForgeVersion;
    }

    // =========================================================================
    // Internal — Version Resolution
    // =========================================================================

    /**
     * Resolves the Eturlia version from the build properties or system property.
     *
     * <p>Looks in order:</p>
     * <ol>
     *   <li>System property {@code eturlia.version}</li>
     *   <li>Resource {@code /eturlia/version.properties} — key {@code eturlia.version}</li>
     *   <li>Fallback: {@code "0.0.0-unknown"}</li>
     * </ol>
     *
     * @return the resolved version string
     */
    private static String resolveEturliaVersion() {
        // 1. System property
        String fromProp = System.getProperty("eturlia.version");
        if (fromProp != null && !fromProp.isEmpty()) {
            return fromProp;
        }

        // 2. Properties file in resources
        try {
            Properties props = new Properties();
            props.load(EturliaServer.class.getResourceAsStream("/eturlia/version.properties"));
            String fromFile = props.getProperty("eturlia.version");
            if (fromFile != null && !fromFile.isEmpty()) {
                return fromFile;
            }
        } catch (Exception e) {
            // Properties file not found — use fallback
        }

        // 3. Fallback
        return "0.0.0-unknown";
    }
}
