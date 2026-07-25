package com.beemdevelopment.aegis.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Fork (白い熊 防具): the app's ONE automation token — the gate on the 保存復元 state-export contract
 * ({@link com.beemdevelopment.aegis.receivers.StateExportReceiver}).
 *
 * <p>Both the master switch and the token live in {@link HoguExport#EXIMPORT_PREFS} — a device-local
 * prefs file that is <b>not</b> part of any export category, so the token can never travel inside a
 * backup ZIP. The switch defaults to <b>off</b>: nothing is reachable until 白い熊 turns it on.
 *
 * <p>The token is 24 random bytes from {@link SecureRandom}, hex-encoded (shell-safe for
 * {@code am --es} and Tasker), generated lazily on first read so the settings row always shows a
 * value. Comparison is constant-time — the family convention, never {@code equals} on a secret.
 */
public final class AutomationAuth {
    private static final String KEY_ENABLED = "automation_enabled";
    private static final String KEY_TOKEN = "automation_token";

    private static final int TOKEN_BYTES = 24;

    private AutomationAuth() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(HoguExport.EXIMPORT_PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
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
        prefs(context).edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    /**
     * Constant-time token check. Blank/absent candidates always fail. Callers report
     * "automation disabled" and "bad token" as distinct errors — they debug differently.
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
