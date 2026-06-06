package com.beemdevelopment.aegis.ui.fragments.preferences;

import android.content.Intent;
import android.os.Bundle;

import androidx.preference.Preference;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.ui.HoguUiActivity;

public class MainPreferencesFragment extends PreferencesFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);

        Preference hoguUi = findPreference("pref_hogu_ui");
        if (hoguUi != null) {
            hoguUi.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), HoguUiActivity.class));
                return true;
            });
        }
    }
}
