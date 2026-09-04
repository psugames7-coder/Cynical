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

/** All commands. Op level 2 required throughout. */
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

                    // ---- data transfer ----
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

            // ---------------- nick ----------------
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

            // ---------------- pearl catch tuning ----------------
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

    // ---------- implementations ----------

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
                "§79. §e/deathban toggle <deathban|invisiblekillers|deathmessages|botmessages|pearlcatch> <true|false>",
                "§6=== Nick ===",
                "§710. §e/nick <player> <nick> §7- change name and skin",
                "§711. §e/unnick <player> §7| §eall §7- remove nick(s)",
                "§712. §e/realname <nick> §7- who is behind a nick",
                "§713. §e/deathban fakenick <player> <nick> §7- fake death curve on a nick",
                "§714. §e/deathban revertnick <player> §7- end fake mode and un-nick",
                "§6=== Data ===",
                "§715. §e/deathban export §7- write a timestamped JSON copy",
                "§716. §e/deathban import <file> [overwrite] §7- load players.yml or .json",
                "§717. §e/deathban reload §7- re-read config and data",
                "§6=== PearlCatch ===",
                "§718. §e/pearlcatch §7- current settings",
                "§719. §e/pearlcatch on|off §7- enable or disable",
                "§720. §e/pearlcatch radius <v> §7- collision radius",
                "§721. §e/pearlcatch delay <min> <max> §7- close and far catch delay",
                "§722. §e/pearlcatch momentum <v> §7- velocity kept on arrival",
                "§723. §e/pearlcatch sound <true|false> §7- burst sound at the catch",
                "§724. §e/deathban help §7- this list"
        };
        for (String l : lines) src.sendFeedback(() -> Text.literal(l), false);
        return 1;
    }

    private static int checkAll(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        msg(ctx, "§6=== Death counts ===");
        boolean any = false;
        for (Map.Entry<UUID, PlayerDataStore.Entry> e : mod.store.all().entrySet()) {
            PlayerDataStore.Entry v = e.getValue();
            if (v.deaths <= 0) continue;
            any = true;
            String status = v.deaths >= mod.config.maxDeaths ? "§4PERMA" : "§e" + v.deaths + "/" + mod.config.maxDeaths;
            msg(ctx, "§f" + v.name + "§7: " + status + (v.tokenRevived ? " §8(revive used)" : ""));
        }
        if (!any) msg(ctx, "§7Nobody has died yet.");
        return 1;
    }

    private static int checkOne(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        UUID id = mod.store.findByName(name);
        PlayerDataStore.Entry e = id == null ? null : mod.store.get(id);
        if (e == null) { msg(ctx, "§7" + name + " has no record."); return 0; }
        String status = e.deaths >= mod.config.maxDeaths ? "§4PERMANENTLY BANNED" : "§e" + e.deaths + "/" + mod.config.maxDeaths;
        msg(ctx, "§f" + e.name + "§7: " + status + (e.tokenRevived ? " §8(revive used)" : ""));
        return 1;
    }

    private static int revive(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        UUID id = mod.store.findByName(name);
        PlayerDataStore.Entry e = id == null ? null : mod.store.get(id);
        if (e == null) { msg(ctx, "§7Player not found."); return 0; }
        e.deaths = 0; e.lastDeath = 0;
        mod.store.save();
        msg(ctx, "§aRevived §f" + e.name + "§a (reset to 0).");
        mod.broadcast(Text.literal(e.name + " was revived!").formatted(Formatting.GREEN));
        return 1;
    }

    private static int pardon(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        UUID id = mod.store.findByName(name);
        PlayerDataStore.Entry e = id == null ? null : mod.store.get(id);
        if (e == null) { msg(ctx, "§7Player not found."); return 0; }
        if (e.deaths >= mod.config.maxDeaths) {
            msg(ctx, "§c" + e.name + " is perma-banned. Use revive, revert or set.");
            return 0;
        }
        e.lastDeath = 0;
        mod.store.save();
        msg(ctx, "§aPardoned §f" + e.name + "§a - timer cleared, still on " + e.deaths + ".");
        return 1;
    }

    private static int revert(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        UUID id = mod.store.findByName(name);
        PlayerDataStore.Entry e = id == null ? null : mod.store.get(id);
        if (e == null) { msg(ctx, "§7Player not found."); return 0; }
        int before = e.deaths;
        e.deaths = Math.max(1, e.deaths - 1);
        e.lastDeath = 0;
        mod.store.save();
        msg(ctx, "§aReverted §f" + e.name + "§a - " + before + " -> " + e.deaths + ".");
        return 1;
    }

    private static int setDeaths(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        UUID id = mod.store.findByName(name);
        if (id == null) id = PlayerDataStore.offlineIdFor(name);
        PlayerDataStore.Entry e = mod.store.getOrCreate(id, name);
        e.deaths = count;
        mod.store.save();
        msg(ctx, "§aSet §f" + name + "§a to " + count + " deaths.");
        return 1;
    }

    private static int fakeNick(CommandContext<ServerCommandSource> ctx, DeathBanMod mod)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        String nick = StringArgumentType.getString(ctx, "nick");

        UUID nickId = mod.resolveNickId(nick);
        PlayerDataStore.Entry ne = mod.store.get(nickId);
        int start;
        boolean seeded = false;
        if (ne != null && ne.deaths > 0) {
            start = ne.deaths;
        } else {
            start = 1 + ThreadLocalRandom.current().nextInt(4);
            ne = mod.store.getOrCreate(nickId, nick);
            ne.name = nick;
            ne.deaths = start;
            mod.store.save();
            seeded = true;
        }
        String realName = mod.realNameOf(target);
        mod.setFakeNick(target.getUuid(), nick);
        // One command is the whole persona: name, skin, and the death curve.
        if (!mod.nickCore.isNicked(target.getUuid())) mod.nickCore.nick(target, nick);
        msg(ctx, "§aFake-nick ON for §f" + realName + "§a as §f" + nick
                + "§a - on " + start + " deaths" + (seeded ? " (new, randomised)" : " (from history)") + ".");
        return 1;
    }

    private static int revertNick(CommandContext<ServerCommandSource> ctx, DeathBanMod mod)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        mod.clearFakeNick(target.getUuid());
        // Spec 1.6: revertnick ends fake mode AND un-nicks.
        if (mod.nickCore.isNicked(target.getUuid())) mod.nickCore.unnick(target);
        msg(ctx, "§eFake-nick OFF for §f" + mod.realNameOf(target));
        return 1;
    }

    private static int giveToken(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) { msg(ctx, "§cRun this in-game."); return 0; }
        if (!p.getInventory().insertStack(mod.revive.makeToken())) {
            p.dropItem(mod.revive.makeToken(), false);
        }
        msg(ctx, "§aGave you a Revive Token.");
        return 1;
    }

    private static int export(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        try {
            Path out = mod.store.export();
            msg(ctx, "§aExported to §f" + out.getFileName());
            msg(ctx, "§7Full path: " + out);
            return 1;
        } catch (Exception e) {
            msg(ctx, "§cExport failed: " + e.getMessage());
            return 0;
        }
    }

    private static int importData(CommandContext<ServerCommandSource> ctx, DeathBanMod mod, boolean overwrite) {
        String name = StringArgumentType.getString(ctx, "file");
        Path p = mod.configDir.resolve(name);
        if (!Files.exists(p)) {
            msg(ctx, "§cNot found: " + p);
            msg(ctx, "§7Put the file in config/deathban/ first.");
            return 0;
        }
        try {
            int n = name.toLowerCase().endsWith(".yml") || name.toLowerCase().endsWith(".yaml")
                    ? mod.store.importLegacyYaml(p, overwrite)
                    : mod.store.importJson(p, overwrite);
            msg(ctx, "§aImported " + n + " record(s)" + (overwrite ? " (overwriting)" : " (new only)") + ".");
            return 1;
        } catch (Exception e) {
            msg(ctx, "§cImport failed: " + e.getMessage());
            return 0;
        }
    }

    // ---------- utils ----------

    private static String onOff(boolean b) { return b ? "§aON" : "§cOFF"; }

    /**
     * 1.21.11 removed ServerCommandSource.hasPermissionLevel and replaced it with
     * a whole permission-predicate system that is barely mapped yet. This asks the
     * one question we actually care about, using only stable calls: is this an op?
     * Console and command blocks pass.
     */
    private static boolean isAdmin(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return true;
        MinecraftServer s = p.getEntityWorld().getServer();
        if (s == null) return false;
        return s.getPlayerManager().isOperator(
                new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
    }

    private static void msg(CommandContext<ServerCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(() -> Text.literal(text), false);
    }
}
