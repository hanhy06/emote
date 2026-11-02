package io.github.hanhy06.emote.examples;

import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlaybackContext;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class ChristmasExample implements EmoteCallbacks {
    @Override
    public void onStart(PlaybackContext context) {
        ServerLevel level = context.getWorld();
        Vec3 position = context.getRootPosition();
        level.sendParticles(ParticleTypes.SNOWFLAKE, position.x, position.y + 1.1, position.z, 56, 0.8, 0.65, 0.8, 0.05);
        level.sendParticles(ParticleTypes.POOF, position.x, position.y + 1.1, position.z, 8, 0.6, 0.55, 0.6, 0.02);
    }
}
