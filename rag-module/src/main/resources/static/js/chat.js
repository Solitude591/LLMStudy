(() => {
    "use strict";

    const TITLE_SYNC_MAX_ATTEMPTS = 12;
    const TITLE_SYNC_INTERVAL_MS = 1_000;
    const DOCUMENT_STORAGE_KEY = "rag-studio-tracked-documents-v1";
    const DOCUMENT_POLL_INTERVAL_MS = 4_000;
    const DOCUMENT_PROCESSING_STEPS = [
        "UPLOADED", "CONVERTING", "CONVERTED", "SPLITTING",
        "CHUNKED", "VECTORING", "VECTOR_STORED"
    ];
    // 聊天页所有业务请求统一经过认证封装，自动附加 Bearer Token 并处理 401。
    const authFetch = (url, options = {}) => RagAuth.fetch(url, options);

    const elements = {
        sidebar: document.querySelector("#sidebar"),
        sidebarBackdrop: document.querySelector("#sidebarBackdrop"),
        openSidebarButton: document.querySelector("#openSidebarButton"),
        closeSidebarButton: document.querySelector("#closeSidebarButton"),
        newChatButton: document.querySelector("#newChatButton"),
        sessionSearchInput: document.querySelector("#sessionSearchInput"),
        sessionList: document.querySelector("#sessionList"),
        sessionCount: document.querySelector("#sessionCount"),
        currentUserLabel: document.querySelector("#currentUserLabel"),
        logoutButton: document.querySelector("#logoutButton"),
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
        openKnowledgeButton: document.querySelector("#openKnowledgeButton"),
        closeKnowledgeButton: document.querySelector("#closeKnowledgeButton"),
        knowledgePanel: document.querySelector("#knowledgePanel"),
        knowledgeBackdrop: document.querySelector("#knowledgeBackdrop"),
        knowledgeHeaderCount: document.querySelector("#knowledgeHeaderCount"),
        uploadDocumentButton: document.querySelector("#uploadDocumentButton"),
        attachDocumentForm: document.querySelector("#attachDocumentForm"),
        attachDocumentId: document.querySelector("#attachDocumentId"),
        onlineDocumentCount: document.querySelector("#onlineDocumentCount"),
        processingDocumentCount: document.querySelector("#processingDocumentCount"),
        trackedDocumentCount: document.querySelector("#trackedDocumentCount"),
        knowledgeError: document.querySelector("#knowledgeError"),
        documentSearchInput: document.querySelector("#documentSearchInput"),
        documentList: document.querySelector("#documentList"),
        documentDialog: document.querySelector("#documentDialog"),
        documentUploadForm: document.querySelector("#documentUploadForm"),
        documentDialogEyebrow: document.querySelector("#documentDialogEyebrow"),
        documentDialogTitle: document.querySelector("#documentDialogTitle"),
        documentDialogDescription: document.querySelector("#documentDialogDescription"),
        closeDocumentDialogButton: document.querySelector("#closeDocumentDialogButton"),
        cancelDocumentDialogButton: document.querySelector("#cancelDocumentDialogButton"),
        versionTarget: document.querySelector("#versionTarget"),
        versionTargetTitle: document.querySelector("#versionTargetTitle"),
        versionTargetId: document.querySelector("#versionTargetId"),
        documentTitleField: document.querySelector("#documentTitleField"),
        documentTitleInput: document.querySelector("#documentTitleInput"),
        documentVisibilityField: document.querySelector("#documentVisibilityField"),
        documentVisibilityInput: document.querySelector("#documentVisibilityInput"),
        changeSummaryField: document.querySelector("#changeSummaryField"),
        changeSummaryInput: document.querySelector("#changeSummaryInput"),
        documentDropzone: document.querySelector("#documentDropzone"),
        documentFileInput: document.querySelector("#documentFileInput"),
        documentFileLabel: document.querySelector("#documentFileLabel"),
        documentFileHint: document.querySelector("#documentFileHint"),
        documentFormError: document.querySelector("#documentFormError"),
        submitDocumentButton: document.querySelector("#submitDocumentButton"),
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
        documents: [],
        activeDocumentId: null,
        documentDialogMode: "create",
        documentDialogTargetId: null,
        documentBusy: false,
        busyVersionId: null,
        documentPollTimer: null,
        toastTimer: null,
        currentUser: null
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
        if (window.location.protocol === "file:" || window.location.port === "4173") {
            const host = window.location.hostname || "localhost";
            return `http://${host}:8080${path}`;
        }
        return new URL(path, window.location.origin).toString();
    }

    function endpointFor(mode = state.mode) {
        return apiUrl(mode === "stream" ? "/chat/client/stream" : "/chat/client/ask");
    }

    function conversationsEndpoint() {
        return apiUrl("/chat/client/conversations");
    }

    function conversationEndpoint(conversationId) {
        return apiUrl(`/chat/client/conversations/${encodeURIComponent(conversationId)}`);
    }

    function messagesEndpoint(conversationId) {
        return `${conversationEndpoint(conversationId)}/messages`;
    }

    function documentEndpoint(docId) {
        return apiUrl(`/document/${encodeURIComponent(docId)}`);
    }

    function documentVersionsEndpoint(docId) {
        return `${documentEndpoint(docId)}/versions`;
    }

    function documentPublishEndpoint(docId, versionId) {
        return `${documentVersionsEndpoint(docId)}/${encodeURIComponent(versionId)}/publish`;
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
            const response = await authFetch(conversationsEndpoint(), {
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
        // 后端同时使用 conversationId 和 Token 中的当前用户 ID 校验会话所有权。
        const response = await authFetch(messagesEndpoint(session.conversationId), {
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
        description.textContent = "在知识库中上传并发布文档版本，再通过对话验证完整 RAG 链路。";

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

    function loadStoredDocuments() {
        try {
            const stored = JSON.parse(window.localStorage.getItem(DOCUMENT_STORAGE_KEY) || "[]");
            if (!Array.isArray(stored)) return;
            state.documents = stored
                .filter(item => item && typeof item.docId === "string" && item.docId.trim())
                .map(item => ({
                    docId: item.docId.trim(),
                    docTitle: item.docTitle?.trim() || "未命名文档",
                    visibility: item.visibility || "PRIVATE",
                    versions: [],
                    loading: true,
                    error: null
                }));
            state.activeDocumentId = state.documents[0]?.docId ?? null;
        } catch (error) {
            console.warn("读取本地文档列表失败", error);
        }
    }

    function persistDocuments() {
        try {
            const serializable = state.documents.map(document => ({
                docId: document.docId,
                docTitle: document.docTitle,
                visibility: document.visibility
            }));
            window.localStorage.setItem(DOCUMENT_STORAGE_KEY, JSON.stringify(serializable));
        } catch (error) {
            console.warn("保存本地文档列表失败", error);
        }
    }

    function upsertDocument(metadata, versions = null, select = false) {
        if (!metadata?.docId) return null;
        let document = state.documents.find(item => item.docId === metadata.docId);
        if (!document) {
            document = {
                docId: metadata.docId,
                docTitle: metadata.docTitle?.trim() || "未命名文档",
                visibility: metadata.visibility || "PRIVATE",
                versions: [],
                loading: false,
                error: null
            };
            state.documents.unshift(document);
        }
        document.docTitle = metadata.docTitle?.trim() || document.docTitle;
        document.visibility = metadata.visibility || document.visibility || "PRIVATE";
        document.loading = false;
        document.error = null;
        if (Array.isArray(versions)) document.versions = versions;
        if (select || !state.activeDocumentId) state.activeDocumentId = document.docId;
        persistDocuments();
        return document;
    }

    async function refreshDocument(docId, quiet = false) {
        let document = state.documents.find(item => item.docId === docId);
        if (document) document.loading = !quiet;
        if (!quiet) renderKnowledge();
        try {
            const [metadataResponse, versionsResponse] = await Promise.all([
                authFetch(documentEndpoint(docId), { headers: { "Accept": "application/json" } }),
                authFetch(documentVersionsEndpoint(docId), { headers: { "Accept": "application/json" } })
            ]);
            const [metadata, versions] = await Promise.all([
                readJsonResponse(metadataResponse), readJsonResponse(versionsResponse)
            ]);
            document = upsertDocument(metadata, Array.isArray(versions) ? versions : []);
            return document;
        } catch (error) {
            if (document) {
                document.loading = false;
                document.error = error.message;
            }
            if (!quiet) showKnowledgeError(error.message);
            throw error;
        } finally {
            renderKnowledge();
        }
    }

    async function refreshTrackedDocuments() {
        const ids = state.documents.map(document => document.docId);
        if (ids.length === 0) {
            renderKnowledge();
            return;
        }
        await Promise.allSettled(ids.map(docId => refreshDocument(docId, true)));
        renderKnowledge();
        startDocumentPolling();
    }

    function showKnowledgeError(message) {
        elements.knowledgeError.textContent = message;
        elements.knowledgeError.hidden = false;
    }

    function clearKnowledgeError() {
        elements.knowledgeError.textContent = "";
        elements.knowledgeError.hidden = true;
    }

    function showDocumentFormError(message) {
        elements.documentFormError.textContent = message;
        elements.documentFormError.hidden = false;
    }

    function clearDocumentFormError() {
        elements.documentFormError.textContent = "";
        elements.documentFormError.hidden = true;
    }

    function releaseStatusLabel(version) {
        if (version.errorMessage) return "处理失败";
        switch ((version.releaseStatus || "").toUpperCase()) {
            case "PUBLISHED": return "已上线";
            case "READY": return "待发布";
            case "ARCHIVED": return "历史版本";
            case "PUBLISHING": return "发布中";
            default: return processingStatusLabel(version.processingStatus);
        }
    }

    function processingStatusLabel(status) {
        const labels = {
            INIT: "等待处理",
            UPLOADED: "文件已上传",
            IMPORTING: "正在导入",
            IMPORTED: "导入完成",
            CONVERTING: "正在解析",
            CONVERTED: "解析完成",
            SPLITTING: "正在分片",
            CHUNKED: "分片完成",
            VECTORING: "正在向量化",
            VECTOR_STORED: "处理完成"
        };
        return labels[(status || "").toUpperCase()] || status || "等待处理";
    }

    function statusTone(version) {
        if (version.errorMessage) return "is-error";
        switch ((version.releaseStatus || "").toUpperCase()) {
            case "PUBLISHED": return "is-published";
            case "READY": return "is-ready";
            case "ARCHIVED": return "is-archived";
            default: return "is-processing";
        }
    }

    function createStatusPill(version) {
        const pill = document.createElement("span");
        pill.className = `status-pill ${statusTone(version)}`;
        pill.textContent = releaseStatusLabel(version);
        return pill;
    }

    function isProcessingVersion(version) {
        const releaseStatus = (version.releaseStatus || "").toUpperCase();
        return !version.errorMessage && ["PREPARING", "PUBLISHING", ""].includes(releaseStatus);
    }

    function processingProgress(status) {
        const normalized = (status || "").toUpperCase();
        const index = DOCUMENT_PROCESSING_STEPS.indexOf(normalized);
        if (index < 0) return normalized === "IMPORTING" || normalized === "IMPORTED" ? 18 : 8;
        return Math.round(((index + 1) / DOCUMENT_PROCESSING_STEPS.length) * 100);
    }

    function formatDocumentDate(value) {
        const date = value ? new Date(value) : null;
        if (!date || !Number.isFinite(date.getTime())) return "时间未知";
        return date.toLocaleString("zh-CN", {
            month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false
        });
    }

    function createVersionNode(document, version) {
        const item = documentNode("article", `version-item${version.current ? " is-current" : ""}`);
        const heading = documentNode("div", "version-heading");
        const title = documentNode("strong", null, `v${version.versionNo ?? "-"}`);
        heading.append(title, createStatusPill(version));

        const summary = documentNode(
            "p", "version-summary",
            version.changeSummary?.trim() || (version.versionNo === 1 ? "初始版本" : "未填写版本说明")
        );
        const meta = documentNode("div", "version-meta");
        meta.append(
            documentNode("span", null, processingStatusLabel(version.processingStatus)),
            documentNode("span", null, version.fileType?.toUpperCase() || "DOCUMENT"),
            documentNode("span", null, formatDocumentDate(version.createdAt))
        );
        item.append(heading, summary, meta);

        if (isProcessingVersion(version)) {
            const progress = documentNode("div", "version-progress");
            const bar = documentNode("span");
            bar.style.width = `${processingProgress(version.processingStatus)}%`;
            progress.append(bar);
            item.append(progress);
        }
        if (version.errorMessage) {
            item.append(documentNode("p", "version-error", version.errorMessage));
        }

        const footer = documentNode("div", "version-footer");
        footer.append(documentNode("code", null, shortId(version.versionId)));
        const releaseStatus = (version.releaseStatus || "").toUpperCase();
        if (version.current) {
            const current = documentNode("button", "version-action-button", "线上版本");
            current.type = "button";
            current.disabled = true;
            footer.append(current);
        } else if (releaseStatus === "READY" || releaseStatus === "ARCHIVED") {
            const action = documentNode(
                "button",
                `version-action-button${releaseStatus === "ARCHIVED" ? " is-rollback" : ""}`,
                releaseStatus === "ARCHIVED" ? "回滚至此" : "发布上线"
            );
            action.type = "button";
            action.disabled = state.busyVersionId === version.versionId;
            if (action.disabled) action.textContent = "正在切换";
            action.addEventListener("click", event => {
                event.stopPropagation();
                void publishDocumentVersion(document.docId, version);
            });
            footer.append(action);
        }
        item.append(footer);
        return item;
    }

    function createDocumentCard(document) {
        const selected = document.docId === state.activeDocumentId;
        const card = documentNode("article", `document-card${selected ? " is-selected" : ""}`);
        const summary = documentNode("button", "document-summary-button");
        summary.type = "button";
        summary.setAttribute("aria-expanded", String(selected));
        const copy = documentNode("span", "document-summary-copy");
        copy.append(
            documentNode("strong", null, document.docTitle || "未命名文档"),
            documentNode("code", null, document.docId)
        );
        const versions = Array.isArray(document.versions) ? document.versions : [];
        const currentVersion = versions.find(version => version.current);
        const versionCount = documentNode(
            "span", "document-version-count",
            document.loading ? "同步中" : `${versions.length} 个版本`
        );
        const currentLine = documentNode("span", "document-current-line");
        if (currentVersion) {
            currentLine.append(
                createStatusPill(currentVersion),
                documentNode("span", null, `v${currentVersion.versionNo} 正在服务线上问答`)
            );
        } else {
            const latest = versions[0];
            if (latest) currentLine.append(createStatusPill(latest));
            currentLine.append(documentNode("span", null, document.error || "尚未发布线上版本"));
        }
        summary.append(copy, versionCount, currentLine);
        summary.addEventListener("click", () => {
            state.activeDocumentId = document.docId;
            renderKnowledge();
        });
        card.append(summary);

        if (selected) {
            const details = documentNode("div", "document-details");
            const toolbar = documentNode("div", "document-detail-toolbar");
            toolbar.append(documentNode("span", null, "版本历史"));
            const newVersion = documentNode("button", "new-version-button", "上传新版本");
            newVersion.type = "button";
            newVersion.addEventListener("click", () => openDocumentDialog("version", document.docId));
            toolbar.append(newVersion);
            details.append(toolbar);
            const list = documentNode("div", "version-list");
            if (document.loading && versions.length === 0) {
                list.append(documentNode("p", "document-empty", "正在同步版本状态…"));
            } else if (versions.length === 0) {
                list.append(documentNode("p", "document-empty", "暂未读取到版本。"));
            } else {
                [...versions]
                    .sort((left, right) => (right.versionNo ?? 0) - (left.versionNo ?? 0))
                    .forEach(version => list.append(createVersionNode(document, version)));
            }
            details.append(list);
            card.append(details);
        }
        return card;
    }

    function documentNode(tagName, className = null, text = null) {
        const node = document.createElement(tagName);
        if (className) node.className = className;
        if (text !== null) node.textContent = text;
        return node;
    }

    function renderKnowledge() {
        const allDocuments = state.documents;
        const versions = allDocuments.flatMap(document => document.versions || []);
        const onlineCount = allDocuments.filter(document =>
            (document.versions || []).some(version => version.current)).length;
        const processingCount = versions.filter(isProcessingVersion).length;
        elements.onlineDocumentCount.textContent = String(onlineCount);
        elements.processingDocumentCount.textContent = String(processingCount);
        elements.trackedDocumentCount.textContent = String(allDocuments.length);
        elements.knowledgeHeaderCount.textContent = String(allDocuments.length);

        const keyword = elements.documentSearchInput.value.trim().toLowerCase();
        const visibleDocuments = allDocuments.filter(document =>
            !keyword || document.docTitle.toLowerCase().includes(keyword)
            || document.docId.toLowerCase().includes(keyword));
        elements.documentList.replaceChildren();
        if (visibleDocuments.length === 0) {
            const empty = documentNode("div", "document-empty");
            empty.append(
                documentNode("strong", null, keyword ? "没有匹配的文档" : "还没有关联文档"),
                documentNode("span", null, keyword
                    ? "可以按标题或完整 docId 搜索。"
                    : "上传首个文件，或输入已有 docId 继续管理版本。")
            );
            elements.documentList.append(empty);
            return;
        }
        visibleDocuments.forEach(document => elements.documentList.append(createDocumentCard(document)));
    }

    async function publishDocumentVersion(docId, version) {
        const document = state.documents.find(item => item.docId === docId);
        if (!document || state.busyVersionId) return;
        const currentVersion = (document.versions || []).find(item => item.current);
        const rollback = (version.releaseStatus || "").toUpperCase() === "ARCHIVED";
        const action = rollback ? "回滚" : "发布";
        if (!window.confirm(`${action}“${document.docTitle}”的 v${version.versionNo}？\n切换完成后，新会话将检索这个版本。`)) {
            return;
        }

        state.busyVersionId = version.versionId;
        clearKnowledgeError();
        renderKnowledge();
        try {
            const response = await authFetch(documentPublishEndpoint(docId, version.versionId), {
                method: "POST",
                headers: { "Accept": "application/json", "Content-Type": "application/json" },
                body: JSON.stringify({ expectedCurrentVersionId: currentVersion?.versionId ?? null })
            });
            await readJsonResponse(response);
            await refreshDocument(docId, true);
            showToast(`${action}成功，当前线上版本为 v${version.versionNo}`);
        } catch (error) {
            showKnowledgeError(error.message);
        } finally {
            state.busyVersionId = null;
            renderKnowledge();
            startDocumentPolling();
        }
    }

    function openDocumentDialog(mode, docId = null) {
        if (state.documentBusy) return;
        const target = docId
            ? state.documents.find(document => document.docId === docId)
            : null;
        if (mode === "version" && !target) {
            showKnowledgeError("没有找到需要创建新版本的逻辑文档。");
            return;
        }
        state.documentDialogMode = mode;
        state.documentDialogTargetId = target?.docId ?? null;
        elements.documentUploadForm.reset();
        elements.documentVisibilityInput.value = "PRIVATE";
        elements.versionTarget.hidden = mode !== "version";
        elements.documentTitleField.hidden = mode === "version";
        elements.documentVisibilityField.hidden = mode === "version";
        elements.changeSummaryField.hidden = mode !== "version";
        elements.documentDialogEyebrow.textContent = mode === "version" ? "NEW SNAPSHOT" : "NEW DOCUMENT";
        elements.documentDialogTitle.textContent = mode === "version" ? "上传新版本" : "上传新文档";
        elements.documentDialogDescription.textContent = mode === "version"
            ? "新版本独立处理，发布前不会影响当前线上内容。"
            : "创建逻辑文档并生成第一个物理版本。";
        elements.submitDocumentButton.textContent = mode === "version" ? "上传并处理" : "开始上传";
        if (target) {
            elements.versionTargetTitle.textContent = target.docTitle;
            elements.versionTargetId.textContent = target.docId;
        }
        clearDocumentFormError();
        updateFileSelection();
        if (!elements.documentDialog.open) elements.documentDialog.showModal();
    }

    function closeDocumentDialog() {
        if (state.documentBusy) return;
        if (elements.documentDialog.open) elements.documentDialog.close();
    }

    function updateFileSelection() {
        const file = elements.documentFileInput.files?.[0];
        elements.documentDropzone.classList.toggle("has-file", Boolean(file));
        if (!file) {
            elements.documentFileLabel.textContent = "选择 PDF、DOC 或 DOCX 文件";
            elements.documentFileHint.textContent = "点击选择，或将文件拖放到此处";
            return;
        }
        elements.documentFileLabel.textContent = file.name;
        elements.documentFileHint.textContent = `${formatFileSize(file.size)} · 已准备上传`;
        if (state.documentDialogMode === "create" && !elements.documentTitleInput.value.trim()) {
            elements.documentTitleInput.value = file.name.replace(/\.[^.]+$/, "");
        }
    }

    function formatFileSize(bytes) {
        if (!Number.isFinite(bytes) || bytes < 1024) return `${bytes || 0} B`;
        if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
        return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    }

    function isAcceptedDocument(file) {
        return Boolean(file && /\.(pdf|doc|docx)$/i.test(file.name));
    }

    async function submitDocumentUpload() {
        if (state.documentBusy) return;
        const file = elements.documentFileInput.files?.[0];
        if (!file) {
            showDocumentFormError("请选择需要上传的文档文件。");
            return;
        }
        if (!isAcceptedDocument(file)) {
            showDocumentFormError("当前仅支持 PDF、DOC 和 DOCX 文件。");
            return;
        }
        const mode = state.documentDialogMode;
        const targetId = state.documentDialogTargetId;
        if (mode === "version" && !targetId) {
            showDocumentFormError("缺少目标文档 ID，请关闭后重试。");
            return;
        }
        const formData = new FormData();
        formData.append("file", file);
        let endpoint;
        if (mode === "version") {
            endpoint = documentVersionsEndpoint(targetId);
            const summary = elements.changeSummaryInput.value.trim();
            if (summary) formData.append("changeSummary", summary);
        } else {
            endpoint = apiUrl("/document/upload");
            const title = elements.documentTitleInput.value.trim();
            if (title) formData.append("docTitle", title);
            formData.append("visibility", elements.documentVisibilityInput.value || "PRIVATE");
        }

        state.documentBusy = true;
        clearDocumentFormError();
        elements.submitDocumentButton.disabled = true;
        elements.submitDocumentButton.textContent = "正在上传…";
        try {
            const response = await authFetch(endpoint, {
                method: "POST",
                headers: { "Accept": "application/json" },
                body: formData
            });
            const metadata = await readJsonResponse(response);
            upsertDocument(metadata, null, true);
            elements.documentDialog.close();
            await refreshDocument(metadata.docId || targetId, true);
            clearKnowledgeError();
            openKnowledgePanel();
            showToast(mode === "version"
                ? `v${metadata.versionNo ?? "新"} 已上传，完成处理后可发布`
                : "文档已上传，正在生成第一个版本");
        } catch (error) {
            showDocumentFormError(error.message);
        } finally {
            state.documentBusy = false;
            elements.submitDocumentButton.disabled = false;
            elements.submitDocumentButton.textContent = mode === "version" ? "上传并处理" : "开始上传";
            renderKnowledge();
            startDocumentPolling();
        }
    }

    async function attachDocument() {
        const docId = elements.attachDocumentId.value.trim();
        if (!docId || state.documentBusy) {
            if (!docId) showKnowledgeError("请输入需要关联的 docId。");
            return;
        }
        clearKnowledgeError();
        const existing = state.documents.find(document => document.docId === docId);
        if (!existing) {
            state.documents.unshift({
                docId,
                docTitle: "正在读取文档…",
                visibility: "PRIVATE",
                versions: [],
                loading: true,
                error: null
            });
        }
        state.activeDocumentId = docId;
        renderKnowledge();
        try {
            await refreshDocument(docId, true);
            elements.attachDocumentId.value = "";
            showToast("文档已关联到当前工作台");
        } catch (error) {
            if (!existing) state.documents = state.documents.filter(document => document.docId !== docId);
            state.activeDocumentId = state.documents[0]?.docId ?? null;
            showKnowledgeError(error.message);
            renderKnowledge();
        }
    }

    function startDocumentPolling() {
        window.clearInterval(state.documentPollTimer);
        const hasProcessing = state.documents.some(document =>
            (document.versions || []).some(isProcessingVersion));
        if (!hasProcessing) {
            state.documentPollTimer = null;
            return;
        }
        state.documentPollTimer = window.setInterval(async () => {
            if (state.documentBusy || state.busyVersionId) return;
            const ids = state.documents
                .filter(document => (document.versions || []).some(isProcessingVersion))
                .map(document => document.docId);
            await Promise.allSettled(ids.map(docId => refreshDocument(docId, true)));
            startDocumentPolling();
        }, DOCUMENT_POLL_INTERVAL_MS);
    }

    function openKnowledgePanel() {
        closeSidebar();
        if (window.matchMedia("(max-width: 1220px)").matches) {
            elements.knowledgePanel.classList.add("is-open");
        }
        syncKnowledgePanelState();
        window.setTimeout(() => elements.documentSearchInput.focus(), 80);
    }

    function closeKnowledgePanel() {
        elements.knowledgePanel.classList.remove("is-open");
        syncKnowledgePanelState();
    }

    function syncKnowledgePanelState() {
        const drawer = window.matchMedia("(max-width: 1220px)").matches;
        const open = !drawer || elements.knowledgePanel.classList.contains("is-open");
        elements.openKnowledgeButton.setAttribute("aria-expanded", String(open));
        elements.knowledgeBackdrop.hidden = !drawer || !open;
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
            const response = await authFetch(conversationEndpoint(session.conversationId), {
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
        // 请求体只包含会话 ID 和问题，userId 由后端根据 Authorization Token 获取。
        const payload = { conversationId: session.conversationId, query };
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
        const response = await authFetch(endpointFor("sync"), {
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
        const response = await authFetch(endpointFor("stream"), {
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
                const response = await authFetch(conversationEndpoint(conversationId), {
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
        closeKnowledgePanel();
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
        renderKnowledge();
    }

    function bindEvents() {
        elements.newChatButton.addEventListener("click", newChat);
        elements.sessionSearchInput.addEventListener("input", renderSessions);
        elements.openSidebarButton.addEventListener("click", openSidebar);
        elements.closeSidebarButton.addEventListener("click", closeSidebar);
        elements.sidebarBackdrop.addEventListener("click", closeSidebar);
        elements.openKnowledgeButton.addEventListener("click", openKnowledgePanel);
        elements.closeKnowledgeButton.addEventListener("click", closeKnowledgePanel);
        elements.knowledgeBackdrop.addEventListener("click", closeKnowledgePanel);
        elements.uploadDocumentButton.addEventListener("click", () => openDocumentDialog("create"));
        elements.documentSearchInput.addEventListener("input", renderKnowledge);
        elements.attachDocumentForm.addEventListener("submit", event => {
            event.preventDefault();
            void attachDocument();
        });
        elements.documentFileInput.addEventListener("change", updateFileSelection);
        ["dragenter", "dragover"].forEach(type => {
            elements.documentDropzone.addEventListener(type, event => {
                event.preventDefault();
                elements.documentDropzone.classList.add("is-dragging");
            });
        });
        ["dragleave", "drop"].forEach(type => {
            elements.documentDropzone.addEventListener(type, event => {
                event.preventDefault();
                elements.documentDropzone.classList.remove("is-dragging");
            });
        });
        elements.documentDropzone.addEventListener("drop", event => {
            const files = event.dataTransfer?.files;
            if (files?.length) {
                elements.documentFileInput.files = files;
                updateFileSelection();
            }
        });
        elements.documentUploadForm.addEventListener("submit", event => {
            event.preventDefault();
            void submitDocumentUpload();
        });
        elements.closeDocumentDialogButton.addEventListener("click", closeDocumentDialog);
        elements.cancelDocumentDialogButton.addEventListener("click", closeDocumentDialog);
        elements.documentDialog.addEventListener("cancel", event => {
            if (state.documentBusy) event.preventDefault();
        });
        window.addEventListener("resize", syncKnowledgePanelState);
        elements.modeButtons.forEach(button => {
            button.addEventListener("click", () => setMode(button.dataset.mode));
        });
        elements.logoutButton.addEventListener("click", () => void RagAuth.logout());
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
            if (event.key === "Escape") {
                closeSidebar();
                closeKnowledgePanel();
            }
        });
    }

    /**
     * 校验登录态后再加载会话与文档，避免未认证页面提前发起业务请求。
     */
    async function initialize() {
        state.currentUser = await RagAuth.requireUser();
        elements.currentUserLabel.textContent = `${state.currentUser.displayName} · ${state.currentUser.role}`
            + (state.currentUser.organizationName ? ` · ${state.currentUser.organizationName}` : "");
        const organizationOption = elements.documentVisibilityInput.querySelector(
            'option[value="ORGANIZATION"]');
        // 没有组织归属时禁用组织可见选项，后端仍会进行同样的强制校验。
        organizationOption.disabled = !state.currentUser.organizationId;
        loadStoredDocuments();
        bindEvents();
        setMode(state.mode);
        resizeComposer();
        syncKnowledgePanelState();
        renderKnowledge();
        void refreshTrackedDocuments();
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
