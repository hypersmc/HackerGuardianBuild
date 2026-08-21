package me.hackerguardian.Util;

public enum LinkErrorCode {

    // 1xx - server/proxy/internal
    HG_E_101_DB_FAILURE(101, "Link verification temporarily unavailable."),
    HG_E_102_MISSING_SECRET(102, "Link verification not configured."),
    HG_E_103_INTERNAL_ERROR(103, "Internal link verification error."),

    // 2xx - user/session
    HG_E_201_NO_TICKET(201, "No link ticket received."),
    HG_E_202_TICKET_EXPIRED(202, "Link ticket expired."),
    HG_E_203_TIMEOUT(203, "Link verification timed out."),

    // 3xx - security/validation
    HG_E_301_SIGNATURE_INVALID(301, "Invalid link ticket."),
    HG_E_302_UUID_MISMATCH(302, "Invalid link identity."),
    HG_E_303_REPLAY_OR_USED(303, "Link ticket already used."),

    // 4xx - protocol/format
    HG_E_401_PAYLOAD_DECODE_FAIL(401, "Invalid link payload."),
    HG_E_402_UNSUPPORTED_VERSION(402, "Unsupported link payload version."),
    HG_E_403_PAYLOAD_ENCODE_FAIL(403, "Payload encode failed."),;

    private final int numeric;
    private final String publicReason;

    LinkErrorCode(int numeric, String publicReason) {
        this.numeric = numeric;
        this.publicReason = publicReason;
    }

    public String code() {
        return "HG-E-" + numeric;
    }

    public String publicReason() {
        return publicReason;
    }
}