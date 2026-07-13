package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.PlayableEmoteSelection;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import io.github.hanhy06.emote.playback.data.PlaybackStartResult;
import io.github.hanhy06.emote.skin.PreparedPlayerSkin;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.PlayerSkinPreparationResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackManager {
    private static final double MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED = 0.01D;
    private static final double MOVE_STOP_VERTICAL_DISTANCE = 0.12D;
    private static final double NAMESPACE_CLEANUP_SEARCH_DISTANCE = 24.0D;
    private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSkinApplication> pendingSkinApplicationMap = new ConcurrentHashMap<>();
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

        MinecraftServer server = server();
        if (server == null) {
            return PlaybackStartResult.failure("Server unavailable.");
        }

        String namespace = definition.namespace();
        PlaybackFunctionIds functionIds = resolveFunctionIds(server, definition);
        if (functionIds == null) {
            return PlaybackStartResult.failure("Datapack not loaded.");
        }

        PlayerSkinPreparationResult skinPreparation = this.playerSkinManager.preparePlayerSkin(player, definition);
        if (!skinPreparation.isReady()) {
            return PlaybackStartResult.failure(skinPreparation.errorMessage());
        }

        resetPlayerPlayback(player, namespace);
        ActiveEmote namespaceTimeline = findActiveNamespaceEmote(namespace);

        PlaybackStartSnapshot startSnapshot = startPlayback(
                player,
                definition,
                functionIds,
                namespaceTimeline
        );
        if (startSnapshot == null) {
            return PlaybackStartResult.failure("Datapack did not create an emote root.");
        }
        ActiveEmote activeEmote = createActiveEmote(
                player,
                namespace,
                startSnapshot
        );
        this.activeEmoteMap.put(player.getUUID(), activeEmote);
        if (skinPreparation.preparedSkin() != null) {
            this.pendingSkinApplicationMap.put(
                    player.getUUID(),
                    new PendingSkinApplication(definition, skinPreparation.preparedSkin())
            );
        }
        this.stateListener.onEmoteStarted(player, activeEmote);
        return PlaybackStartResult.SUCCESS;
    }

    public ActiveEmote stopEmote(ServerPlayer player) {
        return stopEmote(player.getUUID());
    }

    private ActiveEmote stopEmote(UUID playerUuid) {
        this.pendingSkinApplicationMap.remove(playerUuid);
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

    public void tick() {
        MinecraftServer server = server();
        if (server == null || this.activeEmoteMap.isEmpty()) {
            return;
        }

        List<UUID> playerUuidListToStop = new ArrayList<>();

        for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
            if (!canKeepPlaying(player, activeEmote)) {
                playerUuidListToStop.add(activeEmote.playerUuid());
                continue;
            }

            if (hasMovedDuringPlayback(player, activeEmote)) {
                playerUuidListToStop.add(activeEmote.playerUuid());
            }
        }

        for (UUID playerUuid : playerUuidListToStop) {
            stopEmote(playerUuid);
        }

        applyPendingPlayerSkins(server);
    }

    public void stopAllEmotes() {
        List<UUID> playerUuidList = List.copyOf(this.activeEmoteMap.keySet());
        for (UUID playerUuid : playerUuidList) {
            stopEmote(playerUuid);
        }
        this.pendingSkinApplicationMap.clear();
    }

    private PlaybackFunctionIds resolveFunctionIds(MinecraftServer server, EmoteDefinition definition) {
        String namespace = definition.namespace();
        String createFunctionId = namespace + ":_/create";
        String playFunctionId = namespace + ":" + definition.entrypoint();
        if (!isLoadedFunction(server, createFunctionId) || !isLoadedFunction(server, playFunctionId)) {
            return null;
        }

        return new PlaybackFunctionIds(createFunctionId, playFunctionId);
    }

    private void resetPlayerPlayback(ServerPlayer player, String namespace) {
        this.stopEmote(player);
        if (!hasActiveNamespace(namespace)) {
            cleanupNamespace(player, namespace);
        }
    }

    private PlaybackStartSnapshot startPlayback(
            ServerPlayer player,
            EmoteDefinition definition,
            PlaybackFunctionIds functionIds,
            ActiveEmote namespaceTimeline
    ) {
        ServerLevel level = player.level();
        Set<UUID> existingEntityUuids = findNamespaceEntityUuids(level, definition.namespace());
        executeFunction(player, functionIds.createFunctionId());
        Set<UUID> instanceEntityUuids = findNamespaceEntityUuids(level, definition.namespace());
        instanceEntityUuids.removeAll(existingEntityUuids);

        UUID rootEntityUuid = findRootEntityUuid(level, definition.namespace(), instanceEntityUuids);
        if (rootEntityUuid == null) {
            cleanupInstanceEntities(level, instanceEntityUuids);
            return null;
        }

        alignRootWithPlayer(player, rootEntityUuid);

        if (namespaceTimeline == null) {
            executeFunction(player, functionIds.playFunctionId());
        } else {
            copyAnimationState(namespaceTimeline, level.getEntity(rootEntityUuid));
        }
        boolean playerVisibilityManaged = definition.hidePlayer();
        boolean wasInvisible = player.isInvisible();
        if (playerVisibilityManaged) {
            player.setInvisible(true);
        }
        return new PlaybackStartSnapshot(
                rootEntityUuid,
                instanceEntityUuids,
                playerVisibilityManaged,
                wasInvisible
        );
    }

    private void applyPendingPlayerSkins(MinecraftServer server) {
        for (Map.Entry<UUID, PendingSkinApplication> entry : this.pendingSkinApplicationMap.entrySet()) {
            UUID playerUuid = entry.getKey();
            PendingSkinApplication pendingApplication = entry.getValue();
            ActiveEmote activeEmote = this.activeEmoteMap.get(playerUuid);
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (activeEmote == null
                    || player == null
                    || !activeEmote.namespace().equals(pendingApplication.definition().namespace())) {
                this.pendingSkinApplicationMap.remove(playerUuid, pendingApplication);
                continue;
            }

            this.playerSkinManager.applySkinParts(
                    player,
                    pendingApplication.definition(),
                    pendingApplication.preparedPlayerSkin(),
                    activeEmote.rootEntityUuid()
            );
            this.pendingSkinApplicationMap.remove(playerUuid, pendingApplication);
        }
    }

    private ActiveEmote createActiveEmote(
            ServerPlayer player,
            String namespace,
            PlaybackStartSnapshot startSnapshot
    ) {
        return new ActiveEmote(
                player.getUUID(),
                player.level().dimension(),
                namespace,
                player.position(),
                startSnapshot.rootEntityUuid(),
                startSnapshot.instanceEntityUuids(),
                startSnapshot.playerVisibilityManaged(),
                startSnapshot.wasInvisible()
        );
    }

    private boolean canKeepPlaying(ServerPlayer player, ActiveEmote activeEmote) {
        if (player == null || !player.isAlive()) {
            return false;
        }

        if (!player.level().dimension().equals(activeEmote.levelKey())) {
            return false;
        }

        Entity rootEntity = player.level().getEntity(activeEmote.rootEntityUuid());
        return rootEntity != null && !rootEntity.isRemoved();
    }

    private boolean hasMovedDuringPlayback(ServerPlayer player, ActiveEmote activeEmote) {
        return hasMoved(player.position(), activeEmote.startPosition());
    }

    private ActiveEmote findActiveNamespaceEmote(String namespace) {
        for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
            if (!activeEmote.namespace().equals(namespace)) {
                continue;
            }

            ServerLevel level = level(activeEmote);
            Entity rootEntity = level == null ? null : level.getEntity(activeEmote.rootEntityUuid());
            if (rootEntity != null && !rootEntity.isRemoved()) {
                return activeEmote;
            }
        }

        return null;
    }

    private boolean hasActiveNamespace(String namespace) {
        return findActiveNamespaceEmote(namespace) != null;
    }

    private void stopActiveEmote(MinecraftServer server, ActiveEmote activeEmote) {
        boolean lastNamespaceInstance = !hasActiveNamespace(activeEmote.namespace());
        if (lastNamespaceInstance) {
            executeFunction(activeEmote, activeEmote.namespace() + ":_/stop_anim");
            executeFunction(activeEmote, activeEmote.namespace() + ":_/delete");
        }

        ServerLevel level = server.getLevel(activeEmote.levelKey());
        if (level != null) {
            cleanupInstanceEntities(level, activeEmote.instanceEntityUuids());
            if (lastNamespaceInstance) {
                cleanupNamespaceEntitiesNearby(level, activeEmote.namespace(), activeEmote.startPosition());
            }
        }

        ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
        if (player != null) {
            if (activeEmote.playerVisibilityManaged()) {
                player.setInvisible(activeEmote.wasInvisible());
            }
            this.stateListener.onEmoteStopped(player, activeEmote);
        }
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

    private Set<UUID> findNamespaceEntityUuids(ServerLevel level, String namespace) {
        Set<UUID> entityUuids = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            if (matchesNamespaceDisplay(entity, namespace)) {
                entityUuids.add(entity.getUUID());
            }
        }
        return entityUuids;
    }

    private UUID findRootEntityUuid(ServerLevel level, String namespace, Set<UUID> instanceEntityUuids) {
        String rootTag = namespace + "_root";
        for (UUID entityUuid : instanceEntityUuids) {
            Entity entity = level.getEntity(entityUuid);
            if (entity instanceof Display.BlockDisplay && entity.entityTags().contains(rootTag)) {
                return entityUuid;
            }
        }
        return null;
    }

    private void copyAnimationState(ActiveEmote namespaceTimeline, Entity targetRoot) {
        ServerLevel timelineLevel = level(namespaceTimeline);
        Entity timelineRoot = timelineLevel == null
                ? null
                : timelineLevel.getEntity(namespaceTimeline.rootEntityUuid());
        if (timelineRoot == null || targetRoot == null) {
            return;
        }

        for (String tag : timelineRoot.entityTags()) {
            if (tag.startsWith("animation_")) {
                targetRoot.addTag(tag);
            }
        }
    }

    private void cleanupInstanceEntities(ServerLevel level, Set<UUID> instanceEntityUuids) {
        Map<Integer, Entity> entitiesToKill = new LinkedHashMap<>();
        for (UUID entityUuid : instanceEntityUuids) {
            Entity entity = level.getEntity(entityUuid);
            if (entity != null) {
                collectEntityTree(entity, entitiesToKill);
            }
        }

        for (Entity entity : entitiesToKill.values()) {
            if (!entity.isRemoved()) {
                entity.kill(level);
            }
        }
    }

    private ServerLevel level(ActiveEmote activeEmote) {
        MinecraftServer server = server();
        return server == null ? null : server.getLevel(activeEmote.levelKey());
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

    private void alignRootWithPlayer(ServerPlayer player, UUID rootEntityUuid) {
        Entity rootEntity = player.level().getEntity(rootEntityUuid);
        if (rootEntity == null) {
            return;
        }

        float yaw = Mth.wrapDegrees(player.getYRot() + 180.0F);
        rootEntity.snapTo(player.position(), yaw, 0.0F);
        rootEntity.teleportTo(player.getX(), player.getY(), player.getZ());
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
            UUID rootEntityUuid,
            Set<UUID> instanceEntityUuids,
            boolean playerVisibilityManaged,
            boolean wasInvisible
    ) {
        private PlaybackStartSnapshot {
            instanceEntityUuids = Set.copyOf(instanceEntityUuids);
        }
    }

    private record PendingSkinApplication(
            EmoteDefinition definition,
            PreparedPlayerSkin preparedPlayerSkin
    ) {
    }
}
