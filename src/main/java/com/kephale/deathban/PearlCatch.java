package com.kephale.deathban;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PearlCatch {

    private final DeathBanMod mod;

    private final List<EnderPearlEntity> pearls = new ArrayList<>();
    private final List<WindChargeEntity> charges = new ArrayList<>();
    private final Set<WindChargeEntity> grown = new HashSet<>();

    public PearlCatch(DeathBanMod mod) { this.mod = mod; }

    public int trackedPearls() { return pearls.size(); }
    public int trackedCharges() { return charges.size(); }
    public int catches() { return grown.size(); }

    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof EnderPearlEntity p) { if (!pearls.contains(p)) pearls.add(p); }
            else if (entity instanceof WindChargeEntity c) { if (!charges.contains(c)) charges.add(c); }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof EnderPearlEntity p) pearls.remove(p);
            else if (entity instanceof WindChargeEntity c) { charges.remove(c); grown.remove(c); }
        });
        ServerTickEvents.START_WORLD_TICK.register(this::tickWorld);
    }

    private void tickWorld(ServerWorld world) {
        if (pearls.isEmpty() || charges.isEmpty()) {
            if (!grown.isEmpty()) shrinkAll();
            return;
        }
        if (!mod.config.pearlCatchEnabled) {
            if (!grown.isEmpty()) shrinkAll();
            return;
        }

        pearls.removeIf(Entity::isRemoved);
        charges.removeIf(Entity::isRemoved);
        if (pearls.isEmpty() || charges.isEmpty()) {
            if (!grown.isEmpty()) shrinkAll();
            return;
        }

        double radius = mod.config.pearlCollisionRadius;
        double minFlight = mod.config.pearlMinFlightDistance;

        for (WindChargeEntity charge : new ArrayList<>(charges)) {
            if (charge.isRemoved() || charge.getEntityWorld() != world) continue;

            Vec3d cp = charge.getEntityPos();
            boolean near = nearEligiblePearl(world, cp, radius, minFlight);

            if (near) {
                Vec3d cv = charge.getVelocity();
                double x1 = Math.min(cp.x, cp.x + cv.x);
                double y1 = Math.min(cp.y, cp.y + cv.y);
                double z1 = Math.min(cp.z, cp.z + cv.z);
                double x2 = Math.max(cp.x, cp.x + cv.x);
                double y2 = Math.max(cp.y, cp.y + cv.y);
                double z2 = Math.max(cp.z, cp.z + cv.z);
                charge.setBoundingBox(new Box(
                        x1 - radius, y1 - radius, z1 - radius,
                        x2 + radius, y2 + radius, z2 + radius));
                grown.add(charge);
            } else if (grown.remove(charge)) {
                charge.setPosition(cp.x, cp.y, cp.z);
            }
        }
    }

    private void shrinkAll() {
        for (WindChargeEntity c : new ArrayList<>(grown)) {
            if (!c.isRemoved()) {
                Vec3d p = c.getEntityPos();
                c.setPosition(p.x, p.y, p.z);
            }
        }
        grown.clear();
    }

    private boolean nearEligiblePearl(ServerWorld world, Vec3d cp, double radius, double minFlight) {
        double reach = radius + 4.0;
        double reachSq = reach * reach;
        for (EnderPearlEntity pearl : pearls) {
            if (pearl.isRemoved() || pearl.getEntityWorld() != world) continue;
            Vec3d pp = pearl.getEntityPos();
            if (cp.squaredDistanceTo(pp) > reachSq) continue;

            if (minFlight > 0 && pearl.getOwner() instanceof ServerPlayerEntity thrower) {
                if (thrower.getEntityPos().squaredDistanceTo(pp) < minFlight * minFlight) continue;
            }
            return true;
        }
        return false;
    }
}
