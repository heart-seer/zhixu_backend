package com.zhixu.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixu.model.dto.Requests;
import com.zhixu.model.entity.Result;
import com.zhixu.service.ApiException;
import com.zhixu.service.CoreService;
import com.zhixu.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
public class ApiController {
    private final UserService users;
    private final CoreService core;
    private final ObjectMapper json;

    public ApiController(UserService users, CoreService core, ObjectMapper json) {
        this.users = users;
        this.core = core;
        this.json = json;
    }

    // ---------- 认证 ----------

    @Operation(summary = "注册账号", description = "手机号 + 密码注册，成功后直接返回访问令牌与刷新令牌。",
            tags = "认证")
    @PostMapping("/auth/register")
    public Result register(@Valid @RequestBody Requests.Credentials body) {
        return Result.success(users.register(body));
    }

    @Operation(summary = "密码登录", description = "使用手机号与密码登录，返回新的访问令牌与刷新令牌。",
            tags = "认证")
    @PostMapping("/auth/login/password")
    public Result login(@Valid @RequestBody Requests.Credentials body) {
        return Result.success(users.login(body));
    }

    @Operation(summary = "刷新令牌", description = "用刷新令牌换取新令牌；旧访问令牌与刷新令牌立即失效。",
            tags = "认证")
    @PostMapping("/auth/refresh")
    public Result refresh(@Valid @RequestBody Requests.RefreshInput body) {
        return Result.success(users.refresh(body));
    }

    @Operation(summary = "发送短信验证码", description = "尚未接入短信服务，当前固定返回 501。",
            tags = "认证")
    @PostMapping("/auth/code")
    public Result sendCode() {
        throw new ApiException(501, "短信验证码尚未接入");
    }

    @Operation(summary = "验证码登录", description = "尚未接入短信服务，当前固定返回 501。",
            tags = "认证")
    @PostMapping("/auth/login/code")
    public Result loginByCode() {
        throw new ApiException(501, "短信验证码登录尚未接入");
    }

    @Operation(summary = "退出登录", description = "撤销当前访问令牌所属会话。",
            tags = "认证", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/auth/logout")
    public Result logout(HttpServletRequest request) {
        users.logout(bearer(request));
        return Result.success(null);
    }

    // ---------- 我的资料与偏好 ----------

    @Operation(summary = "读取我的资料", description = "返回昵称、职业、当前目标、手机号与账号状态。",
            tags = "我的资料与偏好", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me")
    public Result me(HttpServletRequest request) {
        return Result.success(users.me(uid(request)));
    }

    @Operation(summary = "更新我的资料", description = "按传入字段合并更新个人档案。",
            tags = "我的资料与偏好", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/me")
    public Result updateMe(HttpServletRequest request, @Valid @RequestBody Requests.ProfileInput body) {
        return Result.success(users.updateMe(uid(request), body));
    }

    @Operation(summary = "读取偏好设置", description = "时区、外观、排程模式、专注时长、免打扰与提醒配置。",
            tags = "我的资料与偏好", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me/preferences")
    public Result preferences(HttpServletRequest request) {
        return Result.success(users.preferences(uid(request)));
    }

    @Operation(summary = "更新偏好设置", description = "只更新传入字段；时间字段格式为 HH:mm:ss，时区需为合法 IANA 名称。",
            tags = "我的资料与偏好", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/me/preferences")
    public Result updatePreferences(HttpServletRequest request, @RequestBody Requests.Preferences body) {
        return Result.success(users.updatePreferences(uid(request), body));
    }

    // ---------- 任务 ----------

    @Operation(summary = "任务列表", description = "按创建时间倒序分页，before 传入上一页最后一条的时间游标。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tasks")
    public Result tasks(HttpServletRequest request,
                        @Parameter(description = "时间游标，ISO-8601 UTC，如 2026-10-21T09:00:00Z")
                        @RequestParam(value = "before", required = false) String before,
                        @Parameter(description = "返回条数，默认 20")
                        @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(core.tasks(uid(request), before, limit));
    }

    @Operation(summary = "任务详情", description = "返回任务及其步骤、时间块。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tasks/{id}")
    public Result task(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.task(uid(request), id));
    }

    @Operation(summary = "创建任务", description = "支持携带步骤；相同 Idempotency-Key 与相同正文重复提交返回首次结果。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tasks")
    public Result createTask(HttpServletRequest request,
                             @Parameter(description = "幂等键，建议使用 UUID")
                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                             @Valid @RequestBody Requests.TaskInput body) {
        return Result.success(core.createTask(uid(request), body, key, canonical(body)));
    }

    @Operation(summary = "更新任务", description = "携带 version 做乐观锁校验，版本不一致返回 409。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/tasks/{id}")
    public Result updateTask(HttpServletRequest request, @PathVariable("id") long id,
                             @RequestBody Requests.TaskUpdate body) {
        return Result.success(core.updateTask(uid(request), id, body));
    }

    @Operation(summary = "完成任务", description = "完成时同步把该任务未完成的计划时间块标记为完成。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tasks/{id}/completion")
    public Result completeTask(HttpServletRequest request, @PathVariable("id") long id,
                               @RequestBody Requests.Completion body) {
        return Result.success(core.completeTask(uid(request), id, body));
    }

    @Operation(summary = "删除任务", description = "软删除；可传 version 做乐观锁校验。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/tasks/{id}")
    public Result deleteTask(HttpServletRequest request, @PathVariable("id") long id,
                             @Parameter(description = "乐观锁版本号")
                             @RequestParam(value = "version", required = false) Long version) {
        core.deleteTask(uid(request), id, version);
        return Result.success(null);
    }

    @Operation(summary = "新增步骤", description = "向任务追加步骤；已完成的任务会被重新打开。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tasks/{id}/steps")
    public Result addStep(HttpServletRequest request, @PathVariable("id") long id,
                          @Valid @RequestBody Requests.StepInput body) {
        return Result.success(core.addStep(uid(request), id, body));
    }

    @Operation(summary = "更新步骤", description = "更新步骤标题、排序或完成状态。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/tasks/{id}/steps/{stepId}")
    public Result updateStep(HttpServletRequest request, @PathVariable("id") long id,
                             @PathVariable("stepId") long stepId,
                             @RequestBody Requests.StepInput body) {
        return Result.success(core.updateStep(uid(request), id, stepId, body));
    }

    @Operation(summary = "完成步骤", description = "切换步骤完成状态。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tasks/{id}/steps/{stepId}/completion")
    public Result completeStep(HttpServletRequest request, @PathVariable("id") long id,
                               @PathVariable("stepId") long stepId,
                               @RequestBody Requests.Completion body) {
        return Result.success(core.completeStep(uid(request), id, stepId, body));
    }

    @Operation(summary = "删除步骤", description = "删除指定步骤并重排剩余步骤顺序。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/tasks/{id}/steps/{stepId}")
    public Result deleteStep(HttpServletRequest request, @PathVariable("id") long id,
                             @PathVariable("stepId") long stepId) {
        return Result.success(core.deleteStep(uid(request), id, stepId));
    }

    @Operation(summary = "添加时间块", description = "为任务安排一段时间并生成关联日程；支持幂等键。",
            tags = "任务", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tasks/{id}/time-blocks")
    public Result createTimeBlock(HttpServletRequest request, @PathVariable("id") long id,
                                  @Parameter(description = "幂等键，建议使用 UUID")
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @Valid @RequestBody Requests.TimeBlockInput body) {
        return Result.success(core.createTimeBlock(uid(request), id, body, key, canonical(body)));
    }

    // ---------- 日程 ----------

    @Operation(summary = "日程列表", description = "按时间区间查询与区间重叠的日程，start 与 end 均为 ISO-8601 UTC。",
            tags = "日程", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/calendar/events")
    public Result events(HttpServletRequest request,
                         @Parameter(description = "区间开始，ISO-8601 UTC")
                         @RequestParam(value = "start", required = false) String start,
                         @Parameter(description = "区间结束，ISO-8601 UTC")
                         @RequestParam(value = "end", required = false) String end,
                         @Parameter(description = "返回条数，默认 50")
                         @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(core.events(uid(request), start, end, limit));
    }

    @Operation(summary = "日程详情", tags = "日程",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/calendar/events/{id}")
    public Result event(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.event(uid(request), id));
    }

    @Operation(summary = "创建日程", description = "结束时间必须晚于开始时间；支持幂等键。",
            tags = "日程", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/calendar/events")
    public Result createEvent(HttpServletRequest request,
                              @Parameter(description = "幂等键，建议使用 UUID")
                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                              @Valid @RequestBody Requests.EventInput body) {
        return Result.success(core.createEvent(uid(request), body, key, canonical(body)));
    }

    @Operation(summary = "更新日程", description = "必须携带 version，版本不一致返回 409。",
            tags = "日程", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/calendar/events/{id}")
    public Result updateEvent(HttpServletRequest request, @PathVariable("id") long id,
                              @Valid @RequestBody Requests.EventUpdate body) {
        return Result.success(core.updateEvent(uid(request), id, body));
    }

    @Operation(summary = "完成日程", description = "仅改变日程状态，不影响关联任务。",
            tags = "日程", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/calendar/events/{id}/completion")
    public Result completeEvent(HttpServletRequest request, @PathVariable("id") long id,
                                @RequestBody Requests.Completion body) {
        return Result.success(core.completeEvent(uid(request), id, body));
    }

    @Operation(summary = "删除日程", description = "软删除；可传 version 做乐观锁校验。",
            tags = "日程", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/calendar/events/{id}")
    public Result deleteEvent(HttpServletRequest request, @PathVariable("id") long id,
                              @Parameter(description = "乐观锁版本号")
                              @RequestParam(value = "version", required = false) Long version) {
        core.deleteEvent(uid(request), id, version);
        return Result.success(null);
    }

    // ---------- 消息与提取 ----------

    @Operation(summary = "消息列表", description = "按接收时间倒序分页，before 传入上一页最后一条的时间游标。",
            tags = "消息与提取", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/messages")
    public Result messages(HttpServletRequest request,
                           @Parameter(description = "时间游标，ISO-8601 UTC")
                           @RequestParam(value = "before", required = false) String before,
                           @Parameter(description = "返回条数，默认 20")
                           @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(core.messages(uid(request), before, limit));
    }

    @Operation(summary = "消息详情", description = "返回消息正文及已生成的提取草稿。",
            tags = "消息与提取", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/messages/{id}")
    public Result message(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.message(uid(request), id));
    }

    @Operation(summary = "导入消息", description = "同一外部消息重复导入返回既有消息并标记 duplicate=true。",
            tags = "消息与提取", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/messages/import")
    public Result importMessage(HttpServletRequest request, @Valid @RequestBody Requests.MessageInput body) {
        return Result.success(core.importMessage(uid(request), body));
    }

    @Operation(summary = "标记消息已读", tags = "消息与提取",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/messages/{id}/read")
    public Result readMessage(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.readMessage(uid(request), id));
    }

    @Operation(summary = "生成提取草稿", description = "从消息内容生成待确认的任务草稿；itemKey 默认 main。",
            tags = "消息与提取", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/messages/{id}/drafts/task")
    public Result createDraft(HttpServletRequest request, @PathVariable("id") long id,
                              @Parameter(description = "消息内的条目键，默认 main")
                              @RequestParam(value = "itemKey", required = false) String itemKey,
                              @Parameter(description = "解析时间使用的 IANA 时区，默认取用户偏好")
                              @RequestParam(value = "timezone", required = false) String timezone) {
        return Result.success(core.createDraft(uid(request), id, itemKey, timezone));
    }

    @Operation(summary = "确认提取草稿", description = "把草稿转为正式任务与日程；重复确认返回首次结果。",
            tags = "消息与提取", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/extraction-drafts/{id}/confirm")
    public Result confirmDraft(HttpServletRequest request, @PathVariable("id") long id,
                               @RequestBody(required = false) Requests.Confirmation body) {
        return Result.success(core.confirmDraft(uid(request), id, body));
    }

    // ---------- 内部 ----------

    private long uid(HttpServletRequest request) {
        return users.authenticate(bearer(request));
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException.unauthorized("未登录");
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw ApiException.unauthorized("未登录");
        }
        return token;
    }

    private String canonical(Object body) {
        if (body == null) {
            return "";
        }
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw ApiException.bad("请求体无法序列化");
        }
    }
}
