package me.hackerguardian.main.report;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.UUID;

public final class ReportCommands {

    private final HackerGuardian plugin;
    private final ReportService service;
    public textHandling tx = new textHandling();

    private final DateTimeFormatter fmt =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    public ReportCommands(HackerGuardian plugin, ReportService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register(CommandManager commandManager) {

        // /hg report <player> <reason...>
        commandManager.register("report", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            if (!HackerGuardian.CommandValidate.noPerm(sender, "report")) return;

            Player reporter = (Player) sender;

            if (!reporter.hasPermission("hg.report")) {
                sender.sendMessage("Unknown command. Type \"/help\" for help.\n");
                return;
            }

            if (params.length < 2) {
                sender.sendMessage(tx.playerText(tx.prefix + "Wrong parameters! /hg report <user> <reason>"));
                return;
            }

            String targetName = params[0];
            String reason = joinFrom(params, 1).trim();
            if (reason.length() < 3) {
                sender.sendMessage(tx.playerText(tx.prefix + "Please provide a longer reason."));
                return;
            }

            OfflinePlayer targetOffline = Bukkit.getOfflinePlayer(targetName);
            if (targetOffline == null || (!targetOffline.hasPlayedBefore() && Bukkit.getPlayerExact(targetName) == null)) {
                sender.sendMessage(tx.playerText(tx.prefix + "This player doesn't exist or has never joined."));
                return;
            }

            UUID reportedUuid = targetOffline.getUniqueId();
            if (reportedUuid == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Could not resolve player UUID."));
                return;
            }


            if (reportedUuid.equals(reporter.getUniqueId())) {
                sender.sendMessage(tx.playerText(tx.prefix + "You can't report yourself!"));
                return;
            }

            // Limits
            int maxInWindow = plugin.getConfig().getInt("Settings.max_reports_per_window", 10);
            long windowMs = plugin.getConfig().getLong("Settings.report_window_ms", 24L * 60L * 60L * 1000L);

            // async: check remaining, then create
            service.reportsRemainingInWindow(reporter.getUniqueId().toString(), maxInWindow, windowMs)
                    .whenComplete((remaining, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ex != null) {
                            sender.sendMessage(tx.playerText(tx.prefix + "Report system error. Try again later."));
                            if (plugin.getConfig().getBoolean("debug")){}
                            return;
                        }

                        if (remaining <= 0) {
                            sender.sendMessage(tx.playerText(tx.prefix + "You cannot make more reports right now. (limit " + maxInWindow + ")"));
                            return;
                        }

                        // Create report async
                        service.createReport(reporter, reportedUuid.toString(), targetOffline.getName() != null ? targetOffline.getName() : targetName, reason)
                                .whenComplete((reportId, ex2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (ex2 != null) {
                                        sender.sendMessage(tx.playerText(tx.prefix + "Failed to submit report. Try again later."));
                                        if (plugin.getConfig().getBoolean("debug")) ex2.printStackTrace();
                                        return;
                                    }

                                    int afterRemaining = remaining - 1;
                                    sender.sendMessage(tx.playerText(tx.prefix + "Thanks for your report. " +
                                            "Remaining: " + ChatColor.RED + afterRemaining + ChatColor.RESET + " (max " + maxInWindow + ")"));
                                    sender.sendMessage(tx.playerText(tx.shortprefix + "Report ID: " + ChatColor.RED + "#" + reportId));
                                    sender.sendMessage(tx.playerText(tx.shortprefix + "You: " + ChatColor.RED + reporter.getName()));
                                    sender.sendMessage(tx.playerText(tx.shortprefix + "Reported: " + ChatColor.RED + (targetOffline.getName() != null ? targetOffline.getName() : targetName)));
                                    sender.sendMessage(tx.playerText(tx.shortprefix + "Date: " + ChatColor.RED + fmt.format(Instant.now())));
                                    sender.sendMessage(tx.playerText(tx.shortprefix + "Reason:"));
                                    sender.sendMessage(tx.playerText("> " + ChatColor.RED + reason));

                                    // Notify staff
                                    for (Player staff : Bukkit.getOnlinePlayers()) {
                                        if (!staff.hasPermission("hg.reports.notify")) continue;
                                        staff.sendMessage("");
                                        staff.sendMessage(tx.playerText(tx.prefix +
                                                "New report " + ChatColor.RED + "#" + reportId +
                                                ChatColor.RESET + " against " + ChatColor.RED + (targetOffline.getName() != null ? targetOffline.getName() : targetName) +
                                                ChatColor.RESET + " (OPEN)"));
                                        staff.sendMessage(tx.playerText(tx.shortprefix +
                                                "Use: " + ChatColor.RED + "/hg reports view " + reportId));
                                        staff.sendMessage("");
                                    }
                                }));
                    }));
        });

        // /hg reports ...
        commandManager.register("reports", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            if (!HackerGuardian.CommandValidate.noPerm(sender, "reports")) return;

            if (!sender.hasPermission("hg.reports")) {
                sender.sendMessage("Unknown command. Type \"/help\" for help.\n");
                return;
            }

            if (params.length == 0 || params[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return;
            }

            String sub = params[0].toLowerCase();

            switch (sub) {
                case "view" -> handleView(sender, params);
                case "list" -> handleList(sender, params);
                case "comment" -> handleComment(sender, params);
                case "close" -> handleClose(sender, params);
                case "dismiss" -> handleDismiss(sender, params);
                case "open" -> handleOpen(sender, params);
                default -> sendHelp(sender);
            }
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(tx.playerText(tx.prefix + "Reports commands:"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg report <player> <reason...>"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg reports list <player> [page]"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg reports view <id>"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg reports comment <id> <text...>"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg reports close <id>"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg reports dismiss <id>"));
        sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.RED + "/hg reports open <id>"));
    }

    private void handleView(CommandSender sender, String[] params) {
        if (!sender.hasPermission("hg.reports.view")) {
            sender.sendMessage(tx.playerText(tx.prefix + "No permission."));
            return;
        }
        if (params.length < 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg reports view <id>"));
            return;
        }

        long id;
        try {
            id = Long.parseLong(params[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(tx.playerText(tx.prefix + "ID must be a number."));
            return;
        }

        service.getReport(id).whenComplete((opt, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Failed to load report."));
                if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                return;
            }
            if (opt.isEmpty()) {
                sender.sendMessage(tx.playerText(tx.prefix + "That report ID does not exist."));
                return;
            }

            ReportRow r = opt.get();

            sender.sendMessage(tx.playerText(tx.prefix + "Report " + ChatColor.RED + "#" + r.id()));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Status: " + ChatColor.RED + r.status()));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Reported: " + ChatColor.RED + r.reportedName() + ChatColor.GRAY + " (" + r.reportedUuid() + ")"));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Reporter: " + ChatColor.RED + r.reporterName()));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Created: " + ChatColor.RED + fmt.format(Instant.ofEpochMilli(r.createdAt()))));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Reason: " + ChatColor.RED + r.reason()));

            service.listComments(id).whenComplete((comments, ex2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex2 != null) {
                    sender.sendMessage(tx.playerText(tx.shortprefix + "Comments: " + ChatColor.RED + "(error loading)"));
                    return;
                }
                if (comments == null || comments.isEmpty()) {
                    sender.sendMessage(tx.playerText(tx.shortprefix + "Comments: (NONE)"));
                    return;
                }

                sender.sendMessage(tx.playerText(tx.shortprefix + "Comments (" + ChatColor.RED + comments.size() + ChatColor.RESET + "):"));
                int shown = 0;
                for (CommentRow c : comments) {
                    shown++;
                    sender.sendMessage(tx.playerText(tx.shortprefix
                            + ChatColor.RED + shown + ChatColor.RESET
                            + ". [" + ChatColor.RED + fmt.format(Instant.ofEpochMilli(c.createdAt())) + ChatColor.RESET + "] "
                            + ChatColor.RED + c.commenterName() + ChatColor.RESET
                            + ": " + ChatColor.RED + c.comment()));
                    if (shown >= 10) {
                        if (comments.size() > 10) {
                            sender.sendMessage(tx.playerText(tx.shortprefix + ChatColor.GRAY + "Showing first 10 comments..."));
                        }
                        break;
                    }
                }
            }));
        }));
    }

    private void handleList(CommandSender sender, String[] params) {
        if (!sender.hasPermission("hg.reports.view")) {
            sender.sendMessage(tx.playerText(tx.prefix + "No permission."));
            return;
        }
        if (params.length < 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg reports list <player> [page]"));
            return;
        }

        String targetName = params[1];
        OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
        if (op == null || (!op.hasPlayedBefore() && Bukkit.getPlayerExact(targetName) == null)) {
            sender.sendMessage(tx.playerText(tx.prefix + "This player doesn't exist or has never joined."));
            return;
        }

        int page = 1;
        if (params.length >= 3) {
            try { page = Integer.parseInt(params[2]); } catch (NumberFormatException ignored) {}
        }
        page = Math.max(1, page);

        int pageSize = plugin.getConfig().getInt("Settings.reports_page_size", 5);

        String reportedUuid = op.getUniqueId().toString();

        int finalPage = page;
        service.countReportsForPlayer(reportedUuid).whenComplete((total, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Failed to load report count."));
                return;
            }
            if (total <= 0) {
                sender.sendMessage(tx.playerText(tx.prefix + "There currently isn't any reports on this player."));
                return;
            }

            int maxPages = (int) Math.ceil(total / (double) pageSize);
            int safePage = Math.min(finalPage, maxPages);

            service.listReportsForPlayer(reportedUuid, safePage, pageSize)
                    .whenComplete((rows, ex2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ex2 != null) {
                            sender.sendMessage(tx.playerText(tx.prefix + "Failed to load reports."));
                            return;
                        }

                        sender.sendMessage(tx.playerText(tx.prefix + "Reports for " + ChatColor.RED + (op.getName() != null ? op.getName() : targetName)
                                + ChatColor.GRAY + " (" + safePage + "/" + maxPages + ")"));

                        for (ReportRow r : rows) {
                            String shortReason = shorten(r.reason(), 40);
                            sender.sendMessage(tx.playerText(tx.shortprefix
                                    + ChatColor.RED + "#" + r.id() + ChatColor.RESET
                                    + " [" + ChatColor.RED + r.status() + ChatColor.RESET + "] "
                                    + ChatColor.GRAY + fmt.format(Instant.ofEpochMilli(r.createdAt())) + ChatColor.RESET
                                    + " by " + ChatColor.RED + r.reporterName() + ChatColor.RESET
                                    + " - " + ChatColor.RED + shortReason));
                        }

                        if (maxPages > 1) {
                            sender.sendMessage(tx.playerText(tx.shortprefix + "Next: " + ChatColor.RED + "/hg reports list " + targetName + " " + (safePage + 1)));
                        }
                    }));
        }));
    }

    private void handleComment(CommandSender sender, String[] params) {
        if (!sender.hasPermission("hg.reports.comment")) {
            sender.sendMessage(tx.playerText(tx.prefix + "No permission."));
            return;
        }
        if (HackerGuardian.CommandValidate.notPlayer(sender)) return;

        if (params.length < 3) {
            sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg reports comment <id> <text...>"));
            return;
        }

        long id;
        try { id = Long.parseLong(params[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage(tx.playerText(tx.prefix + "ID must be a number."));
            return;
        }

        String text = joinFrom(params, 2).trim();
        if (text.length() < 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "Comment too short."));
            return;
        }

        Player staff = (Player) sender;

        service.addComment(id, staff, text).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                sender.sendMessage(tx.playerText(tx.prefix + "Failed to add comment."));
                return;
            }
            sender.sendMessage(tx.playerText(tx.prefix + "Comment added to report " + ChatColor.RED + "#" + id));
        }));
    }

    private void handleClose(CommandSender sender, String[] params) {
        if (!sender.hasPermission("hg.reports.close")) {
            sender.sendMessage(tx.playerText(tx.prefix + "No permission."));
            return;
        }
        if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
        if (params.length < 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg reports close <id>"));
            return;
        }

        long id;
        try { id = Long.parseLong(params[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage(tx.playerText(tx.prefix + "ID must be a number."));
            return;
        }

        Player staff = (Player) sender;

        service.close(id, staff).whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null || ok == null || !ok) {
                sender.sendMessage(tx.playerText(tx.prefix + "Failed to close report (invalid id?)."));
                return;
            }
            sender.sendMessage(tx.playerText(tx.prefix + "Closed report " + ChatColor.RED + "#" + id));
        }));
    }

    private void handleDismiss(CommandSender sender, String[] params) {
        if (!sender.hasPermission("hg.reports.dismiss")) {
            sender.sendMessage(tx.playerText(tx.prefix + "No permission."));
            return;
        }
        if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
        if (params.length < 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg reports dismiss <id>"));
            return;
        }

        long id;
        try { id = Long.parseLong(params[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage(tx.playerText(tx.prefix + "ID must be a number."));
            return;
        }

        Player staff = (Player) sender;

        service.dismiss(id, staff).whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null || ok == null || !ok) {
                sender.sendMessage(tx.playerText(tx.prefix + "Failed to dismiss report (invalid id?)."));
                return;
            }
            sender.sendMessage(tx.playerText(tx.prefix + "Dismissed report " + ChatColor.RED + "#" + id));
        }));
    }

    private void handleOpen(CommandSender sender, String[] params) {
        if (!sender.hasPermission("hg.reports.open")) {
            sender.sendMessage(tx.playerText(tx.prefix + "No permission."));
            return;
        }
        if (params.length < 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg reports open <id>"));
            return;
        }

        long id;
        try { id = Long.parseLong(params[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage(tx.playerText(tx.prefix + "ID must be a number."));
            return;
        }

        service.reopen(id).whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null || ok == null || !ok) {
                sender.sendMessage(tx.playerText(tx.prefix + "Failed to reopen report (invalid id?)."));
                return;
            }
            sender.sendMessage(tx.playerText(tx.prefix + "Reopened report " + ChatColor.RED + "#" + id));
        }));
    }

    private static String joinFrom(String[] params, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < params.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(params[i]);
        }
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 3)) + "...";
    }
}