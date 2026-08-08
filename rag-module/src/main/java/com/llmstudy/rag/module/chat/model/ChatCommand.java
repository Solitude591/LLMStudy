package com.llmstudy.rag.module.chat.model;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.UserRole;

/**
 * 与 HTTP DTO 解耦的内部聊天命令。
 *
 * <p>{@link AccessContext} 在 Controller 请求线程中捕获，并随命令进入 Reactor/SSE
 * 流程，避免异步线程读取不到 Sa-Token ThreadLocal 或错误复用其他请求身份。</p>
 */
public record ChatCommand(String conversationId, AccessContext accessContext, String query) {

    public ChatCommand {
        // 空会话 ID 表示创建新会话；已有 ID 则会在会话服务中校验所有者。
        conversationId = conversationId == null || conversationId.isBlank()
                ? null : conversationId.trim();
        if (accessContext == null) {
            throw new IllegalArgumentException("访问上下文不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        query = query.trim();
    }

    /**
     * 提供会话层常用的用户 ID，同时保持身份事实来源仍是 accessContext。
     */
    public String userId() {
        return accessContext.userId();
    }

    /**
     * 便于非 HTTP 调用和单元测试显式构造普通用户上下文。
     * 生产 HTTP 请求应优先使用包含完整组织与角色信息的主构造器。
     */
    public ChatCommand(String conversationId, String userId, String query) {
        this(conversationId, new AccessContext(userId, null, UserRole.USER), query);
    }
}
