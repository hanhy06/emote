package io.github.hanhy06.emote.playback.runtime;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;

import java.util.*;

public final class PlaybackNodes {
    private final Map<EmoteAnimation.NodeSpace, RootTransform> spaces;
    private final Map<String, NodeInstance> nodes;
    private final int displayEntityCount;
    private final EnumSet<EmoteAnimation.NodeSpace> activeSpaces = EnumSet.of(
        EmoteAnimation.NodeSpace.SCENE,
        EmoteAnimation.NodeSpace.INITIATOR
    );
    private final Map<String, Boolean> requestedVisibility = new HashMap<>();

    private float viewYaw;

    public PlaybackNodes(Map<EmoteAnimation.NodeSpace, RootTransform> spaces, Map<String, NodeInstance> nodes) {
        EnumMap<EmoteAnimation.NodeSpace, RootTransform> requiredSpaces = new EnumMap<>(EmoteAnimation.NodeSpace.class);
        requiredSpaces.putAll(spaces);
        for (EmoteAnimation.NodeSpace space : EmoteAnimation.NodeSpace.values()) {
            Objects.requireNonNull(requiredSpaces.get(space), "Missing root for node space " + space);
        }
        this.spaces = Map.copyOf(requiredSpaces);
        this.nodes = Map.copyOf(nodes);
        this.displayEntityCount = (int) nodes.values().stream()
            .filter(node -> !(node.node() instanceof EmoteAnimation.AnchorNode))
            .count();
        initializeVisibility();
        this.viewYaw = root().yaw();
    }

    public RootTransform root() {
        return root(EmoteAnimation.NodeSpace.SCENE);
    }

    public RootTransform root(EmoteAnimation.NodeSpace space) {
        return this.spaces.get(Objects.requireNonNull(space, "space"));
    }

    public Map<String, NodeInstance> nodes() {
        return this.nodes;
    }

    public int displayEntityCount() {
        return this.displayEntityCount;
    }

    public Transformation displayTransformation(
        EmoteAnimation.NodeSpace space,
        PreparedAnimation.PreparedTransform transform
    ) {
        Objects.requireNonNull(transform, "transform");
        return root(Objects.requireNonNull(space, "space")).displayTransformation(transform);
    }

    public Transformation displayTransformation(
        EmoteAnimation.NodeSpace space,
        Matrix4fc matrix,
        boolean preserveMatrix
    ) {
        Objects.requireNonNull(matrix, "matrix");
        return root(Objects.requireNonNull(space, "space")).displayTransformation(matrix, preserveMatrix);
    }

    public boolean requestVisibility(String nodeId, boolean visible) {
        NodeInstance node = Objects.requireNonNull(this.nodes.get(nodeId), "Unknown node " + nodeId);
        this.requestedVisibility.put(nodeId, visible);
        return effectiveVisibility(nodeId);
    }

    boolean effectiveVisibility(String nodeId) {
        NodeInstance node = Objects.requireNonNull(this.nodes.get(nodeId), "Unknown node " + nodeId);
        return this.requestedVisibility.getOrDefault(nodeId, false) && this.activeSpaces.contains(node.node().space());
    }

    void activateSpace(EmoteAnimation.NodeSpace space) {
        this.activeSpaces.add(Objects.requireNonNull(space, "space"));
    }

    public float orientationYaw(EmoteAnimation.NodeSpace space) {
        return space == EmoteAnimation.NodeSpace.SCENE ? this.viewYaw : root(space).yaw();
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
            this.displayContent = switch (this.displayContent) {
                case ItemContent ignored -> new ItemContent(Objects.requireNonNull(itemStack, "itemStack"));
                case HeldItemContent held -> new HeldItemContent(Objects.requireNonNull(itemStack, "itemStack"), held.arm());
                default -> throw new IllegalStateException("Node is not an item display: " + this.id);
            };
        }

        public boolean isAnchor() {
            return this.entity == null;
        }
    }

    public sealed interface DisplayContent permits ItemContent, HeldItemContent, BlockContent, TextContent {
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

    public record HeldItemContent(ItemStack itemStack, HumanoidArm arm) implements DisplayContent {
        public HeldItemContent {
            itemStack = Objects.requireNonNull(itemStack, "itemStack").copy();
            Objects.requireNonNull(arm, "arm");
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
