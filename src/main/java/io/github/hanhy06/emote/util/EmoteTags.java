package io.github.hanhy06.emote.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmoteTags {
    private static final Pattern TAG_PATTERN = Pattern.compile("(?:^|\\s)#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]*)");

    private EmoteTags() {
    }

    public static List<String> extract(String description) {
        Objects.requireNonNull(description, "description");
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher matcher = TAG_PATTERN.matcher(description);
        while (matcher.find()) {
            tags.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(tags);
    }

}
