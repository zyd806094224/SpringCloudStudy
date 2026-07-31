package com.example.serviceproduct.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.serviceproduct.dao.ProductEntity;
import com.example.serviceproduct.mapper.ProductMapper;
import com.example.serviceproduct.service.ConsumeResult;
import com.example.serviceproduct.service.TransferConsumeService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 扣减库存 + Redis 幂等控制的核心实现。
 * <p>
 * 幂等策略：基于 txId + Redis SETNX。
 * - 消费前 SETNX 抢锁（PROCESSING），TTL 24h
 * - 抢锁失败 -> 已处理过 -> 幂等跳过
 * - 扣减成功 -> 标记为 SUCCESS
 * - 业务失败 -> 删除 key（让后续重试可重新处理），返回 BUSINESS_FAILED
 * <p>
 * 监听器（TransferMessageListener）和死信定时重试任务（DeadLetterRetryTask）共用此逻辑。
 */
@Log4j2
@Service
public class TransferConsumeServiceImpl implements TransferConsumeService {

    /** 幂等 key 前缀，完整 key = IDEMPOTENT_KEY_PREFIX + txId */
    private static final String IDEMPOTENT_KEY_PREFIX = "consume:transfer:";

    /** 幂等 key 的过期时间，防止 key 永久残留 */
    private static final long IDEMPOTENT_EXPIRE_HOURS = 24L;

    /** 消息处理中标记 */
    private static final String STATUS_PROCESSING = "PROCESSING";

    /** 消息处理成功标记 */
    private static final String STATUS_SUCCESS = "SUCCESS";

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public ConsumeResult deductStock(String txId, Long productId) {
        // 幂等控制：SETNX 抢锁，已存在说明正在处理或已处理过 -> 直接跳过
        String idempotentKey = IDEMPOTENT_KEY_PREFIX + txId;
        Boolean firstTime = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, STATUS_PROCESSING, IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
        if (firstTime == null || !firstTime) {
            log.info("消息已处理过，幂等跳过: txId={}", txId);
            return ConsumeResult.ALREADY_PROCESSED;
        }

        try {
            // 使用 QueryWrapper 构造查询条件
            QueryWrapper<ProductEntity> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("id", productId);
            ProductEntity productEntity = productMapper.selectOne(queryWrapper);

            boolean success = false;
            if (productEntity != null && productEntity.getStock() > 0) {
                // 扣减库存
                productEntity.setStock(productEntity.getStock() - 1);
                success = productMapper.updateById(productEntity) > 0;
            }

            if (success) {
                // 扣减成功：更新幂等标记为 SUCCESS，防止重试时重复扣库存
                stringRedisTemplate.opsForValue()
                        .set(idempotentKey, STATUS_SUCCESS, IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
                log.info("扣减库存成功: txId={}, productId={}", txId, productId);
                return ConsumeResult.SUCCESS;
            } else {
                // 业务失败：释放幂等锁，让后续重试（如人工补库存后）可重新处理
                stringRedisTemplate.delete(idempotentKey);
                log.info("扣减库存失败（库存不足或商品不存在）: txId={}, productId={}", txId, productId);
                return ConsumeResult.BUSINESS_FAILED;
            }
        } catch (Exception e) {
            // 异常：释放幂等锁，保证后续重试可以重新抢锁处理
            stringRedisTemplate.delete(idempotentKey);
            log.info("扣减库存异常: txId={}, error={}", txId, e.getMessage());
            throw e;
        }
    }
}
