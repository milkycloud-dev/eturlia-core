#!/usr/bin/env python3
"""Install the Eturlia compatibility layer into the patched Folia sources.

Run this after `./gradlew applyPatches` and before building the jar. It is idempotent: every
edit is applied once and recognised on a second run, so it is safe to re-run at any point.

Every plane is switchable at runtime; `strict` restores stock Folia behaviour:

  -Deturlia.compat.mixins=soft|relax|strict     MixinCompat, wired into the launch handler
  -Deturlia.compat.plugins=true|false           folia-supported gate + legacy BukkitScheduler
  -Deturlia.compat.registries=lenient|strict    late registration into frozen registries
  -Deturlia.compat.modloading=lenient|strict    mod loading issues stop being fatal
  -Deturlia.compat.folia-stubs=lenient|strict   getTickCount and the main-thread dispatch
  -Deturlia.compat.bukkit-types=lenient|strict  modded entities/sounds seen by plugins
  -Deturlia.compat.quarantine=<mod ids>         extra mods to soft-skip
  -Deturlia.compat.plugin-remap=true            Paper's Spigot->Mojang plugin remapper (see docs)

Paths are derived from this file's location, so the script works from a checkout, from the
test workspace, or from CI. Override with ETURLIA_CORE / ETURLIA_COMPAT_SRC if the layout
differs.
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CORE = os.environ.get("ETURLIA_CORE") or os.path.abspath(os.path.join(HERE, ".."))
SERVER = CORE + "/Folia-Server/src/main/java"
API = CORE + "/Folia-API/src/main/java"
ETURLIA = CORE + "/build-data/eturlia-core/src/main/java"

# The compatibility classes are plain sources copied into the build; they live in the repo
# next to the rest of eturlia-core unless an out-of-tree copy is pointed at explicitly.
COMPAT_DIR = os.environ.get("ETURLIA_COMPAT_SRC") or (ETURLIA + "/eturlia/core/compat")
COMPAT_CLASSES = ("MixinCompat.java", "ModLoadingCompat.java")


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def replace(path, old, new, label, marker=None):
    """Apply an edit once. `marker` is what proves it is already there, when a later edit has
    since changed the surrounding lines and `new` no longer matches verbatim."""
    text = read(path)
    if (marker or new) in text:
        print("  already applied: " + label)
        return
    if old not in text:
        print("  !! anchor missing: " + label)
        sys.exit(1)
    write(path, text.replace(old, new, 1))
    print("  " + label)


# --------------------------------------------------------------------- mods

def install_mixin_compat():
    print("mods plane")
    for name in COMPAT_CLASSES:
        source = os.path.join(COMPAT_DIR, name)
        target = ETURLIA + "/eturlia/core/compat/" + name
        if os.path.exists(source):
            write(target, read(source))
            print("  " + name)
        elif not os.path.exists(target):
            print("  !! " + name + " not found at " + source)
            sys.exit(1)

    handler = ETURLIA + "/eturlia/launch/EturliaServerLaunchHandler.java"
    replace(
        handler,
        """        Class<?> craftMain = Class.forName(minecraft, "org.bukkit.craftbukkit.Main");
        if (craftMain == null) {
            throw new ClassNotFoundException("org.bukkit.craftbukkit.Main not in module minecraft");
        }

        installMixinErrorHandler();
        installEturliaRuntime(gameLayer, arguments);
""",
        """        // Mixin applies as classes load, and the line below loads one. Compatibility has to be
        // in place first, or the very first game class can still be failed by a mod's injector.
        installMixinErrorHandler();
        eturlia.core.compat.MixinCompat.install();

        Class<?> craftMain = Class.forName(minecraft, "org.bukkit.craftbukkit.Main");
        if (craftMain == null) {
            throw new ClassNotFoundException("org.bukkit.craftbukkit.Main not in module minecraft");
        }

        installEturliaRuntime(gameLayer, arguments);
""",
        "launch handler installs mixin compatibility before the first game class",
        marker="eturlia.core.compat.MixinCompat.install();",
    )
    replace(
        handler,
        "        eturlia.core.compat.MixinCompat.install();\n",
        "        eturlia.core.compat.MixinCompat.install();\n"
        "        eturlia.core.compat.ModLoadingCompat.install();\n",
        "launch handler installs mod loading compatibility",
    )


# ------------------------------------------------------------------ plugins

def install_plugin_compat():
    print("plugins plane")

    # 1. The folia-supported gate, on both plugin description formats.
    replace(
        API + "/org/bukkit/plugin/PluginDescriptionFile.java",
        """    @Override
    public boolean isFoliaSupported() {
        return this.foliaSupported != null && this.foliaSupported.equalsIgnoreCase("true");
    }""",
        """    @Override
    public boolean isFoliaSupported() {
        // Eturlia start - accept plugins that never heard of Folia
        // Folia refuses anything without folia-supported: true, which is every plugin written for
        // Paper. Eturlia takes them and gives them a legacy scheduler running on the global region
        // thread instead; see CraftScheduler. -Deturlia.compat.plugins=false restores the gate.
        if (eturlia$compatPlugins()) {
            return true;
        }
        // Eturlia end - accept plugins that never heard of Folia
        return this.foliaSupported != null && this.foliaSupported.equalsIgnoreCase("true");
    }

    // Eturlia start - accept plugins that never heard of Folia
    /** Whether plugins without a Folia marker are loaded anyway. */
    public static boolean eturlia$compatPlugins() {
        return !"false".equalsIgnoreCase(System.getProperty("eturlia.compat.plugins", "true"));
    }
    // Eturlia end - accept plugins that never heard of Folia""",
        "PluginDescriptionFile.isFoliaSupported",
    )

    replace(
        SERVER + "/io/papermc/paper/plugin/provider/configuration/PaperPluginMeta.java",
        """    @Override
    public boolean isFoliaSupported() {
        return this.foliaSupported;
    }""",
        """    @Override
    public boolean isFoliaSupported() {
        // Eturlia - accept plugins that never heard of Folia; see PluginDescriptionFile
        return this.foliaSupported || org.bukkit.plugin.PluginDescriptionFile.eturlia$compatPlugins();
    }""",
        "PaperPluginMeta.isFoliaSupported",
    )

    # 2. The legacy scheduler itself.
    scheduler = SERVER + "/org/bukkit/craftbukkit/scheduler/CraftScheduler.java"
    replace(
        scheduler,
        """    protected CraftTask handle(final CraftTask task, final long delay) { // Paper
        if (true) throw new UnsupportedOperationException(); // Folia - region threading""",
        """    // Eturlia start - legacy scheduler on the global region thread
    /**
     * Whether {@code BukkitScheduler}'s sync methods work.
     *
     * <p>Folia removed them because there is no main thread to run them on. Eturlia runs them on
     * the global region thread, which owns no chunks but ticks every tick and is the closest thing
     * left to a main thread. A plugin that only touches its own state — most of them — behaves
     * exactly as it did on Paper. One that reaches into a chunk from there still has to go through
     * the region schedulers.</p>
     */
    public static boolean eturlia$legacyScheduler() {
        return !"false".equalsIgnoreCase(System.getProperty("eturlia.compat.plugins", "true"));
    }

    /** Drives the legacy scheduler from {@code RegionizedServer}'s global tick. */
    public static void eturlia$tickLegacy(final int tickCount) {
        if (!eturlia$legacyScheduler()) {
            return;
        }
        final org.bukkit.Server server = org.bukkit.Bukkit.getServer();
        if (server == null) {
            return;
        }
        final org.bukkit.scheduler.BukkitScheduler scheduler = server.getScheduler();
        if (scheduler instanceof CraftScheduler craft) {
            try {
                craft.mainThreadHeartbeat(tickCount);
            } catch (final Throwable thrown) {
                // A plugin task must not be able to stop the global tick.
                server.getLogger().log(Level.WARNING, "Legacy scheduler tick failed", thrown);
            }
        }
    }
    // Eturlia end - legacy scheduler on the global region thread

    protected CraftTask handle(final CraftTask task, final long delay) { // Paper
        if (!eturlia$legacyScheduler()) throw new UnsupportedOperationException(); // Folia - region threading // Eturlia - unless compatibility is on""",
        "CraftScheduler.handle + legacy tick entry point",
    )

    replace(
        SERVER + "/io/papermc/paper/threadedregions/RegionizedServer.java",
        """        // scheduler
        ((FoliaGlobalRegionScheduler)Bukkit.getGlobalRegionScheduler()).tick();""",
        """        // scheduler
        ((FoliaGlobalRegionScheduler)Bukkit.getGlobalRegionScheduler()).tick();
        org.bukkit.craftbukkit.scheduler.CraftScheduler.eturlia$tickLegacy((int)this.tickCount); // Eturlia - legacy BukkitScheduler runs here""",
        "RegionizedServer drives the legacy scheduler",
    )


# --------------------------------------------------------------- vanilla shapes

def install_configuration_listener_shape():
    """The configuration listener keeps a constructor with vanilla's signature, for mods."""
    print("vanilla shape plane")
    replace(
        SERVER + "/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java",
        """    // CraftBukkit start
    public ServerConfigurationPacketListenerImpl(MinecraftServer minecraftserver, Connection networkmanager, CommonListenerCookie commonlistenercookie, ServerPlayer player) {
        super(minecraftserver, networkmanager, commonlistenercookie, player);
        // CraftBukkit end
        this.gameProfile = commonlistenercookie.gameProfile();
        this.clientInformation = commonlistenercookie.clientInformation();
    }""",
        """    // Eturlia start - the only constructor is shaped like vanilla's
    // CraftBukkit added a ServerPlayer parameter here. badpackets injects into this constructor to
    // install its packet handler, and its injector is written against vanilla's signature — so the
    // injection is refused, the handler stays null, and every modded client is dropped during
    // configuration with "NullPointerException ... badpackets_handler() is null". One parameter
    // makes the whole pack unjoinable.
    //
    // An extra overload does not help: a mixin aimed at "<init>" is applied to every constructor,
    // so the CraftBukkit one has to stop being a constructor. It becomes a factory instead, and
    // hands the player over out of band.
    private static final ThreadLocal<ServerPlayer> ETURLIA_INCOMING_PLAYER = new ThreadLocal<>();

    // CraftBukkit start
    public static ServerConfigurationPacketListenerImpl eturlia$create(MinecraftServer minecraftserver, Connection networkmanager, CommonListenerCookie commonlistenercookie, ServerPlayer player) {
        ETURLIA_INCOMING_PLAYER.set(player);
        try {
            return new ServerConfigurationPacketListenerImpl(minecraftserver, networkmanager, commonlistenercookie);
        } finally {
            ETURLIA_INCOMING_PLAYER.remove();
        }
    }
    // CraftBukkit end

    public ServerConfigurationPacketListenerImpl(MinecraftServer minecraftserver, Connection networkmanager, CommonListenerCookie commonlistenercookie) {
        super(minecraftserver, networkmanager, commonlistenercookie, ETURLIA_INCOMING_PLAYER.get());
        this.gameProfile = commonlistenercookie.gameProfile();
        this.clientInformation = commonlistenercookie.clientInformation();
    }
    // Eturlia end - the only constructor is shaped like vanilla's""",
        "ServerConfigurationPacketListenerImpl keeps vanilla's constructor",
    )

    for path, old, new in (
        (SERVER + "/net/minecraft/server/network/ServerGamePacketListenerImpl.java",
         "new ServerConfigurationPacketListenerImpl(this.server, this.connection, this.createCookie(this.player.clientInformation()), this.player)",
         "ServerConfigurationPacketListenerImpl.eturlia$create(this.server, this.connection, this.createCookie(this.player.clientInformation()), this.player)"),
        (SERVER + "/net/minecraft/server/network/ServerLoginPacketListenerImpl.java",
         "new ServerConfigurationPacketListenerImpl(this.server, this.connection, commonlistenercookie, this.player)",
         "ServerConfigurationPacketListenerImpl.eturlia$create(this.server, this.connection, commonlistenercookie, this.player)"),
    ):
        replace(path, old, new, "call site in " + os.path.basename(path))


# ---------------------------------------------------------------- capabilities

def install_capability_accessors():
    """Entities and item stacks answer NeoForge capability lookups."""
    print("capability plane")

    replace(
        SERVER + "/net/minecraft/world/entity/Entity.java",
        "    public Entity(EntityType<?> type, Level world) {",
        """    // Eturlia start - NeoForge capability lookups
    /**
     * Looks up a capability attached to this entity.
     *
     * <p>NeoForge patches these two methods straight into {@code Entity}, and mods call them
     * constantly — Curios asks every {@code LivingEntity} for its inventory on the first tick after
     * a player joins. Without them the call is a {@code NoSuchMethodError} inside a region tick,
     * and Folia ends the server rather than skip a tick.</p>
     */
    @Nullable
    public final <T> T getCapability(net.neoforged.neoforge.capabilities.EntityCapability<T, Void> capability) {
        return capability.getCapability(this, null);
    }

    @Nullable
    public final <T, C> T getCapability(net.neoforged.neoforge.capabilities.EntityCapability<T, C> capability, C context) {
        return capability.getCapability(this, context);
    }
    // Eturlia end - NeoForge capability lookups

    public Entity(EntityType<?> type, Level world) {""",
        "Entity.getCapability",
    )

    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        """    @Override
    public BlockState getBlockState(BlockPos pos) {""",
        """    // Eturlia start - NeoForge capability lookups
    /**
     * Looks up a capability attached to a block or block entity.
     *
     * <p>The block half of the same API as {@code Entity.getCapability}. Supplementaries asks for
     * it for every block entity in every chunk the server sends a player, so without it a join
     * produces hundreds of {@code NoSuchMethodError}s and the block entity data never reaches the
     * client.</p>
     */
    @Nullable
    public <T, C> T getCapability(net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability, BlockPos pos, C context) {
        return capability.getCapability((Level) (Object) this, pos, null, null, context);
    }

    @Nullable
    public <T, C> T getCapability(net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability, BlockPos pos, @Nullable BlockState state, @Nullable net.minecraft.world.level.block.entity.BlockEntity blockEntity, C context) {
        return capability.getCapability((Level) (Object) this, pos, state, blockEntity, context);
    }
    // Eturlia end - NeoForge capability lookups

    @Override
    public BlockState getBlockState(BlockPos pos) {""",
        "Level.getCapability",
    )

    replace(
        SERVER + "/net/minecraft/world/item/ItemStack.java",
        "    public Item getItem() {",
        """    // Eturlia start - NeoForge capability lookups
    /** The item-stack half of NeoForge's capability API; see {@code Entity.getCapability}. */
    @Nullable
    public <T> T getCapability(net.neoforged.neoforge.capabilities.ItemCapability<T, Void> capability) {
        return capability.getCapability(this, null);
    }

    @Nullable
    public <T, C> T getCapability(net.neoforged.neoforge.capabilities.ItemCapability<T, C> capability, C context) {
        return capability.getCapability(this, context);
    }
    // Eturlia end - NeoForge capability lookups

    public Item getItem() {""",
        "ItemStack.getCapability",
    )


# ---------------------------------------------------------------- bukkit types

def install_bukkit_type_bridges():
    """A modded entity type no longer kills the region that tries to spawn it."""
    print("bukkit type plane")
    replace(
        API + "/org/bukkit/entity/EntityType.java",
        """    @NotNull
    @Override
    public NamespacedKey getKey() {
        Preconditions.checkArgument(key != null, "EntityType doesn't have key! Is it UNKNOWN?");

        return key;
    }""",
        """    // Eturlia - a key for UNKNOWN; see getKey() below
    private static final NamespacedKey ETURLIA_UNKNOWN_KEY = new NamespacedKey("eturlia", "unknown");

    @NotNull
    @Override
    public NamespacedKey getKey() {
        // Eturlia start - UNKNOWN has to answer
        // Every modded entity reaches plugins as UNKNOWN, because this is an enum and nothing can
        // add to it at runtime. Listeners read the type of a spawning entity and call getKey() on
        // it without checking; throwing aborts the dispatch, so every plugin registered after that
        // one stops seeing spawns at all. An unresolvable key is a far smaller lie than that.
        if (key == null && !"strict".equalsIgnoreCase(System.getProperty("eturlia.compat.bukkit-types", "lenient"))) {
            return EntityType.ETURLIA_UNKNOWN_KEY;
        }
        // Eturlia end - UNKNOWN has to answer
        Preconditions.checkArgument(key != null, "EntityType doesn't have key! Is it UNKNOWN?");

        return key;
    }""",
        "EntityType.UNKNOWN answers getKey()",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/CraftSound.java",
        """    public static Sound minecraftToBukkit(SoundEvent minecraft) {
        Preconditions.checkArgument(minecraft != null);

        net.minecraft.core.Registry<SoundEvent> registry = CraftRegistry.getMinecraftRegistry(Registries.SOUND_EVENT);
        Sound bukkit = Registry.SOUNDS.get(CraftNamespacedKey.fromMinecraft(registry.getResourceKey(minecraft).orElseThrow().location()));

        Preconditions.checkArgument(bukkit != null);

        return bukkit;
    }""",
        """    public static Sound minecraftToBukkit(SoundEvent minecraft) {
        Preconditions.checkArgument(minecraft != null);

        net.minecraft.core.Registry<SoundEvent> registry = CraftRegistry.getMinecraftRegistry(Registries.SOUND_EVENT);
        // Eturlia start - a modded sound has no Bukkit constant
        // org.bukkit.Sound is a fixed registry of vanilla sounds. A modded mob's death sound is
        // not in it, and throwing here happens inside CraftEventFactory.populateFields while the
        // EntityDeathEvent is being built - so the mob's whole death, drops included, is lost with
        // an "Entity threw exception" in the log. Every reader of this value already handles null
        // (populateFields sets it to null for a silent death, playDeathSound checks for null).
        java.util.Optional<net.minecraft.resources.ResourceKey<SoundEvent>> key = registry.getResourceKey(minecraft);
        boolean lenient = !"strict".equalsIgnoreCase(System.getProperty("eturlia.compat.bukkit-types", "lenient"));
        if (key.isEmpty()) {
            if (lenient) {
                return null;
            }
            throw new IllegalArgumentException("Sound is not registered: " + minecraft);
        }
        Sound bukkit = Registry.SOUNDS.get(CraftNamespacedKey.fromMinecraft(key.get().location()));

        if (bukkit == null && lenient) {
            return null;
        }
        // Eturlia end - a modded sound has no Bukkit constant

        Preconditions.checkArgument(bukkit != null);

        return bukkit;
    }""",
        "CraftSound answers null for a modded sound",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/entity/CraftEntityType.java",
        """    public static EntityType minecraftToBukkit(net.minecraft.world.entity.EntityType<?> minecraft) {
        Preconditions.checkArgument(minecraft != null);

        net.minecraft.core.Registry<net.minecraft.world.entity.EntityType<?>> registry = CraftRegistry.getMinecraftRegistry(Registries.ENTITY_TYPE);
        EntityType bukkit = Registry.ENTITY_TYPE.get(CraftNamespacedKey.fromMinecraft(registry.getResourceKey(minecraft).orElseThrow().location()));

        Preconditions.checkArgument(bukkit != null);

        return bukkit;
    }""",
        """    // Eturlia start - Bukkit's EntityType enum cannot know about modded entities
    /** Whether an entity with no Bukkit counterpart is reported as UNKNOWN instead of throwing. */
    private static boolean eturlia$lenient() {
        return !"strict".equalsIgnoreCase(System.getProperty("eturlia.compat.bukkit-types", "lenient"));
    }

    private static final java.util.Set<String> ETURLIA_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Eturlia end - Bukkit's EntityType enum cannot know about modded entities

    public static EntityType minecraftToBukkit(net.minecraft.world.entity.EntityType<?> minecraft) {
        Preconditions.checkArgument(minecraft != null);

        net.minecraft.core.Registry<net.minecraft.world.entity.EntityType<?>> registry = CraftRegistry.getMinecraftRegistry(Registries.ENTITY_TYPE);
        // Eturlia start - a modded entity has no Bukkit counterpart
        // EntityType is an enum in the Bukkit API, so nothing can add to it at runtime. Throwing
        // here reaches NaturalSpawner, which is inside a region tick, and Folia answers a failed
        // region tick by shutting the server down: the first natural spawn of any modded mob ends
        // the server a second after a player joins. UNKNOWN is what the API already uses for an
        // entity it cannot name, so events still fire and the region keeps ticking.
        java.util.Optional<net.minecraft.resources.ResourceKey<net.minecraft.world.entity.EntityType<?>>> key = registry.getResourceKey(minecraft);
        if (key.isEmpty()) {
            if (!eturlia$lenient()) {
                throw new IllegalArgumentException("Entity type is not registered: " + minecraft);
            }
            return EntityType.UNKNOWN;
        }
        EntityType bukkit = Registry.ENTITY_TYPE.get(CraftNamespacedKey.fromMinecraft(key.get().location()));

        if (bukkit == null) {
            if (!eturlia$lenient()) {
                Preconditions.checkArgument(false);
            }
            // A pack with ninety mods has hundreds of entity types, and every one of them used to
            // print a line the first time a player came near it. The fact is worth knowing once;
            // the list is not worth the console.
            if (ETURLIA_REPORTED.add(key.get().location().toString())) {
                int seen = ETURLIA_REPORTED.size();
                if (seen <= 5) {
                    org.bukkit.Bukkit.getLogger().info("Eturlia: " + key.get().location()
                            + " is a modded entity, so plugins see it as UNKNOWN");
                } else if (seen == 6) {
                    org.bukkit.Bukkit.getLogger().info("Eturlia: more modded entity types follow;"
                            + " plugins see all of them as UNKNOWN (-Deturlia.compat.bukkit-types=strict to refuse instead)");
                }
            }
            return EntityType.UNKNOWN;
        }
        // Eturlia end - a modded entity has no Bukkit counterpart

        return bukkit;
    }""",
        "CraftEntityType.minecraftToBukkit answers UNKNOWN for modded entities",
    )

    replace(
        SERVER + "/org/bukkit/craftbukkit/entity/CraftEntity.java",
        """        throw new AssertionError("Unknown entity " + (entity == null ? null : entity.getClass()));""",
        """        // Eturlia start - wrap a modded entity in the nearest Bukkit type
        // CraftBukkit builds its wrapper from the Bukkit EntityType, and a modded entity has none,
        // so this used to be an AssertionError thrown from inside ServerLevel.addEntity — during a
        // region tick, which Folia answers by shutting the server down. The first Alex's Mobs
        // cockroach to spawn near a player ended the server.
        //
        // A plugin asking about a modded mob now gets a LivingEntity or a plain Entity: less than
        // the truth, but every method it can call still works.
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            // CraftBukkit's own event code casts the wrapper of anything that flies to Projectile -
            // ProjectileHitEvent and ProjectileCollideEvent both do it unconditionally - so a
            // modded projectile handed the plain wrapper takes its region down on first impact.
            // Create's potato cannon did exactly that.
            return new EturliaUnknownProjectile(server, projectile);
        }
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            return new CraftLivingEntity(server, living);
        }
        return new EturliaUnknownEntity(server, entity);
    }

    /** The wrapper for a modded projectile: everything Bukkit does with one starts with a cast. */
    public static final class EturliaUnknownProjectile extends CraftProjectile {

        public EturliaUnknownProjectile(CraftServer server, net.minecraft.world.entity.projectile.Projectile entity) {
            super(server, entity);
        }

        @Override
        public String toString() {
            return "EturliaUnknownProjectile{" + this.getHandleRaw().getClass().getName() + "}";
        }
    }

    /** The wrapper for an entity type Bukkit has no class for — anything a mod adds. */
    public static final class EturliaUnknownEntity extends CraftEntity {

        public EturliaUnknownEntity(CraftServer server, Entity entity) {
            super(server, entity);
        }

        // getType() is final here and already answers UNKNOWN through CraftEntityType.

        @Override
        public String toString() {
            return "EturliaUnknownEntity{" + this.getHandle().getClass().getName() + "}";
        }
        // Eturlia end - wrap a modded entity in the nearest Bukkit type""",
        "CraftEntity.getEntity wraps modded entities",
    )


# ----------------------------------------------------------------- recipe book

MC_DEV = CORE + "/Folia-Server/.gradle/caches/paperweight/mc-dev-sources"


def install_recipe_book_settings():
    """Recipe book settings survive a category a mod added."""
    print("recipe book plane")
    path = SERVER + "/net/minecraft/stats/RecipeBookSettings.java"
    if not os.path.exists(path):
        write(path, read(MC_DEV + "/net/minecraft/stats/RecipeBookSettings.java"))
        print("  imported RecipeBookSettings.java")

    replace(
        path,
        "    private final Map<RecipeBookType, RecipeBookSettings.TypeSettings> states;",
        """    private final Map<RecipeBookType, RecipeBookSettings.TypeSettings> states;

    // Eturlia start - a mod's recipe book category has no vanilla tag names and no default state
    // TAG_FIELDS above only names vanilla's four categories, and every read here assumed the map
    // held an entry for each. FarmersDelight adds a fifth, so loading a player died on
    // "Cannot invoke TypeSettings.copy() because typeSettings is null" and the client was told
    // "Invalid player data" — a join failure that reads like corruption but is a missing default.
    private static Pair<String, String> eturlia$tagFields(RecipeBookType category) {
        Pair<String, String> vanilla = TAG_FIELDS.get(category);
        if (vanilla != null) {
            return vanilla;
        }
        String name = category.name().toLowerCase(java.util.Locale.ROOT);
        return Pair.of("isGuiOpen_" + name, "isFilteringCraftable_" + name);
    }

    /** The settings for a category, created closed and unfiltered if nothing has them yet. */
    private RecipeBookSettings.TypeSettings eturlia$settings(RecipeBookType category) {
        RecipeBookSettings.TypeSettings settings = this.states.get(category);
        if (settings == null) {
            settings = new RecipeBookSettings.TypeSettings(false, false);
            this.states.put(category, settings);
        }
        return settings;
    }
    // Eturlia end - a mod's recipe book category has no vanilla tag names and no default state""",
        "RecipeBookSettings knows about modded categories",
    )

    for old, new, label in (
        ("        return this.states.get(category).open;",
         "        return this.eturlia$settings(category).open; // Eturlia - modded categories",
         "isOpen"),
        ("        this.states.get(category).open = open;",
         "        this.eturlia$settings(category).open = open; // Eturlia - modded categories",
         "setOpen"),
        ("        return this.states.get(category).filtering;",
         "        return this.eturlia$settings(category).filtering; // Eturlia - modded categories",
         "isFiltering"),
        ("        this.states.get(category).filtering = filtering;",
         "        this.eturlia$settings(category).filtering = filtering; // Eturlia - modded categories",
         "setFiltering"),
        ("""        TAG_FIELDS.forEach((category, pair) -> {
            boolean bl = nbt.getBoolean(pair.getFirst());
            boolean bl2 = nbt.getBoolean(pair.getSecond());
            map.put(category, new RecipeBookSettings.TypeSettings(bl, bl2));
        });""",
         """        // Eturlia - every category, not only the four vanilla names
        for (RecipeBookType category : RecipeBookType.values()) {
            Pair<String, String> pair = eturlia$tagFields(category);
            boolean bl = nbt.getBoolean(pair.getFirst());
            boolean bl2 = nbt.getBoolean(pair.getSecond());
            map.put(category, new RecipeBookSettings.TypeSettings(bl, bl2));
        }""",
         "read(CompoundTag)"),
        ("""        TAG_FIELDS.forEach((category, pair) -> {
            RecipeBookSettings.TypeSettings typeSettings = this.states.get(category);
            nbt.putBoolean(pair.getFirst(), typeSettings.open);
            nbt.putBoolean(pair.getSecond(), typeSettings.filtering);
        });""",
         """        // Eturlia - every category, not only the four vanilla names
        for (RecipeBookType category : RecipeBookType.values()) {
            Pair<String, String> pair = eturlia$tagFields(category);
            RecipeBookSettings.TypeSettings typeSettings = this.eturlia$settings(category);
            nbt.putBoolean(pair.getFirst(), typeSettings.open);
            nbt.putBoolean(pair.getSecond(), typeSettings.filtering);
        }""",
         "write(CompoundTag)"),
        ("""            RecipeBookSettings.TypeSettings typeSettings = this.states.get(recipeBookType);
            map.put(recipeBookType, typeSettings.copy());""",
         """            RecipeBookSettings.TypeSettings typeSettings = this.eturlia$settings(recipeBookType); // Eturlia - modded categories
            map.put(recipeBookType, typeSettings.copy());""",
         "copy"),
        ("""            RecipeBookSettings.TypeSettings typeSettings = other.states.get(recipeBookType);
            this.states.put(recipeBookType, typeSettings.copy());""",
         """            RecipeBookSettings.TypeSettings typeSettings = other.eturlia$settings(recipeBookType); // Eturlia - modded categories
            this.states.put(recipeBookType, typeSettings.copy());""",
         "replaceFrom"),
    ):
        replace(path, old, new, "RecipeBookSettings." + label)


# ----------------------------------------------------------------------- enums

# Vanilla enums that NeoForge marks extensible so mods can add entries. Without the marker the
# extender refuses with "Tried to extend non-enum class or non-extensible enum", and on Eturlia
# that lands during login, where it reads as "Internal server error" on the client.
EXTENSIBLE_ENUMS = {
    "net/minecraft/world/inventory/RecipeBookType.java": """package net.minecraft.world.inventory;

// Eturlia - mods add their own recipe book categories through NeoForge's enum extender, which
// only touches enums carrying this marker. Vanilla's copy has no marker, so a modded client is
// disconnected during login with "Internal server error".
// Eturlia - NeoForge compares extensible enums during login; without this annotation the client
// reports "Enum is extensible on the client but not on the server" and disconnects.
@net.neoforged.fml.common.asm.enumextension.NetworkedEnum(net.neoforged.fml.common.asm.enumextension.NetworkedEnum.NetworkCheck.CLIENTBOUND)
public enum RecipeBookType implements net.neoforged.fml.common.asm.enumextension.IExtensibleEnum {
    CRAFTING,
    FURNACE,
    BLAST_FURNACE,
    SMOKER;

    // Eturlia - deliberately not @ReservedConstructor: mods add entries through this very
    // constructor (FarmersDelight's FARMERSDELIGHT_COOKING), and reserving it makes the extender
    // reject them with "Invalid, non-existant or disallowed constructor '()V'".
    RecipeBookType() {
    }

    // Eturlia - the extender rewrites this method's body; it must exist or it throws
    // NoSuchElementException while the class loads, which happens during login.
    public static net.neoforged.fml.common.asm.enumextension.ExtensionInfo getExtensionInfo() {
        return net.neoforged.fml.common.asm.enumextension.ExtensionInfo.nonExtended(RecipeBookType.class);
    }
}
""",
}


def install_extensible_enums():
    print("enum plane")
    for relative, body in EXTENSIBLE_ENUMS.items():
        path = SERVER + "/" + relative
        if os.path.exists(path) and "IExtensibleEnum" in read(path):
            print("  already applied: " + relative)
            continue
        write(path, body)
        print("  " + relative)


# ------------------------------------------------------------------ serializers

def install_data_serializers():
    """Modded entity data serializers get a wire id, so modded mobs can spawn."""
    print("serializer plane")
    path = SERVER + "/net/minecraft/server/Main.java"
    replace(
        path,
        """            } catch (RuntimeException | LinkageError eturliaError) {
                LOGGER.error("Eturlia: failed to rebuild the modded block-item / point-of-interest maps",
                        eturliaError);
            }
            // Eturlia end""",
        """            } catch (RuntimeException | LinkageError eturliaError) {
                LOGGER.error("Eturlia: failed to rebuild the modded block-item / point-of-interest maps",
                        eturliaError);
            }
            // Eturlia end
            // Eturlia start - and the same for entity data serializers
            try {
                int eturliaSerializers = eturlia$rebuildDataSerializers();
                if (eturliaSerializers > 0) {
                    LOGGER.info("Eturlia: gave {} modded entity data serializers a wire id", eturliaSerializers);
                }
            } catch (RuntimeException | LinkageError eturliaError) {
                LOGGER.error("Eturlia: failed to register modded entity data serializers; "
                        + "mobs from mods will fail to spawn", eturliaError);
            }
            // Eturlia end""",
        "Main rebuilds entity data serializers",
    )

    replace(
        path,
        "    public static void eturlia$bootFromOptions(",
        """    // Eturlia start - modded entity data serializers
    /**
     * Gives every modded {@code EntityDataSerializer} the wire id it never got.
     *
     * <p>NeoForge keeps these in a registry of its own and patches
     * {@code EntityDataSerializers} to read ids from there. Eturlia's copy of that class is
     * vanilla, so a mod's serializer is unknown to it and every mob using one dies at spawn with
     * "Unregistered serializer ... for 8!".</p>
     *
     * <p>The ids have to agree with the client, which is a stock NeoForge client reading ids from
     * the registry. So the registry is the authority here: if it disagrees with vanilla's table
     * about any serializer both know, the table is rebuilt in registry order; otherwise the
     * missing entries are simply appended, which lands them on the same numbers.</p>
     */
    private static int eturlia$rebuildDataSerializers() {
        final Object raw;
        try {
            raw = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistries")
                    .getField("ENTITY_DATA_SERIALIZERS").get(null);
        } catch (ReflectiveOperationException noNeoForge) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        final net.minecraft.core.Registry<net.minecraft.network.syncher.EntityDataSerializer<?>> registry =
                (net.minecraft.core.Registry<net.minecraft.network.syncher.EntityDataSerializer<?>>) raw;

        boolean agrees = true;
        boolean knowsVanilla = false;
        for (net.minecraft.network.syncher.EntityDataSerializer<?> serializer : registry) {
            int vanillaId = net.minecraft.network.syncher.EntityDataSerializers.getSerializedId(serializer);
            if (vanillaId < 0) {
                continue;
            }
            knowsVanilla = true;
            if (vanillaId != registry.getId(serializer)) {
                agrees = false;
                break;
            }
        }

        if (!agrees && knowsVanilla) {
            LOGGER.warn("Eturlia: NeoForge numbers entity data serializers differently from vanilla; "
                    + "rebuilding the table in registry order so clients agree");
            return eturlia$mirrorDataSerializers(registry);
        }

        int added = 0;
        for (net.minecraft.network.syncher.EntityDataSerializer<?> serializer : registry) {
            if (net.minecraft.network.syncher.EntityDataSerializers.getSerializedId(serializer) < 0) {
                net.minecraft.network.syncher.EntityDataSerializers.registerSerializer(serializer);
                added++;
            }
        }
        return added;
    }

    /** Replaces vanilla's serializer table with the registry's, entry for entry. */
    private static int eturlia$mirrorDataSerializers(
            net.minecraft.core.Registry<net.minecraft.network.syncher.EntityDataSerializer<?>> registry) {
        try {
            java.lang.reflect.Field field = net.minecraft.network.syncher.EntityDataSerializers.class
                    .getDeclaredField("SERIALIZERS");
            field.setAccessible(true);
            Object table = field.get(null);
            java.lang.reflect.Method clear = table.getClass().getMethod("clear");
            clear.invoke(table);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Eturlia: could not clear vanilla's entity data serializer table", e);
            return 0;
        }
        int added = 0;
        for (net.minecraft.network.syncher.EntityDataSerializer<?> serializer : registry) {
            net.minecraft.network.syncher.EntityDataSerializers.registerSerializer(serializer);
            added++;
        }
        return added;
    }
    // Eturlia end - modded entity data serializers

    public static void eturlia$bootFromOptions(""",
        "Main.eturlia$rebuildDataSerializers",
    )


# ----------------------------------------------------------------- folia stubs

def install_tick_count():
    """Folia has no single tick counter; mods ask for one every tick anyway."""
    print("folia stub plane")
    replace(
        SERVER + "/io/papermc/paper/threadedregions/RegionizedServer.java",
        """    public static RegionizedServer getInstance() {""",
        """    // Eturlia - the global region's tick number, for mods that expect MinecraftServer#getTickCount
    public long eturlia$globalTickCount() {
        return this.tickCount;
    }

    public static RegionizedServer getInstance() {""",
        "RegionizedServer exposes its tick count",
    )
    replace(
        SERVER + "/net/minecraft/server/MinecraftServer.java",
        """    public int getTickCount() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }""",
        """    public int getTickCount() {
        // Eturlia start - answer with the global region's tick
        // Folia removed this because every region ticks on its own clock, so there is no single
        // answer. Mods do not know that: a ServerTickEvent handler that calls this throws, the
        // region tick dies with it, and the server shuts down seconds after "Done". The global
        // region ticks once per tick for the whole server, which is the answer they expect.
        if (!"strict".equalsIgnoreCase(System.getProperty("eturlia.compat.folia-stubs", "lenient"))) {
            return (int) io.papermc.paper.threadedregions.RegionizedServer.getInstance().eturlia$globalTickCount();
        }
        // Eturlia end - answer with the global region's tick
        throw new UnsupportedOperationException(); // Folia - region threading
    }""",
        "MinecraftServer.getTickCount answers instead of throwing",
    )


# ------------------------------------------------------------- plugin remapping

def install_plugin_remapping():
    """Let Paper remap Spigot-mapped plugins, which needs mappings it cannot find in our layout."""
    print("plugin remapping plane")
    replace(
        SERVER + "/io/papermc/paper/util/MappingEnvironment.java",
        """    public static @Nullable InputStream mappingsStreamIfPresent() {
        return MappingEnvironment.class.getClassLoader().getResourceAsStream("META-INF/mappings/reobf.tiny");
    }""",
        """    public static @Nullable InputStream mappingsStreamIfPresent() {
        // Eturlia start - find the mappings beside the server as well as inside it
        // Paper ships reobf.tiny in the server jar. Ours is nested inside the standalone launcher
        // jar and extracted at boot, so META-INF/mappings is on no classloader's resource path;
        // the missing file silently switches Paper's plugin remapper off, and every Spigot-mapped
        // plugin then dies on its first NMS call (DecentHolograms: CraftPlayer.getHandle()).
        final java.nio.file.Path external = MappingEnvironment.eturliaMappingsFile();
        if (external != null) {
            try {
                return java.nio.file.Files.newInputStream(external);
            } catch (final java.io.IOException ex) {
                // fall through to the copy on the classpath, if there is one
            }
        }
        // Eturlia end - find the mappings beside the server as well as inside it
        return MappingEnvironment.class.getClassLoader().getResourceAsStream("META-INF/mappings/reobf.tiny");
    }

    // Eturlia start - mojang+yarn -> spigot mappings on disk
    // Off by default. Turning the remapper on gets as far as "Remapping server..." and then the
    // plugin system dies loading io.papermc.paper.pluginremap.InsertManifestAttribute, because
    // its interface net.neoforged.art.api.Transformer is not visible from the layer ModLauncher
    // loads the server into - and a plugin system that cannot start takes every plugin with it.
    // -Deturlia.compat.plugin-remap=true to work on it; -Deturlia.mappings= moves the file.
    public static java.nio.file.@Nullable Path eturliaMappingsFile() {
        if (!Boolean.getBoolean("eturlia.compat.plugin-remap")) {
            return null;
        }
        final java.nio.file.Path path = java.nio.file.Path.of(
            System.getProperty("eturlia.mappings", "eturlia-mappings/reobf.tiny"));
        return java.nio.file.Files.isRegularFile(path) ? path : null;
    }
    // Eturlia end - mojang+yarn -> spigot mappings on disk""",
        "MappingEnvironment reads reobf.tiny from disk",
    )


# ------------------------------------------------------------------------ tags

def install_tag_diagnostics():
    """Name the vanilla tag a plugin could not get, instead of handing back a silent null."""
    print("tag plane")
    replace(
        SERVER + "/org/bukkit/craftbukkit/CraftServer.java",
        """    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> org.bukkit.Tag<T> getTag(String registry, NamespacedKey tag, Class<T> clazz) {""",
        """    private static final java.util.Set<String> ETURLIA_MISSING_TAGS = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Eturlia

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> org.bukkit.Tag<T> getTag(String registry, NamespacedKey tag, Class<T> clazz) {""",
        "CraftServer remembers which tags it could not answer",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/CraftServer.java",
        """            default -> throw new IllegalArgumentException();
        }

        return null;
    }""",
        """            default -> throw new IllegalArgumentException();
        }

        // Eturlia start - a missing tag must not be silent
        // Plugins cache Tag constants in static initialisers (WorldGuard's Materials does), so a
        // null here surfaces much later as ExceptionInInitializerError with the cause dropped by
        // the JVM - unreadable. Name it here, once, while we still know what was asked for.
        if (CraftServer.ETURLIA_MISSING_TAGS.add(registry + "/" + key)) {
            this.getLogger().warning("Eturlia: no " + registry + " tag " + key + " - callers see null");
        }
        // Eturlia end - a missing tag must not be silent
        return null;
    }""",
        "CraftServer.getTag reports the tags it cannot answer",
    )

    # A vanilla tag on a modded server holds modded entries, and those have no Bukkit constant.
    # Collectors.toUnmodifiableSet() rejects the resulting nulls, so every plugin that reads a
    # tag dies - and if it read it from a static initialiser, as WorldGuard's Materials does, the
    # class stays broken for the rest of the run and the JVM throws away the cause.
    TAG = SERVER + "/org/bukkit/craftbukkit/tag"
    replace(
        TAG + "/CraftBlockTag.java",
        """        return this.getHandle().stream().map((block) -> CraftBlockType.minecraftToBukkit(block.value())).collect(Collectors.toUnmodifiableSet());""",
        """        // Eturlia - a modded block has no Material; drop it instead of collecting a null
        return this.getHandle().stream().map((block) -> CraftBlockType.minecraftToBukkit(block.value())).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());""",
        "CraftBlockTag drops modded blocks",
    )
    replace(
        TAG + "/CraftItemTag.java",
        """        return this.getHandle().stream().map((item) -> CraftItemType.minecraftToBukkit(item.value())).collect(Collectors.toUnmodifiableSet());""",
        """        // Eturlia - a modded item maps to AIR, which would claim air is in this tag
        return this.getHandle().stream()
            .filter((item) -> item.value() != net.minecraft.world.item.Items.AIR)
            .map((item) -> CraftItemType.minecraftToBukkit(item.value()))
            .filter((material) -> material != null && material != org.bukkit.Material.AIR)
            .collect(Collectors.toUnmodifiableSet());""",
        "CraftItemTag drops modded items",
    )
    replace(
        TAG + "/CraftEntityTag.java",
        """        return this.getHandle().stream().map(Holder::value).map(CraftEntityType::minecraftToBukkit).collect(Collectors.toUnmodifiableSet());""",
        """        // Eturlia - a modded entity type reads back as UNKNOWN; it is not a member of anything
        return this.getHandle().stream().map(Holder::value).map(CraftEntityType::minecraftToBukkit)
            .filter((type) -> type != null && type != EntityType.UNKNOWN)
            .collect(Collectors.toUnmodifiableSet());""",
        "CraftEntityTag drops modded entity types",
    )
    replace(
        TAG + "/CraftFluidTag.java",
        """        return this.getHandle().stream().map(Holder::value).map(CraftFluid::minecraftToBukkit).collect(Collectors.toUnmodifiableSet());""",
        """        // Eturlia - a modded fluid has no Bukkit constant
        return this.getHandle().stream().map(Holder::value).map(CraftFluid::minecraftToBukkit).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());""",
        "CraftFluidTag drops modded fluids",
    )


# ------------------------------------------------------- NeoForge patch methods

# NeoForge ships its own patched Minecraft; we ship Folia's. Every method NeoForge adds to a
# vanilla class is therefore missing here, and a mod that calls one dies with NoSuchMethodError
# inside whatever tick was running. These put the method back, vanilla-shaped.

LOOT_CONTEXT = '''package net.minecraft.world.level.storage.loot;

import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

public class LootContext {
    private final LootParams params;
    private final RandomSource random;
    private final HolderGetter.Provider lootDataResolver;
    private final Set<LootContext.VisitedEntry<?>> visitedElements = Sets.newLinkedHashSet();
    // Eturlia start - NeoForge exposes the table currently being rolled; mods branch on it
    public static final ResourceLocation ETURLIA_NO_TABLE = ResourceLocation.withDefaultNamespace("empty");
    @Nullable
    private ResourceLocation queriedLootTableId;
    // Eturlia end

    LootContext(LootParams parameters, RandomSource random, HolderGetter.Provider lookup) {
        this.params = parameters;
        this.random = random;
        this.lootDataResolver = lookup;
    }

    // Eturlia start - NeoForge patch: LootContext#getQueriedLootTableId
    // Twilight Forest asks for it from a @Inject on LootTable#getRandomItems, so it has to be set
    // by the time the roll finishes, and it must never be null: callers dereference it directly.
    public ResourceLocation getQueriedLootTableId() {
        return this.queriedLootTableId == null ? LootContext.ETURLIA_NO_TABLE : this.queriedLootTableId;
    }

    public void setQueriedLootTableId(@Nullable ResourceLocation id) {
        if (id != null) {
            this.queriedLootTableId = id;
        }
    }
    // Eturlia end

    public boolean hasParam(LootContextParam<?> parameter) {
        return this.params.hasParam(parameter);
    }

    public <T> T getParam(LootContextParam<T> parameter) {
        return this.params.getParameter(parameter);
    }

    public void addDynamicDrops(ResourceLocation id, Consumer<ItemStack> lootConsumer) {
        this.params.addDynamicDrops(id, lootConsumer);
    }

    @Nullable
    public <T> T getParamOrNull(LootContextParam<T> parameter) {
        return this.params.getParamOrNull(parameter);
    }

    public boolean hasVisitedElement(LootContext.VisitedEntry<?> entry) {
        return this.visitedElements.contains(entry);
    }

    public boolean pushVisitedElement(LootContext.VisitedEntry<?> entry) {
        return this.visitedElements.add(entry);
    }

    public void popVisitedElement(LootContext.VisitedEntry<?> entry) {
        this.visitedElements.remove(entry);
    }

    public HolderGetter.Provider getResolver() {
        return this.lootDataResolver;
    }

    public RandomSource getRandom() {
        return this.random;
    }

    public float getLuck() {
        return this.params.getLuck();
    }

    public ServerLevel getLevel() {
        return this.params.getLevel();
    }

    public static LootContext.VisitedEntry<LootTable> createVisitedEntry(LootTable table) {
        return new LootContext.VisitedEntry<>(LootDataType.TABLE, table);
    }

    public static LootContext.VisitedEntry<LootItemCondition> createVisitedEntry(LootItemCondition predicate) {
        return new LootContext.VisitedEntry<>(LootDataType.PREDICATE, predicate);
    }

    public static LootContext.VisitedEntry<LootItemFunction> createVisitedEntry(LootItemFunction itemModifier) {
        return new LootContext.VisitedEntry<>(LootDataType.MODIFIER, itemModifier);
    }

    public static class Builder {
        private final LootParams params;
        @Nullable
        private RandomSource random;

        public Builder(LootParams parameters) {
            this.params = parameters;
        }

        public LootContext.Builder withOptionalRandomSeed(long seed) {
            if (seed != 0L) {
                this.random = RandomSource.create(seed);
            }

            return this;
        }

        public LootContext.Builder withOptionalRandomSource(RandomSource random) {
            this.random = random;
            return this;
        }

        public ServerLevel getLevel() {
            return this.params.getLevel();
        }

        public LootContext create(Optional<ResourceLocation> randomId) {
            ServerLevel serverLevel = this.getLevel();
            MinecraftServer minecraftServer = serverLevel.getServer();
            RandomSource randomSource = Optional.ofNullable(this.random)
                .or(() -> randomId.map(serverLevel::getRandomSequence))
                .orElseGet(serverLevel::getRandom);
            LootContext context = new LootContext(this.params, randomSource, minecraftServer.reloadableRegistries().lookup());
            context.setQueriedLootTableId(randomId.orElse(null)); // Eturlia - the sequence id is the table id for every table that names one
            return context;
        }
    }

    public static enum EntityTarget implements StringRepresentable {
        THIS("this", LootContextParams.THIS_ENTITY),
        ATTACKER("attacker", LootContextParams.ATTACKING_ENTITY),
        DIRECT_ATTACKER("direct_attacker", LootContextParams.DIRECT_ATTACKING_ENTITY),
        ATTACKING_PLAYER("attacking_player", LootContextParams.LAST_DAMAGE_PLAYER);

        public static final StringRepresentable.EnumCodec<LootContext.EntityTarget> CODEC = StringRepresentable.fromEnum(LootContext.EntityTarget::values);
        private final String name;
        private final LootContextParam<? extends Entity> param;

        private EntityTarget(final String type, final LootContextParam<? extends Entity> parameter) {
            this.name = type;
            this.param = parameter;
        }

        public LootContextParam<? extends Entity> getParam() {
            return this.param;
        }

        public static LootContext.EntityTarget getByName(String type) {
            LootContext.EntityTarget entityTarget = CODEC.byName(type);
            if (entityTarget != null) {
                return entityTarget;
            } else {
                throw new IllegalArgumentException("Invalid entity target " + type);
            }
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public static record VisitedEntry<T>(LootDataType<T> type, T value) {
    }
}
'''


def install_custom_ingredients():
    """Recipes written with a NeoForge ingredient type load instead of being dropped."""
    print("custom ingredient plane")
    path = SERVER + "/net/minecraft/world/item/crafting/Ingredient.java"

    # CraftingHelper reads the values array straight off the ingredient, and builds new ones
    # through fromValues; both have to be reachable from outside this class.
    replace(
        path,
        """    private static Ingredient fromValues(Stream<? extends Ingredient.Value> entries) {""",
        """    // Eturlia - NeoForge's CraftingHelper reads the values back off the ingredient
    public Ingredient.Value[] getValues() {
        return this.values;
    }

    public static Ingredient fromValues(Stream<? extends Ingredient.Value> entries) {""",
        "Ingredient exposes getValues/fromValues",
    )

    replace(
        path,
        """    private static Codec<Ingredient> codec(boolean allowEmpty) {
        Codec<Ingredient.Value[]> codec = Codec.list(Ingredient.Value.CODEC).comapFlatMap((list) -> {
            return !allowEmpty && list.size() < 1 ? DataResult.error(() -> {
                return "Item array cannot be empty, at least one item must be defined";
            }) : DataResult.success((Ingredient.Value[]) list.toArray(new Ingredient.Value[0]));
        }, List::of);

        return Codec.either(codec, Ingredient.Value.CODEC).flatComapMap((either) -> {
            return (Ingredient) either.map(Ingredient::new, (recipeitemstack_provider) -> {
                return new Ingredient(new Ingredient.Value[]{recipeitemstack_provider});
            });
        }, (recipeitemstack) -> {
            return recipeitemstack.values.length == 1 ? DataResult.success(Either.right(recipeitemstack.values[0])) : (recipeitemstack.values.length == 0 && !allowEmpty ? DataResult.error(() -> {
                return "Item array cannot be empty, at least one item must be defined";
            }) : DataResult.success(Either.left(recipeitemstack.values)));
        });
    }""",
        """    private static Codec<Ingredient> codec(boolean allowEmpty) {
        // Eturlia start - hand the dispatch to NeoForge
        // A modded recipe writes {"type":"neoforge:difference", ...} where vanilla expects an item
        // or a tag, and the vanilla codec answers "Not a json array". 153 recipes and a dozen
        // advancements were dropped at every boot for that one reason - chest boats, hoppers,
        // shulker boxes, trapped chests, minecarts, most of Twilight Forest's equipment.
        // CraftingHelper builds the codec that also accepts every registered IngredientType, and
        // it needs exactly what this class now exposes: Value.MAP_CODEC, LIST_CODEC,
        // LIST_CODEC_NONEMPTY, fromValues, getValues, getCustomIngredient, isCustom. It wraps
        // itself in Codec.lazyInitialized, so reading LIST_CODEC from inside this class's own
        // <clinit> is safe even though LIST_CODEC is declared further down.
        return net.neoforged.neoforge.common.crafting.CraftingHelper.makeIngredientCodec(allowEmpty);
        // Eturlia end - hand the dispatch to NeoForge
    }""",
        "Ingredient.codec accepts NeoForge ingredient types",
    )

    replace(
        path,
        """    public static final com.mojang.serialization.MapCodec<Ingredient> MAP_CODEC_NONEMPTY =
        Ingredient.Value.MAP_CODEC.flatXmap(
            value -> DataResult.success(new Ingredient(new Ingredient.Value[]{value})),
            ingredient -> ingredient.values.length == 1
                ? DataResult.success(ingredient.values[0])
                : DataResult.error(() -> "An inline ingredient must have exactly one entry, found "
                        + ingredient.values.length));""",
        """    public static final com.mojang.serialization.MapCodec<Ingredient> MAP_CODEC_NONEMPTY =
        net.neoforged.neoforge.common.crafting.CraftingHelper.makeIngredientMapCodec(); // Eturlia - custom types inline too""",
        "Ingredient.MAP_CODEC_NONEMPTY accepts NeoForge ingredient types",
    )

    # Once custom ingredients actually load, they reach the network codec - which refuses empty
    # stacks and takes the whole update_recipes packet, and the join, with it.
    replace(
        path,
        """            this.itemStacks = stream.distinct().toArray((i) -> new ItemStack[i]);""",
        """            // Eturlia - an empty stack here is fatal on the wire: ItemStack.LIST_STREAM_CODEC
            // answers "Empty ItemStack not allowed" and the client is dropped mid-join with
            // "Failed to encode packet clientbound/minecraft:update_recipes". A modded ingredient
            // that resolves to nothing (an empty tag, a difference that cancels out) is normal.
            this.itemStacks = stream.filter((itemstack) -> itemstack != null && !itemstack.isEmpty()).distinct().toArray((i) -> new ItemStack[i]);""",
        "Ingredient never carries an empty stack onto the wire",
    )

    # The stale note that said this could not be done yet.
    text = read(path)
    stale = """    // TODO(Eturlia): CraftingHelper.makeIngredientCodec needs more NeoForge-only members on
    // this class (Value.MAP_CODEC, getValues(), ...). Vanilla dispatch until that set is complete;
    // recipes using neoforge:difference and friends stay unavailable meanwhile.
"""
    if stale in text:
        write(path, text.replace(stale, "", 1))
        print("  dropped the stale TODO")


# ------------------------------------------------------------- bukkit materials

def install_material_maps():
    """A modded item stops reporting a null Material to plugins."""
    print("material map plane")
    replace(
        SERVER + "/org/bukkit/craftbukkit/util/CraftMagicNumbers.java",
        """    static {
        for (Block block : BuiltInRegistries.BLOCK) {
            BLOCK_MATERIAL.put(block, Material.getMaterial(BuiltInRegistries.BLOCK.getKey(block).getPath().toUpperCase(Locale.ROOT)));
        }

        for (Item item : BuiltInRegistries.ITEM) {
            ITEM_MATERIAL.put(item, Material.getMaterial(BuiltInRegistries.ITEM.getKey(item).getPath().toUpperCase(Locale.ROOT)));
        }""",
        """    static {
        // Eturlia start - never record a null Material
        // Material is an enum, so a modded block or item has none, and Material.getMaterial()
        // answers null for it. Storing that null makes ITEM_MATERIAL.getOrDefault(item, AIR)
        // return the stored null rather than AIR - getOrDefault only substitutes for a missing
        // key - so CraftItemStack.getType() hands plugins a null Material and the first
        // getType().name() they call throws inside whatever thread they were on.
        for (Block block : BuiltInRegistries.BLOCK) {
            Material material = Material.getMaterial(BuiltInRegistries.BLOCK.getKey(block).getPath().toUpperCase(Locale.ROOT));
            if (material != null) {
                BLOCK_MATERIAL.put(block, material);
            }
        }

        for (Item item : BuiltInRegistries.ITEM) {
            Material material = Material.getMaterial(BuiltInRegistries.ITEM.getKey(item).getPath().toUpperCase(Locale.ROOT));
            if (material != null) {
                ITEM_MATERIAL.put(item, material);
            }
        }
        // Eturlia end - never record a null Material""",
        "CraftMagicNumbers keeps modded items out of the material maps",
    )


def install_neoforge_patches():
    print("neoforge patch-method plane")
    path = SERVER + "/net/minecraft/world/level/storage/loot/LootContext.java"
    if os.path.exists(path) and "getQueriedLootTableId" in read(path):
        print("  already applied: LootContext.getQueriedLootTableId")
    else:
        write(path, LOOT_CONTEXT)
        print("  LootContext.getQueriedLootTableId")

    # The context is built from LootParams in plenty of places that never see the table, so name
    # the table on the way in as well - CraftBukkit already keeps its key on every loaded table.
    replace(
        SERVER + "/net/minecraft/world/level/storage/loot/LootTable.java",
        """    public void getRandomItemsRaw(LootContext context, Consumer<ItemStack> lootConsumer) {
        LootContext.VisitedEntry<?> loottableinfo_c = LootContext.createVisitedEntry(this);""",
        """    // Eturlia - tell the context which table it is rolling, for NeoForge's getQueriedLootTableId
    public void eturlia$markQueried(LootContext context) {
        if (this.randomSequence.isPresent()) {
            context.setQueriedLootTableId(this.randomSequence.get());
        } else if (this.craftLootTable != null && this.craftLootTable.getKey() != null) {
            context.setQueriedLootTableId(org.bukkit.craftbukkit.util.CraftNamespacedKey.toMinecraft(this.craftLootTable.getKey()));
        }
    }

    public void getRandomItemsRaw(LootContext context, Consumer<ItemStack> lootConsumer) {
        this.eturlia$markQueried(context); // Eturlia
        LootContext.VisitedEntry<?> loottableinfo_c = LootContext.createVisitedEntry(this);""",
        "LootTable names itself on the context",
    )
    replace(
        SERVER + "/net/minecraft/world/level/storage/loot/LootTable.java",
        """    public void getRandomItems(LootContext context, Consumer<ItemStack> lootConsumer) {
        this.getRandomItemsRaw(context,""",
        """    public void getRandomItems(LootContext context, Consumer<ItemStack> lootConsumer) {
        this.eturlia$markQueried(context); // Eturlia - mods inject here and read the id at once
        this.getRandomItemsRaw(context,""",
        "LootTable names itself before the public roll",
    )


EXCEPTION_COLLECTOR = '''package net.minecraft.util;

import javax.annotation.Nullable;

public class ExceptionCollector<T extends Throwable> {
    @Nullable
    private T result;

    public void add(T throwable) {
        if (this.result == null) {
            this.result = throwable;
        } else if (this.result != throwable) {
            // Eturlia - the same throwable can arrive twice
            // Paper's plugin remapper reports one failure from several of its parallel tasks, and
            // Throwable.addSuppressed(this) answers "Self-suppression not permitted". That
            // IllegalArgumentException then replaced the real failure and aborted the whole plugin
            // directory scan - the server came up with zero plugins and no usable diagnosis.
            this.result.addSuppressed(throwable);
        }
    }

    public void throwIfPresent() throws T {
        if (this.result != null) {
            throw this.result;
        }
    }
}
'''


def install_reobf_server_jar():
    """The remapper needs a real file for the server jar, not a union:// code source."""
    print("reobf server jar plane")
    replace(
        SERVER + "/io/papermc/paper/pluginremap/ReobfServer.java",
        """    private static Path serverJar() {
        try {
            return Path.of(ReobfServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
    }""",
        """    private static Path serverJar() {
        // Eturlia start - the code source is a union:// path under ModLauncher
        // securejarhandler hands out paths on its own filesystem, and AutoRenamingTool wants a
        // java.io.File - Path.toFile() then answers "Path not associated with default file
        // system", which surfaced as "Failed to remap server jar" and took the whole plugin
        // directory scan with it. The launcher already knows where the real jar is.
        final String configured = System.getProperty("eturlia.serverJar");
        if (configured != null && !configured.isBlank()) {
            final Path candidate = Path.of(configured);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        // Eturlia end - the code source is a union:// path under ModLauncher
        try {
            return Path.of(ReobfServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
    }""",
        "ReobfServer uses the real server jar path",
    )


def install_exception_collector():
    """Collecting the same failure twice stops replacing it with a self-suppression error."""
    print("exception collector plane")
    path = SERVER + "/net/minecraft/util/ExceptionCollector.java"
    if os.path.exists(path) and "the same throwable can arrive twice" in read(path):
        print("  already applied: ExceptionCollector tolerates a repeated throwable")
        return
    write(path, EXCEPTION_COLLECTOR)
    print("  ExceptionCollector tolerates a repeated throwable")


def install_legacy_item_key():
    """A recipe result written the 1.20 way (`item`) still reads on 1.21 (`id`)."""
    print("legacy item key plane")
    path = SERVER + "/net/minecraft/world/item/ItemStack.java"

    replace(
        path,
        """    public static final Codec<ItemStack> CODEC = Codec.lazyInitialized(() -> {""",
        """    // Eturlia start - accept the 1.20 spelling of the item field
    // 1.21 renamed the field from "item" to "id". Mods that never updated their generated JSON
    // fail with "No key id in MapLike[{\\"item\\":\\"...\\",\\"count\\":1}]" and the whole recipe is
    // dropped. Reading either spelling costs nothing and writes back the current one.
    private static com.mojang.serialization.MapCodec<Holder<Item>> eturlia$itemField() {
        return Codec.mapEither(
                ItemStack.ITEM_NON_AIR_CODEC.fieldOf("id"),
                ItemStack.ITEM_NON_AIR_CODEC.fieldOf("item")
        ).xmap(
                (either) -> either.map(java.util.function.Function.identity(), java.util.function.Function.identity()),
                com.mojang.datafixers.util.Either::left
        );
    }
    // Eturlia end - accept the 1.20 spelling of the item field

    public static final Codec<ItemStack> CODEC = Codec.lazyInitialized(() -> {""",
        "ItemStack carries the legacy-key reader",
    )

    replace(
        path,
        """            return instance.group(ItemStack.ITEM_NON_AIR_CODEC.fieldOf("id").forGetter(ItemStack::getItemHolder), ExtraCodecs.intRange(1, 99).fieldOf("count").orElse(1).forGetter(ItemStack::getCount)""",
        """            return instance.group(ItemStack.eturlia$itemField().forGetter(ItemStack::getItemHolder), ExtraCodecs.intRange(1, 99).fieldOf("count").orElse(1).forGetter(ItemStack::getCount)""",
        "ItemStack.CODEC reads id or item",
    )

    replace(
        path,
        """            return instance.group(ItemStack.ITEM_NON_AIR_CODEC.fieldOf("id").forGetter(ItemStack::getItemHolder), DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter((itemstack) -> {""",
        """            return instance.group(ItemStack.eturlia$itemField().forGetter(ItemStack::getItemHolder), DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter((itemstack) -> {""",
        "ItemStack.SINGLE_ITEM_CODEC reads id or item",
    )


def install_library_downloader():
    """A plugin that declares `libraries:` gets them even though Aether cannot start here."""
    print("plugin library plane")
    path = API + "/org/bukkit/plugin/java/LibraryLoader.java"

    replace(
        path,
        """        // Eturlia: Maven resolver missing — allow paperLibraryPaths-only; fail clearly for libraries=
        if (this.repository == null || this.session == null) {
            if (paperLibraryPaths != null && desc.getLibraries().isEmpty()) {
                // fall through to Paper path using only paperLibraryPaths
            } else if (!desc.getLibraries().isEmpty()) {
                throw new RuntimeException("[Eturlia] Cannot resolve libraries for plugin '"
                        + desc.getName() + "': Maven RepositorySystem unavailable under ModLauncher/FML");
            } else {
                return null;
            }
        }""",
        """        // Eturlia start - resolve the coordinates ourselves when Aether could not start
        // Maven's ServiceLocator does not come up under ModLauncher, so `repository` is null and
        // every plugin declaring `libraries:` used to be refused outright. The coordinates in a
        // plugin.yml are ordinary Maven ones, and Maven Central serves them over plain HTTP at a
        // path derived from the coordinate - which is all this needs. No transitive resolution:
        // a library that needs its own dependencies has to list them, and the plugin will say so
        // with a NoClassDefFoundError naming exactly what is missing.
        java.util.List<java.nio.file.Path> eturliaDirectJars = java.util.Collections.emptyList();
        if (this.repository == null || this.session == null) {
            if (!desc.getLibraries().isEmpty()) {
                eturliaDirectJars = this.eturlia$fetchLibraries(desc.getLibraries(), desc.getName());
                if (eturliaDirectJars.isEmpty()) {
                    throw new RuntimeException("[Eturlia] Cannot resolve libraries for plugin '"
                            + desc.getName() + "': none of " + desc.getLibraries() + " could be fetched");
                }
            } else if (paperLibraryPaths == null) {
                return null;
            }
        }
        // Eturlia end - resolve the coordinates ourselves when Aether could not start""",
        "LibraryLoader fetches declared libraries directly",
    )

    replace(
        path,
        """        DependencyResult result;
        if (!dependencies.isEmpty()) try // Paper - plugin loader api""",
        """        DependencyResult result;
        if (this.repository == null || this.session == null) {
            result = null; // Eturlia - already fetched above
        } else if (!dependencies.isEmpty()) try // Paper - plugin loader api""",
        "LibraryLoader skips Aether when it is not there",
    )

    replace(
        path,
        """        if (paperLibraryPaths != null) jarPaths.addAll(paperLibraryPaths);""",
        """        if (paperLibraryPaths != null) jarPaths.addAll(paperLibraryPaths);
        jarPaths.addAll(eturliaDirectJars); // Eturlia - whatever we fetched by hand""",
        "LibraryLoader hands the fetched jars to the class loader",
    )

    replace(
        path,
        """    @Nullable
    public ClassLoader createLoader(@NotNull PluginDescriptionFile desc)
    {""",
        """    // Eturlia start - a minimal Maven Central fetcher
    /** Where a coordinate lands on disk, mirroring Maven's own layout so a second boot is free. */
    private static java.nio.file.Path eturlia$cachePath(String group, String artifactId, String version, String fileName) {
        return java.nio.file.Path.of("libraries")
                .resolve(group.replace('.', java.io.File.separatorChar))
                .resolve(artifactId)
                .resolve(version)
                .resolve(fileName);
    }

    /**
     * Downloads each {@code groupId:artifactId:version} coordinate from Maven Central.
     *
     * <p>Longer Maven forms ({@code g:a:packaging:v}, {@code g:a:packaging:classifier:v}) are read
     * as first / second / last and fetched as a jar; a classifier is honoured when present. What
     * is not done is transitive resolution - Aether's job - so a coordinate whose own dependencies
     * are missing will surface later as a NoClassDefFoundError naming the missing class.</p>
     */
    private java.util.List<java.nio.file.Path> eturlia$fetchLibraries(java.util.List<String> coordinates, String pluginName) {
        String base = System.getProperty("eturlia.maven-central", "https://repo.maven.apache.org/maven2");
        java.util.List<java.nio.file.Path> resolved = new ArrayList<>();
        for (String coordinate : coordinates) {
            String[] parts = coordinate.split(":");
            if (parts.length < 3) {
                logger.log(Level.SEVERE, "[Eturlia] " + pluginName + ": unreadable library coordinate '" + coordinate + "'");
                continue;
            }
            String group = parts[0];
            String artifactId = parts[1];
            String version = parts[parts.length - 1];
            String classifier = parts.length >= 5 ? "-" + parts[3] : "";
            String fileName = artifactId + "-" + version + classifier + ".jar";
            java.nio.file.Path target = LibraryLoader.eturlia$cachePath(group, artifactId, version, fileName);
            if (java.nio.file.Files.isRegularFile(target)) {
                resolved.add(target);
                continue;
            }
            String url = base + "/" + group.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName;
            try {
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Path temp = java.nio.file.Files.createTempFile(target.getParent(), fileName, ".part");
                java.net.URLConnection connection = java.net.URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                try (java.io.InputStream in = connection.getInputStream()) {
                    java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logger.log(Level.INFO, "[Eturlia] " + pluginName + ": fetched " + coordinate);
                resolved.add(target);
            } catch (Throwable failure) {
                logger.log(Level.SEVERE, "[Eturlia] " + pluginName + ": could not fetch " + coordinate
                        + " from " + url + " (" + failure + ")");
            }
        }
        return resolved;
    }
    // Eturlia end - a minimal Maven Central fetcher

    @Nullable
    public ClassLoader createLoader(@NotNull PluginDescriptionFile desc)
    {""",
        "LibraryLoader carries the fetcher",
    )


def install_regionless_save():
    """A thread that owns no region must not fail the event it is running."""
    print("regionless save plane")
    replace(
        SERVER + "/ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java",
        """        final int regionShift = this.world.moonrise$getRegionChunkShift();
        for (final LongIterator iterator = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion().getOwnedSectionsUnsynchronised(); iterator.hasNext();) {""",
        """        final int regionShift = this.world.moonrise$getRegionChunkShift();
        // Eturlia start - a thread that owns no region has no sections to name
        // NeoForge fires ServerStartedEvent on the "Server thread", which is a tick thread that
        // owns no region, and a mod handler that touches the world lands here. The unguarded call
        // NPE'd, FML reported the whole event as failed, and every mod waiting on it stayed
        // half-initialised - Easy NPC printed "Server not initialized" once an hour for exactly
        // that reason. There is nothing to save at that moment either: no region, no dirty chunks.
        final io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion<io.papermc.paper.threadedregions.TickRegions.TickRegionData, io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData> eturlia$region =
            io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion();
        if (eturlia$region == null) {
            return;
        }
        // Eturlia end - a thread that owns no region has no sections to name
        for (final LongIterator iterator = eturlia$region.getOwnedSectionsUnsynchronised(); iterator.hasNext();) {""",
        "ChunkHolderManager tolerates a thread with no owning region",
        marker="eturlia$region",
    )


def install_main_thread_dispatch():
    """Folia has no main thread; mods keep asking it to run things anyway."""
    print("main-thread dispatch plane")
    path = SERVER + "/net/minecraft/server/MinecraftServer.java"

    replace(
        path,
        """    @Override
    public void executeBlocking(Runnable runnable) {
        if (true) {
            throw new UnsupportedOperationException();
        }
        super.executeBlocking(runnable);
    }

    @Override
    public void tell(TickTask runnable) {
        if (true) {
            throw new UnsupportedOperationException();
        }
        super.tell(runnable);
    }""",
        """    // Eturlia start - a main thread for code that still believes in one
    // Folia throws from every "run this on the main thread" entry point, because there is no
    // main thread and no single queue behind it. Mods do not know that. Supplementaries builds a
    // chunk packet and tells the server to send the block entity capabilities alongside it, the
    // UnsupportedOperationException takes the region tick with it, and Folia answers a failed
    // region tick by shutting the whole server down - a second after the player joins.
    //
    // Where the task should run depends on the thread that handed it over:
    //  - inside a region tick we already own the region holding the data the task is about to
    //    touch, so running it inline is the only choice that survives ensureTickThread(). It is
    //    also what the caller meant by "main thread": the thread allowed to touch the world.
    //  - anywhere else (netty, plugin pools, the mod loader, and the bootstrap "Server thread",
    //    which is a tick thread that owns no region) it goes on the global region's queue, where
    //    Folia itself puts configuration-phase packets. Running those inline would hand NeoForge's
    //    startup events a null TickRegionScheduler.getCurrentRegion().
    public static void eturlia$runAsMainThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (MinecraftServer.eturlia$inRegionTick()) {
            MinecraftServer.eturlia$runGuarded(runnable);
            return;
        }
        io.papermc.paper.threadedregions.RegionizedServer regionized =
            io.papermc.paper.threadedregions.RegionizedServer.getInstance();
        if (regionized != null) {
            regionized.addTask(() -> MinecraftServer.eturlia$runGuarded(runnable));
            return;
        }
        MinecraftServer.eturlia$runGuarded(runnable);
    }

    // A deferred task that throws must not kill the region running it: on Folia that is a
    // whole-server shutdown, and the mod that queued the task is long gone from the stack.
    public static void eturlia$runGuarded(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable thr) {
            MinecraftServer.LOGGER.warn("Eturlia: deferred main-thread task failed", thr);
        }
    }

    public static boolean eturlia$strictFoliaStubs() {
        return "strict".equalsIgnoreCase(System.getProperty("eturlia.compat.folia-stubs", "lenient"));
    }

    // True only while a region is actually being ticked on this thread - which is what makes it
    // safe to touch that region's world data right now.
    public static boolean eturlia$inRegionTick() {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()
            && io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() != null;
    }

    @Override
    public void executeBlocking(Runnable runnable) {
        if (!MinecraftServer.eturlia$strictFoliaStubs()) {
            // Blocking a tick thread on another tick is always wrong - a region cannot wait on
            // the global region, and the global region cannot wait on itself. Run it here.
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
                MinecraftServer.eturlia$runGuarded(runnable);
                return;
            }
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            MinecraftServer.eturlia$runAsMainThread(() -> {
                try {
                    runnable.run();
                } finally {
                    done.countDown();
                }
            });
            try {
                done.await(30L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (true) {
            throw new UnsupportedOperationException();
        }
        super.executeBlocking(runnable);
    }

    @Override
    public void tell(TickTask runnable) {
        if (!MinecraftServer.eturlia$strictFoliaStubs()) {
            MinecraftServer.eturlia$runAsMainThread(runnable);
            return;
        }
        if (true) {
            throw new UnsupportedOperationException();
        }
        super.tell(runnable);
    }
    // Eturlia end - a main thread for code that still believes in one""",
        "MinecraftServer.tell/executeBlocking dispatch instead of throwing",
        marker="eturlia$runAsMainThread",
    )

    # execute() was already routed to the global region by patch 0096; send it through the same
    # helper so a caller already on a tick thread keeps the region it owns.
    replace(
        path,
        """        if (io.papermc.paper.threadedregions.RegionizedServer.getInstance() != null) {
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(runnable);
            return;
        }
        super.execute(runnable);""",
        """        if (!MinecraftServer.eturlia$strictFoliaStubs()) {
            MinecraftServer.eturlia$runAsMainThread(runnable);
            return;
        }
        super.execute(runnable);""",
        "MinecraftServer.execute shares the dispatch helper",
    )


# ---------------------------------------------------------------------- shapes

def install_shape_compat():
    """A mod's VoxelShape subclass is allowed to finish constructing before its cache is built."""
    print("shape plane")
    path = SERVER + "/net/minecraft/world/phys/shapes/VoxelShape.java"

    replace(
        path,
        """    @Override
    public final void moonrise$initCache() {
        this.cachedShapeData""",
        """    // Eturlia start - a mod's subclass is not finished constructing yet
    /** Whether {@link #moonrise$initCache()} has completed for this shape. */
    private boolean eturlia$cacheReady;

    /**
     * Builds the collision cache, tolerating a subclass that is still under construction.
     *
     * <p>Paper builds this cache in the constructors of {@code SliceShape},
     * {@code ArrayVoxelShape} and {@code CubeVoxelShape}, and it does so by calling
     * {@link #getCoords}, which subclasses override. When the subclass belongs to a mod — Copycats'
     * {@code OutlinedVoxelShape} wraps another shape — its own fields are still null at that point,
     * because Java runs the superclass constructor first. Vanilla never noticed: it builds no
     * cache there.</p>
     *
     * <p>So the failure is swallowed and the cache is left for the first real use, by which time
     * the subclass is fully built. Vanilla shapes are unaffected: their {@code getCoords} works
     * during construction and the cache is ready exactly when it always was.</p>
     */
    @Override
    public final void moonrise$initCache() {
        try {
            this.eturlia$computeCache();
            this.eturlia$cacheReady = true;
        } catch (final Throwable constructingSubclass) {
            this.eturlia$cacheReady = false;
        }
    }

    /** Builds the cache now if the constructor could not. */
    private void eturlia$ensureCache() {
        // The build itself calls back into this class, so it must not re-enter.
        if (this.eturlia$cacheReady || this.eturlia$building) {
            return;
        }
        this.eturlia$building = true;
        try {
            this.moonrise$initCache();
        } finally {
            this.eturlia$building = false;
        }
    }

    private boolean eturlia$building;

    private void eturlia$computeCache() {
    // Eturlia end - a mod's subclass is not finished constructing yet
        this.cachedShapeData""",
        "VoxelShape cache tolerates a half-built subclass",
    )

    # The cache fields are read all over this class, not only through the accessors, so every way
    # in has to be able to trigger the deferred build. One boolean test per call.
    guard = "        this.eturlia$ensureCache(); // Eturlia - deferred for mod subclasses"
    skip = ("moonrise$initCache", "eturlia$", "static ", "abstract ")
    lines = read(path).split("\n")
    out = []
    added = 0
    for index, line in enumerate(lines):
        out.append(line)
        if not line.startswith("    public ") and not line.startswith("    protected "):
            continue
        if not line.rstrip().endswith(") {") or any(token in line for token in skip):
            continue
        if index + 1 < len(lines) and "eturlia$ensureCache" in lines[index + 1]:
            continue
        out.append(guard)
        added += 1
    if added:
        write(path, "\n".join(out))
    print("  %d entry points build the cache on demand" % added)


# ------------------------------------------------------------------ registries

def install_registry_compat():
    """A frozen registry thaws instead of throwing when a mod registers late."""
    print("registry plane")
    replace(
        SERVER + "/net/minecraft/core/MappedRegistry.java",
        """    private void validateWrite() {
        if (this.frozen) {
            throw new IllegalStateException("Registry is already frozen");
        }
    }

    public void validateWrite(ResourceKey<T> key) {
        if (this.frozen) {
            throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
        }
    }""",
        """    private void validateWrite() {
        if (this.frozen && !this.eturlia$thaw(null)) { // Eturlia - late mod registration
            throw new IllegalStateException("Registry is already frozen");
        }
    }

    public void validateWrite(ResourceKey<T> key) {
        if (this.frozen && !this.eturlia$thaw(key)) { // Eturlia - late mod registration
            throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
        }
    }

    // Eturlia start - late mod registration
    /** Whether a registry reopens itself instead of refusing a late write. */
    private static final boolean ETURLIA_LENIENT =
        !"strict".equalsIgnoreCase(System.getProperty("eturlia.compat.registries", "lenient"));

    private boolean eturlia$reopened;

    /**
     * Reopens a frozen registry for one more write.
     *
     * <p>Vanilla freezes its registries once the game is bootstrapped, and NeoForge reopens them
     * around its own registration events. A mod ported from Fabric often registers outside those
     * events — during class initialisation, or while its own config loads — and on a stock server
     * that is an immediate crash naming a key nobody recognises.</p>
     *
     * <p>Eturlia lets the write through. The derived maps that depend on registry contents (block
     * state ids, {@code Item.BY_BLOCK}, POI states) are rebuilt after mod loading anyway, so a
     * late entry is not left half-registered. {@code -Deturlia.compat.registries=strict} restores
     * vanilla's refusal.</p>
     */
    private boolean eturlia$thaw(@Nullable ResourceKey<T> key) {
        if (!ETURLIA_LENIENT) {
            return false;
        }
        this.frozen = false;
        if (!this.eturlia$reopened) {
            this.eturlia$reopened = true;
            LOGGER.warn("Registry {} was reopened for a late registration ({}); "
                    + "a mod registered outside NeoForge's registration events",
                    this.key, key == null ? "no key" : key.location());
        }
        return true;
    }
    // Eturlia end - late mod registration""",
        "MappedRegistry reopens instead of refusing",
    )

    replace(
        SERVER + "/net/minecraft/core/MappedRegistry.java",
        """                if (this.unregisteredIntrusiveHolders != null) {
                    if (!this.unregisteredIntrusiveHolders.isEmpty()) {
                        throw new IllegalStateException("Some intrusive holders were not registered: " + this.unregisteredIntrusiveHolders.values());
                    }""",
        """                if (this.unregisteredIntrusiveHolders != null) {
                    if (!this.unregisteredIntrusiveHolders.isEmpty()) {
                        // Eturlia start - a mod left a holder behind
                        // An intrusive holder is created when a mod builds a Block or Item; it is
                        // consumed when that object is registered. One left over means the mod
                        // built something it never registered — usually a config-gated feature it
                        // decided to skip. Vanilla treats that as fatal. Here it is dropped, since
                        // nothing can ever look it up.
                        if (ETURLIA_LENIENT) {
                            LOGGER.warn("Registry {} had {} intrusive holder(s) a mod never registered; dropping them",
                                    this.key, this.unregisteredIntrusiveHolders.size());
                            this.unregisteredIntrusiveHolders.clear();
                        } else
                        // Eturlia end - a mod left a holder behind
                        throw new IllegalStateException("Some intrusive holders were not registered: " + this.unregisteredIntrusiveHolders.values());
                    }""",
        "MappedRegistry drops orphaned intrusive holders",
    )


# ------------------------------------------------------------------ extensions

def install_item_stack_extension():
    """ItemStack gains every NeoForge stack hook at once instead of one bridge per crash."""
    print("stack extension plane")
    replace(
        SERVER + "/net/minecraft/world/item/ItemStack.java",
        "public final class ItemStack implements DataComponentHolder {",
        """public final class ItemStack implements DataComponentHolder,
        net.neoforged.neoforge.common.extensions.IItemStackExtension { // Eturlia - every NeoForge stack hook at once""",
        "ItemStack implements IItemStackExtension",
    )


def install_item_extension():
    """Item gains every NeoForge item hook at once instead of one bridge per crash."""
    print("extension plane")
    replace(
        SERVER + "/net/minecraft/world/item/Item.java",
        "public class Item implements FeatureElement, ItemLike {",
        """public class Item implements FeatureElement, ItemLike,
        net.neoforged.neoforge.common.extensions.IItemExtension { // Eturlia - every NeoForge item hook at once""",
        "Item implements IItemExtension",
    )
    replace(
        SERVER + "/net/minecraft/world/item/Item.java",
        "    // Eturlia/NeoForge: IItemExtension stack-aware crafting remainder (FD / recipes)",
        """    // Eturlia start - IItemExtension's one abstract method
    /**
     * Whether an anvil may repair this stack with materials.
     *
     * <p>NeoForge patches this straight into {@code Item}; Eturlia implements the interface
     * instead, which is why it has to be spelled out here. The answer is vanilla's own: an item
     * that cannot lose durability cannot be repaired.</p>
     */
    @Override
    public boolean isRepairable(ItemStack stack) {
        return stack.isDamageableItem();
    }
    // Eturlia end - IItemExtension's one abstract method

    // Eturlia/NeoForge: IItemExtension stack-aware crafting remainder (FD / recipes)""",
        "Item.isRepairable",
    )


def install_level_extension():
    """Level, Player and BlockEntity gain their NeoForge hook interfaces in one go."""
    print("level extension plane")
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        "public abstract class Level extends net.neoforged.neoforge.attachment.AttachmentHolder implements LevelAccessor, AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel, ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemEntityGetter, ca.spottedleaf.moonrise.patches.collisions.world.CollisionLevel { // Paper - rewrite chunk system // Paper - optimise collisions // NeoForge - AttachmentHolder",
        """public abstract class Level extends net.neoforged.neoforge.attachment.AttachmentHolder implements LevelAccessor, AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel, ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemEntityGetter, ca.spottedleaf.moonrise.patches.collisions.world.CollisionLevel,
        net.neoforged.neoforge.common.extensions.ILevelExtension { // Paper - rewrite chunk system // Paper - optimise collisions // NeoForge - AttachmentHolder // Eturlia - every NeoForge level hook at once""",
        "Level implements ILevelExtension",
    )
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        "    // Eturlia start - NeoForge capability lookups",
        """    // Eturlia start - ILevelExtension's two abstract methods
    /**
     * The widest entity bounding box the level has seen.
     *
     * <p>NeoForge keeps this on {@code Level} so that entity searches can widen their query box
     * for mods with oversized entities - Create's contraptions are the local example. Implementing
     * the interface is what brings in the whole capability API with it, and the capability API is
     * how one modded block asks the block next to it for its inventory.</p>
     */
    private double eturlia$maxEntityRadius = 2.0D;

    @Override
    public double getMaxEntityRadius() {
        return this.eturlia$maxEntityRadius;
    }

    @Override
    public double increaseMaxEntityRadius(double value) {
        if (value > this.eturlia$maxEntityRadius) {
            this.eturlia$maxEntityRadius = value;
        }
        return this.eturlia$maxEntityRadius;
    }

    /**
     * Vanilla's name for the block-update notification Paper renamed.
     *
     * <p>Paper calls it {@code notifyAndUpdatePhysics} and takes the state the world actually ended
     * up with as an extra argument. Every mod compiled against vanilla or NeoForge still calls
     * {@code markAndNotifyBlock}: Create does it for every block it removes while assembling a
     * contraption, so without this bridge a bearing or an airship never assembles - the blocks stay
     * in the world, the contraption entity is never spawned, and the block entity ticks itself into
     * the same error forever.</p>
     */
    public void markAndNotifyBlock(BlockPos pos, @Nullable LevelChunk chunk, BlockState oldState, BlockState newState, int flags, int recursionLeft) {
        this.notifyAndUpdatePhysics(pos, chunk, oldState, newState, this.getBlockState(pos), flags, recursionLeft);
    }
    // Eturlia end - ILevelExtension's two abstract methods

    // Eturlia start - NeoForge capability lookups""",
        "Level.markAndNotifyBlock",
    )
    replace(
        SERVER + "/net/minecraft/world/entity/player/Player.java",
        "public abstract class Player extends LivingEntity {",
        """public abstract class Player extends LivingEntity implements
        net.neoforged.neoforge.common.extensions.IPlayerExtension { // Eturlia - every NeoForge player hook at once""",
        "Player implements IPlayerExtension",
    )
    replace(
        SERVER + "/net/minecraft/world/level/block/entity/BlockEntity.java",
        "public abstract class BlockEntity extends net.neoforged.neoforge.attachment.AttachmentHolder { // NeoForge - AttachmentHolder",
        """public abstract class BlockEntity extends net.neoforged.neoforge.attachment.AttachmentHolder implements
        net.neoforged.neoforge.common.extensions.IBlockEntityExtension { // NeoForge - AttachmentHolder // Eturlia - every NeoForge block entity hook at once

    // Eturlia start - IBlockEntityExtension's one abstract method
    /**
     * The scratch tag NeoForge hands mods for per-block-entity data.
     *
     * <p>Implementing the interface is what gives every block entity {@code onLoad},
     * {@code invalidateCapabilities} and {@code onDataPacket} - the three calls Create's kinetic
     * blocks make on every state change. The tag itself is not written to disk, which is what
     * NeoForge does for block entities that never touch it.</p>
     */
    private net.minecraft.nbt.CompoundTag eturlia$persistentData;

    @Override
    public net.minecraft.nbt.CompoundTag getPersistentData() {
        if (this.eturlia$persistentData == null) {
            this.eturlia$persistentData = new net.minecraft.nbt.CompoundTag();
        }
        return this.eturlia$persistentData;
    }
    // Eturlia end - IBlockEntityExtension's one abstract method
""",
        "BlockEntity implements IBlockEntityExtension",
    )


def install_remap_fallbacks():
    """A plugin the remapper chokes on loads anyway, and legacy CraftBukkit package names resolve."""
    print("remap fallback plane")
    replace(
        SERVER + "/io/papermc/paper/pluginremap/PluginRemapper.java",
        """            } catch (final Exception ex) {
                throw new RuntimeException("Failed to remap plugin jar '" + inputFile + "'", ex);
            }""",
        """            } catch (final Exception ex) {
                // Eturlia start - drop the classes this JVM could never load, then try once more
                // TAB and DecentHolograms ship adapters for future Minecraft versions compiled at a
                // class-file version this JVM does not know. AutoRenamingTool refuses the whole jar
                // over them, and an unremapped plugin then dies on its first NMS call
                // (DecentHolograms: CraftPlayer.getHandle). Those classes cannot run here under any
                // circumstances, so removing them costs nothing and buys the rest of the plugin.
                try {
                    final Path filtered = destination.resolveSibling(destination.getFileName() + ".eturlia-filtered.jar");
                    if (eturlia$stripUnloadableClasses(inputFile, filtered)) {
                        Files.deleteIfExists(destination);
                        try (final DebugLogger retryLogger = DebugLogger.forOutputFile(destination)) {
                            try (final Renamer retry = Renamer.builder()
                                .add(Transformer.renamerFactory(this.mappings(), false))
                                .add(addNamespaceManifestAttribute(InsertManifestAttribute.MOJANG_PLUS_YARN_NAMESPACE))
                                .add(Transformer.signatureStripperFactory(SignatureStripperConfig.ALL))
                                .lib(reobfServer.toFile())
                                .threads(1)
                                .logger(retryLogger)
                                .debug(retryLogger.debug())
                                .build()) {
                                retry.run(filtered.toFile(), destination.toFile());
                            }
                        }
                        Files.deleteIfExists(filtered);
                        LOGGER.info("Remapped {} '{}' after dropping classes this JVM cannot load.",
                            library ? "library" : "plugin", inputFile);
                        return destination;
                    }
                } catch (final Exception retryFailed) {
                    LOGGER.warn("Second attempt at '{}' failed too ({})", inputFile, retryFailed.toString());
                }
                // Eturlia end - drop the classes this JVM could never load, then try once more
                // Eturlia start - a jar the remapper cannot read is still better used as it is
                // AutoRenamingTool refuses a whole jar over one class it cannot parse (TAB and
                // DecentHolograms carry classes with a class-file version its ASM predates), and
                // Paper answers a failed remap by dropping the plugin. Most plugins that fail here
                // are already Mojang-mapped or never touch NMS at all, so the original jar loads
                // and works; the few that genuinely needed remapping fail on their own later, and
                // only those are lost instead of all of them.
                LOGGER.warn("Could not remap {} '{}' ({}); loading it unremapped.",
                    library ? "library" : "plugin", inputFile, ex.toString());
                try {
                    Files.deleteIfExists(destination);
                } catch (final IOException ignored) {
                }
                index.skip(inputFile);
                return inputFile;
                // Eturlia end - a jar the remapper cannot read is still better used as it is
            }""",
        "PluginRemapper falls back to the original jar",
    )
    replace(
        SERVER + "/io/papermc/paper/pluginremap/PluginRemapper.java",
        """    private IMappingFile mappings() {""",
        """    // Eturlia start - copy a jar without the classes this JVM cannot load
    /**
     * Writes {@code input} to {@code output} minus every class whose class-file version this JVM
     * does not support, and minus multi-release entries for later Java versions.
     *
     * @return {@code true} if anything was dropped, so a second remap attempt is worth making
     */
    private static boolean eturlia$stripUnloadableClasses(final Path input, final Path output) throws IOException {
        final int maxMajor = 44 + Runtime.version().feature(); // 65 on Java 21
        boolean dropped = false;
        Files.deleteIfExists(output);
        try (final java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(input));
             final java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(output))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                final byte[] bytes = in.readAllBytes();
                final String name = entry.getName();
                if (name.endsWith(".class") && bytes.length > 8) {
                    final int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                    if (major > maxMajor) {
                        dropped = true;
                        continue;
                    }
                }
                final java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(name);
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                out.write(bytes);
                out.closeEntry();
            }
        }
        if (!dropped) {
            Files.deleteIfExists(output);
        }
        return dropped;
    }
    // Eturlia end - copy a jar without the classes this JVM cannot load

    private IMappingFile mappings() {""",
        "PluginRemapper can drop unloadable classes",
    )
    replace(
        SERVER + "/io/papermc/paper/plugin/entrypoint/classloader/PaperPluginClassLoader.java",
        """        throw new ClassNotFoundException(name);
    }

    @Override
    public void init(JavaPlugin plugin) {""",
        """        // Eturlia start - the versioned CraftBukkit package a legacy plugin was built against
        // Plugins built for Spigot address CraftBukkit through org.bukkit.craftbukkit.v1_21_R1.
        // Paper dropped the version segment in 1.20.5, so those names resolve to nothing and the
        // plugin dies on enable (InvSee++: CraftHumanEntity). The classes are the same classes -
        // answer with the unversioned one rather than pretending they are gone.
        int eturliaVersionedPackage = name.indexOf("org.bukkit.craftbukkit.v1_");
        if (eturliaVersionedPackage == 0) {
            int afterVersion = name.indexOf('.', "org.bukkit.craftbukkit.".length());
            if (afterVersion > 0) {
                String unversioned = "org.bukkit.craftbukkit." + name.substring(afterVersion + 1);
                // CraftServer's own loader is the one that certainly holds the CraftBukkit classes;
                // this class may live in the API module, which does not see them.
                ClassLoader craftBukkitLoader = org.bukkit.Bukkit.getServer() != null
                    ? org.bukkit.Bukkit.getServer().getClass().getClassLoader()
                    : this.getClass().getClassLoader();
                try {
                    return Class.forName(unversioned, resolve, craftBukkitLoader);
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        // Eturlia end - the versioned CraftBukkit package a legacy plugin was built against

        throw new ClassNotFoundException(name);
    }

    @Override
    public void init(JavaPlugin plugin) {""",
        "PaperPluginClassLoader resolves versioned CraftBukkit names",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/util/CraftMagicNumbers.java",
        """    @Override
    public byte[] processClass(PluginDescriptionFile pdf, String path, byte[] clazz) {""",
        """    // Eturlia start - rewrite the versioned CraftBukkit package out of plugin bytecode
    /**
     * Rewrites {@code org/bukkit/craftbukkit/v1_21_R1/...} to {@code org/bukkit/craftbukkit/...}.
     *
     * <p>Answering the versioned name from the class loader is not enough: a constant-pool
     * reference must resolve to a class of exactly that name, so a plugin built against Spigot
     * fails at the first NMS call no matter what the loader returns (InvSee++ dies on
     * CraftHumanEntity while enabling). Rewriting the reference itself is the only version of this
     * that the JVM accepts. Plugins that never mention the versioned package are handed back
     * untouched, so this costs one substring scan for everyone else.</p>
     */
    public static byte[] eturlia$stripVersionedCraftBukkit(byte[] clazz) {
        if (clazz == null || clazz.length == 0) {
            return clazz;
        }
        if (!new String(clazz, java.nio.charset.StandardCharsets.ISO_8859_1).contains("org/bukkit/craftbukkit/v1_")) {
            return clazz;
        }
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(clazz);
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            reader.accept(new ClassRemapper(writer, new org.objectweb.asm.commons.Remapper() {
                @Override
                public String map(String internalName) {
                    if (internalName.startsWith("org/bukkit/craftbukkit/v1_")) {
                        int afterVersion = internalName.indexOf('/', "org/bukkit/craftbukkit/".length());
                        if (afterVersion > 0) {
                            return "org/bukkit/craftbukkit/" + internalName.substring(afterVersion + 1);
                        }
                    }
                    return internalName;
                }
            }), 0);
            return writer.toByteArray();
        } catch (Throwable thr) {
            // A class we cannot read is a class the JVM will complain about on its own terms.
            return clazz;
        }
    }
    // Eturlia end - rewrite the versioned CraftBukkit package out of plugin bytecode

    @Override
    public byte[] processClass(PluginDescriptionFile pdf, String path, byte[] clazz) {
        clazz = CraftMagicNumbers.eturlia$stripVersionedCraftBukkit(clazz); // Eturlia - before anything else looks at it""",
        "CraftMagicNumbers strips the versioned CraftBukkit package",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/util/CraftMagicNumbers.java",
        """import org.bukkit.plugin.PluginDescriptionFile;""",
        """import org.bukkit.plugin.PluginDescriptionFile;
import org.objectweb.asm.commons.ClassRemapper; // Eturlia - versioned CraftBukkit package rewrite""",
        "CraftMagicNumbers imports ClassRemapper",
    )
    replace(
        API + "/org/bukkit/plugin/java/PluginClassLoader.java",
        """        throw new ClassNotFoundException(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {""",
        """        // Eturlia start - the versioned CraftBukkit package a legacy plugin was built against
        // Same as in PaperPluginClassLoader, for the plugins that still come through the Bukkit
        // loader: org.bukkit.craftbukkit.v1_21_R1.entity.CraftHumanEntity is
        // org.bukkit.craftbukkit.entity.CraftHumanEntity with a version segment Paper dropped.
        if (name.startsWith("org.bukkit.craftbukkit.v1_")) {
            int afterVersion = name.indexOf('.', "org.bukkit.craftbukkit.".length());
            if (afterVersion > 0) {
                // CraftServer's own loader, not this one: PluginClassLoader lives in the API jar,
                // which ModLauncher may hand a module that cannot see org.bukkit.craftbukkit.
                ClassLoader craftBukkitLoader = org.bukkit.Bukkit.getServer() != null
                    ? org.bukkit.Bukkit.getServer().getClass().getClassLoader()
                    : PluginClassLoader.class.getClassLoader();
                try {
                    return Class.forName("org.bukkit.craftbukkit." + name.substring(afterVersion + 1),
                        resolve, craftBukkitLoader);
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        // Eturlia end - the versioned CraftBukkit package a legacy plugin was built against

        throw new ClassNotFoundException(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {""",
        "legacy PluginClassLoader resolves versioned CraftBukkit names",
    )


def install_modded_entity_wrappers():
    """A modded wall entity no longer needs a Bukkit class that does not exist."""
    print("modded entity wrapper plane")
    replace(
        SERVER + "/net/minecraft/world/entity/decoration/BlockAttachedEntity.java",
        """                    HangingBreakEvent event = new HangingBreakEvent((Hanging) this.getBukkitEntity(), cause);
                    this.level().getCraftServer().getPluginManager().callEvent(event);

                    if (this.isRemoved() || event.isCancelled()) {
                        return;
                    }""",
        """                    // Eturlia start - a modded wall entity has no Bukkit Hanging class
                    // HangingBreakEvent needs a Hanging, and Bukkit only has wrappers for the four
                    // vanilla ones. Create's crafting blueprint and seat are block-attached too, and
                    // the cast ended their region the first time one of them lost its wall. Without
                    // a wrapper there is no event to fire, so the entity just falls the vanilla way.
                    if (!(this.getBukkitEntity() instanceof Hanging bukkitHanging)) {
                        this.discard(EntityRemoveEvent.Cause.DROP);
                        this.dropItem((Entity) null);
                        return;
                    }
                    // Eturlia end - a modded wall entity has no Bukkit Hanging class
                    HangingBreakEvent event = new HangingBreakEvent(bukkitHanging, cause);
                    this.level().getCraftServer().getPluginManager().callEvent(event);

                    if (this.isRemoved() || event.isCancelled()) {
                        return;
                    }""",
        "BlockAttachedEntity tolerates a modded wall entity",
    )


def install_level_is_subclassable():
    """Paper sealed most of Level for the JIT; mods that build their own Level cannot load at all."""
    print("level subclass plane (whole class)")
    path = SERVER + "/net/minecraft/world/level/Level.java"
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    head = re.compile(r"^(\s+)(public|protected)( static)? final (.*)$")
    changed = 0
    for index, line in enumerate(lines):
        match = head.match(line)
        if match is None:
            continue
        rest = match.group(4)
        open_paren = rest.find("(")
        if open_paren < 0:
            continue  # a field, not a method
        equals = rest.find("=")
        if equals != -1 and equals < open_paren:
            continue  # a field whose initialiser happens to call something
        lines[index] = "%s%s%s %s" % (match.group(1), match.group(2), match.group(3) or "", rest)
        changed += 1

    if changed == 0:
        print("  already applied: Level's methods are overridable")
        return

    # One note in the file, so the next reader knows this was deliberate.
    for index, line in enumerate(lines):
        if line.startswith("public abstract class Level "):
            lines.insert(index, "// Eturlia - Paper marks most of Level's methods final to help the JIT inline them. Mods build\n"
                                "// their own Level subclasses (Create's SchematicLevel and PonderLevel, contraption worlds), and a\n"
                                "// subclass that overrides a final method cannot even be defined - IncompatibleClassChangeError at\n"
                                "// class load, before a line of the mod runs. The methods are unchanged; only the seal is gone.")
            break

    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    print("  Level: %d methods are overridable again" % changed)


def install_builtin_pack_source():
    """NeoForge adds one method to BuiltInPackSource; without it a mod's built-in packs never load."""
    print("built-in pack source plane")
    path = SERVER + "/net/minecraft/server/packs/repository/BuiltInPackSource.java"
    if os.path.exists(path) and "fromName" in read(path) and "Eturlia" in read(path):
        print("  already applied: BuiltInPackSource.fromName")
        return

    vanilla = (CORE + "/Folia-Server/.gradle/caches/paperweight/mc-dev-sources"
                      "/net/minecraft/server/packs/repository/BuiltInPackSource.java")
    if not os.path.exists(vanilla):
        print("  !! no decompiled BuiltInPackSource to start from — run applyPatches first")
        return

    source = read(vanilla)
    marker = "public abstract class BuiltInPackSource implements RepositorySource {"
    if marker not in source:
        print("  !! BuiltInPackSource does not look like the class we expected")
        return

    addition = marker + """
    // Eturlia start - the one method NeoForge patches into this class
    /**
     * Builds a resources supplier that resolves the pack by its name when it is opened.
     *
     * <p>{@code AddPackFindersEvent.addPackFinders} calls this for every built-in pack a mod ships,
     * so a mod that carries its own datapack - Selling Bin and Starcatcher here - loses all of it
     * with a {@code NoSuchMethodError} while the server is still starting. NeoForge patches the
     * method into vanilla; Eturlia adds it to the class the same way it adds every other vanilla
     * shape a mod expects.</p>
     */
    public static Pack.ResourcesSupplier fromName(java.util.function.Function<PackLocationInfo, PackResources> open) {
        return new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return open.apply(info);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return open.apply(info);
            }
        };
    }
    // Eturlia end - the one method NeoForge patches into this class
"""
    write(path, source.replace(marker, addition, 1))
    print("  BuiltInPackSource.fromName")


def install_quiet_startup():
    """Two Eturlia messages that say the same thing hundreds of times, or say it too loudly."""
    print("quiet startup plane")
    replace(
        ETURLIA + "/eturlia/core/loading/LithostitchedCompatGate.java",
        """        String msg = \"\"\"""",
        """        // Eturlia start - the operator already answered this question
        // With the override set the block below is advice nobody asked for, printed to stderr on
        // every boot. One line is enough to keep the fact visible.
        if (Boolean.getBoolean("eturlia.lithostitched.allow-unsafe")) {
            LOGGER.warning("Lithostitched " + raw + " is below the supported " + min
                    + " and eturlia.lithostitched.allow-unsafe is set: starting anyway."
                    + " Chunk generation may throw NoSuchElementException in TemplateLists.getRandom.");
            return true;
        }
        // Eturlia end - the operator already answered this question
        String msg = \"\"\"""",
        "lithostitched override says one line",
    )


# every class tools/finalscan.py caught a mod subclassing
SUBCLASSED_BY_MODS = [
    # path under the server sources, the class declaration to comment above, why it is here
    ("net/minecraft/server/level/ChunkHolder.java", "public class ChunkHolder",
     "sable's PlotChunkHolder (Create Aeronautics sublevels) overrides getTickingChunk"),
    ("net/minecraft/world/entity/Entity.java", "public abstract class Entity",
     "sable overrides getEyePosition and setRemoved for contraption-relative entities"),
    ("net/minecraft/world/entity/LivingEntity.java", "public abstract class LivingEntity",
     "Brewery's BeerElemental overrides getDimensions"),
    ("net/minecraft/world/entity/decoration/HangingEntity.java", "public abstract class HangingEntity",
     "Create's Blueprint and Aeronautics' Diagram override recalculateBoundingBox"),
    ("net/minecraft/core/dispenser/DefaultDispenseItemBehavior.java",
     "public class DefaultDispenseItemBehavior",
     "Farmer's Delight overrides dispense for the cutting board"),
]


def install_subclassable_core():
    """Un-seal the core classes mods subclass. Found statically, not one crash report at a time.

    Paper and Moonrise mark methods `final` so the JIT can inline them. A subclass that overrides a
    final method cannot be *defined* - IncompatibleClassChangeError at class load, before a line of
    the mod runs, and whatever asked for that class dies with it. `tools/finalscan.py` reads the
    compiled core against every mod jar and lists exactly which classes are affected; this plane
    drops the seal on each of them. The methods themselves are untouched.
    """
    print("subclassable core plane")
    head = re.compile(r"^(\s+)(public|protected)( static)? final (.*)$")
    for relative, declaration, reason in SUBCLASSED_BY_MODS:
        path = SERVER + "/" + relative
        if not os.path.exists(path):
            # Paper only ships the files it patches; the rest are compiled straight from the
            # decompiled vanilla sources. Bring the file in first, then treat it like any other.
            vanilla = MC_DEV + "/" + relative
            if not os.path.exists(vanilla):
                print("  skipped (no source to start from): %s" % relative)
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(vanilla, encoding="utf-8") as handle:
                source = handle.read()
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(source)
            print("  imported vanilla %s" % relative.rsplit("/", 1)[-1])
        with open(path, encoding="utf-8") as handle:
            lines = handle.read().split("\n")

        changed = 0
        for index, line in enumerate(lines):
            match = head.match(line)
            if match is None:
                continue
            rest = match.group(4)
            open_paren = rest.find("(")
            if open_paren < 0:
                continue  # a field, not a method
            equals = rest.find("=")
            if equals != -1 and equals < open_paren:
                continue  # a field whose initialiser happens to call something
            lines[index] = "%s%s%s %s" % (match.group(1), match.group(2), match.group(3) or "", rest)
            changed += 1

        name = relative.rsplit("/", 1)[-1][: -len(".java")]
        if changed == 0:
            print("  already applied: %s is overridable" % name)
            continue

        for index, line in enumerate(lines):
            if line.startswith(declaration):
                lines.insert(index, "// Eturlia - the seal on these methods is gone so mods can subclass %s: %s.\n"
                                    "// A subclass that overrides a final method cannot be defined at all.\n"
                                    "// tools/finalscan.py lists every class in the pack that needs this." % (name, reason))
                break

        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines))
        print("  %s: %d methods are overridable again" % (name, changed))


def install_folia_disabled_commands():
    """Folia comments out the vanilla commands it has not made region-safe. Give them back.

    `/scoreboard`, `/team`, `/tag`, `/data`, `/clone`, `/function`, `/loot`, `/ride`, `/rotate`,
    `/schedule`, `/spreadplayers` and `/datapack` are all registered in vanilla and all commented out
    in Folia's Commands.java with "region threading - TODO". Thirteen commands an operator, a
    datapack and half the plugins on this server expect to exist, and none of them are there.

    A command typed by a player already runs on that player's region, which is the thread that owns
    the blocks and entities the command is about to touch - the case Folia was worried about is the
    console, and the console already refuses entity selectors for exactly this reason. So they are
    registered again. `-Deturlia.compat.folia-commands=strict` puts Folia's silence back.
    """
    print("folia command plane")
    path = SERVER + "/net/minecraft/commands/Commands.java"
    with open(path, encoding="utf-8") as handle:
        source = handle.read()

    if "ETURLIA_FOLIA_COMMANDS" in source:
        print("  already applied")
        return

    disabled = re.compile(r"^(\s*)//(\s*)(\w+\.register\([^\n]*?);(\s*)// Folia - region threading[^\n]*$",
                          re.MULTILINE)
    names = []

    # These stay off. Bukkit already provides /reload and /save-all, and the rest reach past a
    # single region by design - freezing the tick or profiling the server is not a per-region idea.
    leave_alone = ("ReloadCommand", "SaveAllCommand", "SaveOffCommand", "SaveOnCommand",
                   "TickCommand", "PerfCommand", "DebugCommand", "JfrCommand", "StopCommand")

    def restore(match):
        indent, _, call, _ = match.group(1), match.group(2), match.group(3), match.group(4)
        owner = call.split(".register")[0]
        if owner in leave_alone:
            return match.group(0)
        names.append(owner)
        return "%sif (ETURLIA_FOLIA_COMMANDS) %s; // Eturlia - Folia leaves this out; see ETURLIA_FOLIA_COMMANDS" % (indent, call)

    source, count = disabled.subn(restore, source)
    if count == 0:
        print("!! anchor missing: no commented-out Folia command registrations")
        return

    field = (
        "public class Commands {\n"
        "\n"
        "    // Eturlia - Folia comments out every vanilla command it has not made region-safe yet, so\n"
        "    // /scoreboard, /team, /tag, /data, /clone, /function, /loot, /ride, /rotate, /schedule,\n"
        "    // /spreadplayers and /datapack simply do not exist on it. A command a player types runs on\n"
        "    // that player's region - the thread that owns what the command is about to touch - and the\n"
        "    // console already refuses entity selectors, which is the case Folia was guarding against.\n"
        "    // -Deturlia.compat.folia-commands=strict restores Folia's behaviour.\n"
        "    public static final boolean ETURLIA_FOLIA_COMMANDS = !\"strict\".equalsIgnoreCase(\n"
        "        System.getProperty(\"eturlia.compat.folia-commands\", \"lenient\"));\n")
    if "public class Commands {\n" not in source:
        print("!! anchor missing: Commands class declaration")
        return
    source = source.replace("public class Commands {\n", field, 1)

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(source)
    print("  restored %d commands: %s" % (len(names), ", ".join(sorted(set(names)))))


def install_guest_level_ctor():
    """A Level that is not a ServerLevel has to be constructible. Create builds one per contraption.

    Level's constructor was written for the only Level Paper has ever had: a ServerLevel, with a
    Bukkit world, a generator and an environment handed in through a ThreadLocal that only
    MinecraftServer sets. Anything else was refused outright.

    Create's contraption world is a Level. So is its schematic world, and so is sable's plot level -
    the sublevel Create Aeronautics puts a physics airship in. Every one of them threw
    IllegalStateException inside its own constructor, once per tick, forever: the bearing assembled,
    the blocks came out of the world, and then nothing moved and nothing could be taken apart again.

    A guest level now gets the plain answers - no Bukkit world, no generator - and the main world's
    configuration, which is what Paper actually reads off a level on nearly every call.
    """
    print("guest level plane")
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        """        EturliaLevelCtorExtras eturliaExtras = ETURLIA$LEVEL_CTOR_EXTRAS.get();
        if (eturliaExtras == null) {
            throw new IllegalStateException("Eturlia Level ctor extras missing; ServerLevel must set ETURLIA$LEVEL_CTOR_EXTRAS");
        }
        org.bukkit.generator.ChunkGenerator gen = eturliaExtras.gen;
        org.bukkit.generator.BiomeProvider biomeProvider = eturliaExtras.biomeProvider;
        org.bukkit.World.Environment env = eturliaExtras.env;
        java.util.function.Function<org.spigotmc.SpigotWorldConfig, io.papermc.paper.configuration.WorldConfiguration> paperWorldConfigCreator = eturliaExtras.paperWorldConfigCreator;
        java.util.concurrent.Executor executor = eturliaExtras.executor;
        this.spigotConfig = new org.spigotmc.SpigotWorldConfig(((net.minecraft.world.level.storage.ServerLevelData) worlddatamutable).getLevelName()); // Spigot // Eturlia: ServerLevelData (FakeServerLevel ReadOly)
        this.paperConfig = paperWorldConfigCreator.apply(this.spigotConfig); // Paper - create paper world config
        this.generator = gen;
        this.world = new CraftWorld((ServerLevel) this, gen, biomeProvider, env);""",
        """        // Eturlia start - a Level that is not a ServerLevel is still a Level
        EturliaLevelCtorExtras eturliaExtras = ETURLIA$LEVEL_CTOR_EXTRAS.get();
        java.util.concurrent.Executor executor;
        if (eturliaExtras != null) {
            org.bukkit.generator.ChunkGenerator gen = eturliaExtras.gen;
            org.bukkit.generator.BiomeProvider biomeProvider = eturliaExtras.biomeProvider;
            org.bukkit.World.Environment env = eturliaExtras.env;
            executor = eturliaExtras.executor;
            this.spigotConfig = new org.spigotmc.SpigotWorldConfig(((net.minecraft.world.level.storage.ServerLevelData) worlddatamutable).getLevelName()); // Spigot // Eturlia: ServerLevelData (FakeServerLevel ReadOly)
            this.paperConfig = eturliaExtras.paperWorldConfigCreator.apply(this.spigotConfig); // Paper - create paper world config
            this.generator = gen;
            this.world = new CraftWorld((ServerLevel) this, gen, biomeProvider, env);
        } else {
            // A mod's own level: Create's contraption and schematic worlds, sable's plot sublevels.
            // Nobody set the extras because the caller is not MinecraftServer, and there is no
            // Bukkit world to build - the level exists inside one. Refusing to construct threw once
            // per tick out of the contraption's block entity, which is what "the machine assembles
            // and then does nothing, and I cannot even remove it" looked like from in-game.
            net.minecraft.server.MinecraftServer eturliaServer = net.minecraft.server.MinecraftServer.getServer();
            ServerLevel eturliaMainLevel = eturliaServer == null ? null : eturliaServer.overworld();
            executor = eturliaServer != null ? eturliaServer : Runnable::run;
            this.spigotConfig = eturliaMainLevel != null ? eturliaMainLevel.spigotConfig : new org.spigotmc.SpigotWorldConfig("world");
            this.paperConfig = eturliaMainLevel != null ? eturliaMainLevel.paperConfig() : null;
            this.generator = null;
            this.world = null;
        }
        // Eturlia end - a Level that is not a ServerLevel is still a Level""",
        "Level's constructor accepts a level a mod built",
    )


def install_missing_interface_defaults():
    """CraftBukkit and Paper bolt abstract methods onto vanilla interfaces; mods never heard of them.

    A modded class implements the interface it found in the vanilla jar, loads without complaint,
    and throws AbstractMethodError the first time anything calls the method that was added here.
    tools/finalscan.py finds these the same way it finds the final overrides; each one below is a
    method with a real implementor count in this pack (132 modded merchants, 50 modded recipes, 26
    fake levels). Giving each a `default` costs vanilla nothing - every vanilla class still
    overrides it - and turns a dead region into the answer the method was always going to give.
    """
    print("interface default plane")

    replace(
        SERVER + "/net/minecraft/world/level/BlockGetter.java",
        "    @Nullable BlockState getBlockStateIfLoaded(BlockPos blockposition);",
        "    // Eturlia - Paper's \"only if the chunk is already loaded\" pair. A mod's fake level (Create's\n"
        "    // schematic world, CreativeCore's, citadel's path cache) has every block in memory already,\n"
        "    // so the plain lookup is the honest answer and there is nothing to load.\n"
        "    @Nullable default BlockState getBlockStateIfLoaded(BlockPos blockposition) { return this.getBlockState(blockposition); }",
        "BlockGetter.getBlockStateIfLoaded has a default",
    )
    replace(
        SERVER + "/net/minecraft/world/level/BlockGetter.java",
        "    @Nullable FluidState getFluidIfLoaded(BlockPos blockposition);",
        "    @Nullable default FluidState getFluidIfLoaded(BlockPos blockposition) { return this.getFluidState(blockposition); } // Eturlia - see getBlockStateIfLoaded",
        "BlockGetter.getFluidIfLoaded has a default",
    )
    replace(
        SERVER + "/net/minecraft/world/level/LevelReader.java",
        "    @Nullable ChunkAccess getChunkIfLoadedImmediately(int x, int z); // Paper - ifLoaded api (we need this since current impl blocks if the chunk is loading)",
        "    // Eturlia - default for the same reason as BlockGetter's pair: sable's plot levels and the\n"
        "    // other wrapper levels in this pack implement LevelReader and never saw this method.\n"
        "    @Nullable default ChunkAccess getChunkIfLoadedImmediately(int x, int z) { return this.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false); } // Paper - ifLoaded api",
        "LevelReader.getChunkIfLoadedImmediately has a default",
    )
    replace(
        SERVER + "/net/minecraft/world/level/LevelAccessor.java",
        "    net.minecraft.server.level.ServerLevel getMinecraftWorld(); // CraftBukkit",
        "    // Eturlia - a wrapper level accessor is not backed by a ServerLevel and cannot invent one.\n"
        "    // Null says that; AbstractMethodError said nothing and took the caller's thread with it.\n"
        "    default net.minecraft.server.level.ServerLevel getMinecraftWorld() { return this instanceof net.minecraft.server.level.ServerLevel level ? level : null; } // CraftBukkit",
        "LevelAccessor.getMinecraftWorld has a default",
    )
    replace(
        SERVER + "/net/minecraft/world/item/trading/Merchant.java",
        "    org.bukkit.craftbukkit.inventory.CraftMerchant getCraftMerchant(); // CraftBukkit",
        "    // Eturlia - 132 modded NPCs in this pack implement Merchant. None of them has a Bukkit\n"
        "    // merchant to hand back, and saying so is survivable; AbstractMethodError was not.\n"
        "    default org.bukkit.craftbukkit.inventory.CraftMerchant getCraftMerchant() { return null; } // CraftBukkit",
        "Merchant.getCraftMerchant has a default",
    )
    replace(
        SERVER + "/net/minecraft/world/item/crafting/Recipe.java",
        "    org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id); // CraftBukkit",
        """    // Eturlia start - a modded recipe still has to have a Bukkit form
    // 50 recipe classes in this pack implement Recipe without this CraftBukkit method, and every
    // plugin that walks Bukkit.recipeIterator() hit AbstractMethodError on the first one. A modded
    // recipe's shape cannot be expressed in Bukkit, but its result can: a shapeless recipe with no
    // ingredients describes "this makes that, by means Bukkit has no word for". If even the result
    // cannot be read, null - and RecipeIterator skips those.
    default org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                return null;
            }
            org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.getResultItem(server.registryAccess()));
            if (result == null || result.getType().isAir()) {
                return null;
            }
            return new org.bukkit.inventory.ShapelessRecipe(id, result);
        } catch (Throwable thrown) {
            return null;
        }
    }
    // Eturlia end - a modded recipe still has to have a Bukkit form""",
        "Recipe.toBukkitRecipe has a default",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/inventory/RecipeIterator.java",
        """    @Override
    public boolean hasNext() {
        return this.recipes.hasNext();
    }

    @Override
    public Recipe next() {
        // Paper start - fix removing recipes from RecipeIterator
        this.currentRecipe = this.recipes.next().getValue().toBukkitRecipe();
        return this.currentRecipe;
        // Paper end - fix removing recipes from RecipeIterator
    }""",
        """    // Eturlia start - a recipe with no Bukkit form is skipped, never handed out as null
    private Recipe nextRecipe;

    private Recipe peek() {
        while (this.nextRecipe == null && this.recipes.hasNext()) {
            this.nextRecipe = this.recipes.next().getValue().toBukkitRecipe();
        }
        return this.nextRecipe;
    }

    @Override
    public boolean hasNext() {
        return this.peek() != null;
    }

    @Override
    public Recipe next() {
        Recipe recipe = this.peek();
        if (recipe == null) {
            throw new java.util.NoSuchElementException();
        }
        this.nextRecipe = null;
        this.currentRecipe = recipe; // Paper - fix removing recipes from RecipeIterator
        return recipe;
    }
    // Eturlia end - a recipe with no Bukkit form is skipped, never handed out as null""",
        "RecipeIterator skips recipes with no Bukkit form",
    )
    replace(
        SERVER + "/org/bukkit/craftbukkit/inventory/RecipeIterator.java",
        """        // Paper end - fix removing recipes from RecipeIterator
        this.recipes.remove();""",
        """        // Paper end - fix removing recipes from RecipeIterator
        if (this.nextRecipe == null) { // Eturlia - with one recipe read ahead, the underlying iterator is past the one being removed
            this.recipes.remove();
        }""",
        "RecipeIterator.remove respects the read-ahead",
    )


def install_block_state_without_tile():
    """A block whose Bukkit type wants a block entity, but has none, still gives a BlockState."""
    print("block state plane")
    replace(
        SERVER + "/org/bukkit/craftbukkit/block/CraftBlockStates.java",
        """        if (world != null && tileEntity == null && CraftBlockStates.isTileEntityOptional(material)) {
            factory = CraftBlockStates.DEFAULT_FACTORY;
        } else {
            factory = CraftBlockStates.getFactory(material, tileEntity != null ? tileEntity.getType() : null); // Paper
        }""",
        """        if (world != null && tileEntity == null && CraftBlockStates.isTileEntityOptional(material)) {
            factory = CraftBlockStates.DEFAULT_FACTORY;
        } else {
            factory = CraftBlockStates.getFactory(material, tileEntity != null ? tileEntity.getType() : null); // Paper
        }
        // Eturlia start - a modded block borrows a vanilla Material, not its block entity
        // Plugins read block.getState() from every interact and place event. A modded block reports
        // a stand-in Material, and if that Material's factory expects a block entity the factory
        // throws "Tile is null, asynchronous access?" - which is not what happened at all, and it
        // takes the plugin's handler with it (WorldGuard and CoreProtect, 31 times in one minute of
        // play). With no block entity there, the plain state is the only honest answer.
        if (world != null && tileEntity == null && factory instanceof BlockEntityStateFactory) {
            factory = CraftBlockStates.DEFAULT_FACTORY;
        }
        // Eturlia end - a modded block borrows a vanilla Material, not its block entity""",
        "CraftBlockStates falls back when there is no block entity",
    )


def install_lenient_schedulers():
    """Folia's schedulers refuse what Bukkit's accept, and the plugin that asked dies on enable."""
    print("scheduler plane")
    base = SERVER + "/io/papermc/paper/threadedregions/scheduler/"
    names = ["FoliaGlobalRegionScheduler.java", "FoliaRegionScheduler.java",
             "FoliaEntityScheduler.java", "FoliaAsyncScheduler.java"]
    check = re.compile(
        r"if \((\w+) (<=|<) 0L?\) \{\n\s*throw new IllegalArgumentException\(\"[^\"]+\"\);\n(\s*)\}")
    finals = re.compile(r"final (long (?:delayTicks|initialDelayTicks|periodTicks|delay|initialDelay|period)\b)")

    for name in names:
        path = base + name
        with open(path, encoding="utf-8") as handle:
            source = handle.read()
        if "Eturlia - clamp" in source:
            print("  already applied: %s" % name)
            continue

        def clamp(match):
            variable, operator, indent = match.group(1), match.group(2), match.group(3)
            # "< 0" guards a delay, where zero is a legal answer; "<= 0" guards a period, which has
            # to be at least one tick to mean anything.
            floor = "0L" if operator == "<" else "1L"
            return ("if (%s %s 0) {\n%s    %s = %s; // Eturlia - clamp instead of throwing: a plugin"
                    " written for Bukkit passes 0 here, and Folia answered by killing it on enable"
                    "\n%s}" % (variable, operator, indent, variable, floor, indent))

        patched = finals.sub(r"\1", check.sub(clamp, source))
        if patched == source:
            print("  !! nothing to clamp in %s" % name)
            continue
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(patched)
        print("  %s: schedulers clamp their arguments" % name)


def install_plugin_context_loader():
    """A plugin that scans its own classpath on enable finds it."""
    print("plugin context loader plane")
    replace(
        API + "/org/bukkit/plugin/java/JavaPlugin.java",
        """        if (isEnabled != enabled) {
            isEnabled = enabled;

            if (isEnabled) {
                try { // Paper - lifecycle events
                onEnable();
                } finally { this.allowsLifecycleRegistration = false; } // Paper - lifecycle events
            } else {
                onDisable();
            }
        }""",
        """        if (isEnabled != enabled) {
            isEnabled = enabled;

            // Eturlia start - run the callback under the plugin's own class loader
            // Libraries that discover their implementations by scanning the classpath - ClassGraph,
            // Reflections, ServiceLoader - start from the thread context class loader. On a plain
            // server that is close enough to the plugin's loader; here it is ModLauncher's module
            // loader, which enumerates nothing a plugin owns, so the scan comes back empty and the
            // plugin throws while enabling (ImageFrame: "Unable to find suitable implementation of
            // PlatformScheduler"). Pointing it at the plugin for the length of the callback costs
            // nothing and is what the library expected to find.
            final Thread eturliaThread = Thread.currentThread();
            final ClassLoader eturliaPrevious = eturliaThread.getContextClassLoader();
            final ClassLoader eturliaPluginLoader = this.getClass().getClassLoader();
            if (eturliaPluginLoader != null) {
                eturliaThread.setContextClassLoader(eturliaPluginLoader);
            }
            try {
            // Eturlia end - run the callback under the plugin's own class loader
            if (isEnabled) {
                try { // Paper - lifecycle events
                onEnable();
                } finally { this.allowsLifecycleRegistration = false; } // Paper - lifecycle events
            } else {
                onDisable();
            }
            // Eturlia start - run the callback under the plugin's own class loader
            } finally {
                eturliaThread.setContextClassLoader(eturliaPrevious);
            }
            // Eturlia end - run the callback under the plugin's own class loader
        }""",
        "JavaPlugin enables under its own class loader",
    )


def install_wrapper_level_compat():
    """A mod's fake Level (Create's contraption and schematic worlds) can be constructed at all."""
    print("wrapper level plane")
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        """    public final io.papermc.paper.threadedregions.RegionizedData<io.papermc.paper.threadedregions.RegionizedWorldData> worldRegionData
        = new io.papermc.paper.threadedregions.RegionizedData<>(
        (ServerLevel)this, () -> new io.papermc.paper.threadedregions.RegionizedWorldData((ServerLevel)Level.this),
        io.papermc.paper.threadedregions.RegionizedWorldData.REGION_CALLBACK
    );""",
        """    // Eturlia start - not every Level is a ServerLevel
    // Folia casts this to ServerLevel in a field initialiser, so the cast runs inside Level's
    // constructor - and a mod that wraps a level (Create's ContraptionWorld and SchematicLevel,
    // through catnip's WrappedLevel) is a Level that is not a ServerLevel. Every contraption
    // therefore died with a ClassCastException the moment it was assembled, right after its blocks
    // had already been taken out of the world. A wrapper level has no regions and never ticks, so
    // it has no region data either; null is the honest answer and nothing on that path asks.
    public final io.papermc.paper.threadedregions.RegionizedData<io.papermc.paper.threadedregions.RegionizedWorldData> worldRegionData
        = Level.eturlia$newWorldRegionData(this);

    private static io.papermc.paper.threadedregions.RegionizedData<io.papermc.paper.threadedregions.RegionizedWorldData> eturlia$newWorldRegionData(Level level) {
        if (!(((Object) level) instanceof ServerLevel serverLevel)) {
            return null;
        }
        return new io.papermc.paper.threadedregions.RegionizedData<>(
            serverLevel, () -> new io.papermc.paper.threadedregions.RegionizedWorldData(serverLevel),
            io.papermc.paper.threadedregions.RegionizedWorldData.REGION_CALLBACK
        );
    }
    // Eturlia end - not every Level is a ServerLevel""",
        "Level.worldRegionData tolerates a wrapper level",
    )
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        """        Level world = ret.world;
        if (world != this) {
            throw new IllegalStateException("World mismatch: expected " + this.getWorld().getName() + " but got " + world.getWorld().getName());
        }""",
        """        Level world = ret.world;
        if (world != this) {
            // Eturlia start - a wrapper level borrows the region it is being used from
            // Create runs block logic against its contraption world while ticking a real region.
            // The data belongs to the real world, which is exactly the world the wrapper is
            // standing in for, so lending it is closer to right than refusing to answer.
            if (!net.minecraft.server.MinecraftServer.eturlia$strictFoliaStubs() && !(((Object) this) instanceof ServerLevel)) {
                return ret;
            }
            // Eturlia end - a wrapper level borrows the region it is being used from
            throw new IllegalStateException("World mismatch: expected " + this.getWorld().getName() + " but got " + world.getWorld().getName());
        }""",
        "Level.getCurrentWorldData tolerates a wrapper level",
    )


def install_modded_material_bridge():
    """A modded block answers plugins with a Material instead of null."""
    print("modded material plane")
    replace(
        SERVER + "/org/bukkit/craftbukkit/util/CraftMagicNumbers.java",
        """    public static Material getMaterial(Block block) {
        return CraftMagicNumbers.BLOCK_MATERIAL.get(block);
    }""",
        """    public static Material getMaterial(Block block) {
        // Eturlia start - a modded block has no Material, and plugins never check for null
        // CoreProtect calls blockType.name() and WorldGuard compares the Material directly, both
        // straight out of BlockPlaceEvent - so the first modded block a player places takes the
        // event handler down with a NullPointerException, and with it whatever protection that
        // plugin was there to apply. STONE is the least surprising stand-in: a plain solid block,
        // so region protection and logging behave the way they would for any other block, and
        // nothing is told that a modded block is empty air. -Deturlia.compat.bukkit-types=strict
        // brings back the null.
        Material material = CraftMagicNumbers.BLOCK_MATERIAL.get(block);
        if (material == null && !"strict".equalsIgnoreCase(System.getProperty("eturlia.compat.bukkit-types", "lenient"))) {
            if (ETURLIA_UNKNOWN_BLOCKS.add(block)) {
                int seen = ETURLIA_UNKNOWN_BLOCKS.size();
                if (seen <= 3) {
                    org.bukkit.Bukkit.getLogger().info("Eturlia: " + BuiltInRegistries.BLOCK.getKey(block)
                            + " is a modded block, so plugins see it as STONE");
                } else if (seen == 4) {
                    org.bukkit.Bukkit.getLogger().info("Eturlia: more modded blocks follow; plugins see all of them as STONE");
                }
            }
            return Material.STONE;
        }
        return material;
        // Eturlia end - a modded block has no Material, and plugins never check for null
    }

    private static final java.util.Set<Block> ETURLIA_UNKNOWN_BLOCKS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()); // Eturlia""",
        "CraftMagicNumbers answers STONE for a modded block",
    )


def install_console_command_errors():
    """A failing vanilla command reports what went wrong instead of a NullPointerException."""
    print("console command error plane")
    replace(
        SERVER + "/org/bukkit/craftbukkit/CraftServer.java",
        """        } catch (CommandException ex) {
            this.pluginManager.callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerCommandException(ex, target, sender, args))); // Paper
            //target.timings.stopTiming(); // Spigot // Paper
            throw ex;
        } catch (Throwable ex) {
            //target.timings.stopTiming(); // Spigot // Paper
            String msg = "Unhandled exception executing '" + commandLine + "' in " + target;
            this.pluginManager.callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerCommandException(ex, target, sender, args))); // Paper
            throw new CommandException(msg, ex);
        }""",
        """        } catch (CommandException ex) {
            // Eturlia start - a vanilla command has no Bukkit Command to blame
            // ServerCommandException requires the Bukkit Command that failed, and a vanilla command
            // reached this way has none: the constructor then threw NullPointerException("command")
            // and that is all the operator ever saw - the real failure never reached the console.
            if (target != null) {
            this.pluginManager.callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerCommandException(ex, target, sender, args))); // Paper
            } else {
                net.minecraft.server.MinecraftServer.LOGGER.warn("Command '{}' failed", commandLine, ex);
            }
            // Eturlia end - a vanilla command has no Bukkit Command to blame
            //target.timings.stopTiming(); // Spigot // Paper
            throw ex;
        } catch (Throwable ex) {
            //target.timings.stopTiming(); // Spigot // Paper
            String msg = "Unhandled exception executing '" + commandLine + "' in " + target;
            // Eturlia start - a vanilla command has no Bukkit Command to blame
            if (target != null) {
            this.pluginManager.callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerCommandException(ex, target, sender, args))); // Paper
            } else {
                net.minecraft.server.MinecraftServer.LOGGER.warn(msg, ex);
            }
            // Eturlia end - a vanilla command has no Bukkit Command to blame
            throw new CommandException(msg, ex);
        }""",
        "CraftServer reports the real command failure",
    )


def install_off_region_world_data():
    """Code that touches a world from outside a region tick gets empty data, not a null pointer."""
    print("off-region world data plane")
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        """    public io.papermc.paper.threadedregions.RegionizedWorldData getCurrentWorldData() {
        final io.papermc.paper.threadedregions.RegionizedWorldData ret = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (ret == null) {
            return ret;
        }""",
        """    // Eturlia start - a world always has data to answer with
    /**
     * The scratch world data handed to callers that are not inside a region tick.
     *
     * <p>Folia only creates this per region, so off a region thread it used to be null - and every
     * CraftBukkit-era field lives on it: {@code captureBlockStates}, {@code capturedTileEntities},
     * {@code captureTreeGeneration}. A console command, a mod's deferred "main thread" task, or a
     * plugin's async callback therefore died on a null pointer instead of doing something sensible.
     * Nothing captures anything outside a region tick, so an empty holder is the right answer;
     * writes into it go to a scratch object and are dropped, which is what "not in a region" means.
     * The old behaviour is one flag away: -Deturlia.compat.folia-stubs=strict.</p>
     */
    private volatile io.papermc.paper.threadedregions.RegionizedWorldData eturlia$offRegionWorldData;

    public io.papermc.paper.threadedregions.RegionizedWorldData eturlia$offRegionWorldData() {
        if (net.minecraft.server.MinecraftServer.eturlia$strictFoliaStubs()) {
            return null;
        }
        io.papermc.paper.threadedregions.RegionizedWorldData data = this.eturlia$offRegionWorldData;
        if (data != null) {
            return data;
        }
        synchronized (this) {
            if (this.eturlia$offRegionWorldData == null && ((Object) this) instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                try {
                    this.eturlia$offRegionWorldData = new io.papermc.paper.threadedregions.RegionizedWorldData(serverLevel);
                } catch (Throwable thr) {
                    // Building it needs the world to be far enough along; before that, null is
                    // still the honest answer and the caller keeps the behaviour it had.
                    return null;
                }
            }
            return this.eturlia$offRegionWorldData;
        }
    }
    // Eturlia end - a world always has data to answer with

    public io.papermc.paper.threadedregions.RegionizedWorldData getCurrentWorldData() {
        final io.papermc.paper.threadedregions.RegionizedWorldData ret = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (ret == null) {
            return this.eturlia$offRegionWorldData(); // Eturlia - empty data instead of null
        }""",
        "Level.getCurrentWorldData answers off-region callers",
    )


def install_level_subclass_compat():
    """A mod may still subclass Level; Moonrise sealed a method it overrides."""
    print("level subclass plane")
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        "    public final <T extends Entity> List<T> getEntitiesOfClass(final Class<T> entityClass, final AABB boundingBox, final Predicate<? super T> predicate) {",
        """    // Eturlia - Moonrise sealed this for the JIT; Create's SchematicLevel overrides it, and a
    // subclass that cannot even be defined takes every schematic, ponder and contraption preview
    // with it (IncompatibleClassChangeError at class load, before any of the mod's code runs).
    public <T extends Entity> List<T> getEntitiesOfClass(final Class<T> entityClass, final AABB boundingBox, final Predicate<? super T> predicate) {""",
        "Level.getEntitiesOfClass is overridable",
    )


def install_spawn_egg_compat():
    """A modded spawn egg answers the feature check and finds its own entity type."""
    print("spawn egg plane")
    replace(
        SERVER + "/net/minecraft/world/item/SpawnEggItem.java",
        """    @Override
    public FeatureFlagSet requiredFeatures() {
        return this.defaultType.requiredFeatures();
    }""",
        """    @Override
    public FeatureFlagSet requiredFeatures() {
        // Eturlia start - a spawn egg that resolves its type later has no default one
        // NeoForge's DeferredSpawnEggItem hands null to this constructor and overrides getType
        // instead, because the entity type does not exist yet when items are registered. Vanilla
        // dereferences the field here, and this method is on the path of every creative inventory
        // packet: the NPE is thrown before the item ever reaches the player's hand, which is why
        // modded eggs neither appear nor spawn anything.
        if (this.defaultType == null) {
            return net.minecraft.world.flag.FeatureFlags.VANILLA_SET;
        }
        // Eturlia end - a spawn egg that resolves its type later has no default one
        return this.defaultType.requiredFeatures();
    }""",
        "SpawnEggItem.requiredFeatures",
    )
    replace(
        SERVER + "/net/minecraft/world/item/SpawnEggItem.java",
        """    public EntityType<?> getType(ItemStack stack) {
        CustomData customdata = (CustomData) stack.getOrDefault(DataComponents.ENTITY_DATA, CustomData.EMPTY);

        return !customdata.isEmpty() ? (EntityType) customdata.read(SpawnEggItem.ENTITY_TYPE_FIELD_CODEC).result().orElse(this.defaultType) : this.defaultType;
    }""",
        """    public EntityType<?> getType(ItemStack stack) {
        CustomData customdata = (CustomData) stack.getOrDefault(DataComponents.ENTITY_DATA, CustomData.EMPTY);
        // Eturlia start - fall back to the type the item is named after
        EntityType<?> fallback = this.defaultType != null ? this.defaultType : this.eturlia$typeFromItemId(stack);

        return !customdata.isEmpty() ? (EntityType) customdata.read(SpawnEggItem.ENTITY_TYPE_FIELD_CODEC).result().orElse(fallback) : fallback;
    }

    /**
     * Guesses the entity type from the item id when the egg carries no type of its own.
     *
     * <p>Only reached for a subclass that meant to answer {@code getType} itself and did not - a
     * mod loaded far enough to register the item but not far enough to bind its entity supplier.
     * Both spellings mods use are tried, {@code potoo_spawn_egg} and {@code spawn_egg_potoo}.</p>
     */
    @Nullable
    private EntityType<?> eturlia$typeFromItemId(ItemStack stack) {
        net.minecraft.resources.ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return null;
        }

        String path = itemId.getPath();
        if (path.endsWith("_spawn_egg")) {
            path = path.substring(0, path.length() - "_spawn_egg".length());
        } else if (path.startsWith("spawn_egg_")) {
            path = path.substring("spawn_egg_".length());
        } else {
            return null;
        }

        return BuiltInRegistries.ENTITY_TYPE
            .getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(), path))
            .orElse(null);
        // Eturlia end - fall back to the type the item is named after
    }""",
        "SpawnEggItem.getType fallback",
    )
    replace(
        SERVER + "/net/minecraft/world/item/SpawnEggItem.java",
        """                entitytypes = this.getType(itemstack);
                if (entitytypes.spawn((ServerLevel) world, itemstack,""",
        """                entitytypes = this.getType(itemstack);
                if (entitytypes == null) return InteractionResult.PASS; // Eturlia - an egg with no type spawns nothing instead of throwing
                if (entitytypes.spawn((ServerLevel) world, itemstack,""",
        "SpawnEggItem.useOn null type",
    )
    replace(
        SERVER + "/net/minecraft/world/item/SpawnEggItem.java",
        """                EntityType<?> entitytypes = this.getType(itemstack);
                Entity entity = entitytypes.spawn((ServerLevel) world, itemstack, user, blockposition, MobSpawnType.SPAWN_EGG, false, false);""",
        """                EntityType<?> entitytypes = this.getType(itemstack);
                if (entitytypes == null) return InteractionResultHolder.pass(itemstack); // Eturlia - an egg with no type spawns nothing instead of throwing
                Entity entity = entitytypes.spawn((ServerLevel) world, itemstack, user, blockposition, MobSpawnType.SPAWN_EGG, false, false);""",
        "SpawnEggItem.use null type",
    )
    replace(
        SERVER + "/net/minecraft/world/item/SpawnEggItem.java",
        """                entitytypes = this.getType(itemstack);
                spawner.setEntityId(entitytypes, world.getRandom());""",
        """                entitytypes = this.getType(itemstack);
                if (entitytypes == null) return InteractionResult.PASS; // Eturlia - an egg with no type leaves the spawner alone
                spawner.setEntityId(entitytypes, world.getRandom());""",
        "SpawnEggItem spawner null type",
    )


def install_container_defaults():
    """A modded container no longer dies on the CraftBukkit methods it never heard of."""
    print("container plane")
    replace(
        SERVER + "/net/minecraft/world/Container.java",
        """    // CraftBukkit start
    java.util.List<ItemStack> getContents();

    void onOpen(CraftHumanEntity who);

    void onClose(CraftHumanEntity who);

    java.util.List<org.bukkit.entity.HumanEntity> getViewers();

    org.bukkit.inventory.@org.jetbrains.annotations.Nullable InventoryHolder getOwner(); // Paper - annotation

    void setMaxStackSize(int size);

    org.bukkit.Location getLocation();""",
        """    // CraftBukkit start
    // Eturlia start - CraftBukkit's container methods stop being abstract
    // CraftBukkit adds seven methods to this interface so that every vanilla container can answer
    // the Bukkit inventory API. A mod that writes its own container implements the interface it
    // found in the vanilla jar, which has none of them, and the class loads happily - until
    // something opens it and the JVM throws AbstractMethodError from inside a region tick, which on
    // Folia ends the server. That is one crash per modded chest, barrel or workbench: BCLib's
    // barrels in the End did it five times this afternoon.
    //
    // Defaults answer the way an inventory with no Bukkit side would: nobody is watching it, it
    // belongs to nobody, and it lives nowhere. Vanilla containers still override all of them, so
    // nothing about the Bukkit API changes for the containers that do have a Bukkit side.
    default java.util.List<ItemStack> getContents() {
        java.util.List<ItemStack> contents = new java.util.ArrayList<>(this.getContainerSize());
        for (int slot = 0; slot < this.getContainerSize(); ++slot) {
            contents.add(this.getItem(slot));
        }
        return contents;
    }

    default void onOpen(CraftHumanEntity who) {}

    default void onClose(CraftHumanEntity who) {}

    default java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return new java.util.ArrayList<>();
    }

    default org.bukkit.inventory.@org.jetbrains.annotations.Nullable InventoryHolder getOwner() {
        return null;
    }

    default void setMaxStackSize(int size) {}

    default org.bukkit.Location getLocation() {
        return null;
    }
    // Eturlia end - CraftBukkit's container methods stop being abstract""",
        "Container CraftBukkit defaults",
    )
    replace(
        SERVER + "/net/minecraft/world/Container.java",
        "    int getMaxStackSize(); // CraftBukkit",
        """    default int getMaxStackSize() { return Container.MAX_STACK; } // CraftBukkit // Eturlia - vanilla's own answer for containers that never overrode it""",
        "Container.getMaxStackSize default",
    )


def install_portal_compat():
    """A modded portal teleports instead of killing the region the player stands in."""
    print("portal plane")
    replace(
        SERVER + "/net/minecraft/world/level/block/Portal.java",
        """    // Folia start - region threading
    public boolean portalAsync(ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos);
    // Folia end - region threading""",
        """    // Folia start - region threading
    // Eturlia start - a portal written against vanilla still works
    // Folia made this abstract, so a modded portal - Twilight Forest, BetterEnd, the Aether - fails
    // with AbstractMethodError the moment an entity touches it. The error lands inside the player
    // tick, Folia answers a failed region tick by stopping the server, and because the player is
    // still standing in the portal when they log back in, the next login repeats it: the kick loop
    // reported for the Twilight Forest portal.
    //
    // The default does what NetherPortalBlock and EndPortalBlock do - refuse the move unless this
    // thread owns both the entity and the portal block - and then asks the mod itself where the
    // entity should end up.
    default boolean portalAsync(ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos) {
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(portalTarget)) {
            return false;
        }
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(sourceWorld, portalPos)) {
            return false;
        }
        return portalTarget.eturlia$portalAsync(this, sourceWorld, portalPos);
    }
    // Eturlia end - a portal written against vanilla still works
    // Folia end - region threading""",
        "Portal.portalAsync default",
    )
    replace(
        SERVER + "/net/minecraft/world/entity/Entity.java",
        "    public boolean endPortalLogicAsync(BlockPos portalPos) {",
        """    // Eturlia start - the transition half of Portal.portalAsync
    /**
     * Carries out a modded portal's own transition through Folia's async teleport.
     *
     * <p>{@code getPortalDestination} is the mod's method, written for a server where any thread
     * may touch any world. Here it runs on the thread that owns the portal block, which is the
     * closest thing to what it expects. If it reaches into a world this thread does not own,
     * Folia's thread check throws - and refusing the transition leaves the player standing in the
     * portal, which is a working server, while letting the throwable out is not.</p>
     */
    public boolean eturlia$portalAsync(net.minecraft.world.level.block.Portal portal, ServerLevel sourceWorld, BlockPos portalPos) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot portal entity async");

        final net.minecraft.world.level.portal.DimensionTransition transition;
        try {
            transition = portal.getPortalDestination(sourceWorld, this, portalPos);
        } catch (Throwable thr) {
            Entity.LOGGER.warn("Eturlia: portal {} could not pick a destination", portal.getClass().getName(), thr);
            return false;
        }

        if (transition == null || transition.newLevel() == null) {
            return false;
        }

        return this.teleportAsync(
            transition.newLevel(), transition.pos(), Float.valueOf(transition.yRot()), Float.valueOf(transition.xRot()),
            transition.speed(),
            transition.cause() == null ? org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN : transition.cause(),
            Entity.TELEPORT_FLAG_LOAD_CHUNK | Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS,
            (entity) -> {
                try {
                    transition.postDimensionTransition().onTransition(entity);
                } catch (Throwable thr) {
                    Entity.LOGGER.warn("Eturlia: portal {} failed after the transition", portal.getClass().getName(), thr);
                }
            }
        );
    }
    // Eturlia end - the transition half of Portal.portalAsync

    public boolean endPortalLogicAsync(BlockPos portalPos) {""",
        "Entity.eturlia$portalAsync",
    )


def install_packet_thread_routing():
    """A mod's "run this on the main thread" lands in the region that owns the player who asked."""
    print("packet routing plane")
    replace(
        SERVER + "/net/minecraft/network/Connection.java",
        """    private static <T extends PacketListener> void genericsFtw(Packet<T> packet, PacketListener listener) {
        packet.handle((T) listener); // CraftBukkit - decompile error
    }""",
        """    private static <T extends PacketListener> void genericsFtw(Packet<T> packet, PacketListener listener) {
        // Eturlia start - remember whose packet this thread is carrying
        // Mod networking runs on this thread and then asks the server to finish the work "on the
        // main thread". Folia has no main thread, so the core has to pick a region for it, and the
        // only region that can be right is the one that owns the player the packet came from.
        final PacketListener previousListener = net.minecraft.server.MinecraftServer.eturlia$currentListener.get();
        net.minecraft.server.MinecraftServer.eturlia$currentListener.set(listener);
        try {
        // Eturlia end - remember whose packet this thread is carrying
        packet.handle((T) listener); // CraftBukkit - decompile error
        // Eturlia start - remember whose packet this thread is carrying
        } finally {
            net.minecraft.server.MinecraftServer.eturlia$currentListener.set(previousListener);
        }
        // Eturlia end - remember whose packet this thread is carrying
    }""",
        "Connection tracks the packet listener",
    )
    replace(
        SERVER + "/net/minecraft/server/MinecraftServer.java",
        """    public static void eturlia$runAsMainThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (MinecraftServer.eturlia$inRegionTick()) {
            MinecraftServer.eturlia$runGuarded(runnable);
            return;
        }
        io.papermc.paper.threadedregions.RegionizedServer regionized =
            io.papermc.paper.threadedregions.RegionizedServer.getInstance();
        if (regionized != null) {
            regionized.addTask(() -> MinecraftServer.eturlia$runGuarded(runnable));
            return;
        }
        MinecraftServer.eturlia$runGuarded(runnable);
    }""",
        """    public static final ThreadLocal<net.minecraft.network.PacketListener> eturlia$currentListener = new ThreadLocal<>();

    public static void eturlia$runAsMainThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (MinecraftServer.eturlia$inRegionTick()) {
            MinecraftServer.eturlia$runGuarded(runnable);
            return;
        }
        io.papermc.paper.threadedregions.RegionizedServer regionized =
            io.papermc.paper.threadedregions.RegionizedServer.getInstance();
        // Eturlia start - a packet's task belongs to the packet's own region
        // The global region is a single queue for the whole server. Sending every mod packet
        // through it does two bad things at once: it serialises work that Folia just spent a lot of
        // effort splitting apart - which is what a player feels as a Create machine stuttering -
        // and it runs the task on a thread that owns no world data, so anything that touches the
        // world throws (3064 of those in one afternoon, all from one block-info packet).
        //
        // While a packet is being handled this thread knows which player sent it, and that player's
        // chunk names the region that owns the blocks around them - which is what the task is
        // almost always about. Queue it there instead: correct data, and one queue per player
        // rather than one for the server.
        if (regionized != null) {
            net.minecraft.network.PacketListener listener = MinecraftServer.eturlia$currentListener.get();
            if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gameListener) {
                net.minecraft.server.level.ServerPlayer player = gameListener.player;
                if (player != null && !player.isRemoved() && player.level() instanceof net.minecraft.server.level.ServerLevel level) {
                    net.minecraft.core.BlockPos pos = player.blockPosition();
                    regionized.taskQueue.queueTickTaskQueue(
                        level, pos.getX() >> 4, pos.getZ() >> 4,
                        () -> MinecraftServer.eturlia$runGuarded(runnable)
                    );
                    return;
                }
            }
        }
        // Eturlia end - a packet's task belongs to the packet's own region
        if (regionized != null) {
            regionized.addTask(() -> MinecraftServer.eturlia$runGuarded(runnable));
            return;
        }
        MinecraftServer.eturlia$runGuarded(runnable);
    }""",
        "runAsMainThread routes by packet owner",
    )
    replace(
        SERVER + "/net/minecraft/world/level/Level.java",
        """        net.minecraft.world.level.block.entity.BlockEntity blockEntity;
        if (!this.getCurrentWorldData().capturedTileEntities.isEmpty() && (blockEntity = this.getCurrentWorldData().capturedTileEntities.get(blockposition)) != null) { // Folia - region threading
            return blockEntity;
        }""",
        """        net.minecraft.world.level.block.entity.BlockEntity blockEntity;
        // Eturlia start - a lookup from outside a region tick reads no captured state
        // capturedTileEntities only ever holds anything in the middle of a block placement, which
        // happens inside a region tick. A caller that is not in one - the global region draining a
        // mod's deferred task, for one - has no captured state to read, and answering with the
        // chunk is both correct and quiet, where reaching into a null holder is a crash.
        final io.papermc.paper.threadedregions.RegionizedWorldData eturliaWorldData = this.getCurrentWorldData();
        if (eturliaWorldData != null && !eturliaWorldData.capturedTileEntities.isEmpty() && (blockEntity = eturliaWorldData.capturedTileEntities.get(blockposition)) != null) { // Folia - region threading
            return blockEntity;
        }
        // Eturlia end - a lookup from outside a region tick reads no captured state""",
        "Level.getBlockEntity survives off-region lookups",
    )


# ----------------------------------------------------------------- quarantine

def install_light_engine_fields():
    """Paper's light rewrite deleted two fields off LevelLightEngine. Sable reads one of them.

    Moonrise replaces vanilla's `blockEngine` and `skyEngine` with a single `lightEngine`
    (StarLightInterface). A field that is gone is not a compile error for a mod - it is a
    NoSuchFieldError the first time the code runs, and on Folia that means the region dies and the
    server shuts down:

        Class net.minecraft.world.level.lighting.LevelLightEngine
        does not have member field 'net.minecraft.world.level.lighting.LightEngine blockEngine'
          -> Region #16 failed to tick, on /sable spawn joint_test

    That is Create Aeronautics' physics sub-level asking for the light engine of the plot it just
    made. The fields exist again and answer null: the sub-level is lit by Starlight through the
    parent level, so there is no per-plot engine to hand back, and null is a value a mod can survive.
    """
    print("light engine field plane")
    replace(
        SERVER + "/net/minecraft/world/level/lighting/LevelLightEngine.java",
        """    public static final int LIGHT_SECTION_PADDING = 1;
    protected final LevelHeightAccessor levelHeightAccessor;""",
        """    public static final int LIGHT_SECTION_PADDING = 1;
    protected final LevelHeightAccessor levelHeightAccessor;
    // Eturlia start - the fields Paper's light rewrite removed
    // Mods still read these two; Starlight has one engine instead of two and keeps it in
    // `lightEngine` below. A read of null is survivable. A NoSuchFieldError kills the region.
    @Nullable public final LightEngine<?, ?> blockEngine = null;
    @Nullable public final LightEngine<?, ?> skyEngine = null;
    // Eturlia end - the fields Paper's light rewrite removed""",
        "LevelLightEngine keeps blockEngine and skyEngine",
    )


def install_particle_probe():
    """Name every particle the server sends, on demand. Off unless asked for.

    "Something draws blue trails behind the player" is unanswerable from the outside: a particle
    leaves no trace in any log, and the same picture can come from a plugin, a server mod, or a mod
    that only exists on the client. This says which - `-Deturlia.debug.particles=true` prints each
    distinct particle type the server sends, with where and how many, at most once every ten seconds
    per type. Nothing in the log means nothing was sent, and then whatever is on screen is drawn by
    the client alone.
    """
    print("particle probe plane")
    replace(
        SERVER + "/net/minecraft/server/level/ServerLevel.java",
        "        ClientboundLevelParticlesPacket packetplayoutworldparticles = new ClientboundLevelParticlesPacket(t0, force, d0, d1, d2, (float) d3, (float) d4, (float) d5, (float) d6, i);",
        """        // Eturlia start - say what is being sent, when asked
        if (ServerLevel.ETURLIA_PARTICLE_PROBE) {
            ServerLevel.eturlia$noteParticle(t0, d0, d1, d2, i);
        }
        // Eturlia end - say what is being sent, when asked
        ClientboundLevelParticlesPacket packetplayoutworldparticles = new ClientboundLevelParticlesPacket(t0, force, d0, d1, d2, (float) d3, (float) d4, (float) d5, (float) d6, i);""",
        "ServerLevel notes the particles it sends",
    )
    replace(
        SERVER + "/net/minecraft/server/level/ServerLevel.java",
        "    public <T extends ParticleOptions> int sendParticles(T particle, double x, double y, double z, int count, double deltaX, double deltaY, double deltaZ, double speed) {",
        """    // Eturlia start - the particle probe
    public static final boolean ETURLIA_PARTICLE_PROBE = Boolean.getBoolean("eturlia.debug.particles");
    private static final java.util.Map<String, Long> ETURLIA_PARTICLE_SEEN = new java.util.concurrent.ConcurrentHashMap<>();

    private static void eturlia$noteParticle(ParticleOptions particle, double x, double y, double z, int count) {
        try {
            String name = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()).toString();
            long now = System.currentTimeMillis();
            Long last = ETURLIA_PARTICLE_SEEN.get(name);
            if (last != null && now - last.longValue() < 10000L) {
                return;
            }
            ETURLIA_PARTICLE_SEEN.put(name, Long.valueOf(now));
            MinecraftServer.LOGGER.info("Eturlia particle probe: {} x{} at {}, {}, {}", name, Integer.valueOf(count),
                Long.valueOf(Math.round(x)), Long.valueOf(Math.round(y)), Long.valueOf(Math.round(z)));
        } catch (Throwable ignored) {
        }
    }
    // Eturlia end - the particle probe

    public <T extends ParticleOptions> int sendParticles(T particle, double x, double y, double z, int count, double deltaX, double deltaY, double deltaZ, double speed) {""",
        "ServerLevel carries the particle probe",
    )


def install_sublevel_chunk_loads():
    """A chunk no region owns can be loaded by whoever asks. Folia refused, and that aborted the JVM.

    Folia only lets the region that owns a chunk load it synchronously. Create Aeronautics builds
    its physics sub-level from the player's region thread, and the sub-level's chunks belong to no
    region at all - nothing is ticking them, because the level has just been made. The guard fired
    anyway:

        IllegalStateException: Cannot asynchronously load chunks     (on /sable spawn joint_test)

    What that costs is out of proportion: sable's native side is left half-built, Rapier's next call
    panics ("No rigid body for id") inside a function that cannot unwind, and the JVM aborts. Every
    attempt at a physics airship took the whole server down, and the wrapper restarted it.

    The rule is unchanged where it matters - a chunk another region owns is still refused. It is
    relaxed only when the regioniser says nobody owns the chunk, which is exactly the sub-level case
    and cannot race with a tick that does not exist. `-Deturlia.compat.sublevel-chunks=strict`
    restores Folia's refusal.
    """
    print("sublevel chunk load plane")
    replace(
        SERVER + "/net/minecraft/server/level/ServerChunkCache.java",
        """    private ChunkAccess syncLoad(final int chunkX, final int chunkZ, final ChunkStatus toStatus) {
        // Folia start - region threading
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Cannot asynchronously load chunks");
        }
        // Folia end - region threading""",
        """    // Eturlia start - a chunk no region owns is nobody else's to tick
    private static boolean eturlia$mayLoadFromThisThread(final ServerLevel level, final int chunkX, final int chunkZ) {
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
            return true;
        }
        if ("strict".equalsIgnoreCase(System.getProperty("eturlia.compat.sublevel-chunks", "lenient"))) {
            return false;
        }
        try {
            // No region over the chunk means no tick over the chunk: there is nothing to race with.
            return level.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) == null;
        } catch (Throwable thrown) {
            return false;
        }
    }
    // Eturlia end - a chunk no region owns is nobody else's to tick

    private ChunkAccess syncLoad(final int chunkX, final int chunkZ, final ChunkStatus toStatus) {
        // Folia start - region threading
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()
            && !ServerChunkCache.eturlia$mayLoadFromThisThread(this.level, chunkX, chunkZ)) { // Eturlia - unowned chunks are loadable
            ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Cannot asynchronously load chunks");
        }
        // Folia end - region threading""",
        "ServerChunkCache loads chunks no region owns",
    )


def install_chunk_access_get_level():
    """`ChunkAccess.getLevel()` - the accessor mods reach for on any chunk, not just a loaded one.

        NoSuchMethodError: 'net.minecraft.world.level.Level ChunkAccess.getLevel()'
          -> Region #1 failed to tick, on /sable spawn joint_test

    Only `LevelChunk` declares it here, so a mod holding the `ChunkAccess` supertype - which is what
    a chunk looks like while a sub-level is being built - links to nothing. The base class answers
    now: the level for a chunk that has one, null for a proto-chunk that does not.
    """
    print("chunk access level plane")
    replace(
        SERVER + "/net/minecraft/world/level/chunk/ChunkAccess.java",
        "public abstract class ChunkAccess implements BlockGetter, BiomeManager.NoiseBiomeSource, LightChunk, StructureAccess, ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk { // Paper - rewrite chunk system",
        """public abstract class ChunkAccess implements BlockGetter, BiomeManager.NoiseBiomeSource, LightChunk, StructureAccess, ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk { // Paper - rewrite chunk system

    // Eturlia start - every chunk can be asked which level it belongs to
    // LevelChunk overrides this with the real answer. A proto-chunk is not in a level yet and says
    // so; a mod that only holds the supertype used to link to nothing at all.
    @Nullable
    public net.minecraft.world.level.Level getLevel() {
        return null;
    }
    // Eturlia end - every chunk can be asked which level it belongs to""",
        "ChunkAccess answers getLevel",
    )


def install_chunk_status_listener_default():
    """A mod building a chunk map for its own level has no status listener to hand in. Accept that.

    `ChunkMap` keeps the `ChunkStatusUpdateListener` it is constructed with and calls it whenever a
    chunk changes full-status. The server always passes one. Create Aeronautics' physics sub-levels
    build their own chunk map through sable and pass null, and the first status change then throws:

        NullPointerException: Cannot invoke ChunkStatusUpdateListener.onChunkStatusChange(...)
        because "this.chunkStatusListener" is null            (on /sable spawn joint_test)

    That NPE is worse than it looks. It leaves sable's native physics half-built, so Rapier's next
    call panics with "No rigid body for id" - inside a function that cannot unwind, which aborts the
    whole JVM. Every attempt at a physics airship took the server down with it.

    A missing listener means nobody is listening. That is a no-op, not a crash.
    """
    print("chunk status listener plane")
    replace(
        SERVER + "/net/minecraft/server/level/ChunkMap.java",
        "        this.chunkStatusListener = chunkStatusChangeListener;",
        "        // Eturlia - a mod's own level has nobody to notify; that is a no-op, not a null\n"
        "        this.chunkStatusListener = chunkStatusChangeListener != null ? chunkStatusChangeListener\n"
        "            : (net.minecraft.world.level.entity.ChunkStatusUpdateListener) (eturliaChunkPos, eturliaStatus) -> { };",
        "ChunkMap tolerates a level with no status listener",
    )


def install_chunk_holder_futures():
    """The three chunk futures Moonrise's rewrite removed, which mods still inherit and read.

        NoSuchFieldError: Class dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder
        does not have member field 'java.util.concurrent.CompletableFuture tickingChunkFuture'
          -> Region #1 failed to tick, on /sable spawn joint_test

    A subclass reading an inherited field it can see in the vanilla jar is not something the
    compiler can warn about. The fields are back, and they start out as the "unloaded" future the
    class already keeps: a mod that reads one gets an immediate, well-formed answer instead of a
    null, and a mod that keeps its own chunk state (sable does) simply assigns over them.
    """
    print("chunk holder futures plane")
    replace(
        SERVER + "/net/minecraft/server/level/ChunkHolder.java",
        """    private static final CompletableFuture<ChunkResult<LevelChunk>> UNLOADED_LEVEL_CHUNK_FUTURE = CompletableFuture.completedFuture(ChunkHolder.UNLOADED_LEVEL_CHUNK);""",
        """    private static final CompletableFuture<ChunkResult<LevelChunk>> UNLOADED_LEVEL_CHUNK_FUTURE = CompletableFuture.completedFuture(ChunkHolder.UNLOADED_LEVEL_CHUNK);
    // Eturlia start - the chunk futures Paper's rewrite removed
    // Moonrise tracks chunk state in its own holder and deleted these three. Mods that subclass
    // ChunkHolder - Create Aeronautics' sublevels through sable's PlotChunkHolder - still read
    // them, and a missing inherited field is a NoSuchFieldError at first touch, which on Folia
    // takes the region and then the server with it.
    public volatile CompletableFuture<ChunkResult<LevelChunk>> fullChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
    public volatile CompletableFuture<ChunkResult<LevelChunk>> tickingChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
    public volatile CompletableFuture<ChunkResult<LevelChunk>> entityTickingChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
    // Eturlia end - the chunk futures Paper's rewrite removed""",
        "ChunkHolder keeps the three chunk futures",
    )


def install_chunk_section_vanilla_ctor():
    """CraftBukkit narrowed LevelChunkSection's constructor; a mod calls the vanilla one.

    Vanilla takes `PalettedContainerRO<Holder<Biome>>` for the biomes; CraftBukkit's copy takes the
    concrete `PalettedContainer`. Same name, different descriptor, so a mod compiled against vanilla
    links to nothing:

        NoSuchMethodError: 'void LevelChunkSection.<init>(PalettedContainer, PalettedContainerRO)'
          -> Region #1 failed to tick, on /sable spawn joint_test

    That is sable building the chunk sections of a physics sub-level. The vanilla shape exists again
    and hands the concrete container through; a read-only container that is not one already is
    copied cell by cell - a biome container is 4x4x4, so that costs nothing.
    """
    print("chunk section ctor plane")
    replace(
        SERVER + "/net/minecraft/world/level/chunk/LevelChunkSection.java",
        """    public LevelChunkSection(PalettedContainer<BlockState> datapaletteblock, PalettedContainer<Holder<Biome>> palettedcontainerro) {
        // CraftBukkit end
        this.states = datapaletteblock;
        this.biomes = palettedcontainerro;
        this.recalcBlockCounts();
    }""",
        """    public LevelChunkSection(PalettedContainer<BlockState> datapaletteblock, PalettedContainer<Holder<Biome>> palettedcontainerro) {
        // CraftBukkit end
        this.states = datapaletteblock;
        this.biomes = palettedcontainerro;
        this.recalcBlockCounts();
    }

    // Eturlia start - vanilla's own constructor shape, which CraftBukkit narrowed
    public LevelChunkSection(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        this(states, LevelChunkSection.eturlia$asPalette(biomes));
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<Holder<Biome>> eturlia$asPalette(PalettedContainerRO<Holder<Biome>> source) {
        if (source instanceof PalettedContainer) {
            return (PalettedContainer<Holder<Biome>>) source;
        }
        PalettedContainer<Holder<Biome>> target = source.recreate();
        for (int y = 0; y < 4; ++y) {
            for (int z = 0; z < 4; ++z) {
                for (int x = 0; x < 4; ++x) {
                    target.set(x, y, z, source.get(x, y, z));
                }
            }
        }
        return target;
    }
    // Eturlia end - vanilla's own constructor shape, which CraftBukkit narrowed""",
        "LevelChunkSection has vanilla's constructor",
    )


def install_read_timeout():
    """Thirty seconds of silence is not proof a client is gone. On a 90-mod pack it is one stall.

    Netty drops a connection that has sent nothing for 30 seconds, and the server reports it as
    "lost connection: Timed out". That number is hard-coded in vanilla and is not the configurable
    keepalive - raising `paper.playerconnection.keepalive` does nothing for it. A client loading a
    pack this size, on a weak machine or a software renderer, blocks its main thread for longer than
    that while it builds the world, and gets thrown out for it: the test client here never survived
    its first minute, and a player on an old laptop hits exactly the same wall.

    `-Deturlia.compat.read-timeout=<seconds>` sets it; 0 removes the handler entirely.
    """
    print("read timeout plane")
    field = """    // Eturlia start - a slow client is not a gone client
    public static int eturlia$readTimeoutSeconds() {
        try {
            return Math.max(0, Integer.getInteger("eturlia.compat.read-timeout", 90));
        } catch (Throwable ignored) {
            return 90;
        }
    }
    // Eturlia end - a slow client is not a gone client
"""
    for path, anchor in (
        (SERVER + "/net/minecraft/server/network/ServerConnectionListener.java",
         '                    ChannelPipeline channelpipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));'),
        (SERVER + "/net/minecraft/network/Connection.java",
         '                ChannelPipeline channelpipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));'),
    ):
        indent = anchor[: len(anchor) - len(anchor.lstrip())]
        replace(
            path,
            anchor,
            "%s// Eturlia - the read timeout is configurable; see eturlia.compat.read-timeout\n"
            "%sint eturliaReadTimeout = net.minecraft.server.network.ServerConnectionListener.eturlia$readTimeoutSeconds();\n"
            "%sChannelPipeline channelpipeline = eturliaReadTimeout > 0\n"
            "%s    ? channel.pipeline().addLast(\"timeout\", new ReadTimeoutHandler(eturliaReadTimeout))\n"
            "%s    : channel.pipeline();"
            % (indent, indent, indent, indent, indent),
            "read timeout in %s" % path.rsplit("/", 1)[-1],
        )
    replace(
        SERVER + "/net/minecraft/server/network/ServerConnectionListener.java",
        "public class ServerConnectionListener {\n",
        "public class ServerConnectionListener {\n\n" + field,
        "ServerConnectionListener carries the read timeout setting",
    )


def install_dispatch_timing():
    """Measure how long a deferred "main thread" task waits before it runs.

    Players report Create machinery behaving as if the threads talk to each other slowly - an item
    thrown at a player lands on them ten seconds later. Every mod task that cannot run inline goes
    through eturlia$runAsMainThread, so that is where the waiting would be. This counts it: how many
    tasks, the average wait, the worst wait, and how many threw, once every 30 seconds and only when
    there is something to say. `-Deturlia.compat.dispatch-timing=off` turns it off.
    """
    print("dispatch timing plane")
    replace(
        SERVER + "/net/minecraft/server/MinecraftServer.java",
        """    // A deferred task that throws must not kill the region running it: on Folia that is a
    // whole-server shutdown, and the mod that queued the task is long gone from the stack.
    public static void eturlia$runGuarded(Runnable runnable) {""",
        """    // Eturlia start - how long a deferred task waits before it runs
    // Two queues can hold a mod's task: the region that owns the player who sent the packet, and
    // the global region for everything else. A task waiting on either is a mod not reacting, and
    // from in-game that reads as the machine being slow rather than as anything being wrong.
    private static final boolean ETURLIA_DISPATCH_TIMING =
        !"off".equalsIgnoreCase(System.getProperty("eturlia.compat.dispatch-timing", "on"));
    private static final String[] ETURLIA_DISPATCH_NAMES = {"player region", "global region"};
    private static final java.util.concurrent.atomic.AtomicLongArray ETURLIA_DISPATCH_COUNT = new java.util.concurrent.atomic.AtomicLongArray(2);
    private static final java.util.concurrent.atomic.AtomicLongArray ETURLIA_DISPATCH_WAITED = new java.util.concurrent.atomic.AtomicLongArray(2);
    private static final java.util.concurrent.atomic.AtomicLongArray ETURLIA_DISPATCH_WORST = new java.util.concurrent.atomic.AtomicLongArray(2);
    private static final java.util.concurrent.atomic.AtomicLong ETURLIA_DISPATCH_REPORTED = new java.util.concurrent.atomic.AtomicLong();

    public static void eturlia$runDeferred(Runnable runnable, long queuedAt, int path) {
        if (!ETURLIA_DISPATCH_TIMING) {
            MinecraftServer.eturlia$runGuarded(runnable);
            return;
        }
        long waited = System.nanoTime() - queuedAt;
        ETURLIA_DISPATCH_COUNT.incrementAndGet(path);
        ETURLIA_DISPATCH_WAITED.addAndGet(path, waited);
        ETURLIA_DISPATCH_WORST.accumulateAndGet(path, waited, Math::max);
        try {
            MinecraftServer.eturlia$runGuarded(runnable);
        } finally {
            MinecraftServer.eturlia$reportDispatch();
        }
    }

    private static void eturlia$reportDispatch() {
        long now = System.currentTimeMillis();
        long last = ETURLIA_DISPATCH_REPORTED.get();
        if (now - last < 30000L || !ETURLIA_DISPATCH_REPORTED.compareAndSet(last, now)) {
            return;
        }
        for (int path = 0; path < 2; ++path) {
            long count = ETURLIA_DISPATCH_COUNT.getAndSet(path, 0L);
            long waited = ETURLIA_DISPATCH_WAITED.getAndSet(path, 0L);
            long worst = ETURLIA_DISPATCH_WORST.getAndSet(path, 0L);
            if (count == 0L) {
                continue;
            }
            long average = (waited / count) / 1000000L;
            long worstMillis = worst / 1000000L;
            // One tick is 50ms. Anything a player would notice is many times that.
            if (worstMillis >= 250L) {
                MinecraftServer.LOGGER.warn("Eturlia: {} deferred tasks on the {} in 30s, average wait {}ms, worst {}ms",
                    count, ETURLIA_DISPATCH_NAMES[path], average, worstMillis);
            }
        }
    }
    // Eturlia end - how long a deferred task waits before it runs

    // A deferred task that throws must not kill the region running it: on Folia that is a
    // whole-server shutdown, and the mod that queued the task is long gone from the stack.
    public static void eturlia$runGuarded(Runnable runnable) {""",
        "MinecraftServer counts what deferred tasks wait",
    )
    replace(
        SERVER + "/net/minecraft/server/MinecraftServer.java",
        """                    regionized.taskQueue.queueTickTaskQueue(
                        level, pos.getX() >> 4, pos.getZ() >> 4,
                        () -> MinecraftServer.eturlia$runGuarded(runnable)
                    );""",
        """                    long eturliaQueuedAt = System.nanoTime(); // Eturlia - timed, see eturlia$runDeferred
                    regionized.taskQueue.queueTickTaskQueue(
                        level, pos.getX() >> 4, pos.getZ() >> 4,
                        () -> MinecraftServer.eturlia$runDeferred(runnable, eturliaQueuedAt, 0)
                    );""",
        "the player-region queue is timed",
    )
    replace(
        SERVER + "/net/minecraft/server/MinecraftServer.java",
        """        if (regionized != null) {
            regionized.addTask(() -> MinecraftServer.eturlia$runGuarded(runnable));
            return;
        }""",
        """        if (regionized != null) {
            long eturliaQueuedAt = System.nanoTime(); // Eturlia - timed, see eturlia$runDeferred
            regionized.addTask(() -> MinecraftServer.eturlia$runDeferred(runnable, eturliaQueuedAt, 1));
            return;
        }""",
        "the global-region queue is timed",
    )


def install_quarantine():
    """Mods that replace the same internals Paper already replaced cannot be reconciled."""
    print("quarantine plane")
    replace(
        ETURLIA + "/eturlia/launch/EturliaModsFolderHygiene.java",
        """        if (isOriginalArclightSable(lower)) {""",
        """        if (isStateTableReplacement(lower)) {
            skip(jar, mode, "it replaces the block-state tables Paper already replaced"
                    + " (Blocks.<clinit> dies with \\"index_table is null\\"). Paper's own tables"
                    + " already give you the memory saving this mod is for.");
            return;
        }
        if (isOriginalArclightSable(lower)) {""",
        "hygiene skips block-state table replacements",
    )
    replace(
        ETURLIA + "/eturlia/launch/EturliaModsFolderHygiene.java",
        """    private static boolean isOriginalArclightSable(String lower) {""",
        """    /**
     * Mods that reimplement {@code StateHolder}'s neighbour tables.
     *
     * <p>Paper replaced those tables with {@code ZeroCollidingReferenceStateTable}, so a mod that
     * swaps in its own is writing into a structure that no longer exists — the failure surfaces
     * far from the cause, inside {@code Blocks}' static initialiser. There is nothing to patch on
     * either side: both implementations are correct, and only one can own the table.</p>
     */
    private static boolean isStateTableReplacement(String lower) {
        String extra = System.getProperty("eturlia.compat.quarantine", "");
        for (String id : extra.split(",")) {
            String trimmed = id.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && lower.startsWith(trimmed)) {
                return true;
            }
        }
        return lower.startsWith("ferritecore");
    }

    private static boolean isOriginalArclightSable(String lower) {""",
        "hygiene knows which mods those are",
    )


if __name__ == "__main__":
    install_mixin_compat()
    install_plugin_compat()
    install_registry_compat()
    install_item_extension()
    install_item_stack_extension()
    install_configuration_listener_shape()
    install_extensible_enums()
    install_recipe_book_settings()
    install_bukkit_type_bridges()
    install_capability_accessors()
    install_data_serializers()
    install_tick_count()
    install_tag_diagnostics()
    install_plugin_remapping()
    install_neoforge_patches()
    install_custom_ingredients()
    install_material_maps()
    install_reobf_server_jar()
    install_exception_collector()
    install_legacy_item_key()
    install_library_downloader()
    install_regionless_save()
    install_main_thread_dispatch()
    install_level_extension()
    install_wrapper_level_compat()
    install_off_region_world_data()
    install_console_command_errors()
    install_modded_material_bridge()
    install_level_subclass_compat()
    install_level_is_subclassable()
    install_subclassable_core()
    install_folia_disabled_commands()
    install_guest_level_ctor()
    install_missing_interface_defaults()
    install_block_state_without_tile()
    install_lenient_schedulers()
    install_plugin_context_loader()
    install_builtin_pack_source()
    install_quiet_startup()
    install_modded_entity_wrappers()
    install_remap_fallbacks()
    install_spawn_egg_compat()
    install_container_defaults()
    install_portal_compat()
    install_packet_thread_routing()
    install_light_engine_fields()
    install_particle_probe()
    install_sublevel_chunk_loads()
    install_chunk_access_get_level()
    install_chunk_status_listener_default()
    install_chunk_holder_futures()
    install_chunk_section_vanilla_ctor()
    install_read_timeout()
    install_dispatch_timing()
    install_shape_compat()
    install_quarantine()
    print("done")
