package me.hackerguardian.bungee.moderation;

public final class BanRow {
    public final long id;
    public final String reason;
    public final Long expiresAt;

    public BanRow(long id, String reason, Long expiresAt) {
        this.id = id;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }
}
