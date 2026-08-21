package me.hackerguardian.main.replay;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.moderation.punish.TimeFormat;
import me.hackerguardian.main.replay.view.ReplayViewer;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public final class ReplayCommand {

    private final ReplayManager rm;
    private final textHandling tx; // your text wrapper
    private final ReplayViewer replayViewer;

    public ReplayCommand(ReplayManager rm, textHandling tx, ReplayViewer replayViewer) {
        this.rm = rm;
        this.tx = tx;
        this.replayViewer = replayViewer;
    }

    public void register(CommandManager commandManager) {
        commandManager.register("replay", (sender, params) -> {
            if (HackerGuardian.CommandValidate.notPlayer(sender)) return;
            Player staff = (Player) sender;

            if (!staff.hasPermission("hg.replay")) {
                staff.sendMessage(tx.playerText(tx.prefix + "No permission."));
                return;
            }

            if (params.length < 1) {
                staff.sendMessage(tx.playerText(tx.prefix + "Usage: /hg replay <start|stop|ai|view|info> ..."));
                return;
            }

            String sub = params[0].toLowerCase();

            // ID-based commands
            if (sub.equals("view") || sub.equals("info") || sub.equals("exit")) {
                if (params.length < 2) {
                    staff.sendMessage(tx.playerText(tx.prefix + "Usage: /hg replay " + sub + " <id>"));
                    return;
                }

                long id;
                try {
                    id = Long.parseLong(params[1]);
                } catch (NumberFormatException e) {
                    staff.sendMessage(tx.playerText(tx.prefix + "Invalid replay id."));
                    return;
                }

                if (sub.equals("info")) {
                    Bukkit.getScheduler().runTaskAsynchronously(HackerGuardian.getInstance(), () -> {
                        ReplayStorage.ReplayInfoMeta meta;
                        try {
                            meta = rm.getStorage().getReplayInfoMeta(id);
                        } catch (Exception ex) {
                            meta = null;
                            if (HackerGuardian.getInstance().getConfig().getBoolean("debug")) ex.printStackTrace();
                        }

                        ReplayStorage.ReplayInfoMeta finalMeta = meta;

                        Bukkit.getScheduler().runTask(HackerGuardian.getInstance(), () -> {
                            if (finalMeta == null) {
                                staff.sendMessage(tx.playerText(tx.prefix + "Replay not found or DB error."));
                                return;
                            }

                            long now = System.currentTimeMillis();
                            long endForDuration = (finalMeta.endedAt == null) ? now : finalMeta.endedAt;

                            String started = TimeFormat.dateTime(finalMeta.startedAt);
                            String ended = (finalMeta.endedAt == null) ? "Still recording" : TimeFormat.dateTime(finalMeta.endedAt);
                            String duration = TimeFormat.remaining(finalMeta.startedAt, endForDuration);

                            String aiScore = (finalMeta.aiScore == null) ? "N/A" : String.format("%.4f", finalMeta.aiScore);

                            staff.sendMessage(tx.playerText(tx.prefix + "Replay Info"));
                            staff.sendMessage(tx.playerText(tx.shortprefix + "ID: " + ChatColor.RED + finalMeta.id));
                            staff.sendMessage(tx.playerText(tx.shortprefix + "Player: " + ChatColor.RED + finalMeta.playerName
                                    + ChatColor.GRAY + " (" + ChatColor.RED + finalMeta.playerUuid + ChatColor.GRAY + ")"));
                            staff.sendMessage(tx.playerText(tx.shortprefix + "Server: " + ChatColor.RED + finalMeta.serverName));

                            staff.sendMessage(tx.playerText(tx.shortprefix + "Trigger: " + ChatColor.RED + safe(finalMeta.triggerType)
                                    + ChatColor.GRAY + " | AI score: " + ChatColor.RED + aiScore));

                            if (finalMeta.triggerMeta != null && !finalMeta.triggerMeta.isBlank()) {
                                staff.sendMessage(tx.playerText(tx.shortprefix + "Meta: " + ChatColor.RED + finalMeta.triggerMeta));
                            }

                            staff.sendMessage(tx.playerText(tx.shortprefix + "Started: " + ChatColor.RED + started));
                            staff.sendMessage(tx.playerText(tx.shortprefix + "Ended: " + ChatColor.RED + ended));
                            staff.sendMessage(tx.playerText(tx.shortprefix + "Duration: " + ChatColor.RED + duration));

                            staff.sendMessage(tx.playerText(tx.shortprefix + "Format: " + ChatColor.RED + "v" + finalMeta.formatVersion
                                    + ChatColor.GRAY + " | Codec: " + ChatColor.RED + safe(finalMeta.codec)));


                            staff.sendMessage(tx.playerText(tx.shortprefix + "Bytes: " + ChatColor.RED + humanBytes(finalMeta.bytesTotal)));


                            staff.sendMessage(tx.playerText(tx.shortprefix + "Commands: "
                                    + ChatColor.RED + "/hg replay view " + finalMeta.id
                                    + ChatColor.GRAY + " | "
                                    + ChatColor.RED + "/hg replay exit"));
                        });
                    });
                    return;
                }
                if (sub.equals("exit")) {
                    replayViewer.stopViewing(staff);
                    staff.sendMessage(tx.playerText(tx.shortprefix + "Stopped replay viewing."));
                    return;
                }

                replayViewer.view(staff, id);
                return;
            }

            // Player-based commands
            if (params.length < 2) {
                staff.sendMessage(tx.playerText(tx.prefix + "Usage: /hg replay <start|stop|ai> <player> [score]"));
                return;
            }

            Player target = Bukkit.getPlayerExact(params[1]);
            if (target == null) {
                staff.sendMessage(tx.playerText(tx.prefix + "Player must be online."));
                return;
            }

            if (sub.equals("start")) {
                rm.triggerManual(target, staff, "manual start");
                staff.sendMessage(tx.playerText(tx.shortprefix + "Started recording for " + target.getName()));
                return;
            }

            if (sub.equals("stop")) {
                rm.stop(target);
                staff.sendMessage(tx.playerText(tx.shortprefix + "Stopped recording for " + target.getName()));
                return;
            }


            if (sub.equals("ai")) {
                double score = 0.95;
                if (params.length >= 3) {
                    try { score = Double.parseDouble(params[2]); } catch (Exception ignored) {}
                }
                rm.triggerFromAi(target, score, "test-model");
                staff.sendMessage(tx.playerText(tx.shortprefix + "AI-triggered replay for " + target.getName() + " score=" + score));
                return;
            }

            staff.sendMessage(tx.playerText(tx.prefix + "Unknown subcommand."));
        });
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "N/A" : s;
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format("%.2f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
