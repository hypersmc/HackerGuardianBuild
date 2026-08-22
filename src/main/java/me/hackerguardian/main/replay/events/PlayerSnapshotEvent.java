package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class PlayerSnapshotEvent implements ReplayEvent {
    private final String world;
    private final double x, y, z;
    private final float yaw, pitch;
    private final boolean onGround;
    private final boolean sneaking;
    private final boolean sprinting;
    private final String heldItem;

    public PlayerSnapshotEvent(String world, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this(world, x, y, z, yaw, pitch, onGround, false, false, "AIR");
    }

    public PlayerSnapshotEvent(String world, double x, double y, double z, float yaw, float pitch,
                               boolean onGround, boolean sneaking, boolean sprinting, String heldItem) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        this.onGround = onGround;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
        this.heldItem = heldItem == null || heldItem.isBlank() ? "AIR" : heldItem;
    }

    public static PlayerSnapshotEvent from(Player player) {
        Location location = player.getLocation();
        String world = location.getWorld() != null ? location.getWorld().getName() : "world";
        boolean onGround = player.isOnGround();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String held = hand == null || hand.getType() == Material.AIR ? "AIR" : hand.getType().name();
        return new PlayerSnapshotEvent(
                world,
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                onGround, player.isSneaking(), player.isSprinting(), held
        );
    }

    @Override public ReplayEventType type() { return ReplayEventType.PLAYER_SNAPSHOT; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(world, 128);
        out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
        out.writeFloat(yaw); out.writeFloat(pitch);
        out.writeBoolean(onGround);

        // Appended fields keep old recordings readable: older PLAYER_SNAPSHOT
        // payloads simply end after onGround and web decoders treat these as optional.
        out.writeBoolean(sneaking);
        out.writeBoolean(sprinting);
        out.writeString(heldItem, 64);
    }
}
