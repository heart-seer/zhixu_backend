package com.zhixu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:zhixu;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeAll
    void createSchema() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        Path path = Paths.get("database/schema.sql");
        if (!Files.exists(path)) {
            path = Paths.get("../database/schema.sql");
        }
        String sql = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        sql = sql.replaceAll("(?m)^\\s*--.*$", "");
        sql = sql.replaceAll("(?m)^\\s*SET .*;", "");
        sql = sql.replaceAll("(?m)^\\s*KEY [^,\\n]*,\\s*$", "");
        sql = sql.replaceAll("UNIQUE KEY [A-Za-z0-9_]+", "UNIQUE");
        sql = sql.replaceAll("(?i)\\s+COLLATE\\s*=?\\s*[A-Za-z0-9_]+", "");
        sql = sql.replaceAll("(?i)\\s+CHARACTER SET\\s+[A-Za-z0-9_]+", "");
        sql = sql.replaceAll("(?i)\\s+UNSIGNED", "");
        sql = sql.replaceAll("\\s+COMMENT='[^']*'", "");
        sql = sql.replaceAll("\\s+COMMENT '[^']*'", "");
        sql = sql.replaceAll("(?i)\\s+ENGINE=InnoDB", "");
        sql = sql.replaceAll("(?i)\\s+DEFAULT CHARSET=[A-Za-z0-9_]+", "");
        sql = sql.replaceAll("(?i)\\s+ON UPDATE CURRENT_TIMESTAMP\\(6\\)", "");
        sql = sql.replace("CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP");
        sql = sql.replace("DATETIME(6)", "TIMESTAMP(6)");
        sql = sql.replaceAll("(?i)MEDIUMTEXT", "CLOB");
        sql = sql.replaceAll("(?i)MEDIUMBLOB", "BLOB");
        sql = sql.replaceAll("(?i)\\bTEXT\\b", "CLOB");
        sql = sql.replaceAll("(?i)\\bJSON\\b", "CLOB");
        sql = sql.replaceAll("(?i)ENUM\\([^)]*\\)", "VARCHAR(32)");
        sql = sql.replaceAll("(?m)^\\s*CONSTRAINT ck_[^\\n]*$", "");
        sql = sql.replaceAll(",\\s*\\)", "\n)");
        for (String statement : sql.split(";")) {
            if (!statement.trim().isEmpty()) {
                jdbc.execute(statement);
            }
        }
    }

    // ---------- 认证与资料 ----------

    @Test
    void profileRoundTripAndDuplicateRegistration() throws Exception {
        String token = register("13800000001");
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"password\":\"secret123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.displayName").value("13800000001"));

        mvc.perform(put("/me").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"知行\",\"occupation\":\"产品经理\",\"currentGoal\":\"完成知序\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("知行"))
                .andExpect(jsonPath("$.data.occupation").value("产品经理"));

        mvc.perform(put("/me").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticationAndValidation() throws Exception {
        mvc.perform(get("/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/login/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000002\",\"password\":\"secret123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/tasks").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/code")).andExpect(status().isNotImplemented());
    }

    @Test
    void sessionRotationAndPasswordHash() throws Exception {
        register("13800000003");
        String refreshToken = lastRefreshToken;
        mvc.perform(post("/auth/login/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000003\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk());
        String access = lastAccessToken;
        refreshToken = lastRefreshToken;

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM auth_identities WHERE provider = 'phone' AND subject = ?",
                String.class, "13800000003");
        assertThat(hash).startsWith("$2");

        MvcResult refreshed = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String newAccess = data(refreshed).path("accessToken").asText();

        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", bearer(access)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", bearer(newAccess)))
                .andExpect(status().isOk());
    }

    @Test
    void preferencesRoundTripAndValidation() throws Exception {
        String token = register("13800000004");
        mvc.perform(get("/me/preferences").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezoneName").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.focusBlockSeconds").value(5400))
                .andExpect(jsonPath("$.data.remindersEnabled").value(true));

        mvc.perform(put("/me/preferences").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezoneName\":\"Asia/Tokyo\",\"appearance\":\"dark\","
                                + "\"schedulingMode\":\"balanced\",\"focusBlockSeconds\":1800,"
                                + "\"activeStart\":\"08:00:00\",\"activeEnd\":\"21:30:00\","
                                + "\"dndEnabled\":true,\"dndStart\":\"22:00:00\",\"dndEnd\":\"07:00:00\","
                                + "\"remindersEnabled\":false,\"reminderLeadSeconds\":600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezoneName").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.data.appearance").value("dark"))
                .andExpect(jsonPath("$.data.focusBlockSeconds").value(1800))
                .andExpect(jsonPath("$.data.activeStart").value("08:00:00"))
                .andExpect(jsonPath("$.data.remindersEnabled").value(false));

        assertThat(jdbc.queryForObject("SELECT timezone_name FROM user_preferences WHERE user_id = "
                + "(SELECT user_id FROM auth_identities WHERE subject = ?)",
                String.class, "13800000004")).isEqualTo("Asia/Tokyo");

        mvc.perform(put("/me/preferences").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appearance\":\"neon\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/preferences").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"timezoneName\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/preferences").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeStart\":\"09:00:00\",\"activeEnd\":\"09:00:00\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 任务 ----------

    @Test
    void taskIsolationIdempotencyAndVersion() throws Exception {
        String tokenA = register("13800000005");
        String tokenB = register("13800000006");

        MvcResult created = mvc.perform(post("/tasks").header("Authorization", bearer(tokenA))
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"写周报\",\"priority\":\"p1\",\"estimateSeconds\":1800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn();
        String taskId = data(created).path("id").asText();

        mvc.perform(post("/tasks").header("Authorization", bearer(tokenA))
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"写周报\",\"priority\":\"p1\",\"estimateSeconds\":1800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId));

        mvc.perform(post("/tasks").header("Authorization", bearer(tokenA))
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"内容不同的任务\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/tasks").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(get("/tasks/" + taskId).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());

        mvc.perform(put("/tasks/" + taskId).header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"写周报（改）\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.title").value("写周报（改）"));

        mvc.perform(put("/tasks/" + taskId).header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"过期版本\",\"version\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void multiBlockRangeAndIndependentCompletion() throws Exception {
        String token = register("13800000007");
        String taskId = createTask(token, "深度工作", null);
        String eventA = createTimeBlock(token, taskId, "2026-11-01T01:00:00Z", "2026-11-01T02:00:00Z");
        String eventB = createTimeBlock(token, taskId, "2026-11-01T03:00:00Z", "2026-11-01T04:00:00Z");

        mvc.perform(post("/tasks/" + taskId + "/time-blocks").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"2026-11-01T05:00:00Z\",\"endsAt\":\"2026-11-01T05:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/tasks/" + taskId + "/completion").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"));

        mvc.perform(get("/calendar/events").header("Authorization", bearer(token))
                        .param("start", "2026-11-01T00:00:00Z").param("end", "2026-11-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("completed"))
                .andExpect(jsonPath("$.data[1].status").value("completed"));

        assertThat(eventA).isNotEqualTo(eventB);

        String otherTask = createTask(token, "独立日程任务", null);
        String otherEvent = createTimeBlock(token, otherTask, "2026-11-03T01:00:00Z", "2026-11-03T02:00:00Z");
        mvc.perform(post("/calendar/events/" + otherEvent + "/completion").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"));
        mvc.perform(get("/tasks/" + otherTask).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("open"));
    }

    @Test
    void stepLifecycleReopensCompletedTask() throws Exception {
        String token = register("13800000008");
        MvcResult created = mvc.perform(post("/tasks").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"准备评审\",\"steps\":[{\"title\":\"收集材料\"},{\"title\":\"写提纲\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps.length()").value(2))
                .andReturn();
        String taskId = data(created).path("id").asText();
        String stepId = data(created).path("steps").get(0).path("id").asText();

        mvc.perform(put("/tasks/" + taskId + "/steps/" + stepId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"收集全部材料\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].title").value("收集全部材料"));

        mvc.perform(post("/tasks/" + taskId + "/steps/" + stepId + "/completion")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].completedAt").isNotEmpty());

        mvc.perform(post("/tasks/" + taskId + "/completion").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"));

        mvc.perform(post("/tasks/" + taskId + "/steps").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"补充说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("open"))
                .andExpect(jsonPath("$.data.steps.length()").value(3));

        mvc.perform(delete("/tasks/" + taskId + "/steps/" + stepId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps.length()").value(2));
    }

    // ---------- 消息与提取 ----------

    @Test
    void extractionIsIdempotentAndIsolated() throws Exception {
        String token = register("13800000009");
        String other = register("13800000010");

        MvcResult imported = mvc.perform(post("/messages/import").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"chaoxing\",\"externalMessageId\":\"ext-1\","
                                + "\"senderName\":\"学习通\",\"title\":\"第三章作业\","
                                + "\"preview\":\"请在周五前提交\",\"kind\":\"task\","
                                + "\"receivedAt\":\"2026-10-20T08:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn();
        String messageId = data(imported).path("id").asText();

        mvc.perform(post("/messages/import").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"chaoxing\",\"externalMessageId\":\"ext-1\","
                                + "\"senderName\":\"学习通\",\"title\":\"第三章作业\","
                                + "\"preview\":\"请在周五前提交\",\"kind\":\"task\","
                                + "\"receivedAt\":\"2026-10-21T08:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(messageId))
                .andExpect(jsonPath("$.data.duplicate").value(true));

        MvcResult draft = mvc.perform(post("/messages/" + messageId + "/drafts/task")
                        .header("Authorization", bearer(token))
                        .param("itemKey", "main").param("timezone", "Asia/Shanghai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemKey").value("main"))
                .andReturn();
        String draftId = data(draft).path("id").asText();

        MvcResult confirmed = mvc.perform(post("/extraction-drafts/" + draftId + "/confirm")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.title").value("第三章作业"))
                .andReturn();
        String taskId = data(confirmed).path("task").path("id").asText();

        mvc.perform(post("/extraction-drafts/" + draftId + "/confirm").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.id").value(taskId));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE source_message_id = ?",
                Integer.class, Long.parseLong(messageId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM messages WHERE id = ?", String.class,
                Long.parseLong(messageId))).isEqualTo("processed");
        assertThat(jdbc.queryForObject("SELECT status FROM extraction_drafts WHERE id = ?", String.class,
                Long.parseLong(draftId))).isEqualTo("confirmed");

        mvc.perform(get("/messages/" + messageId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedTaskId").value(taskId));
        mvc.perform(get("/messages").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(messageId))
                .andExpect(jsonPath("$.data[0].linkedTaskId").value(taskId));

        mvc.perform(post("/extraction-drafts/" + draftId + "/confirm").header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void messagesListReadAndIsolation() throws Exception {
        String token = register("13800000011");
        String other = register("13800000012");
        importMessage(token, "msg-a", "消息 A", "2026-10-18T08:00:00Z");
        importMessage(token, "msg-b", "消息 B", "2026-10-19T08:00:00Z");
        importMessage(token, "msg-c", "消息 C", "2026-10-20T08:00:00Z");

        MvcResult list = mvc.perform(get("/messages").header("Authorization", bearer(token))
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn();
        String newest = data(list).get(0).path("id").asText();

        mvc.perform(get("/messages").header("Authorization", bearer(token))
                        .param("before", "2026-10-19T08:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(post("/messages/" + newest + "/read").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());

        mvc.perform(get("/messages/" + newest).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentImportAndTaskCreationStayUnique() throws Exception {
        String token = register("13800000013");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> importCall = new Callable<MvcResult>() {
                @Override
                public MvcResult call() throws Exception {
                    return mvc.perform(post("/messages/import").header("Authorization", bearer(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"platform\":\"wecom\",\"externalMessageId\":\"conc-1\","
                                            + "\"senderName\":\"企业微信\",\"title\":\"并发消息\","
                                            + "\"preview\":\"同一条消息\",\"kind\":\"task\","
                                            + "\"receivedAt\":\"2026-10-21T09:00:00Z\"}"))
                            .andReturn();
                }
            };
            List<Future<MvcResult>> imports = new ArrayList<Future<MvcResult>>();
            imports.add(pool.submit(importCall));
            imports.add(pool.submit(importCall));
            String firstId = null;
            for (Future<MvcResult> future : imports) {
                MvcResult result = future.get();
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                String id = data(result).path("id").asText();
                if (firstId == null) {
                    firstId = id;
                } else {
                    assertThat(id).isEqualTo(firstId);
                }
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE external_message_id = 'conc-1'",
                    Integer.class)).isEqualTo(1);

            Callable<MvcResult> taskCall = new Callable<MvcResult>() {
                @Override
                public MvcResult call() throws Exception {
                    return mvc.perform(post("/tasks").header("Authorization", bearer(token))
                                    .header("Idempotency-Key", "conc-task")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"title\":\"并发任务\"}"))
                            .andReturn();
                }
            };
            List<Future<MvcResult>> tasks = new ArrayList<Future<MvcResult>>();
            tasks.add(pool.submit(taskCall));
            tasks.add(pool.submit(taskCall));
            String taskId = null;
            for (Future<MvcResult> future : tasks) {
                MvcResult result = future.get();
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                String id = data(result).path("id").asText();
                if (taskId == null) {
                    taskId = id;
                } else {
                    assertThat(id).isEqualTo(taskId);
                }
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE title = '并发任务'",
                    Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void apiDocsExposeContract() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("知序 APP 后端接口"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/tasks'].post.summary").value("创建任务"))
                .andExpect(jsonPath("$.paths['/tasks'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/auth/register'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/extraction-drafts/{id}/confirm'].post").exists());
    }

    // ---------- 辅助 ----------

    private String lastAccessToken;
    private String lastRefreshToken;

    private String register(String phone) throws Exception {
        MvcResult result = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = data(result);
        lastAccessToken = body.path("accessToken").asText();
        lastRefreshToken = body.path("refreshToken").asText();
        return lastAccessToken;
    }

    private String createTask(String token, String title, String body) throws Exception {
        MvcResult result = mvc.perform(post("/tasks").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "{\"title\":\"" + title + "\"}" : body))
                .andExpect(status().isOk()).andReturn();
        return data(result).path("id").asText();
    }

    private String createTimeBlock(String token, String taskId, String start, String end) throws Exception {
        MvcResult result = mvc.perform(post("/tasks/" + taskId + "/time-blocks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"" + start + "\",\"endsAt\":\"" + end + "\","
                                + "\"timezoneName\":\"Asia/Shanghai\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(result).path("id").asText();
    }

    private void importMessage(String token, String externalId, String title, String receivedAt) throws Exception {
        mvc.perform(post("/messages/import").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"personal\",\"externalMessageId\":\"" + externalId + "\","
                                + "\"senderName\":\"自己\",\"title\":\"" + title + "\","
                                + "\"preview\":\"摘要\",\"kind\":\"task\","
                                + "\"receivedAt\":\"" + receivedAt + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
