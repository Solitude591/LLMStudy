(() => {
    "use strict";

    const TITLE_SYNC_MAX_ATTEMPTS = 12;
    const TITLE_SYNC_INTERVAL_MS = 1_000;

    const elements = {
        sidebar: document.querySelector("#sidebar"),
        sidebarBackdrop: document.querySelector("#sidebarBackdrop"),
        openSidebarButton: document.querySelector("#openSidebarButton"),
        closeSidebarButton: document.querySelector("#closeSidebarButton"),
        newChatButton: document.querySelector("#newChatButton"),
        sessionSearchInput: document.querySelector("#sessionSearchInput"),
        sessionList: document.querySelector("#sessionList"),
        sessionCount: document.querySelector("#sessionCount"),
        userIdInput: document.querySelector("#userIdInput"),
        modeButtons: [...document.querySelectorAll(".mode-button")],
        endpointLabel: document.querySelector("#endpointLabel"),
        connectionDot: document.querySelector("#connectionDot"),
        conversationTitle: document.querySelector("#conversationTitle"),
        conversationIdButton: document.querySelector("#conversationIdButton"),
        headerStatus: document.querySelector(".header-status"),
        requestStatus: document.querySelector("#requestStatus"),
        messageViewport: document.querySelector("#messageViewport"),
        messageList: document.querySelector("#messageList"),
        composerForm: document.querySelector("#composerForm"),
        messageInput: document.querySelector("#messageInput"),
        composerContextStatus: document.querySelector(".composer-context-status"),
        characterCount: document.querySelector("#characterCount"),
        sendButton: document.querySelector("#sendButton"),
        stopButton: document.querySelector("#stopButton"),
        errorBanner: document.querySelector("#errorBanner"),
        toast: document.querySelector("#toast")
    };

    /** 会话和消息只作为当前页面的短期内存状态，真实数据以 MySQL 为准。 */
    const state = {
        sessions: [],
        activeSessionKey: null,
        mode: "stream",
        busy: false,
        loading: false,
        abortController: null,
        toastTimer: null
    };

    function transientId(prefix) {
        const value = globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        return `${prefix}-${value}`;
    }

    function createDraftSession() {
        return {
            key: transientId("draft"),
            conversationId: null,
            title: "新对话",
            messages: [],
            updatedAt: new Date().toISOString()
        };
    }

    function currentSession() {
        let session = state.sessions.find(item => item.key === state.activeSessionKey);
        if (!session) {
            session = createDraftSession();
            state.sessions.unshift(session);
            state.activeSessionKey = session.key;
        }
        return session;
    }

    function apiUrl(path) {
        if (window.location.protocol === "file:") {
            return `http://localhost:8080${path}`;
        }
        return new URL(path, window.location.origin).toString();
    }

    function endpointFor(mode = state.mode) {
        return apiUrl(mode === "stream" ? "/chat/client/stream" : "/chat/client/ask");
    }

    function conversationsEndpoint() {
        const userId = elements.userIdInput.value.trim() || "default";
        return apiUrl(`/chat/client/conversations?userId=${encodeURIComponent(userId)}`);
    }

    function conversationEndpoint(conversationId) {
        return apiUrl(`/chat/client/conversations/${encodeURIComponent(conversationId)}`);
    }

    function messagesEndpoint(conversationId) {
        return `${conversationEndpoint(conversationId)}/messages`;
    }

    function relativeTime(value) {
        const timestamp = new Date(value).getTime();
        const elapsed = Date.now() - timestamp;
        if (!Number.isFinite(elapsed) || elapsed < 60_000) return "刚刚";
        if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
        if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
        return new Date(timestamp).toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" });
    }

    function messageTime(value) {
        const date = value ? new Date(value) : new Date();
        if (!Number.isFinite(date.getTime())) return "";
        return date.toLocaleTimeString("zh-CN", {
            hour: "2-digit",
            minute: "2-digit",
            hour12: false
        });
    }

    function mapConversation(item) {
        return {
            key: item.conversationId,
            conversationId: item.conversationId,
            title: item.title?.trim() || "未命名会话",
            status: item.status,
            updatedAt: item.updatedAt,
            messages: []
        };
    }

    function mapMessage(item) {
        return {
            localId: item.messageId || transientId("message"),
            messageId: item.messageId ?? null,
            role: item.type === "USER" ? "user" : "assistant",
            content: item.content ?? "",
            pending: false,
            failed: false,
            tokenCount: item.tokenCount ?? null,
            modelName: item.modelName ?? null,
            createdAt: item.createdAt ?? new Date().toISOString()
        };
    }

    /** 重新查询活跃会话，并从数据库恢复选中会话的全部消息。 */
    async function refreshConversations(preferredConversationId = null) {
        state.loading = true;
        renderSessions();
        try {
            const response = await fetch(conversationsEndpoint(), {
                headers: { "Accept": "application/json" }
            });
            const body = await readJsonResponse(response);
            const serverSessions = (Array.isArray(body) ? body : []).map(mapConversation);
            const current = state.sessions.find(item => item.key === state.activeSessionKey);
            const activeConversationId = preferredConversationId
                ?? current?.conversationId
                ?? null;

            // 未发送的新对话可在当前页面保留，但不会持久化到浏览器。
            const draft = !preferredConversationId && current && !current.conversationId
                ? current : null;
            state.sessions = draft ? [draft, ...serverSessions] : serverSessions;

            const selected = activeConversationId
                ? state.sessions.find(item => item.conversationId === activeConversationId)
                : null;
            if (selected) {
                state.activeSessionKey = selected.key;
            } else if (draft) {
                state.activeSessionKey = draft.key;
            } else if (state.sessions.length > 0) {
                state.activeSessionKey = state.sessions[0].key;
            } else {
                const newDraft = createDraftSession();
                state.sessions = [newDraft];
                state.activeSessionKey = newDraft.key;
            }

            const active = currentSession();
            if (active.conversationId) {
                await loadMessages(active);
            }
            elements.connectionDot.classList.remove("is-error");
        } finally {
            state.loading = false;
            renderAll();
        }
    }

    async function loadMessages(session) {
        const response = await fetch(messagesEndpoint(session.conversationId), {
            headers: { "Accept": "application/json" }
        });
        const body = await readJsonResponse(response);
        session.messages = (Array.isArray(body) ? body : []).map(mapMessage);
    }

    function renderSessions() {
        const keyword = elements.sessionSearchInput.value.trim().toLowerCase();
        const visibleSessions = state.sessions.filter(session =>
            !keyword || session.title.toLowerCase().includes(keyword)
            || (session.conversationId ?? "").toLowerCase().includes(keyword));

        elements.sessionList.replaceChildren();
        elements.sessionCount.textContent = String(
            state.sessions.filter(session => session.conversationId).length
        );

        if (state.loading && state.sessions.length === 0) {
            const loading = document.createElement("p");
            loading.className = "session-empty";
            loading.textContent = "正在从数据库加载会话…";
            elements.sessionList.append(loading);
            return;
        }
        if (visibleSessions.length === 0) {
            const empty = document.createElement("p");
            empty.className = "session-empty";
            empty.textContent = keyword ? "没有匹配的数据库会话。" : "发送第一条消息后，会话会显示在这里。";
            elements.sessionList.append(empty);
            return;
        }

        visibleSessions.forEach(session => {
            const item = document.createElement("div");
            item.className = `session-item${session.key === state.activeSessionKey ? " is-active" : ""}`;
            item.tabIndex = 0;
            item.setAttribute("role", "button");
            item.setAttribute("aria-label", `打开会话：${session.title}`);

            const copy = document.createElement("div");
            copy.className = "session-copy";
            const title = document.createElement("strong");
            title.textContent = session.title;
            const time = document.createElement("span");
            const messageLabel = session.conversationId
                ? (session.messages.length > 0 ? `${session.messages.length} 条消息` : "数据库会话")
                : "尚未保存";
            time.textContent = `${messageLabel} · ${relativeTime(session.updatedAt)}`;
            copy.append(title, time);
            item.append(copy);

            if (session.conversationId) {
                const remove = document.createElement("button");
                remove.className = "session-remove";
                remove.type = "button";
                remove.textContent = "×";
                remove.title = "删除会话";
                remove.setAttribute("aria-label", `删除会话：${session.title}`);
                remove.addEventListener("click", event => {
                    event.stopPropagation();
                    void deleteSession(session);
                });
                item.append(remove);
            }

            const select = () => void selectSession(session.key);
            item.addEventListener("click", select);
            item.addEventListener("keydown", event => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    select();
                }
            });
            elements.sessionList.append(item);
        });
    }

    function renderWelcome() {
        const wrapper = document.createElement("div");
        wrapper.className = "welcome-state";
        const card = document.createElement("div");
        card.className = "welcome-card";
        const kicker = document.createElement("p");
        kicker.className = "welcome-kicker";
        const liveDot = document.createElement("span");
        liveDot.className = "welcome-live-dot";
        liveDot.setAttribute("aria-hidden", "true");
        const kickerText = document.createElement("span");
        kickerText.textContent = "LIVE RAG WORKSPACE";
        kicker.append(liveDot, kickerText);
        const title = document.createElement("h2");
        title.textContent = "把知识库，变成答案。";
        const description = document.createElement("p");
        description.textContent = "会话与历史消息均从 MySQL 读取，可在这里验证完整 RAG 链路。";

        const pipeline = document.createElement("div");
        pipeline.className = "pipeline-strip";
        [["01", "Query Rewrite"], ["02", "Hybrid Retrieval"],
            ["03", "BGE Rerank"], ["04", "Streaming Answer"]]
            .forEach(([step, label]) => {
                const item = document.createElement("span");
                item.className = "pipeline-item";
                const number = document.createElement("b");
                number.textContent = step;
                const copy = document.createElement("span");
                copy.textContent = label;
                item.append(number, copy);
                pipeline.append(item);
            });

        const suggestions = document.createElement("div");
        suggestions.className = "suggestion-grid";
        [
            { icon: "⌘", label: "U-Net 结构", text: "U-Net 的收缩路径和扩张路径分别起什么作用？跳跃连接是如何帮助精确定位的？" },
            { icon: "≋", label: "方法对比", text: "对比 U-Net、nnU-Net、UNETR 和 MedSAM 的核心设计目标。" },
            { icon: "✦", label: "MedSAM 实验", text: "MedSAM 使用了哪些医学图像模态进行训练？论文如何证明它的泛化能力？" }
        ].forEach(suggestion => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "suggestion-button";
            const icon = document.createElement("span");
            icon.className = "suggestion-icon";
            icon.textContent = suggestion.icon;
            const label = document.createElement("strong");
            label.textContent = suggestion.label;
            const text = document.createElement("span");
            text.textContent = suggestion.text;
            button.append(icon, label, text);
            button.addEventListener("click", () => {
                elements.messageInput.value = suggestion.text;
                resizeComposer();
                elements.messageInput.focus();
            });
            suggestions.append(button);
        });

        card.append(kicker, title, description, pipeline, suggestions);
        wrapper.append(card);
        elements.messageList.append(wrapper);
    }

    function createMessageNode(message) {
        const row = document.createElement("article");
        row.className = `message-row is-${message.role}`;
        row.dataset.localMessageId = message.localId;
        const avatar = document.createElement("div");
        avatar.className = "message-avatar";
        avatar.textContent = message.role === "user" ? "你" : "AI";
        avatar.setAttribute("aria-hidden", "true");
        const body = document.createElement("div");
        body.className = "message-body";
        const author = document.createElement("div");
        author.className = "message-author";
        author.textContent = message.role === "user" ? "你" : "RAG 助手";
        const time = document.createElement("span");
        time.className = "message-time";
        time.textContent = messageTime(message.createdAt);
        author.append(time);
        const content = document.createElement("p");
        content.className = "message-content";
        if (message.pending) content.classList.add("is-streaming");
        if (message.failed) content.classList.add("is-error");
        content.textContent = message.content || (message.pending ? "正在思考…" : "");
        body.append(author, content);

        if (message.role === "assistant" && !message.pending) {
            const meta = document.createElement("div");
            meta.className = "message-meta";
            if (message.modelName) appendMeta(meta, `模型 ${message.modelName}`);
            if (message.tokenCount != null) appendMeta(meta, `${message.tokenCount} tokens`);
            if (message.messageId) appendMeta(meta, `ID ${shortId(message.messageId)}`);
            if (message.content) {
                const copy = document.createElement("button");
                copy.type = "button";
                copy.textContent = "复制回答";
                copy.addEventListener("click", () => copyText(message.content, "回答已复制"));
                meta.append(copy);
            }
            body.append(meta);
        }
        row.append(avatar, body);
        return row;
    }

    function appendMeta(container, text) {
        const span = document.createElement("span");
        span.textContent = text;
        container.append(span);
    }

    function shortId(value) {
        if (!value || value.length <= 18) return value ?? "";
        return `${value.slice(0, 8)}…${value.slice(-6)}`;
    }

    function renderMessages() {
        const session = currentSession();
        elements.messageList.replaceChildren();
        if (session.messages.length === 0) renderWelcome();
        else session.messages.forEach(message => elements.messageList.append(createMessageNode(message)));
        renderConversationHeader();
        requestAnimationFrame(scrollToBottom);
    }

    function updateMessageNode(message) {
        const selector = `[data-local-message-id="${CSS.escape(message.localId)}"]`;
        const row = elements.messageList.querySelector(selector);
        if (!row) {
            renderMessages();
            return;
        }
        const content = row.querySelector(".message-content");
        content.textContent = message.content || (message.pending ? "正在思考…" : "");
        content.classList.toggle("is-streaming", Boolean(message.pending));
        content.classList.toggle("is-error", Boolean(message.failed));
        scrollToBottom();
    }

    function renderConversationHeader() {
        const session = currentSession();
        elements.conversationTitle.textContent = session.title;
        if (session.conversationId) {
            elements.conversationIdButton.textContent = session.conversationId;
            elements.conversationIdButton.disabled = false;
        } else {
            elements.conversationIdButton.textContent = "尚未创建会话";
            elements.conversationIdButton.disabled = true;
        }
    }

    function scrollToBottom() {
        elements.messageViewport.scrollTo({
            top: elements.messageViewport.scrollHeight,
            behavior: state.busy ? "auto" : "smooth"
        });
    }

    async function selectSession(sessionKey) {
        if (state.busy || state.loading) {
            showToast("请先等待当前操作完成");
            return;
        }
        const session = state.sessions.find(item => item.key === sessionKey);
        if (!session) return;
        state.activeSessionKey = session.key;
        renderAll();
        closeSidebar();
        if (!session.conversationId) return;

        state.loading = true;
        setStatus("正在读取历史", "busy");
        try {
            await loadMessages(session);
            setStatus("准备就绪", "ready");
            elements.connectionDot.classList.remove("is-error");
        } catch (error) {
            elements.connectionDot.classList.add("is-error");
            setStatus("加载失败", "error");
            showError(error.message);
        } finally {
            state.loading = false;
            renderAll();
        }
    }

    function newChat() {
        if (state.busy || state.loading) {
            showToast("请先等待当前操作完成");
            return;
        }
        const active = currentSession();
        if (!active.conversationId && active.messages.length === 0) {
            elements.messageInput.focus();
            closeSidebar();
            return;
        }
        const draft = createDraftSession();
        state.sessions.unshift(draft);
        state.activeSessionKey = draft.key;
        renderAll();
        closeSidebar();
        elements.messageInput.focus();
    }

    async function deleteSession(session) {
        if (state.busy || state.loading) {
            showToast("请先等待当前操作完成");
            return;
        }
        if (!window.confirm(`确定删除会话“${session.title}”吗？`)) return;

        state.loading = true;
        clearError();
        setStatus("正在删除会话", "busy");
        try {
            const response = await fetch(conversationEndpoint(session.conversationId), {
                method: "DELETE",
                headers: { "Accept": "application/json" }
            });
            if (!response.ok) await throwResponseError(response);

            const active = currentSession();
            const preferred = active.conversationId === session.conversationId
                ? null : active.conversationId;
            state.sessions = state.sessions.filter(item => item.key !== session.key);
            state.activeSessionKey = preferred;
            state.loading = false;
            await refreshConversations(preferred);
            setStatus("准备就绪", "ready");
            showToast("会话已删除，数据库状态已更新为 DELETED");
        } catch (error) {
            state.loading = false;
            elements.connectionDot.classList.add("is-error");
            setStatus("删除失败", "error");
            showError(error.message);
            renderAll();
        }
    }

    function titleFromQuery(query) {
        const normalized = query.replace(/\s+/g, " ").trim();
        return normalized.length <= 30 ? normalized : `${normalized.slice(0, 30)}…`;
    }

    function appendTransientMessage(role, content, pending = false) {
        const session = currentSession();
        const message = {
            localId: transientId("message"),
            messageId: null,
            role,
            content,
            pending,
            failed: false,
            tokenCount: null,
            modelName: null,
            createdAt: new Date().toISOString()
        };
        session.messages.push(message);
        session.updatedAt = new Date().toISOString();
        return message;
    }

    async function sendMessage() {
        if (state.busy || state.loading) return;
        const query = elements.messageInput.value.trim();
        if (!query) {
            showError("请输入需要发送的问题。");
            elements.messageInput.focus();
            return;
        }

        const userId = elements.userIdInput.value.trim() || "default";
        const session = currentSession();
        const firstMessage = session.messages.length === 0;
        clearError();
        if (firstMessage) session.title = titleFromQuery(query);
        const userMessage = appendTransientMessage("user", query);
        const assistantMessage = appendTransientMessage("assistant", "", true);
        renderAll();

        elements.messageInput.value = "";
        resizeComposer();
        setBusy(true, state.mode === "stream" ? "正在流式生成" : "等待完整响应");
        const payload = { conversationId: session.conversationId, userId, query };
        state.abortController = new AbortController();
        let requestSucceeded = false;

        try {
            if (state.mode === "stream") {
                await sendStreaming(payload, session, userMessage, assistantMessage);
            } else {
                await sendSynchronous(payload, session, userMessage, assistantMessage);
            }
            requestSucceeded = true;
            elements.connectionDot.classList.remove("is-error");
            setStatus("请求完成", "ready");
        } catch (error) {
            if (error.name === "AbortError") {
                assistantMessage.content = assistantMessage.content || "已停止生成。";
                setStatus("已停止", "ready");
            } else {
                assistantMessage.failed = true;
                assistantMessage.content = assistantMessage.content || `请求失败：${error.message}`;
                elements.connectionDot.classList.add("is-error");
                setStatus("请求失败", "error");
                showError(error.message);
            }
            assistantMessage.pending = false;
        } finally {
            state.abortController = null;
            setBusy(false);
            const conversationId = session.conversationId;
            const temporaryTitle = session.title;
            if (conversationId) {
                try {
                    // 发送完成后立即回读 MySQL，页面不依赖刚才的临时消息对象。
                    await refreshConversations(conversationId);
                } catch (error) {
                    console.warn("回读数据库会话失败", error);
                    renderAll();
                }
                if (requestSucceeded && firstMessage) {
                    void synchronizeConversationTitle(conversationId, temporaryTitle);
                }
            } else {
                renderAll();
            }
        }
    }

    async function sendSynchronous(payload, session, userMessage, assistantMessage) {
        const response = await fetch(endpointFor("sync"), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
            signal: state.abortController.signal
        });
        const body = await readJsonResponse(response);
        bindServerConversation(session, body.conversationId, body.conversationTitle);
        userMessage.messageId = body.userMessageId;
        assistantMessage.messageId = body.assistantMessageId;
        assistantMessage.content = body.content ?? "";
        assistantMessage.tokenCount = body.tokenCount ?? null;
        assistantMessage.modelName = body.modelName ?? null;
        assistantMessage.pending = false;
    }

    async function sendStreaming(payload, session, userMessage, assistantMessage) {
        const response = await fetch(endpointFor("stream"), {
            method: "POST",
            headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
            body: JSON.stringify(payload),
            signal: state.abortController.signal
        });
        if (!response.ok) await throwResponseError(response);
        if (!response.body) throw new Error("浏览器未提供可读取的流式响应体");

        let receivedDone = false;
        await consumeEventStream(response.body, event => {
            switch (event.event) {
                case "START":
                    bindServerConversation(session, event.conversationId, event.conversationTitle);
                    userMessage.messageId = event.userMessageId;
                    renderConversationHeader();
                    renderSessions();
                    break;
                case "DELTA":
                    assistantMessage.content += event.content ?? "";
                    updateMessageNode(assistantMessage);
                    break;
                case "DONE":
                    assistantMessage.messageId = event.assistantMessageId;
                    assistantMessage.tokenCount = event.tokenCount ?? null;
                    assistantMessage.modelName = event.modelName ?? null;
                    assistantMessage.pending = false;
                    receivedDone = true;
                    break;
                default:
                    console.debug("忽略未知 SSE 事件", event);
            }
        });
        if (!receivedDone) throw new Error("流式响应已结束，但没有收到 DONE 事件");
    }

    function bindServerConversation(session, conversationId, title) {
        if (!conversationId) return;
        session.conversationId = conversationId;
        session.key = conversationId;
        session.title = title?.trim() || session.title;
        state.activeSessionKey = conversationId;
    }

    async function synchronizeConversationTitle(conversationId, temporaryTitle) {
        for (let attempt = 0; attempt < TITLE_SYNC_MAX_ATTEMPTS; attempt += 1) {
            await delay(TITLE_SYNC_INTERVAL_MS);
            try {
                const response = await fetch(conversationEndpoint(conversationId), {
                    headers: { "Accept": "application/json" }
                });
                const body = await readJsonResponse(response);
                const session = state.sessions.find(item => item.conversationId === conversationId);
                if (!session) return;
                const databaseTitle = body.title?.trim();
                if (databaseTitle && databaseTitle !== session.title) {
                    session.title = databaseTitle;
                    renderSessions();
                    if (session.key === state.activeSessionKey) renderConversationHeader();
                }
                if (databaseTitle && databaseTitle !== temporaryTitle) return;
            } catch (error) {
                console.warn("同步会话标题失败，稍后重试", error);
            }
        }
    }

    function delay(milliseconds) {
        return new Promise(resolve => window.setTimeout(resolve, milliseconds));
    }

    async function consumeEventStream(stream, onEvent) {
        const reader = stream.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            buffer = buffer.replace(/\r\n/g, "\n");
            let boundary;
            while ((boundary = buffer.indexOf("\n\n")) >= 0) {
                const block = buffer.slice(0, boundary).trim();
                buffer = buffer.slice(boundary + 2);
                if (block) parseEventBlock(block, onEvent);
            }
        }
        buffer += decoder.decode();
        const tail = buffer.replace(/\r\n/g, "\n").trim();
        if (tail) parseEventBlock(tail, onEvent);
    }

    function parseEventBlock(block, onEvent) {
        const dataLines = block.split("\n")
            .filter(line => line.startsWith("data:"))
            .map(line => line.slice(5).trimStart());
        const raw = dataLines.length > 0 ? dataLines.join("\n") : block;
        if (!raw || raw === "[DONE]") return;
        try {
            onEvent(JSON.parse(raw));
        } catch (_) {
            throw new Error(`无法解析流式事件：${raw.slice(0, 160)}`);
        }
    }

    async function readJsonResponse(response) {
        const text = await response.text();
        let body;
        try {
            body = text ? JSON.parse(text) : {};
        } catch (_) {
            throw new Error(`接口返回了非 JSON 内容（HTTP ${response.status}）`);
        }
        if (!response.ok) {
            throw new Error(body.message ?? body.error ?? `接口请求失败（HTTP ${response.status}）`);
        }
        return body.data ?? body;
    }

    async function throwResponseError(response) {
        const text = await response.text();
        try {
            const body = JSON.parse(text);
            throw new Error(body.message ?? body.error ?? `接口请求失败（HTTP ${response.status}）`);
        } catch (error) {
            if (error instanceof SyntaxError) {
                throw new Error(text || `接口请求失败（HTTP ${response.status}）`);
            }
            throw error;
        }
    }

    function setBusy(busy, label) {
        state.busy = busy;
        elements.sendButton.disabled = busy;
        elements.stopButton.hidden = !busy;
        elements.modeButtons.forEach(button => button.disabled = busy);
        elements.newChatButton.disabled = busy;
        elements.composerContextStatus.textContent = busy ? "处理中" : "已就绪";
        elements.composerContextStatus.classList.toggle("is-busy", busy);
        if (busy) setStatus(label ?? "请求中", "busy");
    }

    function setStatus(label, status) {
        elements.requestStatus.textContent = label;
        elements.headerStatus.classList.toggle("is-busy", status === "busy");
        elements.headerStatus.classList.toggle("is-error", status === "error");
        if (status === "error") elements.composerContextStatus.textContent = "请检查服务";
    }

    function showError(message) {
        elements.errorBanner.textContent = message;
        elements.errorBanner.hidden = false;
    }

    function clearError() {
        elements.errorBanner.textContent = "";
        elements.errorBanner.hidden = true;
    }

    function showToast(message) {
        window.clearTimeout(state.toastTimer);
        elements.toast.textContent = message;
        elements.toast.hidden = false;
        state.toastTimer = window.setTimeout(() => {
            elements.toast.hidden = true;
        }, 2600);
    }

    async function copyText(text, successMessage) {
        try {
            await navigator.clipboard.writeText(text);
            showToast(successMessage);
        } catch (_) {
            showToast("浏览器未允许复制，请手动选择文本");
        }
    }

    function resizeComposer() {
        elements.messageInput.style.height = "auto";
        elements.messageInput.style.height = `${Math.min(elements.messageInput.scrollHeight, 180)}px`;
        elements.characterCount.textContent = `${elements.messageInput.value.length} / 12000`;
    }

    function setMode(mode) {
        if (state.busy || !["stream", "sync"].includes(mode)) return;
        state.mode = mode;
        elements.modeButtons.forEach(button => {
            button.classList.toggle("is-active", button.dataset.mode === mode);
        });
        elements.endpointLabel.textContent = mode === "stream"
            ? "POST /chat/client/stream" : "POST /chat/client/ask";
    }

    function openSidebar() {
        elements.sidebar.classList.add("is-open");
        elements.sidebarBackdrop.hidden = false;
    }

    function closeSidebar() {
        elements.sidebar.classList.remove("is-open");
        elements.sidebarBackdrop.hidden = true;
    }

    function renderAll() {
        renderSessions();
        renderMessages();
    }

    function bindEvents() {
        elements.newChatButton.addEventListener("click", newChat);
        elements.sessionSearchInput.addEventListener("input", renderSessions);
        elements.openSidebarButton.addEventListener("click", openSidebar);
        elements.closeSidebarButton.addEventListener("click", closeSidebar);
        elements.sidebarBackdrop.addEventListener("click", closeSidebar);
        elements.modeButtons.forEach(button => {
            button.addEventListener("click", () => setMode(button.dataset.mode));
        });
        elements.userIdInput.addEventListener("change", async () => {
            if (state.busy || state.loading) return;
            elements.userIdInput.value = elements.userIdInput.value.trim() || "default";
            state.sessions = [];
            state.activeSessionKey = null;
            clearError();
            try {
                await refreshConversations();
                setStatus("准备就绪", "ready");
            } catch (error) {
                elements.connectionDot.classList.add("is-error");
                setStatus("加载失败", "error");
                showError(error.message);
            }
        });
        elements.conversationIdButton.addEventListener("click", () => {
            const id = currentSession().conversationId;
            if (id) void copyText(id, "会话 ID 已复制");
        });
        elements.messageInput.addEventListener("input", resizeComposer);
        elements.messageInput.addEventListener("keydown", event => {
            if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
                event.preventDefault();
                elements.composerForm.requestSubmit();
            }
        });
        elements.composerForm.addEventListener("submit", event => {
            event.preventDefault();
            void sendMessage();
        });
        elements.stopButton.addEventListener("click", () => state.abortController?.abort());
        document.addEventListener("keydown", event => {
            const target = event.target;
            const isEditing = target instanceof HTMLInputElement
                || target instanceof HTMLTextAreaElement || target?.isContentEditable;
            if (!isEditing && !event.ctrlKey && !event.metaKey && !event.altKey
                    && event.key.toLowerCase() === "n") {
                event.preventDefault();
                newChat();
            }
            if (event.key === "Escape") closeSidebar();
        });
    }

    async function initialize() {
        bindEvents();
        setMode(state.mode);
        resizeComposer();
        setStatus("正在读取会话", "busy");
        try {
            await refreshConversations();
            setStatus("准备就绪", "ready");
            elements.messageInput.focus();
        } catch (error) {
            state.loading = false;
            const draft = createDraftSession();
            state.sessions = [draft];
            state.activeSessionKey = draft.key;
            elements.connectionDot.classList.add("is-error");
            setStatus("加载失败", "error");
            showError(error.message);
            renderAll();
        }
    }

    void initialize();
})();
