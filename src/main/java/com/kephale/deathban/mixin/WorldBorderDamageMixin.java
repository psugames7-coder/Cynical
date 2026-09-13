package com.kephale.deathban.mixin;

import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldBorder.class)
public abstract class WorldBorderDamageMixin {

    @Inject(method = "getDamagePerBlock", at = @At("HEAD"), cancellable = true)
    private void deathban$noBorderDamage(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(0.0D);
    }
}
