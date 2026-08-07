/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.region;

import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Stable facade over Folia's region-local entity collections for modders.
 *
 * <p>Vanilla/NeoForge mods often iterate {@code level.getEntities()} as if the
 * list were global and single-threaded. On Folia each region owns a slice.
 * This facade documents that contract and provides a thread-safe snapshot
 * view when a full list is unavoidably requested from a region thread.</p>
 *
 * <p><b>Policy:</b> prefer entity/region schedulers. Global iteration from a
 * region tick is unsupported and may omit entities owned by other regions.</p>
 *
 * @param <T> entity type placeholder (Object at compile time without MC types)
 */
public final class RegionEntityListFacade<T> implements Iterable<T> {

    private static final Logger LOGGER = Logger.getLogger("EturliaEntityFacade");

    private final CopyOnWriteArrayList<T> localSnapshot = new CopyOnWriteArrayList<>();
    private final String regionLabel;

    public RegionEntityListFacade(String regionLabel) {
        this.regionLabel = Objects.requireNonNullElse(regionLabel, "unknown");
    }

    public void replaceLocalSnapshot(Iterable<? extends T> entities) {
        // Build off to the side, then swap in one shot: element-by-element add on a
        // CopyOnWriteArrayList copies the whole backing array per element (O(n^2)) and
        // leaves readers observing a half-filled list in the meantime.
        java.util.ArrayList<T> replacement = new java.util.ArrayList<>();
        if (entities != null) {
            for (T e : entities) {
                if (e != null) {
                    replacement.add(e);
                }
            }
        }
        synchronized (localSnapshot) {
            localSnapshot.clear();
            localSnapshot.addAll(replacement);
        }
    }

    public void addLocal(T entity) {
        if (entity != null) {
            localSnapshot.addIfAbsent(entity);
        }
    }

    public void removeLocal(T entity) {
        localSnapshot.remove(entity);
    }

    /** Region-local view only — never claims global completeness. */
    public java.util.List<T> regionLocalView() {
        CrossRegionInvocationGuard.check("RegionEntityListFacade.view@" + regionLabel);
        return Collections.unmodifiableList(localSnapshot);
    }

    public java.util.List<T> filter(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        CrossRegionInvocationGuard.check("RegionEntityListFacade.filter@" + regionLabel);
        java.util.ArrayList<T> out = new java.util.ArrayList<>();
        for (T e : localSnapshot) {
            if (predicate.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public Iterator<T> iterator() {
        return regionLocalView().iterator();
    }

    public int size() {
        return localSnapshot.size();
    }

    public void warnIfGlobalExpectation(String caller) {
        LOGGER.warning("[Eturlia] " + caller + " requested entity list on region '"
                + regionLabel + "' — returning region-local snapshot only ("
                + localSnapshot.size() + " entities). Cross-region completeness is unsupported.");
    }
}
