package com.hardcoresmp;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Event berwaktu (pvp, totem, heavycore, mobdrops) lengkap dengan bossbar hitung mundur. */
public final class EventManager {

    public enum Id {
        PVP("pvp", "PvP"),
        TOTEM("totem", "Totem"),
        HEAVYCORE("heavycore", "Heavy Core"),
        MOBDROPS("mobdrops", "Mob Drops");

        public final String key;
        public final String label;

        Id(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public static Id from(String s) {
            for (Id i : values()) if (i.key.equalsIgnoreCase(s)) return i;
            return null;
        }
    }

    private static final class State {
        long endAt;
        long total;
        BossBar bar;
    }

    private final HardcoreSMP plugin;
    private final Map<Id, State> active = new EnumMap<>(Id.class);
    private final Map<Id, Long> nextAuto = new EnumMap<>(Id.class);

    EventManager(HardcoreSMP plugin) {
        this.plugin = plugin;
        long now = System.currentTimeMillis();
        ConfigurationSection root = plugin.data().getConfigurationSection("events-state");
        if (root != null) {
            for (Id id : Id.values()) {
                ConfigurationSection s = root.getConfigurationSection(id.key);
                if (s == null) continue;
                long total = s.getLong("total");
                long rem = s.contains("paused") ? s.getLong("paused") : s.getLong("end") - now;
                if (rem > 0 && total > 0) {
                    State st = new State();
                    st.total = total;
                    st.endAt = now + rem;
                    active.put(id, st);
                }
            }
        }
        save();
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ---------- status ----------
    public boolean timed(Id id) { return cfg().getBoolean("events." + id.key + ".timed", true); }

    public boolean isActive(Id id) { return active.containsKey(id); }

    /** true jika fitur sedang berlaku (event aktif, atau mode tidak berwaktu). */
    public boolean effective(Id id) { return !timed(id) || isActive(id); }

    /** Chance yang berlaku sekarang: saat event aktif (atau tidak berwaktu) / di luar event. */
    public double chance(Id id, String activePath, String basePath) {
        if (effective(id)) return cfg().getDouble(activePath, 100.0);
        return cfg().getDouble(basePath, 0.0);
    }

    public String remainingText(Id id) {
        State s = active.get(id);
        return s == null ? "-" : fmt(Math.max(0, s.endAt - System.currentTimeMillis()));
    }

    // ---------- kontrol ----------
    /** minutes <= 0 = pakai duration-minutes dari config. */
    public void start(Id id, long minutes) {
        long mins = minutes > 0 ? minutes : Math.max(1L, cfg().getLong("events." + id.key + ".duration-minutes", 30L));
        State old = active.remove(id);
        if (old != null) hide(old);
        State s = new State();
        s.total = mins * 60_000L;
        s.endAt = System.currentTimeMillis() + s.total;
        active.put(id, s);
        nextAuto.remove(id);
        updateBar(id, s, s.total);
        announce(id, "start", mins);
        save();
    }

    public boolean stop(Id id, boolean announce) {
        State s = active.remove(id);
        if (s == null) return false;
        hide(s);
        long interval = cfg().getLong("events." + id.key + ".auto-interval-minutes", 0L);
        if (interval > 0) nextAuto.put(id, System.currentTimeMillis() + interval * 60_000L);
        if (announce) announce(id, "end", 0);
        save();
        return true;
    }

    public void stopAll() {
        for (Id id : Id.values()) stop(id, true);
    }

    /** Dipanggil tiap 1 detik. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Id id : Id.values()) {
            State s = active.get(id);
            if (s != null) {
                long rem = s.endAt - now;
                if (rem <= 0) stop(id, true);
                else updateBar(id, s, rem);
                continue;
            }
            long interval = cfg().getLong("events." + id.key + ".auto-interval-minutes", 0L);
            if (interval <= 0 || !timed(id)) {
                nextAuto.remove(id);
                continue;
            }
            Long next = nextAuto.get(id);
            if (next == null) nextAuto.put(id, now + interval * 60_000L);
            else if (now >= next) start(id, 0);
        }
    }

    public void showTo(Player p) {
        for (State s : active.values()) {
            if (s.bar != null) p.showBossBar(s.bar);
        }
    }

    // ---------- simpan ----------
    private void save() {
        plugin.data().set("events-state", null);
        for (Map.Entry<Id, State> e : active.entrySet()) {
            plugin.data().set("events-state." + e.getKey().key + ".end", e.getValue().endAt);
            plugin.data().set("events-state." + e.getKey().key + ".total", e.getValue().total);
        }
        plugin.saveData();
    }

    /** Saat server mati: simpan sisa waktu supaya event lanjut setelah restart. */
    public void shutdown() {
        long now = System.currentTimeMillis();
        save();
        for (Map.Entry<Id, State> e : active.entrySet()) {
            plugin.data().set("events-state." + e.getKey().key + ".paused", Math.max(0L, e.getValue().endAt - now));
            hide(e.getValue());
        }
        plugin.saveData();
    }

    // ---------- bossbar ----------
    private void hide(State s) {
        if (s.bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(s.bar);
        s.bar = null;
    }

    private void updateBar(Id id, State s, long remaining) {
        String base = "events." + id.key + ".bossbar.";
        if (!cfg().getBoolean(base + "enabled", true)) {
            hide(s);
            return;
        }
        Component title = HardcoreSMP.MM.deserialize(
                cfg().getString(base + "title", "<white>" + id.label + " <gray>| <white><time>"),
                Placeholder.unparsed("time", fmt(remaining)),
                Placeholder.unparsed("event", id.label));
        float progress = Math.max(0f, Math.min(1f, remaining / (float) s.total));
        BossBar.Color color = parseColor(cfg().getString(base + "color", "WHITE"));
        BossBar.Overlay overlay = parseOverlay(cfg().getString(base + "style", "PROGRESS"));
        if (s.bar == null) {
            s.bar = BossBar.bossBar(title, progress, color, overlay);
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(s.bar);
        } else {
            s.bar.name(title);
            s.bar.progress(progress);
            s.bar.color(color);
            s.bar.overlay(overlay);
        }
    }

    private static BossBar.Color parseColor(String s) {
        try {
            return BossBar.Color.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return BossBar.Color.WHITE;
        }
    }

    private static BossBar.Overlay parseOverlay(String s) {
        try {
            return BossBar.Overlay.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    private void announce(Id id, String which, long minutes) {
        String raw = cfg().getString("events." + id.key + ".messages." + which, "");
        if (raw == null || raw.isEmpty()) return;
        Bukkit.broadcast(HardcoreSMP.MM.deserialize(raw,
                Placeholder.unparsed("minutes", String.valueOf(minutes)),
                Placeholder.unparsed("event", id.label)));
    }

    static String fmt(long ms) {
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }
}
