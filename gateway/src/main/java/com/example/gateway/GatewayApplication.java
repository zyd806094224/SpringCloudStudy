package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Cloud Gateway 启动类。
 *
 * 注意：Gateway 是 WebFlux 应用，不要引入 spring-boot-starter-web / @EnableFeignClients 等
 * 基于 Servlet 的组件，否则启动会冲突。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
