package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.skin.account.MinecraftAccountManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;

public final class AccountCommand {
    private final MinecraftAccountManager accounts;

    public AccountCommand(MinecraftAccountManager accounts) {
        this.accounts = accounts;
    }

    public LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("account")
            .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
            .executes(context -> list(context.getSource()))
            .then(Commands.literal("login")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> login(context.getSource())))
            .then(Commands.literal("remove")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("account", StringArgumentType.word())
                    .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                    .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "account")))));
    }

    private int list(CommandSourceStack source) {
        if (!isOwner(source)) return 0;
        if (this.accounts.storageError() != null) {
            source.sendFailure(Component.literal(this.accounts.storageError()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Skin provider: " + (this.accounts.hasAccounts() ? "account" : "MineSkin")), false);
        for (var account : this.accounts.accounts()) {
            source.sendSuccess(() -> Component.literal(account.name() + " (" + account.uuid() + ") - "
                + (account.needsLogin() ? "login required" : "registered")), false);
        }
        return 1;
    }

    private int login(CommandSourceStack source) {
        if (!isOwner(source)) return 0;
        try {
            this.accounts.login(login -> source.getServer().execute(() -> {
                if (!canReceive(source)) return;
                Component link = Component.literal("[Microsoft login]").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(login.verificationUri())));
                Component code = Component.literal(login.userCode()).withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent.CopyToClipboard(login.userCode())));
                source.sendSuccess(() -> Component.literal("Account login: ").append(link).append("  Code: ").append(code), false);
            }), message -> source.getServer().execute(() -> {
                if (canReceive(source)) source.sendSuccess(() -> Component.literal(message), false);
            }), () -> source.getServer().submit(() -> canReceive(source)).join());
            return 1;
        } catch (IOException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private int remove(CommandSourceStack source, String account) {
        if (!isOwner(source)) return 0;
        try {
            if (!this.accounts.remove(account)) {
                source.sendFailure(Component.literal("No matching bake account"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Bake account removed. Any pending login was canceled."), false);
            return 1;
        } catch (IOException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static boolean isOwner(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_OWNERS).test(source);
    }

    private static boolean canReceive(CommandSourceStack source) {
        if (source.isPlayer()) {
            var player = source.getPlayer();
            return player != null && source.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && isOwner(player.createCommandSourceStack());
        }
        return isOwner(source);
    }
}
