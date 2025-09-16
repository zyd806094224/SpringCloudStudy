package com.example.serviceorder.service.impl;

import com.example.serviceorder.dao.LocalMessage;
import com.example.serviceorder.mapper.LocalMessageMapper;
import com.example.serviceorder.service.MqService;
import lombok.extern.log4j.Log4j2;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Log4j2
@Service
public class MqServiceImpl implements MqService {

    private static final String TRANSFER_TOPIC = "transfer_topic";

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private LocalMessageMapper localMessageMapper;

    @Override
    @Transactional
    public void sendTransferMessage(String txId, String message) {
        try {
            // 发送消息
            rocketMQTemplate.convertAndSend(TRANSFER_TOPIC, message);

            // 消息发送成功后更新状态
            localMessageMapper.updateStatus(txId, 1);
        } catch (Exception e) {
            // 发送失败不抛异常，由定时任务重试
            log.info("消息发送失败，将由定时任务重试: " + e.getMessage());
        }
    }

    @Override
    public void resendPendingMessages() {
        // 查询所有待发送的消息
        List<LocalMessage> pendingMessages = localMessageMapper.selectPendingMessages();

        for (LocalMessage message : pendingMessages) {
            try {
                // 重新发送消息
                rocketMQTemplate.convertAndSend(TRANSFER_TOPIC, message.getMessage());

                // 发送成功更新状态
                localMessageMapper.updateStatus(message.getTxId(), 1);
                log.info("消息重发成功: " + message.getTxId());
            } catch (Exception e) {
                log.info("消息重发失败: " + message.getTxId()+ ", 原因: " + e.getMessage());
            }
        }
    }
}
