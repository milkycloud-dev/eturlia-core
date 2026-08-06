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
import java.util.List;
import java.util.Locale;
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
            if (!Files.isRegularFile(outputFile) || !entry.sha256().equalsIgnoreCase(sha256(outputFile))) {
                extract(entry, outputFile);
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
        int exitCode = new ProcessBuilder(command)
                .inheritIO()
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .start()
                .waitFor();
        System.exit(exitCode);
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
        String bestName = "";
        String needleLower = needle.toLowerCase(Locale.ROOT);
        for (Path p : libs) {
            String name = p.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains(needleLower)
                    && lower.endsWith(".jar")
                    && name.compareTo(bestName) > 0) {
                best = p;
                bestName = name;
            }
        }
        return best;
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
            // Replace stale copies with a symlink when possible
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

    private static void injectClassesFromFolia(
            Path foliaAtJar, Path targetJar, List<String> entries, String label
    ) throws IOException {
        injectClassesFromJar(foliaAtJar, targetJar, entries, label);
    }

    private static void injectClassesFromJar(
            Path sourceJar, Path targetJar, List<String> entries, String label
    ) throws IOException {
        Path work = Files.createTempDirectory("eturlia-" + label);
        try {
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(sourceJar.toFile())) {
                for (String entry : entries) {
                    java.util.jar.JarEntry je = jf.getJarEntry(entry);
                    if (je == null) {
                        System.out.println("Missing " + entry + " in " + sourceJar.getFileName());
                        continue;
                    }
                    Path dest = work.resolve(entry);
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = jf.getInputStream(je)) {
                        Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            String jarBin = Path.of(System.getProperty("java.home"), "bin",
                    File.separatorChar == '\\' ? "jar.exe" : "jar").toString();
            // Detect top-level package dir to update
            String root;
            if (Files.isDirectory(work.resolve("com"))) {
                root = "com";
            } else if (Files.isDirectory(work.resolve("io"))) {
                root = "io";
            } else {
                throw new IOException("No class roots extracted for " + label);
            }
            ProcessBuilder pb = new ProcessBuilder(
                    jarBin, "uf", targetJar.toAbsolutePath().toString(),
                    "-C", work.toString(), root);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("jar uf " + label + " failed (" + code + "): " + out);
            }
            System.out.println("Injected " + label + " into " + targetJar.getFileName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted patching " + label + " jar", e);
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
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
            Files.writeString(eula, "eula=false\n", StandardCharsets.UTF_8);
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
