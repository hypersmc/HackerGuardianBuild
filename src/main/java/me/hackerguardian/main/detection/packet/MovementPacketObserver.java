package me.hackerguardian.main.detection.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.compat.MinecraftVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

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
    private final boolean groundProbeEnabled;
    private final boolean packetPredictorEnabled;
    private final PacketGroundReachability.Profile packetModel;
    private final Map<Player, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask tickTask;
    private long schedulerTickIndex;
    private PacketAdapter inboundAdapter;
    private PacketAdapter outboundAdapter;
    private boolean running;

    public MovementPacketObserver(HackerGuardian plugin, int maxPerPlayer) {
        this(plugin, maxPerPlayer, false);
    }

    public MovementPacketObserver(HackerGuardian plugin, int maxPerPlayer,
                                  boolean groundProbeEnabled) {
        this(plugin, maxPerPlayer, groundProbeEnabled, false, null);
    }

    public MovementPacketObserver(HackerGuardian plugin, int maxPerPlayer,
                                  boolean groundProbeEnabled, boolean packetPredictorEnabled,
                                  MinecraftVersion serverVersion) {
        this.plugin = plugin;
        this.buffer = new MovementPacketBuffer(maxPerPlayer);
        this.groundProbeEnabled = groundProbeEnabled;
        this.packetPredictorEnabled = groundProbeEnabled && packetPredictorEnabled;
        this.packetModel = PacketGroundReachability.Profile.forMinecraft(serverVersion);
    }

    public void start() {
        if (running) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.put(player, new Session(player.getUniqueId(), player.getEntityId()));
        }

        try {
            PacketAdapter incoming = new PacketAdapter(
                    plugin,
                    ListenerPriority.MONITOR,
                    PacketType.Play.Client.GROUND,
                    PacketType.Play.Client.POSITION,
                    PacketType.Play.Client.LOOK,
                    PacketType.Play.Client.POSITION_LOOK,
                    PacketType.Play.Client.TELEPORT_ACCEPT
            ) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isCancelled()) return;
                    Session session = sessions.get(event.getPlayer());
                    if (session == null) return;
                    MovementPacketBuffer.Kind kind = classify(event.getPacketType());
                    if (kind != null) {
                        session.record(kind, MovementPacketDecoder.decode(event.getPacket(), kind));
                    } else if (PacketType.Play.Client.TELEPORT_ACCEPT.equals(event.getPacketType())) {
                        session.confirmTeleport(MovementWireDecoder.teleportId(event.getPacket()));
                    }
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(incoming);
            inboundAdapter = incoming;

            PacketAdapter outgoing = new PacketAdapter(
                    plugin, ListenerPriority.MONITOR,
                    PacketType.Play.Server.POSITION,
                    PacketType.Play.Server.ENTITY_VELOCITY) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    if (event.isCancelled()) return;
                    Session session = sessions.get(event.getPlayer());
                    if (session == null) return;
                    if (PacketType.Play.Server.POSITION.equals(event.getPacketType())) {
                        session.outboundTeleport(MovementWireDecoder.teleportId(event.getPacket()));
                    } else if (PacketType.Play.Server.ENTITY_VELOCITY.equals(event.getPacketType())) {
                        session.outboundSelfVelocity(event.getPacket());
                    }
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(outgoing);
            outboundAdapter = outgoing;
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::captureServerTicks, 1L, 1L);
            running = true;
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (inboundAdapter != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(inboundAdapter);
            inboundAdapter = null;
        }
        if (outboundAdapter != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(outboundAdapter);
            outboundAdapter = null;
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

    /** Attach capture to the already-serialized player packet session. Bukkit thread only. */
    public boolean beginCourse(Player player, PacketCourseCapture capture) {
        Session session = player == null ? null : sessions.get(player);
        return session != null && session.setCourseCapture(capture);
    }

    public boolean advanceCourse(Player player, PacketCourseCapture capture, PacketCourseCapture.Phase phase) {
        Session session = player == null ? null : sessions.get(player);
        return session != null && session.advanceCourse(capture, phase);
    }

    public void endCourse(UUID playerId, PacketCourseCapture capture) {
        for (Session session : sessions.values()) {
            if (session.playerId.equals(playerId)) {
                session.clearCourseCapture(capture);
                return;
            }
        }
    }

    /** Packet-coordinate research, not a detector or a Bukkit-speed profile. */
    public PacketGroundReachability.Snapshot packetPredictionSnapshot(Player player) {
        Session session = player == null ? null : sessions.get(player);
        return session == null ? null : session.packetPredictionSnapshot();
    }

    /** Pure research statistics, intentionally disconnected from detection and enforcement. */
    public MovementGroundProbe.Snapshot groundProbeSnapshot(Player player) {
        Session session = player == null ? null : sessions.get(player);
        return session == null ? null : session.groundProbeSnapshot();
    }

    /** Server-sampled state and conservative client/server position comparison. */
    public MovementTickTimeline.Snapshot tickSnapshot(Player player) {
        Session session = player == null ? null : sessions.get(player);
        return session == null ? null : session.tickSnapshot();
    }

    private void captureServerTicks() {
        long tick = ++schedulerTickIndex;
        for (Map.Entry<Player, Session> entry : sessions.entrySet()) {
            Player player = entry.getKey();
            if (!player.isOnline()) continue;
            MovementTickTimeline.TickInput input =
                    MovementTickSampler.sample(player, groundProbeEnabled);
            if (input != null) entry.getValue().sampleServerTick(tick, input);
        }
    }

    public MovementPacketBuffer.Snapshot snapshot(UUID playerId) {
        return buffer.snapshot(playerId);
    }

    /** Returns an immutable snapshot, or null when the player's session is unavailable. */
    public MovementPacketTransport.Snapshot transportSnapshot(Player player) {
        Session session = player == null ? null : sessions.get(player);
        return session == null ? null : session.transportSnapshot();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessions.put(event.getPlayer(),
                new Session(event.getPlayer().getUniqueId(), event.getPlayer().getEntityId()));
    }

    /** The runtime entity ID can change at respawn; refresh it on the next Bukkit tick. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Session session = sessions.get(player);
            if (session != null && player.isOnline()) session.updateEntityId(player.getEntityId());
        });
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Session session = sessions.get(event.getPlayer());
        if (session != null) session.markCorrection(MovementTickTimeline.Correction.WORLD_CHANGE);
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
        private final MovementPacketTransport transport = new MovementPacketTransport();
        private final MovementTickTimeline timeline = new MovementTickTimeline();
        private final MovementGroundProbe groundProbe = groundProbeEnabled
                ? new MovementGroundProbe() : null;
        private final PacketGroundReachability packetPrediction = packetPredictorEnabled
                ? new PacketGroundReachability(packetModel) : null;
        private PacketCourseCapture attachedCourse;
        private int selfEntityId;
        private boolean active = true;

        private Session(UUID playerId, int selfEntityId) {
            this.playerId = playerId;
            this.selfEntityId = selfEntityId;
        }

        private synchronized boolean setCourseCapture(PacketCourseCapture capture) {
            if (!active || packetPrediction == null || attachedCourse != null) return false;
            attachedCourse = capture;
            packetPrediction.setCourseCapture(capture);
            return true;
        }

        private synchronized boolean advanceCourse(PacketCourseCapture capture, PacketCourseCapture.Phase phase) {
            if (!active || attachedCourse != capture) return false;
            capture.enter(phase);
            return true;
        }

        private synchronized void clearCourseCapture(PacketCourseCapture capture) {
            if (attachedCourse != capture) return;
            if (packetPrediction != null) packetPrediction.setCourseCapture(null);
            attachedCourse = null;
        }

        private synchronized void updateEntityId(int entityId) {
            if (active) {
                selfEntityId = entityId;
                timeline.markCorrection(MovementTickTimeline.Correction.RESPAWN);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized void markCorrection(MovementTickTimeline.Correction reason) {
            if (active) {
                timeline.markCorrection(reason);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized MovementPacketTransport.Snapshot transportSnapshot() {
            return active ? transport.snapshot() : null;
        }

        private synchronized MovementTickTimeline.Snapshot tickSnapshot() {
            return active ? timeline.snapshot(transport.hasPendingTeleport()) : null;
        }

        private synchronized MovementGroundProbe.Snapshot groundProbeSnapshot() {
            return active && groundProbe != null ? groundProbe.snapshot() : null;
        }

        private synchronized PacketGroundReachability.Snapshot packetPredictionSnapshot() {
            return active && packetPrediction != null ? packetPrediction.snapshot() : null;
        }

        private synchronized void sampleServerTick(long tick, MovementTickTimeline.TickInput input) {
            if (!active) return;
            boolean pendingTeleport = transport.hasPendingTeleport();
            long oldEpoch = timeline.currentCorrectionEpoch();
            timeline.sampleServerTick(tick, input, pendingTeleport);
            if (packetPrediction != null
                    && timeline.currentCorrectionEpoch() != oldEpoch) {
                // Fallback for world transitions that produced no Bukkit
                // event but were observed in consecutive server samples.
                packetPrediction.correction();
            }
            if (groundProbe != null) {
                groundProbe.sample(tick, System.nanoTime(),
                        timeline.currentCorrectionEpoch(), input,
                        timeline.settledForResearch(pendingTeleport),
                        timeline.hasRecentClientPosition());
            }
        }

        private synchronized void record(MovementPacketBuffer.Kind kind,
                                         MovementPacketBuffer.PacketState state) {
            if (active) {
                buffer.record(playerId, kind, state);
                transport.movement();
                if (state != null && state.decoded() && state.position() != null) {
                    timeline.observeClientPosition(state.position());
                }
                if (packetPrediction != null
                        && (kind == MovementPacketBuffer.Kind.POSITION
                            || kind == MovementPacketBuffer.Kind.POSITION_LOOK)) {
                    // All Bukkit references were copied into immutable
                    // tick state on the main thread; never use Player APIs
                    // during ProtocolLib's packet receive callback.
                    var context = timeline.packetResearchContext(transport.hasPendingTeleport());
                    packetPrediction.observe(state, context);
                }
            }
        }

        private synchronized void teleport(String worldKey, MovementPacketBuffer.Position position,
                                           MovementPacketBuffer.Rotation rotation, String cause) {
            if (active) {
                buffer.recordServerTeleport(playerId, worldKey, position, rotation, cause);
                transport.serverTeleportIntent();
                timeline.markCorrection(MovementTickTimeline.Correction.BUKKIT_TELEPORT);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized void velocity(String worldKey, MovementPacketBuffer.Velocity velocity) {
            if (active) {
                buffer.recordServerVelocity(playerId, worldKey, velocity);
                transport.serverVelocityIntent(velocity);
                timeline.markCorrection(MovementTickTimeline.Correction.BUKKIT_VELOCITY);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized void outboundTeleport(Integer id) {
            if (active) {
                transport.outboundTeleport(id);
                timeline.markCorrection(MovementTickTimeline.Correction.OUTBOUND_TELEPORT);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized void confirmTeleport(Integer id) {
            if (active) {
                transport.inboundConfirm(id);
                timeline.markCorrection(MovementTickTimeline.Correction.TELEPORT_CONFIRM);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized void outboundSelfVelocity(com.comphenix.protocol.events.PacketContainer packet) {
            if (!active) return;
            Integer targetId = MovementWireDecoder.velocityTargetId(packet);
            MovementPacketBuffer.Velocity velocity = null;
            if (targetId != null && targetId.intValue() == selfEntityId) {
                MovementWireDecoder.SelfVelocity decoded =
                        MovementWireDecoder.selfVelocity(packet, selfEntityId);
                velocity = decoded.forSelf() ? decoded.velocity() : null;
            }
            transport.observedOutboundVelocity(targetId, selfEntityId, velocity);
            if (targetId != null && targetId.intValue() == selfEntityId) {
                timeline.markCorrection(MovementTickTimeline.Correction.OUTBOUND_SELF_VELOCITY);
                if (packetPrediction != null) packetPrediction.correction();
            }
        }

        private synchronized void close() {
            if (packetPrediction != null) packetPrediction.setCourseCapture(null);
            attachedCourse = null;
            active = false;
            buffer.clearPlayer(playerId);
        }
    }
}
