-- ============================================================
-- 类阿里云云盘 数据库初始化脚本
-- 数据库：MySQL 8.0+（推荐），utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS cloud_disk
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE cloud_disk;

-- 文件信息表
CREATE TABLE IF NOT EXISTS file_info (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_name     VARCHAR(512) NOT NULL                COMMENT '原始文件名',
    stored_name   VARCHAR(128) NOT NULL                COMMENT '存储文件名(uuid+扩展名)',
    file_path     VARCHAR(512) NOT NULL                COMMENT '相对存储根目录的路径',
    thumb_path    VARCHAR(512) DEFAULT NULL            COMMENT '缩略图/封面相对路径',
    file_size     BIGINT       NOT NULL                COMMENT '文件大小(字节)',
    content_type  VARCHAR(128) DEFAULT NULL            COMMENT 'MIME 类型',
    file_type     VARCHAR(16)  NOT NULL                COMMENT 'IMAGE / VIDEO / OTHER',
    group_id      BIGINT       DEFAULT NULL            COMMENT '所属分组, NULL=未分组',
    md5           VARCHAR(64)  DEFAULT NULL            COMMENT '文件指纹(可用于秒传/去重)',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除: 0-正常 1-在回收站',
    deleted_time  DATETIME     DEFAULT NULL            COMMENT '进入回收站的时间, NULL=未删除',
    captured_time DATETIME     DEFAULT NULL            COMMENT '拍摄时间, NULL=未知(按上传时间展示)',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_file_type (file_type),
    KEY idx_create_time (create_time),
    KEY idx_md5 (md5),
    KEY idx_group_id (group_id),
    KEY idx_captured_time (captured_time),
    KEY idx_deleted_time (deleted, deleted_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件信息表';

-- 文件分组表
CREATE TABLE IF NOT EXISTS file_group (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(100) NOT NULL                COMMENT '分组名称',
    sort          INT          NOT NULL DEFAULT 0      COMMENT '排序值',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除: 0-正常 1-已删',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分组表';
