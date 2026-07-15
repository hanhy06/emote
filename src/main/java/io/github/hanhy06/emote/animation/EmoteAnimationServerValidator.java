package io.github.hanhy06.emote.animation;

import com.mojang.brigadier.ParseResults;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.hanhy06.emote.animation.EmoteAnimation.*;

public final class EmoteAnimationServerValidator {
    public void validate(Loaded loaded, MinecraftServer server) throws EmoteAnimationLoadException {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(server, "server");
        Path sourcePath = loaded.sourcePath();

        var nbtOps = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var jsonOps = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (Map.Entry<String, Node> entry : loaded.animation().nodes().entrySet()) {
            String path = "$.nodes." + entry.getKey();
            try {
                if (entry.getValue() instanceof ItemNode itemNode) {
                    ItemStack.CODEC.parse(nbtOps, itemNode.itemStackNbt()).getOrThrow();
                } else if (entry.getValue() instanceof BlockNode blockNode) {
                    BlockState.CODEC.parse(nbtOps, blockNode.blockStateNbt()).getOrThrow();
                } else if (entry.getValue() instanceof TextNode textNode) {
                    ComponentSerialization.CODEC.parse(jsonOps, textNode.text()).getOrThrow();
                }
            } catch (RuntimeException exception) {
                String field = entry.getValue() instanceof ItemNode
                    ? "item_stack_snbt"
                    : entry.getValue() instanceof BlockNode ? "block_state_snbt" : "text";
                throw new EmoteAnimationLoadException(
                    sourcePath,
                    path + "." + field,
                    "value is not valid for Minecraft " + server.getServerVersion(),
                    exception
                );
            }
        }

        CommandSourceStack validationSource = server.createCommandSourceStack()
            .withPermission(LevelBasedPermissionSet.OWNER)
            .withSuppressedOutput();
        Events events = loaded.animation().timeline().events();
        validateEvents(events.start(), "$.timeline.events.start", validationSource, server, sourcePath);
        validateTimelineEvents(events.timeline(), validationSource, server, sourcePath);
        validateEvents(events.loop(), "$.timeline.events.loop", validationSource, server, sourcePath);
        validateEvents(events.stop(), "$.timeline.events.stop", validationSource, server, sourcePath);
    }

    private void validateEvents(
        List<Event> events,
        String path,
        CommandSourceStack source,
        MinecraftServer server,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            validateCommands(events.get(eventIndex).commands(), path + "[" + eventIndex + "]", source, server, sourcePath);
        }
    }

    private void validateTimelineEvents(
        List<TimelineEvent> events,
        CommandSourceStack source,
        MinecraftServer server,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            validateCommands(
                events.get(eventIndex).commands(),
                "$.timeline.events.timeline[" + eventIndex + "]",
                source,
                server,
                sourcePath
            );
        }
    }

    private void validateCommands(
        List<String> commands,
        String eventPath,
        CommandSourceStack source,
        MinecraftServer server,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
            String command = commands.get(commandIndex);
            try {
                ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);
                Commands.validateParseResults(parsed);
            } catch (Exception exception) {
                throw new EmoteAnimationLoadException(
                    sourcePath,
                    eventPath + ".commands[" + commandIndex + "]",
                    "command does not parse",
                    exception
                );
            }
        }
    }
}
