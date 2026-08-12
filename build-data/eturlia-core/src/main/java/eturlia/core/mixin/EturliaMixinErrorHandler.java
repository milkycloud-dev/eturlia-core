package eturlia.core.mixin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Keeps one mod's unusable mixin from taking the whole server down with it.
 *
 * <p>Eturlia is Folia plus NeoForge, and Folia inherits Paper's rewrites of vanilla methods.
 * Where Paper changed a call — {@code LivingEntity.hurt} invokes
 * {@code knockback(double, double, double, Entity, Cause)} so it can fire
 * {@code EntityKnockbackEvent}, not vanilla's {@code knockback(DDD)V} — a mod aiming at the
 * vanilla shape finds nothing to inject into. Mixin treats that as fatal by default, so a
 * single mismatched injector in a ninety-mod pack means the server never boots:</p>
 *
 * <pre>
 * InjectionError: Critical injection failure: Argument modifier method be_increaseKnockback(DDD)D
 * in betterend.mixins.common.json:LivingEntityMixin from mod betterend failed injection check,
 * (0/1) succeeded. Scanned 0 target(s).
 * </pre>
 *
 * <p>Downgrading that to a warning is a trade, not a fix: the mixin does not apply, so whatever
 * it added is missing, and the mod may misbehave later. That is still strictly better than a
 * server that cannot start, and every downgrade is named on the console so an operator knows
 * exactly which mod lost which mixin.</p>
 *
 * <p>Mixins belonging to Eturlia, Folia or NeoForge itself are never downgraded — a failure
 * there is our bug and must stay loud.</p>
 *
 * <p>Set {@code -Deturlia.mixin.errors=fatal} to restore stock behaviour, or
 * {@code =quiet} to keep the downgrade but drop the per-mixin console line.</p>
 */
public final class EturliaMixinErrorHandler implements IMixinErrorHandler {

    private static final Logger LOGGER = Logger.getLogger("Eturlia");

    /** Mixin configs whose failures are ours, and must not be downgraded. */
    private static final Set<String> OURS = Set.of("eturlia", "folia", "paper", "neoforge");

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger DOWNGRADED = new AtomicInteger();

    /** Mixin instantiates error handlers reflectively. */
    public EturliaMixinErrorHandler() {
    }

    /** How many mixins were allowed to fail so far. */
    public static int downgraded() {
        return DOWNGRADED.get();
    }

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return decide(config == null ? null : config.getName(), mixin, th, action, "prepare");
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        String config = mixin == null || mixin.getConfig() == null ? null : mixin.getConfig().getName();
        return decide(config, mixin, th, action, "apply to " + targetClassName);
    }

    private ErrorAction decide(String config, IMixinInfo mixin, Throwable th, ErrorAction action, String stage) {
        String mode = System.getProperty("eturlia.mixin.errors", "warn");
        if ("fatal".equalsIgnoreCase(mode) || action != ErrorAction.ERROR) {
            return action;
        }

        String name = mixin == null ? String.valueOf(config) : mixin.getClassName();
        if (isOurs(config) || isOurs(name)) {
            return action;
        }

        DOWNGRADED.incrementAndGet();
        if (!"quiet".equalsIgnoreCase(mode) && REPORTED.add(name)) {
            LOGGER.warning("mixin " + name + " could not " + stage
                    + " and was skipped so the server can start (" + describe(th) + "). "
                    + "That mod may not work fully; -Deturlia.mixin.errors=fatal to abort instead.");
        }
        return ErrorAction.WARN;
    }

    private static boolean isOurs(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ours : OURS) {
            if (lower.startsWith(ours) || lower.contains("." + ours + ".") || lower.contains("/" + ours + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable th) {
        if (th == null) {
            return "no detail";
        }
        String message = th.getMessage();
        if (message == null || message.isBlank()) {
            return th.getClass().getSimpleName();
        }
        // The interesting part is the first sentence; the rest is a stack trace's worth of noise.
        int cut = message.indexOf(". ");
        return cut > 0 ? message.substring(0, cut) : message;
    }
}
