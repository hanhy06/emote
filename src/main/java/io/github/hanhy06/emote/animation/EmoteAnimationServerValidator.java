package io.github.hanhy06.emote.animation;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.ParseResults;
import com.mojang.serialization.JsonOps;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

public final class EmoteAnimationServerValidator {
    private final EmoteAnimationComplexityValidator complexityValidator = new EmoteAnimationComplexityValidator();

    public Loaded prepare(Loaded loaded) throws EmoteAnimationLoadException {
        Objects.requireNonNull(loaded, "loaded");
        MinecraftServer server = Emote.SERVER;
        Path sourcePath = loaded.sourcePath();
        this.complexityValidator.validate(loaded);

        var nbtOps = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var jsonOps = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Map<String, PreparedDisplayData> preparedDisplayData = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : loaded.animation().nodes().entrySet()) {
            String path = "$.nodes." + entry.getKey();
            try {
                if (entry.getValue() instanceof ItemNode itemNode) {
                    ItemStack itemStack = ItemStack.CODEC.parse(nbtOps, itemNode.itemStackNbt()).getOrThrow();
                    validateSkinTarget(sourcePath, path, itemNode, itemStack);
                    ItemDisplayContext itemDisplay = ItemDisplayContext.CODEC.parse(
                        JsonOps.INSTANCE,
                        new JsonPrimitive(itemNode.itemDisplay())
                    ).getOrThrow();
                    preparedDisplayData.put(entry.getKey(), new PreparedItemData(itemStack, itemDisplay));
                } else if (entry.getValue() instanceof BlockNode blockNode) {
                    BlockState blockState = BlockState.CODEC.parse(nbtOps, blockNode.blockStateNbt()).getOrThrow();
                    preparedDisplayData.put(entry.getKey(), new PreparedBlockData(blockState));
                } else if (entry.getValue() instanceof TextNode textNode) {
                    var text = ComponentSerialization.CODEC.parse(jsonOps, textNode.text()).getOrThrow();
                    preparedDisplayData.put(entry.getKey(), new PreparedTextData(text));
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
        return new Loaded(loaded.sourcePath(), loaded.sha256(), loaded.animation(), preparedDisplayData);
    }

    static void validateSkinTarget(Path sourcePath, String nodePath, ItemNode itemNode, ItemStack itemStack)
        throws EmoteAnimationLoadException {
        if (itemNode.skin() != null && !itemStack.is(Items.PLAYER_HEAD)) {
            throw new EmoteAnimationLoadException(
                sourcePath,
                nodePath + ".skin",
                "requires item_stack_snbt to contain a player head"
            );
        }
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
