package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.EmoteApi;
import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlaybackContext;
import io.github.hanhy06.emote.api.Registration;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.util.TrumpetPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class ExampleCallbacks {
    public static final Identifier IDLE_BUTTERFLY_CALLBACK_ID = Identifier.parse("emote:idle_butterfly_callback");
    public static final Identifier BAT_CALLBACK_ID = Identifier.parse("emote:idle_bat_callback");
    public static final Identifier TRUMPET_CAN_CAN_CALLBACK_ID = Identifier.parse("emote:trumpet_can_can_callback");

    private static final double ALLAY_SCALE = 0.35D;

    private static final int MAX_HORN_DURATION_TICKS = 7;

    private static final List<HornNote> TRUMPET_CAN_CAN_NOTES = List.of(
        new HornNote(28, 55, 6, 0.65F),
        new HornNote(34, 55, 6, 0.65F),
        new HornNote(40, 57, 3, 0.65F),
        new HornNote(43, 60, 3, 0.65F),
        new HornNote(46, 59, 3, 0.65F),
        new HornNote(49, 57, 3, 0.65F),
        new HornNote(52, 62, 6, 0.65F),
        new HornNote(58, 62, 6, 0.65F),
        new HornNote(64, 62, 3, 0.65F),
        new HornNote(67, 64, 3, 0.65F),
        new HornNote(70, 59, 3, 0.65F),
        new HornNote(73, 60, 3, 0.65F),
        new HornNote(76, 57, 6, 0.65F),
        new HornNote(82, 57, 6, 0.65F),
        new HornNote(88, 57, 3, 0.65F),
        new HornNote(91, 60, 3, 0.65F),
        new HornNote(94, 59, 3, 0.65F),
        new HornNote(97, 57, 3, 0.65F),
        new HornNote(100, 55, 3, 0.65F),
        new HornNote(103, 67, 3, 0.65F),
        new HornNote(106, 66, 3, 0.65F),
        new HornNote(109, 64, 3, 0.65F),
        new HornNote(112, 62, 3, 0.65F),
        new HornNote(115, 60, 3, 0.65F),
        new HornNote(118, 59, 3, 0.65F),
        new HornNote(121, 57, 3, 0.65F),
        new HornNote(124, 55, 6, 0.65F),
        new HornNote(130, 55, 6, 0.65F),
        new HornNote(136, 57, 3, 0.65F),
        new HornNote(139, 60, 3, 0.65F),
        new HornNote(142, 59, 3, 0.65F),
        new HornNote(145, 57, 3, 0.65F),
        new HornNote(148, 62, 6, 0.65F),
        new HornNote(154, 62, 6, 0.65F),
        new HornNote(160, 62, 3, 0.65F),
        new HornNote(163, 64, 3, 0.65F),
        new HornNote(166, 59, 3, 0.65F),
        new HornNote(169, 60, 3, 0.65F),
        new HornNote(172, 57, 6, 0.65F),
        new HornNote(178, 57, 6, 0.65F),
        new HornNote(184, 57, 3, 0.65F),
        new HornNote(187, 60, 3, 0.65F),
        new HornNote(190, 59, 3, 0.65F),
        new HornNote(193, 57, 3, 0.65F),
        new HornNote(196, 55, 3, 0.65F),
        new HornNote(199, 62, 3, 0.65F),
        new HornNote(202, 57, 3, 0.65F),
        new HornNote(205, 59, 3, 0.65F),
        new HornNote(208, 55, 6, 0.65F),
        new HornNote(214, 50, 6, 0.65F),
        new HornNote(220, 55, 6, 0.65F),
        new HornNote(226, 55, 6, 0.65F),
        new HornNote(232, 57, 3, 0.65F),
        new HornNote(235, 60, 3, 0.65F),
        new HornNote(238, 59, 3, 0.65F),
        new HornNote(241, 57, 3, 0.65F),
        new HornNote(244, 62, 6, 0.65F),
        new HornNote(250, 62, 6, 0.65F),
        new HornNote(256, 62, 3, 0.65F),
        new HornNote(259, 64, 3, 0.65F),
        new HornNote(262, 59, 3, 0.65F),
        new HornNote(265, 60, 3, 0.65F),
        new HornNote(268, 57, 6, 0.65F),
        new HornNote(274, 57, 6, 0.65F),
        new HornNote(280, 57, 3, 0.65F),
        new HornNote(283, 60, 3, 0.65F),
        new HornNote(286, 59, 3, 0.65F),
        new HornNote(289, 57, 3, 0.65F),
        new HornNote(292, 55, 3, 0.65F),
        new HornNote(295, 67, 3, 0.65F),
        new HornNote(298, 66, 3, 0.65F),
        new HornNote(301, 64, 3, 0.65F),
        new HornNote(304, 62, 3, 0.65F),
        new HornNote(307, 60, 3, 0.65F),
        new HornNote(310, 59, 3, 0.65F),
        new HornNote(313, 57, 3, 0.65F),
        new HornNote(316, 55, 6, 0.65F),
        new HornNote(322, 55, 6, 0.65F),
        new HornNote(328, 57, 3, 0.65F),
        new HornNote(331, 60, 3, 0.65F),
        new HornNote(334, 59, 3, 0.65F),
        new HornNote(337, 57, 3, 0.65F),
        new HornNote(340, 62, 6, 0.65F),
        new HornNote(346, 62, 6, 0.65F),
        new HornNote(352, 62, 3, 0.65F),
        new HornNote(355, 64, 3, 0.65F),
        new HornNote(358, 59, 3, 0.65F),
        new HornNote(361, 60, 3, 0.65F),
        new HornNote(364, 57, 6, 0.65F),
        new HornNote(370, 57, 6, 0.65F),
        new HornNote(376, 57, 3, 0.65F),
        new HornNote(379, 60, 3, 0.65F),
        new HornNote(382, 59, 3, 0.65F),
        new HornNote(385, 57, 3, 0.65F),
        new HornNote(388, 55, 3, 0.65F),
        new HornNote(391, 62, 3, 0.65F),
        new HornNote(394, 57, 3, 0.65F),
        new HornNote(397, 59, 3, 0.65F),
        new HornNote(400, 55, 6, 0.65F)
    );

    private final Set<TrumpetCanCan> activeMelodies = new HashSet<>();
    private final List<Registration> registrations;

    private ExampleCallbacks(EmoteApi api) {
        this.registrations = List.of(
            api.registerCallbacks(IDLE_BUTTERFLY_CALLBACK_ID, new EmoteCallbacks() {
                public void onStart(PlaybackContext context) { spawnAllay(context); }
                public void onTick(PlaybackContext context) { moveEntity(context, 0.35F); }
                public void onClose(PlaybackContext context) { removeEntity(context, true); }
            }),

            api.registerCallbacks(BAT_CALLBACK_ID, new EmoteCallbacks() {
                public void onTick(PlaybackContext context) {
                    Integer tick = context.getAnimationTick();
                    if (tick == null) return;
                    if (tick >= 25 && tick < 210) {
                        if (context.getUserState() == null) spawnBat(context);
                        moveEntity(context, 0.6F);
                    } else if (tick >= 210 && context.getUserState() != null) {
                        moveEntity(context, 0.6F);
                        removeEntity(context, true);
                    }
                }
                public void onClose(PlaybackContext context) { removeEntity(context, false); }
            }),


            api.registerCallbacks(TRUMPET_CAN_CAN_CALLBACK_ID, new EmoteCallbacks() {
                public void onStart(PlaybackContext context) {
                    Vec3 origin = context.getRootPosition();
                    List<HornListener> listeners = context.getWorld().players().stream()
                        .filter(player -> player.position().distanceToSqr(origin) <= TrumpetPlayer.RANGE * TrumpetPlayer.RANGE)
                        .map(player -> new HornListener(player.getUUID(), player.connection::send))
                        .toList();
                    TrumpetCanCan melody = new TrumpetCanCan(0, origin, listeners);
                    context.setUserState(melody);
                    activeMelodies.add(melody);
                }
                public void onTick(PlaybackContext context) {
                    TrumpetCanCan melody = (TrumpetCanCan) context.getUserState();
                    long tick = context.getServer().getTickCount();
                    if (melody.activeNote != null && melody.activeNote.endTick() <= tick) melody.stopNote();
                    melody.advance(context.getElapsedTicks(), note -> playHorn(melody, note, tick));
                }
                public void onClose(PlaybackContext context) {
                    if (context.getUserState() instanceof TrumpetCanCan melody) {
                        activeMelodies.remove(melody);
                        melody.stopNote();
                    }
                }
            })
        );
    }

    public static ExampleCallbacks registerAll(EmoteApi api) {
        return new ExampleCallbacks(Objects.requireNonNull(api, "api"));
    }

    public boolean unregister() {
        boolean removed = false;
        for (Registration registration : this.registrations) removed |= registration.unregister();
        return removed;
    }

    private void spawnAllay(PlaybackContext context) {
        removeEntity(context, false);

        ServerLevel level = context.getWorld();
        Allay allay = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
        if (allay == null) {
            throw new IllegalStateException("Failed to create the idle butterfly Allay");
        }

        context.setUserState(allay);
        Vec3 origin = context.getNodeWorldPosition(context.getPayload()).orElseThrow();
        allay.snapTo(origin.x, origin.y, origin.z, context.getActor("actor").orElseThrow().getYRot(), 0.0F);
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
    }

    private void spawnBat(PlaybackContext context) {
        removeEntity(context, false);

        ServerLevel level = context.getWorld();
        Bat bat = EntityTypes.BAT.create(level, EntitySpawnReason.COMMAND);
        if (bat == null) throw new IllegalStateException("Failed to create the idle Bat");

        context.setUserState(bat);
        Vec3 origin = context.getNodeWorldPosition(context.getPayload()).orElseThrow();
        bat.snapTo(origin.x, origin.y, origin.z, context.getActor("actor").orElseThrow().getYRot(), 0.0F);
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
    }

    private void moveEntity(PlaybackContext context, float interpolationSpeed) {
        if (!(context.getUserState() instanceof LivingEntity entity) || entity.isRemoved()) return;

        Vec3 destination = context.getNodeWorldPosition(context.getPayload()).orElseThrow();
        Vec3 movement = destination.subtract(entity.position());
        double horizontalDistance = movement.horizontalDistance();
        if (horizontalDistance > 1.0E-6D) {
            float targetYaw = (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.rotLerp(interpolationSpeed, entity.getYRot(), targetYaw);
            entity.setYRot(yaw);
            entity.setYBodyRot(yaw);
            entity.setYHeadRot(yaw);
            entity.setXRot((float) Mth.clamp(-(Mth.atan2(movement.y, horizontalDistance) * Mth.RAD_TO_DEG), -35.0D, 35.0D));
        }
        entity.teleportTo(destination.x, destination.y, destination.z);
    }

    private void removeEntity(PlaybackContext context, boolean particles) {
        if (!(context.getUserState() instanceof Entity entity)) return;
        context.setUserState(null);
        if (particles && !entity.isRemoved() && entity.level() instanceof ServerLevel level) {
            if (entity instanceof Allay) {
                level.sendParticles(ParticleTypes.WHITE_SMOKE, entity.getX(), entity.getY(0.5D), entity.getZ(), 7, 0.08D, 0.08D, 0.08D, 0.02D);
            } else if (entity instanceof Bat) {
                level.sendParticles(ParticleTypes.SMOKE, entity.getX(), entity.getY(0.5D), entity.getZ(), 12, 0.16D, 0.16D, 0.16D, 0.02D);
            }
        }
        entity.discard();
    }

    private void playHorn(TrumpetCanCan melody, HornNote note, long tick) {
        melody.stopNote();
        Vec3 origin = melody.origin;
        var random = ThreadLocalRandom.current();
        var particle = new ClientboundLevelParticlesPacket(
            ParticleTypes.NOTE, false, false,
            origin.x + random.nextDouble(-0.8D, 0.8D), origin.y + random.nextDouble(0.8D, 2.1D), origin.z + random.nextDouble(-0.8D, 0.8D),
            (float) ((note.midi() % 12) / 12.0), 0.0F, 0.0F, 1.0F, 0
        );
        List<HornVoice> voices = new ArrayList<>();
        for (HornListener listener : melody.listeners) {
            boolean soundOccupied = this.activeMelodies.stream()
                .map(active -> active.activeNote)
                .filter(active -> active != null && active.endTick() > tick)
                .flatMap(active -> active.voices().stream())
                .anyMatch(voice -> voice.listener().id().equals(listener.id()) && voice.sound().equals(TrumpetPlayer.SOUND));
            if (soundOccupied) continue;
            TrumpetPlayer.play(melody.origin, note.midi(), note.volume(), tick, listener.send());
            listener.send().accept(particle);
            voices.add(new HornVoice(listener, TrumpetPlayer.SOUND));
        }
        if (!voices.isEmpty()) {
            long endTick = tick + Math.min(note.durationTicks(), MAX_HORN_DURATION_TICKS);
            melody.activeNote = new ActiveHornNote(endTick, List.copyOf(voices));
        }
    }

    record HornNote(int tick, double midi, int durationTicks, float volume) {}

    static final class TrumpetCanCan {
        private final long startTick;
        private final Vec3 origin;
        private final List<HornListener> listeners;
        private int nextNote;
        private ActiveHornNote activeNote;

        TrumpetCanCan(long startTick, Vec3 origin, List<HornListener> listeners) {
            this.startTick = startTick;
            this.origin = origin;
            this.listeners = listeners;
        }

        void stopNote() {
            ActiveHornNote note = this.activeNote;
            this.activeNote = null;
            if (note != null) note.stop();
        }

        boolean advance(long tick, Consumer<HornNote> play) {
            while (this.nextNote < TRUMPET_CAN_CAN_NOTES.size()) {
                HornNote note = TRUMPET_CAN_CAN_NOTES.get(this.nextNote);
                if (tick - this.startTick < note.tick()) return false;
                this.nextNote++;
                play.accept(note);
            }
            return true;
        }
    }

    private record HornListener(UUID id, Consumer<Packet<?>> send) {}
    private record HornVoice(HornListener listener, Identifier sound) {}
    private record ActiveHornNote(long endTick, List<HornVoice> voices) {
        void stop() {
            for (HornVoice voice : this.voices) {
                TrumpetPlayer.stop(voice.listener().send());
            }
        }
    }
}
