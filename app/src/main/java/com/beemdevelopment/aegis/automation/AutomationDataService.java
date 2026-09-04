package com.beemdevelopment.aegis.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.HoguExport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fork (白い熊 防具): where a data-door export or import actually runs (contract v2 §2a).
 *
 * <h3>Why a foreground service and not the provider call</h3>
 * The call returns in milliseconds; this can run for minutes. Two hard reasons it cannot be done
 * anywhere cheaper:
 * <ul>
 *   <li><b>A binder call holds the caller.</b> 応用管理 is drawing a list; a multi-minute
 *       synchronous call would freeze its UI, report no progress, and refuse cancellation.</li>
 *   <li><b>A backgrounded app writing for minutes is frozen mid-stream on this phone</b>, which
 *       yields a truncated archive underneath a success reply — the worst possible failure, because
 *       it is indistinguishable from a good backup until the day it is restored.</li>
 * </ul>
 *
 * <h3>The descriptor</h3>
 * Already duplicated by {@link AutomationProvider} before it got here, because the original belongs
 * to the binder transaction and is closed the moment {@code call()} returns. This service owns the
 * copy and closes it in a {@code finally} — leaking one would hold the caller's file open
 * indefinitely, and <b>the caller cannot checksum or encrypt a file that is still open</b>.
 *
 * <h3>What this fork writes</h3>
 * The same archive {@link HoguExport} writes everywhere else — one ZIP, {@code manifest.json} plus
 * one entry per category. <b>The vault travels exactly as it sits on disk</b>, which means it is
 * still encrypted with 白い熊's vault password whenever the vault is encrypted. This path never
 * decrypts it and never downgrades its protection.
 */
public class AutomationDataService extends Service {
    private static final String TAG = "AutomationDataService";

    private static final String CHANNEL = "automation_data";
    private static final int NOTIFICATION_ID = 9714;
    private static final String EXTRA_JOB = "job";
    private static final String EXTRA_IMPORTING = "importing";

    /** Progress: what this app counts is categories, and it says so rather than sending a percentage. */
    private static final String PROGRESS_UNIT = "区分";
    private static final long PROGRESS_MIN_INTERVAL_MS = 500;
    /** A progress broadcast is also the heartbeat — the caller gives up on an app that goes quiet. */
    private static final long HEARTBEAT_INTERVAL_MS = 20_000;
    private static final long HEARTBEAT_POLL_MS = 2_000;

    /**
     * The descriptor's way across, because an Intent is the wrong vehicle for one.
     *
     * <p>A {@link ParcelFileDescriptor} in an Intent extra is duplicated by the system on delivery
     * and the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
     * the job id keeps exactly one open descriptor with exactly one owner — this service, which
     * closes it in a {@code finally}.
     */
    private static final ConcurrentHashMap<String, ParcelFileDescriptor> HANDOVER = new ConcurrentHashMap<>();

    static void start(Context context, String jobId, ParcelFileDescriptor fd, boolean importing,
                      @Nullable Bundle extras) {
        HANDOVER.put(jobId, fd);
        try {
            Intent i = new Intent(context, AutomationDataService.class);
            i.putExtra(EXTRA_JOB, jobId);
            i.putExtra(EXTRA_IMPORTING, importing);
            if (extras != null) {
                i.putExtra(AutomationProvider.KEY_ITEMS, extras.getString(AutomationProvider.KEY_ITEMS));
                i.putExtra(AutomationProvider.KEY_REPLY_ACTION, extras.getString(AutomationProvider.KEY_REPLY_ACTION));
                i.putExtra(AutomationProvider.KEY_REPLY_PACKAGE, extras.getString(AutomationProvider.KEY_REPLY_PACKAGE));
                i.putExtra(AutomationProvider.KEY_PROGRESS_ACTION, extras.getString(AutomationProvider.KEY_PROGRESS_ACTION));
            }
            ContextCompat.startForegroundService(context, i);
        } catch (RuntimeException e) {
            // Nothing will ever take it out of the map now — the provider closes it and answers the
            // caller with the failure.
            HANDOVER.remove(jobId);
            throw e;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * <b>The descriptor must have exactly one owner from the moment it arrives.</b>
     *
     * <p>Between {@link #HANDOVER} giving it up and the worker thread taking it, every way out of
     * this method has to close it — not just the interesting one. The window is small and it has
     * several doors: {@code startForeground} refusing, a stale job id with nothing to collect,
     * thread creation failing under memory pressure. A descriptor dropped in any of them holds the
     * caller's file open for the life of this process, and <b>a caller cannot checksum or encrypt a
     * file that is still open</b> — so its backup stalls on a file nobody is writing any more. Hence
     * the {@code handedOff} flag rather than a cleanup on the one path that first showed the bug.
     *
     * <p><b>And {@code startForeground} is not optional once we are here.</b> The platform requires
     * it after {@code startForegroundService()} whatever this method then decides, and kills the
     * process with {@code ForegroundServiceDidNotStartInTimeException} otherwise — so without it on
     * the early-return paths, <i>a caller retrying with a stale job id would kill this app</i>.
     */
    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        final boolean importing = intent != null && intent.getBooleanExtra(EXTRA_IMPORTING, false);
        final String jobId = intent == null ? null : intent.getStringExtra(EXTRA_JOB);
        final Reporter reporter = jobId == null ? null : new Reporter(jobId,
                intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION),
                intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE),
                intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION));

        // Before every return below, per the note above. The type is only meaningful from API 34,
        // where it must match the manifest's specialUse; it is a compile-time constant, so naming it
        // here is safe on every older device.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(importing),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
        } catch (Exception e) {
            // It can be refused outright on API 31+ for a service started from the background —
            // which a provider call() always is. The descriptor is still in HANDOVER and nothing
            // downstream will ever come for it, so this is the path that has to let it go.
            Log.w(TAG, "could not go foreground: " + e.getMessage(), e);
            if (jobId != null) {
                releaseHandover(jobId);
                reporter.reply("ERROR:cannot go foreground: " + e.getClass().getSimpleName());
            }
            return stop(startId);
        }

        if (jobId == null) {
            return stop(startId);
        }
        final ParcelFileDescriptor fd = HANDOVER.remove(jobId);
        if (fd == null) {
            // A duplicate or stale start: nothing to close, and no reply — the run this id belonged
            // to has already answered for itself.
            AutomationJobs.finish(jobId);
            return stop(startId);
        }

        final String items = intent.getStringExtra(AutomationProvider.KEY_ITEMS);

        boolean handedOff = false;
        try {
            new Thread(() -> {
                try {
                    if (importing) {
                        runImport(fd, reporter);
                    } else {
                        runExport(jobId, fd, items, reporter);
                    }
                } catch (HoguExport.CancelledException e) {
                    reporter.reply("ERROR:cancelled");
                } catch (Throwable t) {
                    Log.w(TAG, (importing ? "import" : "export") + " failed: " + t.getMessage(), t);
                    reporter.reply("ERROR:" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                } finally {
                    reporter.stopHeartbeat();
                    closeQuietly(fd);
                    AutomationJobs.finish(jobId);
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                    stopSelf(startId);
                }
            }, TAG).start();
            handedOff = true;
        } finally {
            if (!handedOff) {
                // Thread creation failed — under the memory pressure that makes that happen, the one
                // thing that must still work is giving the caller its file back.
                reporter.reply("ERROR:could not start the worker");
                closeQuietly(fd);
                AutomationJobs.finish(jobId);
                stop(startId);
            }
        }

        return START_NOT_STICKY;
    }

    /** Let go of a descriptor still sitting in {@link #HANDOVER}, on a path that will never use it. */
    private static void releaseHandover(String jobId) {
        ParcelFileDescriptor stranded = HANDOVER.remove(jobId);
        if (stranded != null) {
            closeQuietly(stranded);
        }
        AutomationJobs.finish(jobId);
    }

    // ---------------------------------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------------------------------

    private void runExport(String jobId, ParcelFileDescriptor fd, @Nullable String items,
                           Reporter reporter) throws Exception {
        Set<HoguExport.Cat> cats = resolve(items);
        if (cats == null) {
            reporter.reply("ERROR:unknown category in items: " + items);
            return;
        }

        final long[] written = {0};
        reporter.startHeartbeat();
        try (OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we may
            // not be able to see it at all — it can be an anonymous pipe, or a descriptor into a
            // directory this app cannot list.
            OutputStream counting = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    sink.write(b);
                    written[0]++;
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    sink.write(b, off, len);
                    written[0] += len;
                }
            };
            final int total = cats.size();
            HoguExport.export(this, cats, counting,
                    (done, t, label) -> reporter.progress(done, t, label, written[0]),
                    () -> AutomationJobs.isCancelled(jobId));
            reporter.progress(total, total, "", written[0]);
        }

        if (AutomationJobs.isCancelled(jobId)) {
            reporter.reply("ERROR:cancelled");
        } else {
            reporter.reply("OK:" + written[0] + "|" + cats.size() + " categories");
        }
    }

    /** {@code items} absent/empty means this app's DEFAULT set, not everything. */
    @Nullable
    private static Set<HoguExport.Cat> resolve(@Nullable String items) {
        if (items == null || items.trim().isEmpty()) {
            return HoguExport.Cat.defaults();
        }
        Set<HoguExport.Cat> out = new LinkedHashSet<>();
        for (String raw : items.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            HoguExport.Cat cat = HoguExport.Cat.byId(id);
            if (cat == null) {
                return null;
            }
            out.add(cat);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Import
    // ---------------------------------------------------------------------------------------------

    /**
     * Read the whole archive before touching anything.
     *
     * <p>{@link HoguExport#importFrom} wants the bytes, and that is the right shape here for a
     * reason beyond convenience: a partial read that failed halfway would otherwise import half an
     * archive, and a half-restored app is worse than one that refused.
     *
     * <p><b>The intro flag is the thing that makes a clean-phone restore actually land.</b> Aegis
     * decides whether to run its first-run wizard from {@code pref_intro}, which is device-local and
     * deliberately never exported — so a freshly installed app that has just been handed a restored
     * vault would still show the wizard on first launch and write a brand-new empty vault over the
     * top of it. Silently, and only discovered when 白い熊 went looking for his codes. So when the
     * archive actually carried the vault, this marks the intro done; when it did not, it must not,
     * because skipping the wizard with no vault to open is the same bug facing the other way.
     *
     * <p>And it is written with {@code commit()}, not {@code apply()}. 応用管理 force-stops this app
     * the instant the success reply lands, and an {@code apply()} still sitting in the queue would
     * be lost with the process. The synchronous write also flushes every preference
     * {@link HoguExport#importFrom} merged in on the way here, since those share this file and this
     * thread's write takes the same lock — so the whole restore is on disk before anyone is told it
     * succeeded.
     */
    private void runImport(ParcelFileDescriptor fd, Reporter reporter) throws Exception {
        reporter.startHeartbeat();
        byte[] bytes;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            bytes = readAll(in);
        }
        if (bytes.length == 0) {
            reporter.reply("ERROR:empty archive");
            return;
        }
        // Every category the archive actually carries, not every category we know about: asking for
        // one the archive lacks is how a restore ends up reporting success over nothing.
        Set<HoguExport.Cat> present = HoguExport.categoriesIn(bytes);
        if (present.isEmpty()) {
            reporter.reply("ERROR:archive carries no categories");
            return;
        }
        reporter.progress(0, present.size(), "", 0);

        String summary = HoguExport.importFrom(this, bytes, present);
        settle(HoguExport.hasVault(bytes));

        reporter.progress(present.size(), present.size(), "", 0);
        reporter.reply("OK:" + countLines(summary) + " restored");
    }

    /** The synchronous write described on {@link #runImport} — intro flag when earned, always a flush. */
    private void settle(boolean restoredVault) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor ed = prefs.edit();
        if (restoredVault) {
            ed.putBoolean("pref_intro", true);
        }
        // A real change either way, so the commit always performs a disk write and therefore always
        // flushes what importFrom applied. It is in HoguExport's EXCLUDE list — a device-local
        // timestamp has no business travelling into another install.
        ed.putLong("pref_automation_imported_at", System.currentTimeMillis());
        ed.commit();
    }

    private static int countLines(@Nullable String summary) {
        if (summary == null || summary.isEmpty()) {
            return 0;
        }
        return summary.split("\n").length;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // Reply + progress
    // ---------------------------------------------------------------------------------------------

    /**
     * The one terminal reply, the progress broadcasts, and the heartbeat behind them.
     *
     * <p>Exactly one terminal answer per job, whatever path got here — a synchronous failure and an
     * asynchronous success must never both fire. The same guard the broadcast contract has carried
     * since the first sister app.
     */
    private final class Reporter {
        private final String _jobId;
        @Nullable private final String _replyAction;
        @Nullable private final String _replyPackage;
        @Nullable private final String _progressAction;

        private final AtomicBoolean _replied = new AtomicBoolean(false);
        private final AtomicBoolean _beating = new AtomicBoolean(false);
        private volatile long _lastSentMs;
        private volatile String _lastText = "";
        private volatile long _lastCurrent;
        private volatile long _lastTotal;
        private volatile long _lastBytes;

        Reporter(String jobId, @Nullable String replyAction, @Nullable String replyPackage,
                 @Nullable String progressAction) {
            _jobId = jobId;
            _replyAction = replyAction;
            _replyPackage = replyPackage;
            _progressAction = progressAction;
        }

        void reply(String result) {
            if (!_replied.compareAndSet(false, true)) {
                return;
            }
            stopHeartbeat();
            // Logged either way — the reply is invisible on this side, and this is what 白い熊 reads
            // back with `adb logcat` during acceptance testing.
            Log.w(TAG, "[" + _jobId + "] -> " + (result.length() > 160 ? result.substring(0, 160) : result));
            if (isEmpty(_replyAction) || isEmpty(_replyPackage)) {
                return;
            }
            Intent out = new Intent(_replyAction);
            out.setPackage(_replyPackage);
            // Without this a caller that has been backgrounded never hears the answer, and on a clean
            // phone the caller may not have been launched at all.
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(AutomationProvider.KEY_JOB_ID, _jobId);
            out.putExtra(AutomationProvider.KEY_RESULT, result);
            sendBroadcast(out);
        }

        /** Real numbers, never a percentage — 白い熊's explicit requirement. */
        void progress(int done, int total, String label, long bytes) {
            _lastText = PROGRESS_UNIT + " " + done + "/" + total + (label.isEmpty() ? "" : " — " + label);
            _lastCurrent = done;
            _lastTotal = total;
            _lastBytes = bytes;
            long now = System.currentTimeMillis();
            if (done < total && now - _lastSentMs < PROGRESS_MIN_INTERVAL_MS) {
                return;
            }
            send();
        }

        private void send() {
            if (isEmpty(_progressAction) || isEmpty(_replyPackage)) {
                return;
            }
            _lastSentMs = System.currentTimeMillis();
            Intent out = new Intent(_progressAction);
            out.setPackage(_replyPackage);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(AutomationProvider.KEY_JOB_ID, _jobId);
            // The broadcast contract's correlation key, carrying the same value, so a caller that
            // shares one progress listener between the two doors reads it either way.
            out.putExtra("reply_id", _jobId);
            out.putExtra("app", getApplicationInfo().loadLabel(getPackageManager()).toString());
            out.putExtra("text", _lastText);
            out.putExtra("current", _lastCurrent);
            out.putExtra("total", _lastTotal);
            out.putExtra("unit", PROGRESS_UNIT);
            if (_lastBytes > 0) {
                out.putExtra("bytes", _lastBytes);
            }
            sendBroadcast(out);
        }

        /**
         * Keep saying "still here" while a single long step runs.
         *
         * <p>The caller treats every progress broadcast as proof of life and fails an app that goes
         * quiet for two minutes. One category of this app's export can be a whole file tree — fonts,
         * icon packs — and reports nothing until it finishes, which is exactly the shape that gets a
         * healthy app declared dead. A heartbeat is a promise and not a shield, though: the work it
         * covers still has to terminate, which is what the cancellation flag is polled for.
         */
        void startHeartbeat() {
            if (isEmpty(_progressAction) || isEmpty(_replyPackage) || !_beating.compareAndSet(false, true)) {
                return;
            }
            Thread t = new Thread(() -> {
                while (_beating.get()) {
                    try {
                        Thread.sleep(HEARTBEAT_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (_beating.get() && System.currentTimeMillis() - _lastSentMs >= HEARTBEAT_INTERVAL_MS) {
                        send();
                    }
                }
            }, TAG + "-heartbeat");
            t.setDaemon(true);
            t.start();
        }

        void stopHeartbeat() {
            _beating.set(false);
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------------

    private Notification notification(boolean importing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL, getString(R.string.hogu_auto_data_channel), NotificationManager.IMPORTANCE_LOW));
            }
        }
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(getString(importing
                        ? R.string.hogu_auto_data_importing : R.string.hogu_auto_data_exporting))
                .setSmallIcon(R.drawable.ic_aegis_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private int stop(int startId) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Exception ignored) {
        }
    }
}
