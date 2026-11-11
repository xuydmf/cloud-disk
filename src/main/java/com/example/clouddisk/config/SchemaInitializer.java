package com.example.clouddisk.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 轻量级幂等建表/迁移：启动时确保分组相关表结构存在。
 * 生产环境建议改用 Flyway/Liquibase 管理脚本。
 */
@Slf4j
@Component
@Order(1)
public class SchemaInitializer implements CommandLineRunner {

    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS file_group ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "name VARCHAR(100) NOT NULL COMMENT '分组名称',"
                    + "sort INT NOT NULL DEFAULT 0 COMMENT '排序',"
                    + "deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除',"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分组表'");

            boolean hasGroupId = columnExists(conn, "file_info", "group_id");
            if (!hasGroupId) {
                st.execute("ALTER TABLE file_info ADD COLUMN group_id BIGINT NULL COMMENT '所属分组，NULL=未分组'");
                log.info("已为 file_info 增加 group_id 字段");
            }

            // 回收站：记录文件进入回收站的时间，用来计算「还剩几天被彻底清理」
            if (!columnExists(conn, "file_info", "deleted_time")) {
                st.execute("ALTER TABLE file_info ADD COLUMN deleted_time DATETIME NULL COMMENT '进入回收站的时间, NULL=未删除'");
                log.info("已为 file_info 增加 deleted_time 字段（回收站）");
            }

            // 拍摄时间：相册按日期分组时用它；取不到就回退到 create_time
            if (!columnExists(conn, "file_info", "captured_time")) {
                st.execute("ALTER TABLE file_info ADD COLUMN captured_time DATETIME NULL "
                        + "COMMENT '拍摄时间，NULL=未知（按上传时间展示）'");
                log.info("已为 file_info 增加 captured_time 字段（相册按日期分组）");
            }
            // 历史已删除数据回填删除时间，否则它们永远算不出超期
            int backfilled = st.executeUpdate(
                    "UPDATE file_info SET deleted_time = IFNULL(update_time, NOW()) "
                            + "WHERE deleted = 1 AND deleted_time IS NULL");
            if (backfilled > 0) {
                log.info("已为 {} 条历史删除记录回填 deleted_time", backfilled);
            }
            // 回收站列表与过期清理都按 (deleted, deleted_time) 过滤
            if (!indexExists(conn, "file_info", "idx_deleted_time")) {
                st.execute("ALTER TABLE file_info ADD KEY idx_deleted_time (deleted, deleted_time)");
                log.info("已为 file_info 增加 idx_deleted_time 索引");
            }

            // ---------- AI 自动分组 ----------
            // 分组的「样貌参考图」：大模型靠这张图判断待识别图片里的人是不是该分组成员。
            // 只存相对路径（相对 file.storage.root），删掉对应文件即视为未设置。
            if (!columnExists(conn, "file_group", "ref_image_path")) {
                st.execute("ALTER TABLE file_group ADD COLUMN ref_image_path VARCHAR(255) NULL "
                        + "COMMENT '样貌参考图相对路径，NULL=未设置'");
                log.info("已为 file_group 增加 ref_image_path 字段（AI 自动分组）");
            }
            // 每张图片是否已跑过识别；0=未识别（含关闭功能/非图片），1=已识别
            if (!columnExists(conn, "file_info", "ai_checked")) {
                st.execute("ALTER TABLE file_info ADD COLUMN ai_checked TINYINT NOT NULL DEFAULT 0 "
                        + "COMMENT '是否已完成 AI 识别：0-否 1-是'");
                log.info("已为 file_info 增加 ai_checked 字段（AI 自动分组）");
            }
            // 识别命中但未采纳的分组也记下来，方便排查「为什么没分到某组」
            if (!columnExists(conn, "file_info", "ai_group_id")) {
                st.execute("ALTER TABLE file_info ADD COLUMN ai_group_id BIGINT NULL "
                        + "COMMENT 'AI 识别命中的分组 id，NULL=未命中'");
                log.info("已为 file_info 增加 ai_group_id 字段（AI 自动分组）");
            }
            // 识别结论的可读描述，直接给前端展示
            if (!columnExists(conn, "file_info", "ai_note")) {
                st.execute("ALTER TABLE file_info ADD COLUMN ai_note VARCHAR(255) NULL "
                        + "COMMENT 'AI 识别结论描述'");
                log.info("已为 file_info 增加 ai_note 字段（AI 自动分组）");
            }
            // 待识别的图片用 (file_type, ai_checked) 扫描
            if (!indexExists(conn, "file_info", "idx_ai_checked")) {
                st.execute("ALTER TABLE file_info ADD KEY idx_ai_checked (file_type, ai_checked)");
                log.info("已为 file_info 增加 idx_ai_checked 索引");
            }
            log.info("分组表结构初始化完成");

            // 系统用户表：管理员由 application.yml 初始化，普通用户只能在后台页面添加
            st.execute("CREATE TABLE IF NOT EXISTS sys_user ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "username VARCHAR(64) NOT NULL COMMENT '登录名',"
                    + "password VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',"
                    + "display_name VARCHAR(64) NULL COMMENT '显示名',"
                    + "role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/USER',"
                    + "enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',"
                    + "last_login_time DATETIME NULL COMMENT '最后登录时间',"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY uk_sys_user_username (username)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表'");
            log.info("用户表结构初始化完成");

            // Spring Session JDBC 的两张表：会话落库，重启应用不掉登录态。
            // 索引直接内联在 CREATE TABLE 里，避免 MySQL 不支持 CREATE INDEX IF NOT EXISTS 的问题。
            st.execute("CREATE TABLE IF NOT EXISTS SPRING_SESSION ("
                    + "PRIMARY_ID CHAR(36) NOT NULL,"
                    + "SESSION_ID CHAR(36) NOT NULL,"
                    + "CREATION_TIME BIGINT NOT NULL,"
                    + "LAST_ACCESS_TIME BIGINT NOT NULL,"
                    + "MAX_INACTIVE_INTERVAL INT NOT NULL,"
                    + "EXPIRY_TIME BIGINT NOT NULL,"
                    + "PRINCIPAL_NAME VARCHAR(100),"
                    + "CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID),"
                    + "UNIQUE KEY SPRING_SESSION_IX1 (SESSION_ID),"
                    + "KEY SPRING_SESSION_IX2 (EXPIRY_TIME),"
                    + "KEY SPRING_SESSION_IX3 (PRINCIPAL_NAME)"
                    + ") ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COMMENT='登录会话表'");

            st.execute("CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES ("
                    + "SESSION_PRIMARY_ID CHAR(36) NOT NULL,"
                    + "ATTRIBUTE_NAME VARCHAR(200) NOT NULL,"
                    + "ATTRIBUTE_BYTES BLOB NOT NULL,"
                    + "CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),"
                    + "CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) "
                    + "REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COMMENT='登录会话属性表'");
            log.info("会话表结构初始化完成（登录态持久化，重启不掉线）");
        } catch (Exception e) {
            log.warn("表结构初始化失败（可手动执行 sql 目录下的迁移脚本）：{}", e.getMessage());
        }
    }

    private boolean indexExists(Connection conn, String table, String indexName) {
        try {
            DatabaseMetaData md = conn.getMetaData();
            for (String t : new String[]{table, table.toUpperCase()}) {
                try (ResultSet rs = md.getIndexInfo(conn.getCatalog(), null, t, false, false)) {
                    while (rs.next()) {
                        String name = rs.getString("INDEX_NAME");
                        if (name != null && name.equalsIgnoreCase(indexName)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("检查索引 {} 失败：{}", indexName, e.getMessage());
        }
        return false;
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {

        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getColumns(conn.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return true;
            }
        }
        // 有些驱动对大小写敏感，做一次大写尝试
        try (ResultSet rs = md.getColumns(conn.getCatalog(), null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }
}
