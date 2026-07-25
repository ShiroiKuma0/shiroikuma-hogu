package com.beemdevelopment.aegis.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.beemdevelopment.aegis.BuildConfig;
import com.beemdevelopment.aegis.helpers.AutomationAuth;
import com.beemdevelopment.aegis.helpers.HoguExport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fork (白い熊 防具): the sister-app <b>state-export automation contract</b> (保存復元) — the wire shape
 * every 白い熊 app exposes so one 自由作業盤 task can back them all up headlessly.
 *
 * <ul>
 *   <li>{@code <pkg>.action.EXPORT_STATE}: run the category-ZIP export ({@link HoguExport}) with no
 *       UI. Extras (all String): {@code token} (required), {@code path} (optional absolute directory
 *       — wins over the configured export directory), {@code items} (optional comma list of
 *       {@link HoguExport.Cat} ids; absent/empty = everything), {@code progress_action} (optional),
 *       plus the reply trio {@code reply_action} / {@code reply_package} / {@code reply_id}.</li>
 *   <li>{@code <pkg>.action.LIST_CATEGORIES}: token-gated, instant category enumeration for the
 *       caller's picker. Lines are {@code id<TAB>label}, with a third {@code parent-id} field on
 *       sub-options.</li>
 * </ul>
 *
 * <p><b>ONE ZIP per request, always</b> — every category is an entry inside the single archive, named
 * {@code shiroikuma-hogu_<yyyy-MM-dd_HH-mm-ss>.zip} (identical to what the Export/Import panel
 * writes, and importable by it).
 *
 * <p>Reply: a FRESH broadcast to {@code reply_package} with action {@code reply_action}, extras
 * {@code reply_id} (echoed verbatim) + {@code result} = {@code OK:<path>|<bytes>|<human size>|<n>
 * categories} (EXPORT_STATE), {@code OK:} + the category lines (LIST_CATEGORIES), or
 * {@code ERROR:<reason>}. Exactly one terminal reply, guarded by an {@link AtomicBoolean}. NO binders
 * (ResultReceiver/PendingIntent/Messenger) and NO reliance on the ordered-broadcast result — EMUI
 * severs both between third-party apps (verified on 白い熊's Mate XT, 2026-07-23); the plain reply
 * broadcast is the only channel that works. {@link Intent#FLAG_INCLUDE_STOPPED_PACKAGES} so a
 * backgrounded/stopped caller still hears us.
 *
 * <p>Progress: while exporting, plain broadcasts to {@code reply_package} with action
 * {@code progress_action} — {@code reply_id}, {@code app} (display label), {@code text}
 * (numbers-first, e.g. {@code 区分 3/10 — Vault}, never a percentage) and structured
 * {@code current}/{@code total} (long) + {@code unit} (String). Throttled to at most one every
 * 500 ms, with a final one always sent at completion.
 *
 * <p>Security: exported with NO {@code android:permission} (the caller cannot hold one) — the master
 * switch plus the token are the gate. Both live on the 白い熊 防具 UI page under Export / Import.
 */
public class StateExportReceiver extends BroadcastReceiver {
    public static final String ACTION_EXPORT_STATE = BuildConfig.APPLICATION_ID + ".action.EXPORT_STATE";
    public static final String ACTION_LIST_CATEGORIES = BuildConfig.APPLICATION_ID + ".action.LIST_CATEGORIES";

    private static final String TAG = "StateExportReceiver";
    private static final long PROGRESS_MIN_INTERVAL_MS = 500;
    private static final String PROGRESS_UNIT = "区分"; // categories — what this app counts
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    // Contract extras — deliberately bare names, shared verbatim by every sister app.
    private static final String EXTRA_TOKEN = "token";
    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_ITEMS = "items";
    private static final String EXTRA_PROGRESS_ACTION = "progress_action";
    private static final String EXTRA_REPLY_ACTION = "reply_action";
    private static final String EXTRA_REPLY_PACKAGE = "reply_package";
    private static final String EXTRA_REPLY_ID = "reply_id";
    private static final String EXTRA_RESULT = "result";
    private static final String EXTRA_PROGRESS_APP = "app";
    private static final String EXTRA_PROGRESS_TEXT = "text";
    private static final String EXTRA_PROGRESS_CURRENT = "current";
    private static final String EXTRA_PROGRESS_TOTAL = "total";
    private static final String EXTRA_PROGRESS_UNIT = "unit";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        if (action == null) {
            return;
        }

        final String token = intent.getStringExtra(EXTRA_TOKEN);
        final String replyAction = trimmed(intent.getStringExtra(EXTRA_REPLY_ACTION));
        final String replyPackage = trimmed(intent.getStringExtra(EXTRA_REPLY_PACKAGE));
        final String replyId = trimmed(intent.getStringExtra(EXTRA_REPLY_ID));
        final String progressAction = trimmed(intent.getStringExtra(EXTRA_PROGRESS_ACTION));
        final String pathOverride = trimmed(intent.getStringExtra(EXTRA_PATH));
        final String items = trimmed(intent.getStringExtra(EXTRA_ITEMS));

        final AtomicBoolean replied = new AtomicBoolean(false);
        final Replier reply = result -> {
            if (!replied.compareAndSet(false, true)) {
                return;
            }
            // Log either way — the reply is invisible on this side, and this is what 白い熊 reads
            // back with `adb logcat` during acceptance testing.
            Log.w(TAG, action + " [" + replyId + "] -> "
                    + (result.length() > 160 ? result.substring(0, 160) : result));
            if (replyAction.isEmpty() || replyPackage.isEmpty()) {
                return;
            }
            Intent out = new Intent(replyAction);
            out.setPackage(replyPackage);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(EXTRA_REPLY_ID, replyId);
            out.putExtra(EXTRA_RESULT, result);
            app.sendBroadcast(out);
        };

        // Gate first — "disabled" and "bad token" are distinct on purpose (they debug differently).
        if (!AutomationAuth.isEnabled(app)) {
            reply.send("ERROR:automation disabled");
            return;
        }
        if (!AutomationAuth.isTokenValid(app, token)) {
            reply.send("ERROR:bad token");
            return;
        }

        if (ACTION_LIST_CATEGORIES.equals(action)) {
            reply.send("OK:" + HoguExport.categoryLines(app));
            return;
        }
        if (!ACTION_EXPORT_STATE.equals(action)) {
            reply.send("ERROR:unknown action: " + action);
            return;
        }

        final Set<HoguExport.Cat> cats;
        if (items.isEmpty()) {
            cats = HoguExport.Cat.all();
        } else {
            Set<HoguExport.Cat> resolved = new LinkedHashSet<>();
            List<String> unknown = new ArrayList<>();
            for (String raw : items.split(",")) {
                String id = raw.trim();
                if (id.isEmpty()) {
                    continue;
                }
                HoguExport.Cat cat = HoguExport.Cat.byId(id);
                if (cat == null) {
                    unknown.add(id);
                } else {
                    resolved.add(cat);
                }
            }
            if (!unknown.isEmpty()) {
                reply.send("ERROR:unknown category in items: " + items);
                return;
            }
            cats = resolved;
        }

        final String appLabel = app.getApplicationInfo().loadLabel(app.getPackageManager()).toString();
        final String fileName = HoguExport.exportFileName();
        final long[] lastProgressMs = {0};
        final HoguExport.Progress progress = (done, total, label) -> {
            if (progressAction.isEmpty() || replyPackage.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            // At most one every 500 ms — but the final one always goes out.
            if (done < total && now - lastProgressMs[0] < PROGRESS_MIN_INTERVAL_MS) {
                return;
            }
            lastProgressMs[0] = now;
            Intent out = new Intent(progressAction);
            out.setPackage(replyPackage);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(EXTRA_REPLY_ID, replyId);
            out.putExtra(EXTRA_PROGRESS_APP, appLabel);
            out.putExtra(EXTRA_PROGRESS_TEXT, PROGRESS_UNIT + " " + done + "/" + total + " — " + label);
            out.putExtra(EXTRA_PROGRESS_CURRENT, (long) done);
            out.putExtra(EXTRA_PROGRESS_TOTAL, (long) total);
            out.putExtra(EXTRA_PROGRESS_UNIT, PROGRESS_UNIT);
            app.sendBroadcast(out);
        };

        // The export reads the vault + walks file trees — hold the broadcast open and work off the
        // main thread.
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // Directory precedence: `path` extra -> configured export directory -> error. Writing
                // an arbitrary absolute path needs All-Files-Access; without it we may only fall back
                // to the configured SAF directory (contract §1).
                boolean useAbsolute = !pathOverride.isEmpty() && hasAllFilesAccess();
                DocumentFile safDir = HoguExport.exportDir(app);
                if (!pathOverride.isEmpty() && !useAbsolute && safDir == null) {
                    reply.send("ERROR:no-storage-access");
                    return;
                }

                long bytes;
                String shownPath;
                if (useAbsolute) {
                    File dir = new File(pathOverride);
                    dir.mkdirs();
                    if (!dir.isDirectory()) {
                        reply.send("ERROR:not a directory: " + pathOverride);
                        return;
                    }
                    File file = new File(dir, fileName);
                    try (OutputStream out = new FileOutputStream(file)) {
                        HoguExport.export(app, cats, out, progress);
                    }
                    bytes = file.length();
                    shownPath = file.getAbsolutePath();
                } else {
                    if (safDir == null) {
                        reply.send("ERROR:no-directory");
                        return;
                    }
                    DocumentFile doc = safDir.createFile("application/zip", fileName);
                    if (doc == null) {
                        reply.send("ERROR:cannot create " + fileName + " in the export directory");
                        return;
                    }
                    try (OutputStream out = app.getContentResolver().openOutputStream(doc.getUri())) {
                        if (out == null) {
                            reply.send("ERROR:cannot open " + fileName + " for writing");
                            return;
                        }
                        HoguExport.export(app, cats, out, progress);
                    }
                    bytes = doc.length();
                    String abs = absolutePathOf(safDir, doc);
                    shownPath = abs != null ? abs : safDir.getName() + "/" + fileName;
                }

                reply.send("OK:" + shownPath + "|" + bytes + "|" + HoguExport.humanSize(bytes)
                        + "|" + cats.size() + " categories");
            } catch (Exception e) {
                Log.w(TAG, "export failed: " + e.getMessage(), e);
                reply.send("ERROR:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            } finally {
                pending.finish();
            }
        }, TAG).start();
    }

    /** All-Files-Access — required to write a caller-supplied absolute path on API 30+. */
    private static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    /**
     * Best-effort real filesystem path for a file written through SAF, so the reply carries an
     * absolute path (the contract's preferred shape) rather than just a folder label. Only the
     * primary-storage tree can be resolved this way; anything else returns null.
     */
    @Nullable
    private static String absolutePathOf(DocumentFile dir, DocumentFile doc) {
        Uri treeUri = dir.getUri();
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return null;
        }
        if (docId == null || !docId.startsWith("primary:")) {
            return null; // sd-card/usb volumes: no stable mount path
        }
        String rel = docId.substring("primary:".length());
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        while (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        String base = Environment.getExternalStorageDirectory().getAbsolutePath();
        String name = doc.getName();
        if (name == null) {
            return null;
        }
        return rel.isEmpty() ? base + "/" + name : base + "/" + rel + "/" + name;
    }

    private static String trimmed(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    private interface Replier {
        void send(String result);
    }
}
