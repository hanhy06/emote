package io.github.hanhy06.emote.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.application.EmotePlayService;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.IntegerArgument;
import net.minecraft.commands.arguments.StringArgument;
import net.minecraft.commands.suggestions.SuggestionContext;
import net.minecraft.commands.suggestions.SuggestionProviders;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class UserCommand {
    private final PlayerPlaybackManager playerPlaybackManager;
    private final EmoteMenu menu;
    private final EmoteQueryService emoteQueryService;
    private final EmotePlayService playService;

    public UserCommand(
        PlayerPlaybackManager playerPlaybackManager,
        EmoteMenu menu,
        EmoteQueryService emoteQueryService,
        EmotePlayService playService
    ) {
        this.playerPlaybackManager = playerPlaybackManager;
        this.menu = menu;
        this.emoteQueryService = emoteQueryService;
        this.playService = playService;
    }

    public LiteralArgumentBuilder<CommandSourceStack> createRoot() {
        return Commands.literal("emote")
            .executes(context -> openMenu(context.getSource(), 1))
            .then(Commands.argument("page", IntegerArgument.integer(1))
                .executes(context -> openMenu(
                    context.getSource(),
                    IntegerArgument.getInteger(context, "page")
                )))
            .then(createSearchCommand())
            .then(createPlayCommand())
            .then(createStopCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> createSearchCommand() {
        return Commands.literal("search")
            .executes(context -> openSearch(context.getSource()))
            .then(Commands.argument("query", StringArgument.string())
                .executes(context -> openMenu(
                    context.getSource(),
                    1,
                    StringArgument.getString(context, "query")
                ))
                .then(Commands.argument("page", IntegerArgument.integer(1))
                    .executes(context -> openMenu(
                        context.getSource(),
                        IntegerArgument.getInteger(context, "page"),
                        StringArgument.getString(context, "query")
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createPlayCommand() {
        return Commands.literal("play")
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests(SuggestionProviders.serverOnly((_, context, builder) -> SuggestionContext.suggestResource(
                    getSuggestedPlayIds(context.getSource()).stream().map(Identifier::parse), builder
                )))
                .executes(this::play));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createStopCommand() {
        return Commands.literal("stop")
            .executes(context -> stop(context.getSource()));
    }

    private List<String> getSuggestedPlayIds(SuggestionContext source) {
        return source.entity() instanceof ServerPlayer player
            ? this.emoteQueryService.getPlayableIds(player)
            : this.emoteQueryService.getAllIds();
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

    private int play(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        PlayResult result = this.playService.play(player, IdentifierArgument.getId(context, "id").toString(), PlaySource.COMMAND);
        switch (result) {
            case PlayResult.Failure(var errorMessage) -> {
                source.sendFailure(errorMessage);
                return 0;
            }
            case PlayResult.Success(_) -> {}
        }
        return 1;
    }

    private int stop(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return this.playerPlaybackManager.stop(player) == null ? 0 : 1;
    }

}
