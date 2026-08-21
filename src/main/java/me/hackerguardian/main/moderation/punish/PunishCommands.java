package me.hackerguardian.main.moderation.punish;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.List;

public final class PunishCommands {

    private final HackerGuardian plugin;
    private final PunishmentService service;
    private final PunishAnnouncer announcer;
    public textHandling tx = new textHandling();
    
    // perms
    private static final String PERM_STAFF_NOTIFY = "hg.staff.notify";
    private static final String PERM_PUBLIC_FLAG = "hg.punish.public"; // allow -p usage

    public PunishCommands(HackerGuardian plugin, PunishmentService service, PunishAnnouncer announcer) {
        this.plugin = plugin;
        this.service = service;
        this.announcer = announcer;
    }

    public void register(CommandManager commandManager) {

        commandManager.register("kick", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            if (!HackerGuardian.CommandValidate.noPerm(sender, "kick")) return;
            if (!sender.hasPermission("hg.kick")) return;
            boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
            String serverName = plugin.getConfig().getString("Settings.server_name", "default");

            PunishFlags.Parsed p1 = PunishFlags.parse(params);
            ScopeFlags.Parsed p2 = ScopeFlags.parse(p1.args(), behindProxy);

            PunishFlags flags = flagsOrDefault(p1.flags());
            PunishScope scope = p2.scopeFlags().scope;
            String[] args = p2.args();

            if (args.length < 2) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg kick [-s|-p] <player> <reason...>"));
                return;
            }

            Player staff = (Player) sender;
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "That player is not online."));
                return;
            }

            String reason = joinFrom(args, 1);
            String kickMsg = color(plugin.getConfig().getString("Punishments.messages.kick",
                    "&cYou were kicked.\n&7Reason: &f%reason%").replace("%reason%", reason));

            target.kickPlayer(kickMsg);

            service.logKick(staff, target.getUniqueId().toString(), target.getName(), reason, scope, serverName);

            announcer.broadcast(
                    flagsOrDefault(flags),
                    PERM_STAFF_NOTIFY, PERM_PUBLIC_FLAG,
                    "&7[&cHG&7] &cKICK&7: &f" + staff.getName() + " &7-> &f" + target.getName() + " &7Reason: &f" + reason,
                    "&7[&cHG&7] &f" + target.getName() + " &7was kicked. &7Reason: &f" + reason
            );
        });

        commandManager.register("ban", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            if (!HackerGuardian.CommandValidate.noPerm(sender, "ban")) return;
            if (!sender.hasPermission("hg.ban")) return;

            boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
            String serverName = plugin.getConfig().getString("Settings.server_name", "default");

            // 1) parse -s / -p
            PunishFlags.Parsed p1 = PunishFlags.parse(params);

            // 2) parse -srv / -wid (ignored/stripped if behind_proxy=false)
            ScopeFlags.Parsed p2 = ScopeFlags.parse(p1.args(), behindProxy);

            PunishFlags flags = flagsOrDefault(p1.flags());
            PunishScope scope = p2.scopeFlags().scope;
            String[] args = p2.args();

            if (args.length < 2) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg ban [-s|-p] [-srv|-wid] <player> [duration] <reason...>"));
                sender.sendMessage(tx.playerText(tx.shortprefix + "Example: /hg ban -p -wid Notch 7d cheating"));
                return;
            }

            Player staff = (Player) sender;

            // Target lookup
            String typedName = args[0];
            OfflinePlayer op = Bukkit.getOfflinePlayer(typedName);

            // If they've never joined AND aren't online, reject
            if ((op == null || op.getUniqueId() == null) ||
                    (!op.hasPlayedBefore() && Bukkit.getPlayerExact(typedName) == null)) {
                sender.sendMessage(tx.playerText(tx.prefix + "That player doesn't exist or has never joined."));
                return;
            }

            String targetUuid = op.getUniqueId().toString();
            String targetName = (op.getName() != null && !op.getName().isBlank()) ? op.getName() : typedName;

            // Optional duration
            int reasonStart = 1;
            Long durationMs = DurationParser.parseToMsOrNull(args.length >= 2 ? args[1] : null);
            if (durationMs != null) reasonStart = 2;

            if (args.length <= reasonStart) {
                sender.sendMessage(tx.playerText(tx.prefix + "You must provide a reason."));
                return;
            }

            String reason = joinFrom(args, reasonStart);

            // Scope sanity
            if (behindProxy && scope == PunishScope.SERVER && (serverName == null || serverName.isBlank())) {
                sender.sendMessage(tx.playerText(tx.prefix + "Server scope requires Settings.server_name to be set."));
                return;
            }

            // Compute once (avoid drift). This is only for the *immediate kick message*.
            final long nowMs = System.currentTimeMillis();
            final Long expiresAtMs = (durationMs == null) ? null : (nowMs + durationMs);

            // Create ban async (DB)
            service.ban(staff, targetUuid, targetName, reason, durationMs, scope, serverName)
                    .whenComplete((punishId, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ex != null) {
                            sender.sendMessage(tx.playerText(tx.prefix + "Failed to ban (DB error)."));
                            if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                            return;
                        }

                        // If target is online on THIS server, kick them with your styled message
                        Player online = Bukkit.getPlayer(op.getUniqueId());
                        if (online != null && online.isOnline()) {
                            String kickKey = (expiresAtMs == null)
                                    ? "Punishments.messages.ban_kick_perm"
                                    : "Punishments.messages.ban_kick_temp";

                            String expiresLeft = (expiresAtMs == null) ? "Never" : TimeFormat.remaining(nowMs, expiresAtMs);
                            String expiresDate = (expiresAtMs == null) ? "Never" : TimeFormat.dateTime(expiresAtMs);

                            String kickMsg = plugin.getConfig().getString(kickKey,
                                    "&cYou are banned.\n&7Reason: &f%reason%");

                            kickMsg = kickMsg
                                    .replace("%reason%", reason)
                                    .replace("%expires%", expiresLeft)
                                    .replace("%expires_date%", expiresDate)
                                    .replace("%id%", String.valueOf(punishId));

                            online.kickPlayer(color(kickMsg));
                        }

                        // Announce
                        String durText = (durationMs == null) ? "PERM" : args[1];
                        String scopeText = behindProxy
                                ? (scope == PunishScope.WIDE ? "WIDE" : ("SRV:" + serverName))
                                : "LOCAL";

                        String staffMsg =
                                "&7[&cHG&7] &cBAN&7: &f" + staff.getName() + " &7-> &f" + targetName +
                                        " &7(" + durText + ", " + scopeText + ") &7Reason: &f" + reason + " &7(#" + punishId + ")";

                        String pubMsg =
                                "&7[&cHG&7] &f" + targetName + " &7was banned. &7Reason: &f" + reason;

                        announcer.broadcast(flags, PERM_STAFF_NOTIFY, "hg.punish.public", staffMsg, pubMsg);

                        sender.sendMessage(tx.playerText(tx.shortprefix + "Banned " + ChatColor.RED + targetName + ChatColor.RESET + " (#" + punishId + ")"));
                    }));
        });

        commandManager.register("unban", (sender, params) -> {
            if (!HackerGuardian.CommandValidate.noPerm(sender, "unban")) return;
            if (!sender.hasPermission("hg.unban")) return;

            if (params.length < 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg unban <player>"));
                return;
            }

            String name = params[0];
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null || op.getUniqueId() == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Unknown player."));
                return;
            }

            service.unban((Player)sender, op.getUniqueId().toString(), safeName(op.getName(), name), "manual unban").whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Failed to unban (DB error)."));
                    return;
                }
                sender.sendMessage(tx.playerText(tx.prefix + (ok ? "Unbanned " : "No active ban found for ") + ChatColor.RED + safeName(op.getName(), name)));
            }));
        });

        commandManager.register("mute", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            if (!HackerGuardian.CommandValidate.noPerm(sender, "mute")) return;
            if (!sender.hasPermission("hg.mute")) return;

            boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
            String serverName = plugin.getConfig().getString("Settings.server_name", "default");

            PunishFlags.Parsed p1 = PunishFlags.parse(params);
            ScopeFlags.Parsed p2 = ScopeFlags.parse(p1.args(), behindProxy);

            PunishFlags flags = flagsOrDefault(p1.flags());
            PunishScope scope = p2.scopeFlags().scope;
            String[] args = p2.args();

            if (args.length < 2) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg mute [-s|-p] <player> [duration] <reason...>"));
                return;
            }

            Player staff = (Player) sender;

            String targetName = args[0];
            OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
            if (op == null || (!op.hasPlayedBefore() && Bukkit.getPlayerExact(targetName) == null)) {
                sender.sendMessage(tx.playerText(tx.prefix + "That player doesn't exist or has never joined."));
                return;
            }

            String targetUuid = op.getUniqueId().toString();

            int reasonStart = 1;
            Long durationMs = DurationParser.parseToMsOrNull(args[1]);
            if (durationMs != null) reasonStart = 2;

            if (args.length <= reasonStart) {
                sender.sendMessage(tx.playerText(tx.prefix + "You must provide a reason."));
                return;
            }

            String reason = joinFrom(args, reasonStart);

            service.mute(staff, targetUuid, safeName(op.getName(), targetName), reason, durationMs, scope, serverName)
                    .whenComplete((id, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ex != null) {
                            sender.sendMessage(tx.playerText(tx.prefix + "Failed to mute (DB error)."));
                            if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                            return;
                        }

                        String durText = (durationMs == null) ? "PERM" : args[1];

                        // Let the player know instantly if online
                        Player online = Bukkit.getPlayer(op.getUniqueId());
                        if (online != null) {
                            online.sendMessage(color("&cYou have been muted. &7Reason: &f" + reason));
                        }

                        announcer.broadcast(
                                flags,
                                PERM_STAFF_NOTIFY, PERM_PUBLIC_FLAG,
                                "&7[&cHG&7] &cMUTE&7: &f" + staff.getName() + " &7-> &f" + safeName(op.getName(), targetName) +
                                        " &7(" + durText + ") &7Reason: &f" + reason + " &7(#" + id + ")",
                                "&7[&cHG&7] &f" + safeName(op.getName(), targetName) + " &7was muted. &7Reason: &f" + reason
                        );
                    }));
        });

        commandManager.register("unmute", (sender, params) -> {
            if (!HackerGuardian.CommandValidate.noPerm(sender, "unmute")) return;
            if (!sender.hasPermission("hg.unmute")) return;

            if (params.length < 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg unmute <player>"));
                return;
            }

            String name = params[0];
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null || op.getUniqueId() == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Unknown player."));
                return;
            }

            service.unmute((Player)sender, op.getUniqueId().toString(), safeName(op.getName(), name), "manual unmute").whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Failed to unmute (DB error)."));
                    return;
                }
                sender.sendMessage(tx.playerText(tx.prefix + (ok ? "Unmuted " : "No active mute found for ") + ChatColor.RED + safeName(op.getName(), name)));
            }));
        });

        commandManager.register("ipban", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            if (!HackerGuardian.CommandValidate.noPerm(sender, "banip")) return;
            if (!sender.hasPermission("hg.banip")) return;

            boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
            String serverName = plugin.getConfig().getString("Settings.server_name", "default");

            PunishFlags.Parsed p1 = PunishFlags.parse(params);
            ScopeFlags.Parsed p2 = ScopeFlags.parse(p1.args(), behindProxy);

            PunishFlags flags = flagsOrDefault(p1.flags());
            PunishScope scope = p2.scopeFlags().scope;
            String[] args = p2.args();

            if (args.length < 2) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg ipban [-s|-p] [-srv|-wid] <player> [duration] <reason...>"));
                return;
            }

            Player staff = (Player) sender;

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "That player is not online."));
                return;
            }

            InetSocketAddress addr = target.getAddress();
            if (addr == null || addr.getAddress() == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Could not read player IP."));
                return;
            }

            String ip = addr.getAddress().getHostAddress();

            int reasonStart = 1;
            Long durationMs = DurationParser.parseToMsOrNull(args[1]);
            if (durationMs != null) reasonStart = 2;

            if (args.length <= reasonStart) {
                sender.sendMessage(tx.playerText(tx.prefix + "You must provide a reason."));
                return;
            }

            String reason = joinFrom(args, reasonStart);

            // Scope sanity
            if (behindProxy && scope == PunishScope.SERVER && (serverName == null || serverName.isBlank())) {
                sender.sendMessage(tx.playerText(tx.prefix + "Server scope requires Settings.server_name to be set."));
                return;
            }
            final long nowMS = System.currentTimeMillis();
            final Long expiresAtMs = (durationMs == null) ? null : (nowMS + durationMs);

            service.banIp(staff, ip, reason, durationMs, scope, serverName).whenComplete((id, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Failed to IP-ban (DB error)."));
                    if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                    return;
                }

                // IF target is online on THIS server, kick them with styled message.
                Player online = Bukkit.getPlayer(target.getUniqueId());
                if (online != null && online.isOnline()) {
                    String kickKey = (expiresAtMs == null)
                            ? "Punishments.message.ip_ban_kick_perm"
                            : "Punishments.message.ip_ban_kick_temp";
                }

                String expiresLeft = (expiresAtMs == null) ? "Never" : TimeFormat.remaining(expiresAtMs, nowMS);
                String expiresDate = (expiresAtMs == null) ? "Never" : TimeFormat.dateTime(expiresAtMs);


                @Deprecated
                // Kick target immediately (they’re now IP banned)
                String kickMsg = color(plugin.getConfig().getString("Punishments.messages.ipban_kick",
                        "&cYou are IP-banned.\n&7Reason: &f%reason%").replace("%reason%", reason));
                target.kickPlayer(kickMsg);

                String durText = (durationMs == null) ? "PERM" : args[1];

                // Staff message can include the IP; public message should NOT.
                announcer.broadcast(
                        flags,
                        PERM_STAFF_NOTIFY, PERM_PUBLIC_FLAG,
                        "&7[&cHG&7] &cIPBAN&7: &f" + staff.getName() + " &7-> &f" + target.getName() +
                                " &7(" + durText + ") &7IP: &f" + ip + " &7Reason: &f" + reason + " &7(#" + id + ")",
                        "&7[&cHG&7] &f" + target.getName() + " &7was IP-banned. &7Reason: &f" + reason
                );
            }));
        });

        commandManager.register("unbanip", (sender, params) -> {
            if (!HackerGuardian.CommandValidate.noPerm(sender, "unbanip")) return;
            if (!sender.hasPermission("hg.unbanip")) return;

            if (params.length < 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg unbanip <ipv4_or_v6>"));
                return;
            }

            String ip = params[0];

            service.unbanIp((Player)sender, ip, "manual unbanip").whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Failed to unbanip (DB error)."));
                    return;
                }
                sender.sendMessage(tx.playerText(tx.prefix + (ok ? "Unbanned IP " : "No active IP ban for ") + ChatColor.RED + ip));
            }));
        });

        // Optional but HIGH VALUE:
        // /hg check <player>
        commandManager.register("check", (sender, params) -> {
            if (!HackerGuardian.CommandValidate.noPerm(sender, "check")) return;
            if (!sender.hasPermission("hg.check")) return;

            if (params.length < 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg check <player>"));
                return;
            }

            String name = params[0];
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null || op.getUniqueId() == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Unknown player."));
                return;
            }

            String uuid = op.getUniqueId().toString();

            service.getActiveBan(uuid).whenComplete((banOpt, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Failed to check punishments."));
                    return;
                }
                sender.sendMessage(tx.playerText(tx.prefix + "Check: " + ChatColor.RED + safeName(op.getName(), name)));
                sender.sendMessage(tx.playerText(tx.shortprefix + "UUID: " + ChatColor.RED + uuid));

                if (banOpt.isPresent()) {
                    sender.sendMessage(tx.playerText(tx.shortprefix + "Ban: " + ChatColor.RED + "ACTIVE" + ChatColor.RESET + " Reason: " + ChatColor.RED + banOpt.get().reason()));
                } else {
                    sender.sendMessage(tx.playerText(tx.shortprefix + "Ban: " + ChatColor.RED + "NONE"));
                }

                service.getActiveMute(uuid).whenComplete((muteOpt, ex2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (muteOpt.isPresent()) {
                        sender.sendMessage(tx.playerText(tx.shortprefix + "Mute: " + ChatColor.RED + "ACTIVE" + ChatColor.RESET + " Reason: " + ChatColor.RED + muteOpt.get().reason()));
                    } else {
                        sender.sendMessage(tx.playerText(tx.shortprefix + "Mute: " + ChatColor.RED + "NONE"));
                    }
                }));
            }));
        });

        // /hg punishments <player>  (last N)
        commandManager.register("punishments", (sender, params) -> {
            if (!HackerGuardian.CommandValidate.noPerm(sender, "punishments")) return;
            if (!sender.hasPermission("hg.punishments")) return;

            if (params.length < 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg punishments <player>"));
                return;
            }

            String name = params[0];
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null || op.getUniqueId() == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Unknown player."));
                return;
            }

            int limit = plugin.getConfig().getInt("Punishments.history_limit", 10);

            service.listPunishments(op.getUniqueId().toString(), limit).whenComplete((rows, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Failed to load history."));
                    return;
                }
                sender.sendMessage(tx.playerText(tx.prefix + "Punishment history for " + ChatColor.RED + safeName(op.getName(), name)));
                if (rows == null || rows.isEmpty()) {
                    sender.sendMessage(tx.playerText(tx.shortprefix + "(none)"));
                    return;
                }
                for (PunishmentRow r : rows) {
                    sender.sendMessage(tx.playerText(tx.shortprefix
                            + ChatColor.RED + "#" + r.id() + ChatColor.RESET
                            + " " + ChatColor.RED + r.type() + ChatColor.RESET
                            + " " + (r.active() ? ChatColor.GREEN + "ACTIVE" : ChatColor.GRAY + "INACTIVE")
                            + ChatColor.RESET + " by " + ChatColor.RED + r.actorName()
                            + ChatColor.RESET + " - " + ChatColor.RED + shorten(r.reason(), 60)));
                }
            }));
        });
    }

    private PunishFlags flagsOrDefault(PunishFlags f) {
        // Default silent if neither -s nor -p provided
        if (!f.silent && !f.publicBroadcast) {
            return new PunishFlags(true, false); // silent by default
        }
        return f;
    }

    private static String joinFrom(String[] params, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < params.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(params[i]);
        }
        return sb.toString().trim();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String safeName(String a, String fallback) {
        return (a == null || a.isBlank()) ? fallback : a;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}