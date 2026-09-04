package com.beemdevelopment.aegis.automation;

import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fork (白い熊 防具): the jobs the data door has started, and the flag each of them watches to stop.
 *
 * <p>One at a time is not enforced here — a caller asking for two exports at once is asking for two
 * files, and refusing that is {@link AutomationDataService}'s business. What this owns is the
 * mapping from the id a caller was handed to a cancellation it can act on, which must outlive the
 * binder call that created it and be reachable from a service that never saw the caller.
 */
public final class AutomationJobs {

    private static final ConcurrentHashMap<String, Boolean> CANCELLED = new ConcurrentHashMap<>();

    private AutomationJobs() { }

    public static String begin() {
        String id = UUID.randomUUID().toString();
        CANCELLED.put(id, Boolean.FALSE);
        return id;
    }

    /**
     * Ask a job to stop. A no-op for an id that is finished or was never real.
     *
     * <p>Deliberately silent: a cancel arriving after the work completed is the normal race, not an
     * error, and answering it as one would make every well-behaved caller look broken.
     */
    public static void cancel(@Nullable String jobId) {
        if (jobId != null) {
            CANCELLED.computeIfPresent(jobId, (k, v) -> Boolean.TRUE);
        }
    }

    /** Polled at write boundaries — never mid-write, so a cancelled archive is never half a file. */
    public static boolean isCancelled(String jobId) {
        return Boolean.TRUE.equals(CANCELLED.get(jobId));
    }

    public static void finish(String jobId) {
        CANCELLED.remove(jobId);
    }
}
