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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
                    .then(literal("chance")
                            .executes(ctx -> {
                                msg(ctx, "§eElimination chance: §f" + mod.config.eliminationChance + "%");
                                return 1;
                            })
                            .then(argument("percent", IntegerArgumentType.integer(0, 100))
                                    .executes(ctx -> {
                                        mod.config.eliminationChance = IntegerArgumentType.getInteger(ctx, "percent");
                                        mod.config.save();
                                        msg(ctx, "§aElimination chance set to §f" + mod.config.eliminationChance + "%");
                                        return 1;
                                    })))
                    .then(literal("immune")
                            .then(argument("name", StringArgumentType.word())
                                    .then(argument("on", BoolArgumentType.bool())
                                            .executes(ctx -> immune(ctx, mod)))))
                    .then(literal("forcenext")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> forceNext(ctx, mod, true))
                                    .then(literal("clear").executes(ctx -> forceNext(ctx, mod, false)))))
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
                    .then(literal("headchance")
                            .then(argument("chance", DoubleArgumentType.doubleArg(0.0, 1.0))
                                    .executes(ctx -> {
                                        mod.config.steveHeadChance = DoubleArgumentType.getDouble(ctx, "chance");
                                        mod.config.save();
                                        msg(ctx, "Steve head chance set to " + mod.config.steveHeadChance);
                                        return 1;
                                    })))
                    .then(literal("item").executes(ctx -> giveToken(ctx, mod)))
                    .then(literal("panel").executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { msg(ctx, "§cRun this in-game."); return 0; }
                        mod.panel.open(p);
                        return 1;
                    }))
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
                                    })))
                            .then(literal("tablist").then(argument("on", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        mod.config.tabListNearbyOnly = BoolArgumentType.getBool(ctx, "on");
                                        mod.config.save();
                                        if (!mod.config.tabListNearbyOnly) mod.tabList.showAll(ctx.getSource().getServer());
                                        msg(ctx, "Tab list nearby only " + onOff(mod.config.tabListNearbyOnly));
                                        return 1;
                                    }))))
            );

            dispatcher.register(literal("disguise")
                    .requires(Commands::isAdmin)
                    .then(argument("player", EntityArgumentType.player())
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> disguise(ctx, mod, null))
                                    .then(argument("lives", StringArgumentType.word())
                                            .executes(ctx -> disguise(ctx, mod,
                                                    StringArgumentType.getString(ctx, "lives")))))));

            dispatcher.register(literal("pearlcatch")
                    .requires(Commands::isAdmin)
                    .executes(ctx -> {
                        msg(ctx, "PearlCatch " + onOff(mod.config.pearlCatchEnabled)
                                + " | radius " + mod.config.pearlCollisionRadius
                                + " | minflight " + mod.config.pearlMinFlightDistance);
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
                    .then(literal("minflight").then(argument("v", DoubleArgumentType.doubleArg(0.0, 20.0))
                            .executes(ctx -> {
                                mod.config.pearlMinFlightDistance = DoubleArgumentType.getDouble(ctx, "v");
                                mod.config.save();
                                msg(ctx, "Min pearl flight " + mod.config.pearlMinFlightDistance); return 1;
                            }))));
        });
    }

    private static int help(ServerCommandSource src) {
        String[] lines = {
                "§6=== DeathBan ===",
                "§70. §e/deathban panel §7- death panel GUI",
                "§71. §e/deathban check [player]",
                "§72. §e/deathban revive <player> §7- reset to 0",
                "§73. §e/deathban pardon <player> §7- clear timer",
                "§74. §e/deathban revert <player> §7- subtract 1",
                "§75. §e/deathban set <player> <n>",
                "§76. §e/deathban chance [0-100] §7- elimination %",
                "§77. §e/deathban immune <player> <true|false>",
                "§78. §e/deathban forcenext <player> [clear]",
                "§79. §e/deathban revivedeaths <n>",
                "§710. §e/deathban headchance <0-1>",
                "§711. §e/deathban item §7- Revive Token",
                "§712. §e/deathban debug",
                "§713. §e/deathban export | import <file> [overwrite] | reload",
                "§714. §e/deathban toggle <deathban|invisiblekillers|deathmessages|pearlcatch|tablist> <bool>",
                "§6=== Disguise ===",
                "§715. §e/disguise <player> <name> [lives|death]",
                "§6=== PearlCatch ===",
                "§716. §e/pearlcatch [on|off|radius <v>|minflight <v>]"
        };
        for (String l : lines) src.sendFeedback(() -> Text.literal(l), false);
        return 1;
    }

    private static String tags(PlayerDataStore.Entry v) {
        String t = "";
        if (v.immune) t += " §b[immune]";
        if (v.forcedNext) t += " §c[primed]";
        if (v.tokenRevived) t += " §8(revive used)";
        return t;
    }

    private static int checkAll(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        msg(ctx, "§6=== Death counts ===");
        boolean any = false;
        for (Map.Entry<UUID, PlayerDataStore.Entry> e : mod.store.all().entrySet()) {
            PlayerDataStore.Entry v = e.getValue();
            if (v.deaths <= 0 && !v.immune && !v.forcedNext) continue;
            any = true;
            String status = v.deaths >= mod.config.maxDeaths ? "§4FINAL" : "§e" + v.deaths + "/" + mod.config.maxDeaths;
            msg(ctx, "§f" + v.name + "§7: " + status + tags(v));
        }
        MinecraftServer s = ctx.getSource().getServer();
        if (s != null) {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                DeathBanMod.Disguise d = mod.disguiseOf(p.getUuid());
                if (d == null) continue;
                any = true;
                String l = d.death ? "death" : (d.lives == null ? "auto" : String.valueOf(d.lives));
                msg(ctx, "§d" + p.getGameProfile().name() + " §7disguised as §f" + d.name + " §7(" + l + ")");
            }
        }
        if (!any) msg(ctx, "§7Nothing to show.");
        return 1;
    }

    private static UUID idFor(DeathBanMod mod, CommandContext<ServerCommandSource> ctx, String name) {
        MinecraftServer s = ctx.getSource().getServer();
        if (s != null) {
            ServerPlayerEntity online = s.getPlayerManager().getPlayer(name);
            if (online != null) return online.getUuid();
        }
        return mod.store.findByName(name);
    }

    private static List<PlayerDataStore.Entry> allFor(DeathBanMod mod, CommandContext<ServerCommandSource> ctx, String name) {
        List<PlayerDataStore.Entry> out = new ArrayList<>();
        UUID primary = idFor(mod, ctx, name);
        if (primary != null) {
            PlayerDataStore.Entry pe = mod.store.get(primary);
            if (pe != null) out.add(pe);
        }
        for (UUID id : mod.store.idsByName(name)) {
            PlayerDataStore.Entry e = mod.store.get(id);
            if (e != null && !out.contains(e)) out.add(e);
        }
        return out;
    }

    private static PlayerDataStore.Entry entryFor(DeathBanMod mod, CommandContext<ServerCommandSource> ctx, String name) {
        UUID id = idFor(mod, ctx, name);
        if (id == null) id = PlayerDataStore.offlineIdFor(name);
        PlayerDataStore.Entry e = mod.store.getOrCreate(id, name);
        if (e.name == null || e.name.isEmpty()) e.name = name;
        return e;
    }

    private static int checkOne(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        UUID id = idFor(mod, ctx, name);
        PlayerDataStore.Entry e = id == null ? null : mod.store.get(id);
        if (e == null) { msg(ctx, "§7" + name + " has no record."); return 0; }
        String status = e.deaths >= mod.config.maxDeaths ? "§4FINAL" : "§e" + e.deaths + "/" + mod.config.maxDeaths;
        msg(ctx, "§f" + e.name + "§7: " + status + tags(e));
        return 1;
    }

    private static int revive(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        List<PlayerDataStore.Entry> all = allFor(mod, ctx, name);
        if (all.isEmpty()) { msg(ctx, "§7Player not found."); return 0; }
        for (PlayerDataStore.Entry e : all) { e.deaths = 0; e.lastDeath = 0; }
        mod.store.save();
        String shown = all.get(0).name;
        msg(ctx, "§aRevived §f" + shown + "§a (reset to 0" + (all.size() > 1 ? ", " + all.size() + " records" : "") + ").");
        mod.broadcast(Text.literal(shown + " was revived!").formatted(Formatting.GREEN));
        return 1;
    }

    private static int pardon(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        List<PlayerDataStore.Entry> all = allFor(mod, ctx, name);
        if (all.isEmpty()) { msg(ctx, "§7Player not found."); return 0; }
        for (PlayerDataStore.Entry e : all) {
            if (e.deaths >= mod.config.maxDeaths) {
                msg(ctx, "§c" + e.name + " can no longer respawn. Use revive, revert or set.");
                return 0;
            }
        }
        for (PlayerDataStore.Entry e : all) e.lastDeath = 0;
        mod.store.save();
        msg(ctx, "§aPardoned §f" + all.get(0).name + "§a - timer cleared.");
        return 1;
    }

    private static int revert(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        List<PlayerDataStore.Entry> all = allFor(mod, ctx, name);
        if (all.isEmpty()) { msg(ctx, "§7Player not found."); return 0; }
        int before = all.get(0).deaths;
        for (PlayerDataStore.Entry e : all) {
            e.deaths = Math.max(1, e.deaths - 1);
            e.lastDeath = 0;
        }
        mod.store.save();
        msg(ctx, "§aReverted §f" + all.get(0).name + "§a - " + before + " -> " + all.get(0).deaths + ".");
        return 1;
    }

    private static int setDeaths(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        int count = Math.max(0, Math.min(mod.config.maxDeaths,
                IntegerArgumentType.getInteger(ctx, "count")));
        PlayerDataStore.Entry e = entryFor(mod, ctx, name);
        e.deaths = count;
        e.lastDeath = count > 0 ? System.currentTimeMillis() : 0;
        mod.store.save();
        msg(ctx, "§aSet §f" + e.name + "§a to " + count + " deaths.");
        return 1;
    }

    private static int immune(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean on = BoolArgumentType.getBool(ctx, "on");
        PlayerDataStore.Entry e = entryFor(mod, ctx, name);
        e.immune = on;
        mod.store.save();
        msg(ctx, "§f" + e.name + " §7is " + (on ? "§bimmune§7 to elimination." : "§cno longer immune§7."));
        return 1;
    }

    private static int forceNext(CommandContext<ServerCommandSource> ctx, DeathBanMod mod, boolean on) {
        String name = StringArgumentType.getString(ctx, "name");
        PlayerDataStore.Entry e = entryFor(mod, ctx, name);
        e.forcedNext = on;
        mod.store.save();
        msg(ctx, on ? "§f" + e.name + "§7's next death §cwill eliminate them§7."
                    : "§f" + e.name + "§7 is no longer primed.");
        return 1;
    }

    private static int disguise(CommandContext<ServerCommandSource> ctx, DeathBanMod mod, String lives)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        String name = StringArgumentType.getString(ctx, "name");

        Integer n = null;
        boolean death = false;
        if (lives != null) {
            if (lives.equalsIgnoreCase("death")) {
                death = true;
            } else {
                try {
                    n = Math.max(0, Math.min(mod.config.maxDeaths, Integer.parseInt(lives)));
                } catch (NumberFormatException ex) {
                    msg(ctx, "§cLives must be a number or 'death'.");
                    return 0;
                }
            }
        }

        mod.setDisguise(target.getUuid(), new DeathBanMod.Disguise(name, n, death));

        String what;
        if (death) {
            what = "next death is §cfinal";
        } else if (n != null) {
            int next = Math.min(mod.config.maxDeaths, n + 1);
            what = "on §f" + n + "§7, next death shows §f" + next;
        } else {
            UUID pid = mod.resolveNameId(name);
            PlayerDataStore.Entry pe = mod.store.get(pid);
            int cur = pe == null ? 0 : pe.deaths;
            int next = Math.min(mod.config.maxDeaths, cur + 1);
            what = "on §f" + cur + "§7 from history, next death shows §f" + next;
        }
        msg(ctx, "§d" + target.getGameProfile().name() + " §7disguised as §f" + name + "§7 - " + what + "§7.");
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
            return 0;
        }
        try {
            int n = name.toLowerCase().endsWith(".yml") || name.toLowerCase().endsWith(".yaml")
                    ? mod.store.importLegacyYaml(p, overwrite)
                    : mod.store.importJson(p, overwrite);
            msg(ctx, "§aImported " + n + " record(s).");
            return 1;
        } catch (Exception e) {
            msg(ctx, "§cImport failed: " + e.getMessage());
            return 0;
        }
    }

    private static String onOff(boolean b) { return b ? "§aON" : "§cOFF"; }

    private static int debug(CommandContext<ServerCommandSource> ctx, DeathBanMod mod) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        msg(ctx, "§6DeathBan " + DeathBanMod.VERSION + " §7| admin: " + isAdmin(ctx.getSource()));
        msg(ctx, "§7elimination " + mod.config.eliminationChance + "% | steve head " + mod.config.steveHeadChance);
        msg(ctx, "§7pearlcatch " + mod.config.pearlCatchEnabled + " | radius " + mod.config.pearlCollisionRadius
                + " | tablist nearby " + mod.config.tabListNearbyOnly);
        if (p != null) {
            msg(ctx, "§7you show as: " + mod.displayNameOf(p) + " | real: " + mod.realNameOf(p));
            msg(ctx, "§7your record: " + mod.store.get(p.getUuid()));
            msg(ctx, "§7records under your name: " + mod.store.idsByName(mod.realNameOf(p)).size());
        }
        return 1;
    }

    private static boolean isAdmin(ServerCommandSource src) {
        try {
            ServerPlayerEntity p = src.getPlayer();
            if (p == null) return true;
            MinecraftServer s = p.getEntityWorld().getServer();
            if (s == null || s.isSingleplayer()) return true;
            net.minecraft.server.PlayerConfigEntry entry =
                    new net.minecraft.server.PlayerConfigEntry(p.getGameProfile());
            if (s.isHost(entry)) return true;
            return s.getPlayerManager().isOperator(entry);
        } catch (Throwable t) {
            DeathBanMod.LOGGER.warn("isAdmin check failed, allowing", t);
            return true;
        }
    }

    private static void msg(CommandContext<ServerCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(() -> Text.literal(text), false);
    }
}
