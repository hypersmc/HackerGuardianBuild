package me.hackerguardian.main.detection.ml;

/** Deliberate human/test provenance label for offline supervised training. */
public enum MlCaptureLabel {
    LEGIT,
    CHEAT;

    public static MlCaptureLabel parse(String raw) {
        if (raw == null) return null;
        for (MlCaptureLabel value : values()) {
            if (value.name().equalsIgnoreCase(raw.trim())) return value;
        }
        return null;
    }
}
