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

    }
}
