package com.beemdevelopment.aegis.helpers;

import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatActivity;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.Theme;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import java.util.Map;

public class ThemeHelper {
    private final AppCompatActivity _activity;
    private final Preferences _prefs;

    public ThemeHelper(AppCompatActivity activity, Preferences prefs) {
        _activity = activity;
        _prefs = prefs;
    }

    /**
     * Sets the theme of the activity. The actual style that is set is picked from the
     * given map, based on the theme configured by the user.
     */
    public void setTheme(Map<Theme, Integer> themeMap) {
        int theme = themeMap.get(getConfiguredTheme());
        _activity.setTheme(theme);

        // 白い熊 防具: apply the chosen accent + text colours as EXACT static overlays (one pre-baked
        // overlay per palette swatch — see HoguPalette / res/values/themes_hogu.xml). Material3 only
        // applies exact colours from a fixed compile-time set, so the configurable foundation colours
        // are a curated swatch palette: accent recolours every accent role; text recolours body text +
        // toolbar titles (colorOnSurface / onSurfaceVariant). Surfaces stay black from the base theme.
        HoguPalette.Swatch accent = HoguPalette.find(HoguTheme.getColor(_activity, HoguTheme.KEY_ACCENT));
        if (accent != null) {
            _activity.getTheme().applyStyle(accent.accentOverlay, true);
        }
        HoguPalette.Swatch text = HoguPalette.find(HoguTheme.getColor(_activity, HoguTheme.KEY_TEXT));
        if (text != null) {
            _activity.getTheme().applyStyle(text.textOverlay, true);
        }

        // Stock wallpaper-based dynamic colours remain available only when no swatch accent is set.
        if (accent == null && _prefs.isDynamicColorsEnabled()) {
            DynamicColorsOptions.Builder optsBuilder = new DynamicColorsOptions.Builder();
            if (getConfiguredTheme().equals(Theme.AMOLED)) {
                optsBuilder.setThemeOverlay(R.style.ThemeOverlay_Aegis_Dynamic_Amoled);
            } else if (getConfiguredTheme().equals(Theme.DARK)) {
                optsBuilder.setThemeOverlay(R.style.ThemeOverlay_Aegis_Dynamic_Dark);
            }
            DynamicColors.applyToActivityIfAvailable(_activity, optsBuilder.build());
        }
    }

    public Theme getConfiguredTheme() {
        Theme theme = _prefs.getCurrentTheme();

        if (theme == Theme.SYSTEM || theme == Theme.SYSTEM_AMOLED) {
            int currentNightMode = _activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
                theme = theme == Theme.SYSTEM_AMOLED ? Theme.AMOLED : Theme.DARK;
            } else {
                theme = Theme.LIGHT;
            }
        }

        return theme;
    }
}
