package com.zhixu.mapper;

import com.zhixu.service.Times;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;

/**
 * 账号、身份、会话与偏好数据访问。会话只保存令牌摘要，不保存原始令牌。
 */
@Repository
public class UserMapper {
    private final CoreMapper db;

    public UserMapper(CoreMapper db) {
        this.db = db;
    }

    public long insertUser(Instant now) {
        return db.insert("INSERT INTO users (status, created_at, updated_at) VALUES ('active', ?, ?)",
                Times.time(now), Times.time(now));
    }

    public void insertIdentity(long userId, String provider, String issuer, String subject, String passwordHash,
                               Instant now) {
        db.update("INSERT INTO auth_identities (user_id, provider, issuer, subject, password_hash, "
                        + "verified_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, provider, issuer, subject, passwordHash, Times.time(now), Times.time(now));
    }

    public Map<String, Object> identity(String provider, String issuer, String subject) {
        return db.one("SELECT id, user_id, provider, issuer, subject, password_hash, verified_at, "
                + "created_at FROM auth_identities WHERE provider = ? AND issuer = ? AND subject = ?",
                provider, issuer, subject);
    }

    public Map<String, Object> user(long userId) {
        return db.one("SELECT id, status, last_login_at, created_at, updated_at "
                + "FROM users WHERE id = ?", userId);
    }

    public String phone(long userId) {
        Map<String, Object> row = db.one("SELECT subject FROM auth_identities "
                + "WHERE user_id = ? AND provider = 'phone' ORDER BY id LIMIT 1", userId);
        return row == null ? null : (String) row.get("subject");
    }

    public void insertProfile(long userId, String displayName, Instant now) {
        db.update("INSERT INTO user_profiles (user_id, display_name, updated_at) VALUES (?, ?, ?)",
                userId, displayName, Times.time(now));
    }

    public Map<String, Object> profile(long userId) {
        return db.one("SELECT id, user_id, display_name, alternate_name, occupation, "
                + "current_goal, avatar_object_key, updated_at FROM user_profiles WHERE user_id = ?", userId);
    }

    public int updateProfile(long userId, String displayName, String alternateName, String occupation,
                             String currentGoal, String avatarObjectKey, Instant now) {
        return db.update("UPDATE user_profiles SET display_name = ?, alternate_name = ?, "
                        + "occupation = ?, current_goal = ?, avatar_object_key = ?, updated_at = ? WHERE user_id = ?",
                displayName, alternateName, occupation, currentGoal, avatarObjectKey, Times.time(now), userId);
    }

    public void insertPreferences(long userId) {
        db.update("INSERT INTO user_preferences (user_id) VALUES (?)", userId);
    }

    public Map<String, Object> preferences(long userId) {
        return db.one("SELECT id, user_id, timezone_name, appearance, scheduling_mode, "
                + "focus_block_seconds, active_start, active_end, dnd_enabled, dnd_start, dnd_end, "
                + "reminders_enabled, reminder_lead_seconds, updated_at "
                + "FROM user_preferences WHERE user_id = ?", userId);
    }

    public int updatePreferences(long userId, String timezoneName, String appearance, String schedulingMode,
                                 int focusBlockSeconds, String activeStart, String activeEnd, boolean dndEnabled,
                                 String dndStart, String dndEnd, boolean remindersEnabled, int reminderLeadSeconds,
                                 Instant now) {
        return db.update("UPDATE user_preferences SET timezone_name = ?, appearance = ?, "
                        + "scheduling_mode = ?, focus_block_seconds = ?, active_start = ?, active_end = ?, "
                        + "dnd_enabled = ?, dnd_start = ?, dnd_end = ?, reminders_enabled = ?, "
                        + "reminder_lead_seconds = ?, updated_at = ? WHERE user_id = ?",
                timezoneName, appearance, schedulingMode, focusBlockSeconds, activeStart, activeEnd, dndEnabled,
                dndStart, dndEnd, remindersEnabled, reminderLeadSeconds, Times.time(now), userId);
    }

    // ---------- 会话 ----------

    public void insertSession(long userId, byte[] accessHash, byte[] refreshHash, String deviceName,
                              Instant accessExpiresAt, Instant refreshExpiresAt, Instant now) {
        db.update("INSERT INTO auth_sessions (user_id, access_token_hash, refresh_token_hash, "
                        + "device_name, access_expires_at, refresh_expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, accessHash, refreshHash, deviceName, Times.time(accessExpiresAt),
                Times.time(refreshExpiresAt), Times.time(now));
    }

    public Map<String, Object> sessionByAccess(byte[] accessHash) {
        return db.one("SELECT s.id, s.user_id, s.access_expires_at, s.refresh_expires_at, "
                + "s.revoked_at, u.status AS user_status FROM auth_sessions s "
                + "JOIN users u ON u.id = s.user_id WHERE s.access_token_hash = ?", accessHash);
    }

    public Map<String, Object> sessionByRefresh(byte[] refreshHash) {
        return db.one("SELECT s.id, s.user_id, s.access_expires_at, s.refresh_expires_at, "
                + "s.revoked_at, u.status AS user_status FROM auth_sessions s "
                + "JOIN users u ON u.id = s.user_id WHERE s.refresh_token_hash = ?", refreshHash);
    }

    public int rotateSession(long sessionId, byte[] accessHash, byte[] refreshHash, Instant accessExpiresAt,
                             Instant refreshExpiresAt) {
        return db.update("UPDATE auth_sessions SET access_token_hash = ?, refresh_token_hash = ?, "
                        + "access_expires_at = ?, refresh_expires_at = ? WHERE id = ? AND revoked_at IS NULL",
                accessHash, refreshHash, Times.time(accessExpiresAt), Times.time(refreshExpiresAt), sessionId);
    }

    public int revokeSession(long sessionId, Instant now) {
        return db.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Times.time(now), sessionId);
    }

    public int touchLogin(long userId, Instant now) {
        return db.update("UPDATE users SET last_login_at = ? WHERE id = ?",
                Times.time(now), userId);
    }
}
