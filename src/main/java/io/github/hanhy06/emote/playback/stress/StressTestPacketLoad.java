package io.github.hanhy06.emote.playback.stress;

import io.github.hanhy06.emote.mixin.accessor.SynchedEntityDataAccessor;
import io.github.hanhy06.emote.playback.PlaybackScene;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.util.concurrent.Future;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        this.session = new Session(protocol, server.getCompressionThreshold(), packetFanout,
            EventLoopGroupHolder.remote(server.useNativeTransport()).eventLoopGroup());
    }

    public void register(ServerLevel level, PlaybackScene scene) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        for (PlaybackScene.AttachmentInstance node : scene.attachments()) {
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
                current.send(index, new ClientboundBundlePacket(packets));
            }
        }
    }

    public void unregister(PlaybackScene scene) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        for (PlaybackScene.AttachmentInstance node : scene.attachments()) {
            Entity entity = node.entity();
            if (entity != null && current.entities.remove(entity) != null) {
                current.sendForAll(new ClientboundRemoveEntitiesPacket(entity.getId()));
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

    public void sync(PlaybackScene scene) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        for (PlaybackScene.AttachmentInstance node : scene.attachments()) {
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
                current.sendForAll(new ClientboundSetEntityDataPacket(entity.getId(), changed));
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
        long runtimeProcessingNanos,
        long maximumRuntimeProcessingNanosPerTick,
        long drainNanos
    ) {
        private static final PacketLoadResult EMPTY = new PacketLoadResult(
            0, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L
        );
    }

    static final class Session {
        private final int packetFanout;
        private final List<VirtualChannel> channels = new ArrayList<>();
        private final IdentityHashMap<Entity, List<SynchedEntityData.DataValue<?>>> entities = new IdentityHashMap<>();

        private long creationPackets;
        private long creationBytes;
        private boolean runtimeStarted;
        private int runtimeSamples;
        private final AtomicLong maximumRuntimeBytesPerTick = new AtomicLong();
        private final AtomicLong maximumRuntimeProcessingNanosPerTick = new AtomicLong();

        Session(ProtocolInfo<ClientGamePacketListener> protocol, int compressionThreshold, int packetFanout, EventLoopGroup eventLoops) {
            this.packetFanout = packetFanout;
            try {
                for (int index = 0; index < packetFanout; index++) {
                    VirtualChannel channel = new VirtualChannel(protocol, compressionThreshold);
                    this.channels.add(channel);
                    eventLoops.register(channel).syncUninterruptibly();
                }
            } catch (RuntimeException exception) {
                close();
                throw exception;
            }
        }

        void sendForAll(Packet<?> packet) {
            for (int index = 0; index < this.channels.size(); index++) {
                send(index, packet);
            }
        }

        void send(int channelIndex, Packet<?> packet) {
            VirtualChannel channel = this.channels.get(channelIndex);
            channel.writeAndFlush(packet, channel.voidPromise());
        }

        void beginRuntime() {
            List<Future<long[]>> samples = new ArrayList<>(this.channels.size());
            for (VirtualChannel channel : this.channels) {
                samples.add(channel.eventLoop().submit(() -> {
                    channel.checkFailure();
                    long[] sample = {channel.packetCounter.count, channel.bytes};
                    channel.packetCounter.count = 0L;
                    channel.bytes = 0L;
                    channel.processingNanos = 0L;
                    return sample;
                }));
            }
            for (Future<long[]> sample : samples) {
                sample.syncUninterruptibly();
                this.creationPackets += sample.getNow()[0];
                this.creationBytes += sample.getNow()[1];
            }
            this.runtimeStarted = true;
        }

        void sampleRuntimeTick() {
            this.runtimeSamples++;
            AtomicInteger remaining = new AtomicInteger(this.channels.size());
            AtomicLong bytes = new AtomicLong();
            AtomicLong processingNanos = new AtomicLong();
            for (VirtualChannel channel : this.channels) {
                channel.eventLoop().execute(() -> {
                    bytes.addAndGet(channel.bytes - channel.lastSampledBytes);
                    processingNanos.addAndGet(channel.processingNanos - channel.lastSampledProcessingNanos);
                    channel.lastSampledBytes = channel.bytes;
                    channel.lastSampledProcessingNanos = channel.processingNanos;
                    if (remaining.decrementAndGet() == 0) {
                        this.maximumRuntimeBytesPerTick.accumulateAndGet(bytes.get(), Math::max);
                        this.maximumRuntimeProcessingNanosPerTick.accumulateAndGet(processingNanos.get(), Math::max);
                    }
                });
            }
        }

        PacketLoadResult createResult() {
            if (!this.runtimeStarted) beginRuntime();
            long startedNanos = System.nanoTime();
            List<Future<?>> pending = new ArrayList<>(this.channels.size());
            for (VirtualChannel channel : this.channels) {
                pending.add(channel.eventLoop().submit(channel::checkFailure));
            }
            pending.forEach(Future::syncUninterruptibly);
            long drainNanos = System.nanoTime() - startedNanos;
            return new PacketLoadResult(
                this.packetFanout,
                this.creationPackets,
                this.creationBytes,
                this.channels.stream().mapToLong(channel -> channel.packetCounter.count).sum(),
                this.channels.stream().mapToLong(channel -> channel.bytes).sum(),
                this.runtimeSamples,
                this.maximumRuntimeBytesPerTick.get(),
                this.channels.stream().mapToLong(channel -> channel.processingNanos).sum(),
                this.maximumRuntimeProcessingNanosPerTick.get(),
                drainNanos
            );
        }

        void close() {
            this.entities.clear();
            List<ChannelFuture> pending = new ArrayList<>(this.channels.size());
            for (VirtualChannel channel : this.channels) pending.add(channel.close());
            pending.forEach(ChannelFuture::syncUninterruptibly);
        }
    }

    private static final class VirtualChannel extends AbstractChannel {
        private static final ChannelMetadata METADATA = new ChannelMetadata(false);

        private final ChannelConfig config = new DefaultChannelConfig(this);
        private final SocketAddress address = new LocalAddress("emote-stress-" + id().asLongText());
        private final PacketCounter packetCounter = new PacketCounter();
        private volatile boolean open = true;
        private volatile @Nullable Throwable failure;
        private long bytes;
        private long processingNanos;
        private long lastSampledBytes;
        private long lastSampledProcessingNanos;

        private VirtualChannel(ProtocolInfo<ClientGamePacketListener> protocol, int compressionThreshold) {
            super(null);
            pipeline().addLast(new Varint21LengthFieldPrepender());
            if (compressionThreshold >= 0) {
                pipeline().addLast(new CompressionEncoder(compressionThreshold));
            }
            pipeline().addLast(new PacketEncoder<>(protocol));
            pipeline().addLast(this.packetCounter);
            pipeline().addLast(new PacketBundleUnpacker(Objects.requireNonNull(protocol.bundlerInfo(), "PLAY bundler info")));
            pipeline().addLast(new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    long startedNanos = System.nanoTime();
                    try { context.write(message, promise); }
                    finally { processingNanos += System.nanoTime() - startedNanos; }
                }

                @Override
                public void flush(ChannelHandlerContext context) throws Exception {
                    long startedNanos = System.nanoTime();
                    try { context.flush(); }
                    finally { processingNanos += System.nanoTime() - startedNanos; }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                    if (failure == null) failure = cause;
                    context.close();
                }
            });
        }

        private void checkFailure() {
            if (this.failure != null) throw new IllegalStateException("Stress-test packet transmission failed", this.failure);
        }

        @Override
        public ChannelConfig config() { return this.config; }

        @Override
        public ChannelMetadata metadata() { return METADATA; }

        @Override
        public boolean isOpen() { return this.open; }

        @Override
        public boolean isActive() { return this.open; }

        @Override
        protected boolean isCompatible(EventLoop loop) { return true; }

        @Override
        protected SocketAddress localAddress0() { return this.address; }

        @Override
        protected SocketAddress remoteAddress0() { return this.address; }

        @Override
        protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override
                public void connect(SocketAddress remoteAddress, @Nullable SocketAddress localAddress, ChannelPromise promise) {
                    promise.setFailure(new UnsupportedOperationException("Send-only stress-test channel"));
                }
            };
        }

        @Override
        protected void doBind(SocketAddress localAddress) { throw new UnsupportedOperationException("Send-only stress-test channel"); }

        @Override
        protected void doDisconnect() { doClose(); }

        @Override
        protected void doClose() { this.open = false; }

        @Override
        protected void doBeginRead() { }

        @Override
        protected Object filterOutboundMessage(Object message) {
            if (message instanceof ByteBuf) return message;
            throw new IllegalArgumentException("Expected encoded packet bytes");
        }

        @Override
        protected void doWrite(ChannelOutboundBuffer buffer) {
            while (buffer.current() instanceof ByteBuf bytes) {
                this.bytes += bytes.readableBytes();
                buffer.remove();
            }
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

}
