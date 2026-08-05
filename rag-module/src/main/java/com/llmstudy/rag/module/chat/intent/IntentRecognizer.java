package com.llmstudy.rag.module.chat.intent;

import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** 聊天主意图识别端口。 */
public interface IntentRecognizer {

    /**
     * 结合近期会话识别当前问题的唯一主意图并抽取检索焦点。
     *
     * @param query   当前用户问题
     * @param history 按时间正序排列的近期消息
     * @return 规范化后的意图识别结果
     */
    IntentRecognitionResult recognize(String query, List<Message> history);
}
