package com.kephale.deathban;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NickCore {

    public NickCore(DeathBanMod mod) { }

    public boolean isNicked(UUID id) { return false; }

    public GameProfile profileFor(ServerPlayerEntity player, GameProfile real) { return real; }

    public String getNick(UUID id) { return null; }

    public List<UUID> nickedPlayers() { return new ArrayList<>(); }

    public String getRealName(ServerPlayerEntity p) { return p.getGameProfile().name(); }

    public String getDisplayName(ServerPlayerEntity p) { return p.getGameProfile().name(); }

    public void nick(ServerPlayerEntity player, String nick) { }

    public void unnick(ServerPlayerEntity player) { }

    public void restoreAll() { }
}
