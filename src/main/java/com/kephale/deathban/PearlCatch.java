package com.kephale.deathban;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PearlCatch {

    private final DeathBanMod mod;

    private final List<EnderPearlEntity> pearls = new ArrayList<>();
    private final List<WindChargeEntity> charges = new ArrayList<>();
    private final Set<WindChargeEntity> grown = new HashSet<>();
    private final Map<EnderPearlEntity, Vec3d> launched = new HashMap<>();
    private final Map<Entity, Long> spawnedAt = new HashMap<>();
    private final Map<Entity, Hand> thrownWith = new HashMap<>();
    private final Map<UUID, Long> pearlUse = new HashMap<>();
    private final Map<UUID, Hand> pearlUseHand = new HashMap<>();
    private final Map<UUID, Long> chargeUse = new HashMap<>();
    private final Map<UUID, Hand> chargeUseHand = new HashMap<>();
    public boolean debug = false;

    public PearlCatch(DeathBanMod mod) { this.mod = mod; }

    public int trackedPearls() { return pearls.size(); }
    public int trackedCharges() { return charges.size(); }
    public int catches() { return grown.size(); }

    public void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity) {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.isOf(Items.ENDER_PEARL)) {
                    pearlUse.put(player.getUuid(), System.nanoTime());
                    pearlUseHand.put(player.getUuid(), hand);
                } else if (stack.isOf(Items.WIND_CHARGE)) {
                    chargeUse.put(player.getUuid(), System.nanoTime());
                    chargeUseHand.put(player.getUuid(), hand);
                }
            }
            return ActionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof EnderPearlEntity p) {
                if (!pearls.contains(p)) pearls.add(p);
                launched.putIfAbsent(p, p.getEntityPos());
                spawnedAt.putIfAbsent(p, System.nanoTime());
                tagHand(p, pearlUse, pearlUseHand);
            } else if (entity instanceof WindChargeEntity c) {
                if (!charges.contains(c)) charges.add(c);
                spawnedAt.putIfAbsent(c, System.nanoTime());
                tagHand(c, chargeUse, chargeUseHand);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof EnderPearlEntity p) { pearls.remove(p); launched.remove(p); spawnedAt.remove(p); thrownWith.remove(p); }
            else if (entity instanceof WindChargeEntity c) { charges.remove(c); grown.remove(c); spawnedAt.remove(c); thrownWith.remove(c); }
        });
        ServerTickEvents.START_WORLD_TICK.register(this::tickWorld);
    }

    private void tagHand(Entity e, Map<UUID, Long> uses, Map<UUID, Hand> hands) {
        if (thrownWith.containsKey(e)) return;
        Entity owner = e instanceof net.minecraft.entity.projectile.ProjectileEntity pe ? pe.getOwner() : null;
        if (owner == null) return;
        UUID id = owner.getUuid();
        Long t = uses.remove(id);
        Hand h = hands.remove(id);
        if (t == null || h == null) return;
        if (System.nanoTime() - t > 50_000_000L) return;
        thrownWith.put(e, h);
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
        launched.keySet().removeIf(Entity::isRemoved);
        charges.removeIf(Entity::isRemoved);
        spawnedAt.keySet().removeIf(Entity::isRemoved);
        thrownWith.keySet().removeIf(Entity::isRemoved);
        if (pearls.isEmpty() || charges.isEmpty()) {
            if (!grown.isEmpty()) shrinkAll();
            return;
        }

        for (WindChargeEntity charge : new ArrayList<>(charges)) {
            if (charge.isRemoved() || charge.getEntityWorld() != world) continue;

            Vec3d cp = charge.getEntityPos();
            Box target = targetBox(world, charge, cp);

            if (target != null) {
                charge.setBoundingBox(target);
                grown.add(charge);
            } else if (grown.remove(charge)) {
                charge.setPosition(cp.x, cp.y, cp.z);
            }
        }
    }

    private Box targetBox(ServerWorld world, WindChargeEntity charge, Vec3d cp) {
        double radius = mod.config.pearlCollisionRadius;
        double minFlight = mod.config.pearlMinFlightDistance;
        double reach = radius + 4.0;
        double reachSq = reach * reach;
        Entity chargeOwner = charge.getOwner();
        long windowNanos = (long) (mod.config.pearlIpcWindowMs * 1_000_000.0);
        long offhandNanos = (long) (mod.config.pearlIpcOffhandWindowMs * 1_000_000.0);
        boolean chargeOffhand = thrownWith.get(charge) == Hand.OFF_HAND;
        Long chargeTime = spawnedAt.get(charge);

        EnderPearlEntity partner = null;
        double partnerDistSq = Double.MAX_VALUE;
        long partnerGap = 0;
        boolean partnerOffhand = false;
        long ownGap = -1;
        boolean normalNear = false;

        for (EnderPearlEntity pearl : pearls) {
            if (pearl.isRemoved() || pearl.getEntityWorld() != world) continue;
            Vec3d pp = pearl.getEntityPos();
            double distSq = cp.squaredDistanceTo(pp);
            if (distSq > reachSq) continue;

            Entity pearlOwner = pearl.getOwner();
            boolean sameOwner = chargeOwner != null && pearlOwner != null
                    && pearlOwner.getUuid().equals(chargeOwner.getUuid());

            if (sameOwner && chargeTime != null) {
                Long pearlTime = spawnedAt.get(pearl);
                if (pearlTime != null) {
                    long gap = Math.abs(pearlTime - chargeTime);
                    boolean offhandPair = chargeOffhand && thrownWith.get(pearl) == Hand.MAIN_HAND;
                    boolean inWindow = (windowNanos > 0 && gap <= windowNanos)
                            || (offhandPair && offhandNanos > 0 && gap <= offhandNanos);
                    if (inWindow) {
                        if (distSq < partnerDistSq) {
                            partnerDistSq = distSq;
                            partner = pearl;
                            partnerGap = gap;
                            partnerOffhand = offhandPair;
                        }
                        continue;
                    }
                    ownGap = gap;
                }
            }

            if (minFlight > 0 && pearlOwner instanceof ServerPlayerEntity thrower) {
                if (thrower.getEntityPos().squaredDistanceTo(pp) < minFlight * minFlight) continue;
            }
            normalNear = true;
        }

        Box modBox = modBox(cp, charge.getVelocity(), radius);

        if (normalNear || partner == null) {
            if (debug && ownGap >= 0 && chargeOwner instanceof ServerPlayerEntity p) {
                p.sendMessage(Text.literal("§7normal catch | gap " + ms(ownGap) + "ms"), true);
            }
            return normalNear ? modBox : null;
        }

        double flight = flightOf(partner);
        double blockZone = mod.config.pearlBlockZone;
        double blendZone = mod.config.pearlBlendZone;

        Box normal = normalBox(charge, cp);
        Box cell = blockCell(partner.getEntityPos());
        Box blockMode = normal.intersects(cell) ? cell.union(normal) : normal;

        Box result;
        String zone;
        if (flight <= blockZone) {
            result = blockMode;
            zone = "same-block";
        } else if (blendZone <= 0 || flight >= blockZone + blendZone) {
            result = modBox;
            zone = "full";
        } else {
            double t = (flight - blockZone) / blendZone;
            result = lerp(blockMode, modBox, t);
            zone = "blend " + Math.round(t * 100) + "%";
        }

        if (debug && partner.getOwner() instanceof ServerPlayerEntity thrower) {
            thrower.sendMessage(Text.literal(
                    "§dIPC" + (partnerOffhand ? " offhand" : "") + "
