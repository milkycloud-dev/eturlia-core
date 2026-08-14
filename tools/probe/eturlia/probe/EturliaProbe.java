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
            default -> sender.sendMessage("eprobe entities | menus | biomes");
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

    // ------------------------------------------------------------------ plumbing

    private Player firstOnline() {
        for (Player player : this.getServer().getOnlinePlayers()) {
            return player;
        }
        return null;
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
