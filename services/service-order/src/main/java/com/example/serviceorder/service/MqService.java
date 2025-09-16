package com.example.serviceorder.service;

public interface MqService {
    // 发送消息
    void sendTransferMessage(String txId, String message);

    // 重新发送未成功的消息
    void resendPendingMessages();
}
