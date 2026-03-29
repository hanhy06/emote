package io.github.hanhy06.emote.config.data;

import java.util.LinkedHashMap;
import java.util.List;

public record IdentifierConfig(
	LinkedHashMap<String, List<IdentifierEntry>> permissions
) {
	public static IdentifierConfig createDefault() {
		return new IdentifierConfig(new LinkedHashMap<>());
	}
}
