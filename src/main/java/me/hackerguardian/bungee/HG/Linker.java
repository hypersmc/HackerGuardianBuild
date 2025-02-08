package me.hackerguardian.bungee.HG;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import me.hackerguardian.bungee.utils.BMySQL;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 15-05-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class Linker implements Listener {
    Logger logger = Logger.getLogger("HGBungee_Link");
    @EventHandler
    public void on(PluginMessageEvent event)
    {
        if ( event.getTag().equalsIgnoreCase( "hg:channel" ) ) {
            logger.info("Message Received in channel!");


            if ( event.getReceiver() instanceof ProxiedPlayer )
            {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {

                    logger.info("Received data: " + Arrays.toString(event.getData()));
                    String data = in.readLine(); // Assuming data is sent as UTF-8 string, adjust as needed
                    logger.info("Received data: " + data);
                    String[] parts = data.split("\\|");
                    logger.info(parts[0] + "| " + parts[1] + "| " + parts[2]);
                    String uuid = parts[0];
                    boolean value = Boolean.parseBoolean(parts[1]);
                    String type = parts[2];
                    String reason = parts[3].replaceAll("Â", "");
                    UUID playerUUID = UUID.fromString(uuid);
                    BMySQL sql = new BMySQL();

                    if (playerUUID != null && value) {
                        if (type.equalsIgnoreCase("ban")) {
                            if (sql.getplayerban(playerUUID).equalsIgnoreCase("true")) {
                                ProxiedPlayer player = ProxyServer.getInstance().getPlayer(playerUUID);

                                player.disconnect(new TextComponent(reason));
                            }
                        }
                        if (type.equalsIgnoreCase("kick")) {
                            ProxiedPlayer player = ProxyServer.getInstance().getPlayer(playerUUID);
                            player.disconnect(new TextComponent(reason));
                        }
                    }
                } catch (IOException e) {
                    logger.info("IO Error: ");
                    e.printStackTrace();
                }
            }
            if ( event.getReceiver() instanceof Server )
            {

            }

        }
    }
}
