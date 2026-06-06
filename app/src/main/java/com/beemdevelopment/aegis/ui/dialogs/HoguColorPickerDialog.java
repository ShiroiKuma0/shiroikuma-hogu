package com.beemdevelopment.aegis.ui.dialogs;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.HoguTheme;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

/**
 * A small self-contained ARGB colour picker (preview + hex field + A/R/G/B sliders + recently-used
 * row) used by the 白い熊 防具 UI page. "Default" resets the element to inherit the theme colour.
 */
public final class HoguColorPickerDialog {
    public interface Listener {
        void onColor(int color);
        void onReset();
    }

    private HoguColorPickerDialog() { }

    public static void show(Context ctx, CharSequence title, int initialColor, Listener listener) {
        LayoutInflater inflater = LayoutInflater.from(ctx);
        View view = inflater.inflate(R.layout.dialog_hogu_color_picker, null);

        View preview = view.findViewById(R.id.color_preview);
        EditText hex = view.findViewById(R.id.color_hex);
        SeekBar seekA = view.findViewById(R.id.color_seek_a);
        SeekBar seekR = view.findViewById(R.id.color_seek_r);
        SeekBar seekG = view.findViewById(R.id.color_seek_g);
        SeekBar seekB = view.findViewById(R.id.color_seek_b);
        LinearLayout recentRow = view.findViewById(R.id.color_recent_row);

        final boolean[] updating = {false};
        final int[] current = {initialColor};

        Runnable syncFromColor = () -> {
            updating[0] = true;
            int c = current[0];
            seekA.setProgress(Color.alpha(c));
            seekR.setProgress(Color.red(c));
            seekG.setProgress(Color.green(c));
            seekB.setProgress(Color.blue(c));
            hex.setText(String.format(Locale.ROOT, "#%08X", c));
            preview.setBackgroundColor(c);
            updating[0] = false;
        };

        Runnable syncFromSeekBars = () -> {
            if (updating[0]) {
                return;
            }
            int c = Color.argb(seekA.getProgress(), seekR.getProgress(), seekG.getProgress(), seekB.getProgress());
            current[0] = c;
            updating[0] = true;
            hex.setText(String.format(Locale.ROOT, "#%08X", c));
            preview.setBackgroundColor(c);
            updating[0] = false;
        };

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    syncFromSeekBars.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        seekA.setOnSeekBarChangeListener(seekListener);
        seekR.setOnSeekBarChangeListener(seekListener);
        seekG.setOnSeekBarChangeListener(seekListener);
        seekB.setOnSeekBarChangeListener(seekListener);

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (updating[0]) {
                    return;
                }
                Integer parsed = parseHex(e.toString());
                if (parsed != null) {
                    current[0] = parsed;
                    updating[0] = true;
                    seekA.setProgress(Color.alpha(parsed));
                    seekR.setProgress(Color.red(parsed));
                    seekG.setProgress(Color.green(parsed));
                    seekB.setProgress(Color.blue(parsed));
                    preview.setBackgroundColor(parsed);
                    updating[0] = false;
                }
            }
        });

        // Recently used swatches
        List<Integer> recent = HoguTheme.getRecent(ctx);
        if (recent.isEmpty()) {
            view.findViewById(R.id.color_recent_label).setVisibility(View.GONE);
            recentRow.setVisibility(View.GONE);
        } else {
            int size = Math.round(28 * ctx.getResources().getDisplayMetrics().density);
            int margin = Math.round(4 * ctx.getResources().getDisplayMetrics().density);
            for (int c : recent) {
                View swatch = new View(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginEnd(margin);
                swatch.setLayoutParams(lp);
                swatch.setBackground(HoguTheme.swatch(c));
                swatch.setOnClickListener(v -> {
                    current[0] = c;
                    syncFromColor.run();
                });
                recentRow.addView(swatch);
            }
        }

        syncFromColor.run();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(view)
                .setNeutralButton(R.string.hogu_default, (d, w) -> listener.onReset())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> listener.onColor(current[0]))
                .show();
    }

    @Nullable
    private static Integer parseHex(String text) {
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 6) {
            s = "FF" + s;
        }
        if (s.length() != 8) {
            return null;
        }
        try {
            return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
