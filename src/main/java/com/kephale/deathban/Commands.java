package com.kephale.deathban;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class Commands {

    private Commands() {}

    public static void register(DeathBanMod mod) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(literal("deathban")
                    .requires(Commands::isAdmin)
                    .executes(ctx -> help(ctx.getSource()))
                    .then(literal("help").executes(ctx -> help(ctx.getSource())))

                    .then(literal("check")
                            .executes(ctx -> checkAll(ctx, mod))
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> checkOne(ctx, mod))))

                    .then(literal("revive")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> revive(ctx, mod))))

                    .then(literal("pardon")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> pardon(ctx, mod))))

                    .then(literal("revert")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> revert(ctx, mod))))

                    .then(literal("set")
                            .then(argument("name", StringArgumentType.word())
                                    .then(argument("count", IntegerArgumentType.integer(0))
                                            .executes(ctx -> setDeaths(ctx, mod)))))

                    .then(literal("revivedeaths")
                            .executes(ctx -> {
                                msg(ctx, "Token revives bring players back on " + mod.config.reviveDeaths + " deaths.");
                                return 1;
                            })
                            .then(argument("n", IntegerArgumentType.integer(0, 4))
                                    .executes(ctx -> {
                                        mod.config.reviveDeaths = IntegerArgumentType.getInteger(ctx, "n");
                                        mod.config.save();
                                        msg(ctx, "Token revives now bring players back on " + mod.config.reviveDeaths + " deaths.");
                                        return 1;
                                    })))

                    .then(literal("fakenick")
                            .then(argument("player", EntityArgumentType.player())
                                    .then(argument("nick", StringArgumentType.word())
                                            .executes(ctx -> fakeNick(ctx, mod)))))

                    .then(literal("revertnick")
                            .then(argument("player", EntityArgumentType.player())
                                    .executes(ctx -> revertNick(ctx, mod))))

                    .then(literal("item").executes(ctx -> giveToken(ctx, mod)))
                    .then(literal("debug").executes(ctx -> debug(ctx, mod)))
                    .then(literal("export").executes(ctx -> export(ctx, mod)))
                    .then(literal("import")
                            .then(argument("file", StringArgumentType.string())
                                    .executes(ctx -> importData(ctx, mod, false))
                                    .then(argument("overwrite", BoolArgumentType.bool())
                                            .executes(ctx -> importData(ctx, mod,
                                                    BoolArgumentType.getBool(ctx, "overwrite"))))))
                    .then(literal("reload").executes(ctx -> {
                        mod.config = ModConfig.load(mod.configDir);
                        mod.store.load();
                        mod.syncDeathMessageGameRule();
                        msg(ctx, "Config and player data reloaded.");
                        return 1;
                    }))

                    .then(literal("toggle")
                            .then(literal("deathban").then(argument("on", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        mod.config.deathBanEnabled = BoolArgumentType.getBool(ctx, "on");
                                        mod.config.save();
                                        msg(ctx, "Death bans " + onOff(mod.config.deathBanEnabled));
                                        return 1;
                                    })))
                            .then(literal("invisiblekillers").then(argument("on", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        mod.config.hideInvisibleKillers = BoolArgumentType.getBool(ctx, "on");
                                        mod.config.save();
                                        msg(ctx, "Hiding invisible killers " + onOff(mod.config.hideInvisibleKillers));
                                        return 1;
                                    })))
                            .then(literal("botmessages").then(argument("on", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        mod.config.hideBotConnectionMessages = BoolArgumentType.getBool(ctx, "on");
                                        mod.config.save();
                                        msg(ctx, "Hiding bot join/leave messages " + onOff(mod.config.hideBotConnectionMessages));
                                        return 1;
                                    })))
                            .then(literal("deathmessages").then(argument("on", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        mod.config.ownDeathMessages = BoolArgumentType.getBool(ctx, "on");
                                        mod.config.save();
                                        mod.syncDeathMessageGameRule();
                                        msg(ctx, "Custom death messages " + onOff(mod.config.ownDeathMessages));
                                        return 1;
                                    })))
                            .then(literal("pearlcatch").then(argument("on", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        mod.config.pearlCatchEnabled = BoolArgumentType.getBool(ctx, "on");
                                        mod.config.save();
                                        msg(ctx, "PearlCatch " + onOff(mod.config.pearlCatchEnabled));
                                        return 1;
                                    }))))

                    .then(literal("headchance")
                            .then(argument("chance", DoubleArgumentType.doubleArg(0.0, 1.0))
                                    .executes(ctx -> {
                                        mod.config.steveHeadChance = DoubleArgumentType.getDouble(ctx, "chance");
                                        mod.config.save();
                                        msg(ctx, "Steve head chance set to " + mod.config.steveHeadChance);
                                        return 1;
                                    })))
            );

            dispatcher.register(literal("nick")
                    .requires(Commands::isAdmin)
                    .then(argument("player", EntityArgumentType.player())
                            .then(argument("nick", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                        String nick = StringArgumentType.getString(ctx, "nick");
                                        String real = mod.nickCore.getRealName(t);
                                        mod.nickCore.nick(t, nick);
                                        msg(ctx, "§aNicked §f" + real + "§a as §f" + nick);
                                        return 1;
                                    }))));

            dispatcher.register(literal("unnick")
                    .requires(Commands::isAdmin)
                    .then(literal("all").executes(ctx -> {
                        int n = 0;
                        for (UUID id : mod.nickCore.nickedPlayers()) {
                            ServerPlayerEntity t = ctx.getSource().getServer().getPlayerManager().getPlayer(id);
                            if (t != null) { mod.nickCore.unnick(t); n++; }
                        }
                        msg(ctx, "§aCleared " + n + " nick(s).");
                        return 1;
                    }))
                    .then(argument("player", EntityArgumentType.player())
                            .executes(ctx -> {
                                ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                if (!mod.nickCore.isNicked(t.getUuid())) { msg(ctx, "§7Not nicked."); return 0; }
                                String real = mod.nickCore.getRealName(t);
                                mod.nickCore.unnick(t);
                                msg(ctx, "§aUn-nicked §f" + real);
                                return 1;
                            })));

            dispatcher.register(literal("realname")
                    .requires(Commands::isAdmin)
                    .then(argument("nick", StringArgumentType.word())
                            .executes(ctx -> {
                                String nick = StringArgumentType.getString(ctx, "nick");
                                for (UUID id : mod.nickCore.nickedPlayers()) {
                                    ServerPlayerEntity t = ctx.getSource().getServer().getPlayerManager().getPlayer(id);
                                    if (t != null && nick.equalsIgnoreCase(mod.nickCore.getNick(id))) {
                                        msg(ctx, "§f" + nick + " §7is really §b" + mod.nickCore.getRealName(t));
                                        return 1;
                                    }
                                }
                                msg(ctx, "§7No nicked player by that name.");
                                return 0;
                            })));

            dispatcher.register(literal("pearlcatch")
                    .requires(Commands::isAdmin)
                    .executes(ctx -> {
                        msg(ctx, "PearlCatch " + onOff(mod.config.pearlCatchEnabled)
                                + " | radius " + mod.config.pearlCollisionRadius
                                + " | delay " + mod.config.pearlDelayMinTicks + "-" + mod.config.pearlDelayMaxTicks
                                + " | taper " + mod.config.pearlDelayTaperDistance
                                + " | momentum " + mod.config.pearlMomentumKeep);
                        return 1;
                    })
                    .then(literal("on").executes(ctx -> {
                        mod.config.pearlCatchEnabled = true; mod.config.save();
                        msg(ctx, "PearlCatch ON"); return 1;
                    }))
                    .then(literal("off").executes(ctx -> {
                        mod.config.pearlCatchEnabled = false; mod.config.save();
                        msg(ctx, "PearlCatch OFF"); return 1;
                    }))
                    .then(literal("radius").then(argument("v", DoubleArgumentType.doubleArg(0.1, 8.0))
                            .executes(ctx -> {
                                mod.config.pearlCollisionRadius = DoubleArgumentType.getDouble(ctx, "v");
                                mod.config.save();
                                msg(ctx, "Collision radius " + mod.config.pearlCollisionRadius); return 1;
                            })))
                    .then(literal("delay")
                            .then(argument("min", IntegerArgumentType.integer(0, 40))
                                    .then(argument("max", IntegerArgumentType.integer(0, 40))
                                            .executes(ctx -> {
                                                mod.config.pearlDelayMinTicks = IntegerArgumentType.getInteger(ctx, "min");
                                                mod.config.pearlDelayMaxTicks = IntegerArgumentType.getInteger(ctx, "max");
                                                mod.config.save();
                                                msg(ctx, "Catch delay " + mod.config.pearlDelayMinTicks
                                                        + "-" + mod.config.pearlDelayMaxTicks + " ticks"); return 1;
                                            }))))
                    .then(literal("momentum").then(argument("v", DoubleArgumentType.doubleArg(0.0, 2.0))
                            .executes(ctx -> {
                                mod.config.pearlMomentumKeep = DoubleArgumentType.getDouble(ctx, "v");
                                mod.config.save();
                                msg(ctx, "Momentum keep " + mod.config.pearlMomentumKeep); return 1;
                            })))
                    .then(literal("sound").then(argument("on", BoolArgumentType.bool())
                            .executes(ctx -> {
                                mod.config.pearlPlaySound = BoolArgumentType.getBool(ctx, "on");
                                mod.config.save();
                                msg(ctx, "Catch sound " + onOff(mod.config.pearlPlaySound)); return 1;
                            }))));
        });
    }

    private static int help(ServerCommandSource src) {
        String[] lines = {
                "§6=== DeathBan ===",
                "§71. §e/deathban check [player] §7- death counts",
                "§72. §e/deathban revive <player> §7- unban, reset to 0",
                "§73. §e/deathban pardon <player> §7- clear timer, keep count",
                "§74. §e/deathban revert <player> §7- pardon + subtract 1",
                "§75. §e/deathban set <player> <n> §7- set a count",
                "§76. §e/deathban revivedeaths <n> §7- token revive start count",
                "§77. §e/deathban headchance <0-1> §7- head drop chance",
                "§78. §e/deathban item §7- give yourself a Revive Token",
                "§79. §e/deathban debug §7- version and live state",
                "§710. §e/deathban toggle <deathban|invisiblekillers|deathmessages|botmessages|pearlcatch> <true|false>",
                "§6=== Nick ===",
                "§711. §e/nick <player> <nick> §7- change name and skin",
                "§712. §e/unnick <player> §7| §eall §7- remove nick(s)",
                "§713. §e/realname <nick> §7- who is behind a nick",
                "§714. §e/deathban fakenick <player> <nick> §7- fake death curve on a nick",
                "§715. §e/deathban revertnick <player> §7- end fake mode and un-nick",
                "§6=== Data ===",
                "§716. §e/deathban export §7- write a timestamped JSON copy",
                "§717. §e/deathban import <file> [overwrite] §7- load players.yml or .json",
                "§718. §e/deathban reload §7- re-read config and data",
                "§6=== PearlCatch ===",
                "§719. §e/pearlcatch §7- current settings",
                "§720. §e/pearlcatch on|off §7- enable or disable",
