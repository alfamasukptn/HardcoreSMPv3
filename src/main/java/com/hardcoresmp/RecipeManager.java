package com.hardcoresmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Mendaftarkan recipe head-apple dan semua recipe EZ Craft dari config. */
public final class RecipeManager {

    private final HardcoreSMP plugin;
    private final List<NamespacedKey> registered = new ArrayList<>();
    private final Set<NamespacedKey> headKeys = new HashSet<>();

    RecipeManager(HardcoreSMP plugin) {
        this.plugin = plugin;
    }

    public boolean isHeadRecipe(NamespacedKey key) {
        return headKeys.contains(key);
    }

    public void unregisterAll() {
        for (NamespacedKey k : registered) Bukkit.removeRecipe(k);
        registered.clear();
        headKeys.clear();
    }

    public void reload() {
        unregisterAll();
        FileConfiguration c = plugin.getConfig();

        // Head -> Enchanted Golden Apple
        ConfigurationSection ha = c.getConfigurationSection("head-apple");
        if (ha != null && ha.getBoolean("enabled", true)) {
            NamespacedKey key = new NamespacedKey(plugin, "head_apple");
            ItemStack result = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, Math.max(1, ha.getInt("amount", 1)));
            if (add(key, ha, result)) headKeys.add(key);
        }

        // EZ Craft
        ConfigurationSection ez = c.getConfigurationSection("ez-craft.recipes");
        if (c.getBoolean("ez-craft.enabled", true) && ez != null) {
            for (String id : ez.getKeys(false)) {
                ConfigurationSection s = ez.getConfigurationSection(id);
                if (s == null || !s.getBoolean("enabled", true)) continue;
                Material m = Material.matchMaterial(s.getString("result", ""));
                if (m == null || m.isAir()) {
                    plugin.getLogger().warning("[EZ Craft] '" + id + "': result tidak valid.");
                    continue;
                }
                ItemStack result = new ItemStack(m, Math.max(1, s.getInt("amount", 1)));
                if (s.contains("name") || s.contains("lore")) {
                    ItemMeta meta = result.getItemMeta();
                    if (s.contains("name")) {
                        meta.displayName(HardcoreSMP.MM.deserialize(s.getString("name", ""))
                                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                    }
                    if (s.contains("lore")) {
                        List<Component> lore = new ArrayList<>();
                        for (String line : s.getStringList("lore")) {
                            lore.add(HardcoreSMP.MM.deserialize(line)
                                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                        }
                        meta.lore(lore);
                    }
                    result.setItemMeta(meta);
                }
                String keyName = "ez_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
                add(new NamespacedKey(plugin, keyName), s, result);
            }
        }

        removeVanilla();
        for (Player p : Bukkit.getOnlinePlayers()) discover(p);
    }

    public void discover(Player p) {
        if (!registered.isEmpty()) p.discoverRecipes(registered);
    }

    private boolean add(NamespacedKey key, ConfigurationSection s, ItemStack result) {
        try {
            Recipe r = build(key, s, result);
            if (r == null) return false;
            Bukkit.removeRecipe(key);
            Bukkit.addRecipe(r);
            registered.add(key);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("[Recipe] '" + key.getKey() + "' gagal didaftarkan: " + ex.getMessage());
            return false;
        }
    }

    private Recipe build(NamespacedKey key, ConfigurationSection s, ItemStack result) {
        String type = s.getString("type", "shaped").toLowerCase(Locale.ROOT);
        if (type.equals("shapeless")) {
            ShapelessRecipe r = new ShapelessRecipe(key, result);
            int total = 0;
            for (String line : s.getStringList("ingredients")) {
                String[] p = line.split(":");
                Material m = Material.matchMaterial(p[0].trim());
                if (m == null || m.isAir()) {
                    plugin.getLogger().warning("[Recipe] '" + key.getKey() + "': bahan tidak valid '" + line + "'");
                    return null;
                }
                int n = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1;
                for (int i = 0; i < n; i++) {
                    r.addIngredient(new RecipeChoice.MaterialChoice(m));
                    total++;
                }
            }
            if (total < 1 || total > 9) {
                plugin.getLogger().warning("[Recipe] '" + key.getKey() + "': jumlah bahan harus 1-9.");
                return null;
            }
            return r;
        }

        List<String> shape = s.getStringList("shape");
        ConfigurationSection ing = s.getConfigurationSection("ingredients");
        if (shape.isEmpty() || ing == null) {
            plugin.getLogger().warning("[Recipe] '" + key.getKey() + "': shape/ingredients kosong.");
            return null;
        }
        ShapedRecipe r = new ShapedRecipe(key, result);
        r.shape(shape.toArray(new String[0]));
        for (String sym : ing.getKeys(false)) {
            Material m = Material.matchMaterial(ing.getString(sym, ""));
            if (m == null || m.isAir() || sym.length() != 1) {
                plugin.getLogger().warning("[Recipe] '" + key.getKey() + "': ingredient '" + sym + "' tidak valid.");
                return null;
            }
            r.setIngredient(sym.charAt(0), new RecipeChoice.MaterialChoice(m));
        }
        return r;
    }

    private void removeVanilla() {
        Set<Material> rm = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("ez-craft.remove-vanilla-recipes")) {
            Material m = Material.matchMaterial(s);
            if (m != null) rm.add(m);
        }
        if (rm.isEmpty()) return;
        List<NamespacedKey> toRemove = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof Keyed k && k.getKey().getNamespace().equals(NamespacedKey.MINECRAFT)
                    && rm.contains(r.getResult().getType())) {
                toRemove.add(k.getKey());
            }
        }
        for (NamespacedKey k : toRemove) Bukkit.removeRecipe(k);
    }
}
