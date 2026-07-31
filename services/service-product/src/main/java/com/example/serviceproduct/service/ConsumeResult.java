package com.example.serviceproduct.service;

/**
 * 库存扣减消费结果
 */
public enum ConsumeResult {

    /** 业务处理成功（本次扣减了库存） */
    SUCCESS,

    /** 已处理过，幂等跳过（没有重复扣减） */
    ALREADY_PROCESSED,

    /** 业务失败（库存不足、商品不存在等），重试也无意义 */
    BUSINESS_FAILED
}
