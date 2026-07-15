package io.github.hanhy06.emote.animation;

import net.minecraft.resources.Identifier;

import java.util.*;

import static io.github.hanhy06.emote.animation.EmoteAnimation.Loaded;

public final class EmoteAnimationRegistry {
    private volatile RegistryState state = RegistryState.empty();

    public void replace(Collection<Loaded> animations) {
        List<Loaded> sorted = new ArrayList<>(animations);
        sorted.sort(Comparator.comparing(animation -> animation.animation().id().toString()));

        LinkedHashMap<Identifier, Loaded> byId = new LinkedHashMap<>();
        for (Loaded animation : sorted) {
            Identifier id = animation.animation().id();
            if (byId.putIfAbsent(id, animation) != null) {
                throw new IllegalArgumentException("Duplicate emote animation id: " + id);
            }
        }
        this.state = new RegistryState(Map.copyOf(byId), List.copyOf(sorted));
    }

    public Loaded find(Identifier id) {
        return this.state.byId().get(id);
    }

    public Loaded find(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? null : find(parsed);
    }

    public List<Loaded> getAnimations() {
        return this.state.animations();
    }

    public int size() {
        return this.state.animations().size();
    }

    private record RegistryState(Map<Identifier, Loaded> byId, List<Loaded> animations) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of());
        }
    }
}
