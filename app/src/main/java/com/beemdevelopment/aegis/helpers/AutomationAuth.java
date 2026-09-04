package com.beemdevelopment.aegis.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Fork (白い熊 防具): the gate on the 保存復元 automation surface — the master switch, the optional
 * token, and the ONE function that decides whether a request is served.
 *
 * <p>Two components sit behind it: the broadcast contract
 * ({@link com.beemdevelopment.aegis.receivers.StateExportReceiver}) and the data door
 * ({@link com.beemdevelopment.aegis.automation.AutomationProvider}).
 *
 * <p><b>Contract v2 turned the gate around.</b> v1 shipped every app closed — the switch defaulted
 * to off and a caller also had to present a 48-character secret 白い熊 had pasted from this app's
 * settings into the caller's. That is the wrong shape for the case the family now exists to serve:
 * <b>a pasted secret cannot survive a wipe</b>, and 応用管理 restoring apps <i>and their data</i>
 * onto a clean phone is precisely a device where nothing has been configured and nobody has pasted
 * anything. A gate that only works once the phone is already set up is no gate for setting the
 * phone up. So the switch now ships <b>on</b> and the token is <b>opt-in</b>; the switch stays
 * because closing one app off must remain possible, and a feature that can be turned on but never
 * off is one 白い熊 cannot retreat from.
 *
 * <p><b>A token sent to an app that does not require one is IGNORED, never refused.</b> Tokens live
 * in task arguments and workspace variables that outlive the setting they were pasted for; a caller
 * still sending one — because it was configured last year, or because another app on the batch does
 * want one — must be served. Refusing it would turn "白い熊 turned a switch off" into "half the batch
 * mysteriously fails", which is exactly the friction the switch exists to remove.
 *
 * <p>The identity check that replaces the token on the data door is not here — it is
 * {@link com.beemdevelopment.aegis.automation.AutomationCallers}, and it applies whether or not a
 * token is asked for.
 *
 * <p>All three values live in {@link HoguExport#EXIMPORT_PREFS} — a device-local prefs file that is
 * <b>not</b> part of any export category, so the token can never travel inside a backup ZIP. The
 * token is 24 random bytes from {@link SecureRandom}, hex-encoded (shell-safe for {@code am --es}
 * and Tasker), generated lazily on first read so the settings row always shows a value. Comparison
 * is constant-time — the family convention, never {@code equals} on a secret.
 */
public final class AutomationAuth {
    private static final String KEY_ENABLED = "automation_enabled";
    private static final String KEY_REQUIRE_TOKEN = "automation_require_token";
    private static final String KEY_TOKEN = "automation_token";

    private static final int TOKEN_BYTES = 24;

    private AutomationAuth() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(HoguExport.EXIMPORT_PREFS, Context.MODE_PRIVATE);
    }

    /** The master switch. <b>Default on</b> (contract v2) — see the class comment for why. */
    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    /**
     * <b>{@code commit()}, not {@code apply()} — and v2 is why.</b> The default flipped to
     * {@code true}, so this key's absence now means ON. An {@code apply()} still in flight when the
     * process dies leaves nothing on disk, and the next read falls back to the default: <b>a switch
     * 白い熊 turned OFF comes back ON.</b> Under v1's {@code false} default the same lost write was
     * harmless, which is exactly why it survived this long. A synchronous write of a two-key file on
     * a settings tap is not a cost worth trading for that.
     */
    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    /** Whether a caller must also present the token. <b>Default off</b> (contract v2). */
    public static boolean isTokenRequired(Context context) {
        return prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false);
    }

    /** {@code commit()} for the same reason as {@link #setEnabled}: a lost write silently relaxes the gate. */
    public static void setTokenRequired(Context context, boolean required) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, required).commit();
    }

    /**
     * The whole gate, in one place: {@code null} means proceed, anything else is the exact
     * {@code ERROR:} line to answer with.
     *
     * <p>One function on purpose. Two checks written out at each entry point is how "disabled" and
     * "bad token" drift apart across forty-two apps — and they must stay distinct, because they
     * debug differently.
     *
     * <p>{@code candidate} is only looked at when this app is actually asking for a token. When it
     * is not, a token that arrived anyway is simply not read.
     */
    @Nullable
    public static String refuse(Context context, @Nullable String candidate) {
        if (!isEnabled(context)) {
            return "ERROR:automation disabled";
        }
        if (isTokenRequired(context) && !isTokenValid(context, candidate)) {
            return "ERROR:bad token";
        }
        return null;
    }

    /** The automation token, generating and persisting one on first use. */
    public static synchronized String getOrCreateToken(Context context) {
        String current = prefs(context).getString(KEY_TOKEN, "");
        if (current != null && !current.isEmpty()) {
            return current;
        }
        return regenerateToken(context);
    }

    /** Replaces the token with a fresh random one — revokes every pasted copy. */
    public static synchronized String regenerateToken(Context context) {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String token = sb.toString();
        // commit(): a lost regeneration leaves the OLD token on disk and still valid, so the one
        // action whose entire purpose is revocation would silently revoke nothing — while telling
        // 白い熊 to go and update every pasted copy.
        prefs(context).edit().putString(KEY_TOKEN, token).commit();
        return token;
    }

    /**
     * Constant-time token check. Blank/absent candidates always fail. Only consulted when
     * {@link #isTokenRequired} is on — see {@link #refuse}.
     */
    public static boolean isTokenValid(Context context, @Nullable String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(candidate.getBytes(), getOrCreateToken(context).getBytes());
    }

    /** The token shortened for display, e.g. {@code 80922d8c…4c49a87c}. */
    public static String abbreviate(String token) {
        if (token.length() <= 20) {
            return token;
        }
        return token.substring(0, 8) + "…" + token.substring(token.length() - 8);
    }
}
