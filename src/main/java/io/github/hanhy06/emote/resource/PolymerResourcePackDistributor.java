package io.github.hanhy06.emote.resource;

import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.api.ResourcePackStatusConsumer;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public final class PolymerResourcePackDistributor {
    private final ConfigManager configManager;
    private final ResourcePackContributor contributor;
    private volatile ResourcePackContributor.Snapshot lastBuiltSnapshot;
    private volatile OutputGenerator.Result lastSuccessfulResult;
    private volatile ResourcePackContributor.Snapshot preparedSnapshot;
    private volatile ResourcePackContributor.Snapshot buildingSnapshot;
    private volatile ResourcePackContributor.Snapshot completedSnapshot;
    private volatile OutputGenerator.Result completedResult;

    public PolymerResourcePackDistributor(ConfigManager configManager) {
        this.configManager = configManager;
        this.contributor = new ResourcePackContributor();

        PolymerResourcePackUtils.RESOURCE_PACK_INITIALIZED_EVENT.register(() -> {
            this.buildingSnapshot = null;
            this.completedSnapshot = null;
            this.completedResult = null;
        });
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(this::addEmoteResources);
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(this::finishResourcePackBuild);
    }

    public BuildResult rebuild() {
        ResourcePackContributor.Snapshot snapshot;
        try {
            snapshot = this.contributor.read(this.configManager.getResourcePackDirectory());
            ResourcePackContributor.Snapshot lastBuilt = this.lastBuiltSnapshot;
            if ((lastBuilt == null && snapshot.isEmpty())
                || (lastBuilt != null && snapshot.hasSameContent(lastBuilt))) {
                EmoteMod.LOGGER.info("Skipping Polymer resource pack rebuild because emote resources are unchanged");
                return BuildResult.UNCHANGED;
            }
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to inspect emote resource inputs", exception);
            return BuildResult.FAILED;
        }

        Path mainPath = PolymerResourcePackUtils.getMainPath();
        Path stagingPath;
        try {
            Files.createDirectories(mainPath.getParent());
            stagingPath = Files.createTempFile(mainPath.getParent(), "emote-resource-pack-", ".zip");
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to prepare the emote resource pack build", exception);
            return BuildResult.FAILED;
        }

        BuildResult buildResult = BuildResult.FAILED;
        boolean buildStarted = false;
        try {
            this.preparedSnapshot = snapshot;
            buildStarted = true;
            OutputGenerator.Result result = PolymerResourcePackUtils.getInstance().build(
                transactionalZipGenerator(stagingPath, mainPath),
                ResourcePackStatusConsumer.nonLogging()
            );
            if (result == null
                || result.hadIssues()
                || !snapshot.hasSameContent(this.completedSnapshot)
                || !result.equals(this.completedResult)) {
                EmoteMod.LOGGER.warn("Failed to complete the emote resource pack build");
            } else {
                this.lastBuiltSnapshot = snapshot;
                this.lastSuccessfulResult = result;
                buildResult = BuildResult.BUILT;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            EmoteMod.LOGGER.warn("Interrupted while building the emote resource pack");
        } catch (ExecutionException exception) {
            EmoteMod.LOGGER.warn("Failed to build the emote resource pack", exception.getCause());
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to build the emote resource pack", exception);
        } finally {
            this.preparedSnapshot = null;
            this.buildingSnapshot = null;
            this.completedSnapshot = null;
            this.completedResult = null;
            try {
                Files.deleteIfExists(stagingPath);
            } catch (IOException exception) {
                EmoteMod.LOGGER.warn("Failed to remove the temporary emote resource pack", exception);
            }
        }

        if (buildResult == BuildResult.FAILED && buildStarted) {
            restoreLastSuccessfulResult();
        }
        return buildResult;
    }

    private OutputGenerator<OutputGenerator.Result> transactionalZipGenerator(Path stagingPath, Path mainPath) {
        return (resources, converter, status) -> {
            if (this.buildingSnapshot == null) {
                return null;
            }
            OutputGenerator.Result staged = OutputGenerator.zipGenerator(stagingPath).generateFile(resources, converter, status);
            if (staged == null || staged.hadIssues()) {
                return null;
            }
            try {
                Files.move(stagingPath, mainPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new OutputGenerator.Result(mainPath, staged.hash(), false);
            } catch (AtomicMoveNotSupportedException exception) {
                EmoteMod.LOGGER.warn("Failed to publish the emote resource pack atomically", exception);
            } catch (IOException exception) {
                EmoteMod.LOGGER.warn("Failed to publish the emote resource pack", exception);
            }
            return null;
        };
    }

    private void restoreLastSuccessfulResult() {
        OutputGenerator.Result result = this.lastSuccessfulResult;
        if (result != null) {
            PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.invoker().accept(result);
        }
    }

    private void addEmoteResources(ResourcePackBuilder builder) {
        try {
            this.configManager.configureResourcePack();
            ResourcePackContributor.Snapshot snapshot = this.preparedSnapshot;
            if (snapshot == null) {
                snapshot = this.contributor.read(this.configManager.getResourcePackDirectory());
            }
            int resourceCount = this.contributor.addTo(snapshot, builder);
            this.buildingSnapshot = snapshot;
            EmoteMod.LOGGER.info("Added {} emote resources to the Polymer resource pack", resourceCount);
        } catch (IOException exception) {
            this.buildingSnapshot = null;
            EmoteMod.LOGGER.warn("Failed to add emote resources to the Polymer resource pack", exception);
        }
    }

    private void finishResourcePackBuild(Object output) {
        ResourcePackContributor.Snapshot snapshot = this.buildingSnapshot;
        this.buildingSnapshot = null;
        if (output instanceof OutputGenerator.Result result && !result.hadIssues() && snapshot != null) {
            if (this.preparedSnapshot == null) {
                this.lastBuiltSnapshot = snapshot;
                this.lastSuccessfulResult = result;
            } else {
                this.completedSnapshot = snapshot;
                this.completedResult = result;
            }
        }
    }

    public void pushToOnlinePlayers() {
        ResourcePackDataProvider provider = ResourcePackDataProvider.getActive();
        for (var player : EmoteMod.SERVER.getPlayerList().getPlayers()) {
            var context = player.connection.getPacketContext();
            if (!provider.isReady(context)) {
                continue;
            }
            for (var pack : provider.getProperties(context)) {
                player.connection.send(new ClientboundResourcePackPushPacket(
                    pack.id(),
                    pack.url(),
                    pack.hash(),
                    pack.isRequired(),
                    Optional.ofNullable(pack.prompt())
                ));
            }
        }
    }

    public enum BuildResult {
        UNCHANGED,
        BUILT,
        FAILED
    }
}
