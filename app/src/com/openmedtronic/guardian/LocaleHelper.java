package com.openmedtronic.guardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * Manages in-app language selection (System default, English, Russian, etc.).
 * To add a new language in the future, add its res/values-XX/strings.xml
 * and append its code and label to SUPPORTED_LANG_CODES / SUPPORTED_LANG_LABELS.
 */
public final class LocaleHelper {

    public static final String PREF_NAME = "guardian_prefs";
    public static final String KEY_APP_LANG = "app_lang";

    public static final String LANG_SYSTEM = "system";
    public static final String[] SUPPORTED_LANG_CODES = new String[] { LANG_SYSTEM, "en", "ru" };

    private LocaleHelper() {}

    public static String getSavedLanguage(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_APP_LANG, LANG_SYSTEM);
    }

    public static void setSavedLanguage(Context context, String langCode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP_LANG, langCode)
                .apply();
    }

    public static Locale resolveLocale(String langCode) {
        if (langCode == null || LANG_SYSTEM.equals(langCode) || langCode.isEmpty()) {
            Configuration sysConfig = Resources.getSystem().getConfiguration();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return sysConfig.getLocales().get(0);
            }
            return sysConfig.locale;
        }
        return new Locale(langCode);
    }

    public static Context applyLocale(Context base) {
        String langCode = getSavedLanguage(base);
        Locale locale = resolveLocale(langCode);
        Locale.setDefault(locale);

        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return base.createConfigurationContext(config);
        }
        base.getResources().updateConfiguration(config, base.getResources().getDisplayMetrics());
        return base;
    }
}
