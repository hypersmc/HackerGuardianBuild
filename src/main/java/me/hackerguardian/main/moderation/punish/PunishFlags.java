package me.hackerguardian.main.moderation.punish;

import java.util.ArrayList;
import java.util.List;

public final class PunishFlags {
    public final boolean silent; // -s
    public final boolean publicBroadcast; // -p

    public PunishFlags(boolean silent, boolean publicBroadcast) {
        this.silent = silent;
        this.publicBroadcast = publicBroadcast;
    }

    public static Parsed parse(String[] args) {
        boolean silent = false;
        boolean pub = false;

        List<String> kept = new ArrayList<>(args.length);
        for (String a : args) {
            if (a == null) continue;
            String lc = a.toLowerCase();

            if (lc.equals("-s")) {
                silent = true;
                continue;
            }
            if (lc.equals("-p")) {
                pub = true;
                continue;
            }
            kept.add(a);
        }

        // If both were provided, let -p win.
        if (pub) silent = false;

        return new Parsed(new PunishFlags(silent, pub), kept.toArray(new String[0]));
    }

    public record Parsed(PunishFlags flags, String[] args) {}
}