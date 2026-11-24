package com.hardcoresmp;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Membatasi jumlah PLAYER yang boleh memegang mace. */
public final class MaceManager {

    private final HardcoreSMP plugin;
    private final Set<UUID> holders = new LinkedHashSet<>();

    MaceManager(HardcoreSMP plugin) {
        this.plugin = plugin;
        for (String s : plugin.data().getStringList("mace-holders")) {
            try {
                holders.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("mace.enabled", true); }
    public int max() { return plugin.getConfig().getInt("mace.max-holders", 3); }
    public Set<UUID> holders() { return Collections.unmodifiableSet(holders); }

    private boolean bypass(Player p) { return p.hasPermission("hardcoresmp.bypass"); }

    public boolean canHold(Player p) {
        if (!enabled() || bypass(p)) return true;
        return holders.contains(p.getUniqueId()) || max() < 0 || holders.size() < max();
    }

    public boolean tryClaim(Player p) {
        if (!enabled() || bypass(p)) return true;
        if (holders.contains(p.getUniqueId())) return true;
        if (max() >= 0 && holders.size() >= max()) return false;
        holders.add(p.getUniqueId());
        save();
        return true;
    }

    public void release(UUID id) {
        if (holders.remove(id)) save();
    }

    public void clear() {
        holders.clear();
        save();
    }

    public void save() {
        List<String> list = new ArrayList<>();
        for (UUID u : holders) list.add(u.toString());
        plugin.data().set("mace-holders", list);
        plugin.saveData();
    }

    // ---------- pengecekan item ----------
    public static boolean containsMace(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (it.getType() == Material.MACE) return true;
        if (it.hasItemMeta() && it.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack in : box.getInventory().getContents()) {
                if (in != null && in.getType() == Material.MACE) return true;
            }
        }
        return false;
    }

    private static boolean hasMace(Inventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (containsMace(it)) return true;
        }
        return false;
    }

    /** Hapus semua mace dari inventory (termasuk di dalam shulker). */
    private int strip(Inventory inv, List<ItemStack> out) {
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType().isAir()) continue;
            if (it.getType() == Material.MACE) {
                out.add(it.clone());
                inv.setItem(i, null);
                n += it.getAmount();
            } else if (it.hasItemMeta() && it.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof ShulkerBox box) {
                int r = strip(box.getInventory(), out);
                if (r > 0) {
                    bsm.setBlockState(box);
                    it.setItemMeta(bsm);
                    inv.setItem(i, it);
                    n += r;
                }
            }
        }
        return n;
    }

    private int confiscate(Player p) {
        List<ItemStack> out = new ArrayList<>();
        int n = strip(p.getInventory(), out);
        for (ItemStack it : out) {
            Item e = p.getWorld().dropItemNaturally(p.getLocation(), it);
            e.setPickupDelay(100);
        }
        return n;
    }

    /** Dipanggil berkala. */
    public void tick() {
        if (!enabled()) return;
        boolean ender = plugin.getConfig().getBoolean("mace.count-ender-chest", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bypass(p)) continue;
            UUID id = p.getUniqueId();
            boolean inInv = hasMace(p.getInventory());
            boolean has = inInv || (ender && hasMace(p.getEnderChest()));
            if (has) {
                if (!holders.contains(id) && !tryClaim(p) && inInv) {
                    if (confiscate(p) > 0) {
                        p.sendMessage(plugin.msg("mace-confiscated",
                                Placeholder.unparsed("max", String.valueOf(max()))));
                    }
                }
            } else {
                release(id);
            }
        }
    }

    /** Dipakai saat player logout. */
    public void releaseIfEmpty(Player p) {
        boolean ender = plugin.getConfig().getBoolean("mace.count-ender-chest", true);
        boolean has = hasMace(p.getInventory()) || (ender && hasMace(p.getEnderChest()));
        if (!has) release(p.getUniqueId());
    }
}
