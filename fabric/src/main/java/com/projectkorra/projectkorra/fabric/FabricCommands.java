package com.projectkorra.projectkorra.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.projectkorra.projectkorra.command.BendingTabComplete;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.command.Command;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Arrays;

final class FabricCommands {
    private FabricCommands() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (String alias : Commands.commandaliases) {
                LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal(alias)
                        .executes(context -> execute(context.getSource(), alias, ""));
                root.then(CommandManager.argument("arguments", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            CommandSender sender = sender(context.getSource());
                            String input = builder.getRemaining();
                            String[] args = input.isEmpty() ? new String[]{""} : input.split(" ", -1);
                            for (String suggestion : new BendingTabComplete().onTabComplete(sender, new Command(), alias, args)) {
                                builder.suggest(suggestion);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> execute(context.getSource(), alias, StringArgumentType.getString(context, "arguments"))));
                dispatcher.register(root);
            }
        });
    }

    private static int execute(ServerCommandSource source, String alias, String input) {
        String[] args = input.isBlank() ? new String[0] : Arrays.stream(input.trim().split("\\s+")).filter(part -> !part.isEmpty()).toArray(String[]::new);
        Commands.dispatch(sender(source), alias, args);
        return 1;
    }

    private static CommandSender sender(ServerCommandSource source) {
        if (source.getPlayer() != null) return FabricMC.player(source.getPlayer());
        return new CommandSender() {
            @Override public String getName() { return source.getName(); }
            @Override public void sendMessage(String message) { source.sendFeedback(() -> Text.literal(message), false); }
            @Override public boolean hasPermission(String permission) { return true; }
        };
    }
}
