package com.example.serviceproduct.dao;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 死信消息实体
 */
@TableName("dead_letter_message")
@Data
public class DeadLetterMessageEntity {

    /**
     * 主键（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 事务ID
     */
    private String txId;

    /**
     * 原始 topic
     */
    private String topic;

    /**
     * 消费者组
     */
    private String consumerGroup;

    /**
     * 原始消息内容
     */
    private String messageContent;

    /**
     * 错误信息
     */
    private String errorMsg;

    /**
     * 处理状态：0-待处理，1-自动重试成功，2-已人工处理
     */
    private Integer status;

    /**
     * 已自动重试次数（达到 MAX_RETRY 后停止自动重试，升级人工）
     */
    private Integer retryCount;

    private Date createTime;

    private Date updateTime;
}
