package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.EnumMap;
import java.util.Map;

public final class SceneRootResolver {
    public static Map<EmoteAnimation.NodeSpace, RootTransform> single(RootTransform root) {
        EnumMap<EmoteAnimation.NodeSpace, RootTransform> roots = new EnumMap<>(EmoteAnimation.NodeSpace.class);
        for (EmoteAnimation.NodeSpace space : EmoteAnimation.NodeSpace.values()) {
            roots.put(space, root);
        }
        return Map.copyOf(roots);
    }

}
