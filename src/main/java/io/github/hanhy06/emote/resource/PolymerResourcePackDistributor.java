package io.github.hanhy06.emote.resource;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackCreator;
import eu.pb4.polymer.resourcepack.api.ResourcePackStatusConsumer;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public final class PolymerResourcePackDistributor {
    private static final Identifier PACK_ID = Identifier.fromNamespaceAndPath(EmoteMod.MOD_ID, "resources");
    private static final UUID PACK_UUID = UUID.nameUUIDFromBytes(PACK_ID.toString().getBytes(StandardCharsets.UTF_8));

    private final ConfigManager configManager;
    private final ResourcePackContributor contributor;
    private final Path outputPath;
    private final PackCompiler packCompiler;
    private volatile PublishedPack publishedPack;

    public PolymerResourcePackDistributor(ConfigManager configManager) {
        this(
            configManager,
            PolymerResourcePackUtils.getMainPath().resolveSibling(EmoteMod.MOD_ID + "_resource_pack.zip"),
            true,
            null
        );
    }

    PolymerResourcePackDistributor(
        ConfigManager configManager,
        Path outputPath,
        boolean registerHosting,
        PackCompiler packCompiler
    ) {
        this.configManager = configManager;
        this.contributor = new ResourcePackContributor();
        this.outputPath = outputPath;
        this.packCompiler = packCompiler == null ? this::compilePack : packCompiler;

        if (registerHosting) {
            AutoHostUtils.registerHostedFile(PACK_ID, outputPath);
            AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
                PublishedPack published = this.publishedPack;
                if (published != null) {
                    consumer.accept(provider.createProperties(context, PACK_UUID, PACK_ID, published.result().hash()));
                }
            });
        }
    }

    public BuildResult rebuild() {
        ResourcePackContributor.Snapshot snapshot;
        try {
            this.configManager.configureResourcePack();
            snapshot = this.contributor.read(this.configManager.getResourcePackDirectory());
            PublishedPack published = this.publishedPack;
            if ((published == null && snapshot.isEmpty())
                || (published != null && snapshot.hasSameContent(published.snapshot()))) {
                EmoteMod.LOGGER.info("Skipping emote resource pack rebuild because resources are unchanged");
                return BuildResult.UNCHANGED;
            }
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to inspect emote resource inputs", exception);
            return BuildResult.FAILED;
        }

        Path stagingPath;
        try {
            Files.createDirectories(this.outputPath.getParent());
            stagingPath = Files.createTempFile(this.outputPath.getParent(), "emote-resource-pack-", ".zip");
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to prepare the emote resource pack build", exception);
            return BuildResult.FAILED;
        }

        try {
            OutputGenerator.Result staged = this.packCompiler.compile(snapshot, stagingPath);
            if (staged == null || staged.hadIssues()) {
                EmoteMod.LOGGER.warn("Failed to complete the emote resource pack build; keeping the previous pack");
                return BuildResult.FAILED;
            }

            publish(stagingPath);
            OutputGenerator.Result result = new OutputGenerator.Result(this.outputPath, staged.hash(), false);
            this.publishedPack = new PublishedPack(snapshot, result);
            return BuildResult.BUILT;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            EmoteMod.LOGGER.warn("Interrupted while building the emote resource pack");
        } catch (ExecutionException exception) {
            EmoteMod.LOGGER.warn("Failed to build the emote resource pack", exception.getCause());
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to publish the emote resource pack; keeping the previous pack", exception);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to build the emote resource pack", exception);
        } finally {
            try {
                Files.deleteIfExists(stagingPath);
            } catch (IOException exception) {
                EmoteMod.LOGGER.warn("Failed to remove the temporary emote resource pack", exception);
            }
        }
        return BuildResult.FAILED;
    }

    private OutputGenerator.Result compilePack(ResourcePackContributor.Snapshot snapshot, Path stagingPath)
        throws ExecutionException, InterruptedException {
        ResourcePackCreator creator = ResourcePackCreator.create();
        creator.creationEvent.register(builder -> {
            try {
                int resourceCount = this.contributor.addTo(snapshot, builder);
                EmoteMod.LOGGER.info("Added {} resources to the emote resource pack", resourceCount);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        return creator.build(OutputGenerator.zipGenerator(stagingPath), ResourcePackStatusConsumer.nonLogging());
    }

    private void publish(Path stagingPath) throws IOException {
        try {
            Files.move(stagingPath, this.outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(stagingPath, this.outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void pushToOnlinePlayers() {
        PublishedPack published = this.publishedPack;
        if (published == null) {
            return;
        }

        ResourcePackDataProvider provider = ResourcePackDataProvider.getActive();
        for (var player : EmoteMod.SERVER.getPlayerList().getPlayers()) {
            var context = player.connection.getPacketContext();
            if (!provider.isReady(context)) {
                continue;
            }
            var pack = provider.createProperties(context, PACK_UUID, PACK_ID, published.result().hash());
            player.connection.send(new ClientboundResourcePackPushPacket(
                pack.id(),
                pack.url(),
                pack.hash(),
                pack.isRequired(),
                Optional.ofNullable(pack.prompt())
            ));
        }
    }

    private record PublishedPack(ResourcePackContributor.Snapshot snapshot, OutputGenerator.Result result) {
    }

    @FunctionalInterface
    interface PackCompiler {
        OutputGenerator.Result compile(ResourcePackContributor.Snapshot snapshot, Path stagingPath)
            throws IOException, ExecutionException, InterruptedException;
    }

    public enum BuildResult {
        UNCHANGED,
        BUILT,
        FAILED
    }
}
