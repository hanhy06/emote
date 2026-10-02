package io.github.hanhy06.emote.playback.runtime;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

import java.util.*;

public final class PlaybackNodes {
    private RootTransform root;
    private final Map<String, NodeInstance> nodes;
    private final int displayEntityCount;
    private final Map<String, Boolean> requestedVisibility = new HashMap<>();

    private float viewYaw;

    public PlaybackNodes(RootTransform root, Map<String, NodeInstance> nodes) {
        this.root = Objects.requireNonNull(root, "root");
        this.nodes = Map.copyOf(nodes);
        this.displayEntityCount = (int) nodes.values().stream()
            .filter(node -> !(node.node() instanceof EmoteAnimation.AnchorNode))
            .count();
        initializeVisibility();
        this.viewYaw = root().yaw();
    }

    public RootTransform root() {
        return this.root;
    }

    public boolean moveSceneTo(Vec3 position) {
        Objects.requireNonNull(position, "position");
        if (position.equals(this.root.position())) return false;
        this.root = RootTransform.create(position, this.root.yaw());
        return true;
    }

    public Map<String, NodeInstance> nodes() {
        return this.nodes;
    }

    public int displayEntityCount() {
        return this.displayEntityCount;
    }

    public Transformation displayTransformation(
        PreparedAnimation.PreparedTransform transform
    ) {
        Objects.requireNonNull(transform, "transform");
        return root().displayTransformation(transform);
    }

    public Transformation displayTransformation(
        Matrix4fc matrix,
        boolean preserveMatrix
    ) {
        Objects.requireNonNull(matrix, "matrix");
        return root().displayTransformation(matrix, preserveMatrix);
    }

    public boolean requestVisibility(String nodeId, boolean visible) {
        NodeInstance node = Objects.requireNonNull(this.nodes.get(nodeId), "Unknown node " + nodeId);
        this.requestedVisibility.put(nodeId, visible);
        return effectiveVisibility(nodeId);
    }

    boolean effectiveVisibility(String nodeId) {
        NodeInstance node = Objects.requireNonNull(this.nodes.get(nodeId), "Unknown node " + nodeId);
        return this.requestedVisibility.getOrDefault(nodeId, false);
    }

    public float orientationYaw() {
        return Mth.wrapDegrees(root().yaw() + root().relativeYaw(this.viewYaw));
    }

    public float viewYaw() {
        return this.viewYaw;
    }

    public float updateViewYaw(float playerYaw, float maxDifference) {
        float difference = Mth.wrapDegrees(playerYaw - this.viewYaw);
        if (Math.abs(difference) > maxDifference) {
            this.viewYaw = Mth.wrapDegrees(
                this.viewYaw + difference - Math.copySign(maxDifference, difference)
            );
        }
        return this.viewYaw;
    }

    private void initializeVisibility() {
        this.nodes.forEach((nodeId, node) -> this.requestedVisibility.put(nodeId, node.node().visible()));
    }

    public static final class NodeInstance {
        private final String id;
        private final EmoteAnimation.Node node;
        private final Display entity;

        private DisplayContent displayContent;
        private CompoundTag initialEntityData = new CompoundTag();
        private final Set<String> modifiedNbtFields = new HashSet<>();

        public NodeInstance(
            String id,
            EmoteAnimation.Node node,
            Display entity,
            DisplayContent displayContent
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.node = Objects.requireNonNull(node, "node");
            this.entity = entity;
            this.displayContent = displayContent;
        }

        public String id() {
            return this.id;
        }

        public EmoteAnimation.Node node() {
            return this.node;
        }

        public Display entity() {
            return this.entity;
        }

        public DisplayContent displayContent() {
            return this.displayContent;
        }

        public void setItemStack(ItemStack itemStack) {
            if (!(this.displayContent instanceof ItemContent)) {
                throw new IllegalStateException("Node is not an item display: " + this.id);
            }
            this.displayContent = new ItemContent(Objects.requireNonNull(itemStack, "itemStack"));
        }

        void setDisplayContent(DisplayContent displayContent) {
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

        void recordNbtFields(CompoundTag patch) {
            this.modifiedNbtFields.addAll(patch.keySet());
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

    public sealed interface DisplayContent permits ItemContent, BlockContent, TextContent {
    }

    public record ItemContent(ItemStack itemStack) implements DisplayContent {
        public ItemContent {
            itemStack = itemStack.copy();
        }

        @Override
        public ItemStack itemStack() {
            return this.itemStack.copy();
        }
    }

    public record BlockContent(BlockState blockState) implements DisplayContent {
        public BlockContent {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    public record TextContent(Component text) implements DisplayContent {
        public TextContent {
            Objects.requireNonNull(text, "text");
        }
    }
}
