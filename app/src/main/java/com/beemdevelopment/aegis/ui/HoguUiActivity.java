package com.beemdevelopment.aegis.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.Theme;
import com.beemdevelopment.aegis.helpers.AutomationAuth;
import com.beemdevelopment.aegis.helpers.FontUtil;
import com.beemdevelopment.aegis.helpers.HoguExport;
import com.beemdevelopment.aegis.helpers.HoguTheme;
import com.beemdevelopment.aegis.helpers.ViewHelper;
import com.beemdevelopment.aegis.ui.dialogs.HoguExportImportDialog;
import com.beemdevelopment.aegis.ui.dialogs.HoguSwatchPickerDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 白い熊 防具 UI page — a hand-built, sectioned, deeply-indented customization screen in the kxkb
 * settings look: big bold accent section headings with a <b>text-wide</b> underline, thin full-width
 * spacers between sections, sub-sections one step in, and tight indented rows.
 *
 * <p>The first section is Export / Import: the persisted backup directory (red until it is set), the
 * Export/Import panel, and — directly below them, never as a section of its own — the 保存復元
 * automation switch and token, because this is a backup feature and 白い熊 finds it where backup
 * lives, identically in every sister app.
 */
public class HoguUiActivity extends AegisActivity implements HoguExportImportDialog.Callbacks {
    private static final int MAX_FONT_SIZE_SP = 48;
    private static final float SAMPLE_SIZE_SP = 18f;

    private static final int[] WEIGHT_VALUES = {0, 100, 300, 400, 500, 600, 700, 900};
    private static final String[] WEIGHT_LABELS = {"Default", "Thin", "Light", "Regular", "Medium", "SemiBold", "Bold", "Black"};

    private static final Theme[] THEME_VALUES = {Theme.LIGHT, Theme.DARK, Theme.AMOLED, Theme.SYSTEM, Theme.SYSTEM_AMOLED};
    private static final String[] THEME_LABELS = {"Light", "Dark", "AMOLED", "Follow system", "Follow system (AMOLED)"};

    private LinearLayout _holder;
    private int _step;
    private int _base;
    private boolean _firstSection;
    private boolean _renderedAllFilesAccess;

    private final Map<String, TextView> _samples = new HashMap<>();
    private String _pendingFontCat;

    @Nullable
    private HoguExportImportDialog _eximport;

    private final ActivityResultLauncher<String[]> _fontImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFontPicked);

    private final ActivityResultLauncher<Uri> _dirPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onDirPicked);

    private final ActivityResultLauncher<String> _saveAsLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri != null && _eximport != null) {
                    _eximport.onSaveAsPicked(uri);
                }
            });

    private final ActivityResultLauncher<String[]> _importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && _eximport != null) {
                    _eximport.onImportPicked(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (abortIfOrphan(savedInstanceState)) {
            return;
        }
        setContentView(R.layout.activity_hogu_ui);
        setSupportActionBar(findViewById(R.id.toolbar));
        ViewHelper.setupAppBarInsets(findViewById(R.id.app_bar_layout));
        setTitle(R.string.pref_section_hogu_ui_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        _holder = findViewById(R.id.hogu_holder);
        _base = getResources().getDimensionPixelSize(R.dimen.hogu_indent_base);
        _step = getResources().getDimensionPixelSize(R.dimen.hogu_indent_step);

        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the All-files-access settings screen: repaint the automation rows. Only
        // when the grant actually changed — a rebuild lists the SAF backup directory, which is not
        // something to redo on every resume.
        if (_holder != null && _holder.getChildCount() > 0 && _renderedAllFilesAccess != hasAllFilesAccess()) {
            buildRows();
        }
    }

    @Override
    protected void onDestroy() {
        if (_eximport != null) {
            _eximport.dismiss(); // never leak the panel's window when the page goes away
            _eximport = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // region Row building

    private void buildRows() {
        _samples.clear();
        _holder.removeAllViews();
        _firstSection = true;

        addSection(getString(R.string.hogu_section_eximport));
        addExportDirRow();
        addExportImportRow();
        addAutomationRows();

        addSection(getString(R.string.hogu_section_foundation));
        addThemeRow(1);
        addColorRow(getString(R.string.hogu_accent_color), HoguTheme.KEY_ACCENT,
                com.google.android.material.R.attr.colorPrimary, 1);
        addColorRow(getString(R.string.hogu_text_color), HoguTheme.KEY_TEXT,
                com.google.android.material.R.attr.colorOnSurface, 1);

        addSection(getString(R.string.hogu_section_entries));
        addTextElement(getString(R.string.hogu_element_issuer), FontUtil.ISSUER, HoguTheme.KEY_COLOR_ISSUER,
                com.google.android.material.R.attr.colorOnSurface);
        addTextElement(getString(R.string.hogu_element_account), FontUtil.ACCOUNT, HoguTheme.KEY_COLOR_ACCOUNT,
                com.google.android.material.R.attr.colorOnSurface);
        addTextElement(getString(R.string.hogu_element_code), FontUtil.CODE, HoguTheme.KEY_COLOR_CODE,
                R.attr.colorCode);
    }

    private void addTextElement(String name, String cat, String colorKey, @AttrRes int colorAttr) {
        addSubgroup(name, 1);
        addColorRow(getString(R.string.hogu_label_color), colorKey, colorAttr, 2);
        addFontRow(cat, 2);
        addWeightRow(cat, 2);
        addSizeRow(cat, 2);
        addSample(cat, 2);
    }

    private View inflate(int layout) {
        return getLayoutInflater().inflate(layout, _holder, false);
    }

    /** Section / sub-section indent: 36dp at level 0, one 18dp step per level below that. */
    private void indentHeading(View v, int level) {
        v.setPaddingRelative(_base + level * _step, v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingBottom());
    }

    /** Row indent: one step deeper than the heading it sits under (72dp under a section, 90dp under a sub-section). */
    private void indentRow(View v, int level) {
        v.setPaddingRelative(_base + (level + 1) * _step, v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingBottom());
    }

    private int accent() {
        return HoguTheme.resolve(_holder, HoguTheme.KEY_ACCENT, com.google.android.material.R.attr.colorPrimary);
    }

    private int textColor() {
        return HoguTheme.resolve(_holder, HoguTheme.KEY_TEXT, com.google.android.material.R.attr.colorOnSurface);
    }

    private void addSection(String title) {
        View v = inflate(R.layout.item_hogu_section);
        TextView label = v.findViewById(R.id.hogu_section_label);
        View rule = v.findViewById(R.id.hogu_section_rule);
        View spacer = v.findViewById(R.id.hogu_section_spacer);
        label.setText(title);
        label.setTextColor(accent());
        rule.setBackgroundColor(accent());
        // The thin full-width spacer separates sections — the first one has nothing above it.
        if (_firstSection) {
            spacer.setVisibility(View.GONE);
            _firstSection = false;
        } else {
            spacer.setBackgroundColor(accent());
        }
        indentHeading(v.findViewById(R.id.hogu_section_box), 0);
        _holder.addView(v);
    }

    private void addSubgroup(String title, int level) {
        View v = inflate(R.layout.item_hogu_subgroup);
        TextView label = v.findViewById(R.id.hogu_subgroup_label);
        View rule = v.findViewById(R.id.hogu_subgroup_rule);
        label.setText(title);
        label.setTextColor(accent());
        rule.setBackgroundColor(accent());
        indentHeading(v, level);
        _holder.addView(v);
    }

    /** A plain row: label (+ optional summary), indented, clickable. */
    private View newRow(String label, @Nullable String summary, int summaryColor, int level) {
        View v = inflate(R.layout.item_hogu_row);
        TextView lbl = v.findViewById(R.id.hogu_row_label);
        lbl.setText(label);
        if (summary != null) {
            TextView sum = v.findViewById(R.id.hogu_row_summary);
            sum.setVisibility(View.VISIBLE);
            sum.setText(summary);
            sum.setTextColor(summaryColor);
        }
        indentRow(v, level);
        return v;
    }

    private int dimText() {
        int c = textColor();
        return (c & 0x00FFFFFF) | 0xB3000000; // ~70% alpha — the kxkb "dim" summary tone
    }

    private void addColorRow(String label, String key, @AttrRes int attrFallback, int level) {
        View v = newRow(label, null, 0, level);
        View swatch = v.findViewById(R.id.hogu_row_swatch);
        swatch.setVisibility(View.VISIBLE);
        swatch.setBackground(HoguTheme.swatch(HoguTheme.resolve(v, key, attrFallback)));
        v.setOnClickListener(view -> openColorPicker(label, key, attrFallback));
        _holder.addView(v);
    }

    private void addThemeRow(int level) {
        View v = newRow(getString(R.string.hogu_app_theme), null, 0, level);
        TextView val = v.findViewById(R.id.hogu_row_value);
        val.setVisibility(View.VISIBLE);
        val.setTextColor(accent());
        val.setText(themeLabel(_prefs.getCurrentTheme()));
        v.setOnClickListener(view -> openThemePicker());
        _holder.addView(v);
    }

    private void addFontRow(String cat, int level) {
        View v = newRow(getString(R.string.hogu_label_font), null, 0, level);
        TextView val = v.findViewById(R.id.hogu_row_value);
        val.setVisibility(View.VISIBLE);
        val.setTextColor(accent());
        val.setText(fontDisplayName(FontUtil.getFamily(this, cat)));
        v.setOnClickListener(view -> openFontPicker(cat));
        _holder.addView(v);
    }

    private void addWeightRow(String cat, int level) {
        View v = newRow(getString(R.string.hogu_label_weight), null, 0, level);
        TextView val = v.findViewById(R.id.hogu_row_value);
        val.setVisibility(View.VISIBLE);
        val.setTextColor(accent());
        val.setText(weightLabel(FontUtil.getWeight(this, cat)));
        v.setOnClickListener(view -> openWeightPicker(cat));
        _holder.addView(v);
    }

    private void addSizeRow(String cat, int level) {
        View v = inflate(R.layout.item_hogu_seek);
        TextView lbl = v.findViewById(R.id.hogu_seek_label);
        TextView val = v.findViewById(R.id.hogu_seek_value);
        SeekBar bar = v.findViewById(R.id.hogu_seek_bar);
        lbl.setText(R.string.hogu_label_size);
        val.setTextColor(accent());
        bar.setMax(MAX_FONT_SIZE_SP);
        int size = FontUtil.getSize(this, cat);
        bar.setProgress(size);
        val.setText(sizeLabel(size));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    FontUtil.setSize(HoguUiActivity.this, cat, progress);
                    val.setText(sizeLabel(progress));
                    styleSample(cat);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        indentRow(v, level);
        _holder.addView(v);
    }

    private void addSample(String cat, int level) {
        TextView sample = (TextView) inflate(R.layout.item_hogu_sample);
        sample.setText(R.string.hogu_sample_text);
        _samples.put(cat, sample);
        styleSample(cat);
        indentRow(sample, level);
        _holder.addView(sample);
    }

    private void styleSample(String cat) {
        TextView sample = _samples.get(cat);
        if (sample == null) {
            return;
        }
        sample.setTypeface(Typeface.DEFAULT);
        sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, SAMPLE_SIZE_SP);
        FontUtil.apply(sample, cat);
        sample.setTextColor(HoguTheme.resolve(sample, colorKeyForCat(cat), colorAttrForCat(cat)));
    }

    private String colorKeyForCat(String cat) {
        switch (cat) {
            case FontUtil.ISSUER: return HoguTheme.KEY_COLOR_ISSUER;
            case FontUtil.ACCOUNT: return HoguTheme.KEY_COLOR_ACCOUNT;
            default: return HoguTheme.KEY_COLOR_CODE;
        }
    }

    @AttrRes
    private int colorAttrForCat(String cat) {
        return cat.equals(FontUtil.CODE) ? R.attr.colorCode : com.google.android.material.R.attr.colorOnSurface;
    }

    // endregion

    // region Export / Import

    /**
     * The persisted backup directory. Red — here on the page, exactly as in the panel — for as long
     * as it is unset; the accent (yellow) once it points somewhere real.
     */
    private void addExportDirRow() {
        DocumentFile dir = HoguExport.exportDir(this);
        Uri uri = HoguExport.exportDirUri(this);
        String name = dir != null ? dir.getName() : (uri != null ? uri.getLastPathSegment() : null);
        boolean unset = name == null;

        View v = newRow(getString(R.string.hogu_eim_dir_row),
                unset ? getString(R.string.hogu_eim_warn_nodir) : lastExportSummary(),
                unset ? HoguExportImportDialog.WARN_COLOR : accent(), 1);
        TextView val = v.findViewById(R.id.hogu_row_value);
        val.setVisibility(View.VISIBLE);
        val.setText(unset ? getString(R.string.hogu_eim_dir_unset) : name);
        val.setTextColor(unset ? HoguExportImportDialog.WARN_COLOR : accent());
        v.setOnClickListener(view -> _dirPickerLauncher.launch(HoguExport.exportDirUri(this)));
        _holder.addView(v);
    }

    /** The directory is queried on opening the page, so the newest backup shows without a tap. */
    private String lastExportSummary() {
        DocumentFile newest = HoguExport.latestExport(this);
        if (newest == null) {
            return getString(R.string.hogu_eim_warn_none);
        }
        return getString(R.string.hogu_eim_last, new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date(newest.lastModified())));
    }

    private void addExportImportRow() {
        View v = newRow(getString(R.string.hogu_eim_open), getString(R.string.hogu_eim_open_desc), dimText(), 1);
        v.setOnClickListener(view -> openExportImport());
        _holder.addView(v);
    }

    private void openExportImport() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        _eximport = new HoguExportImportDialog(this, this);
        _eximport.show();
    }

    // --- HoguExportImportDialog.Callbacks ---

    @Override
    public void launchDirPicker(@Nullable Uri initial) {
        _dirPickerLauncher.launch(initial);
    }

    @Override
    public void launchSaveAs(String suggestedName) {
        _saveAsLauncher.launch(suggestedName);
    }

    @Override
    public void launchImportPicker() {
        _importLauncher.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
    }

    @Override
    public void onDirChanged() {
        buildRows();
    }

    @Override
    public void onChainFinished() {
        _eximport = null;
        finish();
    }

    private void onDirPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        if (_eximport != null && _eximport.isShowing()) {
            _eximport.onDirPicked(uri); // takes the permission, repaints the panel and this page
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        HoguExport.setExportDirUri(this, uri);
        buildRows();
    }

    // endregion

    // region 保存復元 automation

    /**
     * The three automation rows, appended directly below the Export / Import rows. Never a section
     * of its own: this is a backup feature, so it lives where backup lives — the same place in every
     * sister app. See {@link com.beemdevelopment.aegis.receivers.StateExportReceiver} and
     * {@link com.beemdevelopment.aegis.automation.AutomationProvider}.
     *
     * <p>Contract v2 order: the master switch (default ON), 「Use authorization token?」 (default
     * OFF), and the token itself — <b>shown only when it is being asked for</b>. A 48-character
     * secret sitting under an off switch invites 白い熊 to paste it somewhere it will do nothing.
     */
    private void addAutomationRows() {
        boolean on = AutomationAuth.isEnabled(this);
        _renderedAllFilesAccess = hasAllFilesAccess();
        boolean warn = on && !_renderedAllFilesAccess;

        View v = newRow(getString(R.string.hogu_auto_switch),
                getString(warn ? R.string.hogu_auto_no_storage : R.string.hogu_auto_switch_desc),
                warn ? HoguExportImportDialog.WARN_COLOR : dimText(), 1);
        MaterialSwitch sw = v.findViewById(R.id.hogu_row_switch);
        sw.setVisibility(View.VISIBLE);
        sw.setChecked(on);
        v.setOnClickListener(view -> {
            if (warn) {
                openAllFilesAccess();
                return;
            }
            boolean checked = !AutomationAuth.isEnabled(this);
            AutomationAuth.setEnabled(this, checked);
            if (checked && !hasAllFilesAccess()) {
                askAllFilesAccess();
            } else {
                buildRows();
            }
        });
        _holder.addView(v);

        addRequireTokenRow();
        if (AutomationAuth.isTokenRequired(this)) {
            addTokenRow();
        }
    }

    /**
     * 「Use authorization token?」 — default OFF. Off means any sister app may drive the automation;
     * on means a caller must also present the token below. The data door checks the caller's package
     * name, uid and signing certificate either way, which is what made the token optional rather
     * than merely weaker — see
     * {@link com.beemdevelopment.aegis.automation.AutomationCallers}.
     */
    private void addRequireTokenRow() {
        boolean on = AutomationAuth.isTokenRequired(this);
        View v = newRow(getString(R.string.hogu_auto_require_token),
                getString(R.string.hogu_auto_require_token_desc), dimText(), 1);
        MaterialSwitch sw = v.findViewById(R.id.hogu_row_switch);
        sw.setVisibility(View.VISIBLE);
        sw.setChecked(on);
        v.setOnClickListener(view -> {
            AutomationAuth.setTokenRequired(this, !AutomationAuth.isTokenRequired(this));
            buildRows(); // the token row appears/disappears with it
        });
        _holder.addView(v);
    }

    /** Tap = copy the full token; "Regenerate" on the right = a fresh secret (revokes pasted copies). */
    private void addTokenRow() {
        String token = AutomationAuth.getOrCreateToken(this);
        View v = newRow(getString(R.string.hogu_auto_token), AutomationAuth.abbreviate(token), dimText(), 1);
        TextView summary = v.findViewById(R.id.hogu_row_summary);
        summary.setTypeface(Typeface.MONOSPACE);

        TextView action = v.findViewById(R.id.hogu_row_action);
        action.setVisibility(View.VISIBLE);
        action.setText(R.string.hogu_auto_token_regenerate);
        action.setTextColor(accent());
        action.setOnClickListener(view -> confirmRegenerateToken());

        v.setOnClickListener(view -> {
            HoguExportImportDialog.copyToClipboard(this, "automation_token",
                    AutomationAuth.getOrCreateToken(this));
            Toast.makeText(this, R.string.hogu_auto_token_copied, Toast.LENGTH_SHORT).show();
        });
        _holder.addView(v);
    }

    private void confirmRegenerateToken() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hogu_auto_token_regen_title)
                .setMessage(R.string.hogu_auto_token_regen_msg)
                .setPositiveButton(R.string.hogu_auto_token_regenerate, (d, which) -> {
                    AutomationAuth.regenerateToken(this);
                    buildRows();
                    Toast.makeText(this, R.string.hogu_auto_token_regenerated, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** All-Files-Access: needed only to write a caller-supplied absolute backup directory. */
    private static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager();
    }

    private void askAllFilesAccess() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hogu_auto_storage_title)
                .setMessage(R.string.hogu_auto_storage_msg)
                .setPositiveButton(R.string.hogu_auto_storage_open, (d, which) -> openAllFilesAccess())
                .setNegativeButton(R.string.hogu_auto_storage_skip, null)
                .setOnDismissListener(d -> buildRows()) // repaint the switch row either way
                .show();
    }

    private void openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Intent direct = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(direct);
        } catch (Exception e) {
            // Some OEM builds refuse the per-app deep link; fall back to the full list.
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    // endregion

    // region Pickers

    private void openColorPicker(CharSequence title, String key, @AttrRes int attrFallback) {
        int current = HoguTheme.getColor(this, key);
        HoguSwatchPickerDialog.show(this, title, current, new HoguSwatchPickerDialog.Listener() {
            @Override public void onColor(int color) {
                HoguTheme.setColor(HoguUiActivity.this, key, color);
                refreshAfterColorChange(key);
            }
            @Override public void onReset() {
                HoguTheme.setColor(HoguUiActivity.this, key, HoguTheme.UNSET);
                refreshAfterColorChange(key);
            }
        });
    }

    private void refreshAfterColorChange(String key) {
        if (key.equals(HoguTheme.KEY_ACCENT) || key.equals(HoguTheme.KEY_TEXT)) {
            // Accent + text drive the whole Material3 palette (see ThemeHelper), so recreate the
            // activity to re-theme this page's own chrome immediately too.
            recreate();
        } else {
            buildRows();
        }
    }

    private void openThemePicker() {
        Theme current = _prefs.getCurrentTheme();
        int checked = 0;
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hogu_app_theme)
                .setSingleChoiceItems(THEME_LABELS, checked, (d, which) -> {
                    _prefs.setCurrentTheme(THEME_VALUES[which]);
                    d.dismiss();
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openWeightPicker(String cat) {
        int current = FontUtil.getWeight(this, cat);
        int checked = 0;
        for (int i = 0; i < WEIGHT_VALUES.length; i++) {
            if (WEIGHT_VALUES[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hogu_weight_title)
                .setSingleChoiceItems(WEIGHT_LABELS, checked, (d, which) -> {
                    FontUtil.setWeight(this, cat, WEIGHT_VALUES[which]);
                    d.dismiss();
                    buildRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openFontPicker(String cat) {
        final List<String> names = FontUtil.getFontNames(this);
        List<String> display = new ArrayList<>();
        for (String n : names) {
            display.add(fontDisplayName(n));
        }
        display.add(getString(R.string.hogu_add_font));

        String currentFamily = FontUtil.getFamily(this, cat);
        int checked = names.indexOf(currentFamily.isEmpty() ? FontUtil.DEFAULT : currentFamily);
        if (checked < 0) {
            checked = 0;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, display) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                CheckedTextView tv = (CheckedTextView) super.getView(position, convertView, parent);
                if (position < names.size()) {
                    Typeface tf = FontUtil.getTypefaceByName(HoguUiActivity.this, names.get(position));
                    tv.setTypeface(tf != null ? tf : Typeface.DEFAULT);
                } else {
                    tv.setTypeface(Typeface.DEFAULT);
                }
                return tv;
            }
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hogu_font_picker_title)
                .setSingleChoiceItems(adapter, checked, (d, which) -> {
                    if (which < names.size()) {
                        String picked = names.get(which);
                        FontUtil.setFamily(this, cat, picked.equals(FontUtil.DEFAULT) ? "" : picked);
                        d.dismiss();
                        buildRows();
                    } else {
                        d.dismiss();
                        _pendingFontCat = cat;
                        _fontImportLauncher.launch(new String[]{"*/*"});
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onFontPicked(Uri uri) {
        if (uri == null || _pendingFontCat == null) {
            _pendingFontCat = null;
            return;
        }
        try {
            String name = FontUtil.importFont(this, uri);
            FontUtil.setFamily(this, _pendingFontCat, name);
            Toast.makeText(this, getString(R.string.hogu_font_imported, name), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, R.string.hogu_font_import_failed, Toast.LENGTH_SHORT).show();
        }
        _pendingFontCat = null;
        buildRows();
    }

    // endregion

    // region Labels

    private String themeLabel(Theme theme) {
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == theme) {
                return THEME_LABELS[i];
            }
        }
        return THEME_LABELS[0];
    }

    private String weightLabel(int weight) {
        for (int i = 0; i < WEIGHT_VALUES.length; i++) {
            if (WEIGHT_VALUES[i] == weight) {
                return WEIGHT_LABELS[i];
            }
        }
        return WEIGHT_LABELS[0];
    }

    private String fontDisplayName(String name) {
        if (name == null || name.isEmpty() || name.equals(FontUtil.DEFAULT)) {
            return FontUtil.DEFAULT;
        }
        if (name.equals(FontUtil.MONOSPACE)) {
            return "Monospace";
        }
        return name;
    }

    private String sizeLabel(int size) {
        return size > 0 ? getString(R.string.hogu_size_value, size) : getString(R.string.hogu_size_default);
    }

    // endregion
}
