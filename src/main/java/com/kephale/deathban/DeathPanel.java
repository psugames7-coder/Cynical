package com.kephale.deathban;

import com.mojang.authlib.GameProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeathPanel {

    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_FILTER = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_HEAD = 4;
    private static final int SLOT_FIRST_COUNT = 19;
    private static final int SLOT_IMMUNE = 38;
    private static final int SLOT_FORCE = 40;
    private static final int SLOT_TIMER = 42;
    private static final int SLOT_BACK = 45;

    private final DeathBanMod mod;

    public DeathPanel(DeathBanMod mod) { this.mod = mod; }

    public void open(ServerPlayerEntity admin) {
        SimpleInventory inv = new SimpleInventory(54);
        admin.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, ignored) -> new Handler(syncId, playerInv, inv),
                Text.literal("Death Panel")));
    }

    private final class Handler extends GenericContainerScreenHandler {

        private final SimpleInventory inv;
        private final List<UUID> shown = new ArrayList<>();
        private boolean detail = false;
        private boolean onlineOnly = true;
        private int page = 0;
        private UUID selected;

        Handler(int syncId, PlayerInventory playerInv, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.inv = inv;
            render();
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            if (slot < 0 || slot >= 54) return;

            if (!detail) {
                if (slot < PER_PAGE) {
                    if (slot >= shown.size()) return;
                    UUID id = shown.get(slot);
                    if (action == SlotActionType.QUICK_MOVE) {
                        selected = id;
                        detail = true;
                    } else if (action == SlotActionType.PICKUP && button == 0) {
                        change(id, 1);
                    } else if (action == SlotActionType.PICKUP && button == 1) {
                        change(id, -1);
                    } else {
                        return;
                    }
                } else if (slot == SLOT_PREV && page > 0) {
                    page--;
                } else if (slot == SLOT_NEXT) {
                    page++;
                } else if (slot == SLOT_FILTER) {
                    onlineOnly = !onlineOnly;
                    page = 0;
                } else {
                    return;
                }
            } else {
                PlayerDataStore.Entry e = selected == null ? null : mod.store.get(selected);
                if (slot >= SLOT_FIRST_COUNT && slot <= SLOT_FIRST_COUNT + mod.config.maxDeaths) {
                    set(selected, slot - SLOT_FIRST_COUNT);
                } else if (slot == SLOT_IMMUNE && e != null) {
                    e.immune = !e.immune;
                    mod.store.save();
                } else if (slot == SLOT_FORCE && e != null) {
                    e.forcedNext = !e.forcedNext;
                    mod.store.save();
                } else if (slot == SLOT_TIMER && e != null) {
                    e.lastDeath = 0;
                    mod.store.save();
                } else if (slot == SLOT_BACK) {
                    detail = false;
                } else {
                    return;
                }
            }
            render();
            syncState();
        }

        private void render() {
            for (int i = 0; i < 54; i++) inv.setStack(i, filler());
            if (detail) renderDetail(); else renderList();
        }

        private void renderList() {
            List<UUID> all = rows();
            int maxPage = Math.max(0, (all.size() - 1) / PER_PAGE);
            if (page > maxPage) page = maxPage;

            shown.clear();
            int start = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE; i++) {
                int idx = start + i;
                if (idx >= all.size()) {
                    inv.setStack(i, ItemStack.EMPTY);
                    continue;
                }
                UUID id = all.get(idx);
                shown.add(id);
                List<Text> lore = info(id);
                lore.add(line(""));
                lore.add(line("§eLeft-click §7+1 death   §eRight-click §7-1 death"));
                lore.add(line("§eShift-click §7for details"));
                inv.setStack(i, head(id, lore));
            }

            if (page > 0) inv.setStack(SLOT_PREV, named(Items.ARROW, "§ePrevious page"));
            if (page < maxPage) inv.setStack(SLOT_NEXT, named(Items.ARROW, "§eNext page"));
            inv.setStack(SLOT_FILTER, named(Items.COMPASS,
                    onlineOnly ? "§fShowing: §aOnline only" : "§fShowing: §bEveryone",
                    line("§7Click to switch")));
            inv.setStack(SLOT_PAGE, named(Items.PAPER,
                    "§fPage " + (page + 1) + "/" + (maxPage + 1),
                    line("§7" + all.size() + " player" + (all.size() == 1 ? "" : "s") + ", newest join first")));
        }

        private void renderDetail() {
            if (selected == null) { detail = false; renderList(); return; }
            PlayerDataStore.Entry e = mod.store.get(selected);
            int cur = e == null ? 0 : e.deaths;
            int max = mod.config.maxDeaths;

            inv.setStack(SLOT_HEAD, head(selected, info(selected)));

            for (int v = 0; v <= max; v++) {
                Item pane;
                if (v == cur) pane = Items.LIME_STAINED_GLASS_PANE;
                else if (v == max) pane = Items.RED_STAINED_GLASS_PANE;
                else pane = Items.GRAY_STAINED_GLASS_PANE;
                String label = v == max ? v + " - can no longer respawn" : v + " death" + (v == 1 ? "" : "s");
                ItemStack s = named(pane, (v == cur ? "§a> " : "§f") + label,
                        line(v == cur ? "§aCurrent" : "§7Click to set"));
                s.setCount(Math.max(1, v));
                inv.setStack(SLOT_FIRST_COUNT + v, s);
            }

            boolean immune = e != null && e.immune;
            boolean forced = e != null && e.forcedNext;
            inv.setStack(SLOT_IMMUNE, named(Items.SHIELD,
                    "§fImmune to elimination: " + (immune ? "§aON" : "§cOFF"), line("§7Click to toggle")));
            inv.setStack(SLOT_FORCE, named(Items.TNT,
                    "§fNext death eliminates: " + (forced ? "§aON" : "§cOFF"), line("§7Click to toggle")));
            inv.setStack(SLOT_TIMER, named(Items.CLOCK, "§fClear ban timer",
                    line("§7" + banLine(e)), line("§7Click to let them rejoin now")));
            inv.setStack(SLOT_BACK, named(Items.ARROW, "§eBack to list"));
        }

        private List<UUID> rows() {
            Map<UUID, Long> joined = new HashMap<>();
            MinecraftServer s = mod.server();
            if (s != null) {
                for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                    PlayerDataStore.Entry e = mod.store.getOrCreate(p.getUuid(), p.getGameProfile().name());
                    if (e.lastJoin == 0) e.lastJoin = System.currentTimeMillis();
                    joined.put(p.getUuid(), e.lastJoin);
                }
            }
            if (!onlineOnly) {
                for (Map.Entry<UUID, PlayerDataStore.Entry> e : mod.store.all().entrySet()) {
                    joined.putIfAbsent(e.getKey(), e.getValue().lastJoin);
                }
            }
            List<UUID> out = new ArrayList<>(joined.keySet());
            out.sort((a, b) -> {
                int c = Long.compare(joined.get(b), joined.get(a));
                return c != 0 ? c : nameOf(a).compareToIgnoreCase(nameOf(b));
            });
            return out;
        }
    }

    private void change(UUID id, int delta) {
        PlayerDataStore.Entry e = mod.store.get(id);
        if (e == null) return;
        e.deaths = Math.max(0, Math.min(mod.config.maxDeaths, e.deaths + delta));
        mod.store.save();
    }

    private void set(UUID id, int value) {
        if (id == null) return;
        PlayerDataStore.Entry e = mod.store.get(id);
        if (e == null) return;
        e.deaths = Math.max(0, Math.min(mod.config.maxDeaths, value));
        mod.store.save();
    }

    private ServerPlayerEntity online(UUID id) {
        MinecraftServer s = mod.server();
        return s == null ? null : s.getPlayerManager().getPlayer(id);
    }

    private String nameOf(UUID id) {
        PlayerDataStore.Entry e = mod.store.get(id);
        if (e != null && e.name != null && !e.name.isEmpty()) return e.name;
        ServerPlayerEntity p = online(id);
        return p != null ? p.getGameProfile().name() : id.toString().substring(0, 8);
    }

    private List<Text> info(UUID id) {
        PlayerDataStore.Entry e = mod.store.get(id);
        ServerPlayerEntity p = online(id);
        int max = mod.config.maxDeaths;
        int d = e == null ? 0 : e.deaths;

        List<Text> lore = new ArrayList<>();
        lore.add(line(d >= max ? "§4Can no longer respawn" : "§7Deaths: §c" + d + "§7/" + max));
        String ban = banLine(e);
        if (!ban.isEmpty() && d < max) lore.add(line("§7" + ban));
        if (e != null && e.immune) lore.add(line("§bImmune to elimination"));
        if (e != null && e.forcedNext) lore.add(line("§cNext death eliminates"));
        if (e != null && e.tokenRevived) lore.add(line("§8Token revive used"));
        if (p != null) {
            lore.add(line(DeathBanMod.isBot(p) ? "§dOnline (bot)" : "§aOnline"));
        } else if (e != null && e.lastJoin > 0) {
            lore.add(line("§7Last joined " + mod.formatDuration(System.currentTimeMillis() - e.lastJoin) + " ago"));
        } else {
            lore.add(line("§8Never joined"));
        }
        return lore;
    }

    private String banLine(PlayerDataStore.Entry e) {
        if (e == null || e.deaths <= 0 || e.lastDeath <= 0) return "Not banned";
        if (e.deaths >= mod.config.maxDeaths) return "Permanent";
        long left = e.lastDeath + mod.banDurationFor(e.deaths) - System.currentTimeMillis();
        return left > 0 ? "Banned, " + mod.formatDuration(left) + " left" : "Not banned";
    }

    private ItemStack head(UUID id, List<Text> lore) {
        ItemStack h = new ItemStack(Items.PLAYER_HEAD);
        String name = nameOf(id);
        ServerPlayerEntity p = online(id);
        ProfileComponent profile;
        if (p != null) {
            profile = ProfileComponent.ofStatic(p.getGameProfile());
        } else {
            try {
                profile = ProfileComponent.ofDynamic(name);
            } catch (Throwable t) {
                profile = ProfileComponent.ofStatic(new GameProfile(id, name));
            }
        }
        h.set(DataComponentTypes.PROFILE, profile);
        h.set(DataComponentTypes.CUSTOM_NAME, line("§f" + name));
        h.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return h;
    }

    private ItemStack named(Item item, String name, Text... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME, line(name));
        if (lore.length > 0) s.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore)));
        return s;
    }

    private ItemStack filler() {
        ItemStack s = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        return s;
    }

    private static Text line(String s) {
        return Text.literal(s).styled(st -> st.withItalic(false));
    }
}
