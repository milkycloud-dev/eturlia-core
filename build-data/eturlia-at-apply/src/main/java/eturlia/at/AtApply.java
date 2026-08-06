package eturlia.at;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.neoforged.accesstransformer.api.AccessTransformerEngine;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Applies NeoForge access transformers to a Folia mojang-mapped server jar so
 * NeoForge runtime classes can access widened Minecraft members without ModLauncher.
 *
 * Usage: AtApply &lt;input.jar&gt; &lt;accesstransformer.cfg&gt; [&lt;extra.cfg&gt; ...] &lt;output.jar&gt;
 */
public final class AtApply {
    private AtApply() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: AtApply <input.jar> <at.cfg> [more.at.cfg ...] <output.jar>");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[args.length - 1]);
        AccessTransformerEngine engine = AccessTransformerEngine.newEngine();
        for (int i = 1; i < args.length - 1; i++) {
            Path at = Path.of(args[i]);
            System.out.println("Loading AT: " + at);
            engine.loadATFromPath(at);
        }

        Files.createDirectories(output.getParent());
        int transformed = 0;
        try (JarFile jar = new JarFile(input.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(output))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream in = jar.getInputStream(entry)) {
                    data = readAll(in);
                }
                String name = entry.getName();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    String internal = name.substring(0, name.length() - 6);
                    Type type = Type.getObjectType(internal);
                    ClassReader reader = new ClassReader(data);
                    ClassNode node = new ClassNode();
                    reader.accept(node, 0);
                    if (engine.transform(node, type)) {
                        ClassWriter writer = new ClassWriter(reader, 0);
                        node.accept(writer);
                        data = writer.toByteArray();
                        transformed++;
                    }
                }
                JarEntry outEntry = new JarEntry(name);
                outEntry.setTime(0);
                jos.putNextEntry(outEntry);
                jos.write(data);
                jos.closeEntry();
            }
        }
        System.out.println("Wrote " + output + " (transformed " + transformed + " classes)");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }
}
