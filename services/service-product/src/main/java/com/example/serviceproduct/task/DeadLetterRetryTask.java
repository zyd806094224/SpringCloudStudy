package com.example.serviceproduct.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.serviceproduct.dao.DeadLetterMessageEntity;
import com.example.serviceproduct.mapper.DeadLetterMessageMapper;
import com.example.serviceproduct.service.AlarmService;
import com.example.serviceproduct.service.ConsumeResult;
import com.example.serviceproduct.service.TransferConsumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 死信消息定时重试任务。
 * <p>
 * 扫描 dead_letter_message 表中 status=0（待处理）的死信，重新执行扣减库存逻辑：
 * - 成功 -> status=1（自动重试成功）
 * - 业务失败（库存不足等） -> retryCount+1，达到 MAX_RETRY 后 status=2 升级人工，并告警
 * - 异常（瞬时故障） -> retryCount+1，下次继续重试
 * <p>
 * 扣减库存逻辑复用 TransferConsumeService，与监听器保持一致（含 Redis 幂等）。
 */
@Log4j2
@Component
public class DeadLetterRetryTask {

    /** 自动重试上限，超过后升级人工处理 */
    private static final int MAX_RETRY = 5;

    @Autowired
    private DeadLetterMessageMapper deadLetterMessageMapper;

    @Autowired
    private TransferConsumeService transferConsumeService;

    @Autowired
    private AlarmService alarmService;

    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 每 2 分钟扫描一次待处理的死信消息进行重试。
     */
    @Scheduled(fixedDelay = 120_000L)
    public void retryDeadLetterMessages() {
        // 查询所有待处理的死信消息（status=0）
        QueryWrapper<DeadLetterMessageEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", 0);
        List<DeadLetterMessageEntity> pendingList = deadLetterMessageMapper.selectList(queryWrapper);

        if (pendingList.isEmpty()) {
            return;
        }
        log.info("死信重试任务开始，待处理条数: {}", pendingList.size());

        for (DeadLetterMessageEntity deadLetter : pendingList) {
            try {
                retryOne(deadLetter);
            } catch (Exception e) {
                // 单条异常不影响其他死信重试
                log.error("死信重试异常: id={}, txId={}, error={}",
                        deadLetter.getId(), deadLetter.getTxId(), e.getMessage(), e);
            }
        }
        log.info("死信重试任务结束");
    }

    private void retryOne(DeadLetterMessageEntity deadLetter) {
        // 解析消息内容，取出 productId（txId 复用原消息的）
        Long productId = null;
        try {
            JsonNode jsonNode = objectMapper.readTree(deadLetter.getMessageContent());
            productId = jsonNode.get("productId").asLong();
        } catch (Exception e) {
            log.error("死信消息内容解析失败，无法重试: id={}", deadLetter.getId());
            markAsManual(deadLetter, "消息内容无法解析");
            return;
        }

        try {
            ConsumeResult result = transferConsumeService.deductStock(deadLetter.getTxId(), productId);
            switch (result) {
                case SUCCESS:
                    // 重试成功：标记为已处理
                    updateStatus(deadLetter.getId(), 1, deadLetter.getRetryCount());
                    log.info("死信重试成功: id={}, txId={}", deadLetter.getId(), deadLetter.getTxId());
                    break;
                case ALREADY_PROCESSED:
                    // 已被其他途径处理过（如监听器消费成功），直接销账
                    updateStatus(deadLetter.getId(), 1, deadLetter.getRetryCount());
                    log.info("死信消息此前已处理过，直接销账: id={}, txId={}",
                            deadLetter.getId(), deadLetter.getTxId());
                    break;
                case BUSINESS_FAILED:
                    // 业务失败：重试次数+1，达上限则升级人工
                    handleBusinessFailed(deadLetter);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            // 瞬时故障：重试次数+1，达上限则升级人工
            handleException(deadLetter, e.getMessage());
        }
    }

    /**
     * 业务失败处理：库存不足等情况，retryCount+1，达上限升级人工。
     */
    private void handleBusinessFailed(DeadLetterMessageEntity deadLetter) {
        int newRetryCount = (deadLetter.getRetryCount() == null ? 0 : deadLetter.getRetryCount()) + 1;
        if (newRetryCount >= MAX_RETRY) {
            markAsManual(deadLetter, "业务失败，自动重试已达上限");
        } else {
            updateStatus(deadLetter.getId(), 0, newRetryCount);
            log.info("死信业务失败，等待下次重试: id={}, txId={}, retryCount={}",
                    deadLetter.getId(), deadLetter.getTxId(), newRetryCount);
        }
    }

    /**
     * 瞬时异常处理：retryCount+1，达上限升级人工。
     */
    private void handleException(DeadLetterMessageEntity deadLetter, String errorMsg) {
        int newRetryCount = (deadLetter.getRetryCount() == null ? 0 : deadLetter.getRetryCount()) + 1;
        if (newRetryCount >= MAX_RETRY) {
            markAsManual(deadLetter, "异常重试已达上限: " + errorMsg);
        } else {
            updateStatus(deadLetter.getId(), 0, newRetryCount);
            log.info("死信重试异常，等待下次重试: id={}, txId={}, retryCount={}",
                    deadLetter.getId(), deadLetter.getTxId(), newRetryCount);
        }
    }

    /**
     * 升级为人工处理：status=2 并触发告警。
     */
    private void markAsManual(DeadLetterMessageEntity deadLetter, String reason) {
        int maxRetry = deadLetter.getRetryCount() == null ? MAX_RETRY : deadLetter.getRetryCount() + 1;
        updateStatus(deadLetter.getId(), 2, Math.max(maxRetry, MAX_RETRY));
        log.error("死信升级人工处理: id={}, txId={}, 原因={}", deadLetter.getId(), deadLetter.getTxId(), reason);

        // 触发告警（旁路逻辑）
        alarmService.sendText("死信消息自动重试耗尽，需人工介入：txId=" + deadLetter.getTxId()
                + "，id=" + deadLetter.getId() + "，原因=" + reason);
    }

    /**
     * 更新死信消息状态和重试次数。
     */
    private void updateStatus(Long id, int status, int retryCount) {
        UpdateWrapper<DeadLetterMessageEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", id)
                .set("status", status)
                .set("retry_count", retryCount)
                .set("update_time", new Date());
        deadLetterMessageMapper.update(null, updateWrapper);
    }
}
