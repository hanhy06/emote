package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.mixin.accessor.DisplayAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.Deflater;

public final class VirtualDisplaySender {
    public static final VirtualDisplaySender INSTANCE = new VirtualDisplaySender();

    private final LongAdder codecCalls = new LongAdder();
    private final LongAdder compressionCalls = new LongAdder();
    private final LongAdder failedEncodes = new LongAdder();
    private final LongAdder directPackets = new LongAdder();
    private final LongAdder fallbackPackets = new LongAdder();
    private final LongAdder workerNanos = new LongAdder();
    private final LongAdder batchWrites = new LongAdder();
    private final LongAdder coalescedPoses = new LongAdder();
    private final Map<ServerGamePacketListenerImpl, Delivery> deliveries = new HashMap<>();
    private @Nullable WireEncoder encoder;
    private Set<Integer> poseFields = Set.of();
    private volatile boolean pendingDeliveries;
    private boolean polymerCore;

    VirtualDisplaySender() {}

    public void start(MinecraftServer server) {
        this.polymerCore = FabricLoader.getInstance().isModLoaded("polymer-core");
        this.poseFields = Set.of(DisplayAccessor.emote$translation().id(), DisplayAccessor.emote$scale().id(),
            DisplayAccessor.emote$leftRotation().id(), DisplayAccessor.emote$rightRotation().id(),
            DisplayAccessor.emote$interpolationStart().id(), DisplayAccessor.emote$interpolationDuration().id(),
            DisplayAccessor.emote$posRotInterpolationDuration().id());
        startEncoder(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())));
    }

    void startEncoder(ProtocolInfo<ClientGamePacketListener> protocol) {
        this.encoder = new WireEncoder(protocol);
    }

    public void stop() {
        if (this.encoder != null) {
            this.encoder.close();
            this.encoder = null;
        }
        this.deliveries.clear();
        this.pendingDeliveries = false;
    }

    public DisplayPacket packet(Packet<?> packet) {
        boolean shareable = !this.polymerCore || !hasContextualMetadata(packet);
        long poseKey = switch (packet) {
            case ClientboundEntityPositionSyncPacket movement -> DisplayPacketQueue.poseKey(movement.id(), false);
            case ClientboundSetEntityDataPacket metadata when metadata.packedItems().stream().allMatch(value -> this.poseFields.contains(value.id())) ->
                DisplayPacketQueue.poseKey(metadata.id(), true);
            default -> 0;
        };
        return new DisplayPacket(packet, shareable, poseKey);
    }

    private static boolean hasContextualMetadata(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> subPacket : bundle.subPackets()) {
                if (hasContextualMetadata(subPacket)) return true;
            }
        }
        if (packet instanceof ClientboundSetEntityDataPacket metadata) {
            return metadata.packedItems().stream().anyMatch(value -> {
                var serializer = value.serializer();
                return serializer == EntityDataSerializers.ITEM_STACK || serializer == EntityDataSerializers.BLOCK_STATE
                    || serializer == EntityDataSerializers.OPTIONAL_BLOCK_STATE || serializer == EntityDataSerializers.COMPONENT
                    || serializer == EntityDataSerializers.OPTIONAL_COMPONENT;
            });
        }
        return false;
    }

    @Nullable EncodedFrame frame(DisplayPacket packet, int threshold) {
        if (!packet.shareable || packet.serializationFailed) return null;
        if (threshold == packet.firstThreshold) return packet.firstFrame;
        if (packet.firstThreshold == Integer.MIN_VALUE) {
            packet.firstThreshold = threshold;
            return packet.firstFrame = encode(packet, threshold);
        }
        if (packet.otherFrames == null) packet.otherFrames = new HashMap<>();
        if (!packet.otherFrames.containsKey(threshold)) packet.otherFrames.put(threshold, encode(packet, threshold));
        return packet.otherFrames.get(threshold);
    }

    private @Nullable EncodedFrame encode(DisplayPacket packet, int threshold) {
        long started = System.nanoTime();
        try {
            if (packet.payloads == null) packet.payloads = this.encoder.serialize(packet.packet());
            return this.encoder.frame(packet.payloads, threshold);
        } catch (RuntimeException exception) {
            if (packet.payloads == null) packet.serializationFailed = true;
            this.failedEncodes.increment();
            if (this.failedEncodes.sum() == 1) EmoteMod.LOGGER.warn("Could not encode virtual display packet {}", packet.packet().type(), exception);
            return null;
        } finally {
            this.workerNanos.add(System.nanoTime() - started);
        }
    }

    public boolean hasPendingDeliveries() { return this.pendingDeliveries; }

    public void viewerChangedWorld(ServerGamePacketListenerImpl viewer) {
        ((SocketConnection) viewer.getPacketContext().orElseThrow(PacketContext.CONNECTION)).emote$invalidateDisplayPackets();
    }

    public void send(Map<ServerGamePacketListenerImpl, List<DisplayPacket>> updates) {
        this.deliveries.values().removeIf(delivery -> !delivery.isCurrent());
        Map<BatchKey, WriteBatch> shared = new HashMap<>();
        Map<ClientboundSetEntityDataPacket, DisplayPacket> mergedMetadata = new HashMap<>();
        updates.forEach((viewer, packets) -> {
            Delivery delivery = this.deliveries.get(viewer);
            if (delivery == null) {
                Connection connection = viewer.getPacketContext().orElseThrow(PacketContext.CONNECTION);
                delivery = new Delivery(viewer, connection);
                if (!(delivery.channel instanceof SocketChannel)) {
                    for (DisplayPacket packet : packets) sendNormally(viewer, packet.packet());
                    return;
                }
                if (!delivery.isCurrent()) return;
                this.deliveries.put(viewer, delivery);
            }
            if (delivery.pending.isEmpty() && delivery.canWrite()) delivery.submit(packets, shared);
            else this.coalescedPoses.add(delivery.pending.addAll(packets));
        });
        for (Iterator<Delivery> deliveries = this.deliveries.values().iterator(); deliveries.hasNext();) {
            Delivery delivery = deliveries.next();
            if (!delivery.pending.isEmpty() && delivery.canWrite()) {
                delivery.submit(delivery.pending.drain(packet -> mergedMetadata.computeIfAbsent(packet, this::packet)), shared);
            }
            if (delivery.pending.isEmpty() && !delivery.scheduled) deliveries.remove();
        }
        this.pendingDeliveries = !this.deliveries.isEmpty();
    }

    private final class Delivery {
        private final ServerGamePacketListenerImpl viewer;
        private final Connection connection;
        private final SocketConnection transport;
        private final Channel channel;
        private final int epoch;
        private final DisplayPacketQueue pending = new DisplayPacketQueue();
        private volatile boolean scheduled;

        private Delivery(ServerGamePacketListenerImpl viewer, Connection connection) {
            this.viewer = viewer;
            this.connection = connection;
            this.transport = (SocketConnection) connection;
            this.channel = this.transport.emote$channel();
            this.epoch = this.transport.emote$displayEpoch();
        }

        private boolean isCurrent() {
            return this.channel.isActive() && this.connection.getPacketListener() == this.viewer && this.epoch == this.transport.emote$displayEpoch();
        }

        private boolean canWrite() {
            return !this.scheduled && this.channel.isWritable();
        }

        private void submit(List<DisplayPacket> packets, Map<BatchKey, WriteBatch> shared) {
            int compression = this.transport.emote$compressionThreshold();
            WriteBatch batch = shared.computeIfAbsent(new BatchKey(compression, packets), key -> prepareBatch(key.packets(), key.compression()));
            this.scheduled = true;
            this.channel.eventLoop().execute(() -> {
                try {
                    if (isCurrent()) write(batch, compression);
                } finally {
                    this.scheduled = false;
                }
            });
        }

        private void write(WriteBatch batch, int compression) {
            var prepender = this.transport.emote$compressionThreshold() == compression ? compatiblePrepender(this.channel, compression) : null;
            if (prepender == null) {
                for (Packet<?> packet : batch.packets()) sendNormally(this.viewer, packet);
                return;
            }
            boolean encrypted = this.channel.pipeline().get("encrypt") != null;
            boolean needsFlush = false;
            for (WireWrite write : batch.writes()) {
                if (write.packet() != null) {
                    sendNormally(this.viewer, write.packet());
                    needsFlush = false;
                } else {
                    directPackets.add(write.packetCount());
                    batchWrites.increment();
                    this.transport.emote$countDisplayPackets(write.packetCount());
                    writeFrame(prepender, write.frame(), encrypted);
                    needsFlush = true;
                }
            }
            if (needsFlush) prepender.flush();
        }
    }

    private record BatchKey(int compression, List<DisplayPacket> packets) {}

    private record WireWrite(@Nullable EncodedFrame frame, @Nullable Packet<?> packet, int packetCount) {}

    private record WriteBatch(List<Packet<?>> packets, List<WireWrite> writes) {}

    private WriteBatch prepareBatch(List<DisplayPacket> packets, int compression) {
        List<WireWrite> writes = new ArrayList<>();
        List<EncodedFrame> frames = new ArrayList<>();
        for (DisplayPacket packet : packets) {
            EncodedFrame frame = frame(packet, compression);
            if (frame == null) {
                if (!frames.isEmpty()) writes.add(join(frames));
                frames.clear();
                writes.add(new WireWrite(null, packet.packet(), 0));
            } else {
                frames.add(frame);
            }
        }
        if (!frames.isEmpty()) writes.add(join(frames));
        return new WriteBatch(packets.stream().map(DisplayPacket::packet).toList(), writes);
    }

    private static WireWrite join(List<EncodedFrame> frames) {
        if (frames.size() == 1) return new WireWrite(frames.getFirst(), null, 1);
        int bytes = 0;
        for (EncodedFrame frame : frames) bytes = Math.addExact(bytes, frame.bytes().length);
        byte[] joined = new byte[bytes];
        List<WirePacket> metrics = new ArrayList<>();
        int offset = 0;
        for (EncodedFrame frame : frames) {
            System.arraycopy(frame.bytes(), 0, joined, offset, frame.bytes().length);
            offset += frame.bytes().length;
            metrics.addAll(frame.packets());
        }
        return new WireWrite(new EncodedFrame(joined, metrics), null, frames.size());
    }

    private void sendNormally(ServerGamePacketListenerImpl viewer, Packet<?> packet) {
        this.fallbackPackets.increment();
        viewer.send(packet);
    }

    private static @Nullable ChannelHandlerContext compatiblePrepender(Channel channel, int threshold) {
        var pipeline = channel.pipeline();
        var prepender = pipeline.context("prepender");
        if (prepender == null || !(prepender.handler().getClass() == Varint21LengthFieldPrepender.class
            || kryptonHandler(prepender.handler(), "pipeline.MinecraftVarintPrepender"))) return null;
        var encoder = pipeline.get("encoder");
        if (!(encoder instanceof PacketEncoder<?> && ((EncoderProtocol) encoder).emote$protocol() == ConnectionProtocol.PLAY)) return null;
        var compression = pipeline.get("compress");
        if (threshold < 0 ? compression != null
            : !(compression instanceof CompressionEncoder vanilla && compression.getClass() == CompressionEncoder.class
                && vanilla.getThreshold() == threshold || kryptonHandler(compression, "compression.MinecraftCompressEncoder"))) return null;

        boolean afterPrepender = false;
        for (String name : pipeline.names()) {
            var handler = pipeline.get(name);
            if (!(handler instanceof ChannelOutboundHandler)) continue;
            if (handler.getClass() == FlowControlHandler.class) continue;
            boolean allowed = switch (name) {
                case "prepender" -> afterPrepender = true;
                case "compress", "encoder" -> afterPrepender;
                case "unbundler" -> afterPrepender && handler.getClass() == PacketBundleUnpacker.class;
                case "fabric:splitter" -> afterPrepender
                    && handler.getClass().getName().equals("net.fabricmc.fabric.impl.networking.splitter.FabricPacketSplitter");
                case "hackfix" -> afterPrepender && handler.getClass().getEnclosingClass() == Connection.class;
                case "timeout" -> handler.getClass() == ReadTimeoutHandler.class;
                case "emote_network_benchmark" -> true;
                case "encrypt" -> !afterPrepender && (handler.getClass() == CipherEncoder.class
                    || kryptonHandler(handler, "pipeline.MinecraftCipherEncoder"));
                default -> false;
            };
            if (!allowed) return null;
        }
        return prepender;
    }

    private static boolean kryptonHandler(@Nullable Object handler, String name) {
        return handler != null && handler.getClass().getName().equals("me.steinborn.krypton.mod.shared.network." + name);
    }

    private static void writeFrame(ChannelHandlerContext prepender, EncodedFrame frame, boolean encrypted) {
        for (WirePacket packet : frame.packets()) {
            var measurement = NetworkBenchmark.INSTANCE.beginFrame(prepender.channel());
            if (measurement != null) {
                measurement.encoded(packet.packet(), packet.payloadSize(), 0);
                if (packet.compressionSize() >= 0) measurement.compressed(packet.compressionSize(), 0, packet.deflated());
                measurement.framed(packet.frameSize(), 0);
            }
        }
        // Krypton's cipher can encrypt its input in place.
        ByteBuf bytes = encrypted ? prepender.alloc().ioBuffer(frame.bytes().length).writeBytes(frame.bytes())
            : Unpooled.wrappedBuffer(frame.bytes());
        prepender.write(bytes);
    }

    public Stats stats() {
        return new Stats(this.encoder != null, this.codecCalls.sum(), this.compressionCalls.sum(), this.failedEncodes.sum(),
            this.directPackets.sum(), this.fallbackPackets.sum(), this.workerNanos.sum(), this.batchWrites.sum(), this.coalescedPoses.sum());
    }

    public record Stats(boolean enabled, long codecCalls, long compressionCalls, long failedEncodes,
                        long directPackets, long fallbackPackets, long workerNanos, long batchWrites, long coalescedPoses) {
        Stats since(Stats start) {
            return new Stats(enabled, codecCalls - start.codecCalls, compressionCalls - start.compressionCalls,
                failedEncodes - start.failedEncodes, directPackets - start.directPackets, fallbackPackets - start.fallbackPackets,
                workerNanos - start.workerNanos, batchWrites - start.batchWrites, coalescedPoses - start.coalescedPoses);
        }
    }

    public interface EncoderProtocol {
        ConnectionProtocol emote$protocol();
    }

    public interface SocketConnection {
        Channel emote$channel();
        int emote$compressionThreshold();
        int emote$displayEpoch();
        void emote$invalidateDisplayPackets();
        void emote$countDisplayPackets(int count);
    }

    public static final class DisplayPacket {
        private final Packet<?> packet;
        private final boolean shareable;
        private final long poseKey;
        private @Nullable List<WirePayload> payloads;
        private boolean serializationFailed;
        private int firstThreshold = Integer.MIN_VALUE;
        private @Nullable EncodedFrame firstFrame;
        private @Nullable Map<Integer, EncodedFrame> otherFrames;

        DisplayPacket(Packet<?> packet, boolean shareable, long poseKey) {
            this.packet = packet;
            this.shareable = shareable;
            this.poseKey = poseKey;
        }

        Packet<?> packet() { return this.packet; }
        long poseKey() { return this.poseKey; }
    }

    record EncodedFrame(byte[] bytes, List<WirePacket> packets) {}
    record WirePacket(Packet<?> packet, int payloadSize, int compressionSize, int frameSize, boolean deflated) {}

    private static final class WirePayload {
        private final Packet<?> packet;
        private final byte[] bytes;
        private @Nullable byte[] compressed;

        private WirePayload(Packet<?> packet, byte[] bytes) {
            this.packet = packet;
            this.bytes = bytes;
        }
    }

    private final class WireEncoder implements AutoCloseable {
        private final ProtocolInfo<ClientGamePacketListener> protocol;
        private final ByteBuf payload = Unpooled.buffer();
        private final ByteBuf compressed = Unpooled.buffer();
        private final ByteBuf framed = Unpooled.buffer();
        private final Deflater deflater = new Deflater();
        private final byte[] deflateBuffer = new byte[8192];

        private WireEncoder(ProtocolInfo<ClientGamePacketListener> protocol) {
            this.protocol = protocol;
        }

        private List<WirePayload> serialize(Packet<?> packet) {
            List<WirePayload> packets = new ArrayList<>();
            this.protocol.bundlerInfo().unbundlePacket(packet, part -> packets.add(serializePacket(part)));
            return packets;
        }

        @SuppressWarnings("unchecked")
        private WirePayload serializePacket(Packet<?> packet) {
            this.payload.clear();
            codecCalls.increment();
            this.protocol.codec().encode(this.payload, (Packet<? super ClientGamePacketListener>) packet);
            return new WirePayload(packet, ByteBufUtil.getBytes(this.payload));
        }

        private EncodedFrame frame(List<WirePayload> packets, int threshold) {
            this.framed.clear();
            List<WirePacket> sizes = new ArrayList<>(packets.size());
            for (WirePayload packet : packets) sizes.add(writePacket(packet, threshold));
            return new EncodedFrame(ByteBufUtil.getBytes(this.framed, 0, this.framed.readableBytes(), true), List.copyOf(sizes));
        }

        private WirePacket writePacket(WirePayload packet, int threshold) {
            int length = packet.bytes.length;
            if (threshold >= 0 && length > 8_388_608) throw new IllegalArgumentException("Display packet exceeds the compression size limit: " + length);
            boolean deflated = threshold >= 0 && length >= threshold;
            if (deflated && packet.compressed == null) packet.compressed = compress(packet.bytes);
            byte[] body = deflated ? packet.compressed : packet.bytes;
            int uncompressedPrefix = threshold >= 0 && !deflated ? 1 : 0;
            int bodySize = body.length + uncompressedPrefix;
            int lengthPrefix = VarInt.getByteSize(bodySize);
            if (lengthPrefix > 3) throw new IllegalArgumentException("Display packet exceeds the frame size limit: " + bodySize);
            VarInt.write(this.framed, bodySize);
            if (uncompressedPrefix != 0) this.framed.writeByte(0);
            this.framed.writeBytes(body);
            return new WirePacket(packet.packet, length, threshold >= 0 ? bodySize : -1, lengthPrefix + bodySize, deflated);
        }

        private byte[] compress(byte[] bytes) {
            this.compressed.clear();
            VarInt.write(this.compressed, bytes.length);
            try {
                compressionCalls.increment();
                this.deflater.setInput(bytes);
                this.deflater.finish();
                while (!this.deflater.finished()) {
                    int written = this.deflater.deflate(this.deflateBuffer);
                    this.compressed.writeBytes(this.deflateBuffer, 0, written);
                }
            } finally {
                this.deflater.reset();
            }
            return ByteBufUtil.getBytes(this.compressed);
        }

        @Override
        public void close() {
            this.payload.release();
            this.compressed.release();
            this.framed.release();
            this.deflater.end();
        }
    }
}
