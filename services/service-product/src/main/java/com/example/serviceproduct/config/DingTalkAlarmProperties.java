package com.example.serviceproduct.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉告警配置（示例：占位配置，实际 webhook/secret 需替换为真实值）。
 * <p>
 * 对应 application.yml 中：
 * <pre>
 * alarm:
 *   ding-talk:
 *     enabled: false
 *     webhook: https://oapi.dingtalk.com/robot/send?access_token=YOUR_TOKEN
 *     secret: YOUR_SECRET
 *     keyword: "[库存服务]"   # 消息前缀，需与钉钉机器人安全设置的关键词一致
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "alarm.ding-talk")
public class DingTalkAlarmProperties {

    /** 是否启用钉钉告警 */
    private boolean enabled = false;

    /** 钉钉机器人 webhook 地址 */
    private String webhook;

    /** 加签密钥（与机器人安全设置一致） */
    private String secret;

    /** 消息关键词前缀（需在机器人安全设置中配置） */
    private String keyword = "[告警]";
}
