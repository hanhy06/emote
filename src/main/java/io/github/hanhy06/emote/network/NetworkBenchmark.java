package io.github.hanhy06.emote.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.hanhy06.emote.EmoteMod;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class NetworkBenchmark {
    public static final NetworkBenchmark INSTANCE = new NetworkBenchmark();

    private static final AttributeKey<Frame> FRAME = AttributeKey.valueOf("emote:network_benchmark_frame");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final SocketProbe SOCKET_PROBE = new SocketProbe();

    private volatile @Nullable Capture active;
    private long nextCaptureId;

    private NetworkBenchmark() {
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Capture capture = this.active;
            if (capture != null) capture.notePlayer(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(this::stop);
    }

    public boolean start(MinecraftServer server, int durationTicks, Consumer<Result> onComplete) {
        if (this.active != null) return false;
        this.active = new Capture(++this.nextCaptureId, server, durationTicks, 0L, onComplete);
        return true;
    }

    public boolean startForSeconds(MinecraftServer server, int seconds, Consumer<Report> onComplete) {
        if (this.active != null) return false;
        this.active = new Capture(++this.nextCaptureId, server, 0, seconds * 1_000_000_000L, result -> onComplete.accept(result.report()));
        return true;
    }

    public boolean isRunning() {
        return this.active != null;
    }

    public void sampleTick(MinecraftServer server, long elapsedNanos) {
        Capture capture = this.active;
        if (capture == null) return;

        int completedTick = server.getTickCount();
        if (completedTick <= capture.lastSampledTick) return;
        capture.tickNanos.add(elapsedNanos);
        capture.lastSampledTick = completedTick;
        long elapsed = System.nanoTime() - capture.startedNanos;
        if (capture.durationNanos > 0L ? elapsed >= capture.durationNanos : capture.tickNanos.size() >= capture.durationTicks) {
            finish(server, true);
        } else {
            capture.jvm.sample(elapsed, false);
        }
    }

    public boolean stop(MinecraftServer server) {
        return finish(server, false);
    }

    private boolean finish(MinecraftServer server, boolean completed) {
        Capture capture = this.active;
        if (capture == null) return false;
        this.active = null;

        Report report = capture.report(server.getPlayerList().getPlayerCount(), completed);
        Path reportPath = null;
        if (capture.durationTicks > 0) {
            try {
                Path directory = Files.createDirectories(server.getServerDirectory().resolve("debug"));
                reportPath = Files.createTempFile(directory, "emote-network-", ".json");
                try (var writer = Files.newBufferedWriter(reportPath)) {
                    GSON.toJson(report, writer);
                }
            } catch (IOException exception) {
                EmoteMod.LOGGER.warn("Could not write the emote network benchmark report", exception);
                reportPath = null;
            }
        }
        capture.onComplete.accept(new Result(report, reportPath));
        return true;
    }

    public void installSocketProbe(ChannelPipeline pipeline) {
        pipeline.channel().attr(FRAME);
        // Measure writes that reach the transport, including buffers that bypass the codec.
        pipeline.addFirst("emote_network_benchmark", SOCKET_PROBE);
    }

    public @Nullable Frame beginFrame(Channel channel) {
        Capture capture = this.active;
        if (capture == null || !channel.hasAttr(FRAME)) return null;
        Frame frame = frameForCapture(channel, capture);
        frame.packetType = null;
        frame.compressionBytes = 0;
        frame.compressionNanos = 0;
        frame.compressionEnabled = false;
        frame.deflated = false;
        return frame;
    }

    private static Frame frameForCapture(Channel channel, Capture capture) {
        var attribute = channel.attr(FRAME);
        Frame frame = attribute.get();
        if (frame == null || frame.captureId != capture.id) {
            Thread thread = Thread.currentThread();
            capture.noteEventLoop(thread);
            ConnectionStats connection = new ConnectionStats(channel.remoteAddress(), thread.getName());
            capture.connections.add(connection);
            frame = new Frame(capture.id, connection);
            attribute.set(frame);
        }
        return frame;
    }

    public @Nullable Frame encodedFrame(Channel channel) {
        Capture capture = this.active;
        if (capture == null || !channel.hasAttr(FRAME)) return null;
        Frame frame = channel.attr(FRAME).get();
        return frame != null && frame.captureId == capture.id && frame.packetType != null ? frame : null;
    }

    public static final class Frame {
        // A TCP channel runs the encoder, compressor and prepender in order on the same event loop.
        private final long captureId;
        private final ConnectionStats connection;
        private @Nullable PacketType<?> packetType;
        private int encodedBytes;
        private int compressionBytes;
        private long encodeNanos;
        private long compressionNanos;
        private boolean compressionEnabled;
        private boolean deflated;

        private Frame(long captureId, ConnectionStats connection) {
            this.captureId = captureId;
            this.connection = connection;
        }

        public void encoded(Packet<?> packet, int bytes, long nanos) {
            this.packetType = packet.type();
            this.encodedBytes = bytes;
            this.encodeNanos = nanos;
        }

        public void compressed(int bytes, long nanos, boolean deflated) {
            this.compressionBytes = bytes;
            this.compressionNanos = nanos;
            this.compressionEnabled = true;
            this.deflated = deflated;
        }

        public void framed(int bytes, long nanos) {
            PacketType<?> type = this.packetType;
            this.packetType = null;
            if (type == null) return;

            synchronized (this.connection) {
                Capture capture = INSTANCE.active;
                if (capture == null || capture.id != this.captureId) return;
                this.connection.packetTypes.computeIfAbsent(type, ignored -> new Traffic()).record(this, bytes, nanos);
            }
        }

        public void discard() {
            this.packetType = null;
        }
    }

    @ChannelHandler.Sharable
    private static final class SocketProbe extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            Capture capture = INSTANCE.active;
            if (capture == null || !(message instanceof ByteBuf bytes)) {
                context.write(message, promise);
                return;
            }
            Channel channel = context.channel();
            int size = bytes.readableBytes();
            ConnectionStats connection = frameForCapture(channel, capture).connection;
            context.write(message, promise);
            ChannelOutboundBuffer pending = channel.unsafe().outboundBuffer();
            long pendingBytes = pending == null ? 0 : pending.totalPendingWriteBytes();
            synchronized (connection) {
                if (INSTANCE.active != capture) return;
                connection.transport.writeCalls++;
                connection.transport.writeBytes += size;
                if (!channel.isWritable()) connection.transport.unwritableWrites++;
                connection.transport.peakPendingWriteBytes = Math.max(connection.transport.peakPendingWriteBytes, pendingBytes);
            }
        }

        @Override
        public void flush(ChannelHandlerContext context) throws Exception {
            Capture capture = INSTANCE.active;
            if (capture == null) {
                context.flush();
                return;
            }
            ConnectionStats connection = frameForCapture(context.channel(), capture).connection;
            long started = System.nanoTime();
            context.flush();
            long elapsed = System.nanoTime() - started;
            synchronized (connection) {
                if (INSTANCE.active != capture) return;
                connection.transport.flushCalls++;
                connection.transport.flushNanos += elapsed;
            }
        }
    }

    private static final class Capture {
        private final long id;
        private final String startedAt = Instant.now().toString();
        private final long startedNanos = System.nanoTime();
        private final int durationTicks;
        private final long durationNanos;
        private final int compressionThreshold;
        private final int playersAtStart;
        private final Consumer<Result> onComplete;
        private final JvmBenchmarkStats.Measurement jvm = new JvmBenchmarkStats.Measurement();
        private final VirtualDisplaySender.Stats initialVirtualDisplayStats = VirtualDisplaySender.INSTANCE.stats();
        private final LongArrayList tickNanos = new LongArrayList();
        private final Map<SocketAddress, String> players = new HashMap<>();
        private final ConcurrentLinkedQueue<ConnectionStats> connections = new ConcurrentLinkedQueue<>();
        private final Map<Long, EventLoopStart> eventLoops = new ConcurrentHashMap<>();
        private int lastSampledTick;

        private Capture(long id, MinecraftServer server, int durationTicks, long durationNanos, Consumer<Result> onComplete) {
            this.id = id;
            this.durationTicks = durationTicks;
            this.durationNanos = durationNanos;
            this.compressionThreshold = server.getCompressionThreshold();
            this.playersAtStart = server.getPlayerList().getPlayerCount();
            this.onComplete = onComplete;
            this.lastSampledTick = server.getTickCount();
            server.getPlayerList().getPlayers().forEach(this::notePlayer);
            this.jvm.sample(0, false);
        }

        private void notePlayer(ServerPlayer player) {
            this.players.put(player.connection.getRemoteAddress(), player.getScoreboardName());
        }

        private void noteEventLoop(Thread thread) {
            if (!THREADS.isThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return;
            this.eventLoops.computeIfAbsent(thread.threadId(), id -> new EventLoopStart(
                thread.getName(), System.nanoTime(), THREADS.getThreadCpuTime(id)
            ));
        }

        private Report report(int playersAtEnd, boolean completed) {
            long endNanos = System.nanoTime();
            long elapsed = endNanos - this.startedNanos;
            List<EventLoopCpu> cpu = new ArrayList<>();
            this.eventLoops.forEach((threadId, start) -> {
                long endCpuNanos = THREADS.getThreadCpuTime(threadId);
                if (start.cpuNanos >= 0 && endCpuNanos >= start.cpuNanos) {
                    cpu.add(new EventLoopCpu(start.name, endCpuNanos - start.cpuNanos, endNanos - start.wallNanos));
                }
            });
            cpu.sort(Comparator.comparingLong(EventLoopCpu::cpuNanos).reversed());
            var jvmStats = this.jvm.finish(elapsed);
            var virtualDisplayStats = VirtualDisplaySender.INSTANCE.stats().since(this.initialVirtualDisplayStats);

            Traffic total = new Traffic();
            Transport transport = new Transport();
            Map<String, Traffic> packetTypes = new TreeMap<>();
            List<ConnectionReport> connectionReports = new ArrayList<>();
            for (ConnectionStats connection : this.connections) {
                synchronized (connection) {
                    Traffic connectionTotal = new Traffic();
                    Map<String, Traffic> connectionTypes = new TreeMap<>();
                    connection.packetTypes.forEach((type, traffic) -> {
                        String name = type.id().toString();
                        connectionTypes.computeIfAbsent(name, ignored -> new Traffic()).add(traffic);
                        packetTypes.computeIfAbsent(name, ignored -> new Traffic()).add(traffic);
                        connectionTotal.add(traffic);
                    });
                    if (connectionTotal.frames == 0 && connection.transport.writeCalls == 0 && connection.transport.flushCalls == 0) continue;

                    total.add(connectionTotal);
                    transport.add(connection.transport);
                    connectionReports.add(new ConnectionReport(this.players.get(connection.address), String.valueOf(connection.address),
                        connection.eventLoop, connection.transport.copy(), connectionTotal, connectionTypes));
                }
            }
            connectionReports.sort(Comparator.comparingLong((ConnectionReport connection) -> connection.transport.writeBytes).reversed());
            double seconds = elapsed / 1.0e9;
            return new Report(this.startedAt, seconds, this.durationTicks, this.durationNanos / 1.0e9, completed, this.compressionThreshold,
                this.playersAtStart, playersAtEnd, TickTimes.of(this.tickNanos, seconds), jvmStats, transport, total, packetTypes, connectionReports, cpu, virtualDisplayStats);
        }
    }

    private static final class ConnectionStats {
        private final SocketAddress address;
        private final String eventLoop;
        private final Map<PacketType<?>, Traffic> packetTypes = new HashMap<>();
        private final Transport transport = new Transport();

        private ConnectionStats(SocketAddress address, String eventLoop) {
            this.address = address;
            this.eventLoop = eventLoop;
        }
    }

    private record EventLoopStart(String name, long wallNanos, long cpuNanos) {}

    public record EventLoopCpu(String thread, long cpuNanos, long sampledWallNanos) {}

    public record Result(Report report, @Nullable Path path) {}

    public record Report(String startedAt, double wallSeconds, int requestedTicks, double requestedWallSeconds, boolean completed, int compressionThreshold,
                         int playersAtStart, int playersAtEnd, TickTimes ticks, JvmBenchmarkStats jvm, Transport transport, Traffic traffic, Map<String, Traffic> packetTypes,
                         List<ConnectionReport> connections, List<EventLoopCpu> eventLoopCpu, VirtualDisplaySender.Stats virtualDisplays) {}

    public record ConnectionReport(@Nullable String player, String remoteAddress, String eventLoop, Transport transport,
                                   Traffic traffic, Map<String, Traffic> packetTypes) {}

    public record TickTimes(int samples, double observedTps, double averageMspt, double p50Mspt, double p95Mspt, double p99Mspt,
                            double maxMspt, long ticksOver50Millis, long[] samplesNanos) {
        private static TickTimes of(LongArrayList samples, double seconds) {
            long[] values = samples.toLongArray();
            if (values.length == 0) return new TickTimes(0, 0, 0, 0, 0, 0, 0, 0, values);
            long[] chronological = values.clone();
            Arrays.sort(values);
            return new TickTimes(values.length, values.length / seconds, Arrays.stream(values).average().orElseThrow() / 1.0e6,
                percentileMs(values, 0.50), percentileMs(values, 0.95), percentileMs(values, 0.99), values[values.length - 1] / 1.0e6,
                Arrays.stream(values).filter(nanos -> nanos > 50_000_000L).count(), chronological);
        }

        private static double percentileMs(long[] sorted, double percentile) {
            return sorted[(int) Math.ceil(sorted.length * percentile) - 1] / 1.0e6;
        }
    }

    public static final class Traffic {
        private long frames;
        private long encodedBytes;
        private long compressionFrames;
        private long deflatedFrames;
        private long compressionBytes;
        private long framedBytes;
        private long encodeNanos;
        private long compressionNanos;
        private long deflatePathNanos;
        private long frameNanos;

        private void record(Frame frame, int bytes, long nanos) {
            this.frames++;
            this.encodedBytes += frame.encodedBytes;
            this.framedBytes += bytes;
            this.encodeNanos += frame.encodeNanos;
            this.frameNanos += nanos;
            if (frame.compressionEnabled) {
                this.compressionFrames++;
                this.compressionBytes += frame.compressionBytes;
                this.compressionNanos += frame.compressionNanos;
            }
            if (frame.deflated) {
                this.deflatedFrames++;
                this.deflatePathNanos += frame.compressionNanos;
            }
        }

        private void add(Traffic other) {
            this.frames += other.frames;
            this.encodedBytes += other.encodedBytes;
            this.compressionFrames += other.compressionFrames;
            this.deflatedFrames += other.deflatedFrames;
            this.compressionBytes += other.compressionBytes;
            this.framedBytes += other.framedBytes;
            this.encodeNanos += other.encodeNanos;
            this.compressionNanos += other.compressionNanos;
            this.deflatePathNanos += other.deflatePathNanos;
            this.frameNanos += other.frameNanos;
        }

        public long frames() { return this.frames; }
        public long deflatedFrames() { return this.deflatedFrames; }
        public long encodeNanos() { return this.encodeNanos; }
        public long compressionNanos() { return this.compressionNanos; }
        public long frameNanos() { return this.frameNanos; }
    }

    public static final class Transport {
        private long writeCalls;
        private long writeBytes;
        private long flushCalls;
        private long flushNanos;
        private long unwritableWrites;
        private long peakPendingWriteBytes;

        private void add(Transport other) {
            this.writeCalls += other.writeCalls;
            this.writeBytes += other.writeBytes;
            this.flushCalls += other.flushCalls;
            this.flushNanos += other.flushNanos;
            this.unwritableWrites += other.unwritableWrites;
            this.peakPendingWriteBytes = Math.max(this.peakPendingWriteBytes, other.peakPendingWriteBytes);
        }

        private Transport copy() {
            Transport snapshot = new Transport();
            snapshot.add(this);
            return snapshot;
        }

        public long writeBytes() { return this.writeBytes; }
        public long flushNanos() { return this.flushNanos; }
    }
}
