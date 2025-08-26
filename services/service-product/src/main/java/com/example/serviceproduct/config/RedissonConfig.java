package com.example.serviceproduct.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    /**
     * 配置 RedissonClient 实例（单机模式）
     */
    @Bean(destroyMethod = "shutdown") // 容器销毁时关闭 Redisson 连接
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 单机模式：指定 Redis 地址（替换为你的 Redis 地址和端口）
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379") // Redis 地址，格式：redis://ip:port
                .setDatabase(0) // 数据库索引（默认 0）
                .setPassword(null) // Redis 密码（无密码则为 null）
                .setConnectionPoolSize(10) // 连接池大小
                .setConnectionMinimumIdleSize(2); // 最小空闲连接数

        // 若为集群模式，替换为：
        // config.useClusterServers()
        //        .addNodeAddress("redis://127.0.0.1:6379", "redis://127.0.0.1:6380")
        //        .setPassword(null);

        return Redisson.create(config);
    }
}
