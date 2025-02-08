package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.ChatColor;
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
        if (p.hasPermission("hg.joinevent")){
            p.sendMessage(main.text.playerTextWsp("Hi! My name is " + main.text.prefixnoBR + " your new AI companion dedicated to observing and learning!"));
            p.sendMessage(main.text.playerTextWsp("While you might not see me, rest assured, I'm always here, silently watching over your gameplay."));
            p.sendMessage(main.text.playerTextWsp("If I encounter any errors or limitations preventing me from performing certain tasks, please don't hesitate to report them to my maker: '" + ChatColor.RED + main.getDescription().getAuthors().toString().replace("[", "").replace("]", "") + ChatColor.RESET + "'. He's equipped to handle such situations and knows the necessary steps to take."));
        }
    }
}
