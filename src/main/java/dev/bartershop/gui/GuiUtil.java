package dev.bartershop.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Formatting helpers shared by menus. */
public final class GuiUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private GuiUtil() {
    }

    public static String safe(String text) {
        return text == null ? "" : MINI.escapeTags(text);
    }

    public static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    public static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis));
    }

    public static String plural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }
}
