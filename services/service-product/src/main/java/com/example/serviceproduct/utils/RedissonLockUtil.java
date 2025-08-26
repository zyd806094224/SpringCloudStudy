package com.example.serviceproduct.utils;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁工具类（使用 @Autowired 注入）
 */
@Component
public class RedissonLockUtil {

    // 使用 @Autowired 注入 RedissonClient
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 获取锁（阻塞等待，默认30秒自动释放）
     */
    public RLock lock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock(30, TimeUnit.SECONDS);
        return lock;
    }

    /**
     * 尝试获取锁（非阻塞）
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * 释放锁
     */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
