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
            if (ETURLIA_REPORTED.add(key.get().location().toString())) {
                org.bukkit.Bukkit.getLogger().info("Eturlia: " + key.get().location()
                        + " is a modded entity, so plugins see it as UNKNOWN");
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
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            return new CraftLivingEntity(server, living);
        }
        return new EturliaUnknownEntity(server, entity);
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


# ----------------------------------------------------------------- quarantine

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
    install_regionless_save()
    install_main_thread_dispatch()
    install_shape_compat()
    install_quarantine()
    print("done")
