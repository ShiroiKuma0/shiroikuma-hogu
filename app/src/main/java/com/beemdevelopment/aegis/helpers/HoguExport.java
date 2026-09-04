package com.beemdevelopment.aegis.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.util.AtomicFile;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.vault.VaultRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Fork (白い熊 防具): the unified, category-based Export / Import — the single backup of everything
 * this app holds, and the core the 保存復元 automation contract triggers headlessly.
 *
 * <p>The export is <b>one ZIP</b> of plain JSON files — one per category — plus imported fonts and
 * icon packs as real files, plus the vault exactly as it sits on disk (i.e. <b>encrypted</b> whenever
 * the vault is encrypted; this never downgrades the vault's own protection). A {@code manifest.json}
 * names the format, version and the categories present.
 *
 * <p><b>Future-proof by construction:</b> every category is an independent entry; import iterates the
 * <i>selected</i> categories, skips any whose entry is absent, and merges preferences per key — so a
 * missing key keeps its current value and an older app reading a newer export never breaks. In
 * particular {@link Cat#SETTINGS} is a catch-all: any preference upstream Aegis adds later that no
 * sub-category claims lands there automatically.
 *
 * <p>The configured export directory and the automation token live in a separate, device-local prefs
 * file ({@link #EXIMPORT_PREFS}) that is <b>never</b> part of any category, so neither ever travels
 * in a backup ZIP.
 */
public final class HoguExport {
    public static final String FORMAT = "hogu-export";
    public static final int VERSION = 1;

    /**
     * Backup file naming — the 白い熊 family convention (2026-07-25):
     * {@code <english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip}, no version, no
     * {@code -export} infix, so every sister app's backups sort and read uniformly in one shared
     * directory. ONE zip per export, always.
     */
    public static final String EXPORT_PREFIX = "shiroikuma-hogu_";

    /** Device-local prefs: the export directory + the automation switch/token. Never exported. */
    public static final String EXIMPORT_PREFS = "hogu_eximport";
    private static final String KEY_DIR_URI = "dir_uri";

    private static final String MANIFEST = "manifest.json";
    private static final String FONTS_DIR = "fonts/";
    private static final String ICON_PACKS_DIR = "iconpacks/";

    private static final String HOGU_PREFIX = "pref_hogu_";

    private HoguExport() { }

    // ---------------------------------------------------------------------------------------------
    // Categories
    // ---------------------------------------------------------------------------------------------

    /**
     * A selectable export/import category. {@code id} is the ZIP entry name ({@code <id>.json}) and
     * exactly the id accepted in the automation contract's {@code items} extra. A category with a
     * {@code parentId} is a sub-option of that parent; a parent selected <i>without</i> its children
     * means "that category's own data only".
     *
     * <p>{@code defaultSelected} is this app's answer to "does this item start ticked?" — the
     * contract's fourth {@code LIST_CATEGORIES} field ({@code on}/{@code off}, absent = {@code on}).
     * It is the app's decision to state, not the caller's to guess, and it seeds both the caller's
     * picker and this app's own Export/Import sheet from the same source.
     */
    public enum Cat {
        UI("ui", R.string.hogu_eim_cat_ui, null),
        UI_FONTS("ui.fonts", R.string.hogu_eim_cat_ui_fonts, "ui"),
        SETTINGS("settings", R.string.hogu_eim_cat_settings, null),
        SETTINGS_APPEARANCE("settings.appearance", R.string.hogu_eim_cat_settings_appearance, "settings"),
        SETTINGS_BEHAVIOR("settings.behavior", R.string.hogu_eim_cat_settings_behavior, "settings"),
        SETTINGS_SECURITY("settings.security", R.string.hogu_eim_cat_settings_security, "settings"),
        SETTINGS_BACKUPS("settings.backups", R.string.hogu_eim_cat_settings_backups, "settings"),
        VAULT("vault", R.string.hogu_eim_cat_vault, null),
        // Derived counters — rebuilt simply by using the app, so this one starts unticked. The
        // vault itself stays on.
        VAULT_USAGE("vault.usage", R.string.hogu_eim_cat_vault_usage, "vault", false),
        ICON_PACKS("iconpacks", R.string.hogu_eim_cat_iconpacks, null);

        private final String _id;
        @StringRes private final int _labelRes;
        @Nullable private final String _parentId;
        private final boolean _defaultSelected;

        Cat(String id, @StringRes int labelRes, @Nullable String parentId) {
            this(id, labelRes, parentId, true);
        }

        Cat(String id, @StringRes int labelRes, @Nullable String parentId, boolean defaultSelected) {
            _id = id;
            _labelRes = labelRes;
            _parentId = parentId;
            _defaultSelected = defaultSelected;
        }

        public String getId() {
            return _id;
        }

        @StringRes
        public int getLabelRes() {
            return _labelRes;
        }

        @Nullable
        public String getParentId() {
            return _parentId;
        }

        public boolean isChild() {
            return _parentId != null;
        }

        /** Whether this category starts ticked — the {@code on}/{@code off} field of the contract. */
        public boolean isDefaultSelected() {
            return _defaultSelected;
        }

        @Nullable
        public static Cat byId(String id) {
            for (Cat c : values()) {
                if (c._id.equals(id)) {
                    return c;
                }
            }
            return null;
        }

        public static Set<Cat> all() {
            return new LinkedHashSet<>(Arrays.asList(values()));
        }

        /**
         * The default set: every category that starts ticked. This is what an {@code EXPORT_STATE}
         * with no {@code items} extra exports.
         */
        public static Set<Cat> defaults() {
            Set<Cat> out = new LinkedHashSet<>();
            for (Cat c : values()) {
                if (c._defaultSelected) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    // --- which preference keys belong to which settings sub-category -----------------------------
    // Derived from upstream Aegis's own settings screens (res/xml/preferences_*.xml), mapped to the
    // keys those screens actually persist (Preferences.java). Anything not listed here — and not
    // excluded below — falls through to Cat.SETTINGS, so new upstream preferences are carried
    // automatically without touching this file.

    private static final Set<String> KEYS_APPEARANCE = new LinkedHashSet<>(Arrays.asList(
            "pref_current_theme", "pref_dynamic_colors", "pref_lang", "pref_current_view_mode",
            "pref_show_icons", "pref_show_next_code", "pref_expiration_state",
            "pref_code_group_size_string", "pref_account_name_position",
            "pref_shared_issuer_account_name", "pref_current_sort_category"
    ));

    private static final Set<String> KEYS_BEHAVIOR = new LinkedHashSet<>(Arrays.asList(
            "pref_focus_search", "pref_search_behavior_mask", "pref_minimize_on_copy",
            "pref_current_copy_behavior", "pref_haptic_feedback", "pref_groups_multiselect",
            "pref_highlight_entry", "pref_pause_entry"
    ));

    private static final Set<String> KEYS_SECURITY = new LinkedHashSet<>(Arrays.asList(
            "pref_password_reminder_freq", "pref_secure_screen", "pref_tap_to_reveal",
            "pref_tap_to_reveal_time", "pref_auto_lock_mask", "pref_auto_lock", "pref_pin_keyboard",
            "pref_panic_trigger", "pref_warn_time_sync"
    ));

    private static final Set<String> KEYS_BACKUPS = new LinkedHashSet<>(Arrays.asList(
            "pref_android_backups", "pref_backups", "pref_backups_location", "pref_backups_versions",
            "pref_backup_reminder", "pref_plaintext_backup_warning_disabled"
    ));

    private static final Set<String> KEYS_VAULT_USAGE = new LinkedHashSet<>(Arrays.asList(
            "pref_usage_count", "pref_last_used_timestamps", "pref_group_filter_uuids"
    ));

    /**
     * Never exported: onboarding flags, run-time results and timestamps — device-local state that
     * would be wrong (or actively harmful) to carry onto another install.
     */
    private static final Set<String> EXCLUDE = new LinkedHashSet<>(Arrays.asList(
            "pref_intro", "pref_export_latest", "pref_backups_result_builtin",
            "pref_backups_result_android", "pref_backups_reminder_needed",
            "pref_password_reminder_counter", "pref_password_reminder",
            "pref_plaintext_backup_warning_needed",
            // When the automation data door last restored this install
            // (com.beemdevelopment.aegis.automation.AutomationDataService). Device-local by nature,
            // and it is written with commit() precisely so the restore is on disk before the caller
            // is told it worked — carrying it into another install would be meaningless.
            "pref_automation_imported_at"
    ));

    // ---------------------------------------------------------------------------------------------
    // File naming
    // ---------------------------------------------------------------------------------------------

    /** The name of the ZIP to write now — identical for the UI panel and the automation receiver. */
    public static String exportFileName() {
        return EXPORT_PREFIX
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date())
                + ".zip";
    }

    /** True if {@code name} is one of this app's backups. */
    public static boolean isExportFileName(@Nullable String name) {
        return name != null && name.endsWith(".zip") && name.startsWith(EXPORT_PREFIX);
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        }
        if (bytes >= 1L << 10) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / (double) (1L << 10));
        }
        return bytes + " B";
    }

    // ---------------------------------------------------------------------------------------------
    // The configured export directory (a persisted SAF tree)
    // ---------------------------------------------------------------------------------------------

    private static SharedPreferences eximportPrefs(Context context) {
        return context.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public static Uri exportDirUri(Context context) {
        String raw = eximportPrefs(context).getString(KEY_DIR_URI, null);
        if (raw == null) {
            return null;
        }
        try {
            return Uri.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setExportDirUri(Context context, Uri uri) {
        eximportPrefs(context).edit().putString(KEY_DIR_URI, uri.toString()).apply();
    }

    /** The configured export directory, or null when unset / no longer reachable. */
    @Nullable
    public static DocumentFile exportDir(Context context) {
        Uri uri = exportDirUri(context);
        if (uri == null) {
            return null;
        }
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(context, uri);
            return dir != null && dir.isDirectory() ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The newest backup this app wrote into the configured directory, or null. */
    @Nullable
    public static DocumentFile latestExport(Context context) {
        DocumentFile dir = exportDir(context);
        if (dir == null) {
            return null;
        }
        DocumentFile newest = null;
        try {
            for (DocumentFile f : dir.listFiles()) {
                if (f.isFile() && isExportFileName(f.getName())
                        && (newest == null || f.lastModified() > newest.lastModified())) {
                    newest = f;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return newest;
    }

    // ---------------------------------------------------------------------------------------------
    // EXPORT
    // ---------------------------------------------------------------------------------------------

    /** Progress sink: {@code (done, total, categoryLabel)} after each category is written. */
    public interface Progress {
        void onProgress(int done, int total, String label);
    }

    /**
     * Cancellation signal, polled at every entry boundary — the contract's {@code CANCEL_EXPORT}
     * ({@link com.beemdevelopment.aegis.receivers.StateExportReceiver}). The export unwinds at the
     * next boundary with a {@link CancelledException}; nothing is ever interrupted mid-{@code write}.
     */
    public interface Cancellation {
        boolean isCancelled();
    }

    /** Thrown when a {@link Cancellation} fired — the export stopped, its output is incomplete. */
    public static class CancelledException extends IOException {
        public CancelledException() {
            super("cancelled");
        }
    }

    public static String export(Context context, Set<Cat> cats, OutputStream out,
                                @Nullable Progress onProgress) throws IOException, JSONException {
        return export(context, cats, out, onProgress, null);
    }

    /**
     * Write a ZIP of the selected categories to {@code out}. Returns a short human summary.
     *
     * <p>The headless export core: the Export/Import panel and
     * {@link com.beemdevelopment.aegis.receivers.StateExportReceiver} are two thin callers of this —
     * no export logic is duplicated anywhere.
     *
     * <p>{@code cancel}, when given, is polled before every category and before every file copied
     * into the archive, so a cancelled run unwinds promptly and at a clean boundary. The caller owns
     * the half-written output: on {@link CancelledException} it must delete it.
     */
    public static String export(Context context, Set<Cat> cats, OutputStream out,
                                @Nullable Progress onProgress, @Nullable Cancellation cancel)
            throws IOException, JSONException {
        List<Cat> ordered = ordered(cats);
        int total = ordered.size();
        int done = 0;

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            JSONArray ids = new JSONArray();
            for (Cat c : ordered) {
                ids.put(c.getId());
            }
            JSONObject manifest = new JSONObject()
                    .put("format", FORMAT)
                    .put("version", VERSION)
                    .put("app", context.getPackageName())
                    .put("appVersion", appVersion(context))
                    .put("createdTs", System.currentTimeMillis())
                    .put("categories", ids);
            writeEntry(zip, MANIFEST, manifest.toString(2));

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            for (Cat cat : ordered) {
                throwIfCancelled(cancel);
                switch (cat) {
                    case UI:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, true, null));
                        break;
                    case UI_FONTS:
                        writeTree(zip, FontUtil.getFontsDir(context), FONTS_DIR, cancel);
                        break;
                    case SETTINGS:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, false, null));
                        break;
                    case SETTINGS_APPEARANCE:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, false, KEYS_APPEARANCE));
                        break;
                    case SETTINGS_BEHAVIOR:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, false, KEYS_BEHAVIOR));
                        break;
                    case SETTINGS_SECURITY:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, false, KEYS_SECURITY));
                        break;
                    case SETTINGS_BACKUPS:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, false, KEYS_BACKUPS));
                        break;
                    case VAULT:
                        writeVault(context, zip);
                        break;
                    case VAULT_USAGE:
                        writeEntry(zip, cat.getId() + ".json", exportPrefsMatching(prefs, false, KEYS_VAULT_USAGE));
                        break;
                    case ICON_PACKS:
                        writeTree(zip, new File(context.getFilesDir(), "icons"), ICON_PACKS_DIR, cancel);
                        break;
                }
                done++;
                if (onProgress != null) {
                    onProgress.onProgress(done, total, context.getString(cat.getLabelRes()));
                }
            }
        }

        return total + (total == 1 ? " category" : " categories");
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Dump the default preferences as a type-tagged JSON object. When {@code hogu} is true only the
     * fork's own {@code pref_hogu_*} keys are taken; otherwise the fork's keys are always skipped and
     * either the given {@code keys} are taken, or — when {@code keys} is null — every key no
     * sub-category claims (the {@link Cat#SETTINGS} catch-all).
     */
    private static String exportPrefsMatching(SharedPreferences prefs, boolean hogu,
                                              @Nullable Set<String> keys) throws JSONException {
        JSONObject obj = new JSONObject();
        Map<String, ?> all = prefs.getAll();
        // Sorted, so two exports of the same state produce byte-identical JSON.
        for (String k : new TreeSet<>(all.keySet())) {
            if (EXCLUDE.contains(k)) {
                continue;
            }
            boolean isHogu = k.startsWith(HOGU_PREFIX);
            if (hogu != isHogu) {
                continue;
            }
            if (!hogu) {
                if (keys != null) {
                    if (!keys.contains(k)) {
                        continue;
                    }
                } else if (isClaimedBySubCategory(k)) {
                    continue;
                }
            }
            JSONObject e = typed(all.get(k));
            if (e != null) {
                obj.put(k, e);
            }
        }
        return obj.toString(2);
    }

    private static boolean isClaimedBySubCategory(String key) {
        return KEYS_APPEARANCE.contains(key) || KEYS_BEHAVIOR.contains(key)
                || KEYS_SECURITY.contains(key) || KEYS_BACKUPS.contains(key)
                || KEYS_VAULT_USAGE.contains(key);
    }

    @Nullable
    private static JSONObject typed(Object v) throws JSONException {
        JSONObject e = new JSONObject();
        if (v instanceof Boolean) {
            return e.put("t", "b").put("v", v);
        } else if (v instanceof Integer) {
            return e.put("t", "i").put("v", v);
        } else if (v instanceof Long) {
            return e.put("t", "l").put("v", v);
        } else if (v instanceof Float) {
            return e.put("t", "f").put("v", ((Float) v).doubleValue());
        } else if (v instanceof String) {
            return e.put("t", "s").put("v", v);
        } else if (v instanceof Set) {
            JSONArray arr = new JSONArray();
            for (Object o : (Set<?>) v) {
                arr.put(String.valueOf(o));
            }
            return e.put("t", "ss").put("v", arr);
        }
        return null;
    }

    /**
     * The vault exactly as it sits on disk — i.e. still encrypted whenever the vault is encrypted.
     * The backup is therefore never weaker than the app's own storage.
     */
    private static void writeVault(Context context, ZipOutputStream zip) throws IOException {
        File file = new File(context.getFilesDir(), VaultRepository.FILENAME);
        if (!file.isFile()) {
            return;
        }
        byte[] bytes = new AtomicFile(file).readFully();
        zip.putNextEntry(new ZipEntry(Cat.VAULT.getId() + ".json"));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Copy a whole directory tree into the ZIP under {@code prefix}. */
    private static void writeTree(File dir, ZipOutputStream zip, String prefix, String rel,
                                  @Nullable Cancellation cancel) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            throwIfCancelled(cancel);
            String name = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) {
                writeTree(f, zip, prefix, name, cancel);
            } else if (f.isFile()) {
                zip.putNextEntry(new ZipEntry(prefix + name));
                try (InputStream in = new FileInputStream(f)) {
                    copy(in, zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static void writeTree(ZipOutputStream zip, File dir, String prefix,
                                  @Nullable Cancellation cancel) throws IOException {
        if (dir.isDirectory()) {
            writeTree(dir, zip, prefix, "", cancel);
        }
    }

    private static void throwIfCancelled(@Nullable Cancellation cancel) throws CancelledException {
        if (cancel != null && cancel.isCancelled()) {
            throw new CancelledException();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // IMPORT
    // ---------------------------------------------------------------------------------------------

    /** Categories present in a ZIP (from its manifest, falling back to the entries found). */
    public static Set<Cat> categoriesIn(byte[] zip) {
        Map<String, byte[]> files = readZip(zip);
        byte[] manifest = files.get(MANIFEST);
        if (manifest != null) {
            try {
                JSONArray arr = new JSONObject(new String(manifest, StandardCharsets.UTF_8))
                        .optJSONArray("categories");
                if (arr != null) {
                    Set<Cat> set = new LinkedHashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        Cat c = Cat.byId(arr.optString(i));
                        if (c != null) {
                            set.add(c);
                        }
                    }
                    if (!set.isEmpty()) {
                        return set;
                    }
                }
            } catch (JSONException ignored) {
            }
        }

        Set<Cat> set = new LinkedHashSet<>();
        for (Cat c : Cat.values()) {
            if (files.containsKey(c.getId() + ".json")) {
                set.add(c);
            }
        }
        if (hasPrefix(files, FONTS_DIR)) {
            set.add(Cat.UI_FONTS);
        }
        if (hasPrefix(files, ICON_PACKS_DIR)) {
            set.add(Cat.ICON_PACKS);
        }
        return set;
    }

    private static boolean hasPrefix(Map<String, byte[]> files, String prefix) {
        for (String name : files.keySet()) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code zip} carries the vault — importing it replaces this device's vault. */
    public static boolean hasVault(byte[] zip) {
        return readZip(zip).containsKey(Cat.VAULT.getId() + ".json");
    }

    /**
     * Apply the selected categories from a ZIP. Absent entries are skipped; preferences merge per
     * key, so unrelated and device-local values survive. Returns a human summary, one line per
     * category actually applied.
     */
    public static String importFrom(Context context, byte[] zip, Set<Cat> cats) throws IOException, JSONException {
        Map<String, byte[]> files = readZip(zip);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        List<String> parts = new ArrayList<>();

        for (Cat cat : ordered(cats)) {
            int n;
            switch (cat) {
                case UI_FONTS:
                    n = importTree(files, FONTS_DIR, FontUtil.getFontsDir(context));
                    break;
                case ICON_PACKS:
                    n = importTree(files, ICON_PACKS_DIR, new File(context.getFilesDir(), "icons"));
                    break;
                case VAULT: {
                    byte[] data = files.get(cat.getId() + ".json");
                    if (data == null) {
                        continue;
                    }
                    VaultRepository.writeToFile(context, new ByteArrayInputStream(data));
                    n = 1;
                    break;
                }
                default: {
                    byte[] data = files.get(cat.getId() + ".json");
                    if (data == null) {
                        continue;
                    }
                    n = importPrefs(prefs, new String(data, StandardCharsets.UTF_8));
                    break;
                }
            }
            if (n >= 0) {
                parts.add(context.getString(cat.getLabelRes()) + ": " + n);
            }
        }

        FontUtil.invalidateCache();
        if (parts.isEmpty()) {
            return context.getString(R.string.hogu_eim_import_nothing);
        }
        return String.join("\n", parts);
    }

    private static int importPrefs(SharedPreferences prefs, String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        SharedPreferences.Editor ed = prefs.edit(); // merge — never clear
        int n = 0;
        for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
            String k = it.next();
            if (EXCLUDE.contains(k)) {
                continue;
            }
            JSONObject e = obj.optJSONObject(k);
            if (e == null) {
                continue;
            }
            switch (e.optString("t")) {
                case "b": ed.putBoolean(k, e.optBoolean("v")); break;
                case "i": ed.putInt(k, e.optInt("v")); break;
                case "l": ed.putLong(k, e.optLong("v")); break;
                case "f": ed.putFloat(k, (float) e.optDouble("v")); break;
                case "s": ed.putString(k, e.optString("v")); break;
                case "ss": {
                    JSONArray arr = e.optJSONArray("v");
                    Set<String> set = new HashSet<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            set.add(arr.optString(i));
                        }
                    }
                    ed.putStringSet(k, set);
                    break;
                }
                default: continue;
            }
            n++;
        }
        ed.apply();
        return n;
    }

    /** Restore a file tree from the ZIP. Entry names are sanitised — no absolute or {@code ..} paths. */
    private static int importTree(Map<String, byte[]> files, String prefix, File destRoot) throws IOException {
        int n = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String rel = sanitize(name.substring(prefix.length()));
            if (rel == null) {
                continue;
            }
            File dest = new File(destRoot, rel);
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                continue;
            }
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(entry.getValue());
            }
            n++;
        }
        return n;
    }

    @Nullable
    private static String sanitize(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\")) {
            return null;
        }
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return null;
            }
        }
        return rel;
    }

    /** Read every ZIP entry into memory keyed by entry name. */
    private static Map<String, byte[]> readZip(byte[] zip) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                copy(zis, buf);
                out.put(e.getName(), buf.toByteArray());
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Declaration order, so progress and the ZIP read the same on every run. */
    private static List<Cat> ordered(Set<Cat> cats) {
        List<Cat> out = new ArrayList<>();
        for (Cat c : Cat.values()) {
            if (cats.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The direct sub-options of {@code parent}, in declaration order. */
    public static List<Cat> childrenOf(Cat parent) {
        List<Cat> out = new ArrayList<>();
        for (Cat c : Cat.values()) {
            if (parent.getId().equals(c.getParentId())) {
                out.add(c);
            }
        }
        return out;
    }

    /** The top-level categories, in declaration order. */
    public static List<Cat> topLevel() {
        List<Cat> out = new ArrayList<>();
        for (Cat c : Cat.values()) {
            if (!c.isChild()) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The {@code LIST_CATEGORIES} reply body: {@code id<TAB>label<TAB>parent-id<TAB>on|off} per
     * line. The third field is empty for a top-level category — the fields are positional, so the
     * {@code on}/{@code off} default must stay the fourth one either way.
     */
    public static String categoryLines(Context context) {
        StringBuilder sb = new StringBuilder();
        for (Cat c : Cat.values()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(c.getId()).append('\t').append(context.getString(c.getLabelRes()))
                    .append('\t').append(c.getParentId() != null ? c.getParentId() : "")
                    .append('\t').append(c.isDefaultSelected() ? "on" : "off");
        }
        return sb.toString();
    }
}
