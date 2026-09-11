package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Registers the example callbacks shipped with the mod. The idle butterfly callback is declared as follows:
 *
 * <pre>{@code
 * {
 *   "source": {"type": "server"},
 *   "origin": {"type": "node", "node": "butterfly"},
 *   "commands": [],
 *   "callbacks": [{"name": "emote:idle_butterfly_callback"}]
 * }
 * }</pre>
 */
public final class ExampleCallbacks {
    public static final Identifier IDLE_BUTTERFLY_CALLBACK_ID = Identifier.parse("emote:idle_butterfly_callback");
    public static final Identifier IDLE_BAT_CALLBACK_ID = Identifier.parse("emote:idle_bat_callback");
    public static final Identifier TRUMPET_KOREAN_REVEILLE_CALLBACK_ID = Identifier.parse("emote:trumpet_korean_reveille_callback");

    private static final double ALLAY_SCALE = 0.35D;

    private static final float HORN_RANGE = 24.0F;
    private static final int MAX_HORN_DURATION_TICKS = 5;
    private static final Identifier HORN_SOUND = Identifier.parse("minecraft:item.goat_horn.sound.0");
    private static final double HORN_BASE_FREQUENCY = 130.8D;
    private static final double HORN_PITCH_OFFSET = 4.5D;

    private static final List<ScheduledHornNote> TRUMPET_KOREAN_REVEILLE_NOTES = List.of(
        new ScheduledHornNote(28, new HornNote(65, 7, 0.65F)),
        new ScheduledHornNote(37, new HornNote(62, 8, 0.65F)),
        new ScheduledHornNote(45, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(49, new HornNote(62, 5, 0.65F)),
        new ScheduledHornNote(54, new HornNote(62, 7, 0.65F)),
        new ScheduledHornNote(62, new HornNote(62, 5, 0.65F)),
        new ScheduledHornNote(67, new HornNote(65, 4, 0.65F)),
        new ScheduledHornNote(71, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(75, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(79, new HornNote(62, 5, 0.65F)),
        new ScheduledHornNote(84, new HornNote(65, 2, 0.65F)),
        new ScheduledHornNote(86, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(88, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(92, new HornNote(62, 5, 0.65F)),
        new ScheduledHornNote(97, new HornNote(65, 4, 0.65F)),
        new ScheduledHornNote(101, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(105, new HornNote(62, 8, 0.65F)),
        new ScheduledHornNote(114, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(118, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(120, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(122, new HornNote(58, 9, 0.65F)),
        new ScheduledHornNote(131, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(135, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(139, new HornNote(62, 9, 0.65F)),
        new ScheduledHornNote(148, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(152, new HornNote(65, 5, 0.65F)),
        new ScheduledHornNote(157, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(161, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(165, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(169, new HornNote(65, 3, 0.65F)),
        new ScheduledHornNote(172, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(174, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(178, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(182, new HornNote(65, 5, 0.65F)),
        new ScheduledHornNote(187, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(191, new HornNote(62, 7, 0.65F)),
        new ScheduledHornNote(199, new HornNote(58, 5, 0.65F)),
        new ScheduledHornNote(204, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(206, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(208, new HornNote(58, 17, 0.65F)),
        new ScheduledHornNote(242, new HornNote(65, 5, 0.65F)),
        new ScheduledHornNote(247, new HornNote(65, 2, 0.65F)),
        new ScheduledHornNote(249, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(251, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(255, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(259, new HornNote(58, 3, 0.65F)),
        new ScheduledHornNote(262, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(264, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(266, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(268, new HornNote(65, 21, 0.65F)),
        new ScheduledHornNote(302, new HornNote(58, 5, 0.65F)),
        new ScheduledHornNote(307, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(311, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(315, new HornNote(62, 4, 0.65F)),
        new ScheduledHornNote(319, new HornNote(65, 5, 0.65F)),
        new ScheduledHornNote(324, new HornNote(65, 2, 0.65F)),
        new ScheduledHornNote(326, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(328, new HornNote(58, 4, 0.65F)),
        new ScheduledHornNote(332, new HornNote(62, 5, 0.65F)),
        new ScheduledHornNote(337, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(339, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(341, new HornNote(58, 2, 0.65F)),
        new ScheduledHornNote(343, new HornNote(62, 2, 0.65F)),
        new ScheduledHornNote(345, new HornNote(65, 17, 0.65F))
    );

    private final Map<UUID, TrumpetKoreanReveille> trumpetKoreanReveilles = new HashMap<>();
    private final Map<UUID, ActiveHornNote> playingHorns = new HashMap<>();
    private long hornTick;

    private final Map<UUID, Entity> entitiesByPlayer = new HashMap<>();
    private final List<ListenerRegistration> registrations;

    private boolean registered = true;

    private ExampleCallbacks(EmoteApi api) {
        this.registrations = List.of(
            api.addCallbackListener(IDLE_BUTTERFLY_CALLBACK_ID, this::handleIdleButterfly),
            api.addCallbackListener(IDLE_BAT_CALLBACK_ID, this::handleIdleBat),
            api.addCallbackListener(TRUMPET_KOREAN_REVEILLE_CALLBACK_ID, this::handleTrumpetKoreanReveille),
            api.addPlaybackListener(new EmotePlaybackListener() {
                @Override
                public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
                    removeEntity(playback.playerUuid(), true);
                    stopTrumpetKoreanReveille(playback.playerUuid());
                }
            })
        );
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (this.registered) tickTrumpetKoreanReveilles();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> clearTrumpetKoreanReveilles());
    }

    public static ExampleCallbacks registerAll(EmoteApi api) {
        return new ExampleCallbacks(Objects.requireNonNull(api, "api"));
    }

    public boolean unregister() {
        if (!this.registered) return false;
        this.registered = false;

        boolean removed = false;
        for (ListenerRegistration registration : this.registrations) {
            removed |= registration.unregister();
        }
        this.entitiesByPlayer.values().forEach(Entity::discard);
        this.entitiesByPlayer.clear();
        clearTrumpetKoreanReveilles();
        return removed;
    }

    private void handleTrumpetKoreanReveille(EmoteCallbackEvent event) {
        if (event.phase() == EmoteCallbackPhase.STOP) {
            stopTrumpetKoreanReveille(event.player().getUUID());
            return;
        }
        if (event.phase() != EmoteCallbackPhase.START) return;
        UUID performer = event.player().getUUID();
        stopTrumpetKoreanReveille(performer);
        List<HornListener> listeners = event.player().level().players().stream()
            .filter(player -> player.position().distanceToSqr(event.origin()) <= HORN_RANGE * HORN_RANGE)
            .map(player -> new HornListener(player.getUUID(), player.connection::send))
            .toList();
        this.trumpetKoreanReveilles.put(performer, new TrumpetKoreanReveille(this.hornTick, event.origin(), listeners));
    }

    private void handleIdleButterfly(EmoteCallbackEvent event) {
        switch (event.phase()) {
            case START -> spawnAllay(event);
            case TIMELINE -> moveAllay(event);
            case STOP -> removeEntity(event.player().getUUID(), true);
            case LOOP -> {}
        }
    }

    private void spawnAllay(EmoteCallbackEvent event) {
        UUID playerUuid = event.player().getUUID();
        removeEntity(playerUuid, false);

        ServerLevel level = event.player().level();
        Allay allay = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
        if (allay == null) {
            throw new IllegalStateException("Failed to create the idle butterfly Allay");
        }

        allay.snapTo(event.origin().x, event.origin().y, event.origin().z, event.player().getYRot(), 0.0F);
        allay.setNoAi(true);
        allay.setInvulnerable(true);
        allay.setSilent(true);
        allay.setCanPickUpLoot(false);
        allay.addTag(PlaybackEntityController.RUNTIME_TAG);
        Objects.requireNonNull(allay.getAttribute(Attributes.SCALE), "Allay scale attribute").setBaseValue(ALLAY_SCALE);

        if (!level.addFreshEntity(allay)) {
            allay.discard();
            throw new IllegalStateException("Failed to add the idle butterfly Allay to the level");
        }
        level.sendParticles(ParticleTypes.WHITE_SMOKE, allay.getX(), allay.getY(0.5D), allay.getZ(), 7, 0.08D, 0.08D, 0.08D, 0.02D);
        this.entitiesByPlayer.put(playerUuid, allay);
    }

    private void moveAllay(EmoteCallbackEvent event) {
        Entity entity = this.entitiesByPlayer.get(event.player().getUUID());
        if (!(entity instanceof Allay allay) || allay.isRemoved()) return;

        Vec3 destination = event.origin();
        Vec3 movement = destination.subtract(allay.position());
        if (movement.horizontalDistanceSqr() > 1.0E-6D) {
            float targetYaw = (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.rotLerp(0.35F, allay.getYRot(), targetYaw);
            allay.setYRot(yaw);
            allay.setYBodyRot(yaw);
            allay.setYHeadRot(yaw);
        }
        allay.teleportTo(destination.x, destination.y, destination.z);
    }

    private void handleIdleBat(EmoteCallbackEvent event) {
        if (event.phase() == EmoteCallbackPhase.STOP) {
            removeEntity(event.player().getUUID(), false);
            return;
        }
        if (event.phase() != EmoteCallbackPhase.TIMELINE) return;

        switch (event.payload()) {
            case "spawn" -> spawnBat(event);
            case "move" -> moveBat(event);
            case "remove" -> {
                moveBat(event);
                removeEntity(event.player().getUUID(), true);
            }
            default -> throw new IllegalArgumentException("Unknown idle bat callback payload: " + event.payload());
        }
    }

    private void spawnBat(EmoteCallbackEvent event) {
        UUID playerUuid = event.player().getUUID();
        removeEntity(playerUuid, false);

        ServerLevel level = event.player().level();
        Bat bat = EntityTypes.BAT.create(level, EntitySpawnReason.COMMAND);
        if (bat == null) throw new IllegalStateException("Failed to create the idle Bat");

        bat.snapTo(event.origin().x, event.origin().y, event.origin().z, event.player().getYRot(), 0.0F);
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setInvulnerable(true);
        bat.setSilent(true);
        bat.setResting(false);
        bat.addTag(PlaybackEntityController.RUNTIME_TAG);

        if (!level.addFreshEntity(bat)) {
            bat.discard();
            throw new IllegalStateException("Failed to add the idle Bat to the level");
        }
        level.sendParticles(ParticleTypes.SMOKE, bat.getX(), bat.getY(0.5D), bat.getZ(), 12, 0.16D, 0.16D, 0.16D, 0.02D);
        this.entitiesByPlayer.put(playerUuid, bat);
    }

    private void moveBat(EmoteCallbackEvent event) {
        Entity entity = this.entitiesByPlayer.get(event.player().getUUID());
        if (!(entity instanceof Bat bat) || bat.isRemoved()) return;

        Vec3 destination = event.origin();
        Vec3 movement = destination.subtract(bat.position());
        double horizontalDistance = movement.horizontalDistance();
        if (horizontalDistance > 1.0E-6D) {
            float targetYaw = (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.rotLerp(0.6F, bat.getYRot(), targetYaw);
            bat.setYRot(yaw);
            bat.setYBodyRot(yaw);
            bat.setYHeadRot(yaw);
            bat.setXRot((float) Mth.clamp(-(Mth.atan2(movement.y, horizontalDistance) * Mth.RAD_TO_DEG), -35.0D, 35.0D));
        }
        bat.teleportTo(destination.x, destination.y, destination.z);
    }

    private void removeEntity(UUID playerUuid, boolean particles) {
        Entity entity = this.entitiesByPlayer.remove(playerUuid);
        if (entity == null) return;
        if (particles && !entity.isRemoved() && entity.level() instanceof ServerLevel level) {
            if (entity instanceof Allay) {
                level.sendParticles(ParticleTypes.WHITE_SMOKE, entity.getX(), entity.getY(0.5D), entity.getZ(), 7, 0.08D, 0.08D, 0.08D, 0.02D);
            } else if (entity instanceof Bat) {
                level.sendParticles(ParticleTypes.SMOKE, entity.getX(), entity.getY(0.5D), entity.getZ(), 12, 0.16D, 0.16D, 0.16D, 0.02D);
            }
        }
        entity.discard();
    }

    private void playHorn(UUID performer, Vec3 origin, HornNote note, List<HornListener> listeners) {
        double frequency = 440.0 * Math.pow(2.0, (note.midi() + HORN_PITCH_OFFSET - 12.0 - 69.0) / 12.0);
        float pitch = (float) (frequency / HORN_BASE_FREQUENCY);
        if (pitch < 0.5F || pitch > 2.0F) throw new IllegalArgumentException("Goat horn pitch must be between 0.5 and 2.0: " + note);
        stopHornNote(performer);

        List<HornVoice> voices = new ArrayList<>();
        for (HornListener listener : listeners) {
            boolean soundOccupied = this.playingHorns.values().stream()
                .flatMap(active -> active.voices().stream())
                .anyMatch(voice -> voice.listener().id().equals(listener.id()) && voice.sound().equals(HORN_SOUND));
            if (soundOccupied) continue;
            listener.send().accept(new ClientboundSoundPacket(
                Holder.direct(SoundEvent.createFixedRangeEvent(HORN_SOUND, HORN_RANGE)), SoundSource.RECORDS,
                origin.x, origin.y, origin.z, note.volume(), pitch, this.hornTick
            ));
            voices.add(new HornVoice(listener, HORN_SOUND));
        }
        if (!voices.isEmpty()) {
            long endTick = this.hornTick + Math.min(note.durationTicks(), MAX_HORN_DURATION_TICKS);
            this.playingHorns.put(performer, new ActiveHornNote(endTick, List.copyOf(voices)));
        }
    }

    private void tickTrumpetKoreanReveilles() {
        this.hornTick++;
        var iterator = this.playingHorns.values().iterator();
        while (iterator.hasNext()) {
            ActiveHornNote note = iterator.next();
            if (note.endTick() > this.hornTick) continue;
            iterator.remove();
            note.stop();
        }
        var reveilles = this.trumpetKoreanReveilles.entrySet().iterator();
        while (reveilles.hasNext()) {
            var entry = reveilles.next();
            TrumpetKoreanReveille melody = entry.getValue();
            if (melody.advance(this.hornTick, note -> playHorn(entry.getKey(), melody.origin, note, melody.listeners))) {
                reveilles.remove();
            }
        }
    }

    private void stopTrumpetKoreanReveille(UUID performer) {
        this.trumpetKoreanReveilles.remove(performer);
        stopHornNote(performer);
    }

    private void stopHornNote(UUID performer) {
        ActiveHornNote note = this.playingHorns.remove(performer);
        if (note != null) {
            note.stop();
        }
    }

    private void clearTrumpetKoreanReveilles() {
        this.trumpetKoreanReveilles.clear();
        this.playingHorns.values().forEach(ActiveHornNote::stop);
        this.playingHorns.clear();
    }

    record HornNote(double midi, int durationTicks, float volume) {
        HornNote {
            if (!Double.isFinite(midi) || midi < 0 || midi > 127) throw new IllegalArgumentException("MIDI note must be between 0 and 127");
            if (durationTicks < 1 || durationTicks > 200) throw new IllegalArgumentException("Horn duration must be between 1 and 200 ticks");
            if (!Float.isFinite(volume) || volume <= 0 || volume > 1) throw new IllegalArgumentException("Horn volume must be greater than 0 and at most 1");
        }
    }

    private record ScheduledHornNote(int tick, HornNote note) {}

    static final class TrumpetKoreanReveille {
        private final long startTick;
        private final Vec3 origin;
        private final List<HornListener> listeners;
        private int nextNote;

        TrumpetKoreanReveille(long startTick, Vec3 origin, List<HornListener> listeners) {
            this.startTick = startTick;
            this.origin = origin;
            this.listeners = listeners;
        }

        boolean advance(long tick, Consumer<HornNote> play) {
            while (this.nextNote < TRUMPET_KOREAN_REVEILLE_NOTES.size()) {
                ScheduledHornNote scheduled = TRUMPET_KOREAN_REVEILLE_NOTES.get(this.nextNote);
                if (tick - this.startTick < scheduled.tick()) return false;
                this.nextNote++;
                play.accept(scheduled.note());
            }
            return true;
        }
    }

    private record HornListener(UUID id, Consumer<Packet<?>> send) {}
    private record HornVoice(HornListener listener, Identifier sound) {}
    private record ActiveHornNote(long endTick, List<HornVoice> voices) {
        void stop() {
            for (HornVoice voice : this.voices) {
                voice.listener().send().accept(new ClientboundStopSoundPacket(voice.sound(), SoundSource.RECORDS));
            }
        }
    }
}
