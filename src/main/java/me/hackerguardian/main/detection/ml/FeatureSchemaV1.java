package me.hackerguardian.main.detection.ml;

import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import org.bukkit.GameMode;

import java.util.List;

/**
 * Version 1 of the stable model-input contract.
 *
 * Feature order, units and clamps are part of the schema. Changing any of
 * these semantics requires a new schema id instead of silently reusing v1.
 */
public final class FeatureSchemaV1 {

    public static final String ID = "behavior-v1";

    private static final List<String> FEATURE_NAMES = List.of(
            "movement_samples_per_second",
            "average_horizontal_speed",
            "max_horizontal_speed",
            "max_horizontal_delta",
            "average_yaw_delta",
            "yaw_delta_std",
            "average_pitch_delta",
            "pitch_delta_std",
            "ground_ratio",
            "swing_cps",
            "hit_rate",
            "hits_per_second",
            "average_hit_distance",
            "max_hit_distance",
            "blocks_broken_per_second",
            "max_break_distance",
            "blocks_placed_per_second",
            "max_place_distance",
            "ping_ms",
            "tps",
            "sprinting",
            "sneaking",
            "in_water",
            "on_ladder",
            "speed_effect",
            "jump_boost_effect",
            "flying",
            "gliding",
            "in_vehicle",
            "game_mode_survival",
            "game_mode_adventure",
            "game_mode_creative",
            "game_mode_spectator"
    );

    public static final int FEATURE_COUNT = FEATURE_NAMES.size();

    private FeatureSchemaV1() {}

    public static List<String> featureNames() {
        return FEATURE_NAMES;
    }

    public static String featureNamesCsv() {
        return String.join(",", FEATURE_NAMES);
    }

    public static FeatureVector extract(BehaviorSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");

        double seconds = Math.max(0.001, snapshot.getWindowMs() / 1000.0);
        GameMode gameMode = snapshot.getGameMode() == null ? GameMode.SURVIVAL : snapshot.getGameMode();

        double[] values = new double[] {
                clamp(snapshot.getMovementSamples() / seconds, 0.0, 100.0),
                clamp(snapshot.getAverageHorizontalSpeed(), 0.0, 20.0),
                clamp(snapshot.getMaxHorizontalSpeed(), 0.0, 50.0),
                clamp(snapshot.getMaxHorizontalDelta(), 0.0, 10.0),
                clamp(snapshot.getAverageYawDelta(), 0.0, 180.0),
                clamp(snapshot.getYawDeltaStd(), 0.0, 180.0),
                clamp(snapshot.getAveragePitchDelta(), 0.0, 180.0),
                clamp(snapshot.getPitchDeltaStd(), 0.0, 180.0),
                clamp(snapshot.getGroundRatio(), 0.0, 1.0),
                clamp(snapshot.getSwingCps(), 0.0, 100.0),
                clamp(snapshot.getHitRate(), 0.0, 1.0),
                clamp(snapshot.getHitCount() / seconds, 0.0, 100.0),
                clamp(snapshot.getAverageHitDistance(), 0.0, 20.0),
                clamp(snapshot.getMaxHitDistance(), 0.0, 20.0),
                clamp(snapshot.getBlocksBroken() / seconds, 0.0, 100.0),
                clamp(snapshot.getMaxBreakDistance(), 0.0, 20.0),
                clamp(snapshot.getBlocksPlaced() / seconds, 0.0, 100.0),
                clamp(snapshot.getMaxPlaceDistance(), 0.0, 20.0),
                clamp(snapshot.getPingMs(), 0.0, 5000.0),
                clamp(snapshot.getTps(), 0.0, 20.5),
                bool(snapshot.isSprinting()),
                bool(snapshot.isSneaking()),
                bool(snapshot.isInWater()),
                bool(snapshot.isOnLadder()),
                bool(snapshot.hasSpeedEffect()),
                bool(snapshot.hasJumpBoostEffect()),
                bool(snapshot.isFlying()),
                bool(snapshot.isGliding()),
                bool(snapshot.isInVehicle()),
                bool(gameMode == GameMode.SURVIVAL),
                bool(gameMode == GameMode.ADVENTURE),
                bool(gameMode == GameMode.CREATIVE),
                bool(gameMode == GameMode.SPECTATOR)
        };

        return new FeatureVector(ID, values);
    }

    private static double bool(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(min, Math.min(max, value));
    }
}
