package me.hackerguardian.api.moderation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only projection over the moderation audit log for the web API. */
public final class ModerationApiRepository {

    private final DataSource dataSource;

    public ModerationApiRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Page list(Filters filters) throws SQLException {
        Filters f = filters == null ? new Filters() : filters;
        int page = Math.max(1, f.page);
        int perPage = Math.max(1, Math.min(100, f.perPage));
        int offset = (page - 1) * perPage;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (notBlank(f.targetUuid)) {
            where.append(" AND l.target_uuid=?");
            params.add(f.targetUuid.trim());
        }
        if (notBlank(f.type)) {
            where.append(" AND l.action=?");
            params.add(f.type.trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(f.server)) {
            where.append(" AND l.scope_server=?");
            params.add(f.server.trim());
        }
        if (f.fromMs != null) {
            where.append(" AND l.created_at>=?");
            params.add(f.fromMs);
        }
        if (f.toMs != null) {
            where.append(" AND l.created_at<=?");
            params.add(f.toMs);
        }

        long total;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM hg_moderation_log l" + where)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                total = result.getLong(1);
            }
        }

        String sql = "SELECT l.id, l.action, l.target_uuid, l.target_name, l.actor_uuid, l.actor_name, l.reason, "
                + "l.created_at, l.expires_at, l.related_id, l.scope, l.scope_server, "
                + "p.active AS punishment_active, ib.active AS ip_ban_active "
                + "FROM hg_moderation_log l "
                + "LEFT JOIN hg_punishments p ON p.id=l.related_id AND l.action IN ('BAN','MUTE') "
                + "LEFT JOIN hg_ip_bans ib ON ib.id=l.related_id AND l.action='IP_BAN'"
                + where + " ORDER BY l.created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(perPage);
        pageParams.add(offset);

        List<Action> actions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, pageParams);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) actions.add(map(result));
            }
        }
        return new Page(page, perPage, total, actions);
    }

    private static Action map(ResultSet result) throws SQLException {
        Long expiresAt = nullableLong(result, "expires_at");
        Long relatedId = nullableLong(result, "related_id");
        String action = result.getString("action");
        Boolean active = null;
        if ("BAN".equals(action) || "MUTE".equals(action)) {
            Object value = result.getObject("punishment_active");
            if (value != null) active = result.getBoolean("punishment_active");
        } else if ("IP_BAN".equals(action)) {
            Object value = result.getObject("ip_ban_active");
            if (value != null) active = result.getBoolean("ip_ban_active");
        } else if (action != null) {
            active = false;
        }

        return new Action(
                result.getLong("id"),
                result.getString("scope_server"),
                result.getString("target_uuid"),
                result.getString("target_name"),
                action,
                result.getString("reason"),
                result.getString("actor_uuid"),
                result.getString("actor_name"),
                result.getLong("created_at"),
                expiresAt,
                active,
                result.getString("scope"),
                relatedId
        );
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null : result.getLong(column);
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            int index = i + 1;
            if (value instanceof Integer integer) statement.setInt(index, integer);
            else if (value instanceof Long number) statement.setLong(index, number);
            else statement.setString(index, String.valueOf(value));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static final class Filters {
        public String targetUuid;
        public String type;
        public String server;
        public Long fromMs;
        public Long toMs;
        public int page = 1;
        public int perPage = 25;
    }

    public record Page(int page, int perPage, long total, List<Action> actions) {
        public long pages() { return perPage <= 0 ? 1 : Math.max(1L, (total + perPage - 1L) / perPage); }
    }

    public record Action(
            long id,
            String serverName,
            String targetUuid,
            String targetName,
            String type,
            String reason,
            String actorUuid,
            String actorName,
            long createdAt,
            Long expiresAt,
            Boolean active,
            String scope,
            Long relatedId
    ) {}
}
