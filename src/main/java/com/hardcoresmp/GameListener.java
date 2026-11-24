package com.hardcoresmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class GameListener implements Listener {

    private static final String BYPASS = "hardcoresmp.bypass";

    private final HardcoreSMP plugin;
    private final NamespacedKey headKey;
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<String, Long> headCooldown = new HashMap<>();
    private final Map<UUID, Long> notified = new HashMap<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();       // korban -> penyerang player terakhir
    private final Map<UUID, Long> headHandled = new HashMap<>();   // korban -> kapan drop head terakhir diproses

    private record Hit(UUID attacker, long time) {}

    GameListener(HardcoreSMP plugin) {
        this.plugin = plugin;
        this.headKey = new NamespacedKey(plugin, "pvp_head");
    }

    private static boolean roll(double percent) {
        if (percent <= 0) return false;
        if (percent >= 100) return true;
        return ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }

    private void notifyOnce(Player p, Component c) {
        long now = System.currentTimeMillis();
        Long last = notified.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        notified.put(p.getUniqueId(), now);
        p.sendMessage(c);
    }

    // =====================================================
    //  PVP HEAD
    // =====================================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) killer = recentAttacker(victim);   // plugin death animation bisa membuat getKiller() null
        combat.remove(victim.getUniqueId());
        FileConfiguration c = plugin.getConfig();

        if (killer != null && !killer.equals(victim) && c.getBoolean("pvp-head.enabled", true)) {
            handleHead(victim, killer);
        }

    }

    private void handleHead(Player victim, Player killer) {
        FileConfiguration c = plugin.getConfig();
        long nowMs = System.currentTimeMillis();
        long dedupe = c.getLong("pvp-head.compat.dedupe-seconds", 60L) * 1000L;
        Long handled = headHandled.get(victim.getUniqueId());
        if (handled != null && nowMs - handled < dedupe) {
            debug("handleHead dilewati (sudah diproses) untuk " + victim.getName());
            return;
        }
        headHandled.put(victim.getUniqueId(), nowMs);
        if (!roll(plugin.events().chance(EventManager.Id.PVP, "pvp-head.drop-chance-percent", "pvp-head.base-drop-chance-percent"))) return;

        if (c.getBoolean("pvp-head.anti-farm.block-same-ip", true)) {
            InetSocketAddress a1 = killer.getAddress();
            InetSocketAddress a2 = victim.getAddress();
            if (a1 != null && a2 != null && a1.getAddress().equals(a2.getAddress())) return;
        }
        int cdMin = c.getInt("pvp-head.anti-farm.same-victim-cooldown-minutes", 30);
        String ck = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        if (cdMin > 0) {
            Long last = headCooldown.get(ck);
            if (last != null && now - last < cdMin * 60_000L) return;
            headCooldown.put(ck, now);
        }

        ItemStack head = createHead(victim, killer);
        if (c.getBoolean("pvp-head.give-directly-to-killer", false)) {
            for (ItemStack left : killer.getInventory().addItem(head).values()) {
                killer.getWorld().dropItemNaturally(killer.getLocation(), left);
            }
        } else {
            victim.getWorld().dropItemNaturally(victim.getLocation(), head);
        }
    }

    // =====================================================
    //  Kompatibilitas plugin death animation
    // =====================================================
    private void debug(String msg) {
        if (plugin.getConfig().getBoolean("pvp-head.compat.debug", false)) {
            plugin.getLogger().info("[HeadDebug] " + msg);
        }
    }

    private static Player attackerOf(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Penyerang player terakhir yang kita catat sendiri (tidak bergantung pada getKiller()). */
    private Player recentAttacker(Player victim) {
        Hit h = lastHit.get(victim.getUniqueId());
        if (h == null) return null;
        long memory = plugin.getConfig().getLong("pvp-head.compat.killer-memory-seconds", 15L) * 1000L;
        if (System.currentTimeMillis() - h.time() > memory) return null;
        return Bukkit.getPlayer(h.attacker());
    }

    /**
     * Mencatat setiap pukulan antar player (termasuk yang dibatalkan plugin lain) dan mendeteksi
     * pukulan mematikan yang "dicegat" oleh plugin death animation sehingga PlayerDeathEvent tidak muncul.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHitTrack(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        FileConfiguration c = plugin.getConfig();
        if (!e.isCancelled()) {
            lastHit.put(victim.getUniqueId(), new Hit(attacker.getUniqueId(), System.currentTimeMillis()));
        }
        if (!c.getBoolean("pvp-head.enabled", true)) return;

        String mode = c.getString("pvp-head.compat.lethal-hit-mode", "SMART").toUpperCase(Locale.ROOT);
        if (mode.equals("OFF")) return;

        double before = victim.getHealth();
        if (before - e.getFinalDamage() > 0) return;                       // bukan pukulan mematikan
        if (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return; // diselamatkan totem

        final boolean cancelled = e.isCancelled();
        debug("Pukulan mematikan: " + attacker.getName() + " -> " + victim.getName()
                + " (cancelled=" + cancelled + ", hp=" + before + ", mode=" + mode + ")");
        long delay = Math.max(1L, c.getLong("pvp-head.compat.verify-delay-ticks", 5L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> verifyLethal(victim, attacker, before, mode), delay);
    }

    private void verifyLethal(Player victim, Player attacker, double healthBefore, String mode) {
        if (!victim.isOnline() || victim.isDead()) return;                 // mati normal -> ditangani onPlayerDeath
        Long handled = headHandled.get(victim.getUniqueId());
        long dedupe = plugin.getConfig().getLong("pvp-head.compat.dedupe-seconds", 60L) * 1000L;
        if (handled != null && System.currentTimeMillis() - handled < dedupe) return;

        boolean healthChanged = Math.abs(victim.getHealth() - healthBefore) >= 0.5;
        Pose pose = victim.getPose();
        boolean poseChanged = pose == Pose.DYING || pose == Pose.SLEEPING
                || (pose == Pose.SWIMMING && !victim.isInWater());
        boolean spectator = victim.getGameMode() == GameMode.SPECTATOR;
        boolean invulnerable = victim.isInvulnerable();
        boolean intercepted = healthChanged || poseChanged || spectator || invulnerable;

        debug("Verifikasi " + victim.getName() + ": healthChanged=" + healthChanged + ", pose=" + pose
                + ", spectator=" + spectator + ", invulnerable=" + invulnerable + " -> "
                + (mode.equals("ALWAYS") || intercepted ? "DIANGGAP MATI" : "diabaikan (kemungkinan diblokir protection)"));

        if (mode.equals("ALWAYS") || intercepted) {
            handleHead(victim, attacker);
        }
    }

    private ItemStack createHead(Player victim, Player killer) {
        FileConfiguration c = plugin.getConfig();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(victim.getPlayerProfile());
        TagResolver r = TagResolver.resolver(
                Placeholder.unparsed("victim", victim.getName()),
                Placeholder.unparsed("killer", killer.getName()));
        meta.displayName(HardcoreSMP.MM.deserialize(c.getString("pvp-head.name", "<red>Kepala <victim>"), r)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("pvp-head.lore")) {
            lore.add(HardcoreSMP.MM.deserialize(line, r)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(headKey, PersistentDataType.STRING, victim.getUniqueId().toString());
        head.setItemMeta(meta);
        return head;
    }

    private boolean isPvpHead(ItemStack it) {
        if (it == null || it.getType() != Material.PLAYER_HEAD || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(headKey, PersistentDataType.STRING);
    }

    private boolean allHeadsArePvp(ItemStack[] matrix) {
        boolean any = false;
        for (ItemStack it : matrix) {
            if (it != null && it.getType() == Material.PLAYER_HEAD) {
                any = true;
                if (!isPvpHead(it)) return false;
            }
        }
        return any;
    }

    // =====================================================
    //  MOB DROPS (totem evoker + custom)
    // =====================================================
    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        FileConfiguration c = plugin.getConfig();

        if (e.getEntityType() == EntityType.EVOKER && c.getBoolean("totem.enabled", true)) {
            e.getDrops().removeIf(i -> i.getType() == Material.TOTEM_OF_UNDYING);
            if (roll(plugin.events().chance(EventManager.Id.TOTEM, "totem.evoker-drop-chance-percent", "totem.base-drop-chance-percent"))) {
                e.getDrops().add(new ItemStack(Material.TOTEM_OF_UNDYING));
            }
        }

        if (!c.getBoolean("mob-drops.enabled", false) || !plugin.events().effective(EventManager.Id.MOBDROPS)) return;
        ConfigurationSection entries = c.getConfigurationSection("mob-drops.entries");
        if (entries == null) return;
        for (String id : entries.getKeys(false)) {
            ConfigurationSection s = entries.getConfigurationSection(id);
            if (s == null) continue;
            EntityType type;
            try {
                type = EntityType.valueOf(s.getString("entity", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (type != e.getEntityType()) continue;
            Material m = Material.matchMaterial(s.getString("item", ""));
            if (m == null || m.isAir()) continue;
            if (!roll(s.getDouble("chance-percent", 100.0))) continue;
            int min = Math.max(1, s.getInt("min", 1));
            int max = Math.max(min, s.getInt("max", min));
            int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
            e.getDrops().add(new ItemStack(m, amount));
        }
    }

    // =====================================================
    //  HEAVY CORE dari Ominous Vault
    // =====================================================
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("heavy-core.enabled", true)) return;
        Item item = e.getEntity();
        if (item.getItemStack().getType() != Material.HEAVY_CORE) return;
        if (item.getThrower() != null) return;
        if (!nearVault(item.getLocation())) return;
        if (!roll(plugin.events().chance(EventManager.Id.HEAVYCORE, "heavy-core.vault-drop-chance-percent", "heavy-core.base-drop-chance-percent"))) {
            e.setCancelled(true);
        }
    }

    private boolean nearVault(Location loc) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -2; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block b = loc.getBlock().getRelative(x, y, z);
                    if (b.getType() == Material.VAULT) return true;
                }
            }
        }
        return false;
    }

    // =====================================================
    //  CRAFTING: head-apple + mace
    // =====================================================
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r == null) return;
        FileConfiguration c = plugin.getConfig();

        if (r instanceof Keyed k && plugin.recipes().isHeadRecipe(k.getKey())
                && c.getBoolean("head-apple.require-pvp-head", true)
                && !allHeadsArePvp(e.getInventory().getMatrix())) {
            e.getInventory().setResult(null);
            return;
        }

        if (r.getResult().getType() == Material.MACE && plugin.maces().enabled()) {
            if (!c.getBoolean("mace.allow-crafting", true)) {
                e.getInventory().setResult(null);
                return;
            }
            for (HumanEntity h : e.getViewers()) {
                if (h instanceof Player p && !plugin.maces().canHold(p)) {
                    e.getInventory().setResult(null);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        Recipe r = e.getRecipe();
        FileConfiguration c = plugin.getConfig();

        if (r instanceof Keyed k && plugin.recipes().isHeadRecipe(k.getKey())
                && c.getBoolean("head-apple.require-pvp-head", true)
                && !allHeadsArePvp(e.getInventory().getMatrix())) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendMessage(plugin.msg("head-needs-pvp"));
            return;
        }

        if (r.getResult().getType() == Material.MACE && plugin.maces().enabled()
                && e.getWhoClicked() instanceof Player p) {
            if (!c.getBoolean("mace.allow-crafting", true) && !p.hasPermission(BYPASS)) {
                e.setCancelled(true);
                p.sendMessage(plugin.msg("mace-crafting-disabled"));
            } else if (!plugin.maces().tryClaim(p)) {
                e.setCancelled(true);
                p.sendMessage(maceFull());
            }
        }
    }

    private Component maceFull() {
        return plugin.msg("mace-limit-reached",
                Placeholder.unparsed("count", String.valueOf(plugin.maces().holders().size())),
                Placeholder.unparsed("max", String.valueOf(plugin.maces().max())));
    }

    // =====================================================
    //  PICKUP: mace limit + item limit
    // =====================================================
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.hasPermission(BYPASS)) return;
        ItemStack st = e.getItem().getItemStack();

        if (plugin.maces().enabled() && MaceManager.containsMace(st) && !plugin.maces().tryClaim(p)) {
            e.setCancelled(true);
            notifyOnce(p, maceFull());
            return;
        }

        int limit = limitFor(st.getType());
        if (limit >= 0 && count(p.getInventory(), st.getType()) + st.getAmount() > limit) {
            e.setCancelled(true);
            notifyOnce(p, plugin.msg("item-limit-reached",
                    Placeholder.unparsed("item", niceName(st.getType())),
                    Placeholder.unparsed("limit", String.valueOf(limit))));
        }
    }

    private int limitFor(Material m) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("item-limits.enabled", false)) return -1;
        ConfigurationSection lim = c.getConfigurationSection("item-limits.limits");
        if (lim == null) return -1;
        for (String k : lim.getKeys(false)) {
            if (Material.matchMaterial(k) == m) return lim.getInt(k, -1);
        }
        return -1;
    }

    private static int count(PlayerInventory inv, Material m) {
        int n = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }

    private static String niceName(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Dipanggil berkala dari task utama. */
    public void tickLimits() {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("item-limits.enabled", false)) return;
        ConfigurationSection lim = c.getConfigurationSection("item-limits.limits");
        if (lim == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(BYPASS)) continue;
            for (String k : lim.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                int limit = lim.getInt(k, -1);
                if (m == null || limit < 0) continue;
                enforce(p, m, limit);
            }
        }
    }

    private void enforce(Player p, Material m, int limit) {
        PlayerInventory inv = p.getInventory();
        int excess = count(inv, m) - limit;
        if (excess <= 0) return;
        ItemStack[] c = inv.getContents();
        for (int i = c.length - 1; i >= 0 && excess > 0; i--) {
            ItemStack it = c[i];
            if (it == null || it.getType() != m) continue;
            int take = Math.min(excess, it.getAmount());
            ItemStack drop = it.clone();
            drop.setAmount(take);
            it.setAmount(it.getAmount() - take);
            inv.setItem(i, it.getAmount() <= 0 ? null : it);
            Item e = p.getWorld().dropItemNaturally(p.getLocation(), drop);
            e.setPickupDelay(100);
            excess -= take;
        }
        p.sendMessage(plugin.msg("item-limit-dropped",
                Placeholder.unparsed("item", niceName(m)),
                Placeholder.unparsed("limit", String.valueOf(limit))));
    }

    // =====================================================
    //  COMBAT LOG + join/quit
    // =====================================================
    /** Opsi: PvP hanya boleh saat PvP Event berlangsung. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvpControl(EntityDamageByEntityEvent e) {
        if (!plugin.getConfig().getBoolean("events.pvp.control-world-pvp", false)) return;
        if (plugin.events().effective(EventManager.Id.PVP)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        Entity d = e.getDamager();
        if (d instanceof Player p) attacker = p;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim) || attacker.hasPermission(BYPASS)) return;
        e.setCancelled(true);
        notifyOnce(attacker, plugin.msg("pvp-disabled"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("combat-log.enabled", true)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        Entity d = e.getDamager();
        if (d instanceof Player p) attacker = p;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + c.getLong("combat-log.duration-seconds", 20L) * 1000L;
        combat.put(victim.getUniqueId(), until);
        combat.put(attacker.getUniqueId(), until);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.recipes().discover(e.getPlayer());
        plugin.events().showTo(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        lastHit.remove(p.getUniqueId());
        Long until = combat.remove(p.getUniqueId());
        if (plugin.getConfig().getBoolean("combat-log.enabled", true)
                && until != null && until > System.currentTimeMillis()
                && !p.isDead() && !p.hasPermission(BYPASS)) {
            Bukkit.broadcast(plugin.msg("combat-log-killed", Placeholder.unparsed("player", p.getName())));
            p.setHealth(0.0);
        }
        plugin.maces().releaseIfEmpty(p);
    }
}
