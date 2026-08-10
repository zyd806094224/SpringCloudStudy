/*
 * 死信消息表
 * 配合 TransferDlqListener 使用，存储消费失败重试耗尽后的死信消息。
 * 需要在 spring_cloud 库执行。
 */
CREATE TABLE `dead_letter_message` (
  `id`              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tx_id`           varchar(64)   DEFAULT NULL COMMENT '事务ID',
  `topic`           varchar(128)  DEFAULT NULL COMMENT '原始topic',
  `consumer_group`  varchar(128)  DEFAULT NULL COMMENT '消费者组',
  `message_content` text COMMENT '原始消息内容',
  `error_msg`       varchar(1024) DEFAULT NULL COMMENT '错误信息',
  `status`          tinyint       NOT NULL DEFAULT 0 COMMENT '处理状态：0-待处理，1-自动重试成功，2-已人工处理',
  `retry_count`     int           NOT NULL DEFAULT 0 COMMENT '已自动重试次数',
  `create_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_tx_id` (`tx_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信消息表';
