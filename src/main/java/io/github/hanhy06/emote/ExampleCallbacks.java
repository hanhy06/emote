package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
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
    public static final Identifier TRUMPET_CAN_CAN_CALLBACK_ID = Identifier.parse("emote:trumpet_can_can_callback");

    private static final double ALLAY_SCALE = 0.35D;

    private static final float HORN_RANGE = 24.0F;
    private static final int MAX_HORN_DURATION_TICKS = 7;
    private static final Identifier HORN_SOUND = Identifier.parse("minecraft:item.goat_horn.sound.0");
    private static final double HORN_BASE_FREQUENCY = 130.8D;
    private static final double HORN_PITCH_OFFSET = 4.9D;

    private static final List<ScheduledHornNote> TRUMPET_CAN_CAN_NOTES = List.of(
        new ScheduledHornNote(28, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(34, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(40, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(43, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(46, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(49, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(52, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(58, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(64, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(67, new HornNote(64, 3, 0.65F)),
        new ScheduledHornNote(70, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(73, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(76, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(82, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(88, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(91, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(94, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(97, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(100, new HornNote(55, 3, 0.65F)),
        new ScheduledHornNote(103, new HornNote(67, 3, 0.65F)),
        new ScheduledHornNote(106, new HornNote(66, 3, 0.65F)),
        new ScheduledHornNote(109, new HornNote(64, 3, 0.65F)),
        new ScheduledHornNote(112, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(115, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(118, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(121, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(124, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(130, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(136, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(139, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(142, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(145, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(148, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(154, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(160, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(163, new HornNote(64, 3, 0.65F)),
        new ScheduledHornNote(166, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(169, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(172, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(178, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(184, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(187, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(190, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(193, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(196, new HornNote(55, 3, 0.65F)),
        new ScheduledHornNote(199, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(202, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(205, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(208, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(214, new HornNote(50, 6, 0.65F)),
        new ScheduledHornNote(220, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(226, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(232, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(235, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(238, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(241, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(244, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(250, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(256, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(259, new HornNote(64, 3, 0.65F)),
        new ScheduledHornNote(262, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(265, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(268, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(274, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(280, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(283, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(286, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(289, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(292, new HornNote(55, 3, 0.65F)),
        new ScheduledHornNote(295, new HornNote(67, 3, 0.65F)),
        new ScheduledHornNote(298, new HornNote(66, 3, 0.65F)),
        new ScheduledHornNote(301, new HornNote(64, 3, 0.65F)),
        new ScheduledHornNote(304, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(307, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(310, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(313, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(316, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(322, new HornNote(55, 6, 0.65F)),
        new ScheduledHornNote(328, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(331, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(334, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(337, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(340, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(346, new HornNote(62, 6, 0.65F)),
        new ScheduledHornNote(352, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(355, new HornNote(64, 3, 0.65F)),
        new ScheduledHornNote(358, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(361, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(364, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(370, new HornNote(57, 6, 0.65F)),
        new ScheduledHornNote(376, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(379, new HornNote(60, 3, 0.65F)),
        new ScheduledHornNote(382, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(385, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(388, new HornNote(55, 3, 0.65F)),
        new ScheduledHornNote(391, new HornNote(62, 3, 0.65F)),
        new ScheduledHornNote(394, new HornNote(57, 3, 0.65F)),
        new ScheduledHornNote(397, new HornNote(59, 3, 0.65F)),
        new ScheduledHornNote(400, new HornNote(55, 6, 0.65F))
    );

    private final Map<UUID, TrumpetCanCan> trumpetCanCans = new HashMap<>();
    private final Map<UUID, ActiveHornNote> playingHorns = new HashMap<>();
    private long hornTick;

    private final Map<UUID, Entity> entitiesByPlayer = new HashMap<>();
    private final List<ListenerRegistration> registrations;

    private boolean registered = true;

    private ExampleCallbacks(EmoteApi api) {
        this.registrations = List.of(
            api.addCallbackListener(IDLE_BUTTERFLY_CALLBACK_ID, this::handleIdleButterfly),
            api.addCallbackListener(IDLE_BAT_CALLBACK_ID, this::handleIdleBat),
            api.addCallbackListener(TRUMPET_CAN_CAN_CALLBACK_ID, this::handleTrumpetCanCan),
            api.addPlaybackListener(new EmotePlaybackListener() {
                @Override
                public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
                    removeEntity(playback.playerUuid(), true);
                    stopTrumpetCanCan(playback.playerUuid());
                }
            })
        );
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (this.registered) tickTrumpetCanCans();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> clearTrumpetCanCans());
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
        clearTrumpetCanCans();
        return removed;
    }

    private void handleTrumpetCanCan(EmoteCallbackEvent event) {
        if (event.phase() == EmoteCallbackPhase.STOP) {
            stopTrumpetCanCan(event.player().getUUID());
            return;
        }
        if (event.phase() != EmoteCallbackPhase.START) return;
        UUID performer = event.player().getUUID();
        stopTrumpetCanCan(performer);
        List<HornListener> listeners = event.player().level().players().stream()
            .filter(player -> player.position().distanceToSqr(event.origin()) <= HORN_RANGE * HORN_RANGE)
            .map(player -> new HornListener(player.getUUID(), player.connection::send))
            .toList();
        this.trumpetCanCans.put(performer, new TrumpetCanCan(this.hornTick, event.origin(), listeners));
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
        float pitch = Mth.clamp((float) (frequency / HORN_BASE_FREQUENCY), 0.5F, 2.0F);
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
            listener.send().accept(new ClientboundLevelParticlesPacket(
                ParticleTypes.NOTE, false, false, origin.x, origin.y + 1.8D, origin.z,
                (float) ((note.midi() % 12) / 12.0), 0.0F, 0.0F, 1.0F, 0
            ));
            voices.add(new HornVoice(listener, HORN_SOUND));
        }
        if (!voices.isEmpty()) {
            long endTick = this.hornTick + Math.min(note.durationTicks(), MAX_HORN_DURATION_TICKS);
            this.playingHorns.put(performer, new ActiveHornNote(endTick, List.copyOf(voices)));
        }
    }

    private void tickTrumpetCanCans() {
        this.hornTick++;
        var iterator = this.playingHorns.values().iterator();
        while (iterator.hasNext()) {
            ActiveHornNote note = iterator.next();
            if (note.endTick() > this.hornTick) continue;
            iterator.remove();
            note.stop();
        }
        var melodies = this.trumpetCanCans.entrySet().iterator();
        while (melodies.hasNext()) {
            var entry = melodies.next();
            TrumpetCanCan melody = entry.getValue();
            if (melody.advance(this.hornTick, note -> playHorn(entry.getKey(), melody.origin, note, melody.listeners))) {
                melodies.remove();
            }
        }
    }

    private void stopTrumpetCanCan(UUID performer) {
        this.trumpetCanCans.remove(performer);
        stopHornNote(performer);
    }

    private void stopHornNote(UUID performer) {
        ActiveHornNote note = this.playingHorns.remove(performer);
        if (note != null) {
            note.stop();
        }
    }

    private void clearTrumpetCanCans() {
        this.trumpetCanCans.clear();
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

    static final class TrumpetCanCan {
        private final long startTick;
        private final Vec3 origin;
        private final List<HornListener> listeners;
        private int nextNote;

        TrumpetCanCan(long startTick, Vec3 origin, List<HornListener> listeners) {
            this.startTick = startTick;
            this.origin = origin;
            this.listeners = listeners;
        }

        boolean advance(long tick, Consumer<HornNote> play) {
            while (this.nextNote < TRUMPET_CAN_CAN_NOTES.size()) {
                ScheduledHornNote scheduled = TRUMPET_CAN_CAN_NOTES.get(this.nextNote);
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
