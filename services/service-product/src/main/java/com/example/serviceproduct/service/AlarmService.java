package com.example.serviceproduct.service;

/**
 * 告警服务：用于死信等异常场景的通知。
 */
public interface AlarmService {

    /**
     * 发送文本告警。
     *
     * @param content 告警内容
     */
    void sendText(String content);
}
