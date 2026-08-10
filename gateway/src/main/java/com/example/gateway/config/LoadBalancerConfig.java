package com.example.gateway.config;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 负载均衡策略配置。
 *
 * Spring Cloud LoadBalancer 4.x 通过 @LoadBalancerClients 指定策略类来覆盖默认行为。
 * defaultConfiguration 指向哪个内部类，就用哪个策略。
 *
 * LoadBalancer 内置两种策略实现：
 *   - RoundRobinLoadBalancer：轮询（默认），请求依次轮流分给各实例，分布均匀
 *   - RandomLoadBalancer：    随机，每次随机选一个实例，实现简单、无状态
 *
 * 切换方法：把 defaultConfiguration 改成 RandomConfig.class，并取消 RandomConfig 的注释。
 * 注意：两个策略类不能同时启用，否则它们的 @Bean 方法同名会冲突。
 */
@Configuration
@LoadBalancerClients(defaultConfiguration = LoadBalancerConfig.RoundRobinConfig.class)
public class LoadBalancerConfig {

    /**
     * 轮询策略（当前启用）。
     * 请求依次分给实例1、实例2、实例3、实例1、实例2... 循环。
     */
    public static class RoundRobinConfig {
        @Bean
        ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(
                Environment environment, LoadBalancerClientFactory factory) {
            String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
            return new RoundRobinLoadBalancer(
                    factory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class), serviceId);
        }
    }

    /**
     * 随机策略。
     * 每次请求从实例列表里随机挑一个，长周期下接近平均分布。
     *
     * 要切换为随机策略：
     *   1. 取消下面类的注释
     *   2. 把 @LoadBalancerClients 的 defaultConfiguration 改成 RandomConfig.class
     *   3. （可选）给下面的 @Bean 方法改个不同的名字，如 reactorRandomLoadBalancer，避免任何潜在的 bean 名冲突
     */
    // public static class RandomConfig {
    //     @Bean
    //     ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(
    //             Environment environment, LoadBalancerClientFactory factory) {
    //         String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
    //         return new RandomLoadBalancer(
    //                 factory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class), serviceId);
    //     }
    // }
}
