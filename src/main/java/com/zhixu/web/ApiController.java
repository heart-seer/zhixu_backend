package com.zhixu.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixu.model.dto.Requests;
import com.zhixu.model.entity.Result;
import com.zhixu.service.ApiException;
import com.zhixu.service.CoreService;
import com.zhixu.service.UserService;
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

    @PostMapping("/auth/register")
    public Result register(@Valid @RequestBody Requests.Credentials body) {
        return Result.success(users.register(body));
    }

    @PostMapping("/auth/login/password")
    public Result login(@Valid @RequestBody Requests.Credentials body) {
        return Result.success(users.login(body));
    }

    @PostMapping("/auth/refresh")
    public Result refresh(@Valid @RequestBody Requests.RefreshInput body) {
        return Result.success(users.refresh(body));
    }

    @PostMapping("/auth/code")
    public Result sendCode() {
        throw new ApiException(501, "短信验证码尚未接入");
    }

    @PostMapping("/auth/login/code")
    public Result loginByCode() {
        throw new ApiException(501, "短信验证码登录尚未接入");
    }

    @PostMapping("/auth/logout")
    public Result logout(HttpServletRequest request) {
        users.logout(bearer(request));
        return Result.success(null);
    }

    // ---------- 我的资料与偏好 ----------

    @GetMapping("/me")
    public Result me(HttpServletRequest request) {
        return Result.success(users.me(uid(request)));
    }

    @PutMapping("/me")
    public Result updateMe(HttpServletRequest request, @Valid @RequestBody Requests.ProfileInput body) {
        return Result.success(users.updateMe(uid(request), body));
    }

    @GetMapping("/me/preferences")
    public Result preferences(HttpServletRequest request) {
        return Result.success(users.preferences(uid(request)));
    }

    @PutMapping("/me/preferences")
    public Result updatePreferences(HttpServletRequest request, @RequestBody Requests.Preferences body) {
        return Result.success(users.updatePreferences(uid(request), body));
    }

    // ---------- 任务 ----------

    @GetMapping("/tasks")
    public Result tasks(HttpServletRequest request,
                        @RequestParam(value = "before", required = false) String before,
                        @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(core.tasks(uid(request), before, limit));
    }

    @GetMapping("/tasks/{id}")
    public Result task(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.task(uid(request), id));
    }

    @PostMapping("/tasks")
    public Result createTask(HttpServletRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                             @Valid @RequestBody Requests.TaskInput body) {
        return Result.success(core.createTask(uid(request), body, key, canonical(body)));
    }

    @PutMapping("/tasks/{id}")
    public Result updateTask(HttpServletRequest request, @PathVariable("id") long id,
                             @RequestBody Requests.TaskUpdate body) {
        return Result.success(core.updateTask(uid(request), id, body));
    }

    @PostMapping("/tasks/{id}/completion")
    public Result completeTask(HttpServletRequest request, @PathVariable("id") long id,
                               @RequestBody Requests.Completion body) {
        return Result.success(core.completeTask(uid(request), id, body));
    }

    @DeleteMapping("/tasks/{id}")
    public Result deleteTask(HttpServletRequest request, @PathVariable("id") long id,
                             @RequestParam(value = "version", required = false) Long version) {
        core.deleteTask(uid(request), id, version);
        return Result.success(null);
    }

    @PostMapping("/tasks/{id}/steps")
    public Result addStep(HttpServletRequest request, @PathVariable("id") long id,
                          @Valid @RequestBody Requests.StepInput body) {
        return Result.success(core.addStep(uid(request), id, body));
    }

    @PutMapping("/tasks/{id}/steps/{stepId}")
    public Result updateStep(HttpServletRequest request, @PathVariable("id") long id,
                             @PathVariable("stepId") long stepId,
                             @RequestBody Requests.StepInput body) {
        return Result.success(core.updateStep(uid(request), id, stepId, body));
    }

    @PostMapping("/tasks/{id}/steps/{stepId}/completion")
    public Result completeStep(HttpServletRequest request, @PathVariable("id") long id,
                               @PathVariable("stepId") long stepId,
                               @RequestBody Requests.Completion body) {
        return Result.success(core.completeStep(uid(request), id, stepId, body));
    }

    @DeleteMapping("/tasks/{id}/steps/{stepId}")
    public Result deleteStep(HttpServletRequest request, @PathVariable("id") long id,
                             @PathVariable("stepId") long stepId) {
        return Result.success(core.deleteStep(uid(request), id, stepId));
    }

    @PostMapping("/tasks/{id}/time-blocks")
    public Result createTimeBlock(HttpServletRequest request, @PathVariable("id") long id,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @Valid @RequestBody Requests.TimeBlockInput body) {
        return Result.success(core.createTimeBlock(uid(request), id, body, key, canonical(body)));
    }

    // ---------- 日程 ----------

    @GetMapping("/calendar/events")
    public Result events(HttpServletRequest request,
                         @RequestParam(value = "start", required = false) String start,
                         @RequestParam(value = "end", required = false) String end,
                         @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(core.events(uid(request), start, end, limit));
    }

    @GetMapping("/calendar/events/{id}")
    public Result event(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.event(uid(request), id));
    }

    @PostMapping("/calendar/events")
    public Result createEvent(HttpServletRequest request,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                              @Valid @RequestBody Requests.EventInput body) {
        return Result.success(core.createEvent(uid(request), body, key, canonical(body)));
    }

    @PutMapping("/calendar/events/{id}")
    public Result updateEvent(HttpServletRequest request, @PathVariable("id") long id,
                              @Valid @RequestBody Requests.EventUpdate body) {
        return Result.success(core.updateEvent(uid(request), id, body));
    }

    @PostMapping("/calendar/events/{id}/completion")
    public Result completeEvent(HttpServletRequest request, @PathVariable("id") long id,
                                @RequestBody Requests.Completion body) {
        return Result.success(core.completeEvent(uid(request), id, body));
    }

    @DeleteMapping("/calendar/events/{id}")
    public Result deleteEvent(HttpServletRequest request, @PathVariable("id") long id,
                              @RequestParam(value = "version", required = false) Long version) {
        core.deleteEvent(uid(request), id, version);
        return Result.success(null);
    }

    // ---------- 消息与提取 ----------

    @GetMapping("/messages")
    public Result messages(HttpServletRequest request,
                           @RequestParam(value = "before", required = false) String before,
                           @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(core.messages(uid(request), before, limit));
    }

    @GetMapping("/messages/{id}")
    public Result message(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.message(uid(request), id));
    }

    @PostMapping("/messages/import")
    public Result importMessage(HttpServletRequest request, @Valid @RequestBody Requests.MessageInput body) {
        return Result.success(core.importMessage(uid(request), body));
    }

    @PostMapping("/messages/{id}/read")
    public Result readMessage(HttpServletRequest request, @PathVariable("id") long id) {
        return Result.success(core.readMessage(uid(request), id));
    }

    @PostMapping("/messages/{id}/drafts/task")
    public Result createDraft(HttpServletRequest request, @PathVariable("id") long id,
                              @RequestParam(value = "itemKey", required = false) String itemKey,
                              @RequestParam(value = "timezone", required = false) String timezone) {
        return Result.success(core.createDraft(uid(request), id, itemKey, timezone));
    }

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
