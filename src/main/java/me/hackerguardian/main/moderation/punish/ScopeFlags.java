package me.hackerguardian.main.moderation.punish;

import java.util.ArrayList;
import java.util.List;

public final class ScopeFlags {
    public final PunishScope scope;

    private ScopeFlags(PunishScope scope) {
        this.scope = scope;
    }

    public static Parsed parse(String[] args, boolean behindProxy) {
        // If NOT behind proxy: ignore these flags entirely (strip them so parsing stays identical)
        if (!behindProxy) {
            List<String> kept = new ArrayList<>(args.length);
            for (String a : args) {
                if (a == null) continue;
                String lc = a.toLowerCase();
                if (lc.equals("-srv") || lc.equals("-wid")) continue;
                kept.add(a);
            }
            return new Parsed(new ScopeFlags(PunishScope.SERVER), kept.toArray(new String[0]));
        }

        // behindProxy=true: flags actually mean something
        PunishScope scope = PunishScope.SERVER; // default safer
        List<String> kept = new ArrayList<>(args.length);

        for (String a : args) {
            if (a == null) continue;
            String lc = a.toLowerCase();

            if (lc.equals("-srv")) { scope = PunishScope.SERVER; continue; }
            if (lc.equals("-wid")) { scope = PunishScope.WIDE;   continue; }

            kept.add(a);
        }

        return new Parsed(new ScopeFlags(scope), kept.toArray(new String[0]));
    }

    public record Parsed(ScopeFlags scopeFlags, String[] args) {}
}
