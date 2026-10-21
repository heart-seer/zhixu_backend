package com.zhixu.mapper;

import com.zhixu.service.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.io.Reader;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 业务数据访问：任务、步骤、日程、消息、提取草稿与幂等记录。
 * 所有日期时间都以 UTC 的 Timestamp 参数传入，避免依赖数据库函数。
 */
@Repository
public class CoreMapper {
    private final JdbcTemplate db;

    public CoreMapper(JdbcTemplate db) {
        this.db = db;
    }

    public static final RowMapper<Map<String, Object>> MAP = new RowMapper<Map<String, Object>>() {
        @Override
        public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                int type = meta.getColumnType(i);
                Object raw = type == java.sql.Types.TIME || type == java.sql.Types.DATE
                        ? rs.getString(i) : rs.getObject(i);
                row.put(label, convert(raw, meta.getColumnTypeName(i)));
            }
            return row;
        }
    };

    private static Object convert(Object value, String typeName) throws java.sql.SQLException {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant().toString();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toString();
        }
        if (value instanceof LocalDate) {
            return value.toString();
        }
        if (value instanceof LocalTime) {
            return value.toString();
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            long length = clob.length();
            return length == 0 ? "" : clob.getSubString(1, (int) length);
        }
        if (value instanceof byte[]) {
            String type = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
            if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB") || type.contains("JSON")) {
                return new String((byte[]) value, java.nio.charset.StandardCharsets.UTF_8);
            }
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
        return value;
    }

    public long insert(final String sql, final Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        db.update(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(java.sql.Connection connection) throws java.sql.SQLException {
                PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                return ps;
            }
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入后未获得主键");
        }
        return key.longValue();
    }

    public int update(String sql, Object... args) {
        return db.update(sql, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = db.query(sql, MAP, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> many(String sql, Object... args) {
        return db.query(sql, MAP, args);
    }

    public Long platformId(String code) {
        Map<String, Object> row = one("SELECT id FROM source_platforms WHERE code = ?", code);
        if (row == null) {
            return null;
        }
        return ((Number) row.get("id")).longValue();
    }

    // ---------- 任务 ----------

    public long insertTask(long ownerUserId, Long teamId, Long sourceMessageId, long platformId,
                           String title, String description, String categoryCode, String priority,
                           Integer estimateSeconds, Instant dueAt, boolean aiCandidate, Instant now) {
        return insert("INSERT INTO tasks (owner_user_id, team_id, source_message_id, platform_id, title, "
                        + "description, category_code, priority, status, estimate_seconds, due_at, ai_candidate, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?, 1, ?, ?)",
                ownerUserId, teamId, sourceMessageId, platformId, title, description, categoryCode, priority,
                estimateSeconds, Times.time(dueAt), aiCandidate, Times.time(now), Times.time(now));
    }

    public Map<String, Object> taskRow(long userId, long taskId) {
        return one("SELECT id, owner_user_id, team_id, source_message_id, platform_id, title, description, "
                        + "category_code, priority, status, estimate_seconds, due_at, completed_at, ai_candidate, "
                        + "version, deleted_at, created_at, updated_at FROM tasks "
                        + "WHERE id = ? AND owner_user_id = ? AND deleted_at IS NULL",
                taskId, userId);
    }

    public List<Map<String, Object>> tasks(long userId, Instant before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, owner_user_id, team_id, source_message_id, platform_id, "
                + "title, description, category_code, priority, status, estimate_seconds, due_at, completed_at, "
                + "ai_candidate, version, deleted_at, created_at, updated_at FROM tasks "
                + "WHERE owner_user_id = ? AND deleted_at IS NULL");
        List<Object> args = new ArrayList<Object>();
        args.add(userId);
        if (before != null) {
            sql.append(" AND created_at < ?");
            args.add(Times.time(before));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return many(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> steps(long taskId) {
        return many("SELECT id, task_id, title, position, completed_at FROM task_steps "
                + "WHERE task_id = ? ORDER BY position", taskId);
    }

    public long insertStep(long taskId, String title, int position, Instant completedAt) {
        return insert("INSERT INTO task_steps (task_id, title, position, completed_at) VALUES (?, ?, ?, ?)",
                taskId, title, position, Times.time(completedAt));
    }

    public Map<String, Object> stepRow(long taskId, long stepId) {
        return one("SELECT id, task_id, title, position, completed_at FROM task_steps WHERE id = ? AND task_id = ?",
                stepId, taskId);
    }

    public int updateStepTitle(long taskId, long stepId, String title) {
        return update("UPDATE task_steps SET title = ? WHERE id = ? AND task_id = ?", title, stepId, taskId);
    }

    public int updateStepPosition(long taskId, long stepId, int position) {
        return update("UPDATE task_steps SET position = ? WHERE id = ? AND task_id = ?", position, stepId, taskId);
    }

    public int updateStepCompleted(long taskId, long stepId, Instant completedAt) {
        return update("UPDATE task_steps SET completed_at = ? WHERE id = ? AND task_id = ?",
                Times.time(completedAt), stepId, taskId);
    }

    public int deleteStep(long taskId, long stepId) {
        return update("DELETE FROM task_steps WHERE id = ? AND task_id = ?", stepId, taskId);
    }

    public int maxStepPosition(long taskId) {
        Map<String, Object> row = one("SELECT COALESCE(MAX(position), -1) AS max_position FROM task_steps WHERE task_id = ?",
                taskId);
        return row == null ? -1 : ((Number) row.get("max_position")).intValue();
    }

    public int updateTask(long userId, long taskId, long version, String title, String description,
                          String categoryCode, String priority, Integer estimateSeconds, Instant dueAt, Instant now) {
        return update("UPDATE tasks SET title = ?, description = ?, category_code = ?, priority = ?, "
                        + "estimate_seconds = ?, due_at = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                title, description, categoryCode, priority, estimateSeconds, Times.time(dueAt), Times.time(now),
                taskId, userId, version);
    }

    public int completeTask(long userId, long taskId, long version, boolean completed, Instant now) {
        return update("UPDATE tasks SET status = ?, completed_at = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                completed ? "completed" : "open", completed ? Times.time(now) : null, Times.time(now),
                taskId, userId, version);
    }

    public int bumpTask(long userId, long taskId, long version, Instant now) {
        return update("UPDATE tasks SET version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                Times.time(now), taskId, userId, version);
    }

    public int reopenTask(long userId, long taskId, long version, Instant now) {
        return update("UPDATE tasks SET status = 'open', completed_at = NULL, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                Times.time(now), taskId, userId, version);
    }

    public int softDeleteTask(long userId, long taskId, long version, Instant now) {
        return update("UPDATE tasks SET deleted_at = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                Times.time(now), Times.time(now), taskId, userId, version);
    }

    // ---------- 日程 ----------

    public long insertEvent(long ownerUserId, Long teamId, Long taskId, String title, String description,
                            Instant startsAt, Instant endsAt, String timezoneName, String status,
                            Integer reminderLeadSeconds, Instant completedAt, Instant now) {
        return insert("INSERT INTO calendar_events (owner_user_id, team_id, task_id, title, description, "
                        + "starts_at, ends_at, timezone_name, status, completed_at, reminder_lead_seconds, version, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                ownerUserId, teamId, taskId, title, description, Times.time(startsAt), Times.time(endsAt),
                timezoneName, status, Times.time(completedAt), reminderLeadSeconds, Times.time(now), Times.time(now));
    }

    public Map<String, Object> eventRow(long userId, long eventId) {
        return one("SELECT id, owner_user_id, team_id, task_id, title, description, starts_at, ends_at, "
                        + "timezone_name, status, completed_at, reminder_lead_seconds, version, deleted_at, "
                        + "created_at, updated_at FROM calendar_events "
                        + "WHERE id = ? AND owner_user_id = ? AND deleted_at IS NULL",
                eventId, userId);
    }

    public List<Map<String, Object>> events(long userId, Instant start, Instant end, int limit) {
        return many("SELECT id, owner_user_id, team_id, task_id, title, description, starts_at, ends_at, "
                        + "timezone_name, status, completed_at, reminder_lead_seconds, version, deleted_at, "
                        + "created_at, updated_at FROM calendar_events "
                        + "WHERE owner_user_id = ? AND deleted_at IS NULL AND starts_at < ? AND ends_at > ? "
                        + "ORDER BY starts_at, id LIMIT ?",
                userId, Times.time(end), Times.time(start), limit);
    }

    public int updateEvent(long userId, long eventId, long version, String title, String description,
                           Instant startsAt, Instant endsAt, String timezoneName, Integer reminderLeadSeconds,
                           Instant now) {
        return update("UPDATE calendar_events SET title = ?, description = ?, starts_at = ?, ends_at = ?, "
                        + "timezone_name = ?, reminder_lead_seconds = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                title, description, Times.time(startsAt), Times.time(endsAt), timezoneName, reminderLeadSeconds,
                Times.time(now), eventId, userId, version);
    }

    public int completeEvent(long userId, long eventId, long version, String status, Instant completedAt, Instant now) {
        return update("UPDATE calendar_events SET status = ?, completed_at = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                status, Times.time(completedAt), Times.time(now), eventId, userId, version);
    }

    public int completePlannedTaskEvents(long userId, long taskId, Instant now) {
        return update("UPDATE calendar_events SET status = 'completed', completed_at = ?, version = version + 1, "
                        + "updated_at = ? WHERE task_id = ? AND owner_user_id = ? AND status = 'planned' "
                        + "AND deleted_at IS NULL",
                Times.time(now), Times.time(now), taskId, userId);
    }

    public int softDeleteEvent(long userId, long eventId, long version, Instant now) {
        return update("UPDATE calendar_events SET deleted_at = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND owner_user_id = ? AND version = ? AND deleted_at IS NULL",
                Times.time(now), Times.time(now), eventId, userId, version);
    }

    // ---------- 消息 ----------

    public long insertMessage(long userId, long platformId, byte[] ingestKey, String externalMessageId,
                              String senderName, String title, String preview, String body, String kind,
                              Instant receivedAt, Instant now) {
        return insert("INSERT INTO messages (user_id, platform_id, connection_id, ingest_key, external_message_id, "
                        + "sender_name, title, preview, body, kind, status, received_at, created_at) "
                        + "VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
                userId, platformId, ingestKey, externalMessageId, senderName, title, preview, body, kind,
                Times.time(receivedAt), Times.time(now));
    }

    public Map<String, Object> messageRow(long userId, long messageId) {
        return one("SELECT m.id, m.user_id, m.platform_id, p.code AS platform_code, m.external_message_id, "
                        + "m.sender_name, m.title, m.preview, m.body, m.kind, m.status, m.received_at, m.read_at, "
                        + "m.created_at FROM messages m JOIN source_platforms p ON p.id = m.platform_id "
                        + "WHERE m.id = ? AND m.user_id = ? AND m.deleted_at IS NULL",
                messageId, userId);
    }

    public Map<String, Object> messageByIngest(long userId, byte[] ingestKey) {
        return one("SELECT m.id, m.user_id, m.platform_id, p.code AS platform_code, m.external_message_id, "
                        + "m.sender_name, m.title, m.preview, m.body, m.kind, m.status, m.received_at, m.read_at, "
                        + "m.created_at FROM messages m JOIN source_platforms p ON p.id = m.platform_id "
                        + "WHERE m.user_id = ? AND m.ingest_key = ? AND m.deleted_at IS NULL",
                userId, ingestKey);
    }

    public List<Map<String, Object>> messages(long userId, Instant before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT m.id, m.user_id, m.platform_id, p.code AS platform_code, "
                + "m.external_message_id, m.sender_name, m.title, m.preview, m.body, m.kind, m.status, "
                + "m.received_at, m.read_at, m.created_at FROM messages m "
                + "JOIN source_platforms p ON p.id = m.platform_id "
                + "WHERE m.user_id = ? AND m.deleted_at IS NULL");
        List<Object> args = new ArrayList<Object>();
        args.add(userId);
        if (before != null) {
            sql.append(" AND m.received_at < ?");
            args.add(Times.time(before));
        }
        sql.append(" ORDER BY m.received_at DESC, m.id DESC LIMIT ?");
        args.add(limit);
        return many(sql.toString(), args.toArray());
    }

    public int markRead(long userId, long messageId, Instant now) {
        return update("UPDATE messages SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL",
                Times.time(now), messageId, userId);
    }

    public int markProcessed(long userId, long messageId, Instant now) {
        return update("UPDATE messages SET status = 'processed' WHERE id = ? AND user_id = ?",
                messageId, userId);
    }

    public List<Map<String, Object>> drafts(long messageId) {
        return many("SELECT id, message_id, item_key, target_kind, title, description, proposed_due_at, "
                        + "proposed_start_at, proposed_end_at, proposed_priority, proposed_estimate_seconds, "
                        + "timezone_name, extractor_version, status, revision, created_at, updated_at "
                        + "FROM extraction_drafts WHERE message_id = ? ORDER BY id",
                messageId);
    }

    public Map<String, Object> draftByItem(long messageId, String itemKey) {
        return one("SELECT id, message_id, item_key, target_kind, title, description, proposed_due_at, "
                        + "proposed_start_at, proposed_end_at, proposed_priority, proposed_estimate_seconds, "
                        + "timezone_name, extractor_version, status, revision, created_at, updated_at "
                        + "FROM extraction_drafts WHERE message_id = ? AND item_key = ?",
                messageId, itemKey);
    }

    public Map<String, Object> draftRow(long draftId) {
        return one("SELECT d.id, d.message_id, m.user_id, m.platform_id, m.title AS message_title, "
                        + "d.item_key, d.target_kind, d.title, d.description, d.proposed_due_at, "
                        + "d.proposed_start_at, d.proposed_end_at, d.proposed_priority, "
                        + "d.proposed_estimate_seconds, d.timezone_name, d.extractor_version, d.status, d.revision "
                        + "FROM extraction_drafts d JOIN messages m ON m.id = d.message_id WHERE d.id = ?",
                draftId);
    }

    public long insertDraft(long messageId, String itemKey, String targetKind, String title, String description,
                            Instant dueAt, Instant startsAt, Instant endsAt, String priority, Integer estimateSeconds,
                            String timezoneName, String extractorVersion, Instant now) {
        return insert("INSERT INTO extraction_drafts (message_id, item_key, target_kind, title, description, "
                        + "proposed_due_at, proposed_start_at, proposed_end_at, proposed_priority, "
                        + "proposed_estimate_seconds, timezone_name, evidence, extractor_version, status, revision, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 'pending', 1, ?, ?)",
                messageId, itemKey, targetKind, title, description, Times.time(dueAt), Times.time(startsAt),
                Times.time(endsAt), priority, estimateSeconds, timezoneName, extractorVersion,
                Times.time(now), Times.time(now));
    }

    public Map<String, Object> confirmation(long draftId) {
        return one("SELECT id, draft_id, confirmed_by_user_id, task_id, event_id, confirmed_at "
                + "FROM extraction_confirmations WHERE draft_id = ?", draftId);
    }

    public long insertConfirmation(long draftId, long userId, Long taskId, Long eventId, Instant now) {
        return insert("INSERT INTO extraction_confirmations (draft_id, confirmed_by_user_id, task_id, event_id, "
                        + "confirmed_at) VALUES (?, ?, ?, ?, ?)",
                draftId, userId, taskId, eventId, Times.time(now));
    }

    public int confirmDraft(long draftId, Instant now) {
        return update("UPDATE extraction_drafts SET status = 'confirmed', revision = revision + 1, updated_at = ? "
                + "WHERE id = ?", Times.time(now), draftId);
    }

    // ---------- 幂等 ----------

    public long insertIdempotency(long userId, String operation, String requestKey, byte[] requestHash,
                                  Instant createdAt, Instant expiresAt) {
        return insert("INSERT INTO api_idempotency_requests (user_id, operation, request_key, request_hash, "
                        + "status, expires_at, created_at) VALUES (?, ?, ?, ?, 'pending', ?, ?)",
                userId, operation, requestKey, requestHash, Times.time(expiresAt), Times.time(createdAt));
    }

    public Map<String, Object> idempotency(long userId, String operation, String requestKey) {
        return one("SELECT id, user_id, operation, request_key, request_hash, status, result_task_id, "
                        + "result_event_id, expires_at, created_at FROM api_idempotency_requests "
                        + "WHERE user_id = ? AND operation = ? AND request_key = ?",
                userId, operation, requestKey);
    }

    public int finishIdempotency(long id, Long taskId, Long eventId) {
        return update("UPDATE api_idempotency_requests SET status = 'completed', result_task_id = ?, "
                + "result_event_id = ? WHERE id = ?", taskId, eventId, id);
    }
}
