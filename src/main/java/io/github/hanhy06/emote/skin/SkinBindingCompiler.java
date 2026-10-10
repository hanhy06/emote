package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SkinBindingCompiler {
    public List<SkinBinding> compile(EmoteAnimation animation) {
        List<SkinBinding> bindings = new ArrayList<>();
        animation.nodes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(node ->
            node.getValue().attachments().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(attachment -> {
                if (attachment.getValue() instanceof EmoteAnimation.SkinAttachment skin) {
                    bindings.add(new SkinBinding(node.getKey(), attachment.getKey(),
                        new PlayerSkinRegion(skin.part(), skin.from(), skin.to())));
                }
            }));
        return List.copyOf(bindings);
    }
}
