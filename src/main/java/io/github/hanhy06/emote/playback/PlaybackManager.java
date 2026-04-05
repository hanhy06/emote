package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteOptions;
import io.github.hanhy06.emote.emote.PlayableEmoteSelection;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import io.github.hanhy06.emote.playback.data.BoundEmoteSkinPart;
import io.github.hanhy06.emote.playback.data.PlaybackStartResult;
import io.github.hanhy06.emote.skin.PreparedPlayerSkin;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackManager {
    private static final long TICKS_PER_KEYFRAME = 2L;
    private static final long PLAYBACK_BUFFER_TICKS = 8L;
    private static final double MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED = 0.01D;
    private static final double MOVE_STOP_VERTICAL_DISTANCE = 0.12D;
    private static final double NAMESPACE_CLEANUP_SEARCH_DISTANCE = 24.0D;
    private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();
    private final PlayerSkinManager playerSkinManager;
    private PlaybackStateListener stateListener = PlaybackStateListener.NONE;

    public PlaybackManager(PlayerSkinManager playerSkinManager) {
        this.playerSkinManager = playerSkinManager;
    }

    public void setStateListener(PlaybackStateListener stateListener) {
        this.stateListener = stateListener == null ? PlaybackStateListener.NONE : stateListener;
    }

    public PlaybackStartResult startEmote(ServerPlayer player, PlayableEmoteSelection selection) {
        EmoteDefinition definition = selection.definition();
        EmoteAnimation animation = selection.animation();
        EmoteOptions options = selection.options();

        MinecraftServer server = server();
        if (server == null) {
            return PlaybackStartResult.failure("Server unavailable.");
        }

        String namespace = definition.namespace();
        PlaybackFunctionIds functionIds = resolveFunctionIds(server, namespace, animation);
        if (functionIds == null) {
            return PlaybackStartResult.failure("Datapack not loaded.");
        }

        resetPlayerPlayback(player, namespace);

        PlaybackStartSnapshot startSnapshot = startPlayback(player, definition, functionIds, options);
        ActiveEmote activeEmote = createActiveEmote(
                server,
                player,
                namespace,
                animation,
                startSnapshot
        );
        this.activeEmoteMap.put(player.getUUID(), activeEmote);
        this.stateListener.onEmoteStarted(player, activeEmote);
        return PlaybackStartResult.SUCCESS;
    }

    public ActiveEmote stopEmote(ServerPlayer player) {
        return stopEmote(player.getUUID());
    }

    private ActiveEmote stopEmote(UUID playerUuid) {
        MinecraftServer server = server();
        if (server == null) {
            return null;
        }

        ActiveEmote activeEmote = this.activeEmoteMap.remove(playerUuid);
        if (activeEmote == null) {
            return null;
        }

        stopActiveEmote(server, activeEmote);
        return activeEmote;
    }

    public ActiveEmote findActiveEmote(UUID playerUuid) {
        return this.activeEmoteMap.get(playerUuid);
    }

    public List<ActiveEmote> getActiveEmotes() {
        return List.copyOf(this.activeEmoteMap.values());
    }

    public void tick() {
        MinecraftServer server = server();
        if (server == null || this.activeEmoteMap.isEmpty()) {
            return;
        }

        long currentTick = server.getTickCount();
        List<UUID> playerUuidListToStop = new ArrayList<>();

        for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
            if (!canKeepPlaying(player, activeEmote, currentTick)) {
                playerUuidListToStop.add(activeEmote.playerUuid());
                continue;
            }

            if (hasMovedDuringPlayback(player, activeEmote)) {
                playerUuidListToStop.add(activeEmote.playerUuid());
                player.sendSystemMessage(Component.literal("Stop: moved"));
            }
        }

        for (UUID playerUuid : playerUuidListToStop) {
            stopEmote(playerUuid);
        }
    }

    public void stopAllEmotes() {
        List<UUID> playerUuidList = List.copyOf(this.activeEmoteMap.keySet());
        for (UUID playerUuid : playerUuidList) {
            stopEmote(playerUuid);
        }
    }

    private PlaybackFunctionIds resolveFunctionIds(MinecraftServer server, String namespace, EmoteAnimation animation) {
        String createFunctionId = namespace + ":_/create";
        String playFunctionId = namespace + ":a/" + animation.datapackAnimationName() + "/" + animation.playFunctionName();
        if (!isLoadedFunction(server, createFunctionId) || !isLoadedFunction(server, playFunctionId)) {
            return null;
        }

        return new PlaybackFunctionIds(createFunctionId, playFunctionId);
    }

    private void resetPlayerPlayback(ServerPlayer player, String namespace) {
        stopMatchingNamespaceEmotes(player.getUUID(), namespace);
        this.stopEmote(player);
        cleanupNamespace(player, namespace);
    }

    private PlaybackStartSnapshot startPlayback(
            ServerPlayer player,
            EmoteDefinition definition,
            PlaybackFunctionIds functionIds,
            EmoteOptions options
    ) {
        executeFunction(player, functionIds.createFunctionId());
        alignRootWithPlayer(player, definition.namespace());

        PreparedPlayerSkin preparedPlayerSkin = this.playerSkinManager.preparePlayerSkin(player, definition);
        executeFunction(player, functionIds.playFunctionId());

        List<BoundEmoteSkinPart> boundSkinParts = this.playerSkinManager.captureBoundSkinParts(player, definition);
        boolean playerVisibilityManaged = !options.visiblePlayer();
        boolean wasInvisible = player.isInvisible();
        if (playerVisibilityManaged) {
            player.setInvisible(true);
        }
        return new PlaybackStartSnapshot(preparedPlayerSkin, boundSkinParts, playerVisibilityManaged, wasInvisible);
    }

    private ActiveEmote createActiveEmote(
            MinecraftServer server,
            ServerPlayer player,
            String namespace,
            EmoteAnimation animation,
            PlaybackStartSnapshot startSnapshot
    ) {
        long stopTick = calculateStopTick(server.getTickCount(), animation);
        return new ActiveEmote(
                player.getUUID(),
                player.level().dimension(),
                namespace,
                animation.name(),
                player.position(),
                stopTick,
                startSnapshot.playerVisibilityManaged(),
                startSnapshot.wasInvisible(),
                startSnapshot.boundSkinParts(),
                startSnapshot.preparedPlayerSkin()
        );
    }

    private boolean canKeepPlaying(ServerPlayer player, ActiveEmote activeEmote, long currentTick) {
        if (player == null || !player.isAlive()) {
            return false;
        }

        if (!player.level().dimension().equals(activeEmote.levelKey())) {
            return false;
        }

        return currentTick < activeEmote.stopTick();
    }

    private boolean hasMovedDuringPlayback(ServerPlayer player, ActiveEmote activeEmote) {
        return hasMoved(player.position(), activeEmote.startPosition());
    }

    private void stopMatchingNamespaceEmotes(UUID playerUuid, String namespace) {
        List<UUID> playerUuidListToStop = new ArrayList<>();
        for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
            if (activeEmote.playerUuid().equals(playerUuid)) {
                continue;
            }

            if (!activeEmote.namespace().equals(namespace)) {
                continue;
            }

            playerUuidListToStop.add(activeEmote.playerUuid());
        }

        for (UUID otherPlayerUuid : playerUuidListToStop) {
            stopEmote(otherPlayerUuid);
        }
    }

    private void stopActiveEmote(MinecraftServer server, ActiveEmote activeEmote) {
        executeFunction(activeEmote, activeEmote.namespace() + ":_/stop_anim");
        executeFunction(activeEmote, activeEmote.namespace() + ":_/delete");
        ServerLevel level = server.getLevel(activeEmote.levelKey());
        if (level != null) {
            cleanupNamespaceEntitiesNearby(level, activeEmote.namespace(), activeEmote.startPosition());
        }

        ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
        if (player != null) {
            if (activeEmote.playerVisibilityManaged()) {
                player.setInvisible(activeEmote.wasInvisible());
            }
            this.stateListener.onEmoteStopped(player, activeEmote);
        }
    }

    private static long calculatePlaybackTicks(int keyframeCount) {
        return Math.max(20L, (long) keyframeCount * TICKS_PER_KEYFRAME + PLAYBACK_BUFFER_TICKS);
    }

    static long calculateStopTick(long currentTick, EmoteAnimation animation) {
        if (animation.loop()) {
            return Long.MAX_VALUE;
        }

        return currentTick + calculatePlaybackTicks(animation.keyframeCount());
    }

    private boolean hasMoved(Vec3 currentPosition, Vec3 startPosition) {
        double xDistance = currentPosition.x - startPosition.x;
        double zDistance = currentPosition.z - startPosition.z;
        double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
        double verticalDistance = Math.abs(currentPosition.y - startPosition.y);
        return horizontalDistanceSquared > MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED || verticalDistance > MOVE_STOP_VERTICAL_DISTANCE;
    }

    private boolean isLoadedFunction(MinecraftServer server, String functionId) {
        Identifier identifier = Identifier.tryParse(functionId);
        return identifier != null && server.getFunctions().get(identifier).isPresent();
    }

    private void cleanupNamespace(ServerPlayer player, String namespace) {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }

        if (isLoadedFunction(server, namespace + ":_/delete")) {
            executeFunction(player, namespace + ":_/delete");
        }

        cleanupNamespaceEntities(player.level(), namespace);
    }

    private void cleanupNamespaceEntitiesNearby(ServerLevel level, String namespace, Vec3 origin) {
        AABB searchBox = new AABB(origin, origin).inflate(NAMESPACE_CLEANUP_SEARCH_DISTANCE);
        List<Display> displaysToKill = level.getEntitiesOfClass(
                Display.class,
                searchBox,
                entity -> matchesNamespaceDisplay(entity, namespace)
        );
        for (Entity entity : displaysToKill) {
            if (!entity.isRemoved()) {
                entity.kill(level);
            }
        }
    }

    private void cleanupNamespaceEntities(ServerLevel level, String namespace) {
        Map<Integer, Entity> entitiesToKill = new LinkedHashMap<>();

        for (Entity entity : level.getAllEntities()) {
            if (!matchesNamespaceDisplay(entity, namespace)) {
                continue;
            }

            collectEntityTree(entity, entitiesToKill);
        }

        for (Entity entity : entitiesToKill.values()) {
            if (!entity.isRemoved()) {
                entity.kill(level);
            }
        }
    }

    private void collectEntityTree(Entity entity, Map<Integer, Entity> entitiesToKill) {
        if (entitiesToKill.containsKey(entity.getId())) {
            return;
        }

        for (Entity passenger : entity.getPassengers()) {
            collectEntityTree(passenger, entitiesToKill);
        }

        entitiesToKill.put(entity.getId(), entity);
    }

    private boolean matchesNamespaceDisplay(Entity entity, String namespace) {
        if (!(entity instanceof Display)) {
            return false;
        }

        for (String tag : entity.entityTags()) {
            if (isCleanupTag(tag, namespace)) {
                return true;
            }
        }

        return false;
    }

    private boolean isCleanupTag(String tag, String namespace) {
        if (tag.equals(namespace) || tag.equals(namespace + "_root") || tag.equals(namespace + "_camera")) {
            return true;
        }

        if (!tag.startsWith(namespace + "_")) {
            return false;
        }

        String suffix = tag.substring(namespace.length() + 1);
        if (suffix.isEmpty()) {
            return false;
        }

        if (suffix.charAt(0) == 'p') {
            return suffix.length() > 1 && suffix.substring(1).chars().allMatch(Character::isDigit);
        }

        return suffix.chars().allMatch(Character::isDigit);
    }

    private void alignRootWithPlayer(ServerPlayer player, String namespace) {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }

        float yaw = Mth.wrapDegrees(player.getYRot() + 180.0F);
        CommandSourceStack source = player.createCommandSourceStack()
                .withAnchor(EntityAnchorArgument.Anchor.FEET)
                .withRotation(new Vec2(0.0F, yaw))
                .withMaximumPermission(LevelBasedPermissionSet.OWNER)
                .withSuppressedOutput();
        String rootSelector = "@e[type=minecraft:block_display,tag=" + namespace + "_root,limit=1,sort=nearest]";
        String command = "tp " + rootSelector + " ^ ^ ^ " + yaw + " 0";
        server.getCommands().performPrefixedCommand(source, command);
    }

    private void executeFunction(ServerPlayer player, String functionId) {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }

        CommandSourceStack source = player.createCommandSourceStack()
                .withMaximumPermission(LevelBasedPermissionSet.OWNER)
                .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function " + functionId);
    }

    private void executeFunction(ActiveEmote activeEmote, String functionId) {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }

        ServerLevel level = server.getLevel(activeEmote.levelKey());
        if (level == null) {
            return;
        }

        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(level)
                .withMaximumPermission(LevelBasedPermissionSet.OWNER)
                .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function " + functionId);
    }

    private MinecraftServer server() {
        return Emote.SERVER;
    }

    private record PlaybackFunctionIds(String createFunctionId, String playFunctionId) {
    }

    private record PlaybackStartSnapshot(
            PreparedPlayerSkin preparedPlayerSkin,
            List<BoundEmoteSkinPart> boundSkinParts,
            boolean playerVisibilityManaged,
            boolean wasInvisible
    ) {
    }
}
