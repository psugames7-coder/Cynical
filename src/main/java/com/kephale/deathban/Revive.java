package com.kephale.deathban;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The revive system, ported from the Paper plugin.
 *
 * <p>A Revive Token is a named echo shard. Right clicking it opens a paginated
 * menu of every permanently banned player, one skinned head per slot. Clicking
 * a head revives that player if you are carrying both their real head and a
 * token. Both are consumed and they come back on {@code reviveDeaths} deaths,
 * two short of permanent, not fresh.
 *
 * <p>One token revive per player, ever. After that they are flagged and the
 * menu refuses.
 */
public final class Revive {

    public static final String TOKEN_NAME = "Revive Token";
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;

    private final DeathBanMod mod;

    public Revive(DeathBanMod mod) { this.mod = mod; }

    public void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!isToken(player.getStackInHand(hand))) return ActionResult.PASS;
            open(sp, 0);
            // CONSUME stops the shard being used for anything else this click.
            return ActionResult.CONSUME;
        });
    }

    // ---------- items ----------

    public ItemStack makeToken() {
        ItemStack token = new ItemStack(Items.ECHO_SHARD);
        token.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(TOKEN_NAME).formatted(Formatting.AQUA)
                        .styled(st -> st.withItalic(false)));
        token.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Right-click to open the revive menu.")
                        .formatted(Formatting.GRAY).styled(st -> st.withItalic(false)))));
        return token;
    }

    /**
     * Matches the crafted token as well as the one from {@code /deathban item}.
     * Name match only, so the datapack recipe and this code can never drift into
     * producing two items that don't recognise each other.
     */
    public boolean isToken(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isOf(Items.ECHO_SHARD)) return false;
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return name != null && name.getString().contains(TOKEN_NAME);
    }

    private ItemStack makeHead(UUID id, String name, String display, List<Text> lore) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(new GameProfile(id, name)));
        if (display != null) {
            head.set(DataComponentTypes.CUSTOM_NAME,
                    Text.literal(display).styled(st -> st.withItalic(false)));
        }
        if (lore != null && !lore.isEmpty()) head.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return head;
    }

    // ---------- inventory scanning ----------

    private int findToken(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (isToken(inv.getStack(i))) return i;
        return -1;
    }

    private void consumeOne(ServerPlayerEntity p, int slot) {
        if (slot < 0) return;
        ItemStack it = p.getInventory().getStack(slot);
        if (it.isEmpty()) return;
        it.decrement(1);
        if (it.isEmpty()) p.getInventory().setStack(slot, ItemStack.EMPTY);
    }

    /** Slot holding a head belonging to {@code target}, or -1. */
    private int findHeadSlot(ServerPlayerEntity p, UUID target, String targetName) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack it = inv.getStack(i);
            if (it.isEmpty() || !it.isOf(Items.PLAYER_HEAD)) continue;
            if (headMatches(it, target, targetName)) return i;
        }
        return -1;
    }

    private boolean hasHead(ServerPlayerEntity p, UUID target, String targetName) {
        return findHeadSlot(p, target, targetName) >= 0;
    }

    // ---------- the menu ----------

    /** Every permanently banned record, stable order so pages don't shuffle. */
    private List<UUID> banned() {
        List<UUID> out = new ArrayList<>(mod.store.banned(mod.config.maxDeaths));
        out.sort((a, b) -> {
            String na = nameOf(a), nb = nameOf(b);
            return na.compareToIgnoreCase(nb);
        });
        return out;
    }

    private String nameOf(UUID id) {
        PlayerDataStore.Entry e = mod.store.get(id);
        return e != null && e.name != null ? e.name : id.toString();
    }

    public void open(ServerPlayerEntity player, int page) {
        List<UUID> list = banned();
        int maxPage = Math.max(0, (list.size() - 1) / PER_PAGE);
        final int p = Math.max(0, Math.min(page, maxPage));

        SimpleInventory inv = new SimpleInventory(54);
        int start = p * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;
            UUID id = list.get(idx);
            PlayerDataStore.Entry e = mod.store.get(id);
            String name = nameOf(id);

            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("Deaths: ").formatted(Formatting.GRAY)
                    .append(Text.literal(String.valueOf(e != null ? e.deaths : mod.config.maxDeaths))
                            .formatted(Formatting.RED))
                    .styled(st -> st.withItalic(false)));
            if (e != null && e.tokenRevived) {
                lore.add(Text.literal("Already used their token revive")
                        .formatted(Formatting.RED).styled(st -> st.withItalic(false)));
            } else {
                lore.add(Text.literal("Click to revive (needs their head + 1 token)")
                        .formatted(Formatting.YELLOW).styled(st -> st.withItalic(false)));
            }
            inv.setStack(i, makeHead(id, name, name, lore));
        }
        if (p > 0) inv.setStack(SLOT_PREV, navItem("Previous Page"));
        if (p < maxPage) inv.setStack(SLOT_NEXT, navItem("Next Page"));

        Text title = Text.literal("Revive  (page " + (p + 1) + "/" + (maxPage + 1) + ")");
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, ignored) -> new ReviveScreenHandler(syncId, playerInv, inv, p),
                title));
    }

    private ItemStack navItem(String label) {
        ItemStack it = new ItemStack(Items.ARROW);
        it.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(label).formatted(Formatting.YELLOW).styled(st -> st.withItalic(false)));
        return it;
    }

    /** A read-only 6 row chest. Every click is intercepted; nothing can be taken. */
    private final class ReviveScreenHandler extends GenericContainerScreenHandler {
        private final int page;

        ReviveScreenHandler(int syncId, PlayerInventory playerInv, Inventory inv, int page) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.page = page;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // Nothing in this menu is ever picked up, dragged, or shift-clicked out.
            if (!(player instanceof ServerPlayerEntity sp)) return;
            if (slotIndex < 0 || slotIndex >= 54) return;

            if (slotIndex == SLOT_PREV) { reopen(sp, page - 1); return; }
            if (slotIndex == SLOT_NEXT) { reopen(sp, page + 1); return; }
            if (slotIndex >= PER_PAGE) return;

            List<UUID> list = banned();
            int idx = page * PER_PAGE + slotIndex;
            if (idx >= list.size()) return;
            attemptRevive(sp, list.get(idx), page);
        }

        @Override
        public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
            return false;
        }
    }

    private void reopen(ServerPlayerEntity p, int page) {
        p.closeHandledScreen();
        // Next tick, so the client is not mid close when the new screen arrives.
        if (mod.server() != null) mod.server().execute(() -> { if (!p.isRemoved()) open(p, page); });
    }

    private void attemptRevive(ServerPlayerEntity p, UUID target, int page) {
        PlayerDataStore.Entry e = mod.store.get(target);
        String name = nameOf(target);

        if (e == null) { deny(p, "No record for " + name + "."); return; }

        // One token revive per player, ever.
        if (e.tokenRevived) {
            deny(p, name + " has already used their one token revive.");
            return;
        }
        // Head is checked BEFORE the token so a failed revive never wastes one.
        if (!hasHead(p, target, name)) {
            deny(p, "You need " + name + "'s head to revive them.");
            return;
        }
        int tokenSlot = findToken(p);
        if (tokenSlot < 0) {
            deny(p, "You need a Revive Token to revive someone.");
            return;
        }

        consumeOne(p, findHeadSlot(p, target, name));
        consumeOne(p, tokenSlot);

        e.deaths = mod.config.reviveDeaths;
        e.lastDeath = 0;
        e.tokenRevived = true;
        mod.store.save();

        p.sendMessage(Text.literal("Revived " + name + " on " + mod.config.reviveDeaths
                + " deaths. Their head and a token were consumed.").formatted(Formatting.GREEN), false);
        mod.broadcast(Text.literal(name + " was revived!").formatted(Formatting.GREEN));

        reopen(p, page);
    }

    private void deny(ServerPlayerEntity p, String why) {
        p.sendMessage(Text.literal(why).formatted(Formatting.RED), false);
        p.closeHandledScreen();
    }

    // ------------------------------------------------------------------
    // UNVERIFIED API. If the build fails, it is almost certainly in here.
    // Everything above uses long-stable signatures.
    // ------------------------------------------------------------------

    /**
     * Does this head belong to {@code target}? Matches on profile id first and
     * falls back to the profile name, because a head crafted or dropped before a
     * name change can carry one without the other.
     */
    private boolean headMatches(ItemStack stack, UUID target, String targetName) {
        ProfileComponent pc = stack.get(DataComponentTypes.PROFILE);
        if (pc == null) return false;
        try {
            GameProfile gp = pc.getGameProfile();
            if (gp != null && target.equals(gp.id())) return true;
            if (pc.getName().isPresent() && targetName.equalsIgnoreCase(pc.getName().get())) return true;
        } catch (Throwable t) {
            DeathBanMod.LOGGER.error("ProfileComponent accessors are named something else on this "
                    + "version. Fix Revive.headMatches; nothing else depends on it.", t);
        }
        return false;
    }
}
