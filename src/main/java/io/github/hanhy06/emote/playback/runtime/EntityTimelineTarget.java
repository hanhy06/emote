package io.github.hanhy06.emote.playback.runtime;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import net.minecraft.nbt.CompoundTag;
import org.joml.Matrix4fc;

import java.util.Objects;

public final class EntityTimelineTarget implements AnimationPlayer.TimelineTarget {
    private final PreparedAnimation emote;
    private final PlaybackNodes nodes;
    private final PlaybackEntityController entityController;

    public EntityTimelineTarget(
        PreparedAnimation emote,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.entityController = Objects.requireNonNull(entityController, "entityController");
    }

    @Override
    public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
        PlaybackNodes.NodeInstance node = requiredNode(nodeId);
        return this.nodes.displayTransformation(node.node().space(), transform);
    }

    @Override
    public Transformation createTransformation(String nodeId, Matrix4fc matrix, boolean preserveMatrix) {
        PlaybackNodes.NodeInstance node = requiredNode(nodeId);
        return this.nodes.displayTransformation(node.node().space(), matrix, preserveMatrix);
    }

    @Override
    public void applyTransform(
        String nodeId,
        PreparedAnimation.PreparedTransform transform,
        int interpolationDurationTicks
    ) {
        this.entityController.applyTransformation(
            this.nodes,
            requiredNode(nodeId),
            transform,
            interpolationDurationTicks
        );
    }

    @Override
    public void applyTransform(
        String nodeId,
        Matrix4fc matrix,
        boolean preserveMatrix,
        int interpolationDurationTicks
    ) {
        PlaybackNodes.NodeInstance node = requiredNode(nodeId);
        this.entityController.applyTransformation(
            node,
            this.nodes.displayTransformation(node.node().space(), matrix, preserveMatrix),
            interpolationDurationTicks
        );
    }

    @Override
    public void setVisible(String nodeId, boolean visible) {
        this.entityController.setVisible(requiredNode(nodeId), this.nodes.requestVisibility(nodeId, visible));
    }

    @Override
    public void applyNbt(String nodeId, CompoundTag nbt) {
        this.entityController.applyNbt(this.nodes, requiredNode(nodeId), nbt);
    }

    @Override
    public void resetAll() {
        this.nodes.nodes().forEach((nodeId, node) -> {
            this.entityController.applyTransformation(
                this.nodes,
                node,
                this.emote.defaultTransform(nodeId),
                0
            );
            this.entityController.setVisible(node, this.nodes.requestVisibility(nodeId, node.node().visible()));
        });
    }

    private PlaybackNodes.NodeInstance requiredNode(String nodeId) {
        PlaybackNodes.NodeInstance node = this.nodes.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Missing playback node: " + nodeId);
        }
        return node;
    }
}
