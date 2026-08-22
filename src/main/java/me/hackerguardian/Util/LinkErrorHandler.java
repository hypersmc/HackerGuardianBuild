package me.hackerguardian.Util;

import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.main.utils.textHandling;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LinkErrorHandler {
    textHandling tx = new textHandling();
    private final JavaPlugin plugin;
    private final Plugin plugin2;
    Logger logger = Logger.getLogger("HGBungee_LinkErrorHandler");

    public LinkErrorHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.plugin2 = null;
    }
    public LinkErrorHandler(Plugin plugin) {
        this.plugin2 = plugin;
        this.plugin = null;
    }
    public LinkErrorHandler() {
        this.plugin = null;
        this.plugin2 = null;
    }

    /** Player-facing message (keep it generic; error code is enough). */
    public String buildKickMessage(LinkErrorCode code) {
        String base = "";
        if (plugin != null) {
            base = plugin.getConfig().getString(
                    "Settings.kick_base_message",
                    tx.prefix + "\nConnection blocked. Please join via the official proxy."
            );
        }else if (plugin2 != null) {
            base = HackerGuardianB.getConfiguration().getString(
                    "Settings.kick_base_message",
                    tx.prefix + "\nConnection blocked. Please join via the official proxy."
            );
        }else {
            base = tx.prefix + "\nConnection blocked. Please join via the official proxy.";
        }

        return base + "\n§7Error: " + code.code();
    }

    /** Log a detailed line for admins/devs. */
    public void log(LinkErrorCode code, String playerName, String playerUuid, Map<String, Object> ctx, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(code.code()).append(" ");
        sb.append(code.publicReason());

        if (playerName != null) sb.append(" player=").append(playerName);
        if (playerUuid != null) sb.append(" uuid=").append(playerUuid);

        if (ctx != null && !ctx.isEmpty()) {
            sb.append(" ctx=").append(ctx);
        }

        if (t != null) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, sb.toString(), t);
            }else if (plugin2 != null) {
                plugin2.getLogger().log(Level.WARNING, sb.toString(), t);
            }else {
                logger.log(Level.WARNING, sb.toString(), t);
            }
        } else {
            if (plugin != null) {
                plugin.getLogger().warning(sb.toString());
            }else if (plugin2 != null) {
                plugin2.getLogger().warning(sb.toString());
            }else {
                logger.log(Level.WARNING, sb.toString());
            }
        }
    }

    /** Kick + log (Paper). */
    public void kickpap(Player player, LinkErrorCode code, Map<String, Object> ctx, Throwable t) {
        log(code, player != null ? player.getName() : null, player != null ? player.getUniqueId().toString() : null, ctx, t);

        if (player != null && player.isOnline()) {
            player.kickPlayer(tx.playerText(buildKickMessage(code)));
        }
    }

    public void kickpro(ProxiedPlayer player, LinkErrorCode code, Map<String, Object> ctx, Throwable t) {
        log(code, player != null ? player.getName() : null, player != null ? player.getUniqueId().toString() : null, ctx, t);

        if (player != null && player.isConnected()) {
            player.disconnect(tx.playerText(buildKickMessage(code)));
        }
    }

    /** Just log (for proxy side where we disconnect differently). */
    public void logOnly(LinkErrorCode code, String playerName, String playerUuid, Map<String, Object> ctx, Throwable t) {
        log(code, playerName, playerUuid, ctx, t);
    }
}