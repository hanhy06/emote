package io.github.hanhy06.emote.api.animation;

import com.google.gson.JsonElement;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EmoteAnimation(
    Identifier id,
    EmoteMetadata metadata,
    Settings settings,
    Map<String, Node> nodes,
    Timeline timeline
) {
    public EmoteAnimation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(settings, "settings");
        nodes = Map.copyOf(nodes);
        Objects.requireNonNull(timeline, "timeline");
    }

    public record Settings(
        boolean standalone,
        int cooldownTicks,
        EmotePlayerBehavior player,
        PlaybackSettings playback
    ) {
        public Settings {
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("cooldown must not be negative");
            }
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(playback, "playback");
        }
    }

    public record PlaybackSettings(LoopMode mode, int loopDelayTicks) {
        public PlaybackSettings {
            Objects.requireNonNull(mode, "mode");
            if (loopDelayTicks < 0) {
                throw new IllegalArgumentException("loop delay must not be negative");
            }
            if (mode == LoopMode.ONCE && loopDelayTicks != 0) {
                throw new IllegalArgumentException("loop delay must be zero when playback mode is once");
            }
        }
    }

    public record Matrix(List<Double> values) {
        public Matrix {
            values = List.copyOf(values);
            if (values.size() != 16) {
                throw new IllegalArgumentException("matrix must contain 16 values");
            }
        }

        public double value(int index) {
            return this.values.get(index);
        }
    }

    public sealed interface Node permits ItemNode, BlockNode, TextNode, AnchorNode {
        Matrix defaultMatrix();

        default boolean visible() {
            return true;
        }

        default CompoundTag entityNbt() {
            return new CompoundTag();
        }
    }

    public record ItemNode(
        boolean visible,
        Matrix defaultMatrix,
        CompoundTag entityNbt,
        CompoundTag itemStackNbt,
        String itemDisplay,
        Skin skin
    ) implements Node {
        public ItemNode {
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
            entityNbt = copy(entityNbt);
            itemStackNbt = copy(itemStackNbt);
            Objects.requireNonNull(itemDisplay, "itemDisplay");
        }
    }

    public record BlockNode(
        boolean visible,
        Matrix defaultMatrix,
        CompoundTag entityNbt,
        CompoundTag blockStateNbt
    ) implements Node {
        public BlockNode {
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
            entityNbt = copy(entityNbt);
            blockStateNbt = copy(blockStateNbt);
        }
    }

    public record TextNode(
        boolean visible,
        Matrix defaultMatrix,
        CompoundTag entityNbt,
        JsonElement text
    ) implements Node {
        public TextNode {
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
            entityNbt = copy(entityNbt);
            text = Objects.requireNonNull(text, "text").deepCopy();
        }

        @Override
        public JsonElement text() {
            return this.text.deepCopy();
        }
    }

    public record AnchorNode(Matrix defaultMatrix) implements Node {
        public AnchorNode {
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
        }
    }

    public record Skin(SkinPart part, int order) {
        public Skin {
            Objects.requireNonNull(part, "part");
            if (order < 0) {
                throw new IllegalArgumentException("skin order must not be negative");
            }
        }
    }

    public enum SkinPart {
        HEAD,
        BODY,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG
    }

    public record Timeline(
        int durationTicks,
        List<Keyframe> keyframes,
        Events events
    ) {
        public Timeline {
            keyframes = List.copyOf(keyframes);
            Objects.requireNonNull(events, "events");
        }
    }

    public enum LoopMode {
        ONCE,
        LOOP,
        SERVER_SYNC
    }

    public record Keyframe(
        int tick,
        Map<String, NodeTransform> nodeTransforms,
        Map<String, NodeState> nodeStates
    ) {
        public Keyframe {
            nodeTransforms = Map.copyOf(nodeTransforms);
            nodeStates = Map.copyOf(nodeStates);
        }
    }

    public record NodeTransform(Matrix matrix, int interpolationDurationTicks) {
        public NodeTransform {
            Objects.requireNonNull(matrix, "matrix");
        }
    }

    public record NodeState(boolean visible) {
    }

    public record Events(
        List<Event> start,
        List<TimelineEvent> timeline,
        List<Event> loop,
        List<Event> stop
    ) {
        public Events {
            start = List.copyOf(start);
            timeline = List.copyOf(timeline);
            loop = List.copyOf(loop);
            stop = List.copyOf(stop);
        }

        public static Events empty() {
            return new Events(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record Event(CommandSource source, CommandOrigin origin, List<String> commands) {
        public Event {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(origin, "origin");
            commands = List.copyOf(commands);
        }
    }

    public record TimelineEvent(int tick, CommandSource source, CommandOrigin origin, List<String> commands) {
        public TimelineEvent {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(origin, "origin");
            commands = List.copyOf(commands);
        }

        public Event event() {
            return new Event(this.source, this.origin, this.commands);
        }
    }

    public record CommandSource(SourceType type, String node) {
        public CommandSource {
            Objects.requireNonNull(type, "type");
        }
    }

    public enum SourceType {
        PLAYER,
        SERVER,
        NODE
    }

    public record CommandOrigin(OriginType type, String node, Vec3 offset) {
        public CommandOrigin {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(offset, "offset");
        }
    }

    public enum OriginType {
        ROOT,
        NODE
    }

    public record Vec3(double x, double y, double z) {
        public static final Vec3 ZERO = new Vec3(0.0D, 0.0D, 0.0D);
    }

    public sealed interface PreparedDisplayData permits PreparedItemData, PreparedBlockData, PreparedTextData {
    }

    public record PreparedItemData(ItemStack itemStack, ItemDisplayContext itemDisplay) implements PreparedDisplayData {
        public PreparedItemData {
            itemStack = Objects.requireNonNull(itemStack, "itemStack").copy();
            Objects.requireNonNull(itemDisplay, "itemDisplay");
        }

        @Override
        public ItemStack itemStack() {
            return this.itemStack.copy();
        }
    }

    public record PreparedBlockData(BlockState blockState) implements PreparedDisplayData {
        public PreparedBlockData {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    public record PreparedTextData(Component text) implements PreparedDisplayData {
        public PreparedTextData {
            text = Objects.requireNonNull(text, "text").copy();
        }

        @Override
        public Component text() {
            return this.text.copy();
        }
    }

    public record Loaded(
        Path sourcePath,
        String sha256,
        EmoteAnimation animation,
        Map<String, PreparedDisplayData> preparedDisplayData
    ) {
        public Loaded(Path sourcePath, String sha256, EmoteAnimation animation) {
            this(sourcePath, sha256, animation, Map.of());
        }

        public Loaded {
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(animation, "animation");
            preparedDisplayData = Map.copyOf(preparedDisplayData);
        }
    }

    private static CompoundTag copy(CompoundTag tag) {
        return Objects.requireNonNull(tag, "tag").copy();
    }
}
