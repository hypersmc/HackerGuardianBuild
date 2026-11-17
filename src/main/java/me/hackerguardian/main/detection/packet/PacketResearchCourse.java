package me.hackerguardian.main.detection.packet;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guided, opt-in, non-destructive movement research course.
 * World traversal/checkpoint checks run only on Bukkit's main thread.
 * All measured motion comes from the existing ProtocolLib POSITION observer.
 */
public final class PacketResearchCourse {
    private static final long LIMIT_MS = 180_000L;
    private static final double CHECKPOINT_RADIUS = 1.8;
    private static final double CORRIDOR_RADIUS = 2.3;
    private static final double MAX_OFF_COURSE = 5.0;
    private final HackerGuardian plugin;
    private final MovementPacketObserver observer;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Report> reports = new HashMap<>();
    private BukkitTask task;

    public record Report(String player, boolean complete, String reason,
                         List<PacketCourseCapture.PhaseResult> phases) {}

    private static final class Session {
        final UUID id;
        final String name;
        final World world;
        final int y;
        final List<PacketCourseGeometry.Leg> legs;
        final PacketCourseCapture capture = new PacketCourseCapture();
        final long started = System.currentTimeMillis();
        int leg;
        int offCourseTicks;

        Session(Player player, List<PacketCourseGeometry.Leg> legs) {
            this.id = player.getUniqueId();
            this.name = player.getName();
            this.world = player.getWorld();
            this.y = player.getLocation().getBlockY();
            this.legs = legs;
        }
    }

    public PacketResearchCourse(HackerGuardian plugin, MovementPacketObserver observer) {
        this.plugin = plugin;
        this.observer = observer;
    }

    public String start(Player player) {
        if (!observer.isRunning() || observer.packetPredictionSnapshot(player) == null
                || !observer.packetPredictionSnapshot(player).enabled()) {
            return "Packet reachability research must be enabled before starting a course.";
        }
        if (sessions.containsKey(player.getUniqueId())) return "You already have an active course.";
        Location loc = player.getLocation();
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            return "Use Survival mode; Creative movement is not eligible for this study.";
        }
        int quarterTurn = Math.floorMod(Math.round(loc.getYaw() / 90f), 4);
        int forwardX = switch (quarterTurn) { case 1 -> -1; case 3 -> 1; default -> 0; };
        int forwardZ = switch (quarterTurn) { case 0 -> 1; case 2 -> -1; default -> 0; };
        List<PacketCourseGeometry.Leg> chosen = null;
        for (boolean left : new boolean[]{true, false}) {
            var attempt = PacketCourseGeometry.build(loc.getBlockX(), loc.getBlockZ(),
                    forwardX, forwardZ, left);
            if (safeCourse(loc.getWorld(), loc.getBlockY(), attempt)) {
                chosen = attempt;
                break;
            }
        }
        if (chosen == null) {
            return "No safe flat 58-block course in either direction. Try an open flat platform.";
        }

        Session session = new Session(player, chosen);
        reports.remove(player.getUniqueId());
        session.capture.enter(chosen.get(0).phase());
        if (!observer.beginCourse(player, session.capture)) {
            return "Packet observation session became unavailable. Try again.";
        }
        sessions.put(player.getUniqueId(), session);
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 5L);
        }
        player.sendMessage("§6[HG Course] §fFollow the particles and enter each checkpoint.");
        player.sendMessage("§6[HG Course] §aWalk§f for legs 1-3, then §bSprint§f for legs 4-6.");
        player.sendMessage("§7No world blocks are placed or modified. Move naturally.");
        player.sendMessage("§6[HG Course] §f1/6: " + chosen.get(0).phase().label());
        return null;
    }

    public boolean active(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public boolean cancel(Player player) {
        if (!sessions.containsKey(player.getUniqueId())) return false;
        finish(player.getUniqueId(), false, "Cancelled");
        return true;
    }

    public Report lastReport(UUID playerId) {
        return reports.get(playerId);
    }

    public void shutdown() {
        for (UUID id : List.copyOf(sessions.keySet())) finish(id, false, "Plugin stopped");
        reports.clear();
        stopTaskIfIdle();
    }

    private void tick() {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (!sessions.containsKey(s.id)) continue;
            Player player = Bukkit.getPlayer(s.id);
            if (player == null || !player.isOnline()) {
                finish(s.id, false, "Disconnected");
                continue;
            }
            if (!player.getWorld().equals(s.world)
                    || player.getGameMode() != org.bukkit.GameMode.SURVIVAL
                    || System.currentTimeMillis() - s.started > LIMIT_MS) {
                finish(s.id, false, "World/mode changed or 3-minute timeout");
                continue;
            }
            Location current = player.getLocation();
            if (Math.abs(current.getY() - s.y) > 1.5) {
                finish(s.id, false, "Left level course");
                continue;
            }
            PacketCourseGeometry.Leg leg = s.legs.get(s.leg);
            double dist = distanceToLine(current.getX(), current.getZ(), leg);
            if (dist > MAX_OFF_COURSE) {
                if (++s.offCourseTicks >= 8) {
                    finish(s.id, false, "Left course corridor");
                    continue;
                }
            } else s.offCourseTicks = 0;
            draw(player, s, leg);
            double destinationX = leg.to().x() + 0.5;
            double destinationZ = leg.to().z() + 0.5;
            if (Math.hypot(current.getX() - destinationX,
                    current.getZ() - destinationZ) > CHECKPOINT_RADIUS
                    || dist > CORRIDOR_RADIUS) continue;

            s.leg++;
            if (s.leg == s.legs.size()) {
                finish(s.id, true, "All checkpoints reached");
                continue;
            }
            PacketCourseGeometry.Leg next = s.legs.get(s.leg);
            if (!observer.advanceCourse(player, s.capture, next.phase())) {
                finish(s.id, false, "Packet observer unavailable");
                continue;
            }
            player.sendMessage("§6[HG Course] §f" + (s.leg + 1) + "/6: "
                    + (next.phase().expectsSprint() ? "§b" : "§a")
                    + next.phase().label());
        }
        stopTaskIfIdle();
    }

    private void finish(UUID id, boolean complete, String reason) {
        Session s = sessions.remove(id);
        if (s == null) return;
        observer.endCourse(id, s.capture);
        Report report = new Report(s.name, complete, reason, s.capture.finish());
        reports.put(id, report);
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) {
            player.sendMessage("§6[HG Course] §f" + (complete ? "§aFinished" : "§eStopped")
                    + "§f: " + reason + " | phases " + report.phases().size() + "/6");
            long modeled = 0, timing = 0, total = 0;
            for (var phase : report.phases()) {
                modeled += phase.modeled();
                timing += phase.timingRejects();
                total += phase.packets();
            }
            player.sendMessage("§7POSITION packets " + total + " | modeled " + modeled
                    + " | timing rejected " + timing);
            player.sendMessage("§7Full breakdown: §f/hg detection course report");
        }
        stopTaskIfIdle();
    }

    private void stopTaskIfIdle() {
        if (sessions.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private static double distanceToLine(double x, double z, PacketCourseGeometry.Leg leg) {
        double ax = leg.from().x() + 0.5, az = leg.from().z() + 0.5;
        double bx = leg.to().x() + 0.5, bz = leg.to().z() + 0.5;
        double dx = bx - ax, dz = bz - az;
        double t = ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - ax - t * dx, z - az - t * dz);
    }

    private static boolean safeCourse(World world, int y, List<PacketCourseGeometry.Leg> legs) {
        if (world == null || y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
        for (var leg : legs) {
            int length = leg.length();
            for (int n = 0; n <= length; n++) {
                int x = leg.from().x() + (leg.to().x() - leg.from().x()) * n / length;
                int z = leg.from().z() + (leg.to().z() - leg.from().z()) * n / length;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                if (!world.getBlockAt(x, y - 1, z).getType().isSolid()
                        || !world.getBlockAt(x, y, z).isPassable()
                        || !world.getBlockAt(x, y + 1, z).isPassable()) return false;
            }
        }
        return true;
    }

    private static void draw(Player player, Session session, PacketCourseGeometry.Leg leg) {
        int length = leg.length();
        for (int n = 0; n <= length; n += 2) {
            double t = (double) n / length;
            double x = leg.from().x() + 0.5 + t * (leg.to().x() - leg.from().x());
            double z = leg.from().z() + 0.5 + t * (leg.to().z() - leg.from().z());
            player.spawnParticle(Particle.END_ROD, x, session.y + 0.12, z,
                    1, 0, 0, 0, 0);
        }
        player.spawnParticle(Particle.HAPPY_VILLAGER,
                leg.to().x() + 0.5, session.y + 0.6, leg.to().z() + 0.5,
                6, 0.35, 0.3, 0.35, 0);
    }

    public static List<String> format(Report report) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("§6[HG Course] §f" + report.player() + " | "
                + (report.complete() ? "COMPLETE" : "PARTIAL") + " | " + report.reason());
        lines.add("§7Server tick hints are NOT client simulation ticks.");
        for (var r : report.phases()) {
            lines.add("§e" + r.phase().label() + " §7packets " + r.packets()
                    + " modeled " + r.modeled() + " timing " + r.timingRejects()
                    + " gaps " + r.gapRejects());
            lines.add(" §7sprint matched/mismatch " + r.matchedSprint() + "/"
                    + r.mismatchedSprint() + " | angles " + r.headings().measured()
                    + " p95 " + decimal(r.headings().p95())
                    + " | max run " + length(r.runs().acceptedRuns().max()));
        }
        return lines;
    }

    private static String decimal(Double value) {
        return value == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String length(Long value) {
        return value == null ? "-" : value.toString();
    }
}
