package me.hackerguardian.main.replay.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeReplayPlayer {

    private static final AtomicInteger ENTITY_ID_SEQ = new AtomicInteger(2_000_000);

    private final JavaPlugin plugin;
    private final ProtocolManager pm;
    private final Player viewer;

    private final int entityId;
    private final UUID uuid;
    private final String name;
    private final WrappedGameProfile profile;
    private double lastX, lastY, lastZ;
    private boolean hasLastPos = false;

    private static final double REL_MAX = 8.0; // if we jump more than 8 blocks, teleport instead
    private static final double REL_SCALE = 4096.0; // vanilla relative-move scale

    private Material currentMainHand = null;

    public FakeReplayPlayer(JavaPlugin plugin, Player viewer, UUID uuid, String name, WrappedGameProfile skinProfileOrNull) {
        this.plugin = plugin;
        this.pm = ProtocolLibrary.getProtocolManager();
        this.viewer = viewer;

        this.entityId = ENTITY_ID_SEQ.getAndIncrement();
        this.uuid = (uuid != null) ? uuid : new UUID(ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
        this.name = (name != null && !name.isBlank()) ? name : "Replay";

        // IMPORTANT: avoid WrappedGameProfile.fromPlayer(...) unless you *need* it.
        // A plain WrappedGameProfile is enough for a default Steve/Alex skin.
        this.profile = (skinProfileOrNull != null) ? skinProfileOrNull : new WrappedGameProfile(this.uuid, this.name);
    }

    public int getEntityId() {
        return entityId;
    }

    public void spawn(Location loc) {
        this.lastX = loc.getX();
        this.lastY = loc.getY();
        this.lastZ = loc.getZ();
        this.hasLastPos = true;

        sendPlayerInfoAdd();

        // 1.20.4+ : NAMED_ENTITY_SPAWN is gone -> use SPAWN_ENTITY (player type) instead.
        sendSpawnEntity(loc);

        // Send head rotation to match yaw right away
        sendHeadRotation(loc);

        // Optional: drop from tab list shortly after spawning
        Bukkit.getScheduler().runTaskLater(plugin, this::sendPlayerInfoRemove, 10L);
    }

    public void destroy() {
        // Destroy entity clientside
        try {
            PacketContainer destroy = pm.createPacket(com.comphenix.protocol.PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().write(0, Collections.singletonList(entityId));
            safeSend(destroy);
        } catch (Throwable ignored) {
        }

        // Also remove from tab list just in case
        sendPlayerInfoRemove();
    }

    public void moveTo(Location loc) {
        if (!hasLastPos) {
            // First movement update, just teleport
            teleportAbsolute(loc);
            return;
        }

        double dx = loc.getX() - lastX;
        double dy = loc.getY() - lastY;
        double dz = loc.getZ() - lastZ;

        // If the step is too big, do an absolute teleport (prevents short overflow / rubber-banding)
        if (Math.abs(dx) > REL_MAX || Math.abs(dy) > REL_MAX || Math.abs(dz) > REL_MAX) {
            teleportAbsolute(loc);
        } else {
            relMoveLook(dx, dy, dz, loc.getYaw(), loc.getPitch(), false);
            // Head yaw packet (optional but nice)
            sendHeadRotation(loc);
        }

        this.lastX = loc.getX();
        this.lastY = loc.getY();
        this.lastZ = loc.getZ();
    }
    private void relMoveLook(double dx, double dy, double dz, float yaw, float pitch, boolean onGround) {
        PacketContainer p;

        // Prefer REL_ENTITY_MOVE_LOOK; if unavailable, fall back to REL_ENTITY_MOVE + ENTITY_LOOK.
        try {
            p = pm.createPacket(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        } catch (IllegalArgumentException ignored) {
            // No combined packet on this server/protocollib mapping
            relMoveOnly(dx, dy, dz, onGround);
            lookOnly(yaw, pitch, onGround);
            return;
        }

        writeSafe(p.getIntegers(), 0, entityId);

        short sdx = (short) Math.round(dx * REL_SCALE);
        short sdy = (short) Math.round(dy * REL_SCALE);
        short sdz = (short) Math.round(dz * REL_SCALE);

        // Deltas are SHORTS on most versions. :contentReference[oaicite:1]{index=1}
        if (p.getShorts() != null && p.getShorts().size() >= 3) {
            p.getShorts().write(0, sdx);
            p.getShorts().write(1, sdy);
            p.getShorts().write(2, sdz);
        } else {
            // If your build uses bytes/ints for deltas, add more fallbacks here
            plugin.getLogger().warning("REL move packet has no shorts; cannot move entity reliably on this build.");
            return;
        }

        // Rotation (usually bytes)
        if (p.getBytes() != null && p.getBytes().size() >= 2) {
            p.getBytes().write(0, angleToByte(yaw));
            p.getBytes().write(1, angleToByte(pitch));
        } else if (p.getFloat() != null && p.getFloat().size() >= 2) {
            p.getFloat().write(0, yaw);
            p.getFloat().write(1, pitch);
        }

        if (p.getBooleans() != null && p.getBooleans().size() >= 1) {
            p.getBooleans().write(0, onGround);
        }

        safeSend(p);
    }

    private void relMoveOnly(double dx, double dy, double dz, boolean onGround) {
        PacketContainer p = pm.createPacket(PacketType.Play.Server.REL_ENTITY_MOVE);
        writeSafe(p.getIntegers(), 0, entityId);

        short sdx = (short) Math.round(dx * REL_SCALE);
        short sdy = (short) Math.round(dy * REL_SCALE);
        short sdz = (short) Math.round(dz * REL_SCALE);

        if (p.getShorts() != null && p.getShorts().size() >= 3) {
            p.getShorts().write(0, sdx);
            p.getShorts().write(1, sdy);
            p.getShorts().write(2, sdz);
        } else {
            plugin.getLogger().warning("REL move packet has no shorts; cannot move entity reliably on this build.");
            return;
        }

        if (p.getBooleans() != null && p.getBooleans().size() >= 1) {
            p.getBooleans().write(0, onGround);
        }

        safeSend(p);
    }

    private void lookOnly(float yaw, float pitch, boolean onGround) {
        PacketContainer p = pm.createPacket(PacketType.Play.Server.ENTITY_LOOK);
        writeSafe(p.getIntegers(), 0, entityId);

        if (p.getBytes() != null && p.getBytes().size() >= 2) {
            p.getBytes().write(0, angleToByte(yaw));
            p.getBytes().write(1, angleToByte(pitch));
        } else if (p.getFloat() != null && p.getFloat().size() >= 2) {
            p.getFloat().write(0, yaw);
            p.getFloat().write(1, pitch);
        }

        if (p.getBooleans() != null && p.getBooleans().size() >= 1) {
            p.getBooleans().write(0, onGround);
        }

        safeSend(p);
    }
    private void teleportAbsolute(Location loc) {
        PacketContainer tp = pm.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);

        writeSafe(tp.getIntegers(), 0, entityId);

        boolean wrotePos = false;

        // Old layout: 3 doubles
        StructureModifier<Double> doubles = tp.getDoubles();
        if (doubles != null && doubles.size() >= 3) {
            doubles.write(0, loc.getX());
            doubles.write(1, loc.getY());
            doubles.write(2, loc.getZ());
            wrotePos = true;
        }

        // New layout (1.21+): Vec3 field
        if (!wrotePos) {
            wrotePos = writeVec3Position(tp, loc.getX(), loc.getY(), loc.getZ());
        }

        if (!wrotePos) {
            plugin.getLogger().warning("Replay teleportAbsolute skipped: could not write position for ENTITY_TELEPORT.");
            return;
        }

        // Rotation
        StructureModifier<Byte> bytes = tp.getBytes();
        if (bytes != null && bytes.size() >= 2) {
            bytes.write(0, angleToByte(loc.getYaw()));
            bytes.write(1, angleToByte(loc.getPitch()));
        } else {
            StructureModifier<Float> floats = tp.getFloat();
            if (floats != null && floats.size() >= 2) {
                floats.write(0, loc.getYaw());
                floats.write(1, loc.getPitch());
            }
        }

        // onGround optional
        StructureModifier<Boolean> bools = tp.getBooleans();
        if (bools != null && bools.size() >= 1) {
            bools.write(0, false);
        }

        safeSend(tp);

        // yaw only (nice to have)
        sendHeadRotation(loc);
    }

    private boolean writeVec3Position(PacketContainer packet, double x, double y, double z) {
        try {
            Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            Object vec3 = vec3Class.getConstructor(double.class, double.class, double.class)
                    .newInstance(x, y, z);

            @SuppressWarnings("unchecked")
            StructureModifier<Object> vec3Mod = (StructureModifier<Object>) packet.getModifier().withType(vec3Class);

            return vec3Mod != null && vec3Mod.size() >= 1 && (vec3Mod.write(0, vec3) == null || true);
        } catch (Throwable t) {
            if (plugin.getConfig().getBoolean("debug")) t.printStackTrace();
            return false;
        }
    }

    private static <T> void writeSafe(StructureModifier<T> mod, int index, T value) {
        if (mod != null && mod.size() > index) mod.write(index, value);
    }
    public void swingOffHand() {
        PacketContainer anim = pm.createPacket(PacketType.Play.Server.ANIMATION);
        writeSafe(anim.getIntegers(), 0, entityId);

        // Most builds: animation id is int at index 1
        if (anim.getIntegers() != null && anim.getIntegers().size() >= 2) {
            anim.getIntegers().write(1, 1); // 0 = swing main hand
        } else if (anim.getBytes() != null && anim.getBytes().size() >= 1) {
            anim.getBytes().write(0, (byte) 1);
        }
    }
    public void swingMainHand() {
        PacketContainer anim = pm.createPacket(PacketType.Play.Server.ANIMATION);
        writeSafe(anim.getIntegers(), 0, entityId);

        // Most builds: animation id is int at index 1
        if (anim.getIntegers() != null && anim.getIntegers().size() >= 2) {
            anim.getIntegers().write(1, 0); // 0 = swing main hand
        } else if (anim.getBytes() != null && anim.getBytes().size() >= 1) {
            anim.getBytes().write(0, (byte) 0);
        }

        safeSend(anim);
    }

    public void setMainHand(Material mat) {
        if (mat == null) mat = Material.AIR;
        if (mat == currentMainHand) return; // avoid spam
        currentMainHand = mat;

        PacketContainer eq = pm.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        writeSafe(eq.getIntegers(), 0, entityId);

        ItemStack stack = (mat == Material.AIR) ? null : new ItemStack(mat);

        // Modern: list of (slot, item)
        List<Pair<EnumWrappers.ItemSlot, ItemStack>> pairs =
                Collections.singletonList(new Pair<>(EnumWrappers.ItemSlot.MAINHAND, stack));

        // ProtocolLib accessor for equipment pairs
        eq.getSlotStackPairLists().write(0, pairs);

        safeSend(eq);
    }

    private void sendPlayerInfoAdd() {
        PacketContainer packet = pm.createPacket(com.comphenix.protocol.PacketType.Play.Server.PLAYER_INFO);

        // 1.19.3+: actions are EnumSet
        packet.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));

        PlayerInfoData data = new PlayerInfoData(
                profile,
                0,
                EnumWrappers.NativeGameMode.SURVIVAL,
                WrappedChatComponent.fromText(name)
        );

        // CRITICAL FIX:
        // On modern versions, PlayerInfoDataLists must be written at INDEX 1 (not 0),
        // otherwise you can crash encoding or write into the wrong field.
        packet.getPlayerInfoDataLists().write(1, Collections.singletonList(data));

        safeSend(packet);
    }

    private void sendPlayerInfoRemove() {
        try {
            PacketContainer packet = pm.createPacket(com.comphenix.protocol.PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, Collections.singletonList(uuid));
            safeSend(packet);
        } catch (Throwable ignored) {
            // If ProtocolLib mapping is weird on a build, we just skip removing from tab.
        }
    }

    private void sendSpawnEntity(Location loc) {
        PacketContainer spawn = pm.createPacket(com.comphenix.protocol.PacketType.Play.Server.SPAWN_ENTITY);

        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, uuid);
        spawn.getEntityTypeModifier().write(0, EntityType.PLAYER);

        spawn.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());

        // yaw/pitch in spawn is bytes
        spawn.getBytes()
                .write(0, angleToByte(loc.getYaw()))
                .write(1, angleToByte(loc.getPitch()));

        // "data" field (usually 0)
        if (spawn.getIntegers().size() > 1) {
            spawn.getIntegers().write(1, 0);
        }

        safeSend(spawn);
    }

    private void sendHeadRotation(Location loc) {
        PacketContainer head = pm.createPacket(com.comphenix.protocol.PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().write(0, entityId);
        head.getBytes().write(0, angleToByte(loc.getYaw()));
        safeSend(head);
    }

    private static byte angleToByte(float degrees) {
        return (byte) Math.floor(degrees * 256.0f / 360.0f);
    }

    private void safeSend(PacketContainer packet) {
        try {
            pm.sendServerPacket(viewer, packet);
        } catch (Exception ex) {
            // Don’t kick the viewer; just log.
            plugin.getLogger().severe("Replay packet send failed: " + ex.getMessage());
            if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
        }
    }
}