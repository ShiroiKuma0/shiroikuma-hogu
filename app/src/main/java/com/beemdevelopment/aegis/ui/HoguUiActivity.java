package com.beemdevelopment.aegis.ui;

import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.Theme;
import com.beemdevelopment.aegis.helpers.FontUtil;
import com.beemdevelopment.aegis.helpers.HoguTheme;
import com.beemdevelopment.aegis.helpers.ViewHelper;
import com.beemdevelopment.aegis.ui.dialogs.HoguSwatchPickerDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 白い熊 防具 UI page — a hand-built, sectioned, deeply-indented customization screen
 * (mirrors the sister forks' ThemeActivity) exposing per-element colours and fonts plus a
 * user accent colour. Phase 1: theme, accent, and the main vault-list text elements.
 */
public class HoguUiActivity extends AegisActivity {
    private static final int MAX_FONT_SIZE_SP = 48;
    private static final float SAMPLE_SIZE_SP = 18f;

    private static final int[] WEIGHT_VALUES = {0, 100, 300, 400, 500, 600, 700, 900};
    private static final String[] WEIGHT_LABELS = {"Default", "Thin", "Light", "Regular", "Medium", "SemiBold", "Bold", "Black"};

    private static final Theme[] THEME_VALUES = {Theme.LIGHT, Theme.DARK, Theme.AMOLED, Theme.SYSTEM, Theme.SYSTEM_AMOLED};
    private static final String[] THEME_LABELS = {"Light", "Dark", "AMOLED", "Follow system", "Follow system (AMOLED)"};

    private LinearLayout _holder;
    private int _step;
    private int _base;

    private final Map<String, TextView> _samples = new HashMap<>();
    private String _pendingFontCat;

    private final ActivityResultLauncher<String[]> _fontImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFontPicked);

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
        _step = getResources().getDimensionPixelSize(R.dimen.hogu_indent_step);
        _base = getResources().getDimensionPixelSize(R.dimen.hogu_row_base_padding);

        buildRows();
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

    private void indent(View v, int level) {
        v.setPaddingRelative(_base + level * _step, v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingBottom());
    }

    private int accent() {
        return HoguTheme.resolve(_holder, HoguTheme.KEY_ACCENT, com.google.android.material.R.attr.colorPrimary);
    }

    private void addSection(String title) {
        View v = inflate(R.layout.item_hogu_section);
        TextView label = v.findViewById(R.id.hogu_section_label);
        View rule = v.findViewById(R.id.hogu_section_rule);
        label.setText(title);
        label.setTextColor(accent());
        rule.setBackgroundColor(accent());
        indent(v, 0);
        _holder.addView(v);
    }

    private void addSubgroup(String title, int level) {
        View v = inflate(R.layout.item_hogu_subgroup);
        TextView label = v.findViewById(R.id.hogu_subgroup_label);
        View rule = v.findViewById(R.id.hogu_subgroup_rule);
        label.setText(title);
        label.setTextColor(accent());
        rule.setBackgroundColor(accent());
        indent(v, level);
        _holder.addView(v);
    }

    private void addColorRow(String label, String key, @AttrRes int attrFallback, int level) {
        View v = inflate(R.layout.item_hogu_row);
        TextView lbl = v.findViewById(R.id.hogu_row_label);
        View swatch = v.findViewById(R.id.hogu_row_swatch);
        lbl.setText(label);
        swatch.setVisibility(View.VISIBLE);
        swatch.setBackground(HoguTheme.swatch(HoguTheme.resolve(v, key, attrFallback)));
        v.setOnClickListener(view -> openColorPicker(label, key, attrFallback));
        indent(v, level);
        _holder.addView(v);
    }

    private void addThemeRow(int level) {
        View v = inflate(R.layout.item_hogu_row);
        TextView lbl = v.findViewById(R.id.hogu_row_label);
        TextView val = v.findViewById(R.id.hogu_row_value);
        lbl.setText(R.string.hogu_app_theme);
        val.setVisibility(View.VISIBLE);
        val.setTextColor(accent());
        val.setText(themeLabel(_prefs.getCurrentTheme()));
        v.setOnClickListener(view -> openThemePicker());
        indent(v, level);
        _holder.addView(v);
    }

    private void addFontRow(String cat, int level) {
        View v = inflate(R.layout.item_hogu_row);
        TextView lbl = v.findViewById(R.id.hogu_row_label);
        TextView val = v.findViewById(R.id.hogu_row_value);
        lbl.setText(R.string.hogu_label_font);
        val.setVisibility(View.VISIBLE);
        val.setTextColor(accent());
        val.setText(fontDisplayName(FontUtil.getFamily(this, cat)));
        v.setOnClickListener(view -> openFontPicker(cat));
        indent(v, level);
        _holder.addView(v);
    }

    private void addWeightRow(String cat, int level) {
        View v = inflate(R.layout.item_hogu_row);
        TextView lbl = v.findViewById(R.id.hogu_row_label);
        TextView val = v.findViewById(R.id.hogu_row_value);
        lbl.setText(R.string.hogu_label_weight);
        val.setVisibility(View.VISIBLE);
        val.setTextColor(accent());
        val.setText(weightLabel(FontUtil.getWeight(this, cat)));
        v.setOnClickListener(view -> openWeightPicker(cat));
        indent(v, level);
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
        indent(v, level);
        _holder.addView(v);
    }

    private void addSample(String cat, int level) {
        TextView sample = (TextView) inflate(R.layout.item_hogu_sample);
        sample.setText(R.string.hogu_sample_text);
        _samples.put(cat, sample);
        styleSample(cat);
        indent(sample, level);
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
