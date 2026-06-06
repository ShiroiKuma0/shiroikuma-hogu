package com.beemdevelopment.aegis.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.AttrRes;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-element colours for the 白い熊 防具 UI page. Colours are stored as ARGB ints; the {@link #UNSET}
 * sentinel means "inherit the theme attribute". Also tracks a small recently-used colour list.
 */
public final class HoguTheme {
    /** Sentinel for "not set / inherit". A nearly-transparent value the picker never produces. */
    public static final int UNSET = 1;
    /** The fork's signature accent: vivid pure yellow. When the accent equals this, the chrome is
     *  themed via an exact static overlay (no Material tonal harmonisation). */
    public static final int SEED_YELLOW = 0xFFFFFF00;
    /** The earlier (softer) seeded yellow, migrated to {@link #SEED_YELLOW} on upgrade. */
    public static final int LEGACY_YELLOW = 0xFFFFEB00;

    public static final String KEY_SEEDED = "pref_hogu_seeded";
    public static final String KEY_SEEDED_VIVID = "pref_hogu_seeded_vivid";
    public static final String KEY_SEEDED_TEXT = "pref_hogu_seeded_text";
    public static final String KEY_ACCENT = "pref_hogu_color_accent";
    /** Body text + toolbar titles (Material colorOnSurface / onSurfaceVariant), via a text overlay. */
    public static final String KEY_TEXT = "pref_hogu_color_text";
    public static final String KEY_COLOR_ISSUER = "pref_hogu_color_issuer";
    public static final String KEY_COLOR_ACCOUNT = "pref_hogu_color_account";
    public static final String KEY_COLOR_CODE = "pref_hogu_color_code";
    public static final String KEY_RECENT = "pref_hogu_recent_colors";

    private static final int MAX_RECENT = 8;

    private HoguTheme() { }

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static int getColor(Context ctx, String key) {
        return prefs(ctx).getInt(key, UNSET);
    }

    public static boolean isSet(Context ctx, String key) {
        return getColor(ctx, key) != UNSET;
    }

    public static void setColor(Context ctx, String key, int color) {
        prefs(ctx).edit().putInt(key, color).apply();
        if (color != UNSET) {
            pushRecent(ctx, color);
        }
    }

    /** Returns the stored colour, or the resolved Material attribute colour when unset. */
    public static int resolve(View view, String key, @AttrRes int attr) {
        int c = getColor(view.getContext(), key);
        return c != UNSET ? c : MaterialColors.getColor(view, attr);
    }

    /** A circular swatch drawable filled with the given colour, for colour previews. */
    public static GradientDrawable swatch(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(2, 0x55888888);
        return d;
    }

    public static List<Integer> getRecent(Context ctx) {
        List<Integer> out = new ArrayList<>();
        String csv = prefs(ctx).getString(KEY_RECENT, "");
        for (String part : csv.split(",")) {
            if (!part.isEmpty()) {
                try {
                    out.add((int) Long.parseLong(part));
                } catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }

    private static void pushRecent(Context ctx, int color) {
        List<Integer> recent = getRecent(ctx);
        recent.remove((Integer) color);
        recent.add(0, color);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(recent.get(i));
        }
        prefs(ctx).edit().putString(KEY_RECENT, sb.toString()).apply();
    }
}
