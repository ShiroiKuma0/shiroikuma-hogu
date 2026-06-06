package com.beemdevelopment.aegis.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-surface configurable fonts for the 白い熊 防具 UI page. Mirrors the sister-repo font system:
 * each surface ("category") has an independent family / weight / size stored in SharedPreferences.
 * "Unset" means inherit — the TextView's existing typeface / size is left untouched. Fonts can be
 * bundled assets (assets/fonts/*) or user-imported files placed in {@code <filesDir>/fonts/}.
 */
public final class FontUtil {
    public static final String ISSUER = "issuer";
    public static final String ACCOUNT = "account";
    public static final String CODE = "code";

    public static final String DEFAULT = "Default";
    public static final String MONOSPACE = "@monospace";

    private static final String FAMILY_PREFIX = "pref_hogu_font_family_";
    private static final String WEIGHT_PREFIX = "pref_hogu_font_weight_";
    private static final String SIZE_PREFIX = "pref_hogu_font_size_";

    private static final String ASSET_FONTS_DIR = "fonts";
    private static final int SEMIBOLD = 600;

    private static final Map<String, Typeface> _cache = new HashMap<>();

    private FontUtil() { }

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static File getFontsDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "fonts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String getFamily(Context ctx, String cat) {
        return prefs(ctx).getString(FAMILY_PREFIX + cat, "");
    }

    public static int getWeight(Context ctx, String cat) {
        return prefs(ctx).getInt(WEIGHT_PREFIX + cat, 0);
    }

    public static int getSize(Context ctx, String cat) {
        return prefs(ctx).getInt(SIZE_PREFIX + cat, 0);
    }

    public static void setFamily(Context ctx, String cat, String name) {
        prefs(ctx).edit().putString(FAMILY_PREFIX + cat, name == null ? "" : name).apply();
    }

    public static void setWeight(Context ctx, String cat, int weight) {
        prefs(ctx).edit().putInt(WEIGHT_PREFIX + cat, weight).apply();
    }

    public static void setSize(Context ctx, String cat, int sizeSp) {
        prefs(ctx).edit().putInt(SIZE_PREFIX + cat, sizeSp).apply();
    }

    /** Resolves a font name to a Typeface, or {@code null} for the inherit/"Default" sentinel. */
    public static Typeface getTypefaceByName(Context ctx, String name) {
        if (name == null || name.isEmpty() || name.equals(DEFAULT)) {
            return null;
        }
        if (name.equals(MONOSPACE)) {
            return Typeface.MONOSPACE;
        }
        if (_cache.containsKey(name)) {
            return _cache.get(name);
        }
        Typeface tf;
        try {
            tf = Typeface.createFromAsset(ctx.getAssets(), ASSET_FONTS_DIR + "/" + name);
        } catch (Exception ignored) {
            try {
                tf = Typeface.createFromFile(new File(getFontsDir(ctx), name));
            } catch (Exception ignored2) {
                tf = null;
            }
        }
        _cache.put(name, tf);
        return tf;
    }

    /** Returns the resolved typeface for a category (family + weight), or {@code null} when fully unset. */
    public static Typeface getTypeface(Context ctx, String cat) {
        Typeface base = getTypefaceByName(ctx, getFamily(ctx, cat));
        int weight = getWeight(ctx, cat);
        if (weight <= 0) {
            return base;
        }
        Typeface src = base != null ? base : Typeface.DEFAULT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(src, weight, false);
        }
        return Typeface.create(src, weight >= SEMIBOLD ? Typeface.BOLD : Typeface.NORMAL);
    }

    /**
     * Applies the category's font to a TextView. Only attributes the user actually set are touched,
     * so an unset surface keeps the layout's original typeface / size.
     */
    public static void apply(TextView tv, String cat) {
        Context ctx = tv.getContext();
        if (!getFamily(ctx, cat).isEmpty() || getWeight(ctx, cat) > 0) {
            Typeface tf = getTypeface(ctx, cat);
            if (tf != null) {
                tv.setTypeface(tf);
            }
        }
        int size = getSize(ctx, cat);
        if (size > 0) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        }
    }

    /** Builds the picker list: Default, monospace, bundled assets, then imported files (sorted). */
    public static List<String> getFontNames(Context ctx) {
        List<String> names = new ArrayList<>();
        names.add(DEFAULT);
        names.add(MONOSPACE);
        try {
            String[] assets = ctx.getAssets().list(ASSET_FONTS_DIR);
            if (assets != null) {
                List<String> bundled = new ArrayList<>();
                for (String a : assets) {
                    if (isFontFile(a)) {
                        bundled.add(a);
                    }
                }
                Collections.sort(bundled, String.CASE_INSENSITIVE_ORDER);
                names.addAll(bundled);
            }
        } catch (IOException ignored) { }

        File[] files = getFontsDir(ctx).listFiles();
        if (files != null) {
            List<String> external = new ArrayList<>();
            for (File f : files) {
                if (f.isFile() && isFontFile(f.getName())) {
                    external.add(f.getName());
                }
            }
            Collections.sort(external, String.CASE_INSENSITIVE_ORDER);
            names.addAll(external);
        }
        return names;
    }

    private static boolean isFontFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    /** Imports a user-picked font file into the fonts dir. Returns the stored filename. */
    public static String importFont(Context ctx, Uri uri) throws IOException {
        String name = queryDisplayName(ctx, uri);
        if (name == null || !isFontFile(name)) {
            throw new IOException("Not a .ttf/.otf font file");
        }
        File dest = new File(getFontsDir(ctx), name);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("Could not open font stream");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        _cache.remove(name);
        return name;
    }

    private static String queryDisplayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        if (last != null) {
            int slash = last.lastIndexOf('/');
            return slash >= 0 ? last.substring(slash + 1) : last;
        }
        return null;
    }
}
