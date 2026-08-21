package me.hackerguardian.api.reports;

public final class ReportDto {
    public final long id;
    public final String reportedUuid;
    public final String reportedName;
    public final String reporterUuid;
    public final String reporterName;
    public final String reason;
    public final String status;
    public final long createdAt;
    public final long updatedAt;
    public final String resolvedByUuid;
    public final Long resolvedAt;

    public ReportDto(long id,
                     String reportedUuid, String reportedName,
                     String reporterUuid, String reporterName,
                     String reason,
                     String status,
                     long createdAt, long updatedAt,
                     String resolvedByUuid,
                     Long resolvedAt) {
        this.id = id;
        this.reportedUuid = reportedUuid;
        this.reportedName = reportedName;
        this.reporterUuid = reporterUuid;
        this.reporterName = reporterName;
        this.reason = reason;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resolvedByUuid = resolvedByUuid;
        this.resolvedAt = resolvedAt;
    }
}
