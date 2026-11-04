package io.github.hanhy06.emote.examples;

import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlaybackContext;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.PlaybackSession;
import io.github.hanhy06.emote.playback.RootTransform;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChristmasExample implements EmoteCallbacks {
    private static final Set<String> INSTRUMENTS = Set.of(
        "christmas:piano_deck_the_halls", "christmas:recorder_deck_the_halls",
        "christmas:handbell_deck_the_halls", "christmas:drum_deck_the_halls"
    );
    private static final String CHORUS = "christmas:chorus_deck_the_halls";
    private static final double RANGE_SQUARED = 8 * 8;

    private final EmoteCatalog catalog;
    private final PlaybackEngine engine;
    private final Set<PlaybackSession> instruments = new LinkedHashSet<>();
    private final Map<UUID, PlaybackSession> chorusByInstrument = new HashMap<>();

    public ChristmasExample(EmoteCatalog catalog, PlaybackEngine engine) {
        this.catalog = catalog;
        this.engine = engine;
    }

    @Override
    public void onStart(PlaybackContext context) {
        ServerLevel level = context.getWorld();
        Vec3 position = context.getRootPosition();
        level.sendParticles(ParticleTypes.SNOWFLAKE, position.x, position.y + 1.1, position.z, 56, 0.8, 0.65, 0.8, 0.05);
        level.sendParticles(ParticleTypes.POOF, position.x, position.y + 1.1, position.z, 8, 0.6, 0.55, 0.6, 0.02);
        tryStartChorus(context);
    }

    @Override
    public void onLoop(PlaybackContext context) {
        tryStartChorus(context);
    }

    private void tryStartChorus(PlaybackContext context) {
        PlaybackSession instrument = this.engine.findSession(context.getSessionId());
        if (instrument == null || !INSTRUMENTS.contains(instrument.emoteId())) {
            return;
        }
        this.instruments.add(instrument);
        this.chorusByInstrument.values().removeIf(chorus -> !this.engine.contains(chorus));
        if (this.chorusByInstrument.containsKey(instrument.sessionId())) {
            return;
        }

        Vec3 position = context.getRootPosition();
        Map<String, PlaybackSession> members = new HashMap<>();
        members.put(instrument.emoteId(), instrument);
        for (PlaybackSession candidate : this.instruments) {
            if (this.engine.contains(candidate) && candidate.levelKey().equals(instrument.levelKey())
                && candidate.placement().position().distanceToSqr(position) <= RANGE_SQUARED
                && !this.chorusByInstrument.containsKey(candidate.sessionId())) {
                members.putIfAbsent(candidate.emoteId(), candidate);
            }
        }
        if (members.size() != INSTRUMENTS.size() || !(this.catalog.find(CHORUS) instanceof PreparedAnimation chorus)) {
            return;
        }

        Vec3 center = Vec3.ZERO;
        for (PlaybackSession member : members.values()) {
            center = center.add(member.placement().position());
        }
        center = center.scale(1.0 / members.size());

        // Start a scene without an actor so it can run alongside all four performances.
        var request = new PlaybackEngine.Request(context.getWorld(), RootTransform.create(center, 0), chorus);
        if (this.engine.start(request, null) instanceof PlaybackEngine.StartResult.Success success) {
            for (PlaybackSession member : members.values()) {
                this.chorusByInstrument.put(member.sessionId(), success.session());
            }
        }
    }

    @Override
    public void onClose(PlaybackContext context) {
        UUID instrumentId = context.getSessionId();
        this.instruments.removeIf(instrument -> instrument.sessionId().equals(instrumentId));
        PlaybackSession chorus = this.chorusByInstrument.get(instrumentId);
        if (chorus != null) {
            this.chorusByInstrument.values().removeIf(chorus::equals);
            this.engine.stop(chorus, context.getStopReason().orElse(PlaybackStopReason.MANUAL));
        }
    }
}
