package io.github.hanhy06.emote.selection;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

public final class WeightedChoiceSelector {
    private WeightedChoiceSelector() {
    }

    public static <T> int selectIndex(
        RandomGenerator random,
        List<T> choices,
        ToIntFunction<T> chance,
        int excludedIndex
    ) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(chance, "chance");
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("choices must not be empty");
        }
        if (choices.size() == 1) {
            return 0;
        }
        if (chance.applyAsInt(choices.getFirst()) == 0) {
            if (excludedIndex < 0) {
                return random.nextInt(choices.size());
            }
            int selectedIndex = random.nextInt(choices.size() - 1);
            return selectedIndex >= excludedIndex ? selectedIndex + 1 : selectedIndex;
        }

        int totalChance = 0;
        for (int index = 0; index < choices.size(); index++) {
            if (index != excludedIndex) {
                totalChance += chance.applyAsInt(choices.get(index));
            }
        }
        int selectedChance = random.nextInt(totalChance);
        for (int index = 0; index < choices.size(); index++) {
            if (index == excludedIndex) {
                continue;
            }
            selectedChance -= chance.applyAsInt(choices.get(index));
            if (selectedChance < 0) {
                return index;
            }
        }
        throw new IllegalStateException("failed to select a weighted choice");
    }
}
