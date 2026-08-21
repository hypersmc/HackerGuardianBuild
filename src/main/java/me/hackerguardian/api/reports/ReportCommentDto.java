package me.hackerguardian.api.reports;

public final class ReportCommentDto {
    public final long id;
    public final long reportId;
    public final String commenterUuid;
    public final String commenterName;
    public final String comment;
    public final long createdAt;

    public ReportCommentDto(long id, long reportId,
                            String commenterUuid, String commenterName,
                            String comment, long createdAt) {
        this.id = id;
        this.reportId = reportId;
        this.commenterUuid = commenterUuid;
        this.commenterName = commenterName;
        this.comment = comment;
        this.createdAt = createdAt;
    }
}