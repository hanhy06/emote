package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import io.github.hanhy06.emote.molang.MolangQueryCatalog;
import java.util.Set;

@FunctionalInterface
public interface MolangQuerySource {
    MolangQuerySource EMPTY = (session, queries) -> MolangQueries.applyEntity(null, session, queries);
    void apply(MolangEngine.Session session, Set<String> queries);
    default void apply(MolangEngine.Session session) { apply(session, MolangQueryCatalog.queryNames()); }
}
