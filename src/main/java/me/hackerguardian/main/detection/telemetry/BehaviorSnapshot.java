package me.hackerguardian.main.detection.telemetry;

import org.bukkit.GameMode;

import java.util.UUID;

/**
 * Immutable, typed view of recent player behaviour.
 *
 * Detection v2 deliberately keeps telemetry separate from model input vectors.
 * A future ML adapter can transform this snapshot into its own versioned feature
 * schema without coupling event collection to a specific neural network.
 */
public final class BehaviorSnapshot {

    private final UUID playerId;
    private final String playerName;
    private final String worldName;
    private final long capturedAtMs;
    private final long windowMs;

    private final int movementSamples;
    private final double averageHorizontalSpeed;
    private final double maxHorizontalSpeed;
    private final double maxHorizontalDelta;
    private final double averageYawDelta;
    private final double yawDeltaStd;
    private final double averagePitchDelta;
    private final double pitchDeltaStd;
    private final double groundRatio;

    private final int swingCount;
    private final double swingCps;
    private final int hitCount;
    private final double hitRate;
    private final double averageHitDistance;
    private final double maxHitDistance;

    private final int blocksBroken;
    private final double maxBreakDistance;
    private final int blocksPlaced;
    private final double maxPlaceDistance;

    private final int pingMs;
    private final double tps;
    private final GameMode gameMode;
    private final boolean sprinting;
    private final boolean sneaking;
    private final boolean inWater;
    private final boolean onLadder;
    private final boolean speedEffect;
    private final boolean jumpBoostEffect;
    private final boolean flying;
    private final boolean gliding;
    private final boolean inVehicle;

    private BehaviorSnapshot(Builder b) {
        this.playerId = b.playerId;
        this.playerName = b.playerName;
        this.worldName = b.worldName;
        this.capturedAtMs = b.capturedAtMs;
        this.windowMs = b.windowMs;
        this.movementSamples = b.movementSamples;
        this.averageHorizontalSpeed = b.averageHorizontalSpeed;
        this.maxHorizontalSpeed = b.maxHorizontalSpeed;
        this.maxHorizontalDelta = b.maxHorizontalDelta;
        this.averageYawDelta = b.averageYawDelta;
        this.yawDeltaStd = b.yawDeltaStd;
        this.averagePitchDelta = b.averagePitchDelta;
        this.pitchDeltaStd = b.pitchDeltaStd;
        this.groundRatio = b.groundRatio;
        this.swingCount = b.swingCount;
        this.swingCps = b.swingCps;
        this.hitCount = b.hitCount;
        this.hitRate = b.hitRate;
        this.averageHitDistance = b.averageHitDistance;
        this.maxHitDistance = b.maxHitDistance;
        this.blocksBroken = b.blocksBroken;
        this.maxBreakDistance = b.maxBreakDistance;
        this.blocksPlaced = b.blocksPlaced;
        this.maxPlaceDistance = b.maxPlaceDistance;
        this.pingMs = b.pingMs;
        this.tps = b.tps;
        this.gameMode = b.gameMode;
        this.sprinting = b.sprinting;
        this.sneaking = b.sneaking;
        this.inWater = b.inWater;
        this.onLadder = b.onLadder;
        this.speedEffect = b.speedEffect;
        this.jumpBoostEffect = b.jumpBoostEffect;
        this.flying = b.flying;
        this.gliding = b.gliding;
        this.inVehicle = b.inVehicle;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getWorldName() { return worldName; }
    public long getCapturedAtMs() { return capturedAtMs; }
    public long getWindowMs() { return windowMs; }
    public int getMovementSamples() { return movementSamples; }
    public double getAverageHorizontalSpeed() { return averageHorizontalSpeed; }
    public double getMaxHorizontalSpeed() { return maxHorizontalSpeed; }
    public double getMaxHorizontalDelta() { return maxHorizontalDelta; }
    public double getAverageYawDelta() { return averageYawDelta; }
    public double getYawDeltaStd() { return yawDeltaStd; }
    public double getAveragePitchDelta() { return averagePitchDelta; }
    public double getPitchDeltaStd() { return pitchDeltaStd; }
    public double getGroundRatio() { return groundRatio; }
    public int getSwingCount() { return swingCount; }
    public double getSwingCps() { return swingCps; }
    public int getHitCount() { return hitCount; }
    public double getHitRate() { return hitRate; }
    public double getAverageHitDistance() { return averageHitDistance; }
    public double getMaxHitDistance() { return maxHitDistance; }
    public int getBlocksBroken() { return blocksBroken; }
    public double getMaxBreakDistance() { return maxBreakDistance; }
    public int getBlocksPlaced() { return blocksPlaced; }
    public double getMaxPlaceDistance() { return maxPlaceDistance; }
    public int getPingMs() { return pingMs; }
    public double getTps() { return tps; }
    public GameMode getGameMode() { return gameMode; }
    public boolean isSprinting() { return sprinting; }
    public boolean isSneaking() { return sneaking; }
    public boolean isInWater() { return inWater; }
    public boolean isOnLadder() { return onLadder; }
    public boolean hasSpeedEffect() { return speedEffect; }
    public boolean hasJumpBoostEffect() { return jumpBoostEffect; }
    public boolean isFlying() { return flying; }
    public boolean isGliding() { return gliding; }
    public boolean isInVehicle() { return inVehicle; }

    public static Builder builder(UUID playerId, String playerName, long capturedAtMs, long windowMs) {
        return new Builder(playerId, playerName, capturedAtMs, windowMs);
    }

    public static final class Builder {
        private final UUID playerId;
        private final String playerName;
        private final long capturedAtMs;
        private final long windowMs;

        private String worldName = "";
        private int movementSamples;
        private double averageHorizontalSpeed;
        private double maxHorizontalSpeed;
        private double maxHorizontalDelta;
        private double averageYawDelta;
        private double yawDeltaStd;
        private double averagePitchDelta;
        private double pitchDeltaStd;
        private double groundRatio;
        private int swingCount;
        private double swingCps;
        private int hitCount;
        private double hitRate;
        private double averageHitDistance;
        private double maxHitDistance;
        private int blocksBroken;
        private double maxBreakDistance;
        private int blocksPlaced;
        private double maxPlaceDistance;
        private int pingMs;
        private double tps;
        private GameMode gameMode = GameMode.SURVIVAL;
        private boolean sprinting;
        private boolean sneaking;
        private boolean inWater;
        private boolean onLadder;
        private boolean speedEffect;
        private boolean jumpBoostEffect;
        private boolean flying;
        private boolean gliding;
        private boolean inVehicle;

        private Builder(UUID playerId, String playerName, long capturedAtMs, long windowMs) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.capturedAtMs = capturedAtMs;
            this.windowMs = windowMs;
        }

        public Builder worldName(String value) { this.worldName = value; return this; }
        public Builder movement(int samples, double avgSpeed, double maxSpeed, double maxDelta,
                                double avgYaw, double yawStd, double avgPitch, double pitchStd,
                                double groundRatio) {
            this.movementSamples = samples;
            this.averageHorizontalSpeed = avgSpeed;
            this.maxHorizontalSpeed = maxSpeed;
            this.maxHorizontalDelta = maxDelta;
            this.averageYawDelta = avgYaw;
            this.yawDeltaStd = yawStd;
            this.averagePitchDelta = avgPitch;
            this.pitchDeltaStd = pitchStd;
            this.groundRatio = groundRatio;
            return this;
        }

        public Builder combat(int swings, double cps, int hits, double hitRate,
                              double avgDistance, double maxDistance) {
            this.swingCount = swings;
            this.swingCps = cps;
            this.hitCount = hits;
            this.hitRate = hitRate;
            this.averageHitDistance = avgDistance;
            this.maxHitDistance = maxDistance;
            return this;
        }

        public Builder blocks(int broken, double maxBreakDistance, int placed, double maxPlaceDistance) {
            this.blocksBroken = broken;
            this.maxBreakDistance = maxBreakDistance;
            this.blocksPlaced = placed;
            this.maxPlaceDistance = maxPlaceDistance;
            return this;
        }

        public Builder context(int pingMs, double tps, GameMode gameMode,
                               boolean sprinting, boolean sneaking, boolean inWater,
                               boolean onLadder, boolean speedEffect, boolean jumpBoostEffect,
                               boolean flying, boolean gliding, boolean inVehicle) {
            this.pingMs = pingMs;
            this.tps = tps;
            this.gameMode = gameMode == null ? GameMode.SURVIVAL : gameMode;
            this.sprinting = sprinting;
            this.sneaking = sneaking;
            this.inWater = inWater;
            this.onLadder = onLadder;
            this.speedEffect = speedEffect;
            this.jumpBoostEffect = jumpBoostEffect;
            this.flying = flying;
            this.gliding = gliding;
            this.inVehicle = inVehicle;
            return this;
        }

        public BehaviorSnapshot build() {
            return new BehaviorSnapshot(this);
        }
    }
}
