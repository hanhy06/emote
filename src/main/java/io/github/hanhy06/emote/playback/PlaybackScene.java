package io.github.hanhy06.emote.playback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.DisplayData;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.mixin.accessor.BlockDisplayAccessor;
import io.github.hanhy06.emote.mixin.accessor.DisplayAccessor;
import io.github.hanhy06.emote.mixin.accessor.ItemDisplayAccessor;
import io.github.hanhy06.emote.mixin.accessor.TextDisplayAccessor;
import io.github.hanhy06.emote.skin.PlayerHeadProfileFactory;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
import org.joml.Matrix4fc;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PlaybackScene implements EmotePlayback.PoseTarget {
    private final ServerLevel level;
    private final PreparedEmote emote;
    private RootTransform root;
    private final Map<String, NodeInstance> nodes;
    private final List<AttachmentInstance> attachments;
    private final int displayEntityCount;
    private float viewYaw;

    private PlaybackScene(ServerLevel level, RootTransform root, PreparedEmote emote, Map<String, NodeInstance> nodes) {
        this.level = Objects.requireNonNull(level, "level");
        this.root = Objects.requireNonNull(root, "root");
        this.emote = Objects.requireNonNull(emote, "emote");
        this.nodes = Map.copyOf(nodes);
        this.attachments = this.nodes.values().stream().flatMap(node -> node.attachments().values().stream()).toList();
        this.displayEntityCount = (int) this.attachments.stream().filter(attachment -> attachment.entity() != null).count();
        this.viewYaw = root.yaw();
    }

    public RootTransform root() { return this.root; }
    public Map<String, NodeInstance> nodes() { return this.nodes; }
    public int displayEntityCount() { return this.displayEntityCount; }
    public List<AttachmentInstance> attachments() { return this.attachments; }
    public float viewYaw() { return this.viewYaw; }

    public float orientationYaw() {
        return Mth.wrapDegrees(this.root.yaw() + this.root.relativeYaw(this.viewYaw));
    }

    private float updateViewYaw(float playerYaw, float maxDifference) {
        float difference = Mth.wrapDegrees(playerYaw - this.viewYaw);
        if (Math.abs(difference) > maxDifference) {
            this.viewYaw = Mth.wrapDegrees(this.viewYaw + difference - Math.copySign(maxDifference, difference));
        }
        return this.viewYaw;
    }

    @Override
    public Matrix4fc rootMatrix() {
        return this.root.rotationMatrix();
    }

    @Override
    public Transformation createTransformation(String nodeId, Matrix4fc matrix) {
        requiredNode(nodeId);
        return this.root.displayTransformation(matrix);
    }

    @Override
    public void setVisible(String nodeId, boolean visible) {
        NodeInstance node = requiredNode(nodeId);
        node.visible = visible;
        node.attachments().values().forEach(attachment -> setVisible(node, attachment, node.visible && attachment.visible, false));
    }

    @Override
    public void setVisible(String nodeId, String attachmentId, boolean visible) {
        NodeInstance node = requiredNode(nodeId);
        AttachmentInstance attachment = node.attachments().get(attachmentId);
        attachment.visible = visible;
        setVisible(node, attachment, node.visible && attachment.visible, false);
    }

    @Override
    public void resetAll() {
        this.nodes.forEach((nodeId, node) -> {
            node.attachments().keySet().forEach(attachmentId -> resetNbt(nodeId, attachmentId));
            applyTransform(nodeId, createTransformation(nodeId, this.emote.defaultMatrix(nodeId)), 0);
            node.attachments().forEach((id, attachment) -> setVisible(nodeId, id, attachment.attachment().visible()));
            setVisible(nodeId, node.node().visible());
        });
    }

    private NodeInstance requiredNode(String nodeId) {
        NodeInstance node = this.nodes.get(nodeId);
        if (node == null) throw new IllegalStateException("Missing playback node: " + nodeId);
        return node;
    }

    public void applySkin(Collection<SkinBinding> bindings, Map<PlayerSkinRegion, String> skin) {
        if (skin == null || bindings.isEmpty()) {
            return;
        }
        for (SkinBinding binding : bindings) {
            NodeInstance node = this.nodes.get(binding.nodeId());
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

    public static PlaybackScene create(ServerLevel level, RootTransform root, PreparedEmote emote) {
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
        return new PlaybackScene(level, root, emote, instances);
    }

    public void add() {
        try {
            for (AttachmentInstance node : this.attachments) {
                if (!node.isAnchor() && !this.level.addFreshEntity(node.entity())) {
                    throw new IllegalStateException("Failed to add display entity for attachment " + node.id());
                }
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public void close() {
        for (AttachmentInstance attachment : this.attachments) {
            if (attachment.entity() != null && !attachment.entity().isRemoved()) attachment.entity().kill(this.level);
        }
    }

    public boolean updateViewRotation(float currentYaw, float rotationDeadzone) {
        float previousRelativeYaw = root().relativeYaw(viewYaw());
        float viewYaw = updateViewYaw(currentYaw, rotationDeadzone);
        float relativeYaw = root().relativeYaw(viewYaw);
        boolean rotationChanged = Mth.packDegrees(previousRelativeYaw) != Mth.packDegrees(relativeYaw);
        int interpolationTicks = positionRotationInterpolationTicks(rotationDeadzone);
        for (AttachmentInstance node : this.attachments) {
            if (!node.isAnchor()) {
                ((DisplayAccessor) node.entity()).emote$setPosRotInterpolationDuration(interpolationTicks);
                if (rotationChanged) {
                    node.entity().setYRot(relativeYaw);
                }
            }
        }
        return rotationChanged;
    }

    public boolean moveTo(Vec3 position) {
        Objects.requireNonNull(position, "position");
        if (position.equals(this.root.position())) return false;
        this.root = RootTransform.create(position, this.root.yaw());
        for (AttachmentInstance node : this.attachments) {
            if (!node.isAnchor()) {
                node.entity().setPos(root().position());
            }
        }
        return true;
    }

    private void setVisible(NodeInstance node, AttachmentInstance attachment, boolean visible, boolean loadedNbt) {
        if (attachment.isAnchor() || attachment.displayed == visible && !loadedNbt) return;
        if (visible && node.latestTransformation != null) {
            applyTransformation(attachment, node.latestTransformation, attachment.displayed ? attachment.appliedInterpolationDuration : 0, true);
        }
        switch (attachment.displayContent()) {
            case DisplayData.Item(ItemStack itemStack, var _) ->
                ((ItemDisplayAccessor) attachment.entity()).emote$setItemStack(visible ? itemStack : ItemStack.EMPTY);
            case DisplayData.Block(var blockState) -> ((BlockDisplayAccessor) attachment.entity()).emote$setBlockState(
                visible ? blockState : Blocks.AIR.defaultBlockState()
            );
            case DisplayData.Text(Component text) ->
                ((TextDisplayAccessor) attachment.entity()).emote$setText(visible ? text : Component.empty());
            case null -> {
            }
        }
        attachment.displayed = visible;
    }

    @Override
    public void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove) {
        NodeInstance node = requiredNode(nodeId);
        AttachmentInstance attachment = node.attachments().get(attachmentId);
        if (attachment.isAnchor()) return;
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, attachment.entity().registryAccess());
        attachment.entity().saveWithoutId(output);
        CompoundTag current = output.buildResult();
        Set<String> changed = attachment.replaceNbtState(current, nbt, remove);
        loadNbt(node, attachment, current, changed);
    }

    @Override
    public void resetNbt(String nodeId, String attachmentId) {
        NodeInstance node = requiredNode(nodeId);
        AttachmentInstance attachment = node.attachments().get(attachmentId);
        if (attachment.isAnchor() || !attachment.hasModifiedNbt()) return;
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, attachment.entity().registryAccess());
        attachment.entity().saveWithoutId(output);
        CompoundTag current = output.buildResult();
        Set<String> restored = attachment.restoreNbtFields(current);
        loadNbt(node, attachment, current, restored);
    }

    private void loadNbt(NodeInstance node, AttachmentInstance attachment, CompoundTag current, Set<String> changed) {
        attachment.entity().load(TagValueInput.create(ProblemReporter.DISCARDING, attachment.entity().registryAccess(), current));
        attachment.appliedTransformation = null;
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
        setVisible(node, attachment, node.visible && attachment.visible, true);
    }

    @Override
    public void applyTransform(String nodeId, Transformation transformation, int interpolationDurationTicks) {
        NodeInstance node = requiredNode(nodeId);
        node.latestTransformation = transformation;
        if (node.isAnchor()) {
            return;
        }
        for (AttachmentInstance attachment : node.attachments().values()) {
            if (attachment.entity() != null && attachment.displayed && node.visible && attachment.visible) {
                applyTransformation(attachment, transformation, interpolationDurationTicks, false);
            }
        }
    }

    private static AttachmentInstance createAttachment(
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

    private static Display createDisplay(ServerLevel level, EmoteAnimation.Attachment attachment) {
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

    private static DisplayData applyRuntimeData(
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

    private void applyTransformation(AttachmentInstance attachment, Transformation transformation, int interpolationDurationTicks, boolean force) {
        if (!force && attachment.appliedTransformation != null && transformation.getMatrix().equals(attachment.appliedTransformation.getMatrix())
            && interpolationDurationTicks == attachment.appliedInterpolationDuration) return;
        DisplayAccessor accessor = (DisplayAccessor) attachment.entity();
        accessor.emote$setTransformation(transformation);
        accessor.emote$setTransformationInterpolationDuration(interpolationDurationTicks);
        accessor.emote$setTransformationInterpolationDelay(0);
        attachment.appliedTransformation = transformation;
        attachment.appliedInterpolationDuration = interpolationDurationTicks;
    }

    private static DisplayData requirePreparedData(
        String nodeId,
        DisplayData preparedData
    ) {
        if (preparedData == null) {
            throw new IllegalStateException("Display node was not prepared during reload: " + nodeId);
        }
        return preparedData;
    }

    private static Component resolveText(Display.TextDisplay entity, Component text) {
        try {
            var source = entity.createCommandSourceStackForNameResolution((ServerLevel) entity.level())
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            return ComponentUtils.resolve(ResolutionContext.create(source), text);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Failed to resolve display entity text", exception);
        }
    }

    public static final class NodeInstance {
        private final String id;
        private final EmoteAnimation.Node node;
        private final Map<String, AttachmentInstance> attachments;
        private boolean visible;
        private Transformation latestTransformation;

        public NodeInstance(String id, EmoteAnimation.Node node, Map<String, AttachmentInstance> attachments) {
            this.id = Objects.requireNonNull(id, "id");
            this.node = Objects.requireNonNull(node, "node");
            this.attachments = Map.copyOf(attachments);
            this.visible = node.visible();
        }

        public String id() { return this.id; }
        public EmoteAnimation.Node node() { return this.node; }
        public Map<String, AttachmentInstance> attachments() { return this.attachments; }
        public boolean isAnchor() { return this.attachments.values().stream().noneMatch(a -> a.entity() != null); }
    }

    public static final class AttachmentInstance {
        private final String id;
        private final EmoteAnimation.Attachment attachment;
        private final Display entity;
        private boolean visible;
        private boolean displayed = true;
        private Transformation appliedTransformation;
        private int appliedInterpolationDuration;
        private DisplayData displayContent;
        private CompoundTag initialEntityData = new CompoundTag();
        private final Set<String> modifiedNbtFields = new HashSet<>();

        public AttachmentInstance(String id, EmoteAnimation.Attachment attachment, Display entity, DisplayData displayContent) {
            this.id = Objects.requireNonNull(id, "id");
            this.attachment = Objects.requireNonNull(attachment, "attachment");
            this.entity = entity;
            this.displayContent = displayContent;
            this.visible = attachment.visible();
        }

        public String id() { return this.id; }
        public EmoteAnimation.Attachment attachment() { return this.attachment; }

        public Display entity() {
            return this.entity;
        }

        public DisplayData displayContent() {
            return this.displayContent;
        }

        public void setItemStack(ItemStack itemStack) {
            if (!(this.displayContent instanceof DisplayData.Item item)) {
                throw new IllegalStateException("Attachment is not an item display: " + this.id);
            }
            this.displayContent = new DisplayData.Item(Objects.requireNonNull(itemStack, "itemStack"), item.itemDisplay());
        }

        void setDisplayContent(DisplayData displayContent) {
            this.displayContent = Objects.requireNonNull(displayContent, "displayContent");
        }

        void setInitialEntityData(CompoundTag data) {
            this.initialEntityData = data.copy();
        }

        CompoundTag initialEntityData() {
            return this.initialEntityData.copy();
        }

        void setInitialItem(Tag item) {
            this.initialEntityData.put("item", item.copy());
        }

        Set<String> replaceNbtState(CompoundTag current, CompoundTag merge, Set<String> remove) {
            Set<String> changed = new HashSet<>(restoreNbtFields(current));
            changed.addAll(merge.keySet());
            changed.addAll(remove);
            remove.forEach(current::remove);
            current.merge(merge);
            this.modifiedNbtFields.addAll(merge.keySet());
            this.modifiedNbtFields.addAll(remove);
            return changed;
        }

        Set<String> restoreNbtFields(CompoundTag current) {
            Set<String> restored = Set.copyOf(this.modifiedNbtFields);
            for (String field : restored) {
                Tag initial = this.initialEntityData.get(field);
                if (initial == null) current.remove(field);
                else current.put(field, initial.copy());
            }
            this.modifiedNbtFields.clear();
            return restored;
        }

        boolean hasModifiedNbt() {
            return !this.modifiedNbtFields.isEmpty();
        }

        public boolean isAnchor() {
            return this.entity == null;
        }
    }
}
