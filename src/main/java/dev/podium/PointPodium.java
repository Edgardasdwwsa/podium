package dev.podium;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows a top 3 podium (full-body player-skin statue + hologram) using PointThing's
 * PlaceholderAPI placeholders: %points_top_X_name% and %points_top_X_points%.
 */
public final class PointPodium extends JavaPlugin implements TabExecutor {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final Map<Integer, Podium> podiums = new HashMap<>();
    private final Map<String, OfflinePlayer> nameLookup = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask task;
    private boolean mannequinAvailable;

    private static final class Podium {
        final int rank;
        Location loc;
        Entity stand;
        boolean fullBody;
        TextDisplay text;
        String lastName = "\u0000";
        String lastText = "";

        Podium(int rank, Location loc) {
            this.rank = rank;
            this.loc = loc;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mannequinAvailable = MannequinSupport.available();
        if (!mannequinAvailable) {
            getLogger().warning("Mannequin entities need Paper 1.21.9+. Falling back to player heads.");
        }
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getCommand("podium").setExecutor(this);
        getCommand("podium").setTabCompleter(this);
        startTask();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        podiums.values().forEach(this::despawn);
    }

    // ---------- data ----------

    private void loadData() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        podiums.values().forEach(this::despawn);
        podiums.clear();
        for (int r = 1; r <= 3; r++) {
            Location l = data.getLocation("podiums." + r);
            if (l != null) podiums.put(r, new Podium(r, l));
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    private void startTask() {
        if (task != null) task.cancel();
        long ticks = Math.max(1, getConfig().getLong("update-interval-seconds", 10)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(this, this::refresh, 20L, ticks);
    }

    // ---------- rendering ----------

    private void refresh() {
        FileConfiguration cfg = getConfig();
        // Placeholders need an online player, so leave the podium as it is on an empty server.
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        for (Podium p : podiums.values()) {
            if (p.loc.getWorld() == null) continue;
            if (!p.loc.getWorld().isChunkLoaded(p.loc.getBlockX() >> 4, p.loc.getBlockZ() >> 4)) {
                p.stand = null;
                p.text = null;
                p.lastName = "\u0000";
                continue;
            }
            ensureSpawned(p);

            String name = resolve(cfg.getString("placeholders.name", ""), p.rank);
            String pts = resolve(cfg.getString("placeholders.points", ""), p.rank);
            if (pts != null && cfg.getBoolean("compact-points", true)) pts = compact(pts);

            String shownName = name != null ? name : cfg.getString("empty-name", "&7Nobody yet");
            String shownPts = pts != null ? pts : "0";

            String base = "ranks." + p.rank + ".";
            List<String> lines = new ArrayList<>();
            lines.add(cfg.getString(base + "title", "#" + p.rank));
            lines.addAll(cfg.getStringList(base + "rewards"));
            lines.add("");
            lines.add(cfg.getString(base + "line", "#{rank} {name} ({points})")
                    .replace("{rank}", String.valueOf(p.rank))
                    .replace("{name}", shownName)
                    .replace("{points}", shownPts));
            String text = color(String.join("\n", lines));

            if (!text.equals(p.lastText)) {
                p.text.setText(text);
                p.lastText = text;
            }
            String key = name == null ? "" : name;
            if (!key.equals(p.lastName)) {
                setSkin(p, name);
                p.lastName = key;
            }
        }
    }

    private void ensureSpawned(Podium p) {
        double height = getConfig().getDouble("hologram-height", 0.75);
        if (p.stand == null || !p.stand.isValid()) {
            boolean wantFull = getConfig().getBoolean("full-body", true);
            if (wantFull && mannequinAvailable) {
                // Full-body player skin using Paper's Mannequin entity (1.21.9+).
                p.stand = MannequinSupport.spawn(p.loc);
                p.fullBody = true;
            } else {
                // Fallback: a player head on an invisible armor stand. A standard
                // armor stand's head sits ~1.44 blocks above its feet, so spawn
                // lower to make the head rest on the saved location.
                Location sl = p.loc.clone().add(0, -1.44, 0);
                p.stand = p.loc.getWorld().spawn(sl, ArmorStand.class, s -> {
                    s.setInvisible(true);
                    s.setGravity(false);
                    s.setBasePlate(false);
                    s.setArms(false);
                    s.setInvulnerable(true);
                    s.setPersistent(false);
                    s.setSilent(true);
                    s.setCanPickupItems(false);
                });
                p.fullBody = false;
            }
            p.lastName = "\u0000";
            // Re-place the hologram so it matches the statue height.
            if (p.text != null) {
                p.text.remove();
                p.text = null;
            }
        }
        if (p.text == null || !p.text.isValid()) {
            double bodyTop = p.fullBody ? 1.8 : 0.5;
            Location tl = p.loc.clone().add(0, bodyTop + height, 0);
            p.text = p.loc.getWorld().spawn(tl, TextDisplay.class, t -> {
                t.setBillboard(Display.Billboard.CENTER);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setShadowed(true);
                t.setSeeThrough(false);
                t.setPersistent(false);
            });
            p.lastText = "";
        }
    }

    private void despawn(Podium p) {
        if (p.stand != null) p.stand.remove();
        if (p.text != null) p.text.remove();
        p.stand = null;
        p.text = null;
        p.lastName = "\u0000";
        p.lastText = "";
    }

    private void setSkin(Podium p, String name) {
        OfflinePlayer op = name != null ? lookup(name) : null;
        if (p.fullBody) {
            // Passing the name lets the server fetch the skin even for players
            // who have never joined this server.
            MannequinSupport.setSkin(this, p.stand, op != null ? op.getUniqueId() : null, name);
            return;
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null && op != null) meta.setOwningPlayer(op);
        head.setItemMeta(meta);
        ((ArmorStand) p.stand).getEquipment().setHelmet(head);
    }

    /** Finds a known player by name without any web lookup. */
    private OfflinePlayer lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        OfflinePlayer cached = nameLookup.get(key);
        if (cached != null) return cached;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            nameLookup.put(key, online);
            return online;
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                nameLookup.put(key, op);
                return op;
            }
        }
        return null;
    }

    private String resolve(String template, int rank) {
        if (template == null || template.isEmpty()) return null;
        // PointThing only answers when there is a player context, so use any online player.
        Player ctx = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (ctx == null) return null;
        String out = PlaceholderAPI.setPlaceholders(ctx, template.replace("{rank}", String.valueOf(rank)));
        if (out == null) return null;
        out = out.trim();
        if (out.isEmpty() || out.contains("%")) return null;
        return out;
    }

    private String compact(String raw) {
        try {
            double d = Double.parseDouble(raw.replace(",", ""));
            if (d >= 1_000_000) return trim(d / 1_000_000) + "m";
            if (d >= 1_000) return trim(d / 1_000) + "k";
            return String.valueOf((long) d);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("&x");
            for (char c : m.group(1).toCharArray()) rep.append('&').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    // ---------- commands ----------

    private void msg(CommandSender to, String key, int rank) {
        String prefix = getConfig().getString("messages.prefix", "");
        String m = getConfig().getString("messages." + key, key).replace("{rank}", String.valueOf(rank));
        to.sendMessage(color(prefix + m));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("pointpodium.admin")) {
            msg(sender, "no-permission", 0);
            return true;
        }
        if (args.length == 0) {
            msg(sender, "usage", 0);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                loadData();
                startTask();
                msg(sender, "reloaded", 0);
            }
            case "refresh" -> {
                refresh();
                msg(sender, "refreshed", 0);
            }
            case "set" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "players-only", 0);
                    return true;
                }
                int rank = parseRank(args);
                if (rank < 0) {
                    msg(sender, "usage", 0);
                    return true;
                }
                Podium old = podiums.remove(rank);
                if (old != null) despawn(old);
                Location l = player.getLocation();
                l.setPitch(0);
                podiums.put(rank, new Podium(rank, l));
                data.set("podiums." + rank, l);
                saveData();
                refresh();
                msg(sender, "set", rank);
            }
            case "remove" -> {
                int rank = parseRank(args);
                if (rank < 0) {
                    msg(sender, "usage", 0);
                    return true;
                }
                Podium old = podiums.remove(rank);
                if (old != null) despawn(old);
                data.set("podiums." + rank, null);
                saveData();
                msg(sender, "removed", rank);
            }
            default -> msg(sender, "usage", 0);
        }
        return true;
    }

    private int parseRank(String[] args) {
        if (args.length < 2) return -1;
        try {
            int r = Integer.parseInt(args[1]);
            return r >= 1 && r <= 3 ? r : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("set", "remove", "refresh", "reload");
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove")))
            return List.of("1", "2", "3");
        return List.of();
    }
}
