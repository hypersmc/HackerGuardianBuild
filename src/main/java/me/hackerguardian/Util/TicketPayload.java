package me.hackerguardian.Util;

import java.io.*;

public final class TicketPayload {
    public static final int VERSION = 1;
    private LinkErrorHandler errorHandler = new LinkErrorHandler();

    public final String ticketId;
    public final String playerUuid;
    public final String playerName;
    public final long issuedAt;
    public final long expiresAt;
    public final String targetServer;
    public final String sigHex;

    public TicketPayload(
            String ticketId,
            String playerUuid,
            String playerName,
            long issuedAt,
            long expiresAt,
            String targetServer,
            String sigHex
    ) {
        this.ticketId = ticketId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.targetServer = targetServer;
        this.sigHex = sigHex;
    }

    public String signingString() {
        return ticketId + "|" + playerUuid + "|" + issuedAt + "|" + expiresAt + "|" + targetServer;
    }

    public byte[] encode() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(VERSION);
            out.writeUTF(ticketId);
            out.writeUTF(playerUuid);
            out.writeUTF(playerName);
            out.writeLong(issuedAt);
            out.writeLong(expiresAt);
            out.writeUTF(targetServer);
            out.writeUTF(sigHex);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            errorHandler.logOnly(LinkErrorCode.HG_E_403_PAYLOAD_ENCODE_FAIL, "", "", null, e);
            throw new RuntimeException("Encode failed", e);
        }
    }

    public static TicketPayload decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int v = in.readInt();
            if (v != VERSION) new LinkErrorHandler().logOnly(LinkErrorCode.HG_E_402_UNSUPPORTED_VERSION, "", "", null, null);
            String ticketId = in.readUTF();
            String playerUuid = in.readUTF();
            String playerName = in.readUTF();
            long issuedAt = in.readLong();
            long expiresAt = in.readLong();
            String targetServer = in.readUTF();
            String sigHex = in.readUTF();
            return new TicketPayload(ticketId, playerUuid, playerName, issuedAt, expiresAt, targetServer, sigHex);
        }
    }
}
