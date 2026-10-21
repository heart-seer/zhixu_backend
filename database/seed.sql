-- 知序：基础字典数据
-- 先执行 database/schema.sql 建表，再执行本文件。
-- 这里只写入来源平台字典，不初始化演示账号、密码、Token 或虚假权益。
-- 需要账号时调用 POST /api/auth/register 由服务端生成 BCrypt 哈希。

INSERT INTO source_platforms(code, display_name) VALUES
  ('personal', '本地导入'),
  ('chaoxing', '学习通'),
  ('wecom', '企业微信'),
  ('tencent_meeting', '腾讯会议'),
  ('dingtalk', '钉钉'),
  ('feishu', '飞书')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO subscription_plans(code, name) VALUES ('pro_monthly', '知序 Pro 月付')
ON DUPLICATE KEY UPDATE name = VALUES(name);
