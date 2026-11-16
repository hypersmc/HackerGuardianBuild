package me.hackerguardian.main.detection.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.HackerGuardian;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProtocolLib -> bounded normalized movement observations, with separate
 * Bukkit-side context for server-initiated teleport/velocity events.
 *
 * ProtocolLib normally invokes client packet handlers asynchronously.
 * This listener performs no Bukkit API lookups or mutations in the packet
 * callback; UUIDs are captured on the Bukkit thread at join/startup.
 */
public final class MovementPacketObserver implements Listener {

    private final HackerGuardian plugin;
    private final MovementPacketBuffer buffer;
    private final Map<Player, Session> sessions = new ConcurrentHashMap<>();
    private PacketAdapter adapter;
    private boolean running;

    public MovementPacketObserver(HackerGuardian plugin, int maxPerPlayer) {
        this.plugin = plugin;
        this.buffer = new MovementPacketBuffer(maxPerPlayer);
    }

    public void start() {
        if (running) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.put(player, new Session(player.getUniqueId()));
        }

        try {
            PacketAdapter newAdapter = new PacketAdapter(
                    plugin,
                    ListenerPriority.MONITOR,
                    PacketType.Play.Client.GROUND,
                    PacketType.Play.Client.POSITION,
                    PacketType.Play.Client.LOOK,
                    PacketType.Play.Client.POSITION_LOOK
            ) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isCancelled()) return;
                    Session session = sessions.get(event.getPlayer());
                    if (session == null) return;
                    MovementPacketBuffer.Kind kind = classify(event.getPacketType());
                    if (kind != null) session.record(kind,
                            MovementPacketDecoder.decode(event.getPacket(), kind));
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(newAdapter);
            adapter = newAdapter;
            running = true;
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        if (adapter != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
            adapter = null;
        }
        HandlerList.unregisterAll(this);
        for (Session session : sessions.values()) session.close();
        sessions.clear();
        buffer.clear();
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public MovementPacketBuffer.Snapshot snapshot(UUID playerId) {
        return buffer.snapshot(playerId);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessions.put(event.getPlayer(), new Session(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer());
        if (session != null) session.close();
    }

    /**
     * Server-intent context from Bukkit, not an outgoing packet or client ACK.
     * The target world/position and cause are copied on the Bukkit thread.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Session session = sessions.get(event.getPlayer());
        if (session == null) return;
        Location target = event.getTo();
        if (target == null || target.getWorld() == null) return;
        try {
            session.teleport(
                    target.getWorld().getUID().toString(),
                    new MovementPacketBuffer.Position(target.getX(), target.getY(), target.getZ()),
                    new MovementPacketBuffer.Rotation(target.getYaw(), target.getPitch()),
                    event.getCause().name());
        } catch (IllegalArgumentException ignored) {
            // Bad world coordinates must not reach evidence storage.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Session session = sessions.get(event.getPlayer());
        if (session == null) return;
        Vector vector = event.getVelocity();
        if (vector == null || event.getPlayer().getWorld() == null) return;
        try {
            session.velocity(event.getPlayer().getWorld().getUID().toString(),
                    new MovementPacketBuffer.Velocity(vector.getX(), vector.getY(), vector.getZ()));
        } catch (IllegalArgumentException ignored) {
            // Non-finite velocity must not poison later movement analysis.
        }
    }

    private static MovementPacketBuffer.Kind classify(PacketType type) {
        if (PacketType.Play.Client.POSITION.equals(type)) return MovementPacketBuffer.Kind.POSITION;
        if (PacketType.Play.Client.POSITION_LOOK.equals(type)) return MovementPacketBuffer.Kind.POSITION_LOOK;
        if (PacketType.Play.Client.LOOK.equals(type)) return MovementPacketBuffer.Kind.LOOK;
        if (PacketType.Play.Client.GROUND.equals(type)) return MovementPacketBuffer.Kind.GROUND;
        return null;
    }

    private final class Session {
        private final UUID playerId;
        private boolean active = true;

        private Session(UUID playerId) {
            this.playerId = playerId;
        }

        private synchronized void record(MovementPacketBuffer.Kind kind,
                                         MovementPacketBuffer.PacketState state) {
            if (active) buffer.record(playerId, kind, state);
        }

        private synchronized void teleport(String worldKey, MovementPacketBuffer.Position position,
                                           MovementPacketBuffer.Rotation rotation, String cause) {
            if (active) buffer.recordServerTeleport(playerId, worldKey, position, rotation, cause);
        }

        private synchronized void velocity(String worldKey, MovementPacketBuffer.Velocity velocity) {
            if (active) buffer.recordServerVelocity(playerId, worldKey, velocity);
        }

        private synchronized void close() {
            active = false;
            buffer.clearPlayer(playerId);
        }
    }
}
