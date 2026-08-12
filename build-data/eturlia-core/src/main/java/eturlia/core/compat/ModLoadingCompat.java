package eturlia.core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Keeps one mod's loading failure from ending everyone else's boot.
 *
 * <p>NeoForge collects what went wrong per mod into a single list and, at the end of each loading
 * phase, throws if any entry is an error. On a stock NeoForge server that is right: the pack is
 * known-good and an error means something is genuinely broken. On Eturlia the usual error is a
 * mod calling a NeoForge helper that Folia's copy of the class does not carry —</p>
 *
 * <pre>
 * Caught exception during event AddPackFindersEvent dispatch for modid selling_bin —
 * NoSuchMethodError: BuiltInPackSource.fromName(Function)
 * </pre>
 *
 * <p>— and that one missing helper takes down a server that ninety other mods were happy with.
 * Here such an error is logged, named, and dropped: the mod loses whatever that call did, the
 * boot continues. Warnings are kept as warnings.</p>
 *
 * <p>{@code -Deturlia.compat.modloading=strict} restores NeoForge's behaviour.</p>
 */
public final class ModLoadingCompat {

    private static final Logger LOGGER = Logger.getLogger("Eturlia");
    private static final AtomicInteger DROPPED = new AtomicInteger();

    private ModLoadingCompat() {
    }

    /** How many mod loading errors were let through. */
    public static int dropped() {
        return DROPPED.get();
    }

    public static void install() {
        String mode = System.getProperty("eturlia.compat.modloading", "lenient")
                .toLowerCase(Locale.ROOT);
        if ("strict".equals(mode)) {
            return;
        }
        try {
            Class<?> modLoader = Class.forName("net.neoforged.fml.ModLoader");
            Field field = modLoader.getDeclaredField("loadingIssues");

            Object unsafe = unsafe();
            if (unsafe == null) {
                LOGGER.warning("mod loading compatibility unavailable — one mod's failure can "
                        + "still abort the boot");
                return;
            }
            Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
            Method putObject = unsafe.getClass()
                    .getMethod("putObject", Object.class, long.class, Object.class);
            Method getObject = unsafe.getClass().getMethod("getObject", Object.class, long.class);

            Object base = staticFieldBase.invoke(unsafe, field);
            long offset = (Long) staticFieldOffset.invoke(unsafe, field);

            Object current = getObject.invoke(unsafe, base, offset);
            if (current instanceof Forgiving) {
                return;
            }
            Forgiving replacement = new Forgiving();
            if (current instanceof Collection<?> existing) {
                for (Object issue : existing) {
                    replacement.add(issue);
                }
            }
            putObject.invoke(unsafe, base, offset, replacement);
            LOGGER.info("mod loading compatibility: a mod that fails to load will be skipped "
                    + "instead of stopping the server (-Deturlia.compat.modloading=strict to abort)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warning("could not install mod loading compatibility (" + e
                    + ") — one mod's failure can still abort the boot");
        }
    }

    // ------------------------------------------------------------ event buses

    private static volatile boolean busesWrapped;

    /**
     * Stops a mod's lifecycle event handler from taking the server down with it.
     *
     * <p>{@code ModContainer.acceptEvent} catches whatever a mod's listener throws and turns it
     * straight into a {@code ModLoadingException} — it never reaches the issue list this class
     * replaces. The listener runs on the mod's own event bus, so the fix is to give each mod a bus
     * whose {@code post} does not propagate: the mod loses that one handler, the server keeps
     * going.</p>
     *
     * <p>Called repeatedly from the mixin guard because mod containers only exist part way through
     * the boot, and there is no earlier moment to hook. It does its work once.</p>
     */
    static void wrapModEventBuses() {
        if (busesWrapped) {
            return;
        }
        if ("strict".equalsIgnoreCase(System.getProperty("eturlia.compat.modloading", "lenient"))) {
            busesWrapped = true;
            return;
        }
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object list = modList.getMethod("get").invoke(null);
            if (list == null) {
                return;
            }
            Collection<?> containers = (Collection<?>) modList.getMethod("getSortedMods").invoke(list);
            if (containers == null || containers.isEmpty()) {
                return;
            }
            Class<?> busType = Class.forName("net.neoforged.bus.api.IEventBus");
            Object unsafe = unsafe();
            if (unsafe == null) {
                busesWrapped = true;
                return;
            }
            Method offsetOf = unsafe.getClass().getMethod("objectFieldOffset", Field.class);
            Method get = unsafe.getClass().getMethod("getObject", Object.class, long.class);
            Method put = unsafe.getClass()
                    .getMethod("putObject", Object.class, long.class, Object.class);

            // Through ModContainer, whose package is exported; the concrete container class is not.
            Method getModId = Class.forName("net.neoforged.fml.ModContainer").getMethod("getModId");

            int wrapped = 0;
            for (Object container : containers) {
                String modId = String.valueOf(getModId.invoke(container));
                for (Class<?> type = container.getClass(); type != null; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (!busType.isAssignableFrom(field.getType())) {
                            continue;
                        }
                        long offset = (Long) offsetOf.invoke(unsafe, field);
                        Object bus = get.invoke(unsafe, container, offset);
                        if (bus == null || java.lang.reflect.Proxy.isProxyClass(bus.getClass())) {
                            continue;
                        }
                        Object forgiving = java.lang.reflect.Proxy.newProxyInstance(
                                busType.getClassLoader(), new Class<?>[]{busType},
                                new ForgivingBus(bus, modId));
                        put.invoke(unsafe, container, offset, forgiving);
                        wrapped++;
                    }
                }
            }
            busesWrapped = true;
            if (wrapped > 0) {
                LOGGER.info("mod loading compatibility: " + wrapped + " mod event buses will report "
                        + "a broken handler instead of ending the boot");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            busesWrapped = true;
            LOGGER.warning("could not make mod event buses forgiving (" + e + ")");
        }
    }

    /** A mod's event bus, minus the ability to abort the server. */
    private static final class ForgivingBus implements java.lang.reflect.InvocationHandler {

        private final Object delegate;
        private final String modId;

        ForgivingBus(Object delegate, String modId) {
            this.delegate = delegate;
            this.modId = modId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(this.delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (!method.getName().equals("post")) {
                    throw cause;
                }
                DROPPED.incrementAndGet();
                LOGGER.warning("mod " + this.modId + " failed to handle "
                        + (args == null || args.length == 0 ? "an event"
                            : args[args.length - 1].getClass().getSimpleName())
                        + " (" + cause + "); that handler was skipped");
                // post returns the event it was given.
                return args == null || args.length == 0 ? null : args[args.length - 1];
            }
        }
    }

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

    /** NeoForge's issue list, minus the entries that would stop the boot. */
    private static final class Forgiving extends ArrayList<Object> {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean add(Object issue) {
            if (!isError(issue)) {
                return super.add(issue);
            }
            DROPPED.incrementAndGet();
            LOGGER.warning("a mod could not finish loading and was skipped: " + describe(issue));
            return true;
        }

        @Override
        public boolean addAll(Collection<?> issues) {
            boolean changed = false;
            for (Object issue : issues) {
                changed |= this.add(issue);
            }
            return changed;
        }

        @Override
        public boolean addAll(int index, Collection<?> issues) {
            return this.addAll(issues);
        }

        @Override
        public void add(int index, Object issue) {
            this.add(issue);
        }

        private static boolean isError(Object issue) {
            if (issue == null) {
                return false;
            }
            try {
                Object severity = issue.getClass().getMethod("severity").invoke(issue);
                return "ERROR".equals(String.valueOf(severity));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        private static String describe(Object issue) {
            String text = String.valueOf(issue);
            return text.length() > 300 ? text.substring(0, 300) + "…" : text;
        }
    }
}
