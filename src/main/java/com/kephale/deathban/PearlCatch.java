package com.kephale.deathban;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class PearlCatch {

    private final DeathBanMod mod;

    private final List<EnderPearlEntity> pearls = new ArrayList<>();
    private final List<WindChargeEntity> charges = new ArrayList<>();

    private final List<DelayedTeleport> pending = new ArrayList<>();

    public PearlCatch(DeathBanMod mod) { this.mod = mod; }

    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof EnderPearlEntity p) pearls.add(p);
            else if (entity instanceof WindChargeEntity c) charges.add(c);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof EnderPearlEntity p) pearls.remove(p);
            else if (entity instanceof WindChargeEntity c) charges.remove(c);
        });
        ServerTickEvents.END_WORLD_TICK.register(this::tickWorld);
        ServerTickEvents.END_SERVER_TICK.register(this::tickPending);
    }

    private void prune() {
        pearls.removeIf(Entity::isRemoved);
        charges.removeIf(Entity::isRemoved);
    }

    private void tickWorld(ServerWorld world) {
        if (pearls.isEmpty() || charges.isEmpty()) return;
        if (!mod.config.pearlCatchEnabled) return;
        prune();
        if (pearls.isEmpty() || charges.isEmpty()) return;

        double radius = mod.config.pearlCollisionRadius;
        double radiusSq = radius * radius;

        for (EnderPearlEntity pearl : pearls) {
            if (pearl.isRemoved() || pearl.getEntityWorld() != world) continue;
            if (!(pearl.getOwner() instanceof ServerPlayerEntity thrower)) continue;

            Vec3d pp = pearl.getEntityPos();
            Vec3d pv = pearl.getVelocity();

            for (WindChargeEntity charge : charges) {
                if (charge.isRemoved() || charge.getEntityWorld() != world) continue;
                if (mod.config.pearlSameThrowerOnly) {
                    if (!(charge.getOwner() instanceof ServerPlayerEntity co)) continue;
                    if (!co.getUuid().equals(thrower.getUuid())) continue;
                }

                Vec3d cp = charge.getEntityPos();
                Vec3d cv = charge.getVelocity();

                Vec3d rel = pv.subtract(cv);
                double reach = radius + rel.length();
                if (pp.squaredDistanceTo(cp) > reach * reach) continue;

                double bestSq = Double.MAX_VALUE;
                double bestT = 0.0;
                for (int i = 1; i <= 4; i++) {
                    double t = i / 4.0;
                    double dx = (pp.x + pv.x * t) - (cp.x + cv.x * t);
                    double dy = (pp.y + pv.y * t) - (cp.y + cv.y * t);
                    double dz = (pp.z + pv.z * t) - (cp.z + cv.z * t);
                    double sq = dx * dx + dy * dy + dz * dz;
                    if (sq < bestSq) { bestSq = sq; bestT = t; }
                }
                if (bestSq > radiusSq) continue;

                doCatch(world, pearl, charge, thrower, bestT);
                return;
            }
        }
    }

    private void doCatch(ServerWorld world, EnderPearlEntity pearl, WindChargeEntity charge,
                         ServerPlayerEntity thrower, double t) {
        Vec3d pp = pearl.getEntityPos(), pv = pearl.getVelocity();
        Vec3d cp = charge.getEntityPos(), cv = charge.getVelocity();

        double mx = ((pp.x + pv.x * t) + (cp.x + cv.x * t)) / 2.0;
        double my = ((pp.y + pv.y * t) + (cp.y + cv.y * t)) / 2.0;
        double mz = ((pp.z + pv.z * t) + (cp.z + cv.z * t)) / 2.0;

        if (mod.config.pearlPlaySound) {
            world.playSound(null, mx, my, mz, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
            world.playSound(null, mx, my, mz, SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        }

        pearl.discard();
        pearls.remove(pearl);

        double nudge = mod.config.pearlPassthroughNudge;
        if (nudge > 0 && cv.lengthSquared() > 1.0E-6) {
            Vec3d dir = cv.normalize();
            charge.setPosition(cp.x + dir.x * nudge, cp.y + dir.y * nudge, cp.z + dir.z * nudge);
            charge.setVelocity(cv);
        }

        double dist = thrower.getEntityPos().distanceTo(new Vec3d(mx, my, mz));
        double taper = mod.config.pearlDelayTaperDistance;
        double frac = taper <= 0 ? 1.0 : Math.min(dist / taper, 1.0);
        int min = Math.min(mod.config.pearlDelayMinTicks, mod.config.pearlDelayMaxTicks);
        int max = Math.max(mod.config.pearlDelayMinTicks, mod.config.pearlDelayMaxTicks);
        int delay = (int) Math.round(min + (max - min) * frac);

        schedule(thrower, mx, my, mz, Math.max(0, delay));
    }

    private void schedule(ServerPlayerEntity p, double x, double y, double z, int ticks) {
        if (ticks <= 0) { apply(p, x, y, z); return; }
        pending.add(new DelayedTeleport(p, x, y, z, ticks));
    }

    private void tickPending(MinecraftServer server) {
        if (pending.isEmpty()) return;
        for (int i = pending.size() - 1; i >= 0; i--) {
            DelayedTeleport d = pending.get(i);
            if (--d.ticksLeft > 0) continue;
            pending.remove(i);
            apply(d.player, d.x, d.y, d.z);
        }
    }

    private void apply(ServerPlayerEntity p, double x, double y, double z) {
        if (p == null || p.isRemoved()) return;
        Vec3d keep = p.getVelocity().multiply(mod.config.pearlMomentumKeep);

        p.teleport(((ServerWorld) p.getEntityWorld()), x, y, z, PositionFlag.ROT, 0.0f, 0.0f, false);

        p.setVelocity(keep);
        p.velocityDirty = true;

        // NO DAMAGE IS EVER APPLIED HERE.
    }

    private static final class DelayedTeleport {
        final ServerPlayerEntity player;
        final double x, y, z;
        int ticksLeft;

        DelayedTeleport(ServerPlayerEntity player, double x, double y, double z, int ticks) {
            this.player = player; this.x = x; this.y = y; this.z = z; this.ticksLeft = ticks;
        }
    }
}
