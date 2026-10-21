package com.zhixu.service;

import com.zhixu.mapper.UserMapper;
import com.zhixu.model.dto.Requests;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserService {
    private static final long ACCESS_SECONDS = 60L * 60;
    private static final long REFRESH_SECONDS = 30L * 24 * 60 * 60;

    private final UserMapper mapper;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> register(Requests.Credentials input) {
        String phone = input.getPhone() == null ? "" : input.getPhone().trim();
        if (phone.length() < 5 || phone.length() > 20) {
            throw ApiException.bad("手机号格式不正确");
        }
        String password = input.getPassword() == null ? "" : input.getPassword();
        if (password.length() < 6 || password.length() > 72) {
            throw ApiException.bad("密码长度需在 6 到 72 位之间");
        }
        if (mapper.identity("phone", "local", phone) != null) {
            throw ApiException.conflict("该手机号已注册");
        }
        Instant now = Instant.now();
        long userId = mapper.insertUser(now);
        try {
            mapper.insertIdentity(userId, "phone", "local", phone, encoder.encode(password), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该手机号已注册");
        }
        mapper.insertProfile(userId, phone, now);
        mapper.insertPreferences(userId);
        mapper.touchLogin(userId, now);
        return issue(userId);
    }

    @Transactional
    public Map<String, Object> login(Requests.Credentials input) {
        String phone = input.getPhone() == null ? "" : input.getPhone().trim();
        Map<String, Object> identity = mapper.identity("phone", "local", phone);
        if (identity == null) {
            throw ApiException.unauthorized("手机号或密码不正确");
        }
        String hash = (String) identity.get("password_hash");
        if (hash == null || !encoder.matches(input.getPassword(), hash)) {
            throw ApiException.unauthorized("手机号或密码不正确");
        }
        long userId = ((Number) identity.get("user_id")).longValue();
        Map<String, Object> user = mapper.user(userId);
        if (user == null) {
            throw ApiException.unauthorized("账号不存在");
        }
        if (!"active".equals(user.get("status"))) {
            throw ApiException.forbidden("账号已被停用");
        }
        mapper.touchLogin(userId, Instant.now());
        return issue(userId);
    }

    @Transactional
    public Map<String, Object> refresh(Requests.RefreshInput input) {
        String token = input.getRefreshToken() == null ? "" : input.getRefreshToken().trim();
        if (token.isEmpty()) {
            throw ApiException.bad("刷新令牌不能为空");
        }
        Map<String, Object> session = mapper.sessionByRefresh(Hashing.sha256(token));
        if (session == null) {
            throw ApiException.unauthorized("登录状态已失效");
        }
        Instant refreshExpires = instant(session.get("refresh_expires_at"));
        if (session.get("revoked_at") != null || refreshExpires == null || !refreshExpires.isAfter(Instant.now())) {
            throw ApiException.unauthorized("登录状态已失效");
        }
        if (!"active".equals(session.get("user_status"))) {
            throw ApiException.forbidden("账号已被停用");
        }
        long sessionId = ((Number) session.get("id")).longValue();
        long userId = ((Number) session.get("user_id")).longValue();

        Instant now = Instant.now();
        Instant accessExpires = now.plusSeconds(ACCESS_SECONDS);
        Instant newRefreshExpires = now.plusSeconds(REFRESH_SECONDS);
        String accessToken = Hashing.token();
        String refreshToken = Hashing.token();
        int rotated = mapper.rotateSession(sessionId, Hashing.sha256(accessToken), Hashing.sha256(refreshToken),
                accessExpires, newRefreshExpires);
        if (rotated == 0) {
            throw ApiException.unauthorized("登录状态已失效");
        }
        return tokenPair(userId, accessToken, refreshToken, accessExpires, newRefreshExpires);
    }

    @Transactional
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            return;
        }
        Map<String, Object> session = mapper.sessionByAccess(Hashing.sha256(accessToken.trim()));
        if (session != null) {
            mapper.revokeSession(((Number) session.get("id")).longValue(), Instant.now());
        }
    }

    public long authenticate(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw ApiException.unauthorized("未登录");
        }
        Map<String, Object> session = mapper.sessionByAccess(Hashing.sha256(accessToken.trim()));
        if (session == null) {
            throw ApiException.unauthorized("登录状态已失效");
        }
        Instant expiresAt = instant(session.get("access_expires_at"));
        if (session.get("revoked_at") != null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw ApiException.unauthorized("登录状态已失效");
        }
        if (!"active".equals(session.get("user_status"))) {
            throw ApiException.forbidden("账号已被停用");
        }
        return ((Number) session.get("user_id")).longValue();
    }

    public Map<String, Object> me(long userId) {
        Map<String, Object> profile = mapper.profile(userId);
        if (profile == null) {
            throw ApiException.notFound("用户资料不存在");
        }
        Map<String, Object> user = mapper.user(userId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", userId);
        result.put("status", user == null ? "active" : user.get("status"));
        result.put("phone", mapper.phone(userId));
        result.put("display_name", profile.get("display_name"));
        result.put("alternate_name", profile.get("alternate_name"));
        result.put("occupation", profile.get("occupation"));
        result.put("current_goal", profile.get("current_goal"));
        result.put("avatar_object_key", profile.get("avatar_object_key"));
        result.put("updated_at", profile.get("updated_at"));
        return result;
    }

    @Transactional
    public Map<String, Object> updateMe(long userId, Requests.ProfileInput input) {
        if (mapper.profile(userId) == null) {
            throw ApiException.notFound("用户资料不存在");
        }
        mapper.updateProfile(userId, input.getDisplayName().trim(), trimToNull(input.getAlternateName()),
                trimToNull(input.getOccupation()), trimToNull(input.getCurrentGoal()),
                trimToNull(input.getAvatarObjectKey()), Instant.now());
        return me(userId);
    }

    public Map<String, Object> preferences(long userId) {
        Map<String, Object> row = mapper.preferences(userId);
        if (row == null) {
            throw ApiException.notFound("用户偏好不存在");
        }
        normalizeTimes(row);
        return row;
    }

    @Transactional
    public Map<String, Object> updatePreferences(long userId, Requests.Preferences input) {
        Map<String, Object> current = mapper.preferences(userId);
        if (current == null) {
            throw ApiException.notFound("用户偏好不存在");
        }
        String timezone = input.getTimezoneName() == null
                ? String.valueOf(current.get("timezone_name"))
                : Times.zone(input.getTimezoneName());
        String appearance = input.getAppearance() == null
                ? String.valueOf(current.get("appearance"))
                : input.getAppearance().trim();
        if (!"light".equals(appearance) && !"dark".equals(appearance) && !"system".equals(appearance)) {
            throw ApiException.bad("外观只能是 light、dark 或 system");
        }
        String mode = input.getSchedulingMode() == null
                ? String.valueOf(current.get("scheduling_mode"))
                : input.getSchedulingMode().trim();
        if (!"deep_focus".equals(mode) && !"balanced".equals(mode)) {
            throw ApiException.bad("排程模式只能是 deep_focus 或 balanced");
        }
        int focus = input.getFocusBlockSeconds() == null
                ? number(current.get("focus_block_seconds"))
                : input.getFocusBlockSeconds();
        if (focus < 60 || focus > 86400) {
            throw ApiException.bad("专注时长需在 60 到 86400 秒之间");
        }
        String activeStart = input.getActiveStart() == null
                ? Times.timeOfDay(String.valueOf(current.get("active_start")), "开始时间")
                : Times.timeOfDay(input.getActiveStart(), "开始时间");
        String activeEnd = input.getActiveEnd() == null
                ? Times.timeOfDay(String.valueOf(current.get("active_end")), "结束时间")
                : Times.timeOfDay(input.getActiveEnd(), "结束时间");
        if (activeStart.equals(activeEnd)) {
            throw ApiException.bad("每日可排程开始与结束时间不能相同");
        }
        boolean dndEnabled = input.getDndEnabled() == null
                ? bool(current.get("dnd_enabled"))
                : input.getDndEnabled();
        String dndStart = input.getDndStart() == null
                ? Times.timeOfDay(String.valueOf(current.get("dnd_start")), "免打扰开始时间")
                : Times.timeOfDay(input.getDndStart(), "免打扰开始时间");
        String dndEnd = input.getDndEnd() == null
                ? Times.timeOfDay(String.valueOf(current.get("dnd_end")), "免打扰结束时间")
                : Times.timeOfDay(input.getDndEnd(), "免打扰结束时间");
        if (dndStart.equals(dndEnd)) {
            throw ApiException.bad("免打扰开始与结束时间不能相同");
        }
        boolean remindersEnabled = input.getRemindersEnabled() == null
                ? bool(current.get("reminders_enabled"))
                : input.getRemindersEnabled();
        int reminderLead = input.getReminderLeadSeconds() == null
                ? number(current.get("reminder_lead_seconds"))
                : input.getReminderLeadSeconds();
        if (reminderLead < 0 || reminderLead > 86400) {
            throw ApiException.bad("提醒提前量需在 0 到 86400 秒之间");
        }

        mapper.updatePreferences(userId, timezone, appearance, mode, focus, activeStart, activeEnd,
                dndEnabled, dndStart, dndEnd, remindersEnabled, reminderLead, Instant.now());
        return preferences(userId);
    }

    private Map<String, Object> issue(long userId) {
        Instant now = Instant.now();
        Instant accessExpires = now.plusSeconds(ACCESS_SECONDS);
        Instant refreshExpires = now.plusSeconds(REFRESH_SECONDS);
        String accessToken = Hashing.token();
        String refreshToken = Hashing.token();
        mapper.insertSession(userId, Hashing.sha256(accessToken), Hashing.sha256(refreshToken), "app",
                accessExpires, refreshExpires, now);
        return tokenPair(userId, accessToken, refreshToken, accessExpires, refreshExpires);
    }

    private Map<String, Object> tokenPair(long userId, String accessToken, String refreshToken,
                                          Instant accessExpires, Instant refreshExpires) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("access_token", accessToken);
        result.put("refresh_token", refreshToken);
        result.put("access_expires_at", Times.iso(accessExpires));
        result.put("refresh_expires_at", Times.iso(refreshExpires));
        result.put("user", me(userId));
        return result;
    }

    private static void normalizeTimes(Map<String, Object> row) {
        String[] keys = {"active_start", "active_end", "dnd_start", "dnd_end"};
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                row.put(key, Times.timeOfDay(String.valueOf(value), key));
            }
        }
    }

    private static Instant instant(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        return Instant.parse(String.valueOf(value));
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
