package com.zhixu.service;

import com.zhixu.mapper.CoreMapper;
import com.zhixu.mapper.UserMapper;
import com.zhixu.model.dto.Requests;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CoreService {
    private static final String OPERATION_TASK = "task.create";
    private static final String OPERATION_EVENT = "event.create";
    private static final String OPERATION_TIME_BLOCK = "task.time-block";

    private final CoreMapper mapper;
    private final UserMapper users;

    public CoreService(CoreMapper mapper, UserMapper users) {
        this.mapper = mapper;
        this.users = users;
    }

    // ---------- 任务 ----------

    public List<Map<String, Object>> tasks(long userId, String before, Integer limit) {
        Instant cursor = Times.parse(before, "before");
        List<Map<String, Object>> rows = mapper.tasks(userId, cursor, clamp(limit));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.add(expandTask(row));
        }
        return result;
    }

    public Map<String, Object> task(long userId, long taskId) {
        return getTask(userId, taskId);
    }

    @Transactional
    public Map<String, Object> createTask(long userId, Requests.TaskInput input, String idempotencyKey,
                                          String canonical) {
        boolean keyed = idempotencyKey != null && !idempotencyKey.trim().isEmpty();
        Long requestId = keyed ? beginIdempotency(userId, OPERATION_TASK, idempotencyKey, canonical) : null;
        if (keyed && requestId == null) {
            return reuseTask(userId, OPERATION_TASK, idempotencyKey, canonical);
        }
        String title = requiredTitle(input.getTitle(), "任务标题不能为空");
        String priority = priority(input.getPriority());
        Integer estimate = positiveOrNull(input.getEstimateSeconds(), "预计耗时必须大于零");
        Instant dueAt = Times.parse(input.getDueAt(), "截止时间");
        long platformId = personalPlatform();

        Instant now = Instant.now();
        long taskId = mapper.insertTask(userId, null, null, platformId, title,
                trimToNull(input.getDescription()), trimToNull(input.getCategoryCode()), priority, estimate, dueAt,
                false, now);
        if (input.getSteps() != null) {
            int position = 0;
            for (Requests.StepInput step : input.getSteps()) {
                if (step == null || step.getTitle() == null || step.getTitle().trim().isEmpty()) {
                    throw ApiException.bad("步骤标题不能为空");
                }
                int slot = step.getPosition() == null ? position : step.getPosition();
                insertStep(taskId, step.getTitle().trim(), slot, step.getCompleted() != null && step.getCompleted()
                        ? now : null);
                position = Math.max(position, slot) + 1;
            }
        }
        if (requestId != null) {
            mapper.finishIdempotency(requestId, taskId, null);
        }
        return getTask(userId, taskId);
    }

    @Transactional
    public Map<String, Object> updateTask(long userId, long taskId, Requests.TaskUpdate input) {
        Map<String, Object> row = requireTask(userId, taskId);
        long version = asLong(row.get("version"));
        if (input.getVersion() != null && input.getVersion().longValue() != version) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        String title = input.getTitle() == null ? (String) row.get("title")
                : requiredTitle(input.getTitle(), "任务标题不能为空");
        String description = input.getDescription() == null ? (String) row.get("description")
                : trimToNull(input.getDescription());
        String categoryCode = input.getCategoryCode() == null ? (String) row.get("category_code")
                : trimToNull(input.getCategoryCode());
        String priority = input.getPriority() == null ? (String) row.get("priority") : priority(input.getPriority());
        Integer estimate = input.getEstimateSeconds() == null ? nullableInt(row.get("estimate_seconds"))
                : positiveOrNull(input.getEstimateSeconds(), "预计耗时必须大于零");
        Instant dueAt = input.getDueAt() == null ? instantOrNull(row.get("due_at"))
                : Times.parse(input.getDueAt(), "截止时间");

        int updated = mapper.updateTask(userId, taskId, version, title, description, categoryCode, priority,
                estimate, dueAt, Instant.now());
        if (updated == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        return getTask(userId, taskId);
    }

    @Transactional
    public Map<String, Object> completeTask(long userId, long taskId, Requests.Completion input) {
        Map<String, Object> row = requireTask(userId, taskId);
        long version = asLong(row.get("version"));
        if (input.getVersion() != null && input.getVersion().longValue() != version) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        boolean completed = input.getCompleted() == null || input.getCompleted();
        Instant now = Instant.now();
        int updated = mapper.completeTask(userId, taskId, version, completed, now);
        if (updated == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        if (completed) {
            mapper.completePlannedTaskEvents(userId, taskId, now);
        }
        return getTask(userId, taskId);
    }

    @Transactional
    public void deleteTask(long userId, long taskId, Long version) {
        Map<String, Object> row = requireTask(userId, taskId);
        long current = asLong(row.get("version"));
        if (version != null && version.longValue() != current) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        if (mapper.softDeleteTask(userId, taskId, current, Instant.now()) == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
    }

    @Transactional
    public Map<String, Object> addStep(long userId, long taskId, Requests.StepInput input) {
        Map<String, Object> row = requireTask(userId, taskId);
        long version = asLong(row.get("version"));
        String title = requiredTitle(input.getTitle(), "步骤标题不能为空");
        int position = input.getPosition() == null ? mapper.maxStepPosition(taskId) + 1 : input.getPosition();
        if (position < 0) {
            throw ApiException.bad("步骤排序不能为负数");
        }
        Instant now = Instant.now();
        boolean reopen = "completed".equals(row.get("status"));
        int bumped = reopen ? mapper.reopenTask(userId, taskId, version, now)
                : mapper.bumpTask(userId, taskId, version, now);
        if (bumped == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        try {
            mapper.insertStep(taskId, title, position, null);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该排序位置已被占用");
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("该排序位置已被占用");
        }
        return getTask(userId, taskId);
    }

    @Transactional
    public Map<String, Object> updateStep(long userId, long taskId, long stepId, Requests.StepInput input) {
        Map<String, Object> task = requireTask(userId, taskId);
        Map<String, Object> step = mapper.stepRow(taskId, stepId);
        if (step == null) {
            throw ApiException.notFound("步骤不存在");
        }
        String title = input.getTitle() == null ? (String) step.get("title")
                : requiredTitle(input.getTitle(), "步骤标题不能为空");
        mapper.updateStepTitle(taskId, stepId, title);
        if (input.getPosition() != null) {
            if (input.getPosition() < 0) {
                throw ApiException.bad("步骤排序不能为负数");
            }
            try {
                mapper.updateStepPosition(taskId, stepId, input.getPosition());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.conflict("该排序位置已被占用");
            }
        }
        if (input.getCompleted() != null) {
            mapper.updateStepCompleted(taskId, stepId, input.getCompleted() ? Instant.now() : null);
        }
        if (mapper.bumpTask(userId, taskId, asLong(task.get("version")), Instant.now()) == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        return getTask(userId, taskId);
    }

    @Transactional
    public Map<String, Object> completeStep(long userId, long taskId, long stepId, Requests.Completion input) {
        Map<String, Object> task = requireTask(userId, taskId);
        if (mapper.stepRow(taskId, stepId) == null) {
            throw ApiException.notFound("步骤不存在");
        }
        boolean completed = input.getCompleted() == null || input.getCompleted();
        mapper.updateStepCompleted(taskId, stepId, completed ? Instant.now() : null);
        if (mapper.bumpTask(userId, taskId, asLong(task.get("version")), Instant.now()) == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        return getTask(userId, taskId);
    }

    @Transactional
    public Map<String, Object> deleteStep(long userId, long taskId, long stepId) {
        Map<String, Object> task = requireTask(userId, taskId);
        if (mapper.stepRow(taskId, stepId) == null) {
            throw ApiException.notFound("步骤不存在");
        }
        mapper.deleteStep(taskId, stepId);
        if (mapper.bumpTask(userId, taskId, asLong(task.get("version")), Instant.now()) == 0) {
            throw ApiException.conflict("任务已被修改，请刷新后重试");
        }
        return getTask(userId, taskId);
    }

    // ---------- 日程 ----------

    public List<Map<String, Object>> events(long userId, String start, String end, Integer limit) {
        Instant from = Times.parse(start, "start");
        Instant to = Times.parse(end, "end");
        if (from == null || to == null) {
            throw ApiException.bad("查询日程必须提供 start 与 end");
        }
        if (!to.isAfter(from)) {
            throw ApiException.bad("查询结束时间必须晚于开始时间");
        }
        return mapper.events(userId, from, to, clamp(limit));
    }

    public Map<String, Object> event(long userId, long eventId) {
        return getEvent(userId, eventId);
    }

    @Transactional
    public Map<String, Object> createEvent(long userId, Requests.EventInput input, String idempotencyKey,
                                           String canonical) {
        boolean keyed = idempotencyKey != null && !idempotencyKey.trim().isEmpty();
        Long requestId = keyed ? beginIdempotency(userId, OPERATION_EVENT, idempotencyKey, canonical) : null;
        if (keyed && requestId == null) {
            return reuseEvent(userId, OPERATION_EVENT, idempotencyKey, canonical);
        }
        Instant startsAt = Times.parse(input.getStartsAt(), "开始时间");
        Instant endsAt = Times.parse(input.getEndsAt(), "结束时间");
        validateRange(startsAt, endsAt);
        String timezone = Times.zone(input.getTimezoneName());
        Long taskId = input.getTaskId();
        if (taskId != null) {
            requireTask(userId, taskId);
        }
        Integer reminder = reminder(input.getReminderLeadSeconds());
        long eventId = mapper.insertEvent(userId, null, taskId, requiredTitle(input.getTitle(), "日程标题不能为空"),
                trimToNull(input.getDescription()), startsAt, endsAt, timezone, "planned", reminder, null,
                Instant.now());
        if (requestId != null) {
            mapper.finishIdempotency(requestId, null, eventId);
        }
        return getEvent(userId, eventId);
    }

    @Transactional
    public Map<String, Object> createTimeBlock(long userId, long taskId, Requests.TimeBlockInput input,
                                               String idempotencyKey, String canonical) {
        boolean keyed = idempotencyKey != null && !idempotencyKey.trim().isEmpty();
        Long requestId = keyed ? beginIdempotency(userId, OPERATION_TIME_BLOCK, idempotencyKey, canonical) : null;
        if (keyed && requestId == null) {
            return reuseEvent(userId, OPERATION_TIME_BLOCK, idempotencyKey, canonical);
        }
        Map<String, Object> task = requireTask(userId, taskId);
        Instant startsAt = Times.parse(input.getStartsAt(), "开始时间");
        Instant endsAt = Times.parse(input.getEndsAt(), "结束时间");
        validateRange(startsAt, endsAt);
        String timezone = input.getTimezoneName() == null ? preferenceTimezone(userId)
                : Times.zone(input.getTimezoneName());
        long eventId = mapper.insertEvent(userId, null, taskId, (String) task.get("title"), null, startsAt, endsAt,
                timezone, "planned", reminder(input.getReminderLeadSeconds()), null, Instant.now());
        if (requestId != null) {
            mapper.finishIdempotency(requestId, null, eventId);
        }
        return getEvent(userId, eventId);
    }

    @Transactional
    public Map<String, Object> updateEvent(long userId, long eventId, Requests.EventUpdate input) {
        Map<String, Object> row = requireEvent(userId, eventId);
        long version = asLong(row.get("version"));
        if (input.getVersion().longValue() != version) {
            throw ApiException.conflict("日程已被修改，请刷新后重试");
        }
        String title = input.getTitle() == null ? (String) row.get("title")
                : requiredTitle(input.getTitle(), "日程标题不能为空");
        String description = input.getDescription() == null ? (String) row.get("description")
                : trimToNull(input.getDescription());
        Instant startsAt = input.getStartsAt() == null ? instantOrNull(row.get("starts_at"))
                : Times.parse(input.getStartsAt(), "开始时间");
        Instant endsAt = input.getEndsAt() == null ? instantOrNull(row.get("ends_at"))
                : Times.parse(input.getEndsAt(), "结束时间");
        validateRange(startsAt, endsAt);
        String timezone = input.getTimezoneName() == null ? (String) row.get("timezone_name")
                : Times.zone(input.getTimezoneName());
        Integer reminder = input.getReminderLeadSeconds() == null ? nullableInt(row.get("reminder_lead_seconds"))
                : reminder(input.getReminderLeadSeconds());

        if (mapper.updateEvent(userId, eventId, version, title, description, startsAt, endsAt, timezone, reminder,
                Instant.now()) == 0) {
            throw ApiException.conflict("日程已被修改，请刷新后重试");
        }
        return getEvent(userId, eventId);
    }

    @Transactional
    public Map<String, Object> completeEvent(long userId, long eventId, Requests.Completion input) {
        Map<String, Object> row = requireEvent(userId, eventId);
        long version = asLong(row.get("version"));
        if (input.getVersion() != null && input.getVersion().longValue() != version) {
            throw ApiException.conflict("日程已被修改，请刷新后重试");
        }
        boolean completed = input.getCompleted() == null || input.getCompleted();
        Instant now = Instant.now();
        if (mapper.completeEvent(userId, eventId, version, completed ? "completed" : "planned",
                completed ? now : null, now) == 0) {
            throw ApiException.conflict("日程已被修改，请刷新后重试");
        }
        return getEvent(userId, eventId);
    }

    @Transactional
    public void deleteEvent(long userId, long eventId, Long version) {
        Map<String, Object> row = requireEvent(userId, eventId);
        long current = asLong(row.get("version"));
        if (version != null && version.longValue() != current) {
            throw ApiException.conflict("日程已被修改，请刷新后重试");
        }
        if (mapper.softDeleteEvent(userId, eventId, current, Instant.now()) == 0) {
            throw ApiException.conflict("日程已被修改，请刷新后重试");
        }
    }

    // ---------- 消息与提取 ----------

    public List<Map<String, Object>> messages(long userId, String before, Integer limit) {
        return mapper.messages(userId, Times.parse(before, "before"), clamp(limit));
    }

    public Map<String, Object> message(long userId, long messageId) {
        Map<String, Object> row = mapper.messageRow(userId, messageId);
        if (row == null) {
            throw ApiException.notFound("消息不存在");
        }
        row.put("drafts", mapper.drafts(messageId));
        return row;
    }

    @Transactional
    public Map<String, Object> importMessage(long userId, Requests.MessageInput input) {
        String platformCode = input.getPlatform().trim();
        Long platformId = mapper.platformId(platformCode);
        if (platformId == null) {
            throw ApiException.bad("来源平台不存在：" + platformCode);
        }
        String kind = input.getKind().trim();
        if (!"task".equals(kind) && !"calendar".equals(kind) && !"attachment".equals(kind)) {
            throw ApiException.bad("消息类型只能是 task、calendar 或 attachment");
        }
        Instant receivedAt = input.getReceivedAt() == null ? Instant.now()
                : Times.parse(input.getReceivedAt(), "接收时间");
        String externalId = trimToNull(input.getExternalMessageId());
        String fingerprint = externalId == null
                ? input.getSenderName() + "|" + input.getTitle() + "|" + input.getPreview() + "|"
                + Times.iso(receivedAt)
                : externalId;
        byte[] ingestKey = Hashing.sha256(userId + "|" + platformCode + "|" + fingerprint);

        Map<String, Object> existing = mapper.messageByIngest(userId, ingestKey);
        if (existing != null) {
            existing.put("duplicate", true);
            return existing;
        }
        try {
            long messageId = mapper.insertMessage(userId, platformId, ingestKey, externalId,
                    input.getSenderName().trim(), input.getTitle().trim(), input.getPreview().trim(),
                    trimToNull(input.getBody()), kind, receivedAt, Instant.now());
            Map<String, Object> created = mapper.messageRow(userId, messageId);
            created.put("duplicate", false);
            return created;
        } catch (DuplicateKeyException e) {
            return waitMessage(userId, ingestKey);
        } catch (DataIntegrityViolationException e) {
            return waitMessage(userId, ingestKey);
        }
    }

    @Transactional
    public Map<String, Object> readMessage(long userId, long messageId) {
        if (mapper.messageRow(userId, messageId) == null) {
            throw ApiException.notFound("消息不存在");
        }
        mapper.markRead(userId, messageId, Instant.now());
        return message(userId, messageId);
    }

    @Transactional
    public Map<String, Object> createDraft(long userId, long messageId, String itemKey, String timezone) {
        Map<String, Object> message = mapper.messageRow(userId, messageId);
        if (message == null) {
            throw ApiException.notFound("消息不存在");
        }
        String key = itemKey == null || itemKey.trim().isEmpty() ? "main" : itemKey.trim();
        if (key.length() > 64) {
            throw ApiException.bad("提取条目标识过长");
        }
        Map<String, Object> existing = mapper.draftByItem(messageId, key);
        if (existing != null) {
            return existing;
        }
        String zone = timezone == null || timezone.trim().isEmpty() ? preferenceTimezone(userId)
                : Times.zone(timezone);
        String title = String.valueOf(message.get("title"));
        if (title.length() > 255) {
            title = title.substring(0, 255);
        }
        try {
            long draftId = mapper.insertDraft(messageId, key, "task", title, (String) message.get("preview"),
                    null, null, null, "normal", null, zone, "manual-v1", Instant.now());
            return mapper.draftRow(draftId);
        } catch (DuplicateKeyException e) {
            Map<String, Object> draft = mapper.draftByItem(messageId, key);
            if (draft == null) {
                throw ApiException.conflict("提取草稿正在创建，请稍后重试");
            }
            return draft;
        }
    }

    @Transactional
    public Map<String, Object> confirmDraft(long userId, long draftId, Requests.Confirmation input) {
        Map<String, Object> draft = mapper.draftRow(draftId);
        if (draft == null || asLong(draft.get("user_id")) != userId) {
            throw ApiException.notFound("提取草稿不存在");
        }
        Map<String, Object> confirmed = mapper.confirmation(draftId);
        if (confirmed != null) {
            return confirmationResult(userId, draftId, confirmed);
        }
        if (!"pending".equals(draft.get("status"))) {
            throw ApiException.conflict("草稿已处理");
        }

        long messageId = asLong(draft.get("message_id"));
        long platformId = asLong(draft.get("platform_id"));
        String timezone = input != null && input.getTimezone() != null
                ? Times.zone(input.getTimezone()) : String.valueOf(draft.get("timezone_name"));
        Instant now = Instant.now();
        Long taskId = null;
        Long eventId = null;

        if ("calendar".equals(draft.get("target_kind"))) {
            Instant startsAt = instantOrNull(draft.get("proposed_start_at"));
            Instant endsAt = instantOrNull(draft.get("proposed_end_at"));
            if (startsAt == null || endsAt == null) {
                throw ApiException.bad("日程草稿缺少起止时间");
            }
            validateRange(startsAt, endsAt);
            eventId = mapper.insertEvent(userId, null, null, (String) draft.get("title"),
                    (String) draft.get("description"), startsAt, endsAt, timezone, "planned", null, null, now);
        } else {
            taskId = mapper.insertTask(userId, null, messageId, platformId,
                    requiredTitle((String) draft.get("title"), "任务标题不能为空"),
                    (String) draft.get("description"), null, priority((String) draft.get("proposed_priority")),
                    nullableInt(draft.get("proposed_estimate_seconds")),
                    instantOrNull(draft.get("proposed_due_at")), true, now);
        }
        mapper.confirmDraft(draftId, now);
        Long confirmationId;
        try {
            confirmationId = mapper.insertConfirmation(draftId, userId, taskId, eventId, now);
        } catch (DuplicateKeyException e) {
            Map<String, Object> again = mapper.confirmation(draftId);
            if (again == null) {
                throw ApiException.conflict("草稿已处理");
            }
            return confirmationResult(userId, draftId, again);
        }
        if (confirmationId == null) {
            throw ApiException.conflict("草稿已处理");
        }
        mapper.markProcessed(userId, messageId, now);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("draft_id", draftId);
        if (taskId != null) {
            result.put("task", getTask(userId, taskId));
        }
        if (eventId != null) {
            result.put("event", getEvent(userId, eventId));
        }
        return result;
    }

    // ---------- 内部方法 ----------

    private Map<String, Object> confirmationResult(long userId, long draftId, Map<String, Object> confirmation) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("draft_id", draftId);
        Long taskId = asLongOrNull(confirmation.get("task_id"));
        Long eventId = asLongOrNull(confirmation.get("event_id"));
        if (taskId != null) {
            result.put("task", getTask(userId, taskId));
        }
        if (eventId != null) {
            result.put("event", getEvent(userId, eventId));
        }
        return result;
    }

    private Map<String, Object> waitMessage(long userId, byte[] ingestKey) {
        for (int i = 0; i < 5; i++) {
            Map<String, Object> existing = mapper.messageByIngest(userId, ingestKey);
            if (existing != null) {
                existing.put("duplicate", true);
                return existing;
            }
            sleep(30);
        }
        throw ApiException.conflict("相同消息正在导入，请稍后重试");
    }

    private Long beginIdempotency(long userId, String operation, String idempotencyKey, String canonical) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.length() > 64) {
            throw ApiException.bad("Idempotency-Key 长度不能超过 64");
        }
        Instant now = Instant.now();
        try {
            return mapper.insertIdempotency(userId, operation, key,
                    Hashing.sha256(canonical == null ? "" : canonical), now, now.plusSeconds(86400));
        } catch (DuplicateKeyException e) {
            return null;
        } catch (DataIntegrityViolationException e) {
            return null;
        }
    }

    private Map<String, Object> reuseTask(long userId, String operation, String key, String canonical) {
        Map<String, Object> row = waitIdempotency(userId, operation, key, canonical);
        Long taskId = asLongOrNull(row.get("result_task_id"));
        if (taskId == null) {
            throw ApiException.conflict("请求结果不可用");
        }
        return getTask(userId, taskId);
    }

    private Map<String, Object> reuseEvent(long userId, String operation, String key, String canonical) {
        Map<String, Object> row = waitIdempotency(userId, operation, key, canonical);
        Long eventId = asLongOrNull(row.get("result_event_id"));
        if (eventId == null) {
            throw ApiException.conflict("请求结果不可用");
        }
        return getEvent(userId, eventId);
    }

    private Map<String, Object> waitIdempotency(long userId, String operation, String key, String canonical) {
        String expected = Hashing.base64(Hashing.sha256(canonical == null ? "" : canonical));
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = mapper.idempotency(userId, operation, key);
            if (row != null) {
                if (!expected.equals(String.valueOf(row.get("request_hash")))) {
                    throw ApiException.conflict("幂等键已用于不同的请求");
                }
                if ("completed".equals(row.get("status"))) {
                    return row;
                }
            }
            sleep(40);
        }
        throw ApiException.conflict("相同幂等键的请求正在处理，请稍后重试");
    }

    private Map<String, Object> getTask(long userId, long taskId) {
        Map<String, Object> row = mapper.taskRow(userId, taskId);
        if (row == null) {
            throw ApiException.notFound("任务不存在");
        }
        return expandTask(row);
    }

    private Map<String, Object> expandTask(Map<String, Object> row) {
        long id = asLong(row.get("id"));
        row.put("steps", mapper.steps(id));
        return row;
    }

    private Map<String, Object> requireTask(long userId, long taskId) {
        Map<String, Object> row = mapper.taskRow(userId, taskId);
        if (row == null) {
            throw ApiException.notFound("任务不存在");
        }
        return row;
    }

    private Map<String, Object> getEvent(long userId, long eventId) {
        return requireEvent(userId, eventId);
    }

    private Map<String, Object> requireEvent(long userId, long eventId) {
        Map<String, Object> row = mapper.eventRow(userId, eventId);
        if (row == null) {
            throw ApiException.notFound("日程不存在");
        }
        return row;
    }

    private long personalPlatform() {
        Long id = mapper.platformId("personal");
        if (id == null) {
            throw new IllegalStateException("缺少 personal 来源字典");
        }
        return id;
    }

    private String preferenceTimezone(long userId) {
        Map<String, Object> preferences = users.preferences(userId);
        if (preferences == null) {
            return "Asia/Shanghai";
        }
        return String.valueOf(preferences.get("timezone_name"));
    }

    private void insertStep(long taskId, String title, int position, Instant completedAt) {
        try {
            mapper.insertStep(taskId, title, position, completedAt);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("步骤排序位置重复");
        }
    }

    private static void validateRange(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null) {
            throw ApiException.bad("必须提供开始与结束时间");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw ApiException.bad("结束时间必须晚于开始时间");
        }
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 100);
    }

    private static String requiredTitle(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.bad(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > 255) {
            throw ApiException.bad("标题长度不能超过 255");
        }
        return trimmed;
    }

    private static String priority(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "normal";
        }
        String normalized = value.trim();
        if (!"p0".equals(normalized) && !"p1".equals(normalized) && !"p2".equals(normalized)
                && !"normal".equals(normalized)) {
            throw ApiException.bad("优先级只能是 p0、p1、p2 或 normal");
        }
        return normalized;
    }

    private static Integer positiveOrNull(Integer value, String message) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw ApiException.bad(message);
        }
        return value;
    }

    private static Integer reminder(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value > 86400) {
            throw ApiException.bad("提醒提前量需在 0 到 86400 秒之间");
        }
        return value;
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Long asLongOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private static Instant instantOrNull(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        return Instant.parse(String.valueOf(value));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
