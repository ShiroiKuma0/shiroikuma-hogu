package com.beemdevelopment.aegis.ui.dialogs;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.HoguPalette;
import com.beemdevelopment.aegis.helpers.HoguTheme;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Exact-colour swatch picker for the 白い熊 防具 UI page: a grid of the curated palette swatches.
 * Tapping a swatch selects it immediately; "Default" resets the role to inherit.
 */
public final class HoguSwatchPickerDialog {
    public interface Listener {
        void onColor(int color);
        void onReset();
    }

    private HoguSwatchPickerDialog() { }

    public static void show(Context ctx, CharSequence title, int current, Listener listener) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);
        int size = Math.round(46 * d);
        int margin = Math.round(7 * d);

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(5);
        grid.setPadding(pad, pad, pad, pad);

        final AlertDialog[] dialog = new AlertDialog[1];
        for (HoguPalette.Swatch swatch : HoguPalette.SWATCHES) {
            View view = new View(ctx);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            view.setLayoutParams(lp);

            GradientDrawable bg = HoguTheme.swatch(swatch.color);
            if (swatch.color == current) {
                bg.setStroke(Math.round(3 * d), 0xFFFFFFFF);
            }
            view.setBackground(bg);
            view.setContentDescription(swatch.name);
            view.setOnClickListener(v -> {
                listener.onColor(swatch.color);
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
            });
            grid.addView(view);
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(grid);

        dialog[0] = new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(scroll)
                .setNeutralButton(R.string.hogu_default, (di, w) -> listener.onReset())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog[0].show();
    }
}
