package com.kephale.deathban.mixin;

import com.kephale.deathban.DeathBanMod;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public abstract class DeathMessageMixin {

    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), cancellable = true)
    private void deathban$dropVanillaDeathMessage(Text message, boolean overlay, CallbackInfo ci) {
        try {
            DeathBanMod mod = DeathBanMod.INSTANCE;
            if (mod == null || mod.config == null) return;
            if (!mod.config.ownDeathMessages) return;
            if (mod.isWritingOwnMessage()) return;
            if (DeathBanMod.looksLikeDeathMessage(message)) {
                DeathBanMod.vanillaDeathSuppressed = true;
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
