package eturlia.probe;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The instrument the console-only harness never had: it walks the *modded* half of the registries
 * and reports what the server actually produces for each entry - which Bukkit wrapper an entity
 * gets, and whether a menu can be turned into an InventoryView at all.
 *
 * Everything runs on the region thread that owns the position it touches, and everything writes a
 * machine-readable report, so a run can be judged without reading a log.
 */
public final class EturliaProbe extends JavaPlugin {

    private static final int TICKS_BEFORE_REMOVAL = 40;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "entities" -> this.entityMatrix(sender);
            case "menus" -> this.menuMatrix(sender);
            case "biomes" -> this.biomeCensus(sender);
            case "worldgen" -> this.worldgen(sender);
            case "presets" -> this.presets(sender);
            case "stems" -> this.stems(sender);
            default -> sender.sendMessage("eprobe entities | menus | biomes | worldgen");
        }
        return true;
    }

    // ------------------------------------------------------------------ entities

    /**
     * Spawn every modded entity type once, next to the world spawn, and record the class of the
     * Bukkit handle the core hands out. A wrapper that is not specific enough is what vanilla code
     * casts and fails on, and a failed cast in an entity tick stops a Folia server.
     */
    private void entityMatrix(CommandSender sender) {
        org.bukkit.World world = this.getServer().getWorlds().get(0);
        Location origin = world.getSpawnLocation().clone().add(0.0D, 2.0D, 0.0D);
        List<ResourceLocation> modded = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (!"minecraft".equals(id.getNamespace())) {
                modded.add(id);
            }
        }
        sender.sendMessage("eprobe: " + modded.size() + " modded entity types; spawning");

        Path report = this.getDataFolder().toPath().resolve("entities.tsv");
        this.getServer().getRegionScheduler().execute(this, origin, () -> {
            List<String> rows = new ArrayList<>();
            ServerLevel level = ((CraftWorld) world).getHandle();
            int ok = 0;
            int failed = 0;
            for (ResourceLocation id : modded) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
                String wrapper = "-";
                String outcome;
                try {
                    Entity entity = type.create(level);
                    if (entity == null) {
                        outcome = "no-instance";
                    } else {
                        entity.setPos(origin.getX(), origin.getY(), origin.getZ());
                        wrapper = entity.getBukkitEntity().getClass().getName();
                        outcome = level.addFreshEntity(entity) ? "spawned" : "refused";
                        entity.discard();
                    }
                } catch (Throwable thrown) {
                    outcome = describe(thrown);
                }
                if (outcome.startsWith("threw")) {
                    failed++;
                } else {
                    ok++;
                }
                rows.add(id + "\t" + shortName(wrapper) + "\t" + outcome);
            }
            write(report, rows);
            sender.sendMessage("eprobe entities: " + ok + " handled, " + failed + " threw -> " + report);
        });
    }

    // ------------------------------------------------------------------ menus

    /**
     * Build every modded menu against a real player's inventory and ask it for its Bukkit view.
     * Before the menu plane this threw AbstractMethodError for every mod menu in the pack, which is
     * why no backpack, altar or machine screen would open.
     */
    private void menuMatrix(CommandSender sender) {
        Player player = sender instanceof Player p ? p : firstOnline();
        if (player == null) {
            sender.sendMessage("eprobe menus: needs one player online (the harness client will do)");
            return;
        }
        Path report = this.getDataFolder().toPath().resolve("menus.tsv");
        this.getServer().getRegionScheduler().execute(this, player.getLocation(), () -> {
            List<String> rows = new ArrayList<>();
            net.minecraft.world.entity.player.Inventory inventory =
                    ((CraftPlayer) player).getHandle().getInventory();
            int viewed = 0;
            int broken = 0;
            for (ResourceLocation id : BuiltInRegistries.MENU.keySet()) {
                MenuType<?> type = BuiltInRegistries.MENU.get(id);
                String outcome;
                try {
                    AbstractContainerMenu menu = type.create(1, inventory);
                    Object view = menu == null ? null : menu.getBukkitView();
                    outcome = view == null ? "no-view" : "view " + shortName(view.getClass().getName());
                } catch (Throwable thrown) {
                    outcome = describe(thrown);
                }
                if (outcome.startsWith("threw")) {
                    broken++;
                } else {
                    viewed++;
                }
                rows.add(id + "\t" + outcome);
            }
            write(report, rows);
            sender.sendMessage("eprobe menus: " + viewed + " gave a view, " + broken + " threw -> " + report);
        });
    }

    // ------------------------------------------------------------------ biomes

    /** What each loaded world actually generates: the biome set a mod promised is either there or not. */
    private void biomeCensus(CommandSender sender) {
        Path report = this.getDataFolder().toPath().resolve("biomes.tsv");
        List<String> rows = new ArrayList<>();
        for (org.bukkit.World world : this.getServer().getWorlds()) {
            ServerLevel level = ((CraftWorld) world).getHandle();
            java.util.Set<String> namespaces = new java.util.TreeSet<>();
            int sampled = 0;
            for (int x = -512; x <= 512; x += 64) {
                for (int z = -512; z <= 512; z += 64) {
                    var biome = level.getBiome(new net.minecraft.core.BlockPos(x, 64, z));
                    var key = biome.unwrapKey().orElse(null);
                    if (key != null) {
                        namespaces.add(key.location().toString());
                        sampled++;
                    }
                }
            }
            rows.add(world.getName() + "\t" + sampled + "\t" + String.join(",", namespaces));
        }
        write(report, rows);
        sender.sendMessage("eprobe biomes: " + rows.size() + " worlds -> " + report);
    }

    // ------------------------------------------------------------------ worldgen

    /**
     * What each world is actually generating with: the generator, the biome source, and how many
     * biomes that source admits it can produce. A source that says one is the whole bug.
     */
    private void worldgen(CommandSender sender) {
        Path report = this.getDataFolder().toPath().resolve("worldgen.tsv");
        List<String> rows = new ArrayList<>();
        for (org.bukkit.World world : this.getServer().getWorlds()) {
            ServerLevel level = ((CraftWorld) world).getHandle();
            var generator = level.getChunkSource().getGenerator();
            var source = generator.getBiomeSource();
            java.util.Set<String> possible = new java.util.TreeSet<>();
            try {
                for (var holder : source.possibleBiomes()) {
                    holder.unwrapKey().ifPresent(key -> possible.add(key.location().toString()));
                }
            } catch (Throwable thrown) {
                possible.add("threw " + thrown.getClass().getSimpleName());
            }
            String sample = possible.stream().limit(6).reduce((a, b) -> a + "," + b).orElse("-");
            rows.add(world.getName()
                    + "\t" + generator.getClass().getName()
                    + "\t" + source.getClass().getName()
                    + "\t" + possible.size()
                    + "\t" + sample);
        }
        write(report, rows);
        for (String row : rows) {
            sender.sendMessage("eprobe worldgen: " + row.replace('\t', ' '));
        }
    }

    // ------------------------------------------------------------------ world presets

    /**
     * What the world_preset registry holds, and what `minecraft:normal` would actually build. A
     * server.properties that says level-type=minecraft:normal and a world that comes out flat means
     * the answer is here.
     */
    private void presets(CommandSender sender) {
        Path report = this.getDataFolder().toPath().resolve("presets.tsv");
        List<String> rows = new ArrayList<>();
        var access = ((org.bukkit.craftbukkit.CraftServer) this.getServer()).getServer().registryAccess();
        // Everything here goes through reflection on purpose. This jar passes through the core's
        // own plugin remapper, which rewrites references compiled against Mojang mappings into
        // methods that do not exist - Registry.keySet() came back as IdMap.keySet(). Reflection
        // binds by name at call time and is immune to that.
        Object registry;
        try {
            registry = call(access, "registryOrThrow",
                            new Class<?>[]{net.minecraft.resources.ResourceKey.class},
                            net.minecraft.core.registries.Registries.WORLD_PRESET);
        } catch (Throwable problem) {
            sender.sendMessage("eprobe presets: cannot reach the registry: " + problem);
            return;
        }
        java.util.Collection<?> keys;
        try {
            rows.add("entries\t" + call(registry, "size", new Class<?>[0]));
            keys = (java.util.Collection<?>) call(registry, "keySet", new Class<?>[0]);
        } catch (Throwable problem) {
            sender.sendMessage("eprobe presets: cannot list the registry: " + problem);
            return;
        }
        for (Object id : keys) {
            String overworld;
            try {
                Object preset = call(registry, "get", new Class<?>[]{ResourceLocation.class}, id);
                Object dimensions = call(call(preset, "createWorldDimensions", new Class<?>[0]),
                                         "dimensions", new Class<?>[0]);
                Object stem = ((java.util.Map<?, ?>) dimensions)
                        .get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
                if (stem == null) {
                    overworld = "no overworld stem";
                } else {
                    Object generator = call(stem, "generator", new Class<?>[0]);
                    Object source = call(generator, "getBiomeSource", new Class<?>[0]);
                    overworld = generator.getClass().getName() + " / " + source.getClass().getName();
                }
            } catch (Throwable thrown) {
                overworld = describe(thrown instanceof java.lang.reflect.InvocationTargetException wrapped
                                     && wrapped.getCause() != null ? wrapped.getCause() : thrown);
            }
            rows.add(id + "\t" + overworld);
        }
        write(report, rows);
        for (String row : rows) {
            sender.sendMessage("eprobe presets: " + row.replace('\t', ' '));
        }
    }

    // ------------------------------------------------------------------ level stems

    /**
     * The dimension registry as the server sees it. If `minecraft:overworld` is flat here, the
     * decision was made while the datapacks were read; if it is not, something replaced the
     * generator after the world was built.
     */
    private void stems(CommandSender sender) {
        var access = ((org.bukkit.craftbukkit.CraftServer) this.getServer()).getServer().registryAccess();
        List<String> rows = new ArrayList<>();
        try {
            Object registry = call(access, "registryOrThrow",
                                   new Class<?>[]{net.minecraft.resources.ResourceKey.class},
                                   net.minecraft.core.registries.Registries.LEVEL_STEM);
            java.util.Collection<?> keys = (java.util.Collection<?>) call(registry, "keySet", new Class<?>[0]);
            for (Object id : keys) {
                Object stem = call(registry, "get", new Class<?>[]{ResourceLocation.class}, id);
                Object generator = call(stem, "generator", new Class<?>[0]);
                Object source = call(generator, "getBiomeSource", new Class<?>[0]);
                rows.add(id + "\t" + generator.getClass().getName() + "\t" + source.getClass().getName());
            }
        } catch (Throwable problem) {
            rows.add("failed\t" + problem);
        }
        write(this.getDataFolder().toPath().resolve("stems.tsv"), rows);
        for (String row : rows) {
            sender.sendMessage("eprobe stems: " + row.replace('\t', ' '));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Player firstOnline() {
        for (Player player : this.getServer().getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    /** Call a method by name on whatever class the object really has, walking up if need be. */
    private static Object call(Object target, String name, Class<?>[] signature, Object... args)
            throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Method method = type.getMethod(name, signature);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // try the next class up
            }
        }
        throw new NoSuchMethodException(name + " on " + target.getClass().getName());
    }

    /** The exception, its message and the three frames that name who threw it - on one line. */
    private static String describe(Throwable thrown) {
        StringBuilder text = new StringBuilder("threw ").append(thrown.getClass().getSimpleName());
        if (thrown.getMessage() != null) {
            text.append(": ").append(firstLine(thrown.getMessage()));
        }
        StackTraceElement[] frames = thrown.getStackTrace();
        for (int i = 0; i < frames.length && i < 3; i++) {
            text.append(" | ").append(frames[i].getClassName())
                .append('.').append(frames[i].getMethodName())
                .append(':').append(frames[i].getLineNumber());
        }
        return text.toString();
    }

    private static String firstLine(String text) {
        int cut = text.indexOf('\n');
        return cut < 0 ? text : text.substring(0, cut);
    }

    private static String shortName(String className) {
        return className.startsWith("org.bukkit.craftbukkit.")
                ? className.substring("org.bukkit.craftbukkit.".length())
                : className;
    }

    private void write(Path path, List<String> rows) {
        try {
            Files.createDirectories(path.getParent());
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
                for (String row : rows) {
                    out.println(row);
                }
            }
        } catch (IOException problem) {
            this.getLogger().warning("could not write " + path + ": " + problem);
        }
    }
}
