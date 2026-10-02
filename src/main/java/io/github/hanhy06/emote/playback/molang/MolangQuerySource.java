package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;

@FunctionalInterface
public interface MolangQuerySource {
    MolangQuerySource EMPTY = MolangQueries::applyEmpty;
    void apply(MolangEngine.Session session);
}
