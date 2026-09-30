package io.github.hanhy06.emote.api;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Consumer;

public interface PlaybackContext {
    PlaybackHandle playback();

    MinecraftServer server();

    ServerLevel level();

    long elapsedTicks();

    int clipTick();

    Optional<Entity> actor(String name);

    Optional<Entity> nodeEntity(String nodeId);

    Vec3 nodeWorldPosition(String nodeId);

    Vec3 originAtInvocation();

    TaskHandle afterTicks(long delay, Runnable action);

    TaskHandle everyTicks(long period, Runnable action);

    default TaskHandle everyTick(Runnable action) {
        return everyTicks(1, action);
    }

    TaskHandle onSignal(Identifier name, Consumer<JsonObject> handler);

    void emitSignal(Identifier name, JsonObject parameters);

    void onClose(Runnable cleanup);
}
