package com.kephale.deathban;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TabListRange {

    private final DeathBanMod mod;
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    private int ticks = 0;
    private boolean broken = false;

    private Field entriesField;
    private Constructor<PlayerListS2CPacket.Entry> entryCtor;
    private RecordComponent[] components;
    private int listedIndex = -1;

    public TabListRange(DeathBanMod mod) { this.mod = mod; }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    public void forget(UUID id) {
        hidden.remove(id);
        for (Set<UUID> s : hidden.values()) s.remove(id);
    }

    public void showAll(MinecraftServer server) {
        if (server == null) { hidden.clear(); return; }
        for (Map.Entry<UUID, Set<UUID>> e : hidden.entrySet()) {
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(e.getKey());
            if (viewer == null) continue;
            for (UUID t : e.getValue()) {
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(t);
                if (target != null) send(viewer, target, true);
            }
        }
        hidden.clear();
    }

    private void tick(MinecraftServer server) {
        if (broken || !mod.config.tabListNearbyOnly) return;
        if (++ticks < 20) return;
        ticks = 0;

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.size() < 2) return;
        int range = server.getPlayerManager().getViewDistance() + 1;

        for (ServerPlayerEntity viewer : players) {
            if (DeathBanMod.isBot(viewer)) continue;
            Set<UUID> set = hidden.computeIfAbsent(viewer.getUuid(), k -> new HashSet<>());
            ChunkPos vc = viewer.getChunkPos();

            for (ServerPlayerEntity target : players) {
                if (target == viewer) continue;
                ChunkPos tc = target.getChunkPos();
                boolean near = !mod.isInvisible(target)
                        && target.getEntityWorld() == viewer.getEntityWorld()
                        && Math.abs(tc.x - vc.x) <= range
                        && Math.abs(tc.z - vc.z) <= range;
                boolean isHidden = set.contains(target.getUuid());

                if (near && isHidden) {
                    if (send(viewer, target, true)) set.remove(target.getUuid());
                } else if (!near && !isHidden) {
                    if (send(viewer, target, false)) set.add(target.getUuid());
                }
                if (broken) return;
            }
        }
    }

    private boolean send(ServerPlayerEntity viewer, ServerPlayerEntity target, boolean listed) {
        try {
            bind();
            PlayerListS2CPacket packet = new PlayerListS2CPacket(
                    EnumSet.of(PlayerListS2CPacket.Action.UPDATE_LISTED), List.of(target));
            List<?> entries = (List<?>) entriesField.get(packet);
            List<PlayerListS2CPacket.Entry> out = new ArrayList<>(entries.size());
            for (Object o : entries) out.add(withListed((PlayerListS2CPacket.Entry) o, listed));
            entriesField.set(packet, out);
            viewer.networkHandler.sendPacket(packet);
            return true;
        } catch (Throwable t) {
            broken = true;
            DeathBanMod.LOGGER.warn("Tab list range switched off, could not build the packet", t);
            return false;
        }
    }

    private PlayerListS2CPacket.Entry withListed(PlayerListS2CPacket.Entry e, boolean listed) throws Exception {
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = components[i].getAccessor().invoke(e);
        }
        args[listedIndex] = listed;
        return entryCtor.newInstance(args);
    }

    private void bind() throws Exception {
        if (entriesField != null) return;

        for (Field f : PlayerListS2CPacket.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            entriesField = f;
            break;
        }
        if (entriesField == null) throw new IllegalStateException("entries field not found");

        components = PlayerListS2CPacket.Entry.class.getRecordComponents();
        if (components == null) throw new IllegalStateException("Entry is not a record");
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            components[i].getAccessor().setAccessible(true);
            if (listedIndex < 0 && types[i] == boolean.class) listedIndex = i;
        }
        if (listedIndex < 0) throw new IllegalStateException("listed component not found");

        entryCtor = PlayerListS2CPacket.Entry.class.getDeclaredConstructor(types);
        entryCtor.setAccessible(true);
    }
}
