package io.github.hanhy06.emote.playback.runtime;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.playback.PlaybackPlayer;
import net.minecraft.nbt.CompoundTag;
import org.joml.Matrix4fc;

import java.util.Objects;
import java.util.Set;

public final class EntityTimelineTarget implements PlaybackPlayer.TimelineTarget {
    private final PreparedEmote emote;
    private final PlaybackNodes nodes;
    private final PlaybackEntityController entityController;

    public EntityTimelineTarget(
        PreparedEmote emote,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.entityController = Objects.requireNonNull(entityController, "entityController");
    }

    @Override
    public Transformation createTransformation(String nodeId, Matrix4fc matrix) {
        requiredNode(nodeId);
        return this.nodes.displayTransformation(matrix);
    }

    @Override
    public void applyTransform(
        String nodeId,
        Matrix4fc matrix,
        int interpolationDurationTicks
    ) {
        PlaybackNodes.NodeInstance node = requiredNode(nodeId);
        if (node.isAnchor()) {
            return;
        }
        this.entityController.applyTransformation(
            node,
            this.nodes.displayTransformation(matrix),
            interpolationDurationTicks
        );
    }

    @Override
    public void setVisible(String nodeId, boolean visible) {
        var node = requiredNode(nodeId);
        this.nodes.requestVisibility(nodeId, visible);
        node.attachments().forEach((id, attachment) ->
            this.entityController.setVisible(attachment, this.nodes.effectiveVisibility(nodeId, id)));
    }

    @Override
    public void setVisible(String nodeId, String attachmentId, boolean visible) {
        var attachment = requiredNode(nodeId).attachments().get(attachmentId);
        this.entityController.setVisible(attachment, this.nodes.requestVisibility(nodeId, attachmentId, visible));
    }

    @Override
    public void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove) {
        this.entityController.applyNbt(this.nodes, nodeId, requiredNode(nodeId).attachments().get(attachmentId), nbt, remove);
    }

    @Override
    public void resetNbt(String nodeId, String attachmentId) {
        this.entityController.resetNbt(this.nodes, nodeId, requiredNode(nodeId).attachments().get(attachmentId));
    }

    @Override
    public void resetAll() {
        this.nodes.nodes().forEach((nodeId, node) -> {
            node.attachments().keySet().forEach(attachmentId -> resetNbt(nodeId, attachmentId));
            applyTransform(nodeId, this.emote.defaultMatrix(nodeId), 0);
            node.attachments().forEach((id, attachment) -> setVisible(nodeId, id, attachment.attachment().visible()));
            setVisible(nodeId, node.node().visible());
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
