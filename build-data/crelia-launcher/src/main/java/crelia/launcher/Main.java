package crelia.launcher;

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
 * {@code BootstrapLauncher} + ModLauncher ({@code --launchTarget creliaserver}).
 */
public final class Main {
    private static final String INDEX_RESOURCE = "/META-INF/crelia-libraries.index";
    private static final String LIBRARY_RESOURCE_PREFIX = "/META-INF/crelia-libraries/";

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

        Path outputDir = Path.of("crelia-libraries").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        List<Entry> entries = readEntries();
        List<Path> allLibs = new ArrayList<>(entries.size());

        System.out.println("Checking embedded Crelia libraries in " + outputDir);
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

        Path bootstrapDir = Path.of("crelia-bootstrap").toAbsolutePath().normalize();
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
        Path clientExtra = outputDir.resolve("client-extra-crelia.jar");
        ensureClientExtraLink(clientExtra, serverJar);
        if (allLibs.stream().noneMatch(p -> p.getFileName().toString().contains("client-extra"))) {
            allLibs.add(clientExtra);
        }

        // ignoreList entries are matched with String.startsWith against the legacy jar *filename*
        List<String> ignore = new ArrayList<>(MODULE_PATH_NAMES);
        for (Path p : allLibs) {
            String name = p.getFileName().toString();
            if (isBootstrapModuleJar(name) || isJpmsIncompatibleJar(name)) {
                ignore.add(name);
            }
        }
        String ignoreList = String.join(",", ignore);

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
        command.add("-Dcrelia.serverJar=" + serverJar.toAbsolutePath().normalize());
        command.add("-Dcrelia.neoforgeJar=" + neoForgeJar.toAbsolutePath().normalize());
        command.add("-Dnet.kyori.adventure.text.warnWhenLegacyFormattingDetected=true");
        command.add("-Dio.papermc.paper.suppress.sout.nags=true");
        command.add("-Dpaper.maxChatCommandInputSize=32767");
        command.add("cpw.mods.bootstraplauncher.BootstrapLauncher");
        command.add("--launchTarget");
        command.add("creliaserver");
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

        System.out.println("Starting Crelia via ModLauncher (launchTarget=creliaserver)");
        int exitCode = new ProcessBuilder(command)
                .inheritIO()
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .start()
                .waitFor();
        System.exit(exitCode);
    }

    private static String prop(String key, String fallback) {
        String fromSys = System.getProperty("crelia." + key);
        if (fromSys != null && !fromSys.isEmpty()) {
            return fromSys;
        }
        try (InputStream in = Main.class.getResourceAsStream("/crelia/version.properties")) {
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
     * Point {@code client-extra-crelia.jar} at the Folia AT jar so NeoForgeDevProvider
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

    private static List<Path> materializeBootstrapModules(Path bootstrapDir) throws IOException {
        List<Path> modulePath = new ArrayList<>();
        for (String name : MODULE_PATH_NAMES) {
            Path dest = bootstrapDir.resolve(name);
            String resource = "/META-INF/crelia-bootstrap/" + name;
            try (InputStream in = Main.class.getResourceAsStream(resource)) {
                if (in == null) {
                    // Fall back to a copy already extracted under crelia-libraries
                    Path fromLibs = Path.of("crelia-libraries").resolve(name);
                    if (Files.isRegularFile(fromLibs)) {
                        Files.copy(fromLibs, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        throw new IllegalStateException("Missing bootstrap module jar: " + name
                                + " (embed under META-INF/crelia-bootstrap/)");
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
