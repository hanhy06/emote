package io.github.hanhy06.emote.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

public final class CommandRegistrar {
    private final UserCommand userCommand;
    private final AdminCommand adminCommand;

    public CommandRegistrar(UserCommand userCommand, AdminCommand adminCommand) {
        this.userCommand = userCommand;
        this.adminCommand = adminCommand;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = this.userCommand.createRoot();
            this.adminCommand.attachTo(root);
            dispatcher.register(root);
        });
    }
}
