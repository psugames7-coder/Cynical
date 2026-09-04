package com.kephale.deathban;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nicknames with a real name and skin change, the same behaviour the Bukkit
 * plugin had.
 *
 * <p>Approach, adapted from the Nicked plugin (MIT): rewrite the player's
 * {@link GameProfile} name and textures by reflection, then force every other
 * client to re-request the profile by removing and re-adding them to the player
 * list and respawning their entity.
 *
 * <p>Skins come from Mojang's session server and are cached for half an hour.
 *
 * <p>The live {@link GameProfile} is never mutated. It became a Java record in
 * 1.21.2 and record fields cannot be reassigned reflectively at all, which is
 * why the old approach silently only ever changed the skin. Instead
 * {@link #profileFor} builds a substitute and
 * {@code PlayerListS2CPacketMixin} hands it to other clients at send time.
 */
public final class NickCore {

    private final DeathBanMod mod;

    private final Map<UUID, String> nicks = new HashMap<>();
    private final Map<UUID, String> realNames = new HashMap<>();
    /** Saved textures so the original skin can be put back exactly. */
    private final Map<UUID, Property> originalTextures = new HashMap<>();
    // Written from the fetch thread, read from the server thread.
    private final Map<String, String[]> skinCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> skinCacheTime = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long SKIN_TTL_MS = 30 * 60 * 1000L;

    public NickCore(DeathBanMod mod) { this.mod = mod; }

    // ---------- state ----------

    public boolean isNicked(UUID id) { return nicks.containsKey(id); }

    /**
     * The profile other clients should see. A fresh GameProfile carrying the
     * nick and its skin, built on demand, never a mutation of the live one.
     * Returns the real profile untouched when the player is not nicked.
     */
    public GameProfile profileFor(ServerPlayerEntity player, GameProfile real) {
        UUID id = player.getUuid();
        String nick = nicks.get(id);
        if (nick == null) return real;
        try {
            GameProfile out = new GameProfile(real.getId(), nick);
            String[] skin = skinCache.get(nick.toLowerCase());
            if (skin != null && skin[0] != null) {
                out.getProperties().put("textures", new Property("textures", skin[0], skin[1]));
            } else {
                for (Property pr : real.getProperties().get("textures")) {
                    out.getProperties().put("textures", pr);
                }
            }
            return out;
        } catch (Throwable t) {
            DeathBanMod.LOGGER.warn("Could not build a nicked profile, falling back to the real one", t);
            return real;
        }
    }
    public String getNick(UUID id) { return nicks.get(id); }
    public List<UUID> nickedPlayers() { return new ArrayList<>(nicks.keySet()); }

    public String getRealName(ServerPlayerEntity p) {
        String real = realNames.get(p.getUuid());
        return real != null ? real : p.getGameProfile().getName();
    }

    public String getDisplayName(ServerPlayerEntity p) {
        String nick = nicks.get(p.getUuid());
        return nick != null ? nick : p.getGameProfile().getName();
    }

    // ---------- apply / remove ----------

    public void nick(ServerPlayerEntity player, String nick) {
        UUID id = player.getUuid();
        realNames.put(id, getRealName(player));
        nicks.put(id, nick);

        // No reflection. PlayerListS2CPacketMixin substitutes the profile on
        // the way out; refresh() makes every client re-request it.
        refresh(player);

        fetchSkinAsync(nick, (value, signature) -> {
            MinecraftServer server = mod.server();
            if (server == null) return;
            server.execute(() -> {
                if (player.isRemoved()) return;
                if (!nick.equals(nicks.get(id))) return; // changed meanwhile
                if (value == null) {
                    DeathBanMod.LOGGER.info("No skin found for '{}' - name applied without it.", nick);
                    return;
                }
                setProfileSkin(player, value, signature);
                refresh(player);
            });
        });
    }

    public void unnick(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        if (!nicks.containsKey(id)) return;
        String real = getRealName(player);
        nicks.remove(id);

        restoreSkin(player);
        refresh(player);
        realNames.remove(id);
    }

    public void restoreAll() {
        MinecraftServer server = mod.server();
        if (server != null) {
            for (UUID id : new ArrayList<>(nicks.keySet())) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) unnick(p);
            }
        }
        nicks.clear();
        realNames.clear();
        originalTextures.clear();
    }

    // ---------- profile reflection ----------

    private void setProfileSkin(ServerPlayerEntity player, String value, String signature) {
        try {
            GameProfile profile = player.getGameProfile();
            PropertyMap props = profile.getProperties();

            if (!originalTextures.containsKey(player.getUuid())) {
                var existing = props.get("textures");
                if (existing != null && !existing.isEmpty()) {
                    originalTextures.put(player.getUuid(), existing.iterator().next());
                }
            }
            props.removeAll("textures");
            props.put("textures", new Property("textures", value, signature));
        } catch (Throwable t) {
            DeathBanMod.LOGGER.warn("Could not set profile skin", t);
        }
    }

    private void restoreSkin(ServerPlayerEntity player) {
        try {
            GameProfile profile = player.getGameProfile();
            PropertyMap props = profile.getProperties();
            props.removeAll("textures");
            Property original = originalTextures.remove(player.getUuid());
            if (original != null) props.put("textures", original);
        } catch (Throwable t) {
            DeathBanMod.LOGGER.warn("Could not restore profile skin", t);
        }
    }

    // ---------- refresh ----------

    /**
     * Make everyone else re-read the profile. Removing the player from the tab
     * list and re-adding them resends the profile; destroying and respawning the
     * entity makes the skin re-render.
     */
    private void refresh(ServerPlayerEntity player) {
        MinecraftServer server = mod.server();
        if (server == null) return;
        try {
            PlayerRemoveS2CPacket remove = new PlayerRemoveS2CPacket(List.of(player.getUuid()));
            PlayerListS2CPacket add = new PlayerListS2CPacket(
                    EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,
                               PlayerListS2CPacket.Action.UPDATE_LISTED),
                    List.of(player));
            EntitiesDestroyS2CPacket destroy = new EntitiesDestroyS2CPacket(player.getId());

            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                other.networkHandler.sendPacket(remove);
                other.networkHandler.sendPacket(add);
                if (other.getUuid().equals(player.getUuid())) continue;
                // Drop the entity so the client rebuilds it with the new skin.
                other.networkHandler.sendPacket(destroy);
            }

            // Nudge entity tracking so the destroyed entity is re-sent next tick.
            server.execute(() -> {
                if (player.isRemoved()) return;
                player.getServerWorld().getChunkManager().updatePosition(player);
            });
        } catch (Throwable t) {
            DeathBanMod.LOGGER.warn("Could not refresh nicked player for others", t);
        }
    }

    // ---------- skin fetching ----------

    public interface SkinCallback { void done(String value, String signature); }

    private void fetchSkinAsync(String username, SkinCallback cb) {
        String key = username.toLowerCase();
        Long when = skinCacheTime.get(key);
        String[] cached = skinCache.get(key);
        if (cached != null && when != null && System.currentTimeMillis() - when < SKIN_TTL_MS) {
            cb.done(cached[0], cached[1]);
            return;
        }
        Thread t = new Thread(() -> {
            String[] skin = doFetch(username);
            if (skin != null) {
                skinCache.put(key, skin);
                skinCacheTime.put(key, System.currentTimeMillis());
                cb.done(skin[0], skin[1]);
            } else {
                cb.done(null, null);
            }
        }, "deathban-skin-fetch");
        t.setDaemon(true);
        t.start();
    }

    private String[] doFetch(String username) {
        try {
            String idJson = httpGet("https://api.mojang.com/users/profiles/minecraft/" + username);
            if (idJson == null) return null;
            String uuid = extract(idJson, "id");
            if (uuid == null) return null;

            String profileJson = httpGet(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
            if (profileJson == null) return null;

            String value = extract(profileJson, "value");
            String signature = extract(profileJson, "signature");
            if (value == null) return null;
            return new String[]{ value, signature == null ? "" : signature };
        } catch (Throwable t) {
            return null;
        }
    }

    private String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Minimal field extraction - avoids depending on a JSON library here. */
    private String extract(String json, String field) {
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int open = json.indexOf('"', colon);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }
}
