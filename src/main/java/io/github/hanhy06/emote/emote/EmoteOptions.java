package io.github.hanhy06.emote.emote;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record EmoteOptions(
	boolean loop,
	boolean visiblePlayer
) {
	public static EmoteOptions parse(String options) {
		Objects.requireNonNull(options, "options");

		boolean loop = false;
		boolean visiblePlayer = false;

		for (String option : splitNormalizedOptions(options)) {
			switch (option) {
				case "loop" -> loop = true;
				case "visible", "visible_player" -> visiblePlayer = true;
				default -> {
				}
			}
		}

		return new EmoteOptions(loop, visiblePlayer);
	}

	public static String normalize(String options) {
		Objects.requireNonNull(options, "options");

		List<String> normalizedOptions = splitNormalizedOptions(options);
		if (normalizedOptions.isEmpty()) {
			return "";
		}

		return String.join(" ", normalizedOptions);
	}

	private static List<String> splitNormalizedOptions(String options) {
		String trimmedOptions = options.trim();
		if (trimmedOptions.isEmpty()) {
			return List.of();
		}

		Set<String> normalizedOptions = new LinkedHashSet<>();
		for (String option : trimmedOptions.split("\\s+")) {
			String normalizedOption = normalizeOptionName(option);
			if (isIgnoredOption(normalizedOption)) {
				continue;
			}

			normalizedOptions.add(normalizedOption);
		}
		return List.copyOf(normalizedOptions);
	}

	private static String normalizeOptionName(String optionName) {
		String normalizedOptionName = optionName.trim()
			.toLowerCase(Locale.ROOT)
			.replace('-', '_');

		if (normalizedOptionName.equals("visible")) {
			return "visible_player";
		}

		return normalizedOptionName;
	}

	private static boolean isIgnoredOption(String optionName) {
		return optionName.equals("sync");
	}
}
