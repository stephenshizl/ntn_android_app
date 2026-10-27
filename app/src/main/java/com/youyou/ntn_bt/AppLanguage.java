package com.youyou.ntn_bt;

import java.util.Locale;

/** Stable language tags; native names keep the picker usable in any UI language. */
final class AppLanguage {
    static final String[] TAGS = {"", "zh-Hans", "en", "zh-Hant", "ja", "es"};
    static final String[] NAMES = {"", "简体中文", "English", "繁體中文", "日本語", "Español"};
    static int index(String tags) {
        if (tags == null || tags.isEmpty()) return 0;
        Locale locale = Locale.forLanguageTag(tags.split(",", 2)[0]);
        String lang = locale.getLanguage();
        if (lang.equals("zh")) return locale.getScript().equals("Hant")
                || locale.getCountry().equals("TW") || locale.getCountry().equals("HK")
                || locale.getCountry().equals("MO") ? 3 : 1;
        if (lang.equals("en")) return 2;
        if (lang.equals("ja")) return 4;
        if (lang.equals("es")) return 5;
        return 0;
    }
}
