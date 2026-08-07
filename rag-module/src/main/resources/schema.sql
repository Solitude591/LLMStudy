CREATE TABLE IF NOT EXISTS `knowledge_document`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `doc_id`             BIGINT       NOT NULL COMMENT '逻辑文档唯一标识（雪花算法生成）',
    `doc_title`          VARCHAR(255) NOT NULL COMMENT '文档标题',
    `accessible_by`      VARCHAR(255)          DEFAULT NULL COMMENT '预留的可访问主体标识，当前不参与权限校验',
    `current_version_id` BIGINT                DEFAULT NULL COMMENT '当前已发布的物理版本 ID，首次发布前为 NULL',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_id` (`doc_id`),
    KEY `idx_doc_current_version` (`doc_id`, `current_version_id`),
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
