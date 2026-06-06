package com.beemdevelopment.aegis.helpers;

import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

import com.beemdevelopment.aegis.R;

/**
 * Curated exact colour palette for the 白い熊 防具 UI page. Each swatch is an exact ARGB colour paired
 * with a pre-baked accent overlay and text overlay (res/values/themes_hogu.xml). Foundation colours are
 * applied by {@link ThemeHelper} via these overlays, so they stay pixel-exact — Material3 only applies
 * exact colours from a fixed compile-time set; arbitrary RGB would be harmonised/approximated.
 */
public final class HoguPalette {
    public static final class Swatch {
        public final int color;
        public final String name;
        @StyleRes public final int accentOverlay;
        @StyleRes public final int textOverlay;

        Swatch(int color, String name, @StyleRes int accentOverlay, @StyleRes int textOverlay) {
            this.color = color;
            this.name = name;
            this.accentOverlay = accentOverlay;
            this.textOverlay = textOverlay;
        }
    }

    public static final Swatch[] SWATCHES = {
            new Swatch(0xFFFFFF00, "Yellow", R.style.ThemeOverlay_Hogu_Accent_Yellow, R.style.ThemeOverlay_Hogu_Text_Yellow),
            new Swatch(0xFFFFFFFF, "White", R.style.ThemeOverlay_Hogu_Accent_White, R.style.ThemeOverlay_Hogu_Text_White),
            new Swatch(0xFFFF9500, "Orange", R.style.ThemeOverlay_Hogu_Accent_Orange, R.style.ThemeOverlay_Hogu_Text_Orange),
            new Swatch(0xFFFF3B30, "Red", R.style.ThemeOverlay_Hogu_Accent_Red, R.style.ThemeOverlay_Hogu_Text_Red),
            new Swatch(0xFFFF2D95, "Pink", R.style.ThemeOverlay_Hogu_Accent_Pink, R.style.ThemeOverlay_Hogu_Text_Pink),
            new Swatch(0xFF00E5FF, "Cyan", R.style.ThemeOverlay_Hogu_Accent_Cyan, R.style.ThemeOverlay_Hogu_Text_Cyan),
            new Swatch(0xFF00E676, "Green", R.style.ThemeOverlay_Hogu_Accent_Green, R.style.ThemeOverlay_Hogu_Text_Green),
            new Swatch(0xFF40A9FF, "Blue", R.style.ThemeOverlay_Hogu_Accent_Blue, R.style.ThemeOverlay_Hogu_Text_Blue),
            new Swatch(0xFFB388FF, "Purple", R.style.ThemeOverlay_Hogu_Accent_Purple, R.style.ThemeOverlay_Hogu_Text_Purple),
            new Swatch(0xFFBDBDBD, "Grey", R.style.ThemeOverlay_Hogu_Accent_Grey, R.style.ThemeOverlay_Hogu_Text_Grey),
    };

    private HoguPalette() { }

    /** The swatch matching an exact colour, or {@code null} if the colour isn't in the palette. */
    @Nullable
    public static Swatch find(int color) {
        for (Swatch s : SWATCHES) {
            if (s.color == color) {
                return s;
            }
        }
        return null;
    }
}
