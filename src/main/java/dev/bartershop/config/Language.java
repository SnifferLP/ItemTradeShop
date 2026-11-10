package dev.bartershop.config;

import java.util.Locale;

/** Supported interface languages. The enum name doubles as the file suffix. */
public enum Language {
    EN("English", "English"),
    DE("Deutsch", "German");

    private final String nativeName;
    private final String englishName;

    Language(String nativeName, String englishName) {
        this.nativeName = nativeName;
        this.englishName = englishName;
    }

    public String nativeName() {
        return nativeName;
    }

    public String englishName() {
        return englishName;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Language next() {
        Language[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Language parse(String value, Language fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("de") || cleaned.startsWith("german")) {
            return DE;
        }
        if (cleaned.startsWith("en")) {
            return EN;
        }
        return fallback;
    }
}
