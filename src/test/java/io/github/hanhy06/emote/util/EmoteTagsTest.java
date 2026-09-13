package io.github.hanhy06.emote.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteTagsTest {
    @Test
    void extractsNormalizedUniqueTagsFromDescription() {
        assertEquals(
            List.of("dance", "친구", "two-player"),
            EmoteTags.extract("Fast movement #Dance #친구 #two-player #dance")
        );
    }

    @Test
    void ignoresHashesInsideWordsAndInvalidTags() {
        assertEquals(List.of("valid"), EmoteTags.extract("word#inside # valid #valid"));
    }
}
