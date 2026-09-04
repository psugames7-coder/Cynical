package com.kephale.deathban;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityEvents;
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

/**
 * A wind charge that passes close to an ender pearl "catches" it: the pearl is
 * removed and its thrower is teleported to the collision point.
 *
 * <p>The pearl's THROWER is the one teleported, whoever fired the charge.
 *
 * <p>Pearls and charges are tracked as they spawn and despawn, so the scan
 * costs nothing at all unless both kinds are actually airborne. It never walks
 * the world entity list.
 */
public final class PearlCatch {

    private final DeathBanMod mod;

    /** Live projectiles, maintained by spawn/despawn events. Never a world scan. */
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
        // Registered once at init, not lazily from inside a catch.
        ServerTickEvents.END_SERVER_TICK.register(this::tickPending);
    }

    private void prune() {
        pearls.removeIf(Entity::isRemoved);
        charges.removeIf(Entity::isRemoved);
    }

    private void tickWorld(ServerWorld world) {
        // Cheapest possible exit: two empty-list checks when nothing is in flight.
        if (pearls.isEmpty() || charges.isEmpty()) return;
        if (!mod.config.pearlCatchEnabled) return;
        prune();
        if (pearls.isEmpty() || charges.isEmpty()) return;

        double radius = mod.config.pearlCollisionRadius;
        double radiusSq = radius * radius;

        for (EnderPearlEntity pearl : pearls) {
            if (pearl.isRemoved() || pearl.getWorld() != world) continue;
            if (!(pearl.getOwner() instanceof ServerPlayerEntity thrower)) continue;

            Vec3d pp = pearl.getPos();
            Vec3d pv = pearl.getVelocity();

            for (WindChargeEntity charge : charges) {
                if (charge.isRemoved() || charge.getWorld() != world) continue;
                // Default is OFF: anyone's wind charge catches anyone's pearl.
                // Shooting down an enemy pearl to strand them is the point.
                if (mod.config.pearlSameThrowerOnly) {
                    if (!(charge.getOwner() instanceof ServerPlayerEntity co)) continue;
                    if (!co.getUuid().equals(thrower.getUuid())) continue;
                }

                Vec3d cp = charge.getPos();
                Vec3d cv = charge.getVelocity();

                // Broad phase: can they possibly close to within the radius this tick?
                Vec3d rel = pv.subtract(cv);
                double reach = radius + rel.length();
                if (pp.squaredDistanceTo(cp) > reach * reach) continue;

                // Narrow phase: sample four sub-tick positions.
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
        Vec3d pp = pearl.getPos(), pv = pearl.getVelocity();
        Vec3d cp = charge.getPos(), cv = charge.getVelocity();

        double mx = ((pp.x + pv.x * t) + (cp.x + cv.x * t)) / 2.0;
        double my = ((pp.y + pv.y * t) + (cp.y + cv.y * t)) / 2.0;
        double mz = ((pp.z + pv.z * t) + (cp.z + cv.z * t)) / 2.0;

        // Both impacts fired on the same tick at the point where they met, so
        // it sounds like exactly what it is: a charge bursting into a pearl.
        // ENTITY_ENDERMAN_TELEPORT is the pearl's own sound, the one vanilla
        // plays whenever a pearl resolves. Swap it for ENTITY_ENDER_EYE_DEATH
        // if a shatter reads better than a whoosh.
        if (mod.config.pearlPlaySound) {
            world.playSound(null, mx, my, mz, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
            world.playSound(null, mx, my, mz, SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        }

        pearl.discard();
        pearls.remove(pearl);

        // Keep the wind charge alive by nudging it past the collision point.
        double nudge = mod.config.pearlPassthroughNudge;
        if (nudge > 0 && cv.lengthSquared() > 1.0E-6) {
            Vec3d dir = cv.normalize();
            charge.setPosition(cp.x + dir.x * nudge, cp.y + dir.y * nudge, cp.z + dir.z * nudge);
            charge.setVelocity(cv);
        }

        // Distance-tapered delay. Spec 1.7: MIN ticks when close, MAX when far.
        // The old formula had this the wrong way round.
        double dist = thrower.getPos().distanceTo(new Vec3d(mx, my, mz));
        double taper = mod.config.pearlDelayTaperDistance;
        double frac = taper <= 0 ? 1.0 : Math.min(dist / taper, 1.0);
        int min = Math.min(mod.config.pearlDelayMinTicks, mod.config.pearlDelayMaxTicks);
        int max = Math.max(mod.config.pearlDelayMinTicks, mod.config.pearlDelayMaxTicks);
        int delay = (int) Math.round(min + (max - min) * frac);

        schedule(thrower, mx, my, mz, Math.max(0, delay));
    }

    // ---------- delayed teleport ----------

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

        // Marking both rotation axes RELATIVE and passing 0/0 means the server
        // sends a zero rotation delta, so the client keeps whatever the mouse is
        // doing. Sending absolute yaw/pitch here is what snapped the camera.
        // PositionFlag.ROT is the pre-built Set of both rotation axes marked
        // relative. The X_ROT / Y_ROT constants are not mapped in 1.21.11.
        p.teleport(p.getServerWorld(), x, y, z, PositionFlag.ROT, 0.0f, 0.0f, false);

        p.setVelocity(keep);
        p.velocityDirty = true;

        // NO DAMAGE IS EVER APPLIED HERE. A catch costs whatever vanilla costs
        // and not one point more. Do not add anything to this method.
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
