package com.example.serviceproduct.listener;

import com.example.serviceproduct.dao.DeadLetterMessageEntity;
import com.example.serviceproduct.mapper.DeadLetterMessageMapper;
import com.example.serviceproduct.service.AlarmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * transfer_topic 的死信队列监听器。
 * <p>
 * RocketMQ 死信 topic 命名规则：%DLQ%{消费者组}
 * 这里 transfer_consumer_group 消费失败重试耗尽（maxReconsumeTimes=3）后，
 * 消息会被投递到 %DLQ%transfer_consumer_group。
 * <p>
 * 该监听器把死信消息落库 + 触发钉钉告警，便于后续人工排查或定时任务重试。
 * 注意：DLQ 消费失败不再抛异常，避免在 DLQ 内部无限重试形成死循环。
 */
@Log4j2
@Component
@RocketMQMessageListener(
        topic = "%DLQ%transfer_consumer_group",
        consumerGroup = "transfer_dlq_consumer_group"
)
public class TransferDlqListener implements RocketMQListener<String> {

    private static final String ORIGINAL_TOPIC = "transfer_topic";

    private static final String ORIGINAL_CONSUMER_GROUP = "transfer_consumer_group";

    @Autowired
    private DeadLetterMessageMapper deadLetterMessageMapper;

    @Autowired
    private AlarmService alarmService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String message) {
        DeadLetterMessageEntity entity = new DeadLetterMessageEntity();
        try {
            // 尽量解析出 txId，解析失败也不影响落库
            String txId = null;
            try {
                JsonNode jsonNode = objectMapper.readTree(message);
                JsonNode txIdNode = jsonNode.get("txId");
                if (txIdNode != null) {
                    txId = txIdNode.asText();
                }
            } catch (Exception parseEx) {
                log.warn("死信消息非JSON格式，无法解析txId: {}", message);
            }

            entity.setTxId(txId);
            entity.setTopic(ORIGINAL_TOPIC);
            entity.setConsumerGroup(ORIGINAL_CONSUMER_GROUP);
            entity.setMessageContent(message);
            entity.setStatus(0); // 0-待处理
            entity.setRetryCount(0);
            deadLetterMessageMapper.insert(entity);

            log.error("收到死信消息并已落库，请人工介入处理: txId={}, message={}", txId, message);

            // 触发钉钉告警（旁路逻辑，内部失败不会抛异常）
            alarmService.sendText("死信消息落库：txId=" + txId
                    + "，topic=" + ORIGINAL_TOPIC
                    + "，请及时人工介入处理");
        } catch (Exception e) {
            // 落库失败仅记录日志，不抛异常，避免 DLQ 内部无限重试
            log.error("死信消息落库失败: message={}, error={}", message, e.getMessage(), e);
        }
    }
}
