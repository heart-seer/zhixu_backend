-- 知序 APP 数据库初始结构；MySQL 8.0.16+，请先选中独立的空数据库。
-- 不创建数据库、不删除表、不写入用户数据；仅首次迁移执行，非重复执行脚本。
-- 所有绝对时间均为 UTC；每个应用连接也必须设置 time_zone = '+00:00'。
-- 外键仅校验存在性，不自动保证同用户/同团队；服务层必须在事务中校验权限。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET time_zone = '+00:00';

CREATE TABLE users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部用户标识，API 使用十进制字符串',
  status ENUM('active','disabled','deletion_pending') NOT NULL DEFAULT 'active' COMMENT '账号状态',
  last_login_at DATETIME(6) NULL COMMENT '最后成功登录 UTC 时间',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号主体';

CREATE TABLE auth_identities (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'phone/apple/wechat/sso 等稳定编码',
  issuer VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '签发方命名空间；手机号固定为 local',
  subject VARCHAR(191) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化手机号 E.164 或供应商主体标识，不是令牌',
  password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '仅本地密码使用 Argon2id 等带参数哈希，禁止明文',
  verified_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_identity_subject (provider, issuer, subject),
  KEY idx_identity_user (user_id),
  CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录身份；第三方登录为预留';

CREATE TABLE auth_challenges (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  phone_e164 VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose ENUM('login','register','reset_password') NOT NULL,
  code_mac BINARY(32) NOT NULL COMMENT '服务端密钥 HMAC-SHA256，短验证码不可只做无密钥哈希',
  mac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts SMALLINT UNSIGNED NOT NULL DEFAULT 5,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_challenge_phone (phone_e164, purpose, created_at),
  KEY idx_challenge_expiry (expires_at),
  CONSTRAINT ck_challenge_attempts CHECK (max_attempts > 0 AND attempt_count <= max_attempts),
  CONSTRAINT ck_challenge_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短信验证挑战；限频仍需服务层实现';

CREATE TABLE auth_sessions (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  access_token_hash BINARY(32) NOT NULL COMMENT '高熵不透明访问令牌的 SHA-256 摘要',
  refresh_token_hash BINARY(32) NOT NULL COMMENT '高熵刷新令牌摘要；轮换后替换',
  device_name VARCHAR(128) NULL,
  access_expires_at DATETIME(6) NOT NULL,
  refresh_expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_session_access (access_token_hash),
  UNIQUE KEY uq_session_refresh (refresh_token_hash),
  KEY idx_session_user (user_id, revoked_at),
  KEY idx_session_expiry (refresh_expires_at),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_session_expiry CHECK (refresh_expires_at >= access_expires_at AND access_expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可撤销登录会话；不存原始令牌';

CREATE TABLE user_profiles (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  alternate_name VARCHAR(100) NULL,
  occupation VARCHAR(150) NULL,
  current_goal VARCHAR(255) NULL,
  avatar_object_key VARCHAR(512) NULL COMMENT '私有对象存储键，不保存临时签名 URL',
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_profile_user (user_id),
  CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人档案';

CREATE TABLE user_preferences (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  timezone_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区，服务层校验合法性',
  appearance ENUM('light','dark','system') NOT NULL DEFAULT 'system',
  scheduling_mode ENUM('deep_focus','balanced') NOT NULL DEFAULT 'deep_focus',
  focus_block_seconds INT UNSIGNED NOT NULL DEFAULT 5400,
  active_start TIME NOT NULL DEFAULT '08:30:00' COMMENT '当地每日可排程开始时间',
  active_end TIME NOT NULL DEFAULT '22:30:00' COMMENT '小于开始时间表示跨午夜；相等不合法',
  dnd_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  dnd_start TIME NOT NULL DEFAULT '22:30:00',
  dnd_end TIME NOT NULL DEFAULT '08:30:00',
  reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  reminder_lead_seconds INT UNSIGNED NOT NULL DEFAULT 900,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_preferences_user (user_id),
  CONSTRAINT fk_preferences_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_preferences_focus CHECK (focus_block_seconds BETWEEN 60 AND 86400),
  CONSTRAINT ck_preferences_window CHECK (active_start >= '00:00:00' AND active_start < '24:00:00' AND active_end >= '00:00:00' AND active_end < '24:00:00' AND active_start <> active_end),
  CONSTRAINT ck_preferences_dnd CHECK (dnd_start >= '00:00:00' AND dnd_start < '24:00:00' AND dnd_end >= '00:00:00' AND dnd_end < '24:00:00' AND dnd_start <> dnd_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='类型化偏好，非万能配置表';

CREATE TABLE teams (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(150) NOT NULL,
  archived_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_team_owner (owner_user_id),
  CONSTRAINT fk_team_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='团队预留，当前 UI 未实现';

CREATE TABLE team_members (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role ENUM('owner','admin','member') NOT NULL DEFAULT 'member',
  status ENUM('active','left') NOT NULL DEFAULT 'active',
  joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_team_member (team_id, user_id),
  KEY idx_member_user (user_id, status),
  CONSTRAINT fk_member_team FOREIGN KEY (team_id) REFERENCES teams(id),
  CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='团队成员预留，角色权限由服务校验';

CREATE TABLE source_platforms (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_platform_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='来源字典，不代表已接入或支持授权';

CREATE TABLE source_connections (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  platform_id BIGINT UNSIGNED NOT NULL,
  external_account_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL COMMENT '包含外部组织命名空间的账号标识',
  status ENUM('pending','connected','expired','revoked','error') NOT NULL DEFAULT 'pending',
  credential_ciphertext MEDIUMBLOB NULL COMMENT '需要调用外部 API 时保存 AEAD 密文封装，含随机 nonce 和认证标签',
  credential_key_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '外部 KMS 密钥版本，密钥不入库',
  credential_expires_at DATETIME(6) NULL,
  sync_cursor TEXT NULL COMMENT '同步游标；不得混入访问凭据',
  last_sync_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_connection_account (user_id, platform_id, external_account_id),
  KEY idx_connection_platform (platform_id),
  CONSTRAINT fk_connection_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_connection_platform FOREIGN KEY (platform_id) REFERENCES source_platforms(id),
  CONSTRAINT ck_connection_cipher CHECK ((credential_ciphertext IS NULL AND credential_key_version IS NULL) OR (credential_ciphertext IS NOT NULL AND credential_key_version IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户私有来源连接，重新授权复用同一行';

CREATE TABLE messages (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  platform_id BIGINT UNSIGNED NOT NULL,
  connection_id BIGINT UNSIGNED NULL COMMENT '手动导入可为空；与平台和用户一致性由事务校验',
  ingest_key BINARY(32) NOT NULL COMMENT '服务端按来源命名空间及外部消息 ID 计算的 SHA-256 去重键',
  external_message_id VARCHAR(255) COLLATE utf8mb4_bin NULL COMMENT '外部平台原始标识，不作本地主键',
  sender_name VARCHAR(150) NOT NULL,
  title VARCHAR(255) NOT NULL,
  preview TEXT NOT NULL,
  body MEDIUMTEXT NULL,
  kind ENUM('task','calendar','attachment') NOT NULL,
  status ENUM('pending','processed','ignored') NOT NULL DEFAULT 'pending',
  received_at DATETIME(6) NOT NULL,
  read_at DATETIME(6) NULL,
  deleted_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_message_ingest (user_id, ingest_key),
  KEY idx_message_inbox (user_id, deleted_at, status, received_at, id),
  KEY idx_message_platform (platform_id),
  KEY idx_message_connection (connection_id),
  CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_message_platform FOREIGN KEY (platform_id) REFERENCES source_platforms(id),
  CONSTRAINT fk_message_connection FOREIGN KEY (connection_id) REFERENCES source_connections(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有消息收件箱';

CREATE TABLE message_attachments (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id BIGINT UNSIGNED NOT NULL,
  attachment_key BINARY(32) NOT NULL COMMENT '来源附件标识摘要或本地上传 UUID 摘要',
  filename VARCHAR(255) NOT NULL,
  media_type VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  object_key VARCHAR(512) NOT NULL COMMENT '私有对象存储键，不在数据库存文件二进制或公开下载地址',
  content_sha256 BINARY(32) NULL,
  scan_status ENUM('pending','clean','blocked') NOT NULL DEFAULT 'pending',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_attachment_source (message_id, attachment_key),
  CONSTRAINT fk_attachment_message FOREIGN KEY (message_id) REFERENCES messages(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息附件元数据';

CREATE TABLE tasks (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '创建及个人归属用户，团队任务亦保留创建者',
  team_id BIGINT UNSIGNED NULL COMMENT '空表示个人任务，非空表示团队共享任务',
  source_message_id BIGINT UNSIGNED NULL,
  platform_id BIGINT UNSIGNED NOT NULL COMMENT '手建任务使用 personal 平台',
  title VARCHAR(255) NOT NULL,
  description TEXT NULL,
  category_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '明确业务分类编码，不存即将截止等动态展示组名',
  priority ENUM('p0','p1','p2','normal') NOT NULL DEFAULT 'normal',
  status ENUM('open','completed','cancelled') NOT NULL DEFAULT 'open',
  estimate_seconds INT UNSIGNED NULL COMMENT '空表示未估时，单位秒',
  due_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  ai_candidate BOOLEAN NOT NULL DEFAULT FALSE COMMENT '候选标记，不代表实际调用 AI',
  version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  deleted_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_task_personal (owner_user_id, team_id, deleted_at, status, due_at, id),
  KEY idx_task_team (team_id, deleted_at, status, due_at, id),
  KEY idx_task_source (source_message_id),
  KEY idx_task_platform (platform_id),
  KEY idx_task_completed (owner_user_id, completed_at),
  CONSTRAINT fk_task_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
  CONSTRAINT fk_task_team FOREIGN KEY (team_id) REFERENCES teams(id),
  CONSTRAINT fk_task_message FOREIGN KEY (source_message_id) REFERENCES messages(id),
  CONSTRAINT fk_task_platform FOREIGN KEY (platform_id) REFERENCES source_platforms(id),
  CONSTRAINT ck_task_estimate CHECK (estimate_seconds IS NULL OR estimate_seconds > 0),
  CONSTRAINT ck_task_completed CHECK ((status = 'completed' AND completed_at IS NOT NULL) OR (status <> 'completed' AND completed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务聚合根，支持软删除';

CREATE TABLE task_steps (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(255) NOT NULL,
  position INT UNSIGNED NOT NULL COMMENT '从零开始的排序，允许间隔',
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_step_position (task_id, position),
  CONSTRAINT fk_step_task FOREIGN KEY (task_id) REFERENCES tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单层子任务，与 Flutter TaskStep 对应';

CREATE TABLE calendar_events (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  team_id BIGINT UNSIGNED NULL,
  task_id BIGINT UNSIGNED NULL COMMENT '一个任务可以对应多个时间块，不加唯一约束',
  title VARCHAR(255) NOT NULL,
  description TEXT NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NOT NULL,
  timezone_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建时 IANA 时区用于解释和展示',
  status ENUM('planned','completed','cancelled') NOT NULL DEFAULT 'planned',
  completed_at DATETIME(6) NULL,
  reminder_lead_seconds INT UNSIGNED NULL COMMENT '空表示不提醒；零表示开始时提醒',
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  deleted_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_event_personal (owner_user_id, team_id, deleted_at, starts_at, ends_at),
  KEY idx_event_team (team_id, deleted_at, starts_at, ends_at),
  KEY idx_event_task (task_id, starts_at),
  CONSTRAINT fk_event_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
  CONSTRAINT fk_event_team FOREIGN KEY (team_id) REFERENCES teams(id),
  CONSTRAINT fk_event_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT ck_event_range CHECK (ends_at > starts_at),
  CONSTRAINT ck_event_completed CHECK ((status = 'completed' AND completed_at IS NOT NULL) OR (status <> 'completed' AND completed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日程与任务时间块，一任务多日程';

CREATE TABLE extraction_drafts (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id BIGINT UNSIGNED NOT NULL,
  item_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同一消息中稳定条目标识，重跑时必须复用',
  target_kind ENUM('task','calendar') NOT NULL,
  title VARCHAR(255) NOT NULL,
  description TEXT NULL,
  proposed_due_at DATETIME(6) NULL,
  proposed_start_at DATETIME(6) NULL,
  proposed_end_at DATETIME(6) NULL,
  proposed_priority ENUM('p0','p1','p2','normal') NOT NULL DEFAULT 'normal',
  proposed_estimate_seconds INT UNSIGNED NULL,
  timezone_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence JSON NULL COMMENT '引用原文位置等可变证据，不存凭据或权威业务状态',
  extractor_version VARCHAR(100) NOT NULL COMMENT '人工/规则/模型及版本；不得伪称真实 AI',
  status ENUM('pending','confirmed','rejected') NOT NULL DEFAULT 'pending',
  revision INT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_draft_item (message_id, item_key),
  CONSTRAINT fk_draft_message FOREIGN KEY (message_id) REFERENCES messages(id),
  CONSTRAINT ck_draft_estimate CHECK (proposed_estimate_seconds IS NULL OR proposed_estimate_seconds > 0),
  CONSTRAINT ck_draft_calendar CHECK (target_kind <> 'calendar' OR (proposed_start_at IS NOT NULL AND proposed_end_at IS NOT NULL AND proposed_end_at > proposed_start_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可编辑提取草稿，每条消息可提取多项';

CREATE TABLE extraction_confirmations (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  draft_id BIGINT UNSIGNED NOT NULL,
  confirmed_by_user_id BIGINT UNSIGNED NOT NULL,
  task_id BIGINT UNSIGNED NULL,
  event_id BIGINT UNSIGNED NULL,
  confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_confirmation_draft (draft_id),
  KEY idx_confirmation_user (confirmed_by_user_id),
  KEY idx_confirmation_task (task_id),
  KEY idx_confirmation_event (event_id),
  CONSTRAINT fk_confirmation_draft FOREIGN KEY (draft_id) REFERENCES extraction_drafts(id),
  CONSTRAINT fk_confirmation_user FOREIGN KEY (confirmed_by_user_id) REFERENCES users(id),
  CONSTRAINT fk_confirmation_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_confirmation_event FOREIGN KEY (event_id) REFERENCES calendar_events(id),
  CONSTRAINT ck_confirmation_target CHECK ((task_id IS NOT NULL AND event_id IS NULL) OR (task_id IS NULL AND event_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提取确认结果，草稿唯一约束保证重复确认不重复建项';

CREATE TABLE notifications (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  task_id BIGINT UNSIGNED NULL,
  event_id BIGINT UNSIGNED NULL,
  dedupe_key BINARY(32) NOT NULL COMMENT '按收件人、对象、规则、发生版本及渠道生成摘要',
  channel ENUM('in_app','push') NOT NULL DEFAULT 'in_app',
  title VARCHAR(255) NOT NULL,
  body TEXT NULL,
  scheduled_at DATETIME(6) NOT NULL,
  status ENUM('pending','sending','sent','failed','cancelled') NOT NULL DEFAULT 'pending',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  lease_until DATETIME(6) NULL COMMENT '派送工作器租约，过期可重试',
  next_attempt_at DATETIME(6) NOT NULL,
  sent_at DATETIME(6) NULL,
  read_at DATETIME(6) NULL,
  last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '脱敏错误码，不存包含凭据的原始响应',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_notification_dedupe (user_id, dedupe_key),
  KEY idx_notification_dispatch (status, next_attempt_at, lease_until),
  KEY idx_notification_inbox (user_id, read_at, created_at),
  KEY idx_notification_task (task_id),
  KEY idx_notification_event (event_id),
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_notification_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_notification_event FOREIGN KEY (event_id) REFERENCES calendar_events(id),
  CONSTRAINT ck_notification_target CHECK (task_id IS NULL OR event_id IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提醒与事务发件箱；实际推送服务另行接入';

CREATE TABLE scheduling_batches (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  engine_kind ENUM('manual','rule','model') NOT NULL,
  engine_version VARCHAR(100) NOT NULL,
  status ENUM('pending','running','succeeded','failed') NOT NULL DEFAULT 'pending',
  window_start DATETIME(6) NOT NULL,
  window_end DATETIME(6) NOT NULL,
  input_snapshot JSON NOT NULL COMMENT '有大小上限的任务版本、偏好和可用时间快照，不含令牌',
  error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  finished_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_batch_request (user_id, request_key),
  KEY idx_batch_history (user_id, created_at),
  CONSTRAINT fk_batch_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_batch_window CHECK (window_end > window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 或规则排程批次，仅保存真实执行来源';

CREATE TABLE scheduling_suggestions (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id BIGINT UNSIGNED NOT NULL,
  task_id BIGINT UNSIGNED NOT NULL,
  slot_number INT UNSIGNED NOT NULL COMMENT '同批次时间块序号',
  task_version BIGINT UNSIGNED NOT NULL COMMENT '采纳前校验任务版本未变更',
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NOT NULL,
  explanation TEXT NULL,
  status ENUM('pending','accepted','rejected','stale') NOT NULL DEFAULT 'pending',
  accepted_event_id BIGINT UNSIGNED NULL,
  decided_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_suggestion_slot (batch_id, slot_number),
  KEY idx_suggestion_task (task_id),
  KEY idx_suggestion_event (accepted_event_id),
  CONSTRAINT fk_suggestion_batch FOREIGN KEY (batch_id) REFERENCES scheduling_batches(id),
  CONSTRAINT fk_suggestion_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_suggestion_event FOREIGN KEY (accepted_event_id) REFERENCES calendar_events(id),
  CONSTRAINT ck_suggestion_range CHECK (ends_at > starts_at),
  CONSTRAINT ck_suggestion_accept CHECK ((status = 'accepted' AND accepted_event_id IS NOT NULL AND decided_at IS NOT NULL) OR (status <> 'accepted' AND accepted_event_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待人工采纳的排程时间块，不自动执行任务';

CREATE TABLE focus_sessions (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  task_id BIGINT UNSIGNED NULL,
  event_id BIGINT UNSIGNED NULL,
  client_session_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端持久 UUID，离线补传幂等',
  started_at DATETIME(6) NOT NULL,
  ended_at DATETIME(6) NULL,
  elapsed_seconds INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '扣除暂停后的有效秒数，不与预计耗时混用',
  status ENUM('running','completed','aborted') NOT NULL DEFAULT 'running',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_focus_client (user_id, client_session_key),
  KEY idx_focus_report (user_id, started_at),
  KEY idx_focus_task (task_id),
  KEY idx_focus_event (event_id),
  CONSTRAINT fk_focus_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_focus_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_focus_event FOREIGN KEY (event_id) REFERENCES calendar_events(id),
  CONSTRAINT ck_focus_range CHECK ((status = 'running' AND ended_at IS NULL) OR (status <> 'running' AND ended_at IS NOT NULL AND ended_at >= started_at)),
  CONSTRAINT ck_focus_elapsed CHECK (ended_at IS NULL OR elapsed_seconds <= TIMESTAMPDIFF(SECOND, started_at, ended_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='真实专注记录，不以计划时间代替实际专注';

CREATE TABLE subscription_plans (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(150) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  UNIQUE KEY uq_plan_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订阅方案预留，不初始化虚假 Pro 权益';

CREATE TABLE entitlements (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(150) NOT NULL,
  unit ENUM('boolean','count','seconds','bytes') NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_entitlement_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权益字典预留，明确计量单位';

CREATE TABLE plan_entitlements (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  plan_id BIGINT UNSIGNED NOT NULL,
  entitlement_id BIGINT UNSIGNED NOT NULL,
  limit_value BIGINT UNSIGNED NOT NULL COMMENT '布尔权益用零或一，其他为周期额度',
  reset_period ENUM('never','month') NOT NULL DEFAULT 'never',
  PRIMARY KEY (id),
  UNIQUE KEY uq_plan_entitlement (plan_id, entitlement_id),
  KEY idx_plan_entitlement_kind (entitlement_id),
  CONSTRAINT fk_plan_entitlement_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans(id),
  CONSTRAINT fk_plan_entitlement_kind FOREIGN KEY (entitlement_id) REFERENCES entitlements(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='方案权益预留，不是任意业务 EAV';

CREATE TABLE subscriptions (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL COMMENT '首版仅个人订阅，不表示团队计费已实现',
  plan_id BIGINT UNSIGNED NOT NULL,
  provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  external_subscription_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
  status ENUM('pending','active','past_due','cancelled','expired') NOT NULL DEFAULT 'pending',
  period_start DATETIME(6) NOT NULL,
  period_end DATETIME(6) NOT NULL,
  cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
  last_provider_event_at DATETIME(6) NULL COMMENT '辅助拒绝乱序回调，仍需供应商版本/查询校验',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_subscription_external (provider, external_subscription_id),
  KEY idx_subscription_user (user_id, status, period_end),
  KEY idx_subscription_plan (plan_id),
  CONSTRAINT fk_subscription_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_subscription_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans(id),
  CONSTRAINT ck_subscription_period CHECK (period_end > period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订阅状态镜像预留，不存卡号、CVV 或支付凭据';

CREATE TABLE billing_events (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subscription_id BIGINT UNSIGNED NOT NULL,
  provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  external_event_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
  event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_occurred_at DATETIME(6) NOT NULL,
  verified_payload_hash BINARY(32) NOT NULL COMMENT '验签后的原报文摘要，不保存含敏感支付信息的原报文',
  status ENUM('pending','applied','ignored','failed') NOT NULL DEFAULT 'pending',
  received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  applied_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_billing_event (provider, external_event_id),
  KEY idx_billing_subscription (subscription_id, received_at),
  CONSTRAINT fk_billing_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计费事件去重预留，未知订阅回调须先服务端解析';

CREATE TABLE task_assignments (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NOT NULL,
  member_id BIGINT UNSIGNED NOT NULL COMMENT '必须为任务所在团队的有效成员，服务层事务校验',
  assigned_by_user_id BIGINT UNSIGNED NOT NULL,
  assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_assignment_member (task_id, member_id),
  KEY idx_assignment_member (member_id, task_id),
  KEY idx_assignment_actor (assigned_by_user_id),
  CONSTRAINT fk_assignment_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_assignment_member FOREIGN KEY (member_id) REFERENCES team_members(id),
  CONSTRAINT fk_assignment_actor FOREIGN KEY (assigned_by_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='团队多成员指派预留';

CREATE TABLE api_idempotency_requests (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash BINARY(32) NOT NULL COMMENT '规范化请求摘要；同键不同正文返回冲突',
  status ENUM('pending','completed') NOT NULL DEFAULT 'pending',
  result_task_id BIGINT UNSIGNED NULL,
  result_event_id BIGINT UNSIGNED NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_api_request (user_id, operation, request_key),
  KEY idx_api_request_expiry (expires_at),
  KEY idx_api_request_task (result_task_id),
  KEY idx_api_request_event (result_event_id),
  CONSTRAINT fk_api_request_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_api_request_task FOREIGN KEY (result_task_id) REFERENCES tasks(id),
  CONSTRAINT fk_api_request_event FOREIGN KEY (result_event_id) REFERENCES calendar_events(id),
  CONSTRAINT ck_api_request_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='创建任务/日程请求幂等，不缓存认证响应或原始令牌';

-- 仅固定来源字典；存在字典条目不代表已获得平台授权或可同步。
INSERT INTO source_platforms (code, display_name) VALUES
('personal', '个人自主'),
('dingtalk', '钉钉'),
('wecom', '企业微信'),
('feishu', '飞书'),
('chaoxing', '学习通'),
('tencent_meeting', '腾讯会议');
