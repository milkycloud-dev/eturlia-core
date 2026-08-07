package eturlia.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Outer wrapper: unpack nested libraries, then boot NeoForge via
 * {@code BootstrapLauncher} + ModLauncher ({@code --launchTarget eturliaserver}).
 */
public final class Main {
    private static final String INDEX_RESOURCE = "/META-INF/eturlia-libraries.index";
    private static final String LIBRARY_RESOURCE_PREFIX = "/META-INF/eturlia-libraries/";

    private static final List<String> MODULE_PATH_NAMES = List.of(
            "bootstraplauncher-2.0.2.jar",
            "securejarhandler-3.0.8.jar",
            "asm-9.7.1.jar",
            "asm-tree-9.7.1.jar",
            "asm-commons-9.7.1.jar",
            "asm-util-9.7.1.jar",
            "asm-analysis-9.7.1.jar",
            "JarJarFileSystems-0.4.1.jar"
    );

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (!hasAgreedToEula(Path.of("eula.txt"))) {
            return;
        }

        Path outputDir = Path.of("eturlia-libraries").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        List<Entry> entries = readEntries();
        List<Path> allLibs = new ArrayList<>(entries.size());

        System.out.println("Checking embedded Eturlia libraries in " + outputDir);
        for (Entry entry : entries) {
            Path outputFile = outputDir.resolve(entry.name()).normalize();
            if (!outputFile.startsWith(outputDir)) {
                throw new IllegalStateException("Invalid embedded library path: " + entry.name());
            }
            if (needsExtraction(entry, outputFile)) {
                extract(entry, outputFile);
                Files.writeString(stateFile(outputFile), entry.sha256() + "\n", StandardCharsets.UTF_8);
            }
            allLibs.add(outputFile);
        }

        Path serverJarEarly = findLib(allLibs, "folia-server-neoforge-at.jar");
        Path apiJarEarly = findLib(allLibs, "folia-api-");
        if (serverJarEarly != null) {
            // Paper adds LogUtils.getClassLogger(); stock Mojang logging jars lack it.
            for (Path p : allLibs) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.startsWith("logging-") && n.endsWith(".jar")) {
                    injectFoliaLogUtils(serverJarEarly, p);
                }
            }
            // PaperBrigadier from the API jar is injected into the AT jar to avoid a split
            // package in the minecraft union (AT owns io.papermc.paper.brigadier).
            if (apiJarEarly != null) {
                injectClassesFromJar(apiJarEarly, serverJarEarly, List.of(
                        "io/papermc/paper/brigadier/PaperBrigadier.class"
                ), "paper-brigadier");
            }
            // brigadier is unioned into the minecraft module (see EturliaGameLocator); Folia AT
            // overlays patched ArgumentBuilder/CommandNode. No separate module injection.
        }

        Path bootstrapDir = Path.of("eturlia-bootstrap").toAbsolutePath().normalize();
        Files.createDirectories(bootstrapDir);
        List<Path> modulePath = materializeBootstrapModules(bootstrapDir);

        Path serverJar = findLib(allLibs, "folia-server-neoforge-at.jar");
        Path neoForgeJar = null;
        for (Path p : allLibs) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.contains("neoforge") && n.contains("universal") && n.endsWith(".jar")) {
                neoForgeJar = p;
                break;
            }
        }
        if (serverJar == null) {
            throw new IllegalStateException("Missing embedded folia-server-neoforge-at.jar");
        }
        if (neoForgeJar == null) {
            throw new IllegalStateException("Missing embedded NeoForge universal jar");
        }

        // NeoForgeDevProvider looks for a legacyClassPath entry whose *filename* contains
        // "client-extra" (userdev client-extra jar). Our Folia AT jar already embeds
        // assets/.mcassetsroot — expose it under that name without duplicating bytes.
        Path clientExtra = outputDir.resolve("client-extra-eturlia.jar");
        ensureClientExtraLink(clientExtra, serverJar);
        if (allLibs.stream().noneMatch(p -> p.getFileName().toString().contains("client-extra"))) {
            allLibs.add(clientExtra);
        }

        // ignoreList entries are matched with String.startsWith against the legacy jar *filename*
        List<String> ignore = new ArrayList<>(MODULE_PATH_NAMES);
        for (Path p : allLibs) {
            String name = p.getFileName().toString();
            if (isBootstrapModuleJar(name) || isJpmsIncompatibleJar(name) || isGameLayerOwnedJar(name)) {
                ignore.add(name);
            }
        }
        String ignoreList = String.join(",", ignore);

        // Keep game jars on legacyClassPath for NeoForgeDevProvider's client-extra lookup;
        // ignoreList prevents them from becoming duplicate JPMS modules.
        String legacyClassPathWithExtra = allLibs.stream()
                .filter(p -> !isBootstrapModuleJar(p.getFileName().toString()))
                .filter(p -> !isJpmsIncompatibleJar(p.getFileName().toString()))
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));

        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        // Forward the operator's JVM options to the child JVM — the child is the actual
        // server, so without this `java -Xmx16G -jar eturlia.jar` silently ran the server
        // on the default heap and every -Deturlia.* flag from the docs was dropped.
        command.addAll(inheritedJvmArgs());
        // Then the jvm: section of config/eturlia.yml, so it wins over the inherited flags.
        command.addAll(configuredJvmArgs());
        command.add("-p");
        command.add(modulePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        command.add("--add-modules");
        command.add("ALL-MODULE-PATH");
        command.add("--add-opens");
        command.add("java.base/java.util.jar=cpw.mods.securejarhandler");
        command.add("--add-opens");
        command.add("java.base/java.lang.invoke=cpw.mods.securejarhandler");
        command.add("--add-exports");
        command.add("java.base/sun.security.util=cpw.mods.securejarhandler");
        command.add("--add-exports");
        command.add("jdk.naming.dns/com.sun.jndi.dns=java.naming");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Djava.net.preferIPv6Addresses=system");
        command.add("-DignoreList=" + ignoreList);
        command.add("-Dfml.pluginLayerLibraries=");
        command.add("-Dfml.gameLayerLibraries=");
        command.add("-DlibraryDirectory=" + outputDir);
        command.add("-DlegacyClassPath=" + legacyClassPathWithExtra);
        command.add("-Deturlia.serverJar=" + serverJar.toAbsolutePath().normalize());
        command.add("-Deturlia.neoforgeJar=" + neoForgeJar.toAbsolutePath().normalize());
        Path apiJar = findLib(allLibs, "folia-api-");
        if (apiJar != null) {
            command.add("-Deturlia.apiJar=" + apiJar.toAbsolutePath().normalize());
        }
        Path commonsLang2 = preferNewestLib(allLibs, "commons-lang-2.");
        if (commonsLang2 != null) {
            command.add("-Deturlia.commonsLang2Jar=" + commonsLang2.toAbsolutePath().normalize());
        }
        Path brigadier = preferNewestLib(allLibs, "brigadier-");
        if (brigadier != null) {
            command.add("-Deturlia.brigadierJar=" + brigadier.toAbsolutePath().normalize());
        }
        List<Path> sparkJars = new ArrayList<>();
        for (Path p : allLibs) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.contains("spark-") && n.endsWith(".jar")) {
                sparkJars.add(p);
            }
        }
        if (!sparkJars.isEmpty()) {
            command.add("-Deturlia.sparkJars=" + sparkJars.stream()
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        command.add("-Dnet.kyori.adventure.text.warnWhenLegacyFormattingDetected=true");
        command.add("-Dio.papermc.paper.suppress.sout.nags=true");
        command.add("-Dpaper.maxChatCommandInputSize=32767");
        // Terralith sets JAVA_17 first; force JAVA_21 so Lithostitched/NeoForge classfile 65 mixins apply cleanly.
        command.add("-Dmixin.env.compatLevel=JAVA_21");
        // NeoForge %highlightForge paints INFO green by default — swap in Eturlia's calm palette
        // unless the operator opts into full/off via -Deturlia.console.color=
        Path calmLog4j = installEturliaLog4jConfig(outputDir);
        if (calmLog4j != null) {
            String uri = calmLog4j.toAbsolutePath().normalize().toUri().toString();
            command.add("-Dlog4j2.configurationFile=" + uri);
            command.add("-Dlog4j.configurationFile=" + uri);
        }
        command.add("cpw.mods.bootstraplauncher.BootstrapLauncher");
        command.add("--launchTarget");
        command.add("eturliaserver");
        command.add("--gameDir");
        command.add(".");
        command.add("--fml.neoForgeVersion");
        command.add(prop("neoforgeVersion", "21.1.248"));
        command.add("--fml.fmlVersion");
        command.add(prop("fmlLoaderVersion", "4.0.43"));
        command.add("--fml.mcVersion");
        command.add(prop("mcVersion", "1.21.1"));
        // Stock NeoForge uses bare neoForm timestamp; FML concatenates mcVersion-neoFormVersion
        command.add("--fml.neoFormVersion");
        command.add(prop("neoFormVersionBare", "20240808.144430"));
        command.add("--nogui");
        command.addAll(Arrays.asList(args));

        System.out.println("Starting Eturlia via ModLauncher (launchTarget=eturliaserver)");
        Process child = new ProcessBuilder(command)
                .inheritIO()
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .start();

        // Without this the server keeps running after the wrapper is killed
        // (systemd stop, CI `timeout`, Ctrl+C on Windows): orphaned JVM, held world
        // lock and a port that never frees up.
        Thread shutdown = new Thread(() -> terminateChild(child), "Eturlia-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        int exitCode = child.waitFor();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException ignored) {
            // Already shutting down.
        }
        System.exit(exitCode);
    }

    /** Asks the server JVM to stop, then forces it if it does not exit in time. */
    private static void terminateChild(Process child) {
        if (!child.isAlive()) {
            return;
        }
        System.out.println("Eturlia launcher shutting down — stopping server process "
                + child.pid());
        child.descendants().forEach(ProcessHandle::destroy);
        child.destroy();
        try {
            if (!child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
        }
    }

    /**
     * JVM options taken from the {@code jvm:} section of {@code config/eturlia.yml}.
     *
     * <p>Thread counts for the Moonrise chunk system are read once, very early in the server
     * JVM's life, so they cannot be set from inside the server after the config is parsed —
     * they have to be on the child's command line. Heap size has the same problem. The
     * launcher therefore reads those few keys itself with a minimal scanner rather than
     * pulling the whole config parser (and its module) onto the launcher's classpath.</p>
     */
    static List<String> configuredJvmArgs() {
        Path config = Path.of("config", "eturlia.yml");
        if (!Files.isRegularFile(config)) {
            return List.of();
        }
        Map<String, String> jvm;
        try {
            jvm = readYamlSection(config, "jvm");
        } catch (IOException e) {
            System.out.println("Could not read " + config + " (" + e + ") — using JVM defaults");
            return List.of();
        }

        List<String> args = new ArrayList<>();
        String heapMax = jvm.getOrDefault("heap-max", "");
        if (!heapMax.isBlank()) {
            args.add("-Xmx" + heapMax.trim());
        }
        String heapMin = jvm.getOrDefault("heap-min", "");
        if (!heapMin.isBlank()) {
            args.add("-Xms" + heapMin.trim());
        }
        int workers = parsePositiveInt(jvm.get("worker-threads"));
        if (workers > 0) {
            args.add("-DPaper.WorkerThreadCount=" + workers);
        }
        int io = parsePositiveInt(jvm.get("io-threads"));
        if (io > 0) {
            args.add("-DPaper.IOThreadCount=" + io);
        }
        String extra = jvm.getOrDefault("extra-args", "");
        if (!extra.isBlank()) {
            for (String token : extra.trim().split("\\s+")) {
                if (!token.isBlank()) {
                    args.add(token);
                }
            }
        }
        if (!args.isEmpty()) {
            System.out.println("Applying JVM options from " + config + ": " + args);
        }
        return args;
    }

    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Reads {@code key: value} pairs nested one level under {@code section} from a YAML file.
     *
     * <p>Deliberately tiny: it understands the shape this config uses (two-space indentation,
     * scalar values, {@code #} comments, optionally quoted strings) and nothing else. Anything
     * more complex belongs to the real parser inside the server.</p>
     */
    static Map<String, String> readYamlSection(Path file, String section) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        boolean inSection = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            boolean topLevel = !Character.isWhitespace(line.charAt(0));
            String trimmed = line.trim();
            if (topLevel) {
                inSection = trimmed.equals(section + ":");
                continue;
            }
            if (!inSection) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    /** Strips a trailing {@code #} comment that is not inside quotes. */
    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /**
     * JVM options of this (wrapper) process that should be re-applied to the server JVM.
     *
     * <p>Options the launcher sets itself are filtered out so they are not specified twice.</p>
     */
    private static List<String> inheritedJvmArgs() {
        List<String> inherited = new ArrayList<>();
        List<String> managed = List.of(
                "-DignoreList=", "-DlegacyClassPath=", "-DlibraryDirectory=",
                "-Dfml.pluginLayerLibraries=", "-Dfml.gameLayerLibraries=",
                "-Deturlia.serverJar=", "-Deturlia.neoforgeJar=", "-Deturlia.apiJar=",
                "-Deturlia.commonsLang2Jar=", "-Deturlia.brigadierJar=", "-Deturlia.sparkJars=",
                "-Dlog4j2.configurationFile=", "-Dlog4j.configurationFile=",
                "-Dmixin.env.compatLevel=");
        try {
            for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getInputArguments()) {
                if (arg.startsWith("-agentlib:jdwp") || arg.startsWith("-javaagent:")) {
                    continue; // debugger/agent attached to the wrapper, not the server
                }
                if (managed.stream().anyMatch(arg::startsWith)) {
                    continue;
                }
                inherited.add(arg);
            }
        } catch (RuntimeException | LinkageError e) {
            System.out.println("Could not read wrapper JVM arguments (" + e
                    + ") — starting the server with default JVM options");
        }
        if (!inherited.isEmpty()) {
            System.out.println("Forwarding JVM options to the server process: " + inherited);
        }
        return inherited;
    }

    /**
     * Sidecar file recording which embedded library revision an extracted jar came from.
     *
     * <p>Some jars are patched in place after extraction (LogUtils / PaperBrigadier overlay),
     * which permanently changes their SHA-256. Comparing the file hash against the index
     * therefore re-extracted — and re-patched — every single library on every boot. The
     * sidecar records the <em>source</em> hash instead, so the work happens exactly once.</p>
     */
    private static Path stateFile(Path lib) {
        return lib.resolveSibling(lib.getFileName() + ".eturlia-state");
    }

    private static boolean needsExtraction(Entry entry, Path outputFile) throws Exception {
        if (!Files.isRegularFile(outputFile)) {
            return true;
        }
        Path state = stateFile(outputFile);
        if (Files.isRegularFile(state)) {
            String recorded = Files.readString(state, StandardCharsets.UTF_8).trim();
            if (entry.sha256().equalsIgnoreCase(recorded)) {
                return false;
            }
        }
        return !entry.sha256().equalsIgnoreCase(sha256(outputFile));
    }

    private static String prop(String key, String fallback) {
        String fromSys = System.getProperty("eturlia." + key);
        if (fromSys != null && !fromSys.isEmpty()) {
            return fromSys;
        }
        try (InputStream in = Main.class.getResourceAsStream("/eturlia/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty(key);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }

    private static boolean isBootstrapModuleJar(String fileName) {
        // Nested libs are renamed to NNN-<original>; match on original artifact name.
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.contains("bootstraplauncher-")
                || n.contains("securejarhandler-")
                || n.contains("jarjarfilesystems-")
                || n.matches(".*(?:^|[-.])asm(-tree|-commons|-util|-analysis)?-[0-9][^/]*\\.jar");
    }

    /**
     * Jars that must appear on {@code legacyClassPath} for FML path discovery (or packaging)
     * but must not become JPMS modules — content is already in the {@code minecraft}/{@code neoforge}
     * game modules (or nested via NeoForge jar-in-jar).
     */
    private static boolean isGameLayerOwnedJar(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.contains("folia-server-neoforge-at")
                || n.contains("folia-api-")
                || n.contains("client-extra")
                || (n.contains("neoforge") && n.contains("universal"))
                || n.contains("eturlia-neoforge-coremods")
                || n.contains("eturlia-neoforge-extras")
                || n.contains("eturlia-neoforge-resources")
                || n.startsWith("filteredminecraft")
                || n.startsWith("log4jplugins")
                || n.startsWith("autorenamingtool-")
                // Unioned into the minecraft module (Folia overlays patched classes)
                || n.contains("brigadier-")
                // Paper Spark must see Bukkit API in the minecraft module
                || n.contains("spark-");
    }

    /**
     * Jars that cannot be turned into JPMS modules (reserved package segments like {@code enum}).
     * commons-lang 2.x ships {@code org.apache.commons.lang.enum} which breaks module descriptors.
     * Folia still has commons-lang3 on the fat classpath for modern call sites.
     */
    private static boolean isJpmsIncompatibleJar(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.contains("commons-lang-2.");
    }

    private static Path findLib(List<Path> libs, String contains) {
        for (Path p : libs) {
            if (p.getFileName().toString().contains(contains)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Prefer the highest version among jars whose filename contains {@code needle}
     * (embedded libs may be renamed {@code NNN-artifact-ver.jar}).
     */
    private static Path preferNewestLib(List<Path> libs, String needle) {
        Path best = null;
        String bestName = null;
        String needleLower = needle.toLowerCase(Locale.ROOT);
        for (Path p : libs) {
            String name = p.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.contains(needleLower) || !lower.endsWith(".jar")) {
                continue;
            }
            if (bestName == null || compareVersions(name, bestName) > 0) {
                best = p;
                bestName = name;
            }
        }
        return best;
    }

    /**
     * Compares two jar file names by their numeric version segments.
     *
     * <p>A plain {@code String.compareTo} ranked {@code commons-lang-2.6} above
     * {@code commons-lang-2.10} and was also skewed by the {@code NNN-} prefix that
     * embedded libraries used to carry.</p>
     */
    static int compareVersions(String leftName, String rightName) {
        int[] left = versionNumbers(leftName);
        int[] right = versionNumbers(rightName);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return leftName.compareTo(rightName);
    }

    private static int[] versionNumbers(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        // First digit group that follows a '-' starts the version (skips the artifact name
        // and any NNN- ordering prefix used by older builds).
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-(\\d+(?:\\.\\d+)*)")
                .matcher(name);
        String version = null;
        while (m.find()) {
            version = m.group(1); // last match wins: artifact-1.2 vs 072-artifact-1.2
        }
        if (version == null) {
            return new int[0];
        }
        String[] parts = version.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    /**
     * Point {@code client-extra-eturlia.jar} at the Folia AT jar so NeoForgeDevProvider
     * finds its assets root. Prefer a symlink; fall back to a hard copy.
     */
    private static void ensureClientExtraLink(Path clientExtra, Path serverJar) throws IOException {
        Path target = serverJar.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(clientExtra)) {
            try {
                if (Files.readSymbolicLink(clientExtra).toAbsolutePath().normalize().equals(target)) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Files.deleteIfExists(clientExtra);
        } else if (Files.isRegularFile(clientExtra)) {
            // Windows without developer mode cannot create symlinks, so this is a full copy
            // of a multi-hundred-megabyte jar. Keep an up-to-date copy instead of deleting
            // and re-copying it on every single boot.
            if (Files.size(clientExtra) == Files.size(target)
                    && !Files.getLastModifiedTime(clientExtra).toInstant()
                            .isBefore(Files.getLastModifiedTime(target).toInstant())) {
                return;
            }
            Files.deleteIfExists(clientExtra);
        }
        try {
            Files.createSymbolicLink(clientExtra, target);
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(target, clientExtra, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Install Eturlia's Log4j2 config so the console is not flooded with green INFO lines.
     *
     * <p>NeoForge FML's {@code %highlightForge} uses Log4j's default palette ({@code INFO=green}).
     * Default mode {@code calm} keeps WARN/ERROR colored and leaves INFO plain. Modes:</p>
     * <ul>
     *   <li>{@code calm} (default) — WARN yellow, ERROR red, INFO uncolored</li>
     *   <li>{@code off}/{@code plain}/{@code none} — no level ANSI colors</li>
     *   <li>{@code full} — stock NeoForge green INFO (no override)</li>
     * </ul>
     *
     * @return config path to pass as {@code log4j2.configurationFile}, or {@code null} for stock FML
     */
    private static Path installEturliaLog4jConfig(Path outputDir) throws IOException {
        String mode = System.getProperty("eturlia.console.color", "calm").trim().toLowerCase(Locale.ROOT);
        if ("full".equals(mode) || "neoforge".equals(mode) || "default".equals(mode)) {
            System.out.println("Eturlia console colors: full (stock NeoForge)");
            return null;
        }

        String resource;
        String destName;
        if ("off".equals(mode) || "none".equals(mode) || "plain".equals(mode)) {
            resource = "/eturlia/log4j2-eturlia-plain.xml";
            destName = "log4j2-eturlia-plain.xml";
            System.out.println("Eturlia console colors: plain (no level ANSI)");
        } else {
            resource = "/eturlia/log4j2-eturlia.xml";
            destName = "log4j2-eturlia.xml";
            System.out.println("Eturlia console colors: calm (INFO plain, WARN/ERROR colored)");
        }

        Path dest = outputDir.resolve(destName);
        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("Missing " + resource + " — keeping stock FML console colors");
                return null;
            }
            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    /**
     * Overlay Paper's {@code LogUtils.getClassLogger()} into the Mojang logging jar used
     * as a JPMS module (Folia AT also embeds LogUtils, but that copy is filtered out of the
     * minecraft module to avoid split packages with this jar).
     */
    private static void injectFoliaLogUtils(Path foliaAtJar, Path loggingJar) throws IOException {
        injectClassesFromJar(foliaAtJar, loggingJar, List.of(
                "com/mojang/logging/LogUtils.class",
                "com/mojang/logging/LogUtils$1.class",
                "com/mojang/logging/LogUtils$1ToString.class"
        ), "LogUtils");
    }

    /**
     * Copies {@code entries} from {@code sourceJar} into {@code targetJar}, rewriting the
     * target archive in place.
     *
     * <p>Implemented with {@code java.util.zip} rather than by shelling out to the {@code jar}
     * tool: {@code java.home/bin/jar} does not exist on a JRE, which made this step fail on
     * any non-JDK runtime. A marker file records the work so it is not redone on every boot.</p>
     */
    private static void injectClassesFromJar(
            Path sourceJar, Path targetJar, List<String> entries, String label
    ) throws IOException {
        Path marker = targetJar.resolveSibling(targetJar.getFileName() + ".eturlia-" + label);
        String fingerprint;
        try {
            fingerprint = sha256(sourceJar) + ":" + String.join(",", entries);
        } catch (Exception e) {
            throw new IOException("Failed to fingerprint " + sourceJar, e);
        }
        if (Files.isRegularFile(marker)
                && fingerprint.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
            return; // already injected from this exact source
        }

        Map<String, byte[]> payload = new LinkedHashMap<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(sourceJar.toFile())) {
            for (String entry : entries) {
                java.util.jar.JarEntry je = jf.getJarEntry(entry);
                if (je == null) {
                    System.out.println("Missing " + entry + " in " + sourceJar.getFileName());
                    continue;
                }
                try (InputStream in = jf.getInputStream(je)) {
                    payload.put(entry, in.readAllBytes());
                }
            }
        }
        if (payload.isEmpty()) {
            throw new IOException("No entries extracted for " + label + " from " + sourceJar);
        }

        Path tmp = targetJar.resolveSibling(targetJar.getFileName() + ".inject.tmp");
        try (java.util.zip.ZipInputStream in =
                     new java.util.zip.ZipInputStream(Files.newInputStream(targetJar));
             java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (payload.containsKey(entry.getName())) {
                    continue; // replaced below
                }
                java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                in.transferTo(out);
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> injected : payload.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(injected.getKey()));
                out.write(injected.getValue());
                out.closeEntry();
            }
        }
        Files.move(tmp, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(marker, fingerprint + "\n", StandardCharsets.UTF_8);
        System.out.println("Injected " + label + " into " + targetJar.getFileName());
    }

    private static List<Path> materializeBootstrapModules(Path bootstrapDir) throws IOException {
        List<Path> modulePath = new ArrayList<>();
        for (String name : MODULE_PATH_NAMES) {
            Path dest = bootstrapDir.resolve(name);
            String resource = "/META-INF/eturlia-bootstrap/" + name;
            try (InputStream in = Main.class.getResourceAsStream(resource)) {
                if (in == null) {
                    // Fall back to a copy already extracted under eturlia-libraries
                    Path fromLibs = Path.of("eturlia-libraries").resolve(name);
                    if (Files.isRegularFile(fromLibs)) {
                        Files.copy(fromLibs, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        throw new IllegalStateException("Missing bootstrap module jar: " + name
                                + " (embed under META-INF/eturlia-bootstrap/)");
                    }
                } else {
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            modulePath.add(dest);
        }
        return modulePath;
    }

    private static boolean hasAgreedToEula(Path eula) throws IOException {
        if (!Files.isRegularFile(eula)) {
            Files.writeString(eula, """
                    #By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).
                    #Eturlia is an unofficial Folia/NeoForge hybrid and is not affiliated with Mojang or Microsoft.
                    eula=false
                    """, StandardCharsets.UTF_8);
            System.err.println("You need to agree to the EULA in order to run the server. Go to eula.txt for more info.");
            return false;
        }
        for (String line : Files.readAllLines(eula, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("eula=true")) {
                return true;
            }
        }
        System.err.println("You need to agree to the EULA in order to run the server. Go to eula.txt for more info.");
        return false;
    }

    private static List<Entry> readEntries() throws IOException {
        try (InputStream input = Main.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + INDEX_RESOURCE);
            }
            List<Entry> entries = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        throw new IllegalStateException("Bad index line: " + line);
                    }
                    entries.add(new Entry(line.substring(0, tab), line.substring(tab + 1)));
                }
            }
            return entries;
        }
    }

    private static void extract(Entry entry, Path outputFile) throws Exception {
        System.out.println("Unpacking " + entry.name());
        Files.createDirectories(outputFile.getParent());
        try (InputStream input = Main.class.getResourceAsStream(LIBRARY_RESOURCE_PREFIX + entry.name())) {
            if (input == null) {
                throw new IllegalStateException("Missing embedded library: " + entry.name());
            }
            Path tmp = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestOutputStream dos = new DigestOutputStream(Files.newOutputStream(tmp), digest)) {
                input.transferTo(dos);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!entry.sha256().equalsIgnoreCase(actual)) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("SHA-256 mismatch for " + entry.name()
                        + " expected=" + entry.sha256() + " actual=" + actual);
            }
            Files.move(tmp, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file); OutputStream none = OutputStream.nullOutputStream()) {
            DigestOutputStream dos = new DigestOutputStream(none, digest);
            in.transferTo(dos);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java");
    }

    private record Entry(String sha256, String name) {
    }
}
