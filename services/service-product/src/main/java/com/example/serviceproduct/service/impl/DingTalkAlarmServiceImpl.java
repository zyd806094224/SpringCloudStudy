package com.example.serviceproduct.service.impl;

import com.example.serviceproduct.config.DingTalkAlarmProperties;
import com.example.serviceproduct.service.AlarmService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉机器人告警实现（文本消息 + 加签）。
 * <p>
 * 注意：
 * - alarm.ding-talk.enabled=false 时直接跳过，不发送。
 * - 发送失败仅记录日志，不抛异常（告警是旁路逻辑，不能影响主流程）。
 */
@Log4j2
@Service
public class DingTalkAlarmServiceImpl implements AlarmService {

    @Autowired
    private DingTalkAlarmProperties properties;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public void sendText(String content) {
        if (!properties.isEnabled()) {
            // 告警未启用，跳过
            return;
        }
        try {
            // 拼接关键词前缀（需与钉钉机器人安全设置的关键词一致）
            String fullContent = properties.getKeyword() + " " + content;

            Map<String, Object> text = new HashMap<>();
            text.put("content", fullContent);

            Map<String, Object> body = new HashMap<>();
            body.put("msgtype", "text");
            body.put("text", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            String url = buildSignedUrl();
            restTemplate.postForObject(url, request, String.class);
            log.info("钉钉告警发送成功");
        } catch (Exception e) {
            // 告警是旁路逻辑，失败仅记录日志，不抛异常
            log.error("钉钉告警发送失败: {}", e.getMessage());
        }
    }

    /**
     * 构造加签后的 webhook URL（钉钉机器人 "加签" 安全设置）。
     */
    private String buildSignedUrl() throws Exception {
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + properties.getSecret();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        return properties.getWebhook() + "&timestamp=" + timestamp + "&sign=" + sign;
    }
}
