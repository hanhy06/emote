package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.application.EmotePlayService;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.playback.PlaybackSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class UserCommand {
    private final PlaybackManager playbackManager;
    private final EmoteMenu menu;
    private final EmoteQueryService emoteQueryService;
    private final EmotePlayService playService;

    public UserCommand(
        PlaybackManager playbackManager,
        EmoteMenu menu,
        EmoteQueryService emoteQueryService,
        EmotePlayService playService
    ) {
        this.playbackManager = playbackManager;
        this.menu = menu;
        this.emoteQueryService = emoteQueryService;
        this.playService = playService;
    }

    LiteralArgumentBuilder<CommandSourceStack> createRoot() {
        return Commands.literal("emote")
            .executes(context -> openMenu(context.getSource()))
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(context -> openMenu(
                    context.getSource(),
                    IntegerArgumentType.getInteger(context, "page")
                )))
            .then(createSearchCommand())
            .then(createPlayCommand())
            .then(createStopCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> createSearchCommand() {
        return Commands.literal("search")
            .executes(context -> openSearch(context.getSource()))
            .then(Commands.argument("query", StringArgumentType.string())
                .executes(context -> openMenu(
                    context.getSource(),
                    1,
                    StringArgumentType.getString(context, "query")
                ))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(context -> openMenu(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "page"),
                        StringArgumentType.getString(context, "query")
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createPlayCommand() {
        return Commands.literal("play")
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                    getSuggestedPlayIds(context.getSource()),
                    builder
                ))
                .executes(this::play));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createStopCommand() {
        return Commands.literal("stop")
            .executes(context -> stop(context.getSource()));
    }

    private List<String> getSuggestedPlayIds(CommandSourceStack source) {
        ServerPlayer player = findPlayer(source);
        return player == null
            ? this.emoteQueryService.getAllIds()
            : this.emoteQueryService.getPlayableIds(player);
    }

    private int openMenu(CommandSourceStack source) throws CommandSyntaxException {
        return openMenu(source, 1);
    }

    private int openMenu(
        CommandSourceStack source,
        int pageNumber
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        this.menu.open(player, pageNumber);
        return 1;
    }

    private int openSearch(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        this.menu.openSearch(player);
        return 1;
    }

    private int openMenu(
        CommandSourceStack source,
        int pageNumber,
        String query
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        this.menu.open(player, pageNumber, query);
        return 1;
    }

    private static int applyPlayResult(CommandSourceStack source, PlayResult playResult) {
        if (!playResult.isSuccess()) {
            source.sendFailure(playResult.errorMessage());
            return 0;
        }

        return 1;
    }

    private int play(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        return applyPlayResult(
            source,
            this.playService.play(player, IdentifierArgument.getId(context, "id").toString(), PlaySource.COMMAND)
        );
    }

    private int stop(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlaybackSession session = this.playbackManager.stop(player);
        if (session == null) {
            source.sendFailure(Component.literal("No active emote."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Stop: " + session.id()), false);
        return 1;
    }

    private static ServerPlayer findPlayer(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
