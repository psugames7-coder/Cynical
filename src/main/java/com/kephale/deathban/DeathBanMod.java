package com.kephale.deathban;

import net.minecraft.server.world.ServerWorld;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class DeathBanMod implements ModInitializer {

    public static final String MOD_ID = "deathban";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static DeathBanMod INSTANCE;

    public ModConfig config;
    public PlayerDataStore store;
    public Path configDir;
    private MinecraftServer server;

    private PearlCatch pearlCatch;
    public NickCore nickCore;
    public Revive revive;
    private final Map<UUID, String> fakeNick = new HashMap<>();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        config = ModConfig.load(configDir);
        store = new PlayerDataStore(configDir);
        store.load();

        pearlCatch = new PearlCatch(this);
        nickCore = new NickCore(this);
        revive = new Revive(this);

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            this.server = s;
            syncDeathMessageGameRule();
            LOGGER.info("DeathBan ready - {} player records loaded.", store.all().size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { nickCore.restoreAll(); store.save(); });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) onPlayerDeath(player, source);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onDisconnect(handler.player));

        Commands.register(this);
        pearlCatch.register();
        revive.register();

        LOGGER.info("DeathBan initialised.");
    }

    public MinecraftServer server() { return server; }

    public void broadcast(Text text) {
        if (server == null) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(text, false);
        }
        LOGGER.info(text.getString());
    }

    private long now() { return System.currentTimeMillis(); }

    public void syncDeathMessageGameRule() {
        if (server == null) return;
        String v = config.ownDeathMessages ? "false" : "true";
        try {
            server.getCommandManager().parseAndExecute(
                    server.getCommandSource().withSilent(), "gamerule showDeathMessages " + v);
        } catch (Throwable t) {
            LOGGER.warn("Could not set showDeathMessages", t);
        }
    }

    public void setFakeNick(UUID player, String nick) { fakeNick.put(player, nick); }
    public void clearFakeNick(UUID player) { fakeNick.remove(player); }
    public String fakeNickOf(UUID player) { return fakeNick.get(player); }

    private void onPlayerDeath(ServerPlayerEntity victim, DamageSource source) {
        ServerPlayerEntity killer = null;
        if (source.getAttacker() instanceof ServerPlayerEntity sp) killer = sp;

        boolean pvp = killer != null && killer != victim;
        UUID id = victim.getUuid();
        String nick = fakeNick.get(id);
        if (nick != null) {
            UUID nickId = resolveNickId(nick);
            PlayerDataStore.Entry ne = store.getOrCreate(nickId, nick);
            if (ne.name == null || ne.name.isEmpty()) ne.name = nick;
            ne.deaths += 1;
            ne.lastDeath = now();
            store.save();
            announceDeath(nick, ne.deaths);
            if (config.ownDeathMessages) sendDeathMessage(victim, killer, source);
            kickLater(victim, banMessage(ne.deaths));
            return;
        }
        if (nickCore != null && nickCore.isNicked(id)) {
            if (config.ownDeathMessages) sendDeathMessage(victim, killer, source);
            return;
        }

        if (!config.deathBanEnabled || !pvp) {
            if (config.ownDeathMessages) sendDeathMessage(victim, killer, source);
            return;
        }

        String realName = realNameOf(victim);
        PlayerDataStore.Entry e = store.getOrCreate(id, realName);
        if (realName != null && !realName.isEmpty()) e.name = realName;
        e.deaths += 1;
        e.lastDeath = now();
        store.save();

        boolean op = server != null && server.getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(victim.getGameProfile()));
        if (!op && e.deaths >= config.maxDeaths) {
            dropHead(victim, e.tokenRevived);
        } else if (ThreadLocalRandom.current().nextDouble() < config.steveHeadChance) {
            victim.dropStack(((ServerWorld) victim.getEntityWorld()), new ItemStack(Items.PLAYER_HEAD));
        }
        announceDeath(displayNameOf(victim), e.deaths);
        if (config.ownDeathMessages) sendDeathMessage(victim, killer, source);

        kickLater(victim, banMessage(e.deaths));
        if (op) {
            e.deaths = Math.max(0, e.deaths - 1);
            e.lastDeath = 0;
            store.save();
        }
    }

    private void sendDeathMessage(ServerPlayerEntity victim, ServerPlayerEntity killer, DamageSource source) {
        String victimName = displayNameOf(victim);
        String victimReal = victim.getGameProfile().name();

        if (killer != null && killer != victim
                && config.hideInvisibleKillers && isInvisible(killer)) {
            broadcast(Text.literal(victimName + " was slain by ")
                    .append(Text.literal(displayNameOf(killer)).formatted(Formatting.OBFUSCATED)));
            return;
        }

        String text;
        try {
            text = source.getDeathMessage(victim).getString();
        } catch (Throwable t) {
            text = victimName + " died";
        }
        if (!victimName.equals(victimReal)) text = text.replace(victimReal, victimName);
        if (killer != null) {
            String kReal = killer.getGameProfile().name();
            String kShown = displayNameOf(killer);
            if (!kShown.equals(kReal)) text = text.replace(kReal, kShown);
        }
        broadcast(Text.literal(text));
    }

    private void announceDeath(String who, int deaths) {
        broadcast(Text.literal(who + " is now on " + deaths + " death" + (deaths == 1 ? "" : "s") + ".")
                .formatted(Formatting.RED));
    }

    private void dropHead(ServerPlayerEntity victim, boolean wasTokenRevived) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(net.minecraft.component.DataComponentTypes.PROFILE,
                net.minecraft.component.type.ProfileComponent.ofStatic(victim.getGameProfile()));
        if (wasTokenRevived) {
            head.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                    Text.literal(victim.getGameProfile().name()).formatted(Formatting.RED));
        }
        victim.dropStack(((ServerWorld) victim.getEntityWorld()), head);
    }

    public String displayNameOf(ServerPlayerEntity p) {
        if (nickCore != null && nickCore.isNicked(p.getUuid())) return nickCore.getNick(p.getUuid());
        String nick = fakeNick.get(p.getUuid());
        return nick != null ? nick : p.getGameProfile().name();
    }

    public String realNameOf(ServerPlayerEntity p) {
        if (nickCore != null && nickCore.isNicked(p.getUuid())) return nickCore.getRealName(p);
        return p.getGameProfile().name();
    }

    public UUID resolveNickId(String nick) {
        UUID existing = store.findByName(nick);
        if (existing != null) return existing;
        if (server != null) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(nick);
            if (online != null) return online.getUuid();
        }
        return PlayerDataStore.offlineIdFor(nick);
    }

    private void kickLater(ServerPlayerEntity player, String reason) {
        if (server == null) return;
        server.execute(() -> {
            try { player.networkHandler.disconnect(Text.literal(reason)); }
            catch (Throwable t) { LOGGER.warn("Could not kick after death", t); }
        });
    }

    public String banMessage(int deaths) {
        if (deaths >= config.maxDeaths) return "You have died " + config.maxDeaths + " times. You are PERMANENTLY death-banned.";
        return "You died (death #" + deaths + "). You are banned for " + formatDuration(banDurationFor(deaths)) + ".";
    }

    public long banDurationFor(int deaths) {
        long h = 3600000L;
        return switch (deaths) {
            case 1 -> 8 * h;
            case 2 -> 24 * h;
            case 3 -> 48 * h;
            case 4 -> 7 * 24 * h;
            default -> -1L;
        };
    }

    public String formatDuration(long ms) {
        long totalMin = ms / 60000L;
        long days = totalMin / 1440, hours = (totalMin % 1440) / 60, mins = totalMin % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        sb.append(mins).append("m");
        return sb.toString().trim();
    }

    private void onJoin(ServerPlayerEntity player) {
        if (!config.deathBanEnabled) return;
        PlayerDataStore.Entry e = store.get(player.getUuid());
        if (e == null || e.deaths <= 0) return;
        if (server != null && server.getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(player.getGameProfile()))) return;

        if (e.deaths >= config.maxDeaths) {
            player.networkHandler.disconnect(Text.literal(banMessage(e.deaths)));
            return;
        }
        long until = e.lastDeath + banDurationFor(e.deaths);
        long remaining = until - now();
        if (remaining > 0) {
            player.networkHandler.disconnect(Text.literal(
                    "You are death-banned. Time remaining: " + formatDuration(remaining)));
        }
    }

    private void onDisconnect(ServerPlayerEntity player) {
        if (nickCore != null && nickCore.isNicked(player.getUuid())) nickCore.unnick(player);
    }

    public boolean isInvisible(ServerPlayerEntity p) {
        return p != null && (p.isInvisible() || p.hasStatusEffect(StatusEffects.INVISIBILITY));
    }
}
