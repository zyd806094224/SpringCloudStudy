package com.example.serviceorder.task;

import com.example.serviceorder.service.MqService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class MessageResendTask {

    @Autowired
    private MqService mqService;

    // 每30秒执行一次，重试发送未成功的消息
    @Scheduled(fixedRate = 10000)
    public void resendPendingMessages() {
        log.info("开始执行消息重发任务...");
        mqService.resendPendingMessages();
        log.info("消息重发任务执行完毕");
    }
}

