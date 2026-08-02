(() => {
    "use strict";

    /** 浏览器本地存储键；版本后缀便于将来调整数据结构时安全迁移。 */
    const SESSION_STORAGE_KEY = "rag-chat-ui.sessions.v2";
    const ACTIVE_SESSION_KEY = "rag-chat-ui.active-session.v2";
    const SETTINGS_STORAGE_KEY = "rag-chat-ui.settings.v2";

    /** 防止测试页面长期使用后把 localStorage 填满，只保留最近会话和有限消息。 */
    const MAX_LOCAL_SESSIONS = 40;
    const MAX_LOCAL_MESSAGES = 100;

    /** 缓存页面节点，避免每次收到流式分片时重复执行 DOM 查询。 */
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
        characterCount: document.querySelector("#characterCount"),
        sendButton: document.querySelector("#sendButton"),
        stopButton: document.querySelector("#stopButton"),
        errorBanner: document.querySelector("#errorBanner"),
        toast: document.querySelector("#toast")
    };

    /** 页面运行状态；会话消息本身会同步保存到 localStorage。 */
    const state = {
        sessions: loadJson(SESSION_STORAGE_KEY, []),
        activeSessionId: localStorage.getItem(ACTIVE_SESSION_KEY),
        mode: loadJson(SETTINGS_STORAGE_KEY, {}).mode === "sync" ? "sync" : "stream",
        busy: false,
        abortController: null,
        toastTimer: null
    };

    /**
     * 生成仅用于浏览器本地列表的 ID。真正的 conversationId 由后端在首次请求时返回。
     */
    function localId(prefix) {
        const randomPart = globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        return `${prefix}-${randomPart}`;
    }

    /**
     * 安全读取 JSON 格式的 localStorage；历史数据损坏时回退到默认值，避免页面无法启动。
     */
    function loadJson(key, fallback) {
        try {
            const raw = localStorage.getItem(key);
            return raw ? JSON.parse(raw) : fallback;
        } catch (error) {
            console.warn(`无法读取本地数据 ${key}`, error);
            return fallback;
        }
    }

    /**
     * 创建一个尚未与后端绑定的新会话。第一次发送消息后，conversationId 会更新为后端 UUID。
     */
    function createLocalSession() {
        return {
            localId: localId("session"),
            conversationId: null,
            title: "新对话",
            messages: [],
            updatedAt: new Date().toISOString()
        };
    }

    /**
     * 获取当前选中的本地会话；理论上不会为空，但仍保留兜底以增强页面恢复能力。
     */
    function currentSession() {
        let session = state.sessions.find(item => item.localId === state.activeSessionId);
        if (!session) {
            session = createLocalSession();
            state.sessions.unshift(session);
            state.activeSessionId = session.localId;
            persistState();
        }
        return session;
    }

    /**
     * 将侧栏会话和页面设置写入浏览器。这里不会修改 MySQL 中的任何聊天数据。
     */
    function persistState() {
        // 最近更新的会话排在最前。sort 只调整数组顺序，不会替换会话对象引用。
        state.sessions.sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt));

        // 使用 splice 原地删除超出上限的旧会话，不再通过 slice/map 创建新会话对象。
        if (state.sessions.length > MAX_LOCAL_SESSIONS) {
            state.sessions.splice(MAX_LOCAL_SESSIONS);
        }

        state.sessions.forEach(session => {
            // 兼容旧的或手动修改过的本地数据，确保 messages 始终是数组。
            if (!Array.isArray(session.messages)) {
                session.messages = [];
                return;
            }

            // 同样原地裁剪旧消息，保留正在被流式回调更新的 session/message 对象引用。
            if (session.messages.length > MAX_LOCAL_MESSAGES) {
                session.messages.splice(
                    0,
                    session.messages.length - MAX_LOCAL_MESSAGES
                );
            }
        });

        try {
            localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(state.sessions));
            localStorage.setItem(ACTIVE_SESSION_KEY, state.activeSessionId ?? "");
            localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ mode: state.mode }));
        } catch (error) {
            // localStorage 配额不足不应阻断接口测试，页面仍可在当前标签页继续使用。
            console.warn("保存本地会话失败", error);
        }
    }

    /**
     * 根据当前访问方式生成接口地址。通过 Spring Boot 打开时使用同源地址，直接打开文件时回退到 8080。
     */
    function endpointFor(mode = state.mode) {
        const path = mode === "stream" ? "/chat/client/stream" : "/chat/client/ask";
        if (window.location.protocol === "file:") {
            return `http://localhost:8080${path}`;
        }
        return new URL(path, window.location.origin).toString();
    }

    /**
     * 以“刚刚、N 分钟前”等短文本展示侧栏更新时间，方便快速定位本轮测试会话。
     */
    function relativeTime(value) {
        const elapsed = Date.now() - new Date(value).getTime();
        if (!Number.isFinite(elapsed) || elapsed < 60_000) return "刚刚";
        if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
        if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
        return new Date(value).toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" });
    }

    /**
     * 将 ISO 时间转换为消息行中使用的本地时分文本。
     */
    function messageTime(value) {
        return new Date(value).toLocaleTimeString("zh-CN", {
            hour: "2-digit",
            minute: "2-digit",
            hour12: false
        });
    }

    /**
     * 渲染会话侧栏；所有标题通过 textContent 写入，避免用户内容形成 HTML 注入。
     */
    function renderSessions() {
        const keyword = elements.sessionSearchInput.value.trim().toLowerCase();
        const visibleSessions = state.sessions.filter(session =>
            !keyword || session.title.toLowerCase().includes(keyword)
            || (session.conversationId ?? "").toLowerCase().includes(keyword));

        elements.sessionList.replaceChildren();
        elements.sessionCount.textContent = String(state.sessions.length);

        if (visibleSessions.length === 0) {
            const empty = document.createElement("p");
            empty.className = "session-empty";
            empty.textContent = keyword ? "没有匹配的本地会话。" : "发送第一条消息后，会话会显示在这里。";
            elements.sessionList.append(empty);
            return;
        }

        visibleSessions.forEach(session => {
            const item = document.createElement("div");
            item.className = `session-item${session.localId === state.activeSessionId ? " is-active" : ""}`;
            item.tabIndex = 0;
            item.dataset.sessionId = session.localId;
            item.setAttribute("role", "button");
            item.setAttribute("aria-label", `打开会话：${session.title}`);

            const copy = document.createElement("div");
            copy.className = "session-copy";
            const title = document.createElement("strong");
            title.textContent = session.title;
            const time = document.createElement("span");
            time.textContent = `${session.messages.length} 条消息 · ${relativeTime(session.updatedAt)}`;
            copy.append(title, time);

            const remove = document.createElement("button");
            remove.className = "session-remove";
            remove.type = "button";
            remove.textContent = "×";
            remove.title = "仅从本地侧栏移除";
            remove.setAttribute("aria-label", `从本地移除会话：${session.title}`);
            remove.addEventListener("click", event => {
                event.stopPropagation();
                removeLocalSession(session.localId);
            });

            const select = () => selectSession(session.localId);
            item.addEventListener("click", select);
            item.addEventListener("keydown", event => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    select();
                }
            });
            item.append(copy, remove);
            elements.sessionList.append(item);
        });
    }

    /**
     * 渲染无消息时的欢迎内容和可点击测试问题。
     */
    function renderWelcome() {
        const wrapper = document.createElement("div");
        wrapper.className = "welcome-state";

        const card = document.createElement("div");
        card.className = "welcome-card";
        const kicker = document.createElement("p");
        kicker.className = "welcome-kicker";
        kicker.textContent = "RAG MODULE · CHAT CLIENT";
        const title = document.createElement("h2");
        title.textContent = "今天想验证什么？";
        const description = document.createElement("p");
        description.textContent = "页面会调用当前项目的 ChatClientController，并展示会话 UUID、消息 ID、Token 和模型信息。";

        const suggestions = document.createElement("div");
        suggestions.className = "suggestion-grid";
        [
            "请介绍一下 RAG 的完整工作流程",
            "用 Java 写一个简单的单例模式",
            "记住我的名字叫小林，然后向我问好"
        ].forEach(text => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "suggestion-button";
            button.textContent = text;
            button.addEventListener("click", () => {
                elements.messageInput.value = text;
                resizeComposer();
                elements.messageInput.focus();
            });
            suggestions.append(button);
        });

        card.append(kicker, title, description, suggestions);
        wrapper.append(card);
        elements.messageList.append(wrapper);
    }

    /**
     * 创建一行消息 DOM。消息正文使用 white-space: pre-wrap 保留换行，不执行 Markdown/HTML。
     */
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

        // 助手消息额外展示后端返回的消息 ID、Token、模型名称和复制操作。
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

    /** 在消息元数据行中追加一个简单文本项。 */
    function appendMeta(container, text) {
        const span = document.createElement("span");
        span.textContent = text;
        container.append(span);
    }

    /** 长 ID 在界面中只展示首尾片段，完整值仍保存在本地会话数据中。 */
    function shortId(value) {
        if (!value || value.length <= 18) return value ?? "";
        return `${value.slice(0, 8)}…${value.slice(-6)}`;
    }

    /**
     * 完整重绘当前会话消息，主要用于切换会话、请求完成或异常恢复。
     */
    function renderMessages() {
        const session = currentSession();
        elements.messageList.replaceChildren();
        if (session.messages.length === 0) {
            renderWelcome();
        } else {
            session.messages.forEach(message => elements.messageList.append(createMessageNode(message)));
        }
        renderConversationHeader();
        requestAnimationFrame(scrollToBottom);
    }

    /**
     * 流式响应时仅更新指定消息正文，避免每个 Token 都重绘完整消息列表。
     */
    function updateMessageNode(message) {
        const row = elements.messageList.querySelector(`[data-local-message-id="${CSS.escape(message.localId)}"]`);
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

    /** 更新顶部标题和可复制的后端 conversationId。 */
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

    /** 将消息视图平滑滚动到底部，让最新的流式文本始终可见。 */
    function scrollToBottom() {
        elements.messageViewport.scrollTo({
            top: elements.messageViewport.scrollHeight,
            behavior: state.busy ? "auto" : "smooth"
        });
    }

    /** 切换当前会话，并在移动端自动关闭侧栏抽屉。 */
    function selectSession(sessionId) {
        if (state.busy) {
            showToast("请先等待当前请求完成或点击停止");
            return;
        }
        state.activeSessionId = sessionId;
        persistState();
        renderSessions();
        renderMessages();
        closeSidebar();
    }

    /** 新建一个尚未绑定后端 UUID 的本地空会话。 */
    function newChat() {
        if (state.busy) {
            showToast("请先等待当前请求完成或点击停止");
            return;
        }
        const active = currentSession();
        if (active.messages.length === 0 && !active.conversationId) {
            elements.messageInput.focus();
            closeSidebar();
            return;
        }
        const session = createLocalSession();
        state.sessions.unshift(session);
        state.activeSessionId = session.localId;
        persistState();
        renderAll();
        closeSidebar();
        elements.messageInput.focus();
    }

    /**
     * 只从浏览器侧栏移除会话，不调用后端删除接口，也不会删除 MySQL 中的历史记录。
     */
    function removeLocalSession(sessionId) {
        if (state.busy && sessionId === state.activeSessionId) {
            showToast("当前会话正在生成，暂时无法移除");
            return;
        }
        state.sessions = state.sessions.filter(session => session.localId !== sessionId);
        if (state.sessions.length === 0) state.sessions.push(createLocalSession());
        if (!state.sessions.some(session => session.localId === state.activeSessionId)) {
            state.activeSessionId = state.sessions[0].localId;
        }
        persistState();
        renderAll();
        showToast("已从本地侧栏移除，数据库记录未删除");
    }

    /** 根据第一条用户问题生成本地会话标题，与后端标题截取规则保持接近。 */
    function titleFromQuery(query) {
        const normalized = query.replace(/\s+/g, " ").trim();
        return normalized.length <= 30 ? normalized : `${normalized.slice(0, 30)}…`;
    }

    /** 向当前本地会话追加消息并返回该消息对象。 */
    function appendLocalMessage(role, content, pending = false) {
        const session = currentSession();
        const message = {
            localId: localId("message"),
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

    /**
     * 发送输入框中的消息，并根据当前模式选择同步或流式接口。
     */
    async function sendMessage() {
        if (state.busy) return;

        const query = elements.messageInput.value.trim();
        if (!query) {
            showError("请输入需要发送的问题。");
            elements.messageInput.focus();
            return;
        }

        const userId = elements.userIdInput.value.trim() || "default";
        const session = currentSession();
        clearError();

        // 第一条消息同时成为本地侧栏标题；后端也会使用相同问题生成会话标题。
        if (session.messages.length === 0) session.title = titleFromQuery(query);

        const userMessage = appendLocalMessage("user", query);
        const assistantMessage = appendLocalMessage("assistant", "", true);
        persistState();
        renderAll();

        // 清空并恢复输入框高度，让用户可以在请求期间提前输入下一条问题。
        elements.messageInput.value = "";
        resizeComposer();
        setBusy(true, state.mode === "stream" ? "正在流式生成" : "等待完整响应");

        const payload = {
            conversationId: session.conversationId,
            userId,
            query
        };
        state.abortController = new AbortController();

        try {
            if (state.mode === "stream") {
                await sendStreaming(payload, session, userMessage, assistantMessage);
            } else {
                await sendSynchronous(payload, session, userMessage, assistantMessage);
            }
            elements.connectionDot.classList.remove("is-error");
            setStatus("请求完成", "ready");
        } catch (error) {
            if (error.name === "AbortError") {
                assistantMessage.content = assistantMessage.content || "已停止生成。";
                setStatus("已停止", "ready");
            } else {
                assistantMessage.failed = true;
                assistantMessage.content = assistantMessage.content
                    || `请求失败：${error.message}`;
                elements.connectionDot.classList.add("is-error");
                setStatus("请求失败", "error");
                showError(error.message);
            }
            assistantMessage.pending = false;
        } finally {
            state.abortController = null;
            session.updatedAt = new Date().toISOString();
            persistState();
            setBusy(false);
            renderAll();
        }
    }

    /**
     * 调用 POST /chat/client/ask，并把一次性 JSON 响应映射到本地会话和消息。
     */
    async function sendSynchronous(payload, session, userMessage, assistantMessage) {
        const response = await fetch(endpointFor("sync"), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
            signal: state.abortController.signal
        });
        const body = await readJsonResponse(response);

        session.conversationId = body.conversationId;
        userMessage.messageId = body.userMessageId;
        assistantMessage.messageId = body.assistantMessageId;
        assistantMessage.content = body.content ?? "";
        assistantMessage.tokenCount = body.tokenCount ?? null;
        assistantMessage.modelName = body.modelName ?? null;
        assistantMessage.pending = false;
    }

    /**
     * 调用 POST /chat/client/stream，并持续解析 fetch ReadableStream 中的 SSE data 帧。
     */
    async function sendStreaming(payload, session, userMessage, assistantMessage) {
        const response = await fetch(endpointFor("stream"), {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Accept": "text/event-stream"
            },
            body: JSON.stringify(payload),
            signal: state.abortController.signal
        });

        if (!response.ok) {
            await throwResponseError(response);
        }
        if (!response.body) {
            throw new Error("浏览器未提供可读取的流式响应体");
        }

        let receivedDone = false;
        await consumeEventStream(response.body, event => {
            switch (event.event) {
                case "START":
                    // START 是新会话最关键的事件：立即记录后端 UUID 和已落库的用户消息 ID。
                    session.conversationId = event.conversationId;
                    userMessage.messageId = event.userMessageId;
                    renderConversationHeader();
                    persistState();
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

        if (!receivedDone) {
            throw new Error("流式响应已结束，但没有收到 DONE 事件");
        }
    }

    /**
     * 从 ReadableStream 中按 SSE 空行边界拆帧；兼容 CRLF 和 LF 两种换行格式。
     */
    async function consumeEventStream(stream, onEvent) {
        const reader = stream.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";

        while (true) {
            const { value, done } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            // 等待完整 chunk 拼接后再统一 CRLF，可处理 \r 和 \n 被拆到相邻网络块的情况。
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

    /**
     * 提取 SSE 帧中的一个或多个 data 行并解析 JSON；同时兼容服务器直接输出 JSON 的调试情况。
     */
    function parseEventBlock(block, onEvent) {
        const lines = block.split("\n");
        const dataLines = lines
            .filter(line => line.startsWith("data:"))
            .map(line => line.slice(5).trimStart());
        const raw = dataLines.length > 0 ? dataLines.join("\n") : block;
        if (!raw || raw === "[DONE]") return;

        try {
            onEvent(JSON.parse(raw));
        } catch (error) {
            throw new Error(`无法解析流式事件：${raw.slice(0, 160)}`);
        }
    }

    /**
     * 读取同步 JSON 响应；发生 HTTP 错误时优先提取后端 ApiResult 中的 message。
     */
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
        // 兼容未来 Controller 使用 ApiResult 包裹 DTO 的情况。
        return body.data ?? body;
    }

    /** 解析错误响应并抛出统一 Error，供同步和流式请求共享页面错误展示。 */
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

    /** 控制请求期间的按钮、输入区和顶部状态显示。 */
    function setBusy(busy, label) {
        state.busy = busy;
        elements.sendButton.disabled = busy;
        elements.stopButton.hidden = !busy;
        elements.modeButtons.forEach(button => button.disabled = busy);
        elements.newChatButton.disabled = busy;
        if (busy) setStatus(label ?? "请求中", "busy");
    }

    /** 更新顶部请求状态及对应的视觉状态。 */
    function setStatus(label, status) {
        elements.requestStatus.textContent = label;
        elements.headerStatus.classList.toggle("is-busy", status === "busy");
        elements.headerStatus.classList.toggle("is-error", status === "error");
    }

    /** 显示输入或接口错误。 */
    function showError(message) {
        elements.errorBanner.textContent = message;
        elements.errorBanner.hidden = false;
    }

    /** 清理上一轮错误提示。 */
    function clearError() {
        elements.errorBanner.textContent = "";
        elements.errorBanner.hidden = true;
    }

    /** 显示短时轻提示，重复调用会重新计算消失时间。 */
    function showToast(message) {
        window.clearTimeout(state.toastTimer);
        elements.toast.textContent = message;
        elements.toast.hidden = false;
        state.toastTimer = window.setTimeout(() => {
            elements.toast.hidden = true;
        }, 2200);
    }

    /** 复制文本并提供结果反馈；旧浏览器不支持 Clipboard API 时给出明确提示。 */
    async function copyText(text, successMessage) {
        try {
            await navigator.clipboard.writeText(text);
            showToast(successMessage);
        } catch (_) {
            showToast("浏览器未允许复制，请手动选择文本");
        }
    }

    /** 根据输入内容自动调整文本框高度，同时更新字符数。 */
    function resizeComposer() {
        elements.messageInput.style.height = "auto";
        elements.messageInput.style.height = `${Math.min(elements.messageInput.scrollHeight, 180)}px`;
        elements.characterCount.textContent = `${elements.messageInput.value.length} / 12000`;
    }

    /** 更新模式按钮、接口路径和持久化设置。 */
    function setMode(mode) {
        if (state.busy || !["stream", "sync"].includes(mode)) return;
        state.mode = mode;
        elements.modeButtons.forEach(button => {
            button.classList.toggle("is-active", button.dataset.mode === mode);
        });
        elements.endpointLabel.textContent = mode === "stream"
            ? "POST /chat/client/stream"
            : "POST /chat/client/ask";
        persistState();
    }

    /** 打开移动端侧栏抽屉。 */
    function openSidebar() {
        elements.sidebar.classList.add("is-open");
        elements.sidebarBackdrop.hidden = false;
    }

    /** 关闭移动端侧栏抽屉。 */
    function closeSidebar() {
        elements.sidebar.classList.remove("is-open");
        elements.sidebarBackdrop.hidden = true;
    }

    /** 一次性重绘侧栏、消息区和顶部会话信息。 */
    function renderAll() {
        renderSessions();
        renderMessages();
    }

    /** 注册页面交互事件。 */
    function bindEvents() {
        elements.newChatButton.addEventListener("click", newChat);
        elements.sessionSearchInput.addEventListener("input", renderSessions);
        elements.openSidebarButton.addEventListener("click", openSidebar);
        elements.closeSidebarButton.addEventListener("click", closeSidebar);
        elements.sidebarBackdrop.addEventListener("click", closeSidebar);

        elements.modeButtons.forEach(button => {
            button.addEventListener("click", () => setMode(button.dataset.mode));
        });

        elements.userIdInput.addEventListener("change", () => {
            elements.userIdInput.value = elements.userIdInput.value.trim() || "default";
        });

        elements.conversationIdButton.addEventListener("click", () => {
            const conversationId = currentSession().conversationId;
            if (conversationId) copyText(conversationId, "会话 ID 已复制");
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
            sendMessage();
        });

        elements.stopButton.addEventListener("click", () => {
            state.abortController?.abort();
        });
    }

    /** 恢复本地状态并启动页面。 */
    function initialize() {
        // 清理结构不完整的旧数据，避免手动修改 localStorage 后导致渲染异常。
        state.sessions = Array.isArray(state.sessions)
            ? state.sessions.filter(session => session?.localId && Array.isArray(session.messages))
            : [];
        if (state.sessions.length === 0) state.sessions.push(createLocalSession());
        if (!state.sessions.some(session => session.localId === state.activeSessionId)) {
            state.activeSessionId = state.sessions[0].localId;
        }

        bindEvents();
        setMode(state.mode);
        persistState();
        renderAll();
        resizeComposer();
        elements.messageInput.focus();
    }

    initialize();
})();
