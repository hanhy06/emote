package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.content.DisplayData;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

import java.util.*;
import java.util.stream.Stream;

public final class PlaybackNodes {
    private RootTransform root;
    private final Map<String, NodeInstance> nodes;
    private final int displayEntityCount;

    private float viewYaw;

    public PlaybackNodes(RootTransform root, Map<String, NodeInstance> nodes) {
        this.root = Objects.requireNonNull(root, "root");
        this.nodes = Map.copyOf(nodes);
        this.displayEntityCount = (int) attachments()
            .filter(attachment -> attachment.entity() != null)
            .count();
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

    public Transformation displayTransformation(Matrix4fc matrix) {
        Objects.requireNonNull(matrix, "matrix");
        return root().displayTransformation(matrix);
    }

    public Stream<AttachmentInstance> attachments() {
        return this.nodes.values().stream().flatMap(node -> node.attachments().values().stream());
    }

    public boolean requestVisibility(String nodeId, boolean visible) {
        this.nodes.get(nodeId).visible = visible;
        return visible;
    }

    public boolean requestVisibility(String nodeId, String attachmentId, boolean visible) {
        this.nodes.get(nodeId).attachments().get(attachmentId).visible = visible;
        return effectiveVisibility(nodeId, attachmentId);
    }

    boolean effectiveVisibility(String nodeId, String attachmentId) {
        NodeInstance node = this.nodes.get(nodeId);
        return node.visible && node.attachments().get(attachmentId).visible;
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

    public static final class NodeInstance {
        private final String id;
        private final EmoteAnimation.Node node;
        private final Map<String, AttachmentInstance> attachments;
        private boolean visible;

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
