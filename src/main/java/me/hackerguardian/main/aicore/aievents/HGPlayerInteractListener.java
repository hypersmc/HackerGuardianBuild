package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGPlayerInteractListener implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (main.isLearning()) {
        }
    }
}
