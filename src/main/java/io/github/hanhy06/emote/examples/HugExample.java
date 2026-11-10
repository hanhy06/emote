package io.github.hanhy06.emote.examples;

import io.github.hanhy06.emote.api.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates a two-player emote from a callback registered on the offering Sequence.
 * The Sequence offers the sign at step 0, stows it at step 1, and hugs at step 2.
 * The owner's session and display entities stay alive through all three steps.
 * On acceptance, the partner's animation appears immediately and holds its starting pose.
 * Its callback payload names the exported locator for the second player's starting position.
 */
public final class HugExample implements EmoteCallbacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(HugExample.class);

    // Emote cleans up entities with this tag after a server restart.
    private static final String RUNTIME_ENTITY_TAG = "emote.runtime";
    private static final Identifier PUT_AWAY_SIGN = Identifier.parse("hug:put_away_sign");
    private static final Identifier HUG_PLAYER1 = Identifier.parse("hug:hug_player1");
    private static final int STOW_STEP = 1;
    private static final int HUG_STEP = 2;
    private static final int SIGN_READY_TICK = 31;

    private final EmoteApi api;
    private final Map<UUID, Hug> hugs = new HashMap<>();

    public HugExample(EmoteApi api) {
        this.api = api;
    }

    @Override
    public void onStart(PlaybackContext context) {
        ServerPlayer owner = (ServerPlayer) context.getActor().orElseThrow();
        Hug previous = this.hugs.get(owner.getUUID());

        if (previous != null) {
            finish(previous);
        }

        Hug hug = new Hug(owner, context, this.api.get(PUT_AWAY_SIGN).orElseThrow().durationTicks());
        this.hugs.put(owner.getUUID(), hug);

        // The Sequence callback keeps the same state when its active animation changes.
        context.setUserState(hug);
        updatePlacement(context, hug);
    }

    @Override
    public void onTick(PlaybackContext context) {
        Hug hug = (Hug) context.getUserState();

        if (this.hugs.get(hug.owner.getUUID()) != hug || hug.stage == Stage.HUGGING) {
            return;
        }

        PlaybackPosition position = context.getPosition();
        if (position == null) {
            return;
        }

        if (Integer.valueOf(HUG_STEP).equals(position.stepIndex())) {
            // Without a partner, stop at the neutral boundary before the hug begins.
            finish(hug);
            return;
        }

        if (Integer.valueOf(STOW_STEP).equals(position.stepIndex())) {
            hug.stage = Stage.STOWING;
            if (hug.interaction != null) {
                hug.interaction.discard();
                hug.interaction = null;
            }

            if (hug.partnerPlayback == null) {
                return;
            }

            Integer tick = context.getAnimationTick();
            if (tick != null && tick >= hug.stowDurationTicks - 1) {
                embrace(hug);
            } else {
                // Keep the partner visible at the starting pose until the sign is stowed.
                hug.partnerPlayback.setAnimationTick(0);
            }
            return;
        }

        if (hug.stage != Stage.WAITING) {
            return;
        }

        updatePlacement(context, hug);
        Integer tick = context.getAnimationTick();

        // Allow interaction after the sign-drawing motion has finished.
        if (tick == null || tick < SIGN_READY_TICK) {
            return;
        }

        if (hug.interaction == null) {
            // Use the vanilla entity type, with this example handling its server-side click.
            hug.interaction = new Interaction(EntityTypes.INTERACTION, context.getWorld()) {
                @Override
                public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
                    if (player instanceof ServerPlayer partner) {
                        accept(hug, partner);
                    }

                    return InteractionResult.CONSUME;
                }
            };

            // Width and height are synchronized entity data, configured through vanilla NBT.
            CompoundTag dimensions = new CompoundTag();
            dimensions.putFloat("width", 1.0F);
            dimensions.putFloat("height", 1.8F);
            TypedEntityData.of(EntityTypes.INTERACTION, dimensions).loadInto(hug.interaction);

            hug.interaction.addTag(RUNTIME_ENTITY_TAG);
            positionInteraction(hug);
            if (!context.getWorld().addFreshEntity(hug.interaction)) {
                throw new IllegalStateException("Failed to add the hug Interaction");
            }
        } else {
            positionInteraction(hug);
        }
    }

    @Override
    public void onClose(PlaybackContext context) {
        if (context.getUserState() instanceof Hug hug) {
            finish(hug);
        }
    }

    /** Releases this example's interactions and playback sessions when it is unregistered. */
    public void close() {
        for (Hug hug : List.copyOf(this.hugs.values())) {
            finish(hug);
        }
    }

    /** Called by the server tick listener registered alongside this callback. */
    public void tick() {
        for (Hug hug : List.copyOf(this.hugs.values())) {
            if (
                !hug.ownerPlayback.isActive()
                    || (hug.partnerPlayback != null && !hug.partnerPlayback.isActive())
            ) {
                // Also catches sessions cancelled before their first frame was applied.
                finish(hug);
            }
        }
    }

    private void updatePlacement(PlaybackContext context, Hug hug) {
        hug.ownerPlacement = PlaybackPlacement.external(
            context.getRootPosition(), context.getPlacement().yaw()
        );

        // The locator provides world position; this example faces the partner toward the owner.
        hug.partnerPlacement = PlaybackPlacement.external(
            context.getNodeWorldPosition(context.getPayload()).orElseThrow(),
            context.getPlacement().yaw() + 180.0F
        );
    }

    private static void positionInteraction(Hug hug) {
        // An Interaction's position is the bottom center of its bounding box.
        hug.interaction.setPos(hug.ownerPlacement.position());
    }

    private void accept(Hug hug, ServerPlayer partner) {
        if (this.hugs.get(hug.owner.getUUID()) != hug || hug.stage != Stage.WAITING) {
            return;
        }

        if (partner == hug.owner || !partner.isAlive() || partner.isSpectator()
            || partner.level() != hug.owner.level()) {
            return;
        }

        // A player can only participate in one active hug at a time.
        if (this.hugs.values().stream().anyMatch(active -> active.owner == partner || active.partner == partner)) {
            return;
        }

        if (!hug.ownerPlayback.setStep(STOW_STEP, 0, 0)) {
            return;
        }

        // Freeze the placement and reserve the partner before another click can be accepted.
        hug.ownerPlayback.setPlacement(hug.ownerPlacement);
        hug.partner = partner;
        hug.stage = Stage.STOWING;
        if (hug.interaction != null) {
            hug.interaction.discard();
            hug.interaction = null;
        }

        try {
            // Move before playback so both the camera and movement cancellation use this position.
            Vec3 partnerPosition = hug.partnerPlacement.position();
            partner.connection.teleport(
                partnerPosition.x, partnerPosition.y, partnerPosition.z,
                hug.partnerPlacement.yaw(), 0.0F
            );

            PlayResult result = this.api.play(partner, HUG_PLAYER1, hug.partnerPlacement);
            if (result instanceof PlayResult.Success success) {
                hug.partnerPlayback = success.playback();
            } else {
                partner.sendSystemMessage(result.errorMessage());
                finish(hug);
            }
        } catch (RuntimeException exception) {
            finish(hug);
            LOGGER.warn("Failed to prepare the hug partner", exception);
        }
    }

    private void embrace(Hug hug) {
        // Recheck availability: either player may have left or stopped playback while stowing.
        if (!hug.owner.isAlive() || !hug.partner.isAlive()
            || hug.owner.level() != hug.partner.level()
            || hug.owner.level().getPlayerByUUID(hug.owner.getUUID()) != hug.owner
            || hug.owner.level().getPlayerByUUID(hug.partner.getUUID()) != hug.partner
            || !hug.partnerPlayback.isActive()) {
            finish(hug);
            return;
        }
        hug.stage = Stage.HUGGING;
        try {
            // Start both hug motions next tick without recreating either model.
            if (!hug.ownerPlayback.setStep(HUG_STEP, 0, 0)
                || !hug.partnerPlayback.setAnimationTick(0)) {
                finish(hug);
            }
        } catch (RuntimeException exception) {
            finish(hug);
            LOGGER.warn("Failed to start the hug example", exception);
        }
    }

    private void finish(Hug hug) {
        // Remove shared state first so later callbacks cannot clean up the same flow twice.
        if (!this.hugs.remove(hug.owner.getUUID(), hug)) {
            return;
        }

        if (hug.interaction != null) {
            hug.interaction.discard();
        }

        // These objects keep addressing the original sessions if either player starts another emote.
        hug.ownerPlayback.stop();
        if (hug.partnerPlayback != null) {
            hug.partnerPlayback.stop();
        }
    }

    private enum Stage {
        WAITING,
        STOWING,
        HUGGING
    }

    /** Shared state for the owner's Sequence and the partner's direct animation playback. */
    private static final class Hug {
        final ServerPlayer owner;

        final Playback ownerPlayback;
        final int stowDurationTicks;
        Playback partnerPlayback;
        ServerPlayer partner;

        Stage stage = Stage.WAITING;
        Interaction interaction;
        PlaybackPlacement ownerPlacement;
        PlaybackPlacement partnerPlacement;

        Hug(ServerPlayer owner, Playback ownerPlayback, int stowDurationTicks) {
            this.owner = owner;
            this.ownerPlayback = ownerPlayback;
            this.stowDurationTicks = stowDurationTicks;
        }
    }
}
