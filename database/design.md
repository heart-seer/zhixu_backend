# 知序 APP MySQL 数据库设计

## 1. 交付与范围

- `schema.sql`：28 张 InnoDB 表，面向 MySQL **8.0.16+**（依赖实际执行的 CHECK 约束），统一 utf8mb4。用于已选中的独立空数据库；不创建数据库，不 DROP 表，不创建示例账号，仅初始化 6 个固定来源字典。
- 本说明：实体关系、字段语义、服务端事务、Flutter 映射与实施边界。
- 覆盖账号认证、会话、档案、偏好、任务及子任务、日程、平台连接、消息附件、提取确认、通知、排程建议、专注统计、订阅权益以及可选团队与指派。
- **团队、成员、指派、计费/订阅为预留，不表示当前 UI 或后端已经实现。** 第三方登录、平台同步、真实 AI、系统推送也需要单独实现。没有把“知识库”“手环”“端侧密文计算”等设计文案误建为已存在业务。

### 当前代码事实

已检查 `lib/features/shared/domain_models.dart`、`local_repository.dart`、`data_providers.dart`，以及 profile、auth、网络和安全存储配置：

1. 任务、日程、消息来自 `LocalRepository.seeded()` 的内存示例；并非服务端持久化。
2. profile 的档案、38 项/42.5h/94% 周报、Pro、4 平台在线、缓存容量均为示例；偏好只是页面状态。
3. 登录注册已按手机号/验证码/密码发 HTTP 请求；`apiBaseUrl` 仍为 `https://api.example.com`。微信、Apple、SSO 仅说明弹窗。
4. 访问令牌保存在 `flutter_secure_storage`，请求带 Bearer；刷新令牌虽有存储接口，但现有登录代码没有完整刷新/轮换流程。退出仅清本地凭据，没有云端撤销。
5. 不修改 Flutter 源代码；本设计不是已经部署的后端。

## 2. 总体约定

### 标识、编码与字段

- 每表都有 `BIGINT UNSIGNED AUTO_INCREMENT` 内部主键 `id`，所有内部引用也使用该类型；API **将 ID 序列化为十进制字符串**，避免 Flutter Web/JavaScript 超过 2^53 后精度丢失。客户端临时 UUID 只作幂等键，不替代数据库主键；不得把现有 `task-...` 示例 ID 强转数字。
- 外部账号、消息、订阅、回调 ID 独立列保存；字典/操作编码用 ascii_bin，外部主体/标识用大小写敏感 utf8mb4_bin。正文/名称用 utf8mb4_0900_ai_ci；名称不作业务唯一键。来源由稳定 `code` 标识，中文名可改。
- `_at`、`starts_at/ends_at`、周期窗口等绝对时间使用 UTC `DATETIME(6)`。DATETIME 本身不转换时区，必须由后端转换，并在**每次连接**设置 `time_zone='+00:00'`；API 用含 `Z` 的 ISO 8601。
- `timezone_name` 存 IANA 时区；服务层校验合法性。偏好 `TIME` 是当地每日墙上时刻，不是 UTC。结束小于开始表示跨午夜，相等禁止。夏令时缺失/重复时刻须由服务层显式处理，不能依赖固定 UTC 偏移。
- 所有耗时、提前提醒、专注量统一**秒**；附件大小为字节。空估时表示未知；空提醒提前量表示不提醒，零表示开始时提醒。
- ENUM 是稳定业务代码，不是显示文案。修改枚举需显式迁移。`version` 是任务/日程乐观锁，更新必须在 WHERE 条件中校验并递增。
- JSON 仅用于可变提取证据和排程输入快照；有大小与保留期上限，不用于任务状态、权限、关联、密码、令牌。权益表是封闭的计量业务，不是任意 EAV。

### 隔离与权限边界（重要）

采用“用户私有数据 + 可选团队共享任务/日程”，而非一个混用 NULL 的租户键：

- 私有任务/日程：`team_id IS NULL AND owner_user_id = 当前用户`。
- 团队任务/日程：`team_id IS NOT NULL`，必须在 `team_members` 有 active 成员关系。owner/admin 可管理成员及指派；member 默认可读共享任务、修改指派给自己的完成状态，其他写权限按产品明确配置；非成员即使是原创建者也不能凭 owner 字段越权。
- 消息、连接、偏好、会话、专注记录、订阅均用户私有。团队成员不能自动读取创建者的原始消息、附件或外部凭据。分享由消息创建的任务时，明确征得同意，只暴露任务字段；API 不自动展开 `source_message_id` 的内容。
- 所有查询、修改、下载必须先验证当前用户及关联对象可见性；不能相信客户端传入的 `user_id`、`team_id`、外键或“已连接”状态。
- **本 SQL 使用普通外键，仅保证引用对象存在，不保证同一用户/团队。** 服务端必须在同一事务内检查：消息与连接的 user/platform 一致；任务来源消息为创建者所有；任务与日程的 owner/team 一致；确认草稿、目标和确认人为同用户且 target_kind 匹配；通知接收者可访问目标；专注记录操作者可访问目标；建议属于批次用户可排程任务；指派成员属于任务团队且 active。授权相关成员行需加锁，防止校验后退组的竞态。
- MySQL 无原生应用级行权限保障；ORM 全局过滤也不能替代以上校验。普通客户端绝不直连 MySQL。

## 3. 实体关系

```text
users 1--N auth_identities / auth_sessions
users 1--1 user_profiles / user_preferences
users 1--N teams（owner）；teams 1--N team_members N--1 users
source_platforms 1--N source_connections N--1 users
users 1--N messages；messages N--1 source_platforms / source_connections（可空）
messages 1--N message_attachments / extraction_drafts
users 1--N tasks；teams 1--N tasks（可选）
messages 1--N tasks（可选来源）；tasks 1--N task_steps
任务 1--N calendar_events（可为空，独立日程不关联任务）
extraction_drafts 1--0..1 extraction_confirmations --> task 或 event，严格二选一
users 1--N notifications --> task 或 event 或无目标的系统通知
users 1--N scheduling_batches 1--N scheduling_suggestions --> tasks
scheduling_suggestions --> 采纳后的 calendar_events（可选）
users 1--N focus_sessions --> tasks / calendar_events（可选）
tasks 1--N task_assignments N--1 team_members（可选团队能力）
subscription_plans N--N entitlements（通过 plan_entitlements）
users 1--N subscriptions N--1 subscription_plans
subscriptions 1--N billing_events
users 1--N api_idempotency_requests --> 创建结果 task / event
```

`auth_challenges` 可在注册前存在，不要求用户外键。所有建表引用都指向前面已建表，无需关闭外键、无循环 ALTER；确认结果后置，避免“消息先引用任务、任务又引用消息”的循环。

## 4. 表与关键字段说明

通用 `id`、`created_at`、`updated_at` 含义见总体约定，完整类型、NULL、索引、约束以 SQL 为准。

| # | 表 | 主要字段与规则 |
|---|---|---|
| 1 | users | status 控制可登录/禁用/注销待处理；last_login_at 仅成功登录后更新。 |
| 2 | auth_identities | user_id 指向账号；provider+issuer+subject 唯一，手机号先转 E.164。password_hash 为带盐/算法参数的 Argon2id 等哈希，仅本地身份可用；verified_at 记录已验证事实。 |
| 3 | auth_challenges | phone_e164+purpose 区分登录/注册/重置；code_mac 为带服务端密钥 HMAC，mac_key_version 指定密钥版本；expires_at、consumed_at、attempt_count/max_attempts 防重放与暴力尝试。 |
| 4 | auth_sessions | 高熵访问/刷新令牌各存唯一 SHA-256 摘要；access_expires_at、refresh_expires_at 分开；revoked_at 撤销整会话；device_name 为用户可见设备说明。 |
| 5 | user_profiles | user_id 唯一；display_name、alternate_name、occupation、current_goal 为真实档案；avatar_object_key 为私有头像对象键，不代表身份认证。 |
| 6 | user_preferences | user_id 唯一；时区、外观、排程模式、focus_block_seconds；active_start/end 是可工作窗口，dnd_start/end 独立表示勿扰窗口，配合 dnd_enabled；reminders_enabled 与提前量控制提醒。 |
| 7 | teams | owner_user_id 为最终所有者；name 是团队名，archived_at 归档。所有者也必须有 active owner 成员行，数据库不自动创建。 |
| 8 | team_members | team_id+user_id 唯一；role 与 status 分离；退组标为 left，重新加入复用该行。唯一 owner、所有者不能被直接移除由事务保证。 |
| 9 | source_platforms | code 唯一，display_name 可改；字典初始化个人、钉钉、企业微信、飞书、学习通、腾讯会议，不代表连接已建立。 |
| 10 | source_connections | user+platform+external_account_id 唯一，允许同平台多账号；external_account_id 包含外部组织命名空间；status、有效期、last_sync_at 为真实同步状态。密文封装及 KMS 版本成对存在；sync_cursor 不得夹带凭据。 |
| 11 | messages | user_id 为收件用户；ingest_key 每用户唯一；external_message_id 留存溯源；sender_name/title/preview/body 为原文信息；kind 分类，status 流转 pending/processed/ignored；read_at、deleted_at 独立。 |
| 12 | message_attachments | message_id+attachment_key 唯一；filename、media_type、size_bytes、object_key、可选内容摘要；scan_status 未 clean 不允许下载。 |
| 13 | tasks | owner_user_id、可空 team/source_message、platform_id；title/description/category_code、priority、status、estimate_seconds、due_at；completed_at 与 completed 状态一致；ai_candidate 只是候选标记；version/deleted_at 支持并发和删除。 |
| 14 | task_steps | task_id、title、position、completed_at；任务内 position 唯一，重排须事务内临时移至不冲突区再写新序号。仅单层步骤，不引入无限树。 |
| 15 | calendar_events | 可空 task_id 允许独立日程；同任务可有多个时间块；starts_at/ends_at 必须正区间；timezone_name 保留原时区；status/completed_at 与任务完成分开；reminder_lead_seconds、version、deleted_at。 |
| 16 | extraction_drafts | message_id+item_key 唯一；target_kind、拟定标题/描述/截止/时间区间/优先级/估时；evidence 为原文定位；extractor_version 记录人工、规则或实际模型；revision 用于编辑并发校验。 |
| 17 | extraction_confirmations | draft_id 非空唯一，最多确认一次；confirmed_by_user_id 是操作者；task_id/event_id 必须恰好一个非空；confirmed_at 是确认时间。 |
| 18 | notifications | user_id+dedupe_key 唯一；可关联 task 或 event，也可二者均空；scheduled_at 是原定时刻，next_attempt_at 为下次派送；lease_until 和 attempt_count 支持重试；sent_at/read_at 分离，错误只存脱敏码。 |
| 19 | scheduling_batches | user_id+request_key 唯一；window_start/end、engine_kind/version、input_snapshot、status、error_code、finished_at 记录一次真实排程执行。 |
| 20 | scheduling_suggestions | batch_id+slot_number 唯一；task_id、task_version、拟排区间及 explanation；status、decided_at、accepted_event_id 记录采纳，不把建议当日程。 |
| 21 | focus_sessions | user_id+client_session_key 唯一，支持补传；started_at/ended_at、elapsed_seconds 记录扣暂停后的真实时长；status 区分运行/完成/中止；可同时关联匹配的任务和日程。 |
| 22 | subscription_plans | code 唯一、name、active；改权益时发布新方案版本编码，避免直接重写历史订阅方案。 |
| 23 | entitlements | code 唯一；unit 是 boolean/count/seconds/bytes，不能混用单位。 |
| 24 | plan_entitlements | plan_id+entitlement_id 唯一；limit_value 和 reset_period 定义权益，布尔值只能零/一须服务校验；不使用负值代表无限。 |
| 25 | subscriptions | provider+external_subscription_id 非空唯一；user_id、plan_id、status、period_start/end、cancel_at_period_end；last_provider_event_at 辅助判断乱序，不是仅凭时间即可安全对账。 |
| 26 | billing_events | provider+external_event_id 唯一；subscription_id、event_type、provider_occurred_at、验签报文摘要与处理状态；不保存原始敏感支付报文。 |
| 27 | task_assignments | task_id+member_id 唯一；assigned_by_user_id、assigned_at；个人任务不允许指派，退组时须撤销对应指派。 |
| 28 | api_idempotency_requests | user+operation+request_key 非空唯一；request_hash 检测同键异请求；pending/completed、结果任务/日程及 expires_at；仅用于任务/日程创建，不保存认证响应。 |

## 5. 去重、唯一性与索引

- 不使用 `UNIQUE(user_id, nullable_team_id, ...)` 来声称全局去重。关键幂等唯一键的每个组成字段都 NOT NULL；可空外键只表达可选关联，没有错误依赖 NULL 唯一性。
- 消息 `ingest_key = SHA256(带长度或规范序列化的 [平台 code, 外部组织/账号稳定命名空间, 外部消息 ID])`。不能仅哈希正文，否则同文消息会误合并。手动导入使用客户端持久 UUID；重连复用原 connection 及来源命名空间。输入冲突时复核原标识；不悄悄覆盖来源正文。
- 草稿 `item_key` 在同消息重跑中稳定，不能每次用随机 UUID 或模型随机输出。首次拆分生成并持久保存，后续运行对已有条目匹配，新增条目需明确分配新键；已确认项不得通过重跑换键再次确认。
- 任务/日程创建使用 `api_idempotency_requests`；来源消息确认还受 draft_id 唯一约束保护，即使 HTTP 幂等缓存过期也不会重复提取。
- 主要列表索引：用户任务/团队任务按删除、状态、截止时间；消息按用户、删除、处理状态、收件时间；日程按个人/团队、开始时间；完成统计按用户和 completed_at；专注按用户和 started_at。
- 日程跨天查询：先将当地日界线转换 UTC，再 `starts_at < :day_end AND ends_at > :day_start AND deleted_at IS NULL AND status <> 'cancelled'`。B-tree 只能主要利用一个时间范围，ends_at 为残余过滤；上线后用 EXPLAIN 验证，不声称双范围索引可完美消除扫描。
- 团队/个人查询建议分别授权查询后 UNION ALL，避免无权限的 owner 条件与 team 条件混合；列表用 `(时间,id)` 游标，不依赖大 OFFSET。派送按 status/next_attempt_at/lease_until；所有 FK 子列已显式提供前导索引。

## 6. 建议 API 与关键事务

下列为**后端建议契约**，不是现有接口都已存在。事务死锁需有限重试，所有 SQL 参数化，异常响应脱敏。

### 6.1 验证码、注册、登录与退出

- 兼容现有 `/auth/code`、`/auth/register`、`/auth/login/code`、密码登录路径常量。验证码发送前按手机号/IP/设备限频，前端倒计时不构成安全限制。短信供应商在数据库之外接入。
- 验证时锁定指定挑战行，校验 purpose、有效期、未消费和尝试次数；失败增加计数后**提交**，不能回滚掉失败计数；成功标 consumed_at，同事务创建/绑定身份。注册依靠身份唯一键防并发重复，创建 users/profile/preferences/session 一并提交。验证码 challenge 标识建议随发送响应返回，避免“查询最新一条”的歧义。
- 密码用经调优的 Argon2id 等验证，认证失败返回统一信息并限速。新用户必须经验证码认证；禁止仅凭手机号新增第三方绑定。
- 本方案选择高熵不透明访问/刷新令牌：仅明文返回客户端一次，数据库存摘要；访问查询会话时检查账号 active、访问有效期和 revoked_at。
- 刷新时 `SELECT ... FOR UPDATE` 锁会话并原子替换两种摘要；并发旧刷新请求仅一个成功。若需刷新重用告警/令牌族审计，后续独立迁移，当前不能声称已实现。
- 建议响应保留现有读取方式 `data.token`，增加 `refreshToken` 与过期时间；客户端之后再接入刷新存储及流程。
- 建议 `POST /auth/logout` 将当前 session.revoked_at 写入；全设备退出撤销用户全部会话，再清本地存储。不要只做本地删除就宣称云端退出成功。

### 6.2 任务、步骤、日程

- 建议 `POST /tasks`、`POST /calendar/events` 带 `Idempotency-Key`。先在事务插入幂等行或锁已有行，比较规范化请求摘要；相同正文返回已有资源，不同正文返回 409；创建目标后保存结果并一并提交，不提交无结果 pending 行。客户端必须持久化同一次操作的键。
- 幂等缓存建议保留 7 天；过期后不承诺普通创建仍不重复，离线客户端应先对账再重发。提取结果/建议采纳有独立永久对象唯一性约束。
- 步骤修改、任务完成/重开：锁任务，再锁步骤，检查 version。保持当前“有步骤时全部完成则完成任务，新增未完成步骤则重开；整任务完成/重开可批量设置步骤”的语义；无步骤由任务状态直接控制。同步写 completed_at、version。
- **不复制当前单 `scheduledEventId` 限制。** `POST /tasks/{id}/time-blocks` 可以创建多个 calendar_events，不对 task_id 加唯一约束。若产品需要同一任务不能重叠，由事务锁任务后查询重叠区间再插入。
- 建议区分任务完成与时间块完成：任务是最终交付事实，日程是一次安排/执行事实，修改一个时间块不自动完成整个多块任务。若产品选择“完成任务后同步剩余块”，需显式产品规则和同事务更新，不能保留旧单块联动代码就认为正确。
- 任务删除建议软删除并在同事务软删除/取消其未完成时间块、取消待发通知、使待采纳建议 stale；取消/延期不覆盖历史专注记录。日程改期应 version++，取消旧通知、创建新去重版本通知。
- calendar_events.description 为真实说明；旧 subtitle 中拼接的来源/组织文案应由可见业务数据合成，不从文字反向拆库。

### 6.3 消息同步与提取确认

1. 同步以 ingest_key 插入消息，重复键返回已有消息；只有该批消息成功提交后才能推进 connection.sync_cursor，否则会丢消息。失败不更新 last_sync_at；保存脱敏错误码，不伪造已连接。
2. 提取器生成/更新稳定 item_key 草稿，记录真实 extractor_version。人工/规则可以使用该表，不必接入模型。已确认草稿不可重写；待确认更新 revision++。
3. 建议 `POST /extraction-drafts/{id}/confirm`：锁消息、草稿，检查当前用户、revision、未删除、草稿种类；查询已有 confirmation，存在则直接返回原结果；否则创建任务或日程，插入 confirmation，再标草稿 confirmed。全部同事务。
4. 草稿/确认任务来源消息、确认人和目标权限必须一致；CHECK 仅二选一，种类及状态一致性仍须服务校验。唯一键竞争失败时回滚整事务并读取赢家结果，不能留下孤立任务。
5. 一消息可有多草稿；当所有可处理草稿已 confirmed/rejected 后才设 processed。pending 数以 messages.status 计算；ignored 是用户忽略，与 read_at 互不替代。
6. “全部确认”按消息逐事务，明确报告部分成功，不用一个无限大事务。无草稿不默认消息已处理。
7. 不经提取直接关联已有日程时，可创建人工 calendar 草稿并确认到授权可见的既有日程；确认接口须区分“新建”与“关联”，并写相同关联结果。个人消息原文仍不向团队泄漏。

### 6.4 排程、提醒、专注

- 批次按 user+request_key 去重，快照记录任务版本及偏好；engine_kind=rule 就明确是规则，不可写成 model。`input_snapshot` 只含必要内容，敏感原消息未经同意不发第三方模型。
- 建议采纳事务：先锁用户偏好行作为该用户排程互斥锁（其他人工排程也遵守），再按固定顺序锁任务/建议，检查任务权限、version、状态和当前时间冲突；过期标 stale。创建日程并更新建议 accepted/accepted_event_id 同事务提交；重复采纳返回已有日程。团队共享资源需再按固定顺序锁团队行。MySQL 没有时间区间排斥约束，单查重不加锁存在竞态。
- 创建/改期日程时同事务生成 notifications 作为发件箱。派送工作器用 `FOR UPDATE SKIP LOCKED` 领取并提交租约，再调用推送服务，随后写结果；过期租约可回收。不得长时间持有数据库锁调用外部接口。推送 token 另接安全设备注册服务，当前 schema 不宣称已能真实推送。
- 通知尊重用户提醒/勿扰偏好；dedupe_key 含对象版本、渠道和接收人。外部派送成功后崩溃仍可能重试，因此是至少一次语义，需传稳定供应商幂等键及客户端消息 ID 去重，不声称绝对只发送一次。
- 专注开始/结束按 client_session_key 去重，锁记录检查状态和时间，有效秒数不得超出墙上间隔。客户端报数不可信，正式权益计量需服务时间/心跳审计。
- 周报从真实任务 completed_at、focus_sessions 与排程采纳记录计算，不建假数字表。按用户时区定义周区间；专注跨周按时间窗口合理分摊，不能简单全部算到开始周。AI 采纳率只统计 engine_kind=model 的成功批次建议，明确 accepted/(accepted+rejected)，pending/stale 不计，分母为零显示暂无数据；不把规则采纳标 AI。现有任务只保存最近 completed_at，反复完成/重开不提供不可变历史，若将来需要审计型周报需追加任务状态事件表迁移，不能用当前表声称可还原任意历史。

### 6.5 团队与计费预留

- 创建团队与 owner 成员行同事务；转移所有者时锁团队和相关成员，更新 owner_user_id 与两人角色；移除成员时撤销指派，并验证不是最后所有者。归档团队后禁止新增排程/指派。
- 订阅必须来自验签并服务端核验的供应商状态；provider 及外部订阅号必须与 billing_event 一致。回调先校验签名，锁订阅，用 external_event_id 唯一键去重；乱序事件结合供应商版本或主动查询权威状态判断，不能仅按到达时间覆盖；修改订阅和标 applied 同事务。未知订阅先查询供应商并解析绑定用户，不盲信回调自带 user_id。
- 不存卡号、CVV、支付密钥、完整敏感收据/回调；不建设本地收银台。状态为 active 且 UTC 当前时间处于 `[period_start,period_end)` 才获得权益，cancel_at_period_end 不等于立即失效。
- 当前预留个人订阅，团队座位计费未设计成已可用。方案权益只描述额度，**没有实时用量账本或完整订单/退款/发票系统**；实际收费与强制扣额度前必须另做明确迁移及审计设计。多个 active 订阅的互斥/升级由用户行锁与供应商规则解决，不能靠含 nullable 列的唯一索引解决。

## 7. Flutter 字段映射与必要差异

| 当前字段/行为 | 数据库与后续客户端调整 |
|---|---|
| Task.id、TaskStep.id、CalendarEvent.id、Message.id | API 字符串化内部 ID；steps 使用真正稳定 ID，不能每次按数组下标重建。 |
| Task.platform / Message.source | 来源 code/id 关联 source_platforms，API 可返回 displayName；中文名不作关联键。 |
| Task.group | “即将截止/P0”“今日进行中”“本周待办列表”是优先级/期限/当前时间计算的视图，不原样建 group 列；真实分类使用 category_code。 |
| Task.priority、estimate、description、dueAt | priority、estimate_seconds、description、due_at；Duration 按秒往返。 |
| Task.completed / TaskStep.completed | tasks.status/completed_at、task_steps.completed_at；API 可给兼容布尔值。 |
| Task.sourceMessageId | tasks.source_message_id；消息存在不代表请求者有权读取原文。 |
| Task.scheduledEventId / scheduled | 不再单一字段；通过 calendar_events.task_id 返回 timeBlocks 列表，scheduled 从有效时间块推导。 |
| CalendarEvent.start/end/subtitle/reminder/completed | starts_at/ends_at、真实 description、reminder_lead_seconds、独立 status；subtitle 由 API/展示层合成。 |
| Message.highlight/detail/category/footer/taskDueAt | highlight 只可来自 evidence 引用；截止时间/分类来自草稿结构化字段；“截止时间:周五22:00”“本地示例”等不原样作为权威业务字段。 |
| Message.linkedTaskId / linkedEventId | extraction_drafts + confirmations 返回多个结果列表，不只一个链接。旧单链接 API 仅可临时取一个，不能无提示丢弃其余结果。 |
| Message.read/status | read_at 非空、messages.status；新增 ignored 时客户端需支持或显式映射。 |
| confirmMessageExtraction / confirmAllMessages | 改为服务端确认事务，不能由客户端拼 `task-from-$messageId` 当数据库主键。 |
| profile 的 focus/appearance/time/reminders | user_preferences；页面状态需加载/保存 API，作息和勿扰不再混为同一字段。 |
| 档案、Pro、平台在线、周报 | 分别来自 profile、已验证订阅、连接状态、真实聚合；无数据时显示未连接/暂无数据，不写入本地示例数字。 |

建议分阶段：先实现账号/个人任务/日程/消息 API 与 RemoteRepository，替换 seeded provider；再持久化档案偏好；最后实际接入来源、提醒、排程和可选团队/计费。Flutter 通过 HTTPS 后端访问 MySQL，当前项目没有数据库驱动依赖，也不需要给移动端添加 MySQL 凭据。

## 8. 删除、保留与运行安全

- 全部 FK 使用默认 RESTRICT/NO ACTION，不级联删除、不自动 SET NULL。账号注销先禁用并撤销会话、连接授权；业务任务/日程/消息软删。软删除不会绕开 FK，也不会自动删除对象存储文件。
- 团队归档先停止写入，不能直接删 owner；用户若持有团队必须转移或归档并确定保留政策。
- 硬清除须专用管理作业按子到父顺序执行：通知、专注、幂等缓存、建议→批次、确认→草稿、指派、步骤、日程→任务、附件→消息→连接，再清档案/身份/会话等；成员/团队与订阅/计费分支也须先子后父。确认清除引用后才能删来源消息。跨用户团队记录要先移交或按政策匿名化，不可直接批删用户全部引用。
- 附件/头像私有存储、短时签名 URL、每次鉴权、扫描完成才下载；删除对象采用可重试异步作业，防止数据库事务失败造成不可恢复的数据丢失。
- 建议验证码过期后最多保留 24 小时，会话过期后保留短期审计窗口，幂等缓存 7 天，模型输入快照/证据按最小必要期限定期清理；这些是建议策略，不是脚本已经创建了自动清理器。计费事件遵守法定保留期与隐私政策。
- 密码只存不可逆哈希；会话令牌只存摘要；确需再次使用的第三方 token 只保存 AEAD 密文，KMS 密钥在数据库之外，随机 nonce/认证标签封装在密文中。明文令牌/密码/验证码不写库、不记录日志、不出现在错误响应。
- 手机号和主体 ID 是个人信息，不是认证秘密，当前为最小化查询字段；必须数据库磁盘/备份加密、TLS、访问审计、API 脱敏。若法规要求字段级加密手机号，需专门迁移为加密值+带密钥检索摘要，不能直接以随机密文替换唯一 subject。
- 应用账号仅必要 DML 权限，凭据/计费表按服务拆分授权；迁移账号单独拥有 DDL，运行期不授予 DROP/ALTER/GRANT/FILE。数据库端口不公开，连接密钥走服务端密钥管理，绝不放 Flutter 配置。

## 9. 初始化、迁移与验证记录

### 使用方法

1. 由管理员准备独立空数据库、设置备份与最小权限；选择数据库。
2. 使用安全的客户端登录或组织管理的凭据配置执行 `database/schema.sql`（例如 MySQL 客户端选库后 `SOURCE D:/Projects/Trae/Flutter/Dart/database/schema.sql;`）。不要把密码写在命令行、SQL 或 Flutter 中。
3. 本脚本是首次迁移，不是可重复部署脚本；**没有 IF NOT EXISTS 是为了避免掩盖结构漂移**。DDL 隐式提交，整体不能保证事务回滚；失败须检查已创建对象后由迁移工具恢复，不应直接重跑或随意 DROP 用户数据。
4. 后续使用版本化迁移（框架选型后 Flyway/Liquibase/对应框架均可），线上先备份、在相同 MySQL 版本的空库/影子库验证；大表索引和 ALTER 评估锁、在线 DDL 与回滚方案。

### 已完成验证及限制

- 静态核对 **28 张表、47 个外键**；引用表先创建，主/外键 BIGINT UNSIGNED 类型一致，无循环依赖；每个 FK 子列都有前导索引。
- 核对唯一键均有明确业务范围，提取/同步/确认/排程/回调去重不依赖 NULL 唯一行为；任务对日程是一对多；时间区间与完成状态有 CHECK；CHECK 使用目标版本支持的基础表达式。
- 已核对初始化仅写来源字典，不包含 DROP、CREATE DATABASE、示例用户或明文认证数据。
- 本机发现 **MySQL 客户端 8.0.45**；尝试以本机默认端口无密码连接仅用于只读版本检查，返回 **ERROR 1045：Access denied**。没有可用数据库认证，**未执行建表、未完成 MySQL 运行时语法/约束验证**；也未安装、初始化或启动新服务，未读取/猜测现有密码。
- 有权限后待执行：在专用测试空库运行全部 SQL，核验 INFORMATION_SCHEMA 表/外键/索引；测试负时间块、确认双目标、重复提取确认、同键异正文、验证码并发消费、跨用户/跨团队关联、并发采纳与时间冲突、退组竞态、通知重试、乱序回调，以及 EXPLAIN 常用列表查询。服务层隔离测试尤其不可用 FK 测试替代。
