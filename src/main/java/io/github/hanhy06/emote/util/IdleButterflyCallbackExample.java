package io.github.hanhy06.emote.util;

import io.github.hanhy06.emote.api.EmoteApi;
import io.github.hanhy06.emote.api.EmoteCallbackEvent;
import io.github.hanhy06.emote.api.EmotePlaybackListener;
import io.github.hanhy06.emote.api.ListenerRegistration;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.allay.Allay;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Example listener for an animation event callback declared as follows:
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
public final class IdleButterflyCallbackExample {
    public static final Identifier CALLBACK_ID = Identifier.parse("emote:idle_butterfly_callback");

    private static final double ALLAY_SCALE = 0.35D;

    private final Map<UUID, Allay> allaysByPlayer = new HashMap<>();
    private final ListenerRegistration callbackRegistration;
    private final ListenerRegistration playbackRegistration;

    private boolean registered = true;

    private IdleButterflyCallbackExample(EmoteApi api) {
        this.callbackRegistration = api.addCallbackListener(CALLBACK_ID, this::handleCallback);
        this.playbackRegistration = api.addPlaybackListener(new EmotePlaybackListener() {
            @Override
            public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
                removeAllay(playback.playerUuid());
            }
        });
    }

    public static IdleButterflyCallbackExample register(EmoteApi api) {
        return new IdleButterflyCallbackExample(Objects.requireNonNull(api, "api"));
    }

    public boolean unregister() {
        if (!this.registered) return false;
        this.registered = false;

        boolean callbackRemoved = this.callbackRegistration.unregister();
        boolean playbackRemoved = this.playbackRegistration.unregister();
        this.allaysByPlayer.values().forEach(Allay::discard);
        this.allaysByPlayer.clear();
        return callbackRemoved || playbackRemoved;
    }

    private void handleCallback(EmoteCallbackEvent event) {
        switch (event.phase()) {
            case START -> spawnAllay(event);
            case TIMELINE -> moveAllay(event);
            case STOP -> removeAllay(event.player().getUUID());
            case LOOP -> {
            }
        }
    }

    private void spawnAllay(EmoteCallbackEvent event) {
        UUID playerUuid = event.player().getUUID();
        removeAllay(playerUuid);

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
        Objects.requireNonNull(allay.getAttribute(Attributes.SCALE), "Allay scale attribute").setBaseValue(ALLAY_SCALE);

        if (!level.addFreshEntity(allay)) {
            allay.discard();
            throw new IllegalStateException("Failed to add the idle butterfly Allay to the level");
        }
        this.allaysByPlayer.put(playerUuid, allay);
    }

    private void moveAllay(EmoteCallbackEvent event) {
        Allay allay = this.allaysByPlayer.get(event.player().getUUID());
        if (allay == null || allay.isRemoved()) return;

        allay.teleportTo(event.origin().x, event.origin().y, event.origin().z);
    }

    private void removeAllay(UUID playerUuid) {
        Allay allay = this.allaysByPlayer.remove(playerUuid);
        if (allay != null) {
            allay.discard();
        }
    }
}
