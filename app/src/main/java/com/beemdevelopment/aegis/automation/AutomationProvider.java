package com.beemdevelopment.aegis.automation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.os.BundleCompat;

import com.beemdevelopment.aegis.helpers.AutomationAuth;
import com.beemdevelopment.aegis.helpers.HoguExport;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fork (白い熊 防具): <b>the data door</b> — export this app's own state, and put it back, for a
 * caller we can identify. Contract v2 §2a; it sits <i>alongside</i>
 * {@link com.beemdevelopment.aegis.receivers.StateExportReceiver}, it does not replace it.
 *
 * <h3>Why a provider and not the broadcast receiver next to it</h3>
 * Two reasons, and the first is the whole point of the redesign.
 *
 * <p><b>A broadcast cannot tell you who sent it.</b> v1's answer to that was a shared secret, which
 * cannot survive the wipe this feature exists to recover from. A provider gets the caller's identity
 * from the framework for free — see {@link AutomationCallers} for what is actually checked, and why
 * a package-name prefix would have been <i>worse</i> than the token it replaced.
 *
 * <p><b>A list needs a synchronous answer.</b> 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * <h3>What does NOT happen here</h3>
 * The payload. {@link #call} validates, starts a foreground service and returns — tens of megabytes
 * over minutes inside a binder call would block the caller, report no progress, refuse cancellation
 * and die silently if this process were killed.
 *
 * <h3>Why a descriptor and not a path</h3>
 * Because a backup is not a stable directory while it is being assembled. 応用管理 writes into a
 * temporary path and renames on commit; it encrypts and checksums <b>per file it knows about</b>. A
 * file this app dropped into that directory itself would be renamed out from under it, would sit in
 * plaintext inside an encrypted backup, and would be unverified rather than verified-and-failing. A
 * descriptor is also a capability that <b>expires when it is closed</b>.
 *
 * <p>It also means this fork no longer needs {@code MANAGE_EXTERNAL_STORAGE} for <i>this</i> path —
 * that permission is still declared, but only for the §1 receiver's caller-supplied absolute
 * directory, which is a separate feature.
 *
 * <p><b>{@code import} exists ONLY here.</b> It never gets a broadcast action: an import overwrites
 * this app's vault, and the §1 receiver is {@code exported="true"} with no permission — an import
 * there would let any app on the phone wipe any sister app.
 */
public class AutomationProvider extends ContentProvider {
    private static final String TAG = "AutomationProvider";

    public static final String METHOD_DESCRIBE = "describe";
    public static final String METHOD_EXPORT = "export";
    public static final String METHOD_IMPORT = "import";
    public static final String METHOD_CANCEL = "cancel";

    public static final String KEY_RESULT = "result";
    public static final String KEY_FD = "fd";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_JOB_ID = "job_id";
    public static final String KEY_ITEMS = "items";
    public static final String KEY_REPLY_ACTION = "reply_action";
    public static final String KEY_REPLY_PACKAGE = "reply_package";
    public static final String KEY_PROGRESS_ACTION = "progress_action";

    /**
     * This app's archive format — the same number {@link HoguExport#VERSION} stamps into
     * {@code manifest.json}, so the header cannot drift from what is actually written. Bumped when
     * an older build could no longer read what we write.
     */
    public static final int FORMAT = HoguExport.VERSION;

    /**
     * The oldest archive this build can still read.
     *
     * <p>Version skew has a direction: old data into a newer app is normally fine, because an app
     * migrates its own storage; newer data into an older app is not. This field is what lets a
     * caller refuse the second case at discovery time, before anything is streamed.
     */
    public static final int MIN_FORMAT_READABLE = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Every method answers a {@link Bundle} with {@link #KEY_RESULT} — {@code OK…} or
     * {@code ERROR:…}, the same vocabulary the broadcast contract uses, so a caller has one grammar
     * to parse rather than two.
     *
     * <p><b>A refusal is returned, never thrown.</b> An exception across a binder reaches the caller
     * as a {@code RuntimeException} with our stack trace in it, which tells 白い熊 nothing and tells
     * a misbehaving caller rather more than it should.
     */
    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) {
            return fail("ERROR:not ready");
        }
        ctx = ctx.getApplicationContext();

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        String refusedCaller = AutomationCallers.refuse(ctx, getCallingPackage());
        if (refusedCaller != null) {
            Log.w(TAG, method + " -> " + refusedCaller);
            return fail(refusedCaller);
        }
        // Then this app's own switches — a token is ignored unless this app asks for one.
        String refused = AutomationAuth.refuse(ctx, extras == null ? null : extras.getString(KEY_TOKEN));
        if (refused != null) {
            Log.w(TAG, method + " -> " + refused);
            return fail(refused);
        }

        try {
            switch (method) {
                case METHOD_DESCRIBE:
                    return ok(describe(ctx));
                case METHOD_EXPORT:
                    return start(ctx, extras, false);
                case METHOD_IMPORT:
                    return start(ctx, extras, true);
                case METHOD_CANCEL:
                    AutomationJobs.cancel(extras == null ? null : extras.getString(KEY_JOB_ID));
                    return ok("OK:cancelled");
                default:
                    return fail("ERROR:unknown method: " + method);
            }
        } catch (Exception e) {
            // The last line of the "return a refusal, never throw" rule: whatever went wrong above,
            // the caller gets a parsable ERROR: and not our stack trace.
            Log.w(TAG, method + " failed: " + e.getMessage(), e);
            return fail("ERROR:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * <p>Returned from the call rather than written into the archive, deliberately: 応用管理 must draw
     * a row before an export exists, and at restore must judge compatibility <b>before</b> streaming
     * tens of megabytes into an app that would reject them — which it cannot do if the header is
     * buried inside an encrypted archive.
     *
     * <p>{@code requires_launch_first} is <b>false</b>, and that is a claim this fork has to earn.
     * Aegis decides whether to run its first-run wizard from {@code pref_intro}, which is
     * device-local and never exported — so a restored vault would be overwritten by a fresh one the
     * first time 白い熊 opened the app. {@link AutomationDataService} closes that hole on the import
     * side instead of asking the caller to launch us: see its {@code runImport}. Launching first
     * would not have worked anyway — Aegis's intro is an interactive wizard, so a launch that nobody
     * completes leaves the flag exactly as it was.
     */
    private String describe(Context ctx) throws Exception {
        PackageInfo pkg = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);

        JSONArray contains = new JSONArray();
        for (HoguExport.Cat cat : HoguExport.Cat.defaults()) {
            // Short human strings, rendered verbatim by the caller — so each app describes itself.
            contains.put(ctx.getString(cat.getLabelRes()));
        }

        JSONObject header = new JSONObject()
                .put("app_id", ctx.getPackageName())
                .put("version_code", PackageInfoCompat.getLongVersionCode(pkg))
                .put("version_name", pkg.versionName == null ? "" : pkg.versionName)
                .put("format", FORMAT)
                .put("min_format_readable", MIN_FORMAT_READABLE)
                .put("requires_launch_first", false)
                .put("contains", contains);
        return "OK:" + header.toString();
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * <p>The descriptor is <b>duplicated</b> before it leaves this method. The one in {@code extras}
     * belongs to the binder transaction and is closed when {@code call()} returns; a service reading
     * it afterwards would find it shut. That is a bug you only see under load, so it is not left to
     * the service to remember.
     */
    private Bundle start(Context ctx, @Nullable Bundle extras, boolean importing) {
        ParcelFileDescriptor fd = extras == null
                ? null
                : BundleCompat.getParcelable(extras, KEY_FD, ParcelFileDescriptor.class);
        if (fd == null) {
            return fail("ERROR:no descriptor");
        }
        ParcelFileDescriptor dup;
        try {
            dup = fd.dup();
        } catch (Exception e) {
            return fail("ERROR:descriptor unusable");
        }
        String jobId = AutomationJobs.begin();
        try {
            AutomationDataService.start(ctx, jobId, dup, importing, extras);
        } catch (Exception e) {
            // Nothing will ever close it now, so close it here rather than leak the caller's file
            // open — a caller cannot checksum or encrypt a file that is still open.
            AutomationJobs.finish(jobId);
            closeQuietly(dup);
            Log.w(TAG, "could not start the data service: " + e.getMessage(), e);
            return fail("ERROR:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return ok("OK:" + jobId);
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Exception ignored) {
        }
    }

    private static Bundle ok(String result) {
        Bundle b = new Bundle();
        b.putString(KEY_RESULT, result);
        return b;
    }

    private static Bundle fail(String why) {
        Bundle b = new Bundle();
        b.putString(KEY_RESULT, why);
        return b;
    }

    // A provider that is only ever call()ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong
    // door".

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        throw new UnsupportedOperationException("automation is call() only");
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("automation is call() only");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("automation is call() only");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("automation is call() only");
    }
}
