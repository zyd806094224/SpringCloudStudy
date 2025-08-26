package com.example.serviceorder.dao;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("`order`") // 由于order是MySQL关键字，需要用反引号包裹
@Data
public class OrderEntity {

    /**
     * 订单ID（主键，自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单编号（唯一）
     */
    @TableField("order_no")
    private String orderNo;

    /**
     * 用户ID（关联用户表 user.id）
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 商品ID（关联商品表 product.id）
     */
    @TableField("product_id")
    private Long productId;

    /**
     * 商品名称（冗余存储）
     */
    @TableField("product_name")
    private String productName;

    /**
     * 商品单价（下单时的价格，单位：元）
     */
    @TableField("product_price")
    private BigDecimal productPrice;

    /**
     * 订单总金额（=商品单价×购买数量，单位：元）
     */
    @TableField("order_amount")
    private BigDecimal orderAmount;

    /**
     * 实际支付金额（可能包含折扣，单位：元）
     */
    @TableField("pay_amount")
    private BigDecimal payAmount;

    /**
     * 支付方式（1=支付宝，2=微信，3=银行卡，0=未支付）
     */
    @TableField("pay_type")
    private Integer payType;

    /**
     * 订单状态（0=待支付，1=已支付，2=已发货，3=已完成，4=已取消，5=退款中，6=已退款）
     */
    @TableField("order_status")
    private Integer orderStatus;

    /**
     * 支付时间（支付成功时更新）
     */
    @TableField("pay_time")
    private LocalDateTime payTime;

    /**
     * 订单创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 订单更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除（0=未删除，1=已删除）
     */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
}
