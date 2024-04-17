package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * @author JumpWatch on 27-11-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class onPlayerJoin implements Listener {
    static HackerGuardian main = HackerGuardian.getInstance();

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event){
        Player p = event.getPlayer();

    }
}
