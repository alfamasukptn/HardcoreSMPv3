package com.hardcoresmp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** GUI untuk mengatur semua isi config.yml lewat inventory. */
public final class Gui implements Listener {

    private static final MiniMessage MM = HardcoreSMP.MM;

    private final HardcoreSMP plugin;
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    Gui(HardcoreSMP plugin) {
        this.plugin = plugin;
    }

    // =====================================================
    //  Model & helper
    // =====================================================
    enum Type { BOOL, INT, DOUBLE, TEXT, MATERIAL, ENTITY }

    record Setting(String path, String name, Material icon, String desc, Type type,
                   double step, double min, double max, boolean apply) {}

    record Extra(ItemStack item, Runnable action) {}

    record Entry(String id, ItemStack icon) {}

    record Prompt(Consumer<String> handler, Runnable cancel) {}

    interface EntryHandler { void handle(ListMenu m, Entry en, InventoryClickEvent e); }

    interface AddHandler { void handle(ListMenu m, InventoryClickEvent e); }

    private static Setting bool(String path, String name, Material icon, String desc, boolean apply) {
        return new Setting(path, name, icon, desc, Type.BOOL, 0, 0, 0, apply);
    }

    private static Setting num(Type t, String path, String name, Material icon, String desc,
                               double step, double min, double max, boolean apply) {
        return new Setting(path, name, icon, desc, t, step, min, max, apply);
    }

    private static Setting txt(Type t, String path, String name, Material icon, String desc) {
        return new Setting(path, name, icon, desc, t, 0, 0, 0, false);
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    private void commit(boolean apply) {
        plugin.saveConfig();
        if (apply) plugin.applyConfig();
    }

    static Component c(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static String esc(String s) { return MM.escapeTags(s == null ? "" : s); }

    static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    static Material safeIcon(Material m) {
        return m != null && m.isItem() && !m.isAir() ? m : Material.BARRIER;
    }

    static Material mat(String s) {
        return s == null || s.isBlank() ? null : Material.matchMaterial(s.trim());
    }

    static String nice(Material m) { return m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    private static ItemStack pane() { return item(Material.GRAY_STAINED_GLASS_PANE, " "); }

    private static void fill(Inventory inv, int from, int to) {
        for (int i = from; i < to; i++) inv.setItem(i, pane());
    }

    private Component err(String s) {
        return c("<red>" + esc(s));
    }

    // =====================================================
    //  Prompt lewat chat
    // =====================================================
    void prompt(Player p, String text, Consumer<String> handler, Runnable cancel) {
        prompts.put(p.getUniqueId(), new Prompt(handler, cancel));
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            p.sendMessage(c("<yellow>" + esc(text) + " <gray>(ketik <white>cancel</white> untuk batal)"));
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) pr.cancel().run();
            else pr.handler().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }

    // =====================================================
    //  Event inventory
    // =====================================================
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu m)) return;
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory().equals(top)) {
            e.setCancelled(true);
            m.click(e);
        } else if (e.isShiftClick()
                || e.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu)) return;
        for (int slot : e.getRawSlots()) {
            if (slot < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // =====================================================
    //  Base menu
    // =====================================================
    abstract class Menu implements InventoryHolder {
        protected final Inventory inv;
        protected Player viewer;

        Menu(Component title, int size) {
            this.inv = Bukkit.createInventory(this, size, title);
        }

        abstract void render();

        abstract void click(InventoryClickEvent e);

        void open(Player p) {
            viewer = p;
            inv.clear();
            render();
            p.openInventory(inv);
        }

        void refresh() {
            inv.clear();
            render();
        }

        @Override
        public Inventory getInventory() { return inv; }
    }

    /** Menu berisi tombol biasa. */
    final class ButtonMenu extends Menu {
        private final Map<Integer, Extra> buttons = new LinkedHashMap<>();

        ButtonMenu(String title, int size) { super(c(title), size); }

        ButtonMenu btn(int slot, ItemStack it, Runnable r) {
            buttons.put(slot, new Extra(it, r));
            return this;
        }

        @Override
        void render() {
            fill(inv, 0, inv.getSize());
            buttons.forEach((slot, x) -> inv.setItem(slot, x.item()));
        }

        @Override
        void click(InventoryClickEvent e) {
            Extra x = buttons.get(e.getRawSlot());
            if (x != null) x.action().run();
        }
    }

    // =====================================================
    //  Menu setting (daftar pengaturan)
    // =====================================================
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    final class SettingsMenu extends Menu {
        private final List<Setting> settings;
        private final List<Extra> extras;
        private final Runnable back;

        SettingsMenu(String title, List<Setting> settings, List<Extra> extras, Runnable back) {
            super(c(title), 36);
            this.settings = settings;
            this.extras = extras;
            this.back = back;
        }

        @Override
        void render() {
            fill(inv, 0, 36);
            for (int i = 0; i < settings.size(); i++) inv.setItem(SLOTS[i], settingItem(settings.get(i)));
            for (int i = 0; i < extras.size(); i++) inv.setItem(SLOTS[settings.size() + i], extras.get(i).item());
            inv.setItem(31, item(Material.ARROW, "<yellow>Kembali"));
        }

        @Override
        void click(InventoryClickEvent e) {
            int raw = e.getRawSlot();
            if (raw == 31) {
                back.run();
                return;
            }
            for (int i = 0; i < SLOTS.length; i++) {
                if (SLOTS[i] != raw) continue;
                if (i < settings.size()) handleSetting(viewer, settings.get(i), e, this);
                else if (i - settings.size() < extras.size()) extras.get(i - settings.size()).action().run();
                return;
            }
        }
    }

    ItemStack settingItem(Setting s) {
        FileConfiguration c = cfg();
        String value;
        String help;
        switch (s.type()) {
            case BOOL -> {
                value = c.getBoolean(s.path()) ? "<green>ON" : "<red>OFF";
                help = "<dark_gray>Klik: ON/OFF";
            }
            case INT -> {
                value = "<yellow>" + c.getInt(s.path());
                help = "<dark_gray>Kiri +" + (int) s.step() + " | Kanan -" + (int) s.step() + " | Shift = x10";
            }
            case DOUBLE -> {
                value = "<yellow>" + c.getDouble(s.path());
                help = "<dark_gray>Kiri +" + s.step() + " | Kanan -" + s.step() + " | Shift = x10";
            }
            case MATERIAL -> {
                value = "<yellow>" + esc(c.getString(s.path(), "-"));
                help = "<dark_gray>Klik dengan item di cursor, atau klik kosong lalu ketik";
            }
            default -> {
                value = "<yellow>" + esc(c.getString(s.path(), "-"));
                help = "<dark_gray>Klik lalu ketik nilai baru di chat";
            }
        }
        return item(safeIcon(s.icon()), "<gold>" + s.name(), "<gray>" + esc(s.desc()), "", "<white>Nilai: " + value, help);
    }

    void handleSetting(Player p, Setting s, InventoryClickEvent e, Menu menu) {
        FileConfiguration c = cfg();
        switch (s.type()) {
            case BOOL -> {
                c.set(s.path(), !c.getBoolean(s.path()));
                commit(s.apply());
                menu.refresh();
            }
            case INT, DOUBLE -> {
                double step = s.step() * (e.isShiftClick() ? 10 : 1);
                double v = c.getDouble(s.path());
                if (e.isLeftClick()) v += step;
                else if (e.isRightClick()) v -= step;
                else return;
                v = Math.max(s.min(), Math.min(s.max(), v));
                if (s.type() == Type.INT) c.set(s.path(), (int) Math.round(v));
                else c.set(s.path(), Math.round(v * 100.0) / 100.0);
                commit(s.apply());
                menu.refresh();
            }
            case TEXT -> prompt(p, "Ketik nilai baru untuk '" + s.name() + "' (MiniMessage didukung).", in -> {
                cfg().set(s.path(), in);
                commit(s.apply());
                menu.open(p);
            }, () -> menu.open(p));
            case MATERIAL -> {
                ItemStack cur = e.getCursor();
                if (cur != null && !cur.getType().isAir()) {
                    c.set(s.path(), cur.getType().name());
                    commit(s.apply());
                    menu.refresh();
                } else {
                    prompt(p, "Ketik nama material (contoh: DIAMOND).", in -> {
                        Material m = mat(in);
                        if (m == null || !m.isItem()) p.sendMessage(err("Material tidak valid: " + in));
                        else {
                            cfg().set(s.path(), m.name());
                            commit(s.apply());
                        }
                        menu.open(p);
                    }, () -> menu.open(p));
                }
            }
            case ENTITY -> prompt(p, "Ketik nama entity (contoh: ZOMBIE, WARDEN, EVOKER).", in -> {
                try {
                    EntityType t = EntityType.valueOf(in.toUpperCase(Locale.ROOT));
                    cfg().set(s.path(), t.name());
                    commit(s.apply());
                } catch (IllegalArgumentException ex) {
                    p.sendMessage(err("Entity tidak valid: " + in));
                }
                menu.open(p);
            }, () -> menu.open(p));
        }
    }

    // =====================================================
    //  Menu daftar (entries)
    // =====================================================
    interface EntrySupplier { List<Entry> get(); }

    final class ListMenu extends Menu {
        private final EntrySupplier supplier;
        private final EntryHandler onEntry;
        private final AddHandler onAdd;
        private final Setting toggle;
        private final Map<Integer, Extra> extras;
        private final Runnable back;
        private List<Entry> current = new ArrayList<>();

        ListMenu(String title, EntrySupplier supplier, EntryHandler onEntry, AddHandler onAdd,
                 Setting toggle, Map<Integer, Extra> extras, Runnable back) {
            super(c(title), 54);
            this.supplier = supplier;
            this.onEntry = onEntry;
            this.onAdd = onAdd;
            this.toggle = toggle;
            this.extras = extras;
            this.back = back;
        }

        @Override
        void render() {
            fill(inv, 45, 54);
            current = supplier.get();
            for (int i = 0; i < Math.min(45, current.size()); i++) inv.setItem(i, current.get(i).icon());
            inv.setItem(45, item(Material.ARROW, "<yellow>Kembali"));
            if (onAdd != null) {
                inv.setItem(49, item(Material.LIME_DYE, "<green>Tambah",
                        "<gray>Klik dengan item di cursor,", "<gray>atau klik kosong lalu ketik di chat"));
            }
            if (toggle != null) inv.setItem(53, settingItem(toggle));
            extras.forEach((slot, x) -> inv.setItem(slot, x.item()));
        }

        @Override
        void click(InventoryClickEvent e) {
            int raw = e.getRawSlot();
            if (raw < 45) {
                if (raw < current.size()) onEntry.handle(this, current.get(raw), e);
            } else if (raw == 45) back.run();
            else if (raw == 49 && onAdd != null) onAdd.handle(this, e);
            else if (raw == 53 && toggle != null) handleSetting(viewer, toggle, e, this);
            else if (extras.containsKey(raw)) extras.get(raw).action().run();
        }
    }

    // =====================================================
    //  Menu-menu
    // =====================================================
    public void openMain(Player p) {
        ButtonMenu m = new ButtonMenu("<gold><bold>HardcoreSMP Settings", 36);
        m.btn(10, item(Material.PLAYER_HEAD, "<red>PvP Head", "<gray>Drop kepala saat PvP"), () -> openPvpHead(p));
        m.btn(11, item(Material.ENCHANTED_GOLDEN_APPLE, "<gold>Head -> Golden Apple", "<gray>Recipe & pengaturan"), () -> openHeadApple(p));
        m.btn(12, item(Material.TOTEM_OF_UNDYING, "<yellow>Totem", "<gray>Drop rate dari Evoker"), () -> openTotem(p));
        m.btn(13, item(Material.HEAVY_CORE, "<aqua>Heavy Core", "<gray>Drop rate dari Ominous Vault"), () -> openHeavyCore(p));
        m.btn(14, item(Material.ROTTEN_FLESH, "<green>Mob Drops", "<gray>Drop custom dari mob"), () -> openMobDrops(p));
        m.btn(15, item(Material.CRAFTING_TABLE, "<gold>EZ Craft", "<gray>Recipe custom"), () -> openEzCraft(p));
        m.btn(16, item(Material.MACE, "<dark_aqua>Mace Limit", "<gray>Batas pemegang mace"), () -> openMace(p));
        m.btn(19, item(Material.CHEST, "<white>Item Limits", "<gray>Batas item per player"), () -> openItemLimits(p));
        m.btn(20, item(Material.IRON_SWORD, "<red>Combat Log", "<gray>Logout saat bertarung = mati"), () -> openCombat(p));
        m.btn(21, item(Material.CLOCK, "<gray>Umum", "<gray>Interval pengecekan"), () -> openGeneral(p));
        m.btn(22, item(Material.BELL, "<light_purple>Events", "<gray>Event berwaktu + bossbar"), () -> openEvents(p));
        m.btn(25, item(Material.COMMAND_BLOCK, "<green>Reload dari file", "<gray>Baca ulang config.yml dari disk"), () -> {
            plugin.applyConfig();
            p.sendMessage(plugin.msg("reloaded"));
        });
        m.btn(31, item(Material.BARRIER, "<red>Tutup"), p::closeInventory);
        m.open(p);
    }

    private void openPvpHead(Player p) {
        new SettingsMenu("<red>PvP Head", List.of(
                bool("pvp-head.enabled", "Aktifkan PvP Head", Material.PLAYER_HEAD, "Korban PvP drop kepala", false),
                num(Type.DOUBLE, "pvp-head.drop-chance-percent", "Drop rate saat event (%)", Material.EXPERIENCE_BOTTLE, "Peluang kepala drop saat PvP Event", 5, 0, 100, false),
                num(Type.DOUBLE, "pvp-head.base-drop-chance-percent", "Drop rate di luar event (%)", Material.GLASS_BOTTLE, "Dipakai jika event mode berwaktu", 5, 0, 100, false),
                bool("pvp-head.give-directly-to-killer", "Langsung ke killer", Material.HOPPER, "Kepala masuk inventory killer", false),
                bool("pvp-head.anti-farm.block-same-ip", "Blokir IP sama", Material.SHIELD, "Anti-farm: killer & korban satu IP", false),
                num(Type.INT, "pvp-head.anti-farm.same-victim-cooldown-minutes", "Cooldown korban sama (menit)", Material.CLOCK, "0 = nonaktif", 5, 0, 1440, false),
                txt(Type.TEXT, "pvp-head.name", "Nama item", Material.NAME_TAG, "Placeholder: <victim> <killer>"),
                txt(Type.TEXT, "pvp-head.compat.lethal-hit-mode", "Mode death animation", Material.SKELETON_SKULL, "OFF / SMART / ALWAYS (untuk plugin death animation)"),
                num(Type.INT, "pvp-head.compat.killer-memory-seconds", "Ingatan killer (detik)", Material.CLOCK, "Dipakai jika getKiller() kosong", 5, 0, 120, false),
                num(Type.INT, "pvp-head.compat.verify-delay-ticks", "Jeda verifikasi (tick)", Material.REPEATER, "Naikkan jika animasi lambat mulai", 5, 1, 200, false),
                bool("pvp-head.compat.debug", "Debug log", Material.WRITABLE_BOOK, "Log diagnosa ke console", false)
        ), List.of(), () -> openMain(p)).open(p);
    }

    private void openHeadApple(Player p) {
        new SettingsMenu("<gold>Head -> Enchanted Golden Apple", List.of(
                bool("head-apple.enabled", "Aktifkan recipe", Material.ENCHANTED_GOLDEN_APPLE, "Craft kepala jadi Enchanted Golden Apple", true),
                bool("head-apple.require-pvp-head", "Wajib kepala PvP", Material.PLAYER_HEAD, "Hanya kepala hasil PvP yang sah", false),
                num(Type.INT, "head-apple.amount", "Jumlah hasil", Material.GOLDEN_APPLE, "Jumlah apple per craft", 1, 1, 64, true)
        ), List.of(new Extra(item(Material.CRAFTING_TABLE, "<green>Edit recipe", "<gray>Atur bahan di grid 3x3"),
                () -> openRecipeEditor(p, "head-apple", true, () -> openHeadApple(p)))), () -> openMain(p)).open(p);
    }

    private void openTotem(Player p) {
        new SettingsMenu("<yellow>Totem", List.of(
                bool("totem.enabled", "Aktifkan", Material.TOTEM_OF_UNDYING, "Ubah drop totem dari Evoker", false),
                num(Type.DOUBLE, "totem.evoker-drop-chance-percent", "Drop rate saat event (%)", Material.EXPERIENCE_BOTTLE, "Vanilla = 100", 5, 0, 100, false),
                num(Type.DOUBLE, "totem.base-drop-chance-percent", "Drop rate di luar event (%)", Material.GLASS_BOTTLE, "Dipakai jika event mode berwaktu", 5, 0, 100, false)
        ), List.of(), () -> openMain(p)).open(p);
    }

    private void openHeavyCore(Player p) {
        new SettingsMenu("<aqua>Heavy Core", List.of(
                bool("heavy-core.enabled", "Aktifkan", Material.HEAVY_CORE, "Ubah drop Heavy Core dari Ominous Vault", false),
                num(Type.DOUBLE, "heavy-core.vault-drop-chance-percent", "Drop rate saat event (%)", Material.EXPERIENCE_BOTTLE, "Gagal = item tidak keluar", 5, 0, 100, false),
                num(Type.DOUBLE, "heavy-core.base-drop-chance-percent", "Drop rate di luar event (%)", Material.GLASS_BOTTLE, "Dipakai jika event mode berwaktu", 5, 0, 100, false)
        ), List.of(), () -> openMain(p)).open(p);
    }

    private void openCombat(Player p) {
        new SettingsMenu("<red>Combat Log", List.of(
                bool("combat-log.enabled", "Aktifkan", Material.IRON_SWORD, "Logout saat bertarung = mati", false),
                num(Type.INT, "combat-log.duration-seconds", "Durasi (detik)", Material.CLOCK, "Lama status bertarung", 5, 1, 600, false)
        ), List.of(), () -> openMain(p)).open(p);
    }

    private void openGeneral(Player p) {
        new SettingsMenu("<gray>Umum", List.of(
                num(Type.INT, "check-interval-ticks", "Interval cek (tick)", Material.CLOCK, "20 tick = 1 detik", 5, 5, 1200, true)
        ), List.of(), () -> openMain(p)).open(p);
    }

    private void openMace(Player p) {
        new SettingsMenu("<dark_aqua>Mace Limit", List.of(
                bool("mace.enabled", "Aktifkan", Material.MACE, "Batasi pemegang mace", false),
                num(Type.INT, "mace.max-holders", "Maks pemegang", Material.PLAYER_HEAD, "-1 = tanpa batas, 0 = tidak ada", 1, -1, 100, false),
                bool("mace.allow-crafting", "Boleh craft", Material.CRAFTING_TABLE, "OFF = mace tidak bisa di-craft", false),
                bool("mace.count-ender-chest", "Hitung ender chest", Material.ENDER_CHEST, "Mace di ender chest tetap pakai slot", false)
        ), List.of(new Extra(item(Material.BOOK, "<green>Daftar pemegang", "<gray>Lihat & hapus pemegang mace"),
                () -> openMaceHolders(p))), () -> openMain(p)).open(p);
    }

    private void openMaceHolders(Player p) {
        new ListMenu("<dark_aqua>Pemegang Mace",
                () -> {
                    List<Entry> out = new ArrayList<>();
                    for (UUID u : plugin.maces().holders()) {
                        String n = Bukkit.getOfflinePlayer(u).getName();
                        out.add(new Entry(u.toString(), item(Material.PLAYER_HEAD, "<yellow>" + esc(n == null ? u.toString() : n),
                                "<dark_gray>Klik: hapus dari daftar")));
                    }
                    return out;
                },
                (m, en, e) -> {
                    plugin.maces().release(UUID.fromString(en.id()));
                    m.refresh();
                },
                null, null, new HashMap<>(), () -> openMace(p)).open(p);
    }

    // ---------- Events ----------
    private static Material eventIcon(EventManager.Id id) {
        return switch (id) {
            case PVP -> Material.DIAMOND_SWORD;
            case TOTEM -> Material.TOTEM_OF_UNDYING;
            case HEAVYCORE -> Material.HEAVY_CORE;
            case MOBDROPS -> Material.ROTTEN_FLESH;
        };
    }

    private void openEvents(Player p) {
        new ListMenu("<light_purple>Events",
                () -> {
                    List<Entry> out = new ArrayList<>();
                    for (EventManager.Id id : EventManager.Id.values()) {
                        boolean act = plugin.events().isActive(id);
                        out.add(new Entry(id.key, item(eventIcon(id), "<yellow>" + id.label,
                                "<gray>Status: " + (act ? "<green>AKTIF (" + plugin.events().remainingText(id) + ")" : "<red>Tidak aktif"),
                                "<gray>Durasi default: <white>" + cfg().getInt("events." + id.key + ".duration-minutes", 30) + " menit",
                                "<gray>Mode: <white>" + (plugin.events().timed(id) ? "Berwaktu" : "Selalu aktif"),
                                "", "<dark_gray>Kiri: mulai / stop",
                                "<dark_gray>Shift+Kiri: mulai (ketik menit)",
                                "<dark_gray>Kanan: pengaturan")));
                    }
                    return out;
                },
                (m, en, e) -> {
                    EventManager.Id id = EventManager.Id.from(en.id());
                    if (id == null) return;
                    if (e.isRightClick()) {
                        openEventSettings(p, id);
                    } else if (e.isLeftClick() && e.isShiftClick()) {
                        prompt(p, "Ketik durasi " + id.label + " Event dalam menit.", in -> {
                            try {
                                int mins = Integer.parseInt(in.trim());
                                if (mins <= 0) throw new NumberFormatException();
                                plugin.events().start(id, mins);
                            } catch (NumberFormatException ex) {
                                p.sendMessage(err("Angka tidak valid: " + in));
                            }
                            m.open(p);
                        }, () -> m.open(p));
                    } else if (e.isLeftClick()) {
                        if (plugin.events().isActive(id)) plugin.events().stop(id, true);
                        else plugin.events().start(id, 0);
                        m.refresh();
                    }
                },
                null, null, new HashMap<>(), () -> openMain(p)).open(p);
    }

    private void openEventSettings(Player p, EventManager.Id id) {
        String k = "events." + id.key;
        List<Setting> l = new ArrayList<>();
        l.add(bool(k + ".timed", "Mode berwaktu", Material.CLOCK, "ON = kuat hanya saat event, OFF = selalu aktif", false));
        l.add(num(Type.INT, k + ".duration-minutes", "Durasi (menit)", Material.REPEATER, "Durasi event default", 5, 1, 1440, false));
        l.add(num(Type.INT, k + ".auto-interval-minutes", "Auto-start tiap (menit)", Material.COMPARATOR, "0 = hanya manual", 10, 0, 10080, false));
        l.add(bool(k + ".bossbar.enabled", "Bossbar", Material.DRAGON_HEAD, "Tampilkan hitung mundur di bossbar", false));
        l.add(txt(Type.TEXT, k + ".bossbar.title", "Judul bossbar", Material.NAME_TAG, "Placeholder: <time> <event>"));
        l.add(txt(Type.TEXT, k + ".bossbar.color", "Warna bossbar", Material.RED_DYE, "PINK BLUE RED GREEN YELLOW PURPLE WHITE"));
        l.add(txt(Type.TEXT, k + ".bossbar.style", "Gaya bossbar", Material.STRING, "PROGRESS NOTCHED_6/10/12/20"));
        l.add(txt(Type.TEXT, k + ".messages.start", "Pesan mulai", Material.OAK_SIGN, "Placeholder: <minutes> <event>"));
        l.add(txt(Type.TEXT, k + ".messages.end", "Pesan selesai", Material.SPRUCE_SIGN, "Placeholder: <event>"));
        if (id == EventManager.Id.PVP) {
            l.add(bool(k + ".control-world-pvp", "PvP hanya saat event", Material.DIAMOND_SWORD, "Di luar event, serangan antar player diblokir", false));
        }
        List<Extra> ex = List.of(new Extra(item(Material.LIME_DYE, "<green>Mulai / Stop sekarang", "<gray>Pakai durasi default"), () -> {
            if (plugin.events().isActive(id)) plugin.events().stop(id, true);
            else plugin.events().start(id, 0);
            p.sendMessage(c("<gray>" + id.label + " Event: " + (plugin.events().isActive(id) ? "<green>dimulai" : "<red>dihentikan")));
        }));
        new SettingsMenu("<light_purple>Event: " + id.label, l, ex, () -> openEvents(p)).open(p);
    }

    // ---------- Mob drops ----------
    private void openMobDrops(Player p) {
        new ListMenu("<green>Mob Drops",
                () -> {
                    List<Entry> out = new ArrayList<>();
                    ConfigurationSection sec = cfg().getConfigurationSection("mob-drops.entries");
                    if (sec != null) {
                        for (String id : sec.getKeys(false)) {
                            ConfigurationSection s = sec.getConfigurationSection(id);
                            if (s == null) continue;
                            out.add(new Entry(id, item(safeIcon(mat(s.getString("item"))), "<yellow>" + esc(id),
                                    "<gray>Entity: <white>" + esc(s.getString("entity")),
                                    "<gray>Item: <white>" + esc(s.getString("item")),
                                    "<gray>Chance: <white>" + s.getDouble("chance-percent") + "%",
                                    "<gray>Jumlah: <white>" + s.getInt("min") + "-" + s.getInt("max"),
                                    "", "<dark_gray>Kiri: edit | Shift+Kanan: hapus")));
                        }
                    }
                    return out;
                },
                (m, en, e) -> {
                    if (e.isRightClick() && e.isShiftClick()) {
                        cfg().set("mob-drops.entries." + en.id(), null);
                        commit(false);
                        m.refresh();
                    } else if (e.isLeftClick()) openMobDropEdit(p, en.id());
                },
                (m, e) -> prompt(p, "Ketik ID entry baru (huruf/angka/_).", in -> {
                    String id = in.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                    String b = "mob-drops.entries." + id;
                    if (cfg().contains(b)) {
                        p.sendMessage(err("ID sudah ada: " + id));
                        m.open(p);
                        return;
                    }
                    cfg().set(b + ".entity", "ZOMBIE");
                    cfg().set(b + ".item", "DIAMOND");
                    cfg().set(b + ".chance-percent", 10.0);
                    cfg().set(b + ".min", 1);
                    cfg().set(b + ".max", 1);
                    commit(false);
                    openMobDropEdit(p, id);
                }, () -> m.open(p)),
                bool("mob-drops.enabled", "Aktifkan Mob Drops", Material.LEVER, "Master switch", false),
                new HashMap<>(), () -> openMain(p)).open(p);
    }

    private void openMobDropEdit(Player p, String id) {
        String b = "mob-drops.entries." + id;
        new SettingsMenu("<green>Edit: " + esc(id), List.of(
                txt(Type.ENTITY, b + ".entity", "Entity", Material.ZOMBIE_HEAD, "Mob yang di-kill"),
                txt(Type.MATERIAL, b + ".item", "Item drop", Material.DIAMOND, "Item yang jatuh"),
                num(Type.DOUBLE, b + ".chance-percent", "Chance (%)", Material.EXPERIENCE_BOTTLE, "Peluang drop", 5, 0, 100, false),
                num(Type.INT, b + ".min", "Jumlah min", Material.IRON_NUGGET, "Jumlah minimum", 1, 1, 64, false),
                num(Type.INT, b + ".max", "Jumlah maks", Material.IRON_INGOT, "Jumlah maksimum", 1, 1, 64, false)
        ), List.of(), () -> openMobDrops(p)).open(p);
    }

    // ---------- EZ Craft ----------
    private void openEzCraft(Player p) {
        Map<Integer, Extra> extras = new HashMap<>();
        extras.put(47, new Extra(item(Material.TNT, "<red>Hapus recipe vanilla", "<gray>Daftar item yang recipe vanilla-nya dihapus"),
                () -> openRemoveVanilla(p)));
        new ListMenu("<gold>EZ Craft",
                () -> {
                    List<Entry> out = new ArrayList<>();
                    ConfigurationSection sec = cfg().getConfigurationSection("ez-craft.recipes");
                    if (sec != null) {
                        for (String id : sec.getKeys(false)) {
                            ConfigurationSection s = sec.getConfigurationSection(id);
                            if (s == null) continue;
                            ItemStack ic = item(safeIcon(mat(s.getString("result"))), "<yellow>" + esc(id),
                                    "<gray>Tipe: <white>" + esc(s.getString("type", "shaped")),
                                    "<gray>Status: " + (s.getBoolean("enabled", true) ? "<green>ON" : "<red>OFF"),
                                    "", "<dark_gray>Kiri: edit recipe", "<dark_gray>Kanan: ON/OFF", "<dark_gray>Shift+Kanan: hapus");
                            ic.setAmount(Math.max(1, Math.min(64, s.getInt("amount", 1))));
                            out.add(new Entry(id, ic));
                        }
                    }
                    return out;
                },
                (m, en, e) -> {
                    String b = "ez-craft.recipes." + en.id();
                    if (e.isRightClick() && e.isShiftClick()) {
                        cfg().set(b, null);
                        commit(true);
                        m.refresh();
                    } else if (e.isRightClick()) {
                        cfg().set(b + ".enabled", !cfg().getBoolean(b + ".enabled", true));
                        commit(true);
                        m.refresh();
                    } else if (e.isLeftClick()) {
                        openRecipeEditor(p, en.id(), false, () -> openEzCraft(p));
                    }
                },
                (m, e) -> prompt(p, "Ketik ID recipe baru (huruf kecil/angka/_).", in -> {
                    String id = in.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                    if (cfg().contains("ez-craft.recipes." + id)) {
                        p.sendMessage(err("ID sudah ada: " + id));
                        m.open(p);
                    } else {
                        openRecipeEditor(p, id, false, () -> openEzCraft(p));
                    }
                }, () -> m.open(p)),
                bool("ez-craft.enabled", "Aktifkan EZ Craft", Material.LEVER, "Master switch", true),
                extras, () -> openMain(p)).open(p);
    }

    private void openRemoveVanilla(Player p) {
        new ListMenu("<red>Hapus Recipe Vanilla",
                () -> {
                    List<Entry> out = new ArrayList<>();
                    for (String s : cfg().getStringList("ez-craft.remove-vanilla-recipes")) {
                        out.add(new Entry(s, item(safeIcon(mat(s)), "<yellow>" + esc(s),
                                "<dark_gray>Klik: keluarkan dari daftar",
                                "<dark_gray>(recipe kembali setelah restart server)")));
                    }
                    return out;
                },
                (m, en, e) -> {
                    List<String> l = new ArrayList<>(cfg().getStringList("ez-craft.remove-vanilla-recipes"));
                    l.remove(en.id());
                    cfg().set("ez-craft.remove-vanilla-recipes", l);
                    commit(false);
                    m.refresh();
                },
                (m, e) -> {
                    ItemStack cur = e.getCursor();
                    if (cur != null && !cur.getType().isAir()) {
                        addVanillaRemoval(cur.getType());
                        m.refresh();
                    } else {
                        prompt(p, "Ketik nama material yang recipe-nya mau dihapus (contoh: TNT_MINECART).", in -> {
                            Material mt = mat(in);
                            if (mt == null || !mt.isItem()) p.sendMessage(err("Material tidak valid: " + in));
                            else addVanillaRemoval(mt);
                            m.open(p);
                        }, () -> m.open(p));
                    }
                },
                null, new HashMap<>(), () -> openEzCraft(p)).open(p);
    }

    private void addVanillaRemoval(Material m) {
        List<String> l = new ArrayList<>(cfg().getStringList("ez-craft.remove-vanilla-recipes"));
        if (!l.contains(m.name())) l.add(m.name());
        cfg().set("ez-craft.remove-vanilla-recipes", l);
        commit(true);
    }

    // ---------- Item limits ----------
    private void openItemLimits(Player p) {
        new ListMenu("<white>Item Limits",
                () -> {
                    List<Entry> out = new ArrayList<>();
                    ConfigurationSection sec = cfg().getConfigurationSection("item-limits.limits");
                    if (sec != null) {
                        for (String k : sec.getKeys(false)) {
                            ItemStack ic = item(safeIcon(mat(k)), "<yellow>" + esc(k),
                                    "<gray>Batas: <white>" + sec.getInt(k),
                                    "", "<dark_gray>Kiri +1 | Kanan -1 | Shift = x8", "<dark_gray>Q: hapus");
                            out.add(new Entry(k, ic));
                        }
                    }
                    return out;
                },
                (m, en, e) -> {
                    String path = "item-limits.limits." + en.id();
                    ClickType t = e.getClick();
                    if (t == ClickType.DROP || t == ClickType.CONTROL_DROP) {
                        cfg().set(path, null);
                    } else if (e.isLeftClick() || e.isRightClick()) {
                        int step = e.isShiftClick() ? 8 : 1;
                        int v = cfg().getInt(path, 0) + (e.isLeftClick() ? step : -step);
                        cfg().set(path, Math.max(0, Math.min(2304, v)));
                    } else return;
                    commit(false);
                    m.refresh();
                },
                (m, e) -> {
                    ItemStack cur = e.getCursor();
                    if (cur != null && !cur.getType().isAir()) {
                        addLimit(cur.getType());
                        m.refresh();
                    } else {
                        prompt(p, "Ketik nama material (contoh: TOTEM_OF_UNDYING).", in -> {
                            Material mt = mat(in);
                            if (mt == null || !mt.isItem()) p.sendMessage(err("Material tidak valid: " + in));
                            else addLimit(mt);
                            m.open(p);
                        }, () -> m.open(p));
                    }
                },
                bool("item-limits.enabled", "Aktifkan Item Limits", Material.LEVER, "Master switch", false),
                new HashMap<>(), () -> openMain(p)).open(p);
    }

    private void addLimit(Material m) {
        String path = "item-limits.limits." + m.name();
        if (!cfg().contains(path)) cfg().set(path, 4);
        commit(false);
    }

    // =====================================================
    //  Recipe editor (grid 3x3)
    // =====================================================
    void openRecipeEditor(Player p, String id, boolean head, Runnable back) {
        new RecipeEditor(id, head, back).open(p);
    }

    final class RecipeEditor extends Menu {
        private final int[] grid = {10, 11, 12, 19, 20, 21, 28, 29, 30};
        private static final int RESULT = 24, BACK = 36, SAVE = 40, TYPE = 42;

        private final String id;
        private final boolean head;
        private final Runnable back;
        private final Material[] cells = new Material[9];
        private Material result;
        private int amount = 1;
        private boolean shapeless = false;

        RecipeEditor(String id, boolean head, Runnable back) {
            super(c("<gold>Recipe: <white>" + esc(id)), 45);
            this.id = id;
            this.head = head;
            this.back = back;
            load();
        }

        private String base() { return head ? "head-apple" : "ez-craft.recipes." + id; }

        private void load() {
            ConfigurationSection s = cfg().getConfigurationSection(base());
            result = head ? Material.ENCHANTED_GOLDEN_APPLE : Material.STONE;
            if (s == null) return;
            amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
            if (!head) {
                Material r = mat(s.getString("result"));
                if (r != null) result = r;
            }
            shapeless = s.getString("type", "shaped").equalsIgnoreCase("shapeless");
            if (shapeless) {
                int i = 0;
                for (String line : s.getStringList("ingredients")) {
                    String[] parts = line.split(":");
                    Material m = mat(parts[0]);
                    if (m == null) continue;
                    int n = 1;
                    try {
                        if (parts.length > 1) n = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                    for (int k = 0; k < n && i < 9; k++) cells[i++] = m;
                }
            } else {
                List<String> shape = s.getStringList("shape");
                ConfigurationSection ing = s.getConfigurationSection("ingredients");
                if (ing == null) return;
                for (int r = 0; r < Math.min(3, shape.size()); r++) {
                    String row = shape.get(r);
                    for (int col = 0; col < Math.min(3, row.length()); col++) {
                        char ch = row.charAt(col);
                        if (ch == ' ') continue;
                        cells[r * 3 + col] = mat(ing.getString(String.valueOf(ch), ""));
                    }
                }
            }
        }

        @Override
        void render() {
            fill(inv, 0, 45);
            for (int i = 0; i < 9; i++) {
                Material m = cells[i];
                inv.setItem(grid[i], m == null
                        ? item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Kosong",
                        "<dark_gray>Klik dengan item di cursor: isi",
                        "<dark_gray>Klik kanan: ketik nama material")
                        : item(safeIcon(m), "<yellow>" + nice(m),
                        "<dark_gray>Klik kiri: hapus (cursor kosong)",
                        "<dark_gray>Klik kanan: ganti lewat chat"));
            }
            ItemStack r = item(safeIcon(result), "<green>Hasil: " + nice(result), "<gray>Jumlah: <white>" + amount,
                    head ? "<dark_gray>Hasil terkunci (Enchanted Golden Apple)" : "<dark_gray>Klik dengan item di cursor: ganti hasil",
                    "<dark_gray>Kiri +1 | Kanan -1 | Shift = 8");
            r.setAmount(amount);
            inv.setItem(RESULT, r);
            inv.setItem(23, item(Material.ARROW, " "));
            inv.setItem(4, item(Material.PAPER, "<gold>Petunjuk",
                    "<gray>Isi grid 3x3 dengan bahan.", "<gray>Cursor berisi item = set slot.", "<gray>Klik kanan slot = ketik material."));
            inv.setItem(BACK, item(Material.ARROW, "<yellow>Kembali (tanpa simpan)"));
            inv.setItem(SAVE, item(Material.EMERALD, "<green><bold>Simpan & Terapkan"));
            inv.setItem(TYPE, item(Material.CRAFTING_TABLE, "<aqua>Tipe: " + (shapeless ? "Shapeless" : "Shaped"),
                    "<gray>Shaped = posisi bahan berpengaruh", "<gray>Shapeless = posisi bebas", "<dark_gray>Klik untuk ganti"));
        }

        @Override
        void click(InventoryClickEvent e) {
            int raw = e.getRawSlot();
            for (int i = 0; i < 9; i++) {
                if (grid[i] != raw) continue;
                final int idx = i;
                ItemStack cur = e.getCursor();
                if (cur != null && !cur.getType().isAir()) {
                    cells[idx] = cur.getType();
                } else if (e.isRightClick()) {
                    prompt(viewer, "Ketik nama material untuk slot ini (contoh: GOLD_BLOCK, PLAYER_HEAD).", in -> {
                        Material m = mat(in);
                        if (m == null || !m.isItem()) viewer.sendMessage(err("Material tidak valid: " + in));
                        else cells[idx] = m;
                        open(viewer);
                    }, () -> open(viewer));
                    return;
                } else {
                    cells[idx] = null;
                }
                refresh();
                return;
            }
            switch (raw) {
                case RESULT -> {
                    ItemStack cur = e.getCursor();
                    if (!head && cur != null && !cur.getType().isAir()) {
                        result = cur.getType();
                    } else {
                        int step = e.isShiftClick() ? 8 : 1;
                        if (e.isLeftClick()) amount = Math.min(64, amount + step);
                        else if (e.isRightClick()) amount = Math.max(1, amount - step);
                    }
                    refresh();
                }
                case TYPE -> {
                    shapeless = !shapeless;
                    refresh();
                }
                case BACK -> back.run();
                case SAVE -> save();
                default -> { }
            }
        }

        private void save() {
            boolean any = false;
            for (Material m : cells) if (m != null) any = true;
            if (!any) {
                viewer.sendMessage(err("Grid masih kosong. Isi minimal 1 bahan."));
                return;
            }
            FileConfiguration c = cfg();
            String b = base();
            if (!head && !c.contains(b + ".enabled")) c.set(b + ".enabled", true);
            c.set(b + ".type", shapeless ? "shapeless" : "shaped");
            c.set(b + ".amount", amount);
            if (!head) c.set(b + ".result", result.name());
            c.set(b + ".shape", null);
            c.set(b + ".ingredients", null);

            if (shapeless) {
                Map<Material, Integer> count = new LinkedHashMap<>();
                for (Material m : cells) if (m != null) count.merge(m, 1, Integer::sum);
                List<String> lines = new ArrayList<>();
                count.forEach((m, n) -> lines.add(m.name() + ":" + n));
                c.set(b + ".ingredients", lines);
            } else {
                int minR = 3, maxR = -1, minC = 3, maxC = -1;
                for (int i = 0; i < 9; i++) {
                    if (cells[i] == null) continue;
                    minR = Math.min(minR, i / 3);
                    maxR = Math.max(maxR, i / 3);
                    minC = Math.min(minC, i % 3);
                    maxC = Math.max(maxC, i % 3);
                }
                Map<Material, Character> sym = new LinkedHashMap<>();
                String letters = "ABCDEFGHI";
                List<String> shape = new ArrayList<>();
                for (int r = minR; r <= maxR; r++) {
                    StringBuilder row = new StringBuilder();
                    for (int col = minC; col <= maxC; col++) {
                        Material m = cells[r * 3 + col];
                        if (m == null) row.append(' ');
                        else {
                            if (!sym.containsKey(m)) sym.put(m, letters.charAt(sym.size()));
                            row.append(sym.get(m));
                        }
                    }
                    shape.add(row.toString());
                }
                c.set(b + ".shape", shape);
                sym.forEach((m, ch) -> c.set(b + ".ingredients." + ch, m.name()));
            }
            commit(true);
            viewer.sendMessage(c("<green>Recipe '" + esc(id) + "' disimpan & diterapkan."));
            back.run();
        }
    }
}
