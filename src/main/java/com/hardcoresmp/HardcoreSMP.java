package com.hardcoresmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class HardcoreSMP extends JavaPlugin implements TabExecutor {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private File dataFile;
    private YamlConfiguration data;
    private RecipeManager recipes;
    private MaceManager maces;
    private GameListener listener;
    private EventManager events;
    private BukkitTask eventTask;
    private Gui gui;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        maces = new MaceManager(this);
        recipes = new RecipeManager(this);
        events = new EventManager(this);
        listener = new GameListener(this);
        gui = new Gui(this);
        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        PluginCommand cmd = getCommand("hardcoresmp");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        applyConfig();
        eventTask = Bukkit.getScheduler().runTaskTimer(this, events::tick, 20L, 20L);
        getLogger().info("HardcoreSMP aktif.");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (eventTask != null) eventTask.cancel();
        if (events != null) events.shutdown();
        if (recipes != null) recipes.unregisterAll();
        if (maces != null) maces.save();
    }

    /** Membaca ulang config dari disk, mendaftarkan ulang recipe, dan me-restart task berkala. */
    public void applyConfig() {
        reloadConfig();
        recipes.reload();
        if (task != null) task.cancel();
        long interval = Math.max(5L, getConfig().getLong("check-interval-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            maces.tick();
            listener.tickLimits();
        }, interval, interval);
    }

    // ---------- helpers ----------
    public YamlConfiguration data() { return data; }

    public synchronized void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Gagal menyimpan data.yml: " + e.getMessage());
        }
    }

    public RecipeManager recipes() { return recipes; }
    public MaceManager maces() { return maces; }
    public Gui gui() { return gui; }
    public EventManager events() { return events; }

    /** Pesan dengan prefix. */
    public Component msg(String key, TagResolver... resolvers) {
        String prefix = getConfig().getString("messages.prefix", "");
        return MM.deserialize(prefix + getConfig().getString("messages." + key, key), resolvers);
    }

    /** Pesan tanpa prefix. */
    public Component msgRaw(String key, TagResolver... resolvers) {
        return MM.deserialize(getConfig().getString("messages." + key, key), resolvers);
    }

    // ---------- command ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("hardcoresmp.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("gui")) {
            if (s instanceof Player p) {
                gui.openMain(p);
            } else {
                s.sendMessage(Component.text("/hcs reload | /hcs mace <list|remove <player>|clear>  (GUI hanya untuk player)"));
            }
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                applyConfig();
                s.sendMessage(msg("reloaded"));
            }
            case "mace" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
                    s.sendMessage(msg("mace-list-header",
                            Placeholder.unparsed("count", String.valueOf(maces.holders().size())),
                            Placeholder.unparsed("max", String.valueOf(maces.max()))));
                    for (UUID u : maces.holders()) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                        s.sendMessage(Component.text(" - " + (op.getName() == null ? u.toString() : op.getName())));
                    }
                } else if (a.length >= 3 && a[1].equalsIgnoreCase("remove")) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(a[2]);
                    maces.release(op.getUniqueId());
                    s.sendMessage(msg("mace-removed", Placeholder.unparsed("player", a[2])));
                } else if (a.length >= 2 && a[1].equalsIgnoreCase("clear")) {
                    maces.clear();
                    s.sendMessage(msg("mace-cleared"));
                } else {
                    s.sendMessage(Component.text("/hcs mace <list|remove <player>|clear>"));
                }
            }
            case "event" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
                    for (EventManager.Id id : EventManager.Id.values()) {
                        s.sendMessage(Component.text(id.key + ": "
                                + (events.isActive(id) ? "AKTIF (" + events.remainingText(id) + ")" : "tidak aktif")));
                    }
                } else if (a.length >= 3 && a[1].equalsIgnoreCase("start")) {
                    EventManager.Id id = EventManager.Id.from(a[2]);
                    if (id == null) {
                        s.sendMessage(Component.text("Event tidak dikenal. Pilihan: pvp, totem, heavycore, mobdrops"));
                        return true;
                    }
                    long minutes = 0;
                    if (a.length >= 4) {
                        try {
                            minutes = Long.parseLong(a[3]);
                        } catch (NumberFormatException ex) {
                            s.sendMessage(Component.text("Menit harus berupa angka."));
                            return true;
                        }
                    }
                    events.start(id, minutes);
                    s.sendMessage(Component.text("Event " + id.key + " dimulai."));
                } else if (a.length >= 3 && a[1].equalsIgnoreCase("stop")) {
                    if (a[2].equalsIgnoreCase("all")) {
                        events.stopAll();
                    } else {
                        EventManager.Id id = EventManager.Id.from(a[2]);
                        if (id == null || !events.stop(id, true)) {
                            s.sendMessage(Component.text("Event itu tidak sedang berjalan."));
                            return true;
                        }
                    }
                    s.sendMessage(Component.text("Event dihentikan."));
                } else {
                    s.sendMessage(Component.text("/hcs event <list | start <pvp|totem|heavycore|mobdrops> [menit] | stop <id|all>>"));
                }
            }
            default -> s.sendMessage(Component.text("/hcs (GUI) | /hcs reload | /hcs mace | /hcs event"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("hardcoresmp.admin")) return out;
        if (a.length == 1) {
            for (String o : List.of("gui", "reload", "mace", "event"))
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("mace")) {
            for (String o : List.of("list", "remove", "clear"))
                if (o.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("event")) {
            for (String o : List.of("list", "start", "stop"))
                if (o.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("event")) {
            for (EventManager.Id id : EventManager.Id.values())
                if (id.key.startsWith(a[2].toLowerCase(Locale.ROOT))) out.add(id.key);
            if (a[1].equalsIgnoreCase("stop")) out.add("all");
        } else if (a.length == 3 && a[0].equalsIgnoreCase("mace") && a[1].equalsIgnoreCase("remove")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        return out;
    }
}
