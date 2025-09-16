package com.example.serviceorder.dao;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.util.Date;

@Data
public class LocalMessage {
    /**
     * 订单ID（主键，自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    private String txId;
    private String message;
    private Integer status; // 0-待发送，1-已发送
    private Date createTime;
    private Date updateTime;
}
