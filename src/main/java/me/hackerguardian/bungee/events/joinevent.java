package me.hackerguardian.bungee.events;

import me.hackerguardian.bungee.utils.BMySQL;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 15-05-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class joinevent implements Listener {
    Logger logger = Logger.getLogger("HGBungee_Link");
    @EventHandler
    public void onPlayerJoin(PostLoginEvent event) {
        BMySQL sql = new BMySQL();
        ProxiedPlayer player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        if (sql.getplayerban(playerUUID).equalsIgnoreCase("true")) {
            logger.info("Player " + player.getName() + " joined but was banned!");
            event.getPlayer().disconnect(new TextComponent(sql.getPlayerbanreason(playerUUID)));
        }
    }
}
