package com.example.migrationtool.util;

import jakarta.enterprise.context.ApplicationScoped;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

@ApplicationScoped
public class Messages {

    private static final String BASE_NAME = "messages";
    private static final Locale DEFAULT_LOCALE = Locale.JAPANESE;

    public String get(String key, Object... args) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BASE_NAME, DEFAULT_LOCALE);
            String pattern = bundle.getString(key);
            return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return key;
        }
    }
}
