package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;
import org.neuroph.core.data.DataSet;

/**
 * @author JumpWatch on 07-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGPlayerInteractListener implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (main.learning) {
        }
    }
}
