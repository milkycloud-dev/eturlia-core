package eturlia.core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Makes a mod's broken mixin cost that mod a feature instead of costing the server its boot.
 *
 * <p>Eturlia is Folia (so Paper) plus NeoForge. Paper rewrote hundreds of vanilla methods to fire
 * events, and a mod whose mixin aims at the vanilla shape finds nothing to inject into. Mixin
 * calls that a critical failure and aborts class loading, which aborts the boot. In a ninety-mod
 * pack that means one mismatched injector decides whether the server starts.</p>
 *
 * <p>Patching mods one at a time does not scale, so the core absorbs the mismatch instead. Two
 * levers, in order of preference:</p>
 *
 * <ol>
 *   <li><b>Relax the requirement.</b> An injector is only fatal because its {@code require} count
 *       is at least one. Almost no mod sets {@code require} on the annotation; the number comes
 *       from the mixin config's {@code injectors.defaultRequire}. Zeroing that on third-party
 *       configs turns "critical injection failure" into a log line, and Mixin carries on with
 *       whatever else the mixin had to offer.</li>
 *   <li><b>Contain the rest.</b> For the few injectors that hard-code {@code require = 1},
 *       failure is an {@link Error} thrown straight past Mixin's error handlers. The mixin
 *       launch plugin is wrapped so that such a class ends up loaded with whatever transformations
 *       did apply, rather than not loaded at all.</li>
 * </ol>
 *
 * <p>Mixins belonging to Eturlia, Folia, Paper or NeoForge are left strict — a failure there is
 * our bug and has to stay loud.</p>
 *
 * <p>{@code -Deturlia.compat.mixins=strict} restores stock behaviour;
 * {@code =relax} keeps only the first lever, without the launch-plugin wrapper.</p>
 */
public final class MixinCompat {

    private static final Logger LOGGER = Logger.getLogger("Eturlia");

    /** Config names whose failures are ours to fix, not to hide. */
    private static final Set<String> OURS = Set.of("eturlia", "folia", "paper", "neoforge");

    private static final AtomicBoolean RELAXED = new AtomicBoolean();
    private static final AtomicInteger CONTAINED = new AtomicInteger();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private MixinCompat() {
    }

    private static String mode() {
        return System.getProperty("eturlia.compat.mixins", "soft").toLowerCase(Locale.ROOT);
    }

    /**
     * Installs both levers. Safe to call more than once, and safe to call before mod mixin
     * configs are registered: the relaxation runs again on the first class that reaches the
     * mixin transformer, by which point every config is present.
     */
    public static void install() {
        if ("strict".equals(mode())) {
            LOGGER.info("mixin compatibility disabled (-Deturlia.compat.mixins=strict): "
                    + "one mod's failed injector will abort the boot");
            return;
        }
        relaxConfigs();
        if (!"relax".equals(mode())) {
            wrapMixinLaunchPlugin();
        }
    }

    /** How many classes were loaded despite a mixin failure. */
    public static int contained() {
        return CONTAINED.get();
    }

    // ------------------------------------------------------------------ lever 1

    /**
     * Sets {@code injectors.defaultRequire = 0} and clears {@code required} on every third-party
     * mixin config that is registered right now.
     *
     * <p>Reflection, because {@code MixinConfig} is package-private and its options are read from
     * the config JSON with no API to change them afterwards.</p>
     */
    public static void relaxConfigs() {
        int relaxed = 0;
        try {
            for (Object handle : registeredConfigs()) {
                Object config = configOf(handle);
                if (config == null) {
                    continue;
                }
                String name = nameOf(config);
                if (name == null || isOurs(name)) {
                    continue;
                }
                if (relax(config)) {
                    relaxed++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warning("could not relax mod mixin configs (" + e + ") — "
                    + "a mod with a failed injector may still abort the boot");
            return;
        }
        if (relaxed > 0 && RELAXED.compareAndSet(false, true)) {
            LOGGER.info("mixin compatibility: " + relaxed + " mod mixin configs no longer treat a "
                    + "failed injector as fatal (-Deturlia.compat.mixins=strict to restore)");
        }
    }

    /**
     * The registered mixin configs.
     *
     * <p>{@code Mixins.getConfigs()} hands back {@code transformer.Config} objects, and that
     * package is neither exported nor open, so calling even a public method on one is refused —
     * reflection checks the declaring class, not the modifier. Every read below therefore goes
     * through a field, which {@code Unsafe} can reach whatever the module system thinks.</p>
     */
    private static Iterable<?> registeredConfigs() throws ReflectiveOperationException {
        // Not Mixins.getConfigs(): the processor removes each config from that set the moment it
        // selects it, so by the time classes are being transformed the set is empty. Config keeps
        // every handle it ever made in a static map, which is what is wanted here.
        // Mixin's own loader, not ours — loading these classes through the wrong one would give a
        // second copy with empty statics, indistinguishable from "no mod uses mixins".
        Class<?> config = Class.forName(
                "org.spongepowered.asm.mixin.transformer.Config", true, mixinLoader());
        Object all = getStatic(config, "allConfigs");
        if (all instanceof java.util.Map<?, ?> map) {
            return new java.util.ArrayList<>(map.values());
        }
        Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader());
        Object configs = mixins.getMethod("getConfigs").invoke(null);
        return configs instanceof Iterable<?> iterable ? iterable : java.util.List.of();
    }

    /** Reads a static field of a class the module system will not let us touch normally. */
    private static Object getStatic(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            try {
                field.setAccessible(true);
                return field.get(null);
            } catch (RuntimeException inaccessible) {
                Object base = UNSAFE.getClass().getMethod("staticFieldBase", Field.class)
                        .invoke(UNSAFE, field);
                long offset = (Long) UNSAFE.getClass()
                        .getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, field);
                return UNSAFE.getClass().getMethod("getObject", Object.class, long.class)
                        .invoke(UNSAFE, base, offset);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Set when the launch plugin is wrapped; that object comes from Mixin's own module. */
    private static volatile ClassLoader mixinLoader;

    private static ClassLoader mixinLoader() {
        ClassLoader loader = mixinLoader;
        return loader != null ? loader : MixinCompat.class.getClassLoader();
    }

    /** The {@code MixinConfig} behind a {@code Config} handle. */
    private static Object configOf(Object handle) {
        try {
            return getObject(handle, handle.getClass().getDeclaredField("config"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** A config's resource name, e.g. {@code vinery-common.mixins.json}. */
    private static String nameOf(Object config) {
        try {
            Object name = getObject(config, config.getClass().getDeclaredField("name"));
            return name == null ? null : name.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean relax(Object config) {
        boolean changed = false;
        Class<?> type = config.getClass();
        try {
            changed |= setBoolean(config, type.getDeclaredField("required"), false);
            setObject(config, type.getDeclaredField("requiredValue"), Boolean.FALSE);

            Object injectors = getObject(config, type.getDeclaredField("injectorOptions"));
            if (injectors != null) {
                changed |= setInt(injectors,
                        injectors.getClass().getDeclaredField("defaultRequireValue"), 0);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A different Mixin build may not have these fields; containment still applies.
            if (FIELD_TROUBLE.compareAndSet(false, true)) {
                LOGGER.warning("could not read this Mixin build's config fields (" + e
                        + ") — mod injectors stay strict, failures are contained instead");
            }
            return false;
        }
        return changed;
    }

    // MixinConfig lives in a package the Mixin module does not open, and Eturlia runs in the same
    // module layer, so setAccessible is refused. Unsafe reaches the field regardless; it is the
    // only way to change a config that is otherwise write-once from JSON.
    private static final AtomicBoolean FIELD_TROUBLE = new AtomicBoolean();
    private static final Object UNSAFE = unsafe();

    private static Object unsafe() {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field field = type.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static long offsetOf(Field field) throws ReflectiveOperationException {
        Method offset = UNSAFE.getClass().getMethod("objectFieldOffset", Field.class);
        return (Long) offset.invoke(UNSAFE, field);
    }

    private static boolean setBoolean(Object owner, Field field, boolean value)
            throws ReflectiveOperationException {
        try {
            field.setAccessible(true);
            if (field.getBoolean(owner) == value) {
                return false;
            }
            field.setBoolean(owner, value);
            return true;
        } catch (RuntimeException inaccessible) {
            UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class)
                    .invoke(UNSAFE, owner, offsetOf(field), value);
            return true;
        }
    }

    private static boolean setInt(Object owner, Field field, int value)
            throws ReflectiveOperationException {
        try {
            field.setAccessible(true);
            if (field.getInt(owner) == value) {
                return false;
            }
            field.setInt(owner, value);
            return true;
        } catch (RuntimeException inaccessible) {
            UNSAFE.getClass().getMethod("putInt", Object.class, long.class, int.class)
                    .invoke(UNSAFE, owner, offsetOf(field), value);
            return true;
        }
    }

    private static void setObject(Object owner, Field field, Object value)
            throws ReflectiveOperationException {
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (RuntimeException inaccessible) {
            UNSAFE.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(UNSAFE, owner, offsetOf(field), value);
        }
    }

    private static Object getObject(Object owner, Field field) throws ReflectiveOperationException {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (RuntimeException inaccessible) {
            return UNSAFE.getClass().getMethod("getObject", Object.class, long.class)
                    .invoke(UNSAFE, owner, offsetOf(field));
        }
    }

    // ------------------------------------------------------------------ lever 2

    /**
     * Replaces ModLauncher's {@code mixin} launch plugin with one that survives a failed class.
     *
     * <p>Mixin's own error handlers only see {@code InvalidMixinException}. A failed
     * {@code require} throws {@code InjectionError}, which travels straight up through
     * ModLauncher and stops the class from loading at all. Here it is caught, named, and the
     * class is handed on with whatever mixins did apply.</p>
     */
    private static void wrapMixinLaunchPlugin() {
        try {
            Class<?> launcher = Class.forName("cpw.mods.modlauncher.Launcher");
            Object instance = launcher.getField("INSTANCE").get(null);

            Field pluginsField = launcher.getDeclaredField("launchPlugins");
            pluginsField.setAccessible(true);
            Object handler = pluginsField.get(instance);

            Field map = handler.getClass().getDeclaredField("plugins");
            map.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) map.get(handler);

            Object mixin = plugins.get("mixin");
            if (mixin == null) {
                return;
            }
            if (Proxy.isProxyClass(mixin.getClass())) {
                return;
            }
            mixinLoader = mixin.getClass().getClassLoader();

            Class<?> service = Class.forName("cpw.mods.modlauncher.serviceapi.ILaunchPluginService");
            Object wrapper = Proxy.newProxyInstance(
                    service.getClassLoader(), new Class<?>[]{service}, new Guard(mixin));

            Map<String, Object> replacement = new HashMap<>(plugins);
            replacement.put("mixin", wrapper);
            map.set(handler, replacement);
            LOGGER.info("mixin compatibility: a class whose mixin fails outright will now load "
                    + "with the transformations that did apply");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warning("could not wrap the mixin launch plugin (" + e + ") — a mod with a "
                    + "hard-required injector can still abort the boot");
        }
    }

    /** Delegates everything to the real mixin plugin, and refuses to let it fail a class. */
    private static final class Guard implements InvocationHandler {

        private final Object delegate;

        Guard(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // ModLauncher calls processClassWithFlags, a default method that ends up in
            // processClass; both names have to be guarded or the failure sails straight past.
            boolean processing = method.getName().startsWith("processClass");
            if (processing) {
                // Configs are all registered by the time the first class is transformed; earlier
                // calls from the launch handler may have seen none of them.
                if (!RELAXED.get()) {
                    relaxConfigs();
                    RELAXED.set(true);
                }
                // Mod containers only exist part way through the boot, and class loading is the
                // one thing still happening at that point.
                ModLoadingCompat.wrapModEventBuses();
            }
            // A failed mixin leaves the class node half-written, and half-written classes fail
            // verification at load. Keep the untouched shape so it can be put back.
            byte[] pristine = processing ? ClassNodes.snapshot(args) : null;
            try {
                return method.invoke(this.delegate, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (!processing) {
                    throw cause;
                }
                return contain(method, args, cause, pristine);
            }
        }

        /**
         * Drops the one mixin that failed and applies the rest.
         *
         * <p>Losing every mixin on a class is expensive: {@code ChunkGenerator} carries Lithostitched's
         * accessor interface, and a class loaded without it turns into a {@code ClassCastException}
         * the moment a world loads. So the failure is read for the mixin's own name, that mixin is
         * removed from its config, and the class is transformed again from its untouched shape.
         * Repeat while progress is being made; only if the mixin cannot be named does the whole
         * class fall back to no mixins at all.</p>
         */
        private Object contain(Method method, Object[] args, Throwable cause, byte[] pristine)
                throws Throwable {
            String target = describeTarget(args);
            // Containing failures on our own classes as well - when the blame frame belongs to a
            // mod, thrownByAnotherTransformer(cause) says so - was tried on 2026-08-12 and backed
            // out: it did not change what got contained at boot, and the client started timing out
            // half a minute after every join. Re-enable only with a join test to prove it.
            if (isOurs(String.valueOf(cause.getMessage())) || isOurs(target)) {
                throw cause;
            }

            if (DIAGNOSED.compareAndSet(false, true)) {
                String[] blamed = identify(cause);
                LOGGER.warning("mixin containment diagnostics: blame="
                        + (blamed == null ? "unidentified" : blamed[0] + " / " + blamed[1])
                        + " unsafe=" + (UNSAFE != null) + " configs=" + configCount()
                        + " first=" + summarise(cause.getCause() == null ? cause : cause.getCause()));
            }

            for (int attempt = 0; attempt < MAX_DROPS; attempt++) {
                String[] mixin = identify(cause);
                if (mixin == null || !dropMixin(mixin[0], mixin[1])) {
                    break;
                }
                DROPPED_MIXINS.incrementAndGet();
                LOGGER.warning("mixin " + mixin[1] + " (" + mixin[0] + ") could not be applied to "
                        + target + " and was dropped so the rest of that class's mixins still apply: "
                        + summarise(cause));
                ClassNodes.restore(args, pristine);
                try {
                    return method.invoke(this.delegate, args);
                } catch (InvocationTargetException retry) {
                    cause = retry.getCause() == null ? retry : retry.getCause();
                }
            }

            ClassNodes.restore(args, pristine);
            CONTAINED.incrementAndGet();
            if (REPORTED.add(target)) {
                LOGGER.warning("a mixin could not be applied to " + target + " (" + summarise(cause)
                        + "); the class was loaded without any mixins so the server can start. "
                        + "The mods that target that class may not work fully.");
            }
            return failureResult(method);
        }

        /** ASM's {@code ClassWriter.COMPUTE_FRAMES}: the class may carry half-applied mixins. */
        private static final int COMPUTE_FRAMES = 2;

        /** The value that means "keep going" for whichever {@code processClass} signature ran. */
        private static Object failureResult(Method method) {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) {
                return Boolean.TRUE;
            }
            if (returnType == int.class || returnType == Integer.class) {
                // processClassWithFlags returns ASM class-writer flags, not an enum, on
                // ModLauncher 11. Returning null here would NPE inside the caller.
                return Integer.valueOf(COMPUTE_FRAMES);
            }
            if (returnType.isEnum()) {
                for (Object constant : returnType.getEnumConstants()) {
                    // Frames must be recomputed: the class may carry half-applied mixins.
                    if ("COMPUTE_FRAMES".equals(String.valueOf(constant))) {
                        return constant;
                    }
                }
                Object[] constants = returnType.getEnumConstants();
                return constants.length == 0 ? null : constants[constants.length - 1];
            }
            return null;
        }

        private static String describeTarget(Object[] args) {
            if (args != null) {
                for (Object arg : args) {
                    if (arg == null) {
                        continue;
                    }
                    String type = arg.getClass().getName();
                    if (type.equals("org.objectweb.asm.Type")) {
                        return String.valueOf(arg);
                    }
                }
            }
            return "an unnamed class";
        }
    }

    /**
     * Copies an ASM {@code ClassNode} out and back, so a failed mixin can be undone.
     *
     * <p>Mixin writes into the node it is handed. When an injector throws half way through, the
     * node keeps whatever was written before the throw — new methods referring to callbacks that
     * were never added, frames that no longer match — and the JVM rejects it with a
     * {@code VerifyError} at class load. Restoring the shape the node had on the way in gives back
     * a class that is merely missing the mod's changes, which is the whole point of containment.</p>
     *
     * <p>All reflection: ASM lives in its own module layer and Eturlia's core does not compile
     * against it.</p>
     */
    private static final class ClassNodes {

        private ClassNodes() {
        }

        /** The lists {@code accept} appends to; everything else is overwritten on the way in. */
        private static final String[] APPENDED = {
            "methods", "fields", "innerClasses", "nestMembers", "permittedSubclasses",
            "recordComponents", "attrs", "visibleAnnotations", "invisibleAnnotations",
            "visibleTypeAnnotations", "invisibleTypeAnnotations",
        };

        static byte[] snapshot(Object[] args) {
            Object node = classNode(args);
            if (node == null) {
                return null;
            }
            try {
                ClassLoader loader = node.getClass().getClassLoader();
                Class<?> writerType = Class.forName("org.objectweb.asm.ClassWriter", true, loader);
                Object writer = writerType.getConstructor(int.class).newInstance(0);
                Class<?> visitorType = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
                node.getClass().getMethod("accept", visitorType).invoke(node, writer);
                return (byte[]) writerType.getMethod("toByteArray").invoke(writer);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static void restore(Object[] args, byte[] pristine) {
            Object node = classNode(args);
            if (node == null || pristine == null) {
                return;
            }
            try {
                for (String name : APPENDED) {
                    Field field = node.getClass().getField(name);
                    Object value = field.get(node);
                    if (value instanceof java.util.List<?> list) {
                        list.clear();
                    } else {
                        field.set(node, null);
                    }
                }
                ClassLoader loader = node.getClass().getClassLoader();
                Class<?> readerType = Class.forName("org.objectweb.asm.ClassReader", true, loader);
                Object reader = readerType.getConstructor(byte[].class).newInstance((Object) pristine);
                Class<?> visitorType = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
                readerType.getMethod("accept", visitorType, int.class).invoke(reader, node, 0);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warning("could not undo a failed mixin on a class (" + e
                        + "); it may fail to verify");
            }
        }

        private static Object classNode(Object[] args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (arg != null && arg.getClass().getName().equals("org.objectweb.asm.tree.ClassNode")) {
                    return arg;
                }
            }
            return null;
        }
    }

    // --------------------------------------------------------- dropping one mixin

    /** How many times a single failing mixin was removed so its neighbours could apply. */
    private static final AtomicInteger DROPPED_MIXINS = new AtomicInteger();

    /** A class with more broken mixins than this is not worth retrying one at a time. */
    private static final int MAX_DROPS = 8;

    /** One line, the first time containment happens, saying why a mixin could not be named. */
    private static final AtomicBoolean DIAGNOSED = new AtomicBoolean();

    /** How many mixin configs this build can actually see; zero means reflection is blocked. */
    private static int configCount() {
        int count = 0;
        try {
            for (Object handle : registeredConfigs()) {
                if (configOf(handle) != null) {
                    count++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
        return count;
    }

    /**
     * Mixin names the culprit in the failure text, always as {@code <config>.json:<MixinClass>} —
     * "in tt20.mixins.json:world.ServerLevelMixin from mod tt20", "in callback
     * betterend.mixins.common.json:LivingEntityMixin-&gt;@Inject". The wording around it varies by
     * failure type, so the config-and-class pair is what is matched. Nothing in the exception type
     * carries this, so the message is the only source.
     */
    private static final java.util.regex.Pattern BLAME = java.util.regex.Pattern.compile(
            "([A-Za-z0-9_.\\-]+\\.json):([A-Za-z0-9_$.]+)");

    /** The {@code {configName, mixinClass}} the failure blames, or null. */
    private static String[] identify(Throwable cause) {
        for (Throwable th = cause; th != null; th = th.getCause() == th ? null : th.getCause()) {
            String message = th.getMessage();
            if (message == null) {
                continue;
            }
            java.util.regex.Matcher matcher = BLAME.matcher(message);
            if (matcher.find()) {
                return new String[]{matcher.group(1), matcher.group(2)};
            }
        }
        return null;
    }

    /** Removes one mixin from its config so the next transform of the class leaves it out. */
    private static boolean dropMixin(String configName, String mixinName) {
        try {
            for (Object handle : registeredConfigs()) {
                Object config = configOf(handle);
                if (config == null || !configName.equals(nameOf(config))) {
                    continue;
                }
                boolean removed = removeFrom(
                        getObject(config, config.getClass().getDeclaredField("mixins")), mixinName);
                Object mapping = getObject(
                        config, config.getClass().getDeclaredField("mixinMapping"));
                if (mapping instanceof java.util.Map<?, ?> byTarget) {
                    for (Object list : byTarget.values()) {
                        removed |= removeFrom(list, mixinName);
                    }
                }
                return removed;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
        return false;
    }

    /** Drops every {@code MixinInfo} in {@code list} whose class matches {@code mixinName}. */
    private static boolean removeFrom(Object list, String mixinName) {
        if (!(list instanceof java.util.List<?> entries)) {
            return false;
        }
        boolean removed = false;
        for (java.util.Iterator<?> it = entries.iterator(); it.hasNext(); ) {
            Object info = it.next();
            String className;
            try {
                // Through IMixinInfo, which is exported — MixinInfo itself is not reachable.
                Class<?> iface = Class.forName(
                        "org.spongepowered.asm.mixin.extensibility.IMixinInfo", true, mixinLoader());
                className = String.valueOf(iface.getMethod("getClassName").invoke(info));
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            // The failure names the mixin as "package.Class" or just "Class".
            if (className.equals(mixinName) || className.endsWith("." + mixinName)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ shared

    /**
     * Whether someone else's code threw this, rather than mixin failing to apply our own work.
     *
     * <p>Class transformation is a shared pipeline: a mod's {@code ILaunchPluginService} sees every
     * class ModLauncher loads, ours included. libjf_unsafe walks the interfaces of each one and
     * throws when it cannot resolve a super class from the layer it looked in — and because the
     * class it tripped on was {@code io.papermc.paper.pluginremap.InsertManifestAttribute},
     * {@link #contain} treated it as our own bug, rethrew, and the plugin system died with it.
     * A failure carrying a third party's frames is contained even on our classes; a failure that
     * is entirely ours and mixin's still surfaces.</p>
     */
    private static boolean thrownByAnotherTransformer(Throwable cause) {
        for (Throwable th = cause; th != null; th = th.getCause()) {
            for (StackTraceElement frame : th.getStackTrace()) {
                String cls = frame.getClassName();
                if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
                        || cls.startsWith("org.objectweb.asm.")
                        || cls.startsWith("cpw.mods.")
                        || cls.startsWith("org.spongepowered.asm.")
                        || isOurs(cls)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isOurs(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ours : OURS) {
            if (lower.startsWith(ours)
                    || lower.contains("." + ours + ".")
                    || lower.contains("/" + ours + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String summarise(Throwable th) {
        if (th == null) {
            return "no detail";
        }
        String message = th.getMessage();
        if (message == null || message.isBlank()) {
            return th.getClass().getSimpleName();
        }
        int cut = message.indexOf(". ");
        String head = cut > 0 ? message.substring(0, cut) : message;
        return head.length() > 220 ? head.substring(0, 220) + "…" : head;
    }
}
