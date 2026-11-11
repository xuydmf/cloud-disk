-- ============================================================
-- 增量迁移脚本：为已有库增加「回收站」功能（MySQL 8.0+）
-- 应用启动时 SchemaInitializer 也会自动执行等价操作；
-- 若自动迁移失败，可手动执行本脚本。
-- ============================================================

USE cloud_disk;

-- 1) file_info 增加 deleted_time：进入回收站的时间
--    MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，若已存在会报 duplicate column，可忽略
ALTER TABLE file_info ADD COLUMN deleted_time DATETIME NULL COMMENT '进入回收站的时间, NULL=未删除';

-- 2) 回收站列表 + 过期清理都按 (deleted, deleted_time) 过滤，补一个联合索引
ALTER TABLE file_info ADD KEY idx_deleted_time (deleted, deleted_time);

-- 3) 历史数据回填：之前已逻辑删除、但没有删除时间的记录，
--    用 update_time 兜底（最接近的可得时间），避免它们永远不超期。
--    只回填一次，重复执行不会覆盖掉真实值。
UPDATE file_info
SET deleted_time = IFNULL(update_time, NOW())
WHERE deleted = 1 AND deleted_time IS NULL;
