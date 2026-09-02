package io.github.hanhy06.emote.playback.stress;

import io.github.hanhy06.emote.mixin.accessor.SynchedEntityDataAccessor;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class StressTestPacketLoad {
    public static final StressTestPacketLoad INSTANCE = new StressTestPacketLoad();

    private static final ServerEntity.Synchronizer NOOP_SYNCHRONIZER = new ServerEntity.Synchronizer() {
        @Override
        public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
        }

        @Override
        public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
        }

        @Override
        public void sendToTrackingPlayersFiltered(
            Packet<? super ClientGamePacketListener> packet,
            Predicate<ServerPlayer> predicate
        ) {
        }
    };

    private @Nullable Session session;

    private StressTestPacketLoad() {
    }

    public void start(MinecraftServer server, int packetFanout) {
        stop();
        if (packetFanout == 0) {
            return;
        }

        ProtocolInfo<ClientGamePacketListener> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
            RegistryFriendlyByteBuf.decorator(server.registryAccess())
        );
        List<EncodingSink> sinks = new ArrayList<>(packetFanout);
        try {
            for (int index = 0; index < packetFanout; index++) {
                sinks.add(new EncodingSink(protocol, server.getCompressionThreshold()));
            }
        } catch (RuntimeException exception) {
            sinks.forEach(EncodingSink::close);
            throw exception;
        }
        this.session = new Session(packetFanout, sinks);
    }

    public void register(ServerLevel level, PlaybackNodes nodes) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        for (PlaybackNodes.NodeInstance node : nodes.nodes().values()) {
            Entity entity = node.entity();
            if (entity == null || current.entities.containsKey(entity)) {
                continue;
            }
            current.entities.put(entity, snapshot(entity));

            ServerEntity pairingEntity = new ServerEntity(
                level,
                entity,
                entity.getType().hasUpdateInterval() ? UpdateInterval.periodic(entity.getType().updateInterval()) : UpdateInterval.NEVER,
                entity.getType().trackDeltas(),
                NOOP_SYNCHRONIZER
            );
            for (int index = 0; index < current.packetFanout; index++) {
                List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
                pairingEntity.sendPairingData(null, packets::add);
                current.encode(index, new ClientboundBundlePacket(packets));
            }
        }
    }

    public void unregister(PlaybackNodes nodes) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        for (PlaybackNodes.NodeInstance node : nodes.nodes().values()) {
            Entity entity = node.entity();
            if (entity != null && current.entities.remove(entity) != null) {
                current.encodeForAll(new ClientboundRemoveEntitiesPacket(entity.getId()));
            }
        }
    }

    public void beginRuntime() {
        Session current = this.session;
        if (current != null) {
            current.beginRuntime();
        }
    }

    public void sampleRuntimeTick() {
        Session current = this.session;
        if (current != null) {
            current.sampleRuntimeTick();
        }
    }

    public void sync(PlaybackNodes nodes) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        for (PlaybackNodes.NodeInstance node : nodes.nodes().values()) {
            Entity entity = node.entity();
            if (entity == null) {
                continue;
            }

            List<SynchedEntityData.DataValue<?>> previous = current.entities.get(entity);
            if (previous == null) {
                continue;
            }
            List<SynchedEntityData.DataValue<?>> next = snapshot(entity);
            List<SynchedEntityData.DataValue<?>> changed = new ArrayList<>();
            for (int index = 0; index < next.size(); index++) {
                if (!sameValue(previous.get(index), next.get(index))) {
                    changed.add(next.get(index));
                }
            }
            current.entities.put(entity, next);
            if (!changed.isEmpty()) {
                current.encodeForAll(new ClientboundSetEntityDataPacket(entity.getId(), changed));
            }
        }
    }

    public PacketLoadResult finishRuntime() {
        Session current = this.session;
        return current == null ? PacketLoadResult.EMPTY : current.createResult();
    }

    private static List<SynchedEntityData.DataValue<?>> snapshot(Entity entity) {
        SynchedEntityData.DataItem<?>[] items = ((SynchedEntityDataAccessor) entity.getEntityData()).emote$getItemsById();
        List<SynchedEntityData.DataValue<?>> snapshot = new ArrayList<>(items.length);
        for (SynchedEntityData.DataItem<?> item : items) {
            snapshot.add(item.value());
        }
        return snapshot;
    }

    private static boolean sameValue(
        SynchedEntityData.DataValue<?> first,
        SynchedEntityData.DataValue<?> second
    ) {
        if (first.id() != second.id() || !Objects.equals(first.serializer(), second.serializer())) {
            return false;
        }
        if (first.value() instanceof ItemStack firstStack && second.value() instanceof ItemStack secondStack) {
            return ItemStack.matches(firstStack, secondStack);
        }
        return Objects.equals(first.value(), second.value());
    }

    public void stop() {
        Session current = this.session;
        this.session = null;
        if (current != null) {
            current.close();
        }
    }

    public record PacketLoadResult(
        int packetFanout,
        long creationPackets,
        long creationBytes,
        long runtimePackets,
        long runtimeBytes,
        int runtimeSamples,
        long maximumRuntimeBytesPerTick,
        long runtimeEncodingNanos
    ) {
        private static final PacketLoadResult EMPTY = new PacketLoadResult(
            0, 0L, 0L, 0L, 0L, 0, 0L, 0L
        );
    }

    private static final class Session {
        private final int packetFanout;
        private final List<EncodingSink> sinks;
        private final IdentityHashMap<Entity, List<SynchedEntityData.DataValue<?>>> entities = new IdentityHashMap<>();

        private long creationPackets;
        private long creationBytes;
        private long encodingNanos;
        private long lastSampledBytes;
        private int runtimeSamples;
        private long maximumRuntimeBytesPerTick;

        private Session(int packetFanout, List<EncodingSink> sinks) {
            this.packetFanout = packetFanout;
            this.sinks = sinks;
        }

        private void encodeForAll(Packet<?> packet) {
            for (int index = 0; index < this.sinks.size(); index++) {
                encode(index, packet);
            }
        }

        private void encode(int sinkIndex, Packet<?> packet) {
            long startedNanos = System.nanoTime();
            this.sinks.get(sinkIndex).encode(packet);
            this.encodingNanos += System.nanoTime() - startedNanos;
        }

        private void beginRuntime() {
            this.creationPackets = totalPackets();
            this.creationBytes = totalBytes();
            this.sinks.forEach(EncodingSink::reset);
            this.encodingNanos = 0L;
            this.lastSampledBytes = 0L;
        }

        private void sampleRuntimeTick() {
            long bytes = totalBytes();
            long bytesThisTick = bytes - this.lastSampledBytes;
            this.runtimeSamples++;
            this.maximumRuntimeBytesPerTick = Math.max(this.maximumRuntimeBytesPerTick, bytesThisTick);
            this.lastSampledBytes = bytes;
        }

        private PacketLoadResult createResult() {
            return new PacketLoadResult(
                this.packetFanout,
                this.creationPackets,
                this.creationBytes,
                totalPackets(),
                totalBytes(),
                this.runtimeSamples,
                this.maximumRuntimeBytesPerTick,
                this.encodingNanos
            );
        }

        private long totalPackets() {
            return this.sinks.stream().mapToLong(EncodingSink::packetCount).sum();
        }

        private long totalBytes() {
            return this.sinks.stream().mapToLong(EncodingSink::byteCount).sum();
        }

        private void close() {
            this.entities.clear();
            this.sinks.forEach(EncodingSink::close);
        }
    }

    private static final class EncodingSink {
        private final PacketCounter packetCounter = new PacketCounter();
        private final ByteCounter byteCounter = new ByteCounter();
        private final EmbeddedChannel channel;

        private EncodingSink(ProtocolInfo<ClientGamePacketListener> protocol, int compressionThreshold) {
            List<ChannelHandler> handlers = new ArrayList<>();
            handlers.add(this.byteCounter);
            handlers.add(new Varint21LengthFieldPrepender());
            if (compressionThreshold >= 0) {
                handlers.add(new CompressionEncoder(compressionThreshold));
            }
            handlers.add(new PacketEncoder<>(protocol));
            handlers.add(this.packetCounter);
            handlers.add(new PacketBundleUnpacker(Objects.requireNonNull(protocol.bundlerInfo(), "PLAY bundler info")));
            this.channel = new EmbeddedChannel(handlers.toArray(ChannelHandler[]::new));
        }

        private void encode(Packet<?> packet) {
            this.channel.writeOutbound(packet);
            this.channel.checkException();
        }

        private long packetCount() {
            return this.packetCounter.count;
        }

        private long byteCount() {
            return this.byteCounter.count;
        }

        private void reset() {
            this.packetCounter.count = 0L;
            this.byteCounter.count = 0L;
        }

        private void close() {
            this.channel.finishAndReleaseAll();
        }
    }

    private static final class PacketCounter extends ChannelOutboundHandlerAdapter {
        private long count;

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof Packet<?>) {
                this.count++;
            }
            super.write(context, message, promise);
        }
    }

    private static final class ByteCounter extends ChannelOutboundHandlerAdapter {
        private long count;

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof io.netty.buffer.ByteBuf buffer) {
                this.count += buffer.readableBytes();
            }
            ReferenceCountUtil.release(message);
            promise.setSuccess();
        }
    }
}
