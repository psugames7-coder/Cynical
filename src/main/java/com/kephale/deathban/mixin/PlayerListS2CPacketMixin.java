package com.kephale.deathban.mixin;

import com.kephale.deathban.DeathBanMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListS2CPacket.Entry.class)
public class PlayerListS2CPacketMixin {

    @Shadow @Final private GameProfile profile;

    @Inject(method = "profile", at = @At("HEAD"), cancellable = true)
    private void deathban$nickedProfile(CallbackInfoReturnable<GameProfile> cir) {
        try {
            DeathBanMod mod = DeathBanMod.INSTANCE;
            if (mod == null || mod.nickCore == null || mod.server() == null) return;
            if (profile == null || profile.id() == null) return;
            if (!mod.nickCore.isNicked(profile.id())) return;
            ServerPlayerEntity p = mod.server().getPlayerManager().getPlayer(profile.id());
            if (p == null) return;
            GameProfile swapped = mod.nickCore.profileFor(p, profile);
            if (swapped != null && swapped != profile) cir.setReturnValue(swapped);
        } catch (Throwable ignored) {
        }
    }
}
