CREATE TABLE IF NOT EXISTS `knowledge_document`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `doc_id`          CHAR(32)     NOT NULL COMMENT '文档唯一标识（UUID，去掉横线）',
    `doc_title`       VARCHAR(255) NOT NULL COMMENT '文档标题',
    `original_name`   VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_type`       VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '文件类型（pdf、docx、txt 等）',
    `file_size`       BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `file_md5`        CHAR(32)              DEFAULT NULL COMMENT '文件内容 MD5，用于防止同一用户重复上传',
    `uploader`        VARCHAR(64)  NOT NULL COMMENT '上传者',
    `doc_url`         VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'MinIO 存储路径（bucket/key）',
    `doc_status`        VARCHAR(32)  NOT NULL DEFAULT 'init' COMMENT '文档状态：init, uploaded, converting, converted, splitting, chunked, vectoring, vector_stored',
    `converted_doc_url` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的 markdown 文件 MinIO 路径',
    `error_message`     TEXT                  DEFAULT NULL COMMENT '处理失败时的错误信息',
    `retry_count`       INT          NOT NULL DEFAULT 0 COMMENT '自动补偿重试次数，达到上限后停止补偿等待人工处理',
    `visibility`      VARCHAR(16)  NOT NULL DEFAULT 'private' COMMENT '可见范围：private-仅自己可见, internal-内部可见, public-公开',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_id` (`doc_id`),
    UNIQUE KEY `uk_uploader_file_md5` (`uploader`, `file_md5`),
    KEY `idx_uploader` (`uploader`),
    KEY `idx_doc_status` (`doc_status`),
    KEY `idx_visibility` (`visibility`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档元数据表';

-- 兼容已经创建过的 knowledge_document 表：缺少字段或索引时才执行迁移。
SET @file_md5_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'file_md5'
);
SET @file_md5_column_sql = IF(
    @file_md5_column_exists = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `file_md5` CHAR(32) DEFAULT NULL COMMENT ''文件内容 MD5，用于防止同一用户重复上传'' AFTER `file_size`',
    'SELECT 1'
);
PREPARE file_md5_stmt FROM @file_md5_column_sql;
EXECUTE file_md5_stmt;
DEALLOCATE PREPARE file_md5_stmt;

SET @file_md5_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_document'
      AND INDEX_NAME = 'uk_uploader_file_md5'
);
SET @file_md5_index_sql = IF(
    @file_md5_index_exists = 0,
    'CREATE UNIQUE INDEX `uk_uploader_file_md5` ON `knowledge_document` (`uploader`, `file_md5`)',
    'SELECT 1'
);
PREPARE file_md5_stmt FROM @file_md5_index_sql;
EXECUTE file_md5_stmt;
DEALLOCATE PREPARE file_md5_stmt;

-- 兼容已存在的表：添加 error_message 字段用于记录处理失败的错误信息
SET @error_message_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'error_message'
);
SET @error_message_column_sql = IF(
    @error_message_column_exists = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `error_message` TEXT DEFAULT NULL COMMENT ''处理失败时的错误信息'' AFTER `converted_doc_url`',
    'SELECT 1'
);
PREPARE error_message_stmt FROM @error_message_column_sql;
EXECUTE error_message_stmt;
DEALLOCATE PREPARE error_message_stmt;

-- 兼容已存在的表：添加 retry_count 字段用于记录自动补偿重试次数
SET @retry_count_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_document'
      AND COLUMN_NAME = 'retry_count'
);
SET @retry_count_column_sql = IF(
    @retry_count_column_exists = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `retry_count` INT NOT NULL DEFAULT 0 COMMENT ''自动补偿重试次数，达到上限后停止补偿等待人工处理'' AFTER `error_message`',
    'SELECT 1'
);
PREPARE retry_count_stmt FROM @retry_count_column_sql;
EXECUTE retry_count_stmt;
DEALLOCATE PREPARE retry_count_stmt;


CREATE TABLE IF NOT EXISTS `knowledge_segment`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `chunk_id`       CHAR(32)     NOT NULL COMMENT '片段唯一标识（UUID，去掉横线）',
    `text`           LONGTEXT     NOT NULL COMMENT '片段文本内容',
    `doc_id`         CHAR(32)     NOT NULL COMMENT '所属文档ID，关联 knowledge_document.doc_id',
    `chunk_order`    INT          NOT NULL DEFAULT 0 COMMENT '文档内分片顺序，从0开始',
    `embedding_id`   VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'ES 中的向量文档 ID',
    `status`         VARCHAR(32)  NOT NULL DEFAULT 'init' COMMENT '片段状态：init-初始化, vector_stored-已向量化',
    `metadata`       VARCHAR(2048)                 COMMENT '元数据（parent_chunk_id、brother_chunk_id、page_number 等）',
    `skip_embedding` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否跳过嵌入向量生成：0-不跳过, 1-跳过',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    KEY `idx_doc_id` (`doc_id`),
    KEY `idx_doc_id_order` (`doc_id`, `chunk_order`),
    KEY `idx_embedding_id` (`embedding_id`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_segment_doc` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_document` (`doc_id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档分片片段表';
