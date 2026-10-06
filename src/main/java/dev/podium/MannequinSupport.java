package dev.podium;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Kept in its own class so the main plugin still loads on servers that
 * do not have the Mannequin entity (it is only touched when available()).
 */
final class MannequinSupport {

    private MannequinSupport() {
    }

    static boolean available() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static Entity spawn(Location loc) {
        return loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setImmovable(true);
            m.setDescription(null);
            m.setGravity(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setPersistent(false);
        });
    }

    /**
     * Fetches the full skin (textures) from Mojang off the main thread, then
     * applies it. Skins are cached by Paper, so this is cheap after the first time.
     */
    static void setSkin(Plugin plugin, Entity entity, UUID uuid, String name) {
        Mannequin m = (Mannequin) entity;
        if (uuid == null && name == null) {
            m.setProfile(Mannequin.defaultProfile());
            return;
        }

        PlayerProfile profile;
        if (uuid != null) {
            profile = Bukkit.createProfile(uuid, name);
        } else {
            profile = Bukkit.createProfile(name);
        }

        // Show something straight away (server-side dynamic lookup)...
        try {
            ResolvableProfile.Builder b = ResolvableProfile.resolvableProfile();
            if (uuid != null) b.uuid(uuid);
            if (name != null) b.name(name);
            m.setProfile(b.build());
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not set quick skin for " + name + ": " + t);
        }

        // ...then replace it with a fully resolved profile that has the textures.
        final PlayerProfile toFill = profile;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!toFill.hasTextures()) {
                    toFill.complete(true); // blocking Mojang lookup, fine async
                }
                if (!toFill.hasTextures()) {
                    plugin.getLogger().warning("No skin textures found for '" + name
                            + "'. Is the server online-mode with internet access?");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!entity.isValid()) return;
                    m.setProfile(ResolvableProfile.resolvableProfile(toFill));
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("Skin lookup failed for '" + name + "': " + t);
            }
        });
    }
}
