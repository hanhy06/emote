package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.DisplayData;
import io.github.hanhy06.emote.mixin.accessor.BlockDisplayAccessor;
import io.github.hanhy06.emote.mixin.accessor.DisplayAccessor;
import io.github.hanhy06.emote.mixin.accessor.ItemDisplayAccessor;
import io.github.hanhy06.emote.mixin.accessor.TextDisplayAccessor;
import io.github.hanhy06.emote.skin.PlayerHeadProfileFactory;
import io.github.hanhy06.emote.skin.SkinBinding;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.github.hanhy06.emote.playback.runtime.PlaybackNodes.*;
import io.github.hanhy06.emote.content.PreparedAnimation;

public final class PlaybackEntityController {
    public void applySkin(PlaybackNodes nodes, Collection<SkinBinding> bindings, Map<PlayerSkinRegion, String> skin) {
        if (skin == null || bindings.isEmpty()) {
            return;
        }
        for (SkinBinding binding : bindings) {
            NodeInstance node = nodes.nodes().get(binding.nodeId());
            if (node == null) {
                continue;
            }
            AttachmentInstance attachment = node.attachments().get(binding.attachmentId());
            if (attachment == null) continue;
            String textureUrl = skin.get(binding.region());
            if (textureUrl == null
                || !(attachment.entity() instanceof Display.ItemDisplay itemDisplay)
                || !(attachment.displayContent() instanceof DisplayData.Item(ItemStack itemStack, var _))
                || !itemStack.is(Items.PLAYER_HEAD)) {
                continue;
            }
            ItemStack skinnedStack = itemStack.copy();
            skinnedStack.set(DataComponents.PROFILE, PlayerHeadProfileFactory.createProfile(textureUrl));
            attachment.setItemStack(skinnedStack);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, itemDisplay.registryAccess());
            ItemStack initialItem = TagValueInput.create(ProblemReporter.DISCARDING, itemDisplay.registryAccess(), attachment.initialEntityData())
                .read("item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            if (initialItem.is(Items.PLAYER_HEAD)) {
                initialItem.set(DataComponents.PROFILE, PlayerHeadProfileFactory.createProfile(textureUrl));
                output.store("item", ItemStack.CODEC, initialItem);
                attachment.setInitialItem(output.buildResult().get("item"));
            }
            SlotAccess itemSlot = itemDisplay.getSlot(0);
            if (!itemSlot.get().isEmpty()) {
                itemSlot.set(skinnedStack);
            }
        }
    }

    public static final String RUNTIME_TAG = "emote.runtime";
    private static final int RESPONSIVE_INTERPOLATION_TICKS = 1;
    private static final int VIEW_ROTATION_INTERPOLATION_TICKS = 3;

    public PlaybackNodes create(ServerLevel level, RootTransform root, PreparedEmote emote) {
        LinkedHashMap<String, NodeInstance> instances = new LinkedHashMap<>();
        for (String nodeId : emote.nodeOrder()) {
            EmoteAnimation.Node node = emote.nodes().get(nodeId);
            Map<String, DisplayData> preparedData = emote.displayContents().getOrDefault(nodeId, Map.of());
            LinkedHashMap<String, AttachmentInstance> attachments = new LinkedHashMap<>();
            node.attachments().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                attachments.put(entry.getKey(), createAttachment(level, root, nodeId, entry.getKey(),
                    entry.getValue(), preparedData.get(entry.getKey()), emote.rotationDeadzone())));
            instances.put(nodeId, new NodeInstance(nodeId, node, attachments));
        }
        return new PlaybackNodes(root, instances);
    }

    public PlaybackNodes create(ServerLevel level, Vec3 position, float yaw, PreparedAnimation emote) {
        return create(level, RootTransform.create(position, yaw), emote);
    }

    public void add(ServerLevel level, PlaybackNodes nodes) {
        try {
            for (AttachmentInstance node : nodes.attachments().toList()) {
                if (!node.isAnchor() && !level.addFreshEntity(node.entity())) {
                    throw new IllegalStateException("Failed to add display entity for attachment " + node.id());
                }
            }
        } catch (RuntimeException exception) {
            removeEntities(level, nodes.nodes().values());
            throw exception;
        }
    }

    public void remove(ServerLevel level, PlaybackNodes nodes) {
        removeEntities(level, nodes.nodes().values());
    }

    public boolean updateViewRotation(PlaybackNodes nodes, float currentYaw, float rotationDeadzone) {
        float previousRelativeYaw = nodes.root().relativeYaw(nodes.viewYaw());
        float viewYaw = nodes.updateViewYaw(currentYaw, rotationDeadzone);
        float relativeYaw = nodes.root().relativeYaw(viewYaw);
        boolean rotationChanged = Mth.packDegrees(previousRelativeYaw) != Mth.packDegrees(relativeYaw);
        int interpolationTicks = positionRotationInterpolationTicks(rotationDeadzone);
        for (AttachmentInstance node : nodes.attachments().toList()) {
            if (!node.isAnchor()) {
                ((DisplayAccessor) node.entity()).emote$setPosRotInterpolationDuration(interpolationTicks);
                if (rotationChanged) {
                    node.entity().setYRot(relativeYaw);
                }
            }
        }
        return rotationChanged;
    }

    public boolean moveSceneTo(PlaybackNodes nodes, Vec3 position) {
        if (!nodes.moveSceneTo(position)) {
            return false;
        }
        for (AttachmentInstance node : nodes.attachments().toList()) {
            if (!node.isAnchor()) {
                node.entity().setPos(nodes.root().position());
            }
        }
        return true;
    }

    public void setVisible(AttachmentInstance node, boolean visible) {
        if (node.isAnchor()) {
            return;
        }
        switch (node.displayContent()) {
            case DisplayData.Item(ItemStack itemStack, var _) ->
                ((ItemDisplayAccessor) node.entity()).emote$setItemStack(visible ? itemStack : ItemStack.EMPTY);
            case DisplayData.Block(var blockState) -> ((BlockDisplayAccessor) node.entity()).emote$setBlockState(
                visible ? blockState : Blocks.AIR.defaultBlockState()
            );
            case DisplayData.Text(Component text) ->
                ((TextDisplayAccessor) node.entity()).emote$setText(visible ? text : Component.empty());
            case null -> {
            }
        }
    }

    public void applyNbt(PlaybackNodes nodes, String nodeId, AttachmentInstance attachment, CompoundTag nbt, Set<String> remove) {
        if (attachment.isAnchor()) return;
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, attachment.entity().registryAccess());
        attachment.entity().saveWithoutId(output);
        CompoundTag current = output.buildResult();
        Set<String> changed = attachment.replaceNbtState(current, nbt, remove);
        loadNbt(nodes, nodeId, attachment, current, changed);
    }

    public void resetNbt(PlaybackNodes nodes, String nodeId, AttachmentInstance attachment) {
        if (attachment.isAnchor() || !attachment.hasModifiedNbt()) return;
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, attachment.entity().registryAccess());
        attachment.entity().saveWithoutId(output);
        CompoundTag current = output.buildResult();
        Set<String> restored = attachment.restoreNbtFields(current);
        loadNbt(nodes, nodeId, attachment, current, restored);
    }

    private void loadNbt(PlaybackNodes nodes, String nodeId, AttachmentInstance attachment, CompoundTag current, Set<String> changed) {
        attachment.entity().load(TagValueInput.create(ProblemReporter.DISCARDING, attachment.entity().registryAccess(), current));
        if (attachment.entity() instanceof Display.ItemDisplay && (changed.contains("item") || changed.contains("item_display"))) {
            ItemDisplayAccessor accessor = (ItemDisplayAccessor) attachment.entity();
            ItemStack item = changed.contains("item") ? accessor.emote$getItemStack() : ((DisplayData.Item) attachment.displayContent()).itemStack();
            attachment.setDisplayContent(new DisplayData.Item(item, accessor.emote$getItemTransform()));
        } else if (attachment.entity() instanceof Display.BlockDisplay && changed.contains("block_state")) {
            attachment.setDisplayContent(new DisplayData.Block(((BlockDisplayAccessor) attachment.entity()).emote$getBlockState()));
        } else if (attachment.entity() instanceof Display.TextDisplay && changed.contains("text")) {
            TextDisplayAccessor accessor = (TextDisplayAccessor) attachment.entity();
            Component text = resolveText((Display.TextDisplay) attachment.entity(), accessor.emote$getText());
            accessor.emote$setText(text);
            attachment.setDisplayContent(new DisplayData.Text(text));
        }
        setVisible(attachment, nodes.effectiveVisibility(nodeId, attachment.id()));
    }

    public void applyTransformation(
        NodeInstance node,
        Transformation transformation,
        int interpolationDurationTicks
    ) {
        if (node.isAnchor()) {
            return;
        }
        for (AttachmentInstance attachment : node.attachments().values()) {
            if (attachment.entity() != null) applyTransformation(attachment.entity(), transformation, interpolationDurationTicks);
        }
    }

    private AttachmentInstance createAttachment(
        ServerLevel level,
        RootTransform root,
        String nodeId,
        String attachmentId,
        EmoteAnimation.Attachment attachment,
        DisplayData preparedData,
        float rotationDeadzone
    ) {
        if (attachment instanceof EmoteAnimation.ExternalAttachment) {
            return new AttachmentInstance(attachmentId, attachment, null, null);
        }

        Display entity = createDisplay(level, attachment);
        TypedEntityData.of(entity.getType(), attachment.entityNbt()).loadInto(entity);
        ((DisplayAccessor) entity).emote$setPosRotInterpolationDuration(positionRotationInterpolationTicks(rotationDeadzone));
        entity.setPos(root.position());
        entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
        entity.setYRot(0.0F);
        entity.setXRot(0.0F);
        entity.addTag(RUNTIME_TAG);

        DisplayData content = applyRuntimeData(entity, requirePreparedData(nodeId + "/" + attachmentId, preparedData));
        AttachmentInstance instance = new AttachmentInstance(attachmentId, attachment, entity, content);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        instance.setInitialEntityData(output.buildResult());
        return instance;
    }

    private Display createDisplay(ServerLevel level, EmoteAnimation.Attachment attachment) {
        Display display = switch (attachment) {
            case EmoteAnimation.ItemAttachment ignored -> EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.SkinAttachment ignored -> EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.BlockAttachment ignored -> EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.TextAttachment ignored -> EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            default -> throw new IllegalArgumentException("Attachment does not provide a display entity");
        };
        if (display == null) {
            throw new IllegalStateException("Failed to create display entity");
        }
        return display;
    }

    static int positionRotationInterpolationTicks(float rotationDeadzone) {
        return rotationDeadzone == 0.0F ? RESPONSIVE_INTERPOLATION_TICKS : VIEW_ROTATION_INTERPOLATION_TICKS;
    }

    private DisplayData applyRuntimeData(
        Display entity,
        DisplayData preparedData
    ) {
        return switch (preparedData) {
            case DisplayData.Item(ItemStack itemStack, var itemDisplay) -> {
                ItemDisplayAccessor accessor = (ItemDisplayAccessor) entity;
                ItemStack instanceStack = itemStack.copy();
                accessor.emote$setItemStack(instanceStack);
                accessor.emote$setItemTransform(itemDisplay);
                yield new DisplayData.Item(instanceStack, itemDisplay);
            }
            case DisplayData.Block(var blockState) -> {
                ((BlockDisplayAccessor) entity).emote$setBlockState(blockState);
                yield preparedData;
            }
            case DisplayData.Text(Component unresolvedText) -> {
                Component text = resolveText((Display.TextDisplay) entity, unresolvedText);
                ((TextDisplayAccessor) entity).emote$setText(text);
                yield new DisplayData.Text(text);
            }
        };
    }

    private void applyTransformation(Display entity, Transformation transformation, int interpolationDurationTicks) {
        DisplayAccessor accessor = (DisplayAccessor) entity;
        accessor.emote$setTransformation(transformation);
        accessor.emote$setTransformationInterpolationDuration(interpolationDurationTicks);
        accessor.emote$setTransformationInterpolationDelay(0);
    }

    private DisplayData requirePreparedData(
        String nodeId,
        DisplayData preparedData
    ) {
        if (preparedData == null) {
            throw new IllegalStateException("Display node was not prepared during reload: " + nodeId);
        }
        return preparedData;
    }

    private Component resolveText(Display.TextDisplay entity, Component text) {
        try {
            var source = entity.createCommandSourceStackForNameResolution((ServerLevel) entity.level())
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            return ComponentUtils.resolve(ResolutionContext.create(source), text);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Failed to resolve display entity text", exception);
        }
    }

    private void removeEntities(ServerLevel level, Collection<NodeInstance> nodes) {
        for (AttachmentInstance node : nodes.stream().flatMap(n -> n.attachments().values().stream()).toList()) {
            if (node.entity() != null && !node.entity().isRemoved()) {
                node.entity().kill(level);
            }
        }
    }
}
