package com.kephale.deathban;

import net.minecraft.server.world.ServerWorld;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class DeathBanMod implements ModInitializer {

    public static final String MOD_ID = "deathban";
    public static final String VERSION = "1.5.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static DeathBanMod INSTANCE;

    public static volatile boolean vanillaDeathSuppressed = false;

    public ModConfig config;
    public PlayerDataStore store;
    public Path configDir;
    private MinecraftServer server;

    private PearlCatch pearlCatch;
    public Revive revive;
    public TabListRange tabList;
    public DeathPanel panel;

    private final Map<UUID, Disguise> disguises = new HashMap<>();
    private boolean writingOwn = false;

    public static final class Disguise {
        public final String name;
        public final Integer lives;
        public final boolean death;

        public Disguise(String name, Integer lives, boolean death) {
            this.name = name;
            this.lives = lives;
            this.death = death;
        }
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;
        configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        config = ModConfig.load(configDir);
        store = new PlayerDataStore(configDir);
        store.load();

        pearlCatch = new PearlCatch(this);
        revive = new Revive(this);
        tabList = new TabListRange(this);
        panel = new DeathPanel(this);

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            this.server = s;
            syncDeathMessageGameRule();
            int fixed = normaliseCounts();
            LOGGER.info("=== DeathBan {} ready - {} records, {} corrected, elimination {}% ===",
                    VERSION, store.all().size(), fixed, config.eliminationChance);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> store.save());

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) onPlayerDeath(player, source);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            disguises.remove(handler.player.getUuid());
            tabList.forget(handler.player.getUuid());
        });

        Commands.register(this);
        pearlCatch.register();
        revive.register();
        tabList.register();

        LOGGER.info("DeathBan initialised.");
    }

    public MinecraftServer server() { return server; }

    public int pearlCatchTrackedPearls() { return pearlCatch == null ? -1 : pearlCatch.trackedPearls(); }
    public int pearlCatchTrackedCharges() { return pearlCatch == null ? -1 : pearlCatch.trackedCharges(); }
    public int pearlCatchCount() { return pearlCatch == null ? -1 : pearlCatch.catches(); }

    public void setDisguise(UUID id, Disguise d) { disguises.put(id, d); }
    public Disguise disguiseOf(UUID id) { return disguises.get(id); }

    public static boolean isBot(ServerPlayerEntity p) {
        if (p == null) return false;
        try {
            String cls = p.getClass().getName().toLowerCase();
            if (cls.contains("fake") || cls.contains("bot")) return true;
            return p.networkHandler == null;
        } catch (Throwable t) {
            return false;
        }
    }

    public int normaliseCounts() {
        int fixed = 0;
        for (PlayerDataStore.Entry e : store.all().values()) {
            if (e.deaths > config.maxDeaths) { e.deaths = 4; e.lastDeath = now(); fixed++; }
            if (e.deaths < 0) { e.deaths = 0; fixed++; }
        }
        if (fixed > 0) store.save();
        return fixed;
    }

    public boolean isWritingOwnMessage() { return writingOwn; }

    public static boolean looksLikeDeathMessage(Text message) {
        if (message == null) return false;
        try {
            String key = message.getContent() == null ? "" : message.getContent().toString();
            return key.contains("death.");
        } catch (Throwable t) {
            return false;
        }
    }

    public void broadcast(Text text) {
        if (server == null) return;
        writingOwn = true;
        try {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.sendMessage(text, false);
            }
        } finally {
            writingOwn = false;
        }
        LOGGER.info(text.getString());
    }

    private long now() { return System.currentTimeMillis(); }

    public void syncDeathMessageGameRule() {
        if (server == null) return;
        try {
            server.getCommandManager().parseAndExecute(
                    server.getCommandSource().withSilent(), "gamerule showDeathMessages true");
        } catch (Throwable t) {
            LOGGER.warn("Could not set showDeathMessages", t);
        }
    }

    private boolean isOp(ServerPlayerEntity p) {
        return server != null && server.getPlayerManager().isOperator(
                new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
    }

    private void onPlayerDeath(ServerPlayerEntity victim, DamageSource source) {
        boolean weOweAMessage = vanillaDeathSuppressed;
        vanillaDeathSuppressed = false;

        ServerPlayerEntity killer = null;
        if (source.getAttacker() instanceof ServerPlayerEntity sp) killer = sp;

        if (weOweAMessage) sendDeathMessage(victim, killer, source);

        UUID id = victim.getUuid();
        Disguise d = disguises.remove(id);
        if (d != null) {
            onDisguisedDeath(victim, d);
            return;
        }

        if (!config.deathBanEnabled) return;

        String realName = realNameOf(victim);
        PlayerDataStore.Entry e = store.getOrCreate(id, realName);
        if (realName != null && !realName.isEmpty()) e.name = realName;

        boolean forced = e.forcedNext;
        e.forcedNext = false;

        boolean eliminate;
        if (forced) eliminate = true;
        else if (isOp(victim) || e.immune) eliminate = false;
        else eliminate = ThreadLocalRandom.current().nextInt(100) < config.eliminationChance;

        e.deaths = eliminate ? config.maxDeaths : Math.min(config.maxDeaths, e.deaths + 1);
        e.lastDeath = now();
        store.save();

        if (e.deaths >= config.maxDeaths) {
            announceFinal(displayNameOf(victim));
            dropOwnHead(victim, e.tokenRevived);
        } else {
            announceDeath(displayNameOf(victim), e.deaths);
            if (ThreadLocalRandom.current().nextDouble() < config.steveHeadChance) {
                dropAt(victim, new ItemStack(Items.PLAYER_HEAD));
            }
        }

        kickLater(victim, banMessage(e.deaths));
    }

    private void onDisguisedDeath(ServerPlayerEntity victim, Disguise d) {
        UUID pid = resolveNameId(d.name);
        PlayerDataStore.Entry pe = store.getOrCreate(pid, d.name);
        if (pe.name == null || pe.name.isEmpty()) pe.name = d.name;

        int shown;
        if (d.death) shown = config.maxDeaths;
        else if (d.lives != null) shown = d.lives + 1;
        else shown = pe.deaths + 1;
        shown = Math.max(1, Math.min(config.maxDeaths, shown));

        pe.deaths = shown;
        pe.lastDeath = now();
        store.save();

        if (shown >= config.maxDeaths) {
            announceFinal(displayNameOf(victim));
            dropNamedHead(victim, d.name, pe.tokenRevived);
        } else {
            announceDeath(displayNameOf(victim), shown);
            if (ThreadLocalRandom.current().nextDouble() < config.steveHeadChance) {
                dropAt(victim, new ItemStack(Items.PLAYER_HEAD));
            }
        }

        kickLater(victim, banMessage(shown));
    }

    private void sendDeathMessage(ServerPlayerEntity victim, ServerPlayerEntity killer, DamageSource source) {
        String victimName = displayNameOf(victim);

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
        broadcast(Text.literal(text));
    }

    private void announceDeath(String who, int deaths) {
        broadcast(Text.literal(who + " is now on " + deaths + " death" + (deaths == 1 ? "" : "s") + ".")
                .formatted(Formatting.RED));
    }

    private void announceFinal(String who) {
        broadcast(Text.literal(who + " can no longer respawn.").formatted(Formatting.RED));
    }

    private void dropOwnHead(ServerPlayerEntity victim, boolean wasTokenRevived) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(victim.getGameProfile()));
        if (wasTokenRevived) {
            head.set(DataComponentTypes.CUSTOM_NAME,
                    Text.literal(victim.getGameProfile().name()).formatted(Formatting.RED));
        }
        dropAt(victim, head);
    }

    private void dropNamedHead(ServerPlayerEntity victim, String name, boolean wasTokenRevived) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, ProfileComponent.ofDynamic(name));
        if (wasTokenRevived) {
            head.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(Formatting.RED));
        }
        dropAt(victim, head);
    }

    private void dropAt(ServerPlayerEntity victim, ItemStack stack) {
        try {
            ServerWorld w = (ServerWorld) victim.getEntityWorld();
            Vec3d p = victim.getEntityPos();
            ItemEntity item = new ItemEntity(w, p.x, p.y + 0.5, p.z, stack);
            item.setToDefaultPickupDelay();
            w.spawnEntity(item);
        } catch (Throwable t) {
            LOGGER.warn("Could not drop head", t);
        }
    }

    public String displayNameOf(ServerPlayerEntity p) {
        try {
            String shown = p.getDisplayName() == null ? null : p.getDisplayName().getString();
            if (shown != null && !shown.isEmpty()) return shown;
        } catch (Throwable ignored) {
        }
        return p.getGameProfile().name();
    }

    public String realNameOf(ServerPlayerEntity p) {
        return p.getGameProfile().name();
    }

    public UUID resolveNameId(String name) {
        if (server != null) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
            if (online != null) return online.getUuid();
        }
        UUID existing = store.findByName(name);
        if (existing != null) return existing;
        return PlayerDataStore.offlineIdFor(name);
    }

    private void kickLater(ServerPlayerEntity player, String reason) {
        if (server == null) return;
        if (isBot(player)) return;
        server.execute(() -> {
            try {
                if (player.isRemoved()) return;
                if (player.networkHandler == null) return;
                if (server.getPlayerManager().getPlayer(player.getUuid()) == null) return;
                player.networkHandler.disconnect(Text.literal(reason));
            } catch (Throwable t) {
                LOGGER.warn("Could not kick after death", t);
            }
        });
    }

    public String banMessage(int deaths) {
        if (deaths >= config.maxDeaths) return "You can no longer respawn.";
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
        syncDeathMessageGameRule();
        PlayerDataStore.Entry e = store.getOrCreate(player.getUuid(), realNameOf(player));
        e.name = realNameOf(player);
        e.lastJoin = now();
        if (e.deaths > config.maxDeaths) { e.deaths = 4; e.lastDeath = 0; }
        store.save();
        if (!config.deathBanEnabled) return;
        if (e.deaths <= 0) return;
        if (isOp(player)) return;

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

    public boolean isInvisible(ServerPlayerEntity p) {
        return p != null && (p.isInvisible() || p.hasStatusEffect(StatusEffects.INVISIBILITY));
    }
}
