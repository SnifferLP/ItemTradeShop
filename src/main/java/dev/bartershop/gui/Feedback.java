package dev.bartershop.gui;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Small, consistent audio/visual acknowledgements. Text is always the primary feedback. */
public final class Feedback {

    private Feedback() {
    }

    public static void success(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.25f);
        spawn(player, Particle.HAPPY_VILLAGER, 5);
    }

    public static void confirm(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.6f);
    }

    public static void error(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.55f, 0.9f);
    }

    public static void transfer(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.4f);
        spawn(player, Particle.END_ROD, 8);
    }

    private static void spawn(Player player, Particle particle, int count) {
        Location location = player.getLocation().add(0, 1.0, 0);
        try {
            player.getWorld().spawnParticle(particle, location, count, 0.35, 0.35, 0.35, 0.01);
        } catch (RuntimeException ignored) {
            // Particles are cosmetic; a client/server version mismatch must never break an action.
        }
    }
}
