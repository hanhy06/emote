package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import io.github.hanhy06.emote.molang.MolangQueryCatalog;
import java.util.Set;

@FunctionalInterface
public interface MolangQuerySource {
    MolangQuerySource EMPTY = new MolangQuerySource() {
        @Override public void apply(MolangEngine.Session session) { apply(session, MolangQueryCatalog.queryNames()); }
        @Override public void apply(MolangEngine.Session session, Set<String> queries) { MolangQueries.applyEmpty(session, queries); }
    };
    void apply(MolangEngine.Session session);
    default void apply(MolangEngine.Session session, Set<String> queries) { apply(session); }
}
