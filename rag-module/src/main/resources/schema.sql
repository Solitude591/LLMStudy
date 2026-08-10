CREATE TABLE IF NOT EXISTS `auth_organization`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `organization_id`   VARCHAR(64) NOT NULL COMMENT '组织业务标识',
    `organization_name` VARCHAR(128) NOT NULL COMMENT '组织名称',
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auth_organization_id` (`organization_id`),
    UNIQUE KEY `uk_auth_organization_name` (`organization_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '认证组织表';

CREATE TABLE IF NOT EXISTS `auth_user`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`         VARCHAR(64)  NOT NULL COMMENT '用户业务标识',
    `username`        VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password_hash`   VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `display_name`    VARCHAR(128) NOT NULL COMMENT '展示名',
    `organization_id` VARCHAR(64)           DEFAULT NULL COMMENT '所属组织',
    `role`            VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT 'USER, ORG_ADMIN, SYS_ADMIN',
    `status`          VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED, DISABLED',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auth_user_id` (`user_id`),
    UNIQUE KEY `uk_auth_username` (`username`),
    KEY `idx_auth_user_organization_role` (`organization_id`, `role`),
    CONSTRAINT `fk_auth_user_organization` FOREIGN KEY (`organization_id`)
        REFERENCES `auth_organization` (`organization_id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '认证用户表';

INSERT IGNORE INTO `auth_organization` (`organization_id`, `organization_name`)
VALUES ('org-a', '组织 A'),
       ('org-b', '组织 B');

-- 演示密码均为 ChangeMe123!；生产环境启动前必须替换。
INSERT IGNORE INTO `auth_user`
    (`user_id`, `username`, `password_hash`, `display_name`, `organization_id`, `role`, `status`)
VALUES ('user-alice', 'alice', '$2y$10$QOwF4gQSwPyY4Hbxf3Otcuezq7.ufOu6fj3l3YQ1JfVHeFk2xFyiq',
        'Alice', 'org-a', 'USER', 'ENABLED'),
       ('user-org-admin', 'org_admin', '$2y$10$QOwF4gQSwPyY4Hbxf3Otcuezq7.ufOu6fj3l3YQ1JfVHeFk2xFyiq',
        '组织 A 管理员', 'org-a', 'ORG_ADMIN', 'ENABLED'),
       ('user-bob', 'bob', '$2y$10$QOwF4gQSwPyY4Hbxf3Otcuezq7.ufOu6fj3l3YQ1JfVHeFk2xFyiq',
        'Bob', 'org-b', 'USER', 'ENABLED'),
       ('user-sys-admin', 'sys_admin', '$2y$10$QOwF4gQSwPyY4Hbxf3Otcuezq7.ufOu6fj3l3YQ1JfVHeFk2xFyiq',
        '系统管理员', NULL, 'SYS_ADMIN', 'ENABLED');

CREATE TABLE IF NOT EXISTS `knowledge_document`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `doc_id`             BIGINT       NOT NULL COMMENT '逻辑文档唯一标识（雪花算法生成）',
    `doc_title`          VARCHAR(255) NOT NULL COMMENT '文档标题',
    `owner_user_id`      VARCHAR(64)  NOT NULL COMMENT '文档所有者',
    `visibility`         VARCHAR(32)  NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE, ORGANIZATION, PUBLIC',
    `organization_id`   VARCHAR(64)           DEFAULT NULL COMMENT '组织文档所属组织',
    `current_version_id` BIGINT                DEFAULT NULL COMMENT '当前已发布的物理版本 ID，首次发布前为 NULL',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_id` (`doc_id`),
    KEY `idx_doc_current_version` (`doc_id`, `current_version_id`),
    KEY `idx_doc_owner_visibility` (`owner_user_id`, `visibility`),
    KEY `idx_doc_organization_visibility` (`organization_id`, `visibility`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库逻辑文档表';


CREATE TABLE IF NOT EXISTS `knowledge_document_version`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `version_id`         BIGINT       NOT NULL COMMENT '物理版本唯一标识（雪花算法生成）',
    `doc_id`             BIGINT       NOT NULL COMMENT '所属逻辑文档 ID',
    `version_no`         INT UNSIGNED NOT NULL COMMENT '逻辑文档内递增的展示版本号，从 1 开始',
    `content_hash`       CHAR(64)     NOT NULL COMMENT '原始文件内容 SHA-256',
    `file_type`          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '文件类型，用于选择文档解析策略',
    `uploaded_by`        VARCHAR(64)  NOT NULL COMMENT '该版本上传者',
    `doc_url`            VARCHAR(512) NOT NULL DEFAULT '' COMMENT '原始文件访问地址',
    `raw_object_key`     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '原始文件的 MinIO object key',
    `converted_doc_url`  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的 Markdown 文件地址',
    `content_list_url`   VARCHAR(512)          DEFAULT NULL COMMENT 'MinerU content_list.json 地址，可空',
    `processing_status`  VARCHAR(32)  NOT NULL DEFAULT 'INIT' COMMENT '处理状态：INIT, UPLOADED, CONVERTING, CONVERTED, SPLITTING, CHUNKED, VECTORING, VECTOR_STORED',
    `release_status`     VARCHAR(32)  NOT NULL DEFAULT 'PREPARING' COMMENT '发布状态：PREPARING, READY, PUBLISHING, PUBLISHED, ARCHIVED',
    `error_message`      TEXT                  DEFAULT NULL COMMENT '处理失败原因',
    `retry_count`        INT          NOT NULL DEFAULT 0 COMMENT '自动补偿重试次数',
    `change_summary`     VARCHAR(512)          DEFAULT NULL COMMENT '版本变更说明',
    `ready_at`           DATETIME              DEFAULT NULL COMMENT '完成向量化、可发布的时间',
    `published_at`       DATETIME              DEFAULT NULL COMMENT '最近一次发布时间',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_version_id` (`version_id`),
    UNIQUE KEY `uk_document_version_no` (`doc_id`, `version_no`),
    UNIQUE KEY `uk_document_version_pair` (`doc_id`, `version_id`),
    UNIQUE KEY `uk_document_version_hash` (`doc_id`, `content_hash`),
    KEY `idx_document_version_processing_status` (`processing_status`),
    KEY `idx_document_version_release_status` (`release_status`),
    KEY `idx_document_version_created_at` (`doc_id`, `created_at`),
    CONSTRAINT `fk_document_version_doc` FOREIGN KEY (`doc_id`)
        REFERENCES `knowledge_document` (`doc_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '知识库文档物理版本快照表';

-- 使用复合外键保证 current_version_id 只能指向当前逻辑文档下的版本。
-- schema.sql 会在每次启动时执行，因此约束只在首次创建时添加。
SET @current_version_fk_exists = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_document'
      AND CONSTRAINT_NAME = 'fk_document_current_version'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @current_version_fk_sql = IF(
    @current_version_fk_exists = 0,
    'ALTER TABLE `knowledge_document` ADD CONSTRAINT `fk_document_current_version` FOREIGN KEY (`doc_id`, `current_version_id`) REFERENCES `knowledge_document_version` (`doc_id`, `version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT',
    'SELECT 1'
);
PREPARE current_version_fk_stmt FROM @current_version_fk_sql;
EXECUTE current_version_fk_stmt;
DEALLOCATE PREPARE current_version_fk_stmt;


CREATE TABLE IF NOT EXISTS `table_meta`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `doc_id`         BIGINT       NOT NULL COMMENT '所属文档 ID',
    `sheet_index`    INT          NOT NULL COMMENT '非空 Sheet 序号，从 1 开始',
    `sheet_name`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Excel 中的原始 Sheet 名',
    `table_name`     VARCHAR(64)  NOT NULL COMMENT 'MySQL 物理表名',
    `column_mapping` JSON         NOT NULL COMMENT 'Excel 表头与物理列名的映射',
    `row_count`      BIGINT       NOT NULL DEFAULT 0 COMMENT '已导入数据行数',
    `status`         VARCHAR(32)  NOT NULL DEFAULT 'CREATING' COMMENT 'CREATING, IMPORTED',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_meta_table_name` (`table_name`),
    UNIQUE KEY `uk_table_meta_doc_sheet` (`doc_id`, `sheet_index`),
    KEY `idx_table_meta_doc_id` (`doc_id`),
    CONSTRAINT `fk_table_meta_doc` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_document` (`doc_id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'Excel 动态表元数据';


CREATE TABLE IF NOT EXISTS `knowledge_segment`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `chunk_id`       BIGINT       NOT NULL COMMENT '片段唯一标识（雪花算法生成）',
    `text`           LONGTEXT     NOT NULL COMMENT '片段文本内容',
    `doc_id`         BIGINT       NOT NULL COMMENT '所属文档ID，关联 knowledge_document.doc_id',
    `version_id`     BIGINT       NOT NULL COMMENT '所属物理版本 ID',
    `chunk_order`    INT          NOT NULL DEFAULT 0 COMMENT '文档内分片顺序，从0开始',
    `embedding_id`   VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'ES 中的向量文档 ID',
    `status`         VARCHAR(32)  NOT NULL DEFAULT 'INIT' COMMENT '片段状态：INIT-初始化, VECTOR_STORED-已向量化',
    `metadata`       VARCHAR(2048)                 COMMENT '元数据（parent_chunk_id、brother_chunk_id、page_number 等）',
    `skip_embedding` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否跳过嵌入向量生成：0-不跳过, 1-跳过',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    KEY `idx_doc_id` (`doc_id`),
    KEY `idx_doc_id_order` (`doc_id`, `chunk_order`),
    KEY `idx_doc_version_order` (`doc_id`, `version_id`, `chunk_order`),
    KEY `idx_version_status` (`version_id`, `status`),
    KEY `idx_embedding_id` (`embedding_id`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_segment_document_version` FOREIGN KEY (`doc_id`, `version_id`)
        REFERENCES `knowledge_document_version` (`doc_id`, `version_id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档分片片段表';


CREATE TABLE IF NOT EXISTS `chat_conversation`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `conversation_id` VARCHAR(64)   NOT NULL COMMENT '会话唯一标识',
    `user_id`         VARCHAR(64)   NOT NULL COMMENT '用户唯一标识',
    `title`           VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '会话标题',
    `status`          VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE-活跃，ARCHIVED-已归档，DELETED-已删除',
    `message_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '消息版本，每新增一条消息递增',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_conversation_id` (`conversation_id`),
    KEY `idx_chat_conversation_user_status_updated` (`user_id`, `status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '聊天会话表';

-- 兼容已经创建过的 chat_conversation 表：增加消息版本用于校验 Redis 历史窗口。
SET @chat_message_version_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_conversation'
      AND COLUMN_NAME = 'message_version'
);
SET @chat_message_version_column_sql = IF(
    @chat_message_version_column_exists = 0,
    'ALTER TABLE `chat_conversation` ADD COLUMN `message_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''消息版本，每新增一条消息递增'' AFTER `status`',
    'SELECT 1'
);
PREPARE chat_message_version_column_stmt FROM @chat_message_version_column_sql;
EXECUTE chat_message_version_column_stmt;
DEALLOCATE PREPARE chat_message_version_column_stmt;


CREATE TABLE IF NOT EXISTS `chat_message`
(
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `message_id`          VARCHAR(64)   NOT NULL COMMENT '消息唯一标识',
    `conversation_id`     VARCHAR(64)   NOT NULL COMMENT '所属会话 ID，关联 chat_conversation.conversation_id',
    `type`                VARCHAR(32)   NOT NULL COMMENT '消息类型：SYSTEM，USER，ASSISTANT，TOOL',
    `content`             LONGTEXT      NOT NULL COMMENT '消息内容',
    `transform_content`   LONGTEXT               DEFAULT NULL COMMENT '改写后的内容，主要用于保存用户问题改写结果',
    `token_count`         INT UNSIGNED           DEFAULT NULL COMMENT 'Token 数量，NULL 表示未统计',
    `model_name`          VARCHAR(128)            DEFAULT NULL COMMENT '生成或处理该消息的模型名称',
    `rag_references`      JSON                    DEFAULT NULL COMMENT 'RAG 引用内容',
    `metadata`            JSON                    DEFAULT NULL COMMENT '扩展元数据',
    `created_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_message_id` (`message_id`),
    KEY `idx_chat_message_conversation_created` (`conversation_id`, `created_at`, `id`),
    CONSTRAINT `fk_chat_message_conversation` FOREIGN KEY (`conversation_id`)
        REFERENCES `chat_conversation` (`conversation_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '聊天消息表';

-- 旧版允许前端任意填写 user_id。添加外键前清理这些无法归属的旧会话。
DELETE message
FROM `chat_message` message
JOIN `chat_conversation` conversation
  ON conversation.conversation_id = message.conversation_id
LEFT JOIN `auth_user` user_account
  ON user_account.user_id = conversation.user_id
WHERE user_account.user_id IS NULL;

DELETE conversation
FROM `chat_conversation` conversation
LEFT JOIN `auth_user` user_account
  ON user_account.user_id = conversation.user_id
WHERE user_account.user_id IS NULL;

-- 兼容已存在的 knowledge_document：补齐新权限列并移除旧预留列。
SET @doc_owner_column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'owner_user_id'
);
SET @doc_owner_column_sql = IF(@doc_owner_column_exists = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `owner_user_id` VARCHAR(64) NULL AFTER `doc_title`',
    'SELECT 1');
PREPARE doc_owner_column_stmt FROM @doc_owner_column_sql;
EXECUTE doc_owner_column_stmt;
DEALLOCATE PREPARE doc_owner_column_stmt;

SET @doc_visibility_column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'visibility'
);
SET @doc_visibility_column_sql = IF(@doc_visibility_column_exists = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `visibility` VARCHAR(32) NOT NULL DEFAULT ''PRIVATE'' AFTER `owner_user_id`',
    'SELECT 1');
PREPARE doc_visibility_column_stmt FROM @doc_visibility_column_sql;
EXECUTE doc_visibility_column_stmt;
DEALLOCATE PREPARE doc_visibility_column_stmt;

SET @doc_organization_column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'organization_id'
);
SET @doc_organization_column_sql = IF(@doc_organization_column_exists = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `organization_id` VARCHAR(64) NULL AFTER `visibility`',
    'SELECT 1');
PREPARE doc_organization_column_stmt FROM @doc_organization_column_sql;
EXECUTE doc_organization_column_stmt;
DEALLOCATE PREPARE doc_organization_column_stmt;

UPDATE `knowledge_document`
SET `owner_user_id` = 'user-alice'
WHERE `owner_user_id` IS NULL OR `owner_user_id` = '';
ALTER TABLE `knowledge_document`
    MODIFY COLUMN `owner_user_id` VARCHAR(64) NOT NULL COMMENT '文档所有者';

SET @accessible_by_column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'accessible_by'
);
SET @accessible_by_drop_sql = IF(@accessible_by_column_exists > 0,
    'ALTER TABLE `knowledge_document` DROP COLUMN `accessible_by`', 'SELECT 1');
PREPARE accessible_by_drop_stmt FROM @accessible_by_drop_sql;
EXECUTE accessible_by_drop_stmt;
DEALLOCATE PREPARE accessible_by_drop_stmt;

-- 对已有表幂等添加认证外键；新建和升级路径共用。
SET @chat_user_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation'
      AND CONSTRAINT_NAME = 'fk_chat_conversation_user' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @chat_user_fk_sql = IF(@chat_user_fk_exists = 0,
    'ALTER TABLE `chat_conversation` ADD CONSTRAINT `fk_chat_conversation_user` FOREIGN KEY (`user_id`) REFERENCES `auth_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE',
    'SELECT 1');
PREPARE chat_user_fk_stmt FROM @chat_user_fk_sql;
EXECUTE chat_user_fk_stmt;
DEALLOCATE PREPARE chat_user_fk_stmt;

SET @doc_owner_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND CONSTRAINT_NAME = 'fk_knowledge_document_owner' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @doc_owner_fk_sql = IF(@doc_owner_fk_exists = 0,
    'ALTER TABLE `knowledge_document` ADD CONSTRAINT `fk_knowledge_document_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `auth_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE',
    'SELECT 1');
PREPARE doc_owner_fk_stmt FROM @doc_owner_fk_sql;
EXECUTE doc_owner_fk_stmt;
DEALLOCATE PREPARE doc_owner_fk_stmt;

SET @doc_org_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND CONSTRAINT_NAME = 'fk_knowledge_document_organization' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @doc_org_fk_sql = IF(@doc_org_fk_exists = 0,
    'ALTER TABLE `knowledge_document` ADD CONSTRAINT `fk_knowledge_document_organization` FOREIGN KEY (`organization_id`) REFERENCES `auth_organization` (`organization_id`) ON DELETE RESTRICT ON UPDATE CASCADE',
    'SELECT 1');
PREPARE doc_org_fk_stmt FROM @doc_org_fk_sql;
EXECUTE doc_org_fk_stmt;
DEALLOCATE PREPARE doc_org_fk_stmt;

SET @doc_owner_visibility_index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND INDEX_NAME = 'idx_doc_owner_visibility'
);
SET @doc_owner_visibility_index_sql = IF(@doc_owner_visibility_index_exists = 0,
    'ALTER TABLE `knowledge_document` ADD INDEX `idx_doc_owner_visibility` (`owner_user_id`, `visibility`)',
    'SELECT 1');
PREPARE doc_owner_visibility_index_stmt FROM @doc_owner_visibility_index_sql;
EXECUTE doc_owner_visibility_index_stmt;
DEALLOCATE PREPARE doc_owner_visibility_index_stmt;

SET @doc_org_visibility_index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
      AND INDEX_NAME = 'idx_doc_organization_visibility'
);
SET @doc_org_visibility_index_sql = IF(@doc_org_visibility_index_exists = 0,
    'ALTER TABLE `knowledge_document` ADD INDEX `idx_doc_organization_visibility` (`organization_id`, `visibility`)',
    'SELECT 1');
PREPARE doc_org_visibility_index_stmt FROM @doc_org_visibility_index_sql;
EXECUTE doc_org_visibility_index_stmt;
DEALLOCATE PREPARE doc_org_visibility_index_stmt;

SET @version_uploader_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document_version'
      AND CONSTRAINT_NAME = 'fk_document_version_uploader' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @version_uploader_fk_sql = IF(@version_uploader_fk_exists = 0,
    'ALTER TABLE `knowledge_document_version` ADD CONSTRAINT `fk_document_version_uploader` FOREIGN KEY (`uploaded_by`) REFERENCES `auth_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE',
    'SELECT 1');
PREPARE version_uploader_fk_stmt FROM @version_uploader_fk_sql;
EXECUTE version_uploader_fk_stmt;
DEALLOCATE PREPARE version_uploader_fk_stmt;

-- 小写枚举值统一为大写。
UPDATE `table_meta`
SET `status` = UPPER(`status`)
WHERE BINARY `status` <> BINARY UPPER(`status`);
ALTER TABLE `table_meta` ALTER COLUMN `status` SET DEFAULT 'CREATING';

UPDATE `knowledge_segment`
SET `status` = UPPER(`status`)
WHERE BINARY `status` <> BINARY UPPER(`status`);
ALTER TABLE `knowledge_segment` ALTER COLUMN `status` SET DEFAULT 'INIT';

UPDATE `chat_conversation`
SET `status` = UPPER(`status`)
WHERE BINARY `status` <> BINARY UPPER(`status`);
ALTER TABLE `chat_conversation` ALTER COLUMN `status` SET DEFAULT 'ACTIVE';

UPDATE `chat_message`
SET `type` = UPPER(`type`)
WHERE BINARY `type` <> BINARY UPPER(`type`);

-- 兼容已存在的 knowledge_document_version：补充 content_list.json 地址列。
SET @content_list_url_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_document_version'
      AND COLUMN_NAME = 'content_list_url'
);
SET @content_list_url_column_sql = IF(
    @content_list_url_column_exists = 0,
    'ALTER TABLE `knowledge_document_version` ADD COLUMN `content_list_url` VARCHAR(512) DEFAULT NULL COMMENT ''MinerU content_list.json 地址，可空'' AFTER `converted_doc_url`',
    'SELECT 1'
);
PREPARE content_list_url_column_stmt FROM @content_list_url_column_sql;
EXECUTE content_list_url_column_stmt;
DEALLOCATE PREPARE content_list_url_column_stmt;
