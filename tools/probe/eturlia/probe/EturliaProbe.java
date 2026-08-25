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
            case "levels" -> this.levelTickRates(sender, args);
            case "item" -> this.itemLookup(sender, args);
            case "registryaudit" -> this.registryAudit(sender, args);
            case "track" -> this.track(sender, args);
            case "sublevels" -> this.subLevels(sender, args);
            case "blockstate" -> this.blockStates(sender, args);
            case "openblock" -> this.openBlock(sender, args);
            default -> sender.sendMessage("eprobe entities | menus | biomes | worldgen | levels | item <id>");
        }
        return true;
    }

    // ------------------------------------------------------------------ entities

    /**
     * Entity types a bare {@code create()} cannot build well enough for a client to tick.
     *
     * <p>Create's potato projectile reads its ammunition type on the first client tick. Summoned
     * with no data that field is null, and the client dies inside its own tick loop with
     * {@code NullPointerException: ... PotatoCannonProjectileType.gravityMultiplier()}. Firing the
     * cannon is what supplies it, so no player can reach this state - and the wrapper and packet
     * questions this sweep exists to answer are already answered by every other entity in it.</p>
     *
     * <p>Add to this list only when a type crashes the client for want of data the server cannot
     * invent, and say which data. Anything else belongs in the results.</p>
     */
    private static final java.util.Set<String> NEEDS_MOD_DATA = java.util.Set.of(
            "create:potato_projectile");

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
                if (NEEDS_MOD_DATA.contains(id.toString())) {
                    rows.add(id + "\t-\tskipped-needs-mod-data");
                    continue;
                }
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

    // ------------------------------------------------------------------ levels

    /**
     * Sample every level's game time twice, on the region thread, and report the ticks it advanced.
     *
     * <p>The sampling has to happen on a region thread. {@code Level.getGameTime()} reads
     * {@code getCurrentWorldData()}, which is thread-local to the region being ticked; asked from a
     * plugin thread it finds none and falls back to the saved level data, which does not move. A
     * probe that ignores this reports every level in the server as stalled and is measuring nothing
     * but its own thread.</p>
     *
     * <p>A level that is ticking keeps pace with the window - 20 ticks a second. One that does not
     * is either unloaded, has no region alive in it (nothing is keeping a chunk loaded there), or is
     * being ticked by something that has stopped.</p>
     */
    private void levelTickRates(CommandSender sender, String[] args) {
        long windowMillis = 3000L;
        if (args.length > 1) {
            try {
                windowMillis = Math.max(1000L, Long.parseLong(args[1]));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        final long window = windowMillis;

        java.util.Map<String, long[]> samples = new java.util.concurrent.ConcurrentHashMap<>();
        List<org.bukkit.World> worlds = new ArrayList<>(this.getServer().getWorlds());
        for (org.bukkit.World world : worlds) {
            ServerLevel level = ((CraftWorld) world).getHandle();
            String key = level.dimension().location().toString();
            samples.put(key, new long[] {-1L, -1L});
            this.getServer().getRegionScheduler().execute(this, world.getSpawnLocation(),
                    () -> samples.get(key)[0] = level.getGameTime());
        }
        sender.sendMessage("eprobe levels: sampling " + worlds.size() + " levels over " + window + "ms");

        Path report = this.getDataFolder().toPath().resolve("levels.tsv");
        this.getServer().getAsyncScheduler().runDelayed(this, (first) -> {
            for (org.bukkit.World world : worlds) {
                ServerLevel level = ((CraftWorld) world).getHandle();
                String key = level.dimension().location().toString();
                this.getServer().getRegionScheduler().execute(this, world.getSpawnLocation(),
                        () -> samples.get(key)[1] = level.getGameTime());
            }
            // Give those region tasks a tick or two to land before reading them.
            this.getServer().getAsyncScheduler().runDelayed(this, (second) -> {
                List<String> rows = new ArrayList<>();
                rows.add("level\tticks_in_window\texpected\tverdict");
                long expected = window / 50L;
                int bad = 0;
                for (org.bukkit.World world : worlds) {
                    String key = ((CraftWorld) world).getHandle().dimension().location().toString();
                    long[] pair = samples.get(key);
                    String verdict;
                    long advanced;
                    if (pair[0] < 0L || pair[1] < 0L) {
                        advanced = -1L;
                        verdict = "NO-REGION";
                        bad++;
                    } else {
                        advanced = pair[1] - pair[0];
                        if (advanced <= 0L) {
                            verdict = "STALLED";
                            bad++;
                        } else if (advanced * 100L < expected * 80L) {
                            verdict = "SLOW";
                            bad++;
                        } else {
                            verdict = "ok";
                        }
                    }
                    rows.add(key + "\t" + advanced + "\t" + expected + "\t" + verdict);
                }
                write(report, rows);
                sender.sendMessage("eprobe levels: " + bad + " of " + worlds.size()
                        + " not keeping pace -> " + report);
            }, 1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        }, window, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ item lookup

    /**
     * Says whether an id is in the block registry, the item registry, both or neither.
     *
     * <p>A loot table that names an item the item registry does not have fails to parse, and the
     * block silently drops nothing. The question that separates "the core lost the item" from "the
     * mod never registered one" is simply whether the block is there without its item.</p>
     */
    private void itemLookup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("usage: eprobe item <namespace:path> [more ids...]");
            return;
        }
        Object blockRegistry = builtIn("BLOCK");
        Object itemRegistry = builtIn("ITEM");
        if (blockRegistry == null || itemRegistry == null) {
            sender.sendMessage("eprobe item: could not reach BuiltInRegistries");
            return;
        }
        List<String> rows = new ArrayList<>();
        rows.add("id\tblock\titem\tverdict");
        for (int i = 1; i < args.length; i++) {
            String id = args[i];
            boolean hasBlock = registryHas(blockRegistry, id);
            boolean hasItem = registryHas(itemRegistry, id);
            String verdict;
            if (hasBlock && hasItem) {
                verdict = "ok";
            } else if (hasBlock) {
                verdict = "BLOCK-WITHOUT-ITEM";
            } else if (hasItem) {
                verdict = "item-only";
            } else {
                verdict = "NEITHER";
            }
            rows.add(id + "\t" + hasBlock + "\t" + hasItem + "\t" + verdict);
            sender.sendMessage("eprobe item: " + id + " block=" + hasBlock + " item=" + hasItem
                    + " -> " + verdict);
        }
        write(this.getDataFolder().toPath().resolve("items.tsv"), rows);
    }

    /** BuiltInRegistries.<name>, or null if this build does not have it under that name. */
    private static Object builtIn(String name) {
        try {
            return Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField(name).get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /**
     * Registry.containsKey(ResourceLocation), reached by name.
     *
     * <p>Bound reflectively for the same reason every other registry call in this plugin is: the
     * core's plugin remapper rewrites a Mojang-mapped call site into one this server does not
     * have, and a normally-compiled call dies with NoSuchMethodError at runtime.</p>
     */
    private static boolean registryHas(Object registry, String id) {
        try {
            Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
            Object key = resourceLocation.getMethod("tryParse", String.class).invoke(null, id);
            if (key == null) {
                return false;
            }
            for (java.lang.reflect.Method method : registry.getClass().getMethods()) {
                if (!"containsKey".equals(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0].isAssignableFrom(resourceLocation)) {
                    method.setAccessible(true);
                    return Boolean.TRUE.equals(method.invoke(registry, key));
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError thrown) {
            return false;
        }
    }

    // ------------------------------------------------------------------ registry audit

    /**
     * Every block and item the installed mods declare, checked against what this server registered.
     *
     * <p>One id missing is a mod that ships dead data. A whole namespace missing, or a mod losing a
     * consistent slice of what it declares, is the core dropping registry entries - a completely
     * different problem, and one that would explain far more than a few log lines. The only way to
     * tell them apart is to count both.</p>
     *
     * <p>What a mod declares is read from its own resources: {@code assets/<ns>/blockstates/<name>.json}
     * is the list of blocks it defines, and {@code assets/<ns>/models/item/<name>.json} the items.
     * Those are written by the mod's own data generation, so they are what the mod believes it
     * registers - independent of anything this server did.</p>
     */
    private void registryAudit(CommandSender sender, String[] args) {
        Object blockRegistry = builtIn("BLOCK");
        Object itemRegistry = builtIn("ITEM");
        if (blockRegistry == null || itemRegistry == null) {
            sender.sendMessage("eprobe registryaudit: could not reach BuiltInRegistries");
            return;
        }
        Path mods = Path.of("mods");
        if (!Files.isDirectory(mods)) {
            sender.sendMessage("eprobe registryaudit: no mods directory at " + mods.toAbsolutePath());
            return;
        }

        java.util.Map<String, java.util.Set<String>> declaredBlocks = new java.util.TreeMap<>();
        java.util.Map<String, java.util.Set<String>> declaredItems = new java.util.TreeMap<>();
        int jars = 0;
        try (java.util.stream.Stream<Path> listing = Files.list(mods)) {
            for (Path jar : listing.filter((p) -> p.toString().endsWith(".jar")).toList()) {
                jars++;
                collectDeclared(jar, declaredBlocks, declaredItems);
            }
        } catch (java.io.IOException e) {
            sender.sendMessage("eprobe registryaudit: could not read mods: " + e);
            return;
        }

        List<String> rows = new ArrayList<>();
        rows.add("namespace\tdeclared_blocks\tmissing_blocks\tdeclared_items\tmissing_items\tfirst_missing");
        List<String> detail = new ArrayList<>();
        detail.add("id\tkind");

        java.util.Set<String> namespaces = new java.util.TreeSet<>();
        namespaces.addAll(declaredBlocks.keySet());
        namespaces.addAll(declaredItems.keySet());

        int totalMissingBlocks = 0;
        int totalMissingItems = 0;
        List<String> worst = new ArrayList<>();
        for (String namespace : namespaces) {
            java.util.Set<String> blocks = declaredBlocks.getOrDefault(namespace, java.util.Set.of());
            java.util.Set<String> items = declaredItems.getOrDefault(namespace, java.util.Set.of());
            int missingBlocks = 0;
            int missingItems = 0;
            String first = "";
            for (String name : blocks) {
                String id = namespace + ":" + name;
                if (!registryHas(blockRegistry, id)) {
                    missingBlocks++;
                    detail.add(id + "\tblock");
                    if (first.isEmpty()) {
                        first = id + " (block)";
                    }
                }
            }
            for (String name : items) {
                String id = namespace + ":" + name;
                if (!registryHas(itemRegistry, id)) {
                    missingItems++;
                    detail.add(id + "\titem");
                    if (first.isEmpty()) {
                        first = id + " (item)";
                    }
                }
            }
            totalMissingBlocks += missingBlocks;
            totalMissingItems += missingItems;
            rows.add(namespace + "\t" + blocks.size() + "\t" + missingBlocks + "\t"
                    + items.size() + "\t" + missingItems + "\t" + first);
            if (missingBlocks + missingItems > 0) {
                worst.add(namespace + " " + missingBlocks + "/" + blocks.size() + " blocks, "
                        + missingItems + "/" + items.size() + " items");
            }
        }

        write(this.getDataFolder().toPath().resolve("registryaudit.tsv"), rows);
        write(this.getDataFolder().toPath().resolve("registryaudit-missing.tsv"), detail);
        sender.sendMessage("eprobe registryaudit: " + jars + " jars, " + namespaces.size()
                + " namespaces; missing " + totalMissingBlocks + " blocks and " + totalMissingItems
                + " items -> registryaudit.tsv");
        for (String line : worst.subList(0, Math.min(worst.size(), 12))) {
            sender.sendMessage("  " + line);
        }
    }

    /** Reads one jar's blockstates and item models, and its nested jars too. */
    private static void collectDeclared(Path jar,
            java.util.Map<String, java.util.Set<String>> blocks,
            java.util.Map<String, java.util.Set<String>> items) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".json") || !name.startsWith("assets/")) {
                    continue;
                }
                String[] parts = name.split("/");
                if (parts.length < 4) {
                    continue;
                }
                String namespace = parts[1];
                if (parts.length == 4 && "blockstates".equals(parts[2])) {
                    blocks.computeIfAbsent(namespace, (k) -> new java.util.TreeSet<>())
                            .add(stripJson(parts[3]));
                } else if (parts.length == 5 && "models".equals(parts[2]) && "item".equals(parts[3])) {
                    items.computeIfAbsent(namespace, (k) -> new java.util.TreeSet<>())
                            .add(stripJson(parts[4]));
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // a jar we cannot read tells us nothing; the rest still do
        }
    }

    private static String stripJson(String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    // ------------------------------------------------------------------ track

    /**
     * Samples the position of every entity of a type twice, and says whether any of them moved.
     *
     * <p>Written because the airship phase of the suite asserted with
     * {@code /execute if entity ... run say}, and this build drops /say text from the log - so it
     * reported "no errors" whether or not anything ever flew. This asks the entities themselves,
     * on the region thread that owns them, and reports the distance each one covered.</p>
     *
     * <p>The client is not involved at all: the rig is built and powered from the console, so a
     * verdict here cannot be an artefact of a keystroke that never landed.</p>
     */
    private void track(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage("usage: eprobe track <entity-type> <x> <y> <z> [radius] [seconds]");
            return;
        }
        String wanted = args[1].toLowerCase(java.util.Locale.ROOT);
        double x;
        double y;
        double z;
        double radius = 64.0D;
        long seconds = 10L;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
            if (args.length > 5) {
                radius = Double.parseDouble(args[5]);
            }
            if (args.length > 6) {
                seconds = Long.parseLong(args[6]);
            }
        } catch (NumberFormatException bad) {
            sender.sendMessage("eprobe track: " + bad.getMessage());
            return;
        }

        org.bukkit.World world = this.getServer().getWorlds().get(0);
        Location at = new Location(world, x, y, z);
        final double range = radius;
        java.util.Map<String, double[]> first = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, double[]> second = new java.util.concurrent.ConcurrentHashMap<>();

        this.getServer().getRegionScheduler().execute(this, at,
                () -> sampleInto(world, at, range, wanted, first));
        sender.sendMessage("eprobe track: watching '" + wanted + "' within " + range + " of "
                + x + "," + y + "," + z + " for " + seconds + "s");

        Path report = this.getDataFolder().toPath().resolve("track.tsv");
        this.getServer().getAsyncScheduler().runDelayed(this, (task) -> {
            this.getServer().getRegionScheduler().execute(this, at,
                    () -> sampleInto(world, at, range, wanted, second));
            this.getServer().getAsyncScheduler().runDelayed(this, (finish) -> {
                List<String> rows = new ArrayList<>();
                rows.add("entity\tx0\ty0\tz0\tx1\ty1\tz1\tdistance\tverdict");
                int moved = 0;
                for (java.util.Map.Entry<String, double[]> entry : first.entrySet()) {
                    double[] a = entry.getValue();
                    double[] b = second.get(entry.getKey());
                    if (b == null) {
                        rows.add(entry.getKey() + "\t" + a[0] + "\t" + a[1] + "\t" + a[2]
                                + "\t-\t-\t-\t-\tGONE");
                        continue;
                    }
                    double distance = Math.sqrt((b[0] - a[0]) * (b[0] - a[0])
                            + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
                    String verdict = distance > 0.05D ? "MOVED" : "still";
                    if (distance > 0.05D) {
                        moved++;
                    }
                    rows.add(entry.getKey() + "\t" + a[0] + "\t" + a[1] + "\t" + a[2]
                            + "\t" + b[0] + "\t" + b[1] + "\t" + b[2]
                            + "\t" + String.format(java.util.Locale.ROOT, "%.3f", distance)
                            + "\t" + verdict);
                }
                write(report, rows);
                if (first.isEmpty()) {
                    sender.sendMessage("eprobe track: NONE - no entity of that type is there");
                } else {
                    sender.sendMessage("eprobe track: " + first.size() + " found, " + moved
                            + " moved -> " + report);
                }
            }, 1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        }, Math.max(1000L, seconds * 1000L), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Records position by entity uuid for everything of the wanted type in range. */
    private static void sampleInto(org.bukkit.World world, Location at, double radius,
            String wanted, java.util.Map<String, double[]> into) {
        try {
            for (org.bukkit.entity.Entity entity : world.getNearbyEntities(at, radius, radius, radius)) {
                String type = entity.getType().getKey().toString().toLowerCase(java.util.Locale.ROOT);
                // A modded entity often answers UNKNOWN through the Bukkit type, so accept the
                // handle's own id too.
                String alternate = entity.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                if (!type.contains(wanted) && !alternate.contains(wanted) && !"*".equals(wanted)) {
                    continue;
                }
                Location where = entity.getLocation();
                into.put(entity.getUniqueId() + " " + type,
                        new double[] {where.getX(), where.getY(), where.getZ()});
            }
        } catch (RuntimeException thrown) {
            into.put("error " + thrown, new double[] {0.0D, 0.0D, 0.0D});
        }
    }

    // ------------------------------------------------------------------ sub-levels

    /**
     * Lists sable's physics sub-levels and whether they moved.
     *
     * <p>An Aeronautics airship is not an entity - it is a sub-level, a structure sable keeps in a
     * plot of its own and drives with a physics pipeline. Nothing in Bukkit can see one, so the
     * only way to answer "did the airship fly" is to ask sable, which is what this does: read the
     * container's sub-levels, take each one's logical pose now and again later, and report the
     * distance covered.</p>
     *
     * <p>Everything is bound by name at runtime. Sable absent, or a version that renamed any of
     * this, gives a clean "not available" instead of a stack trace.</p>
     */
    private void subLevels(CommandSender sender, String[] args) {
        long seconds = 10L;
        if (args.length > 1) {
            try {
                seconds = Math.max(1L, Long.parseLong(args[1]));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        org.bukkit.World world = this.getServer().getWorlds().get(0);
        java.util.Map<String, double[]> first = new java.util.LinkedHashMap<>();
        java.util.Map<String, double[]> second = new java.util.LinkedHashMap<>();

        String problem = poseSubLevels(world, first);
        if (problem != null) {
            sender.sendMessage("eprobe sublevels: " + problem);
            return;
        }
        sender.sendMessage("eprobe sublevels: " + first.size() + " now; sampling again in "
                + seconds + "s");

        Path report = this.getDataFolder().toPath().resolve("sublevels.tsv");
        final long window = seconds;
        this.getServer().getAsyncScheduler().runDelayed(this, (task) -> {
            poseSubLevels(world, second);
            List<String> rows = new ArrayList<>();
            rows.add("sublevel\tx0\ty0\tz0\tx1\ty1\tz1\tdistance\tverdict");
            int moved = 0;
            for (java.util.Map.Entry<String, double[]> entry : first.entrySet()) {
                double[] a = entry.getValue();
                double[] b = second.get(entry.getKey());
                if (b == null) {
                    rows.add(entry.getKey() + "\t" + a[0] + "\t" + a[1] + "\t" + a[2]
                            + "\t-\t-\t-\t-\tGONE");
                    continue;
                }
                double distance = Math.sqrt((b[0] - a[0]) * (b[0] - a[0])
                        + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
                if (distance > 0.05D) {
                    moved++;
                }
                rows.add(entry.getKey() + "\t" + a[0] + "\t" + a[1] + "\t" + a[2]
                        + "\t" + b[0] + "\t" + b[1] + "\t" + b[2]
                        + "\t" + String.format(java.util.Locale.ROOT, "%.3f", distance)
                        + "\t" + (distance > 0.05D ? "MOVED" : "still"));
            }
            write(report, rows);
            if (first.isEmpty()) {
                sender.sendMessage("eprobe sublevels: NONE - no sub-level exists in this world");
            } else {
                sender.sendMessage("eprobe sublevels: " + first.size() + " sub-levels, " + moved
                        + " moved over " + window + "s -> " + report);
            }
        }, Math.max(1000L, seconds * 1000L), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Fills {@code into} with each sub-level's world position; returns a reason on failure.
     *
     * <p>Every lookup is by exact signature through {@link java.lang.invoke.MethodHandles}, never
     * by enumerating methods. {@code SubLevelContainer.getContainer} is overloaded on
     * {@code ClientLevel} as well as {@code ServerLevel}, and merely listing the class's methods
     * resolves that parameter - which on a server is a {@code NoClassDefFoundError} for
     * {@code net/minecraft/client/multiplayer/ClientLevel}.</p>
     */
    private static String poseSubLevels(org.bukkit.World world, java.util.Map<String, double[]> into) {
        try {
            Object handle = world.getClass().getMethod("getHandle").invoke(world);
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> serverContainer = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            Class<?> vectorClass = Class.forName("org.joml.Vector3dc");

            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.publicLookup();
            java.lang.invoke.MethodHandle getContainer = lookup.findStatic(containerClass, "getContainer",
                    java.lang.invoke.MethodType.methodType(serverContainer, serverLevel));
            Object container = getContainer.invoke(handle);
            if (container == null) {
                return "no sub-level container in this world";
            }

            java.lang.reflect.Field field = containerClass.getDeclaredField("subLevels");
            field.setAccessible(true);
            Object array = field.get(container);
            if (array == null) {
                return "the container holds no sub-level array";
            }

            java.lang.invoke.MethodHandle isRemoved = lookup.findVirtual(subLevelClass, "isRemoved",
                    java.lang.invoke.MethodType.methodType(boolean.class));
            java.lang.invoke.MethodHandle logicalPose = lookup.findVirtual(subLevelClass, "logicalPose",
                    java.lang.invoke.MethodType.methodType(poseClass));
            java.lang.invoke.MethodHandle getUniqueId = lookup.findVirtual(subLevelClass, "getUniqueId",
                    java.lang.invoke.MethodType.methodType(java.util.UUID.class));
            java.lang.invoke.MethodHandle position = lookup.findVirtual(poseClass, "position",
                    java.lang.invoke.MethodType.methodType(vectorClass));
            java.lang.invoke.MethodHandle vx = lookup.findVirtual(vectorClass, "x",
                    java.lang.invoke.MethodType.methodType(double.class));
            java.lang.invoke.MethodHandle vy = lookup.findVirtual(vectorClass, "y",
                    java.lang.invoke.MethodType.methodType(double.class));
            java.lang.invoke.MethodHandle vz = lookup.findVirtual(vectorClass, "z",
                    java.lang.invoke.MethodType.methodType(double.class));

            int length = java.lang.reflect.Array.getLength(array);
            for (int i = 0; i < length; i++) {
                Object subLevel = java.lang.reflect.Array.get(array, i);
                if (subLevel == null || (boolean) isRemoved.invoke(subLevel)) {
                    continue;
                }
                Object pose = logicalPose.invoke(subLevel);
                Object where = position.invoke(pose);
                into.put(String.valueOf(getUniqueId.invoke(subLevel)), new double[] {
                        (double) vx.invoke(where),
                        (double) vy.invoke(where),
                        (double) vz.invoke(where)});
            }
            return null;
        } catch (ClassNotFoundException noSable) {
            return "sable is not installed (" + noSable.getMessage() + ")";
        } catch (Throwable thrown) {
            return "could not read sable's sub-levels: " + thrown;
        }
    }

    // ------------------------------------------------------------------ block states

    /**
     * Places each block next to a player and asks it for its Bukkit state, the way a plugin does.
     *
     * <p>Every plugin that listens for an interact or a break calls {@code block.getState()}. A
     * modded block entity borrowing a vanilla Material used to hand back a wrong-typed state and
     * throw a ClassCastException inside the plugin - WorldGuard and CoreProtect both - so the
     * handler never finished and the action silently failed for the player. This is the test for
     * that: no exception here means the plugins see a state they can read.</p>
     */
    private void blockStates(CommandSender sender, String[] args) {
        Player player = sender instanceof Player p ? p : firstOnline();
        if (player == null) {
            sender.sendMessage("eprobe blockstate: needs one player online");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("usage: eprobe blockstate <x> <y> <z> [count-along-x]");
            return;
        }
        final int bx;
        final int by;
        final int bz;
        final int span;
        try {
            bx = Integer.parseInt(args[1]);
            by = Integer.parseInt(args[2]);
            bz = Integer.parseInt(args[3]);
            span = args.length > 4 ? Math.max(1, Integer.parseInt(args[4])) : 1;
        } catch (NumberFormatException bad) {
            sender.sendMessage("eprobe blockstate: " + bad.getMessage());
            return;
        }
        Path report = this.getDataFolder().toPath().resolve("blockstates.tsv");
        org.bukkit.World world = player.getWorld();
        Location at = new Location(world, bx, by, bz);
        this.getServer().getRegionScheduler().execute(this, at, () -> {
            List<String> rows = new ArrayList<>();
            rows.add("position	block	outcome");
            int threw = 0;
            for (int i = 0; i < span; i++) {
                org.bukkit.block.Block block = world.getBlockAt(bx + i, by, bz);
                String what = block.getType().getKey().toString();
                String outcome;
                try {
                    Object state = block.getState();
                    outcome = "state " + shortName(state.getClass().getName());
                } catch (Throwable thrown) {
                    outcome = describe(thrown);
                    threw++;
                }
                rows.add((bx + i) + "," + by + "," + bz + "	" + what + "	" + outcome);
                sender.sendMessage("eprobe blockstate: " + (bx + i) + "," + by + "," + bz
                        + " " + what + " -> " + outcome);
            }
            write(report, rows);
            sender.sendMessage("eprobe blockstate: " + span + " read, " + threw + " threw -> " + report);
        });
    }

    // ------------------------------------------------------------------ open a block

    /**
     * Asks a placed block for its menu and opens it for a real player.
     *
     * <p>This is the server half of a right-click: {@code BlockState.getMenuProvider} then
     * {@code ServerPlayer.openMenu}. Building a menu straight from {@code MenuType.create} does not
     * test anything - that is the *client* factory, and it wants a {@code FriendlyByteBuf} that
     * only exists on the client, so it throws for vanilla furnaces just as readily as for a modded
     * backpack. Asking the block is what a player actually causes to happen.</p>
     */
    private void openBlock(CommandSender sender, String[] args) {
        Player player = sender instanceof Player p ? p : firstOnline();
        if (player == null) {
            sender.sendMessage("eprobe openblock: needs one player online");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("usage: eprobe openblock <x> <y> <z>");
            return;
        }
        final int bx;
        final int by;
        final int bz;
        try {
            bx = Integer.parseInt(args[1]);
            by = Integer.parseInt(args[2]);
            bz = Integer.parseInt(args[3]);
        } catch (NumberFormatException bad) {
            sender.sendMessage("eprobe openblock: " + bad.getMessage());
            return;
        }
        Location at = new Location(player.getWorld(), bx, by, bz);
        this.getServer().getRegionScheduler().execute(this, at, () -> {
            String outcome;
            try {
                net.minecraft.server.level.ServerPlayer handle = ((CraftPlayer) player).getHandle();
                net.minecraft.server.level.ServerLevel level = handle.serverLevel();
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(bx, by, bz);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                net.minecraft.world.MenuProvider provider = state.getMenuProvider(level, pos);
                if (provider == null) {
                    outcome = "no menu provider for " + state.getBlock();
                } else {
                    java.util.OptionalInt id = handle.openMenu(provider);
                    outcome = id.isPresent() ? "opened, container id " + id.getAsInt()
                            : "provider refused to open";
                }
            } catch (Throwable thrown) {
                outcome = describe(thrown);
            }
            sender.sendMessage("eprobe openblock: " + bx + "," + by + "," + bz + " -> " + outcome);
            write(this.getDataFolder().toPath().resolve("openblock.tsv"),
                    List.of("position\toutcome", bx + "," + by + "," + bz + "\t" + outcome));
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
        Object registry = builtIn("MENU");
        if (registry == null) {
            sender.sendMessage("eprobe menus: could not reach BuiltInRegistries.MENU");
            return;
        }
        Path report = this.getDataFolder().toPath().resolve("menus.tsv");
        this.getServer().getRegionScheduler().execute(this, player.getLocation(), () -> {
            List<String> rows = new ArrayList<>();
            rows.add("menu\toutcome");
            net.minecraft.world.entity.player.Inventory inventory =
                    ((CraftPlayer) player).getHandle().getInventory();
            int viewed = 0;
            int broken = 0;
            // Iterated as an Iterable and keyed by name: the core's plugin remapper rewrites a
            // Mojang-mapped keySet()/get() call site into one this server does not have, which is
            // why this whole command used to die with NoSuchFieldError before it listed anything.
            for (Object entry : (Iterable<?>) registry) {
                String id = String.valueOf(callByName(registry, "getKey", entry));
                String outcome;
                try {
                    MenuType<?> type = (MenuType<?>) entry;
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
            sender.sendMessage("eprobe menus: " + viewed + " gave a view, " + broken
                    + " threw -> " + report);
        });
    }

    /** Calls a one-argument method by name, so no call site is left for the remapper to rewrite. */
    private static Object callByName(Object target, String method, Object argument) {
        for (java.lang.reflect.Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == 1) {
                try {
                    candidate.setAccessible(true);
                    return candidate.invoke(target, argument);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
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
