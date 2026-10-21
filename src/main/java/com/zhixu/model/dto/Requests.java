package com.zhixu.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

public class Requests {
    public static class Credentials {
        @NotBlank(message = "手机号不能为空")
        private String phone;
        @NotBlank(message = "密码不能为空")
        private String password;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class RefreshInput {
        @NotBlank(message = "刷新令牌不能为空")
        private String refreshToken;

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    public static class StepInput {
        @NotBlank(message = "步骤标题不能为空")
        private String title;
        private Integer position;
        private Boolean completed;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Integer getPosition() {
            return position;
        }

        public void setPosition(Integer position) {
            this.position = position;
        }

        public Boolean getCompleted() {
            return completed;
        }

        public void setCompleted(Boolean completed) {
            this.completed = completed;
        }
    }

    public static class TaskInput {
        @NotBlank(message = "任务标题不能为空")
        private String title;
        private String description;
        private String categoryCode;
        private String priority;
        private Integer estimateSeconds;
        private String dueAt;
        private List<StepInput> steps;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCategoryCode() {
            return categoryCode;
        }

        public void setCategoryCode(String categoryCode) {
            this.categoryCode = categoryCode;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
        }

        public Integer getEstimateSeconds() {
            return estimateSeconds;
        }

        public void setEstimateSeconds(Integer estimateSeconds) {
            this.estimateSeconds = estimateSeconds;
        }

        public String getDueAt() {
            return dueAt;
        }

        public void setDueAt(String dueAt) {
            this.dueAt = dueAt;
        }

        public List<StepInput> getSteps() {
            return steps;
        }

        public void setSteps(List<StepInput> steps) {
            this.steps = steps;
        }
    }

    public static class TaskUpdate {
        private String title;
        private String description;
        private String categoryCode;
        private String priority;
        private Integer estimateSeconds;
        private String dueAt;
        private Long version;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCategoryCode() {
            return categoryCode;
        }

        public void setCategoryCode(String categoryCode) {
            this.categoryCode = categoryCode;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
        }

        public Integer getEstimateSeconds() {
            return estimateSeconds;
        }

        public void setEstimateSeconds(Integer estimateSeconds) {
            this.estimateSeconds = estimateSeconds;
        }

        public String getDueAt() {
            return dueAt;
        }

        public void setDueAt(String dueAt) {
            this.dueAt = dueAt;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }

    public static class Completion {
        private Boolean completed;
        private Long version;

        public Boolean getCompleted() {
            return completed;
        }

        public void setCompleted(Boolean completed) {
            this.completed = completed;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }

    public static class TimeBlockInput {
        @NotBlank(message = "开始时间不能为空")
        private String startsAt;
        @NotBlank(message = "结束时间不能为空")
        private String endsAt;
        private String timezoneName;
        private Integer reminderLeadSeconds;

        public String getStartsAt() {
            return startsAt;
        }

        public void setStartsAt(String startsAt) {
            this.startsAt = startsAt;
        }

        public String getEndsAt() {
            return endsAt;
        }

        public void setEndsAt(String endsAt) {
            this.endsAt = endsAt;
        }

        public String getTimezoneName() {
            return timezoneName;
        }

        public void setTimezoneName(String timezoneName) {
            this.timezoneName = timezoneName;
        }

        public Integer getReminderLeadSeconds() {
            return reminderLeadSeconds;
        }

        public void setReminderLeadSeconds(Integer reminderLeadSeconds) {
            this.reminderLeadSeconds = reminderLeadSeconds;
        }
    }

    public static class EventInput {
        @NotBlank(message = "日程标题不能为空")
        private String title;
        private String description;
        @NotBlank(message = "开始时间不能为空")
        private String startsAt;
        @NotBlank(message = "结束时间不能为空")
        private String endsAt;
        @NotBlank(message = "时区不能为空")
        private String timezoneName;
        private Long taskId;
        private Integer reminderLeadSeconds;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStartsAt() {
            return startsAt;
        }

        public void setStartsAt(String startsAt) {
            this.startsAt = startsAt;
        }

        public String getEndsAt() {
            return endsAt;
        }

        public void setEndsAt(String endsAt) {
            this.endsAt = endsAt;
        }

        public String getTimezoneName() {
            return timezoneName;
        }

        public void setTimezoneName(String timezoneName) {
            this.timezoneName = timezoneName;
        }

        public Long getTaskId() {
            return taskId;
        }

        public void setTaskId(Long taskId) {
            this.taskId = taskId;
        }

        public Integer getReminderLeadSeconds() {
            return reminderLeadSeconds;
        }

        public void setReminderLeadSeconds(Integer reminderLeadSeconds) {
            this.reminderLeadSeconds = reminderLeadSeconds;
        }
    }

    public static class EventUpdate {
        private String title;
        private String description;
        private String startsAt;
        private String endsAt;
        private String timezoneName;
        private Integer reminderLeadSeconds;
        @NotNull(message = "版本号不能为空")
        private Long version;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStartsAt() {
            return startsAt;
        }

        public void setStartsAt(String startsAt) {
            this.startsAt = startsAt;
        }

        public String getEndsAt() {
            return endsAt;
        }

        public void setEndsAt(String endsAt) {
            this.endsAt = endsAt;
        }

        public String getTimezoneName() {
            return timezoneName;
        }

        public void setTimezoneName(String timezoneName) {
            this.timezoneName = timezoneName;
        }

        public Integer getReminderLeadSeconds() {
            return reminderLeadSeconds;
        }

        public void setReminderLeadSeconds(Integer reminderLeadSeconds) {
            this.reminderLeadSeconds = reminderLeadSeconds;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }

    public static class MessageInput {
        @NotBlank(message = "来源平台不能为空")
        private String platform;
        private String externalMessageId;
        @NotBlank(message = "发送人不能为空")
        private String senderName;
        @NotBlank(message = "消息标题不能为空")
        private String title;
        @NotBlank(message = "消息摘要不能为空")
        private String preview;
        private String body;
        @NotBlank(message = "消息类型不能为空")
        private String kind;
        private String receivedAt;

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getExternalMessageId() {
            return externalMessageId;
        }

        public void setExternalMessageId(String externalMessageId) {
            this.externalMessageId = externalMessageId;
        }

        public String getSenderName() {
            return senderName;
        }

        public void setSenderName(String senderName) {
            this.senderName = senderName;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getPreview() {
            return preview;
        }

        public void setPreview(String preview) {
            this.preview = preview;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getReceivedAt() {
            return receivedAt;
        }

        public void setReceivedAt(String receivedAt) {
            this.receivedAt = receivedAt;
        }
    }

    public static class Confirmation {
        private String timezone;

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }

    public static class ProfileInput {
        @NotBlank(message = "昵称不能为空")
        private String displayName;
        private String alternateName;
        private String occupation;
        private String currentGoal;
        private String avatarObjectKey;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAlternateName() {
            return alternateName;
        }

        public void setAlternateName(String alternateName) {
            this.alternateName = alternateName;
        }

        public String getOccupation() {
            return occupation;
        }

        public void setOccupation(String occupation) {
            this.occupation = occupation;
        }

        public String getCurrentGoal() {
            return currentGoal;
        }

        public void setCurrentGoal(String currentGoal) {
            this.currentGoal = currentGoal;
        }

        public String getAvatarObjectKey() {
            return avatarObjectKey;
        }

        public void setAvatarObjectKey(String avatarObjectKey) {
            this.avatarObjectKey = avatarObjectKey;
        }
    }

    public static class Preferences {
        private String timezoneName;
        private String appearance;
        private String schedulingMode;
        private Integer focusBlockSeconds;
        private String activeStart;
        private String activeEnd;
        private Boolean dndEnabled;
        private String dndStart;
        private String dndEnd;
        private Boolean remindersEnabled;
        private Integer reminderLeadSeconds;

        public String getTimezoneName() {
            return timezoneName;
        }

        public void setTimezoneName(String timezoneName) {
            this.timezoneName = timezoneName;
        }

        public String getAppearance() {
            return appearance;
        }

        public void setAppearance(String appearance) {
            this.appearance = appearance;
        }

        public String getSchedulingMode() {
            return schedulingMode;
        }

        public void setSchedulingMode(String schedulingMode) {
            this.schedulingMode = schedulingMode;
        }

        public Integer getFocusBlockSeconds() {
            return focusBlockSeconds;
        }

        public void setFocusBlockSeconds(Integer focusBlockSeconds) {
            this.focusBlockSeconds = focusBlockSeconds;
        }

        public String getActiveStart() {
            return activeStart;
        }

        public void setActiveStart(String activeStart) {
            this.activeStart = activeStart;
        }

        public String getActiveEnd() {
            return activeEnd;
        }

        public void setActiveEnd(String activeEnd) {
            this.activeEnd = activeEnd;
        }

        public Boolean getDndEnabled() {
            return dndEnabled;
        }

        public void setDndEnabled(Boolean dndEnabled) {
            this.dndEnabled = dndEnabled;
        }

        public String getDndStart() {
            return dndStart;
        }

        public void setDndStart(String dndStart) {
            this.dndStart = dndStart;
        }

        public String getDndEnd() {
            return dndEnd;
        }

        public void setDndEnd(String dndEnd) {
            this.dndEnd = dndEnd;
        }

        public Boolean getRemindersEnabled() {
            return remindersEnabled;
        }

        public void setRemindersEnabled(Boolean remindersEnabled) {
            this.remindersEnabled = remindersEnabled;
        }

        public Integer getReminderLeadSeconds() {
            return reminderLeadSeconds;
        }

        public void setReminderLeadSeconds(Integer reminderLeadSeconds) {
            this.reminderLeadSeconds = reminderLeadSeconds;
        }
    }
}
