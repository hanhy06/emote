package io.github.hanhy06.emote.examples;

import io.github.hanhy06.emote.api.EmoteApi;
import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.Playback;
import io.github.hanhy06.emote.api.PlaybackContext;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChristmasExample implements EmoteCallbacks {
    private static final Set<String> INSTRUMENTS = Set.of(
        "christmas:piano", "christmas:recorder",
        "christmas:handbell", "christmas:drum"
    );
    private static final String CHORUS = "christmas:chorus";
    private static final double RANGE_SQUARED = 8 * 8;

    private final EmoteApi api;
    private final Map<UUID, Playback> instruments = new LinkedHashMap<>();
    private final Map<UUID, Playback> chorusByInstrument = new HashMap<>();

    public ChristmasExample(EmoteApi api) {
        this.api = api;
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
        if (!INSTRUMENTS.contains(context.getEmoteId().toString())) {
            return;
        }
        this.instruments.put(context.getSessionId(), context);
        this.chorusByInstrument.values().removeIf(chorus -> !chorus.isActive());
        if (this.chorusByInstrument.containsKey(context.getSessionId())) {
            return;
        }

        Vec3 position = context.getRootPosition();
        Map<String, Playback> members = new HashMap<>();
        members.put(context.getEmoteId().toString(), context);
        for (Playback candidate : this.instruments.values()) {
            if (candidate.isActive() && candidate.getWorld() == context.getWorld()
                && candidate.getRootPosition().distanceToSqr(position) <= RANGE_SQUARED
                && !this.chorusByInstrument.containsKey(candidate.getSessionId())) {
                members.putIfAbsent(candidate.getEmoteId().toString(), candidate);
            }
        }
        if (members.size() != INSTRUMENTS.size()) {
            return;
        }

        Vec3 center = Vec3.ZERO;
        for (Playback member : members.values()) {
            center = center.add(member.getRootPosition());
        }
        center = center.scale(1.0 / members.size());

        // Start a scene without an actor so it can run alongside all four performances.
        if (this.api.play(context.getWorld(), Identifier.parse(CHORUS), center, 0) instanceof PlayResult.Success success) {
            for (Playback member : members.values()) {
                this.chorusByInstrument.put(member.getSessionId(), success.playback());
            }
        }
    }

    @Override
    public void onClose(PlaybackContext context) {
        UUID instrumentId = context.getSessionId();
        this.instruments.remove(instrumentId);
        Playback chorus = this.chorusByInstrument.get(instrumentId);
        if (chorus != null) {
            this.chorusByInstrument.values().removeIf(chorus::equals);
            chorus.stop(context.getCloseReason().orElse(PlaybackStopReason.MANUAL));
        }
    }
}
