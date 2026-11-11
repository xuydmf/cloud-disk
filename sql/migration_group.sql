-- ============================================================
-- 增量迁移脚本：为已有库增加「文件分组」功能（MySQL 8.0+）
-- 应用启动时 SchemaInitializer 也会自动执行等价操作；
-- 若自动迁移失败，可手动执行本脚本。
-- ============================================================

USE cloud_disk;

-- 1) 分组表
CREATE TABLE IF NOT EXISTS file_group (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(100) NOT NULL                COMMENT '分组名称',
    sort          INT          NOT NULL DEFAULT 0      COMMENT '排序值',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除: 0-正常 1-已删',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分组表';

-- 2) file_info 增加 group_id（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--    若已存在会报 duplicate column，可忽略）
ALTER TABLE file_info ADD COLUMN group_id BIGINT NULL COMMENT '所属分组, NULL=未分组';

-- 3) 索引
ALTER TABLE file_info ADD KEY idx_group_id (group_id);
