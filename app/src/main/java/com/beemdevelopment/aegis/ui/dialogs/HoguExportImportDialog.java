package com.beemdevelopment.aegis.ui.dialogs;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.documentfile.provider.DocumentFile;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.HoguExport;
import com.beemdevelopment.aegis.helpers.HoguTheme;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fork (白い熊 防具): the unified, category-based Export / Import panel — the single home for carrying
 * everything this app holds. One checklist drives both directions: Export saves the ticked categories
 * to a .zip; Import applies the ticked categories the chosen .zip contains (absent ones skipped). It
 * also owns the persisted export folder and the "last export" line.
 *
 * <p>Fully self-drawn in the fork's black/accent identity (Material's dialog stroke and text colours
 * don't render the way we need, so the window is made transparent and we own the whole surface). The
 * action row is ArcaneChat-shaped: round pills, Cancel alone on the left, Import / Export on the
 * right.
 *
 * <p>On success an info dialog with a yellow (accent) border reports the result; acknowledging it
 * closes the whole chain — the info dialog, this panel, and the UI settings page behind it. Failures
 * are toasts, and deliberately leave the panel open.
 */
public class HoguExportImportDialog {
    /** The "something needs attention" red, shared with the UI page's export-directory row. */
    public static final int WARN_COLOR = 0xFFFF5252;

    private final AppCompatActivity _activity;
    private final Callbacks _callbacks;
    private final Map<HoguExport.Cat, CheckBox> _checks = new LinkedHashMap<>();

    private final int _accent;
    private final int _text;
    private final int _background;
    private final float _density;

    private Dialog _dialog;
    private TextView _dirValue;
    private TextView _statusTv;

    public interface Callbacks {
        /** Launch the SAF directory picker (the activity owns the result launchers). */
        void launchDirPicker(@Nullable Uri initial);

        /** Launch the "save as" picker — used only when no export directory is configured. */
        void launchSaveAs(String suggestedName);

        /** Launch the file picker for an import. */
        void launchImportPicker();

        /** The configured export directory changed — repaint the UI page's rows. */
        void onDirChanged();

        /** Export/import finished and was acknowledged: close the UI settings page too. */
        void onChainFinished();
    }

    public HoguExportImportDialog(AppCompatActivity activity, Callbacks callbacks) {
        _activity = activity;
        _callbacks = callbacks;
        _density = activity.getResources().getDisplayMetrics().density;
        _accent = HoguTheme.resolve(activity, HoguTheme.KEY_ACCENT,
                com.google.android.material.R.attr.colorPrimary);
        _text = HoguTheme.resolve(activity, HoguTheme.KEY_TEXT,
                com.google.android.material.R.attr.colorOnSurface);
        _background = HoguTheme.resolveAttr(activity,
                com.google.android.material.R.attr.colorSurface, Color.BLACK);
    }

    // ---------------------------------------------------------------------------------------------
    // The panel
    // ---------------------------------------------------------------------------------------------

    public void show() {
        _dialog = new MaterialAlertDialogBuilder(_activity)
                .setView(buildView())
                .setCancelable(true)
                .create();
        _dialog.show();
        if (_dialog.getWindow() != null) {
            _dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public void dismiss() {
        if (_dialog != null && _dialog.isShowing()) {
            _dialog.dismiss();
        }
        _dialog = null;
    }

    public boolean isShowing() {
        return _dialog != null && _dialog.isShowing();
    }

    private View buildView() {
        LinearLayout root = box(dp(20), dp(16), dp(20), dp(20));

        TextView title = text(_activity.getString(R.string.hogu_eim_title), 18f, _accent, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, dp(6));
        root.addView(title);

        TextView desc = text(_activity.getString(R.string.hogu_eim_desc), 13f, _text, false);
        desc.setAlpha(0.85f);
        desc.setPadding(0, 0, 0, dp(10));
        root.addView(desc);

        // Persisted export directory — a bordered, clearly-tappable box that stands out when unset.
        LinearLayout dirBox = new LinearLayout(_activity);
        dirBox.setOrientation(LinearLayout.VERTICAL);
        dirBox.setClickable(true);
        dirBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        dirBox.setBackground(border(10f, 2f));
        dirBox.setOnClickListener(v -> _callbacks.launchDirPicker(HoguExport.exportDirUri(_activity)));
        dirBox.addView(text(_activity.getString(R.string.hogu_eim_dir), 12f, _accent, false));
        _dirValue = text("", 15f, _text, true);
        dirBox.addView(_dirValue);
        LinearLayout.LayoutParams dirLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dirLp.topMargin = dp(6);
        dirLp.bottomMargin = dp(6);
        root.addView(dirBox, dirLp);

        _statusTv = text("", 14f, _text, false);
        _statusTv.setPadding(dp(2), 0, 0, dp(8));
        root.addView(_statusTv);

        root.addView(divider());

        // Every box starts from the category's own default — the same answer the automation
        // contract's LIST_CATEGORIES reports as its fourth `on|off` field, so the in-app sheet and
        // a caller's picker open on exactly the same selection. "Select all" is only pre-ticked
        // when the default really is everything.
        CheckBox selectAll = checkbox(_activity.getString(R.string.hogu_eim_select_all), true, 0);
        selectAll.setChecked(HoguExport.Cat.defaults().size() == HoguExport.Cat.values().length);
        root.addView(selectAll);

        for (HoguExport.Cat cat : HoguExport.topLevel()) {
            CheckBox parent = checkbox(_activity.getString(cat.getLabelRes()), false, 1);
            parent.setChecked(cat.isDefaultSelected());
            _checks.put(cat, parent);
            root.addView(parent);

            List<HoguExport.Cat> children = HoguExport.childrenOf(cat);
            for (HoguExport.Cat child : children) {
                CheckBox cb = checkbox(_activity.getString(child.getLabelRes()), false, 2);
                cb.setChecked(child.isDefaultSelected());
                _checks.put(child, cb);
                root.addView(cb);
            }
            // Sub-options follow their parent's toggle; a child can still be unticked on its own,
            // which is exactly the contract's "parent id without its children" case.
            parent.setOnCheckedChangeListener((btn, checked) -> {
                for (HoguExport.Cat child : children) {
                    CheckBox cb = _checks.get(child);
                    if (cb != null) {
                        cb.setChecked(checked);
                    }
                }
            });
        }

        selectAll.setOnCheckedChangeListener((btn, checked) -> {
            for (CheckBox cb : _checks.values()) {
                cb.setChecked(checked);
            }
        });

        View bottomDivider = divider();
        ((LinearLayout.LayoutParams) bottomDivider.getLayoutParams()).topMargin = dp(8);
        root.addView(bottomDivider);

        // ArcaneChat-style action row: round pills, Cancel alone on the left, the Import / Export
        // actions grouped on the right.
        LinearLayout buttons = new LinearLayout(_activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(14), 0, 0);

        Button cancel = pill(_activity.getString(R.string.hogu_eim_cancel));
        cancel.setOnClickListener(v -> dismiss());
        buttons.addView(cancel);

        buttons.addView(new View(_activity), new LinearLayout.LayoutParams(0, 0, 1f));

        Button importBtn = pill(_activity.getString(R.string.hogu_eim_import));
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importLp.setMarginEnd(dp(8));
        importBtn.setLayoutParams(importLp);
        importBtn.setOnClickListener(v -> onImportClicked());
        buttons.addView(importBtn);

        Button exportBtn = pill(_activity.getString(R.string.hogu_eim_export));
        exportBtn.setOnClickListener(v -> onExportClicked());
        buttons.addView(exportBtn);

        root.addView(buttons);

        refreshStatus();

        // Inset the bordered box from the window edges so all four borders are visible.
        NestedScrollView scroll = new NestedScrollView(_activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(10);
        lp.setMargins(m, m, m, m);
        scroll.addView(root, lp);
        return scroll;
    }

    private Set<HoguExport.Cat> selected() {
        Set<HoguExport.Cat> out = new LinkedHashSet<>();
        for (Map.Entry<HoguExport.Cat, CheckBox> e : _checks.entrySet()) {
            if (e.getValue().isChecked()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Folder + status
    // ---------------------------------------------------------------------------------------------

    /** Called by the host activity when the SAF directory picker returns. */
    public void onDirPicked(Uri uri) {
        try {
            _activity.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        HoguExport.setExportDirUri(_activity, uri);
        refreshStatus();
        _callbacks.onDirChanged();
    }

    private void refreshStatus() {
        if (_dirValue == null) {
            return;
        }
        DocumentFile dir = HoguExport.exportDir(_activity);
        Uri uri = HoguExport.exportDirUri(_activity);
        String name = dir != null ? dir.getName() : (uri != null ? uri.getLastPathSegment() : null);
        _dirValue.setText(name != null ? name : _activity.getString(R.string.hogu_eim_dir_unset));
        _dirValue.setTextColor(name == null ? WARN_COLOR : _text);

        // Red until a directory is set; once it is, the accent (yellow) reports the last export.
        if (dir == null) {
            _statusTv.setText(R.string.hogu_eim_warn_nodir);
            _statusTv.setTextColor(WARN_COLOR);
            _statusTv.setAlpha(1f);
            return;
        }
        DocumentFile newest = HoguExport.latestExport(_activity);
        if (newest == null) {
            _statusTv.setText(R.string.hogu_eim_warn_none);
            _statusTv.setTextColor(_accent);
        } else {
            _statusTv.setText(_activity.getString(R.string.hogu_eim_last, fmtTs(newest.lastModified())));
            _statusTv.setTextColor(_accent);
        }
        _statusTv.setAlpha(1f);
    }

    private static String fmtTs(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(t));
    }

    // ---------------------------------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------------------------------

    private void onExportClicked() {
        if (selected().isEmpty()) {
            toast(_activity.getString(R.string.hogu_eim_none_selected), false);
            return;
        }
        DocumentFile dir = HoguExport.exportDir(_activity);
        if (dir == null) {
            _callbacks.launchSaveAs(HoguExport.exportFileName()); // no folder set -> save-as picker
        } else {
            exportTo(null, dir, HoguExport.exportFileName());
        }
    }

    /** Called by the host activity when the "save as" picker returns. */
    public void onSaveAsPicked(Uri uri) {
        exportTo(uri, null, null);
    }

    private void exportTo(@Nullable Uri uri, @Nullable DocumentFile dir, @Nullable String fileName) {
        final Context ctx = _activity.getApplicationContext();
        final Set<HoguExport.Cat> cats = selected();
        toast(_activity.getString(R.string.hogu_eim_exporting), false); // immediate "started" flash
        new Thread(() -> {
            String name = fileName;
            Uri target = uri;
            long bytes = -1;
            String error = null;
            try {
                if (target == null) {
                    DocumentFile doc = dir.createFile("application/zip", name);
                    if (doc == null) {
                        throw new Exception("could not create file in folder");
                    }
                    target = doc.getUri();
                }
                try (OutputStream out = ctx.getContentResolver().openOutputStream(target)) {
                    if (out == null) {
                        throw new Exception("no output stream");
                    }
                    HoguExport.export(ctx, cats, out, null);
                }
                DocumentFile written = DocumentFile.fromSingleUri(ctx, target);
                if (written != null) {
                    bytes = written.length();
                    if (name == null) {
                        name = written.getName();
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }

            final String fName = name != null ? name : "";
            final long fBytes = bytes;
            final String fError = error;
            final int fCats = cats.size();
            _activity.runOnUiThread(() -> {
                if (fError != null) {
                    toast(_activity.getString(R.string.hogu_eim_export_fail, fError), true);
                    return; // failures leave the panel open
                }
                refreshStatus();
                String detail = fBytes >= 0
                        ? _activity.getString(R.string.hogu_eim_export_done_body,
                                fName, HoguExport.humanSize(fBytes), fCats)
                        : _activity.getString(R.string.hogu_eim_export_done_body_nosize, fName, fCats);
                showInfoDialog(_activity.getString(R.string.hogu_eim_export_done_title), detail,
                        _activity.getString(android.R.string.ok), null);
            });
        }, "hogu-export").start();
    }

    // ---------------------------------------------------------------------------------------------
    // Import
    // ---------------------------------------------------------------------------------------------

    private void onImportClicked() {
        if (selected().isEmpty()) {
            toast(_activity.getString(R.string.hogu_eim_none_selected), false);
            return;
        }
        _callbacks.launchImportPicker();
    }

    /** Called by the host activity when the import file picker returns. */
    public void onImportPicked(Uri uri) {
        final Context ctx = _activity.getApplicationContext();
        final Set<HoguExport.Cat> cats = selected();
        new Thread(() -> {
            byte[] bytes = null;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        buf.write(chunk, 0, n);
                    }
                    bytes = buf.toByteArray();
                }
            } catch (Exception ignored) {
            }
            final byte[] data = bytes;
            _activity.runOnUiThread(() -> {
                if (data == null || data.length == 0) {
                    toast(_activity.getString(R.string.hogu_eim_import_fail, "no input stream"), true);
                    return;
                }
                if (HoguExport.categoriesIn(data).isEmpty()) {
                    toast(_activity.getString(R.string.hogu_eim_import_fail,
                            _activity.getString(R.string.hogu_eim_import_none)), true);
                    return;
                }
                // Restoring the vault REPLACES this device's vault (and its password becomes the
                // backup's). Never do that without an explicit confirmation.
                if (cats.contains(HoguExport.Cat.VAULT) && HoguExport.hasVault(data)) {
                    confirmVaultReplace(() -> runImport(ctx, data, cats));
                } else {
                    runImport(ctx, data, cats);
                }
            });
        }, "hogu-import-read").start();
    }

    private void runImport(Context ctx, byte[] data, Set<HoguExport.Cat> cats) {
        toast(_activity.getString(R.string.hogu_eim_importing), false);
        new Thread(() -> {
            String summary = null;
            String error = null;
            try {
                summary = HoguExport.importFrom(ctx, data, cats);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String fSummary = summary;
            final String fError = error;
            _activity.runOnUiThread(() -> {
                if (fError != null) {
                    toast(_activity.getString(R.string.hogu_eim_import_fail, fError), true);
                    return; // failures leave the panel open
                }
                showInfoDialog(_activity.getString(R.string.hogu_eim_import_done_title),
                        _activity.getString(R.string.hogu_eim_import_done_body, fSummary),
                        _activity.getString(R.string.hogu_eim_restart_later),
                        _activity.getString(R.string.hogu_eim_restart_now));
            });
        }, "hogu-import").start();
    }

    private void confirmVaultReplace(Runnable onProceed) {
        LinearLayout box = box(dp(22), dp(20), dp(22), dp(16));
        box.addView(text(_activity.getString(R.string.hogu_eim_vault_warn_title), 19f, _accent, true));
        TextView body = text(_activity.getString(R.string.hogu_eim_vault_warn_body), 14f, _accent, false);
        body.setPadding(0, dp(10), 0, 0);
        box.addView(body);

        NestedScrollView scroll = new NestedScrollView(_activity);
        scroll.addView(box);
        Dialog dialog = new MaterialAlertDialogBuilder(_activity)
                .setView(scroll)
                .setCancelable(true)
                .create();

        LinearLayout btns = new LinearLayout(_activity);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);

        Button cancel = pill(_activity.getString(R.string.hogu_eim_cancel));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        cancel.setLayoutParams(lp);
        cancel.setOnClickListener(v -> dialog.dismiss());
        btns.addView(cancel);

        Button proceed = pill(_activity.getString(R.string.hogu_eim_vault_warn_proceed));
        proceed.setOnClickListener(v -> {
            dialog.dismiss();
            onProceed.run();
        });
        btns.addView(proceed);
        box.addView(btns);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The result dialog — black surface, accent (yellow) border, accent text
    // ---------------------------------------------------------------------------------------------

    /**
     * The "finished" info dialog. Acknowledging it closes the whole chain: this dialog, the
     * Export/Import panel beneath it, and the UI settings page behind that. When
     * {@code restartLabel} is non-null a second pill restarts the app instead.
     */
    private void showInfoDialog(String title, String body, String okLabel, @Nullable String restartLabel) {
        LinearLayout box = box(dp(22), dp(20), dp(22), dp(16));
        box.addView(text(title, 19f, _accent, true));
        TextView bodyTv = text(body, 14f, _accent, false);
        bodyTv.setPadding(0, dp(10), 0, 0);
        box.addView(bodyTv);

        NestedScrollView scroll = new NestedScrollView(_activity);
        scroll.addView(box);
        Dialog dialog = new MaterialAlertDialogBuilder(_activity)
                .setView(scroll)
                .setCancelable(false)
                .create();

        LinearLayout btns = new LinearLayout(_activity);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);

        Button ok = pill(okLabel);
        ok.setPadding(dp(18), dp(8), dp(18), dp(8));
        if (restartLabel != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            ok.setLayoutParams(lp);
        }
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            finishChain();
        });
        btns.addView(ok);

        if (restartLabel != null) {
            Button restart = pill(restartLabel);
            restart.setPadding(dp(18), dp(8), dp(18), dp(8));
            restart.setOnClickListener(v -> restartApp(_activity.getApplicationContext()));
            btns.addView(restart);
        }
        box.addView(btns);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** Close the panel, then the UI settings page — the whole chain, in one acknowledgement. */
    private void finishChain() {
        dismiss();
        _callbacks.onChainFinished();
    }

    private static void restartApp(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(context.getPackageName());
        if (intent == null || intent.getComponent() == null) {
            return;
        }
        context.startActivity(Intent.makeRestartActivityTask(intent.getComponent()));
        Runtime.getRuntime().exit(0);
    }

    // ---------------------------------------------------------------------------------------------
    // View helpers (themed)
    // ---------------------------------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * _density);
    }

    /** The bordered surface every dialog in this flow sits on: black fill, accent stroke. */
    private LinearLayout box(int l, int t, int r, int b) {
        LinearLayout box = new LinearLayout(_activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(l, t, r, b);
        box.setBackground(border(16f, 2f));
        return box;
    }

    private GradientDrawable border(float radiusDp, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusDp * _density);
        d.setColor(_background);
        d.setStroke((int) (strokeDp * _density), _accent);
        return d;
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(_activity);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private CheckBox checkbox(String label, boolean bold, int indentLevel) {
        CheckBox cb = new CheckBox(_activity);
        cb.setText(label);
        cb.setTextColor(_text);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        if (bold) {
            cb.setTypeface(Typeface.DEFAULT_BOLD);
        }
        cb.setButtonTintList(ColorStateList.valueOf(_accent));
        cb.setPaddingRelative(dp(8), dp(6), 0, dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(16 * indentLevel));
        cb.setLayoutParams(lp);
        return cb;
    }

    private View divider() {
        View v = new View(_activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(_accent);
        v.setAlpha(0.4f);
        return v;
    }

    /**
     * A round-pill outline button — the ArcaneChat dialog action shape: fully rounded ends,
     * wrap-content width with generous side padding.
     */
    private Button pill(String label) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * _density); // > half the height -> a pill
        bg.setColor(_background);
        bg.setStroke((int) (1.5f * _density), _accent);

        Button b = new Button(_activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(_accent);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        return b;
    }

    private void toast(String msg, boolean long_) {
        Toast.makeText(_activity, msg, long_ ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    /** Copies the automation token to the clipboard — used by the UI page's token row. */
    public static void copyToClipboard(Context context, String label, String value) {
        ClipboardManager cb = context.getSystemService(ClipboardManager.class);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText(label, value));
        }
    }
}
