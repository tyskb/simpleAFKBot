package com.afkbot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AfkBotMod implements ModInitializer {

    public static final String MOD_ID = "afk-bot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static boolean isOP(CommandSourceStack source) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    @Override
    public void onInitialize() {
        LOGGER.info("AFK Bot Mod loaded!");

        // Tick event — checks for dead bots and auto-removes after 5 min empty
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BotManager.tick(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(Commands.literal("bot")

                // /bot spawn <name>
                .then(Commands.literal("spawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            String botName = StringArgumentType.getString(context, "name");

                            ServerPlayer caller = source.getPlayer();
                            if (caller == null) {
                                source.sendSuccess(() -> Component.literal("\u00a7cThis command can only be used by players."), false);
                                return 0;
                            }

                            if (BotManager.hasBot(botName)) {
                                source.sendSuccess(() -> Component.literal("\u00a7cBot '" + botName + "' already exists!"), false);
                                return 0;
                            }

                            // Check limits (OPs bypass)
                            if (!isOP(source)) {
                                int playerBots = BotManager.countBotsOwnedBy(caller.getUUID());
                                if (playerBots >= BotManager.getMaxBotsPerPlayer()) {
                                    int max = BotManager.getMaxBotsPerPlayer();
                                    source.sendSuccess(() -> Component.literal("\u00a7cYou already have " + max + " bot(s)! Remove one first."), false);
                                    return 0;
                                }
                                if (BotManager.getTotalBotCount() >= BotManager.getMaxBotsTotal()) {
                                    int max = BotManager.getMaxBotsTotal();
                                    source.sendSuccess(() -> Component.literal("\u00a7cServer bot limit reached (" + max + ")! Ask an admin or remove a bot."), false);
                                    return 0;
                                }
                            }

                            boolean success = BotManager.spawnBot(
                                source.getServer(), botName,
                                source.getPosition(), source.getLevel(),
                                caller.getUUID(), caller.getGameProfile().name()
                            );

                            if (success) {
                                source.sendSuccess(() -> Component.literal("\u00a7aSpawned bot: " + botName), true);
                            } else {
                                source.sendSuccess(() -> Component.literal("\u00a7cFailed to spawn bot: " + botName), false);
                            }
                            return success ? 1 : 0;
                        })
                    )
                )

                // /bot remove <name>
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            String botName = StringArgumentType.getString(context, "name");

                            if (!BotManager.hasBot(botName)) {
                                source.sendSuccess(() -> Component.literal("\u00a7cNo bot found with name: " + botName), false);
                                return 0;
                            }

                            ServerPlayer caller = source.getPlayer();
                            if (caller != null && !isOP(source) && !BotManager.isOwnedBy(botName, caller.getUUID())) {
                                source.sendSuccess(() -> Component.literal("\u00a7cYou don't own that bot!"), false);
                                return 0;
                            }

                            boolean success = BotManager.removeBot(source.getServer(), botName);
                            if (success) {
                                source.sendSuccess(() -> Component.literal("\u00a7eRemoved bot: " + botName), true);
                            }
                            return success ? 1 : 0;
                        })
                    )
                )

                // /bot list
                .then(Commands.literal("list")
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        var bots = BotManager.getBotNames();

                        if (bots.isEmpty()) {
                            source.sendSuccess(() -> Component.literal("\u00a77No active bots."), false);
                        } else {
                            int max = BotManager.getMaxBotsTotal();
                            source.sendSuccess(() -> Component.literal(
                                "\u00a7bActive bots (" + bots.size() + "/" + max + "): \u00a7f" + String.join(", ", bots)
                            ), false);
                        }
                        return 1;
                    })
                )

                // /bot removeall — OP only
                .then(Commands.literal("removeall")
                    .requires(source -> isOP(source))
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        int count = BotManager.removeAllBots(source.getServer());
                        source.sendSuccess(() -> Component.literal("\u00a7eRemoved " + count + " bot(s)."), true);
                        return count;
                    })
                )

                // /bot config — OP only, view or change settings
                .then(Commands.literal("config")
                    .requires(source -> isOP(source))

                    // /bot config — show current settings
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        int perPlayer = BotManager.getMaxBotsPerPlayer();
                        int total = BotManager.getMaxBotsTotal();
                        source.sendSuccess(() -> Component.literal(
                            "\u00a7b--- Bot Config ---\n" +
                            "\u00a77Max per player: \u00a7f" + perPlayer + "\n" +
                            "\u00a77Max total: \u00a7f" + total
                        ), false);
                        return 1;
                    })

                    // /bot config maxPerPlayer <number>
                    .then(Commands.literal("maxPerPlayer")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 100))
                            .executes(context -> {
                                CommandSourceStack source = context.getSource();
                                int value = IntegerArgumentType.getInteger(context, "value");
                                BotManager.setMaxBotsPerPlayer(value);
                                source.sendSuccess(() -> Component.literal("\u00a7aMax bots per player set to: " + value), true);
                                return 1;
                            })
                        )
                    )

                    // /bot config maxTotal <number>
                    .then(Commands.literal("maxTotal")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 100))
                            .executes(context -> {
                                CommandSourceStack source = context.getSource();
                                int value = IntegerArgumentType.getInteger(context, "value");
                                BotManager.setMaxBotsTotal(value);
                                source.sendSuccess(() -> Component.literal("\u00a7aMax total bots set to: " + value), true);
                                return 1;
                            })
                        )
                    )
                )
            );
        });
    }
}
