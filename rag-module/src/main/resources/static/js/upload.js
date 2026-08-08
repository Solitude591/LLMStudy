(() => {
    "use strict";

    const MAX_FILE_SIZE = 50 * 1024 * 1024;
    const ALLOWED_EXTENSIONS = new Set(["pdf", "doc", "docx", "csv", "xls", "xlsx", "ppt", "pptx"]);
    const EXCEL_EXTENSIONS = new Set(["xls", "xlsx"]);

    const form = document.querySelector("#uploadForm");
    const endpointInput = document.querySelector("#endpoint");
    const fileInput = document.querySelector("#file");
    const dropZone = document.querySelector("#dropZone");
    const fileTitle = document.querySelector("#fileTitle");
    const fileHint = document.querySelector("#fileHint");
    const tableNameField = document.querySelector("#tableNameField");
    const tableNameInput = document.querySelector("#tableName");
    const errorMessage = document.querySelector("#errorMessage");
    const submitButton = document.querySelector("#submitButton");
    const resetButton = document.querySelector("#resetButton");
    const progressPanel = document.querySelector("#progressPanel");
    const progressText = document.querySelector("#progressText");
    const progressPercent = document.querySelector("#progressPercent");
    const progressBar = document.querySelector("#progressBar");
    const responseMeta = document.querySelector("#responseMeta");
    const responseOutput = document.querySelector("#responseOutput");
    const queryButton = document.querySelector("#queryButton");
    const currentUserLabel = document.querySelector("#currentUserLabel");
    const logoutButton = document.querySelector("#logoutButton");
    const visibilityInput = document.querySelector("#visibility");

    let selectedFile = null;
    let currentDocId = null;
    let activeRequest = null;

    const defaultEndpoint = () => window.location.protocol === "file:"
        ? "http://localhost:8080/document/upload"
        : new URL("document/upload", window.location.href).toString();

    endpointInput.value = defaultEndpoint();

    const extensionOf = (filename) => {
        const dot = filename.lastIndexOf(".");
        return dot < 0 ? "" : filename.slice(dot + 1).toLowerCase();
    };

    const formatBytes = (bytes) => {
        if (bytes < 1024) return `${bytes} B`;
        if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
        return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
    };

    const showError = (message) => {
        errorMessage.textContent = message;
        errorMessage.hidden = false;
    };

    const clearError = () => {
        errorMessage.textContent = "";
        errorMessage.hidden = true;
    };

    const setExcelMode = (enabled) => {
        tableNameInput.disabled = !enabled;
        tableNameInput.required = enabled;
        tableNameField.classList.toggle("is-disabled", !enabled);
        if (!enabled) tableNameInput.value = "";
    };

    const selectFile = (file) => {
        clearError();
        if (!file) return;

        const extension = extensionOf(file.name);
        if (!ALLOWED_EXTENSIONS.has(extension)) {
            selectedFile = null;
            fileInput.value = "";
            setExcelMode(false);
            showError(`不支持 .${extension || "未知"} 文件，仅支持 PDF、Word、CSV、Excel、PPT。`);
            return;
        }
        if (file.size > MAX_FILE_SIZE) {
            selectedFile = null;
            fileInput.value = "";
            setExcelMode(false);
            showError(`文件大小为 ${formatBytes(file.size)}，超过接口限制的 50MB。`);
            return;
        }

        selectedFile = file;
        fileTitle.textContent = file.name;
        fileHint.textContent = `${formatBytes(file.size)} · ${extension.toUpperCase()}`;
        setExcelMode(EXCEL_EXTENSIONS.has(extension));
    };

    const setProgress = (percent, text) => {
        progressPanel.hidden = false;
        progressBar.style.width = `${percent}%`;
        progressPercent.textContent = `${percent}%`;
        progressText.textContent = text;
    };

    const displayResponse = (status, body, elapsed) => {
        responseMeta.textContent = `HTTP ${status} · ${elapsed} ms · ${new Date().toLocaleTimeString()}`;
        let output = body;
        try {
            output = JSON.stringify(JSON.parse(body), null, 2);
        } catch (_) {
            // 非 JSON 响应按原文展示，便于观察网关或服务器错误页面。
        }
        responseOutput.textContent = output || "(空响应)";
    };

    const parseDocId = (body) => {
        try {
            return JSON.parse(body)?.data?.docId ?? null;
        } catch (_) {
            return null;
        }
    };

    const documentUrl = (uploadEndpoint, docId) => {
        const url = new URL(uploadEndpoint, window.location.href);
        url.pathname = url.pathname.replace(/\/upload\/?$/, `/${encodeURIComponent(docId)}`);
        url.search = "";
        url.hash = "";
        return url.toString();
    };

    dropZone.addEventListener("click", () => fileInput.click());
    dropZone.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            fileInput.click();
        }
    });
    fileInput.addEventListener("change", () => selectFile(fileInput.files[0]));

    ["dragenter", "dragover"].forEach((name) => {
        dropZone.addEventListener(name, (event) => {
            event.preventDefault();
            dropZone.classList.add("is-dragging");
        });
    });
    ["dragleave", "drop"].forEach((name) => {
        dropZone.addEventListener(name, (event) => {
            event.preventDefault();
            dropZone.classList.remove("is-dragging");
        });
    });
    dropZone.addEventListener("drop", (event) => {
        const file = event.dataTransfer.files[0];
        if (file) selectFile(file);
    });

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        clearError();

        if (!selectedFile) {
            showError("请先选择需要上传的文件。");
            return;
        }
        if (!form.reportValidity()) return;

        const extension = extensionOf(selectedFile.name);
        if (EXCEL_EXTENSIONS.has(extension)
            && !/^[a-z][a-z0-9_]{0,47}$/.test(tableNameInput.value.trim())) {
            showError("Excel 目标表名格式不正确，请按字段提示填写。");
            tableNameInput.focus();
            return;
        }

        const payload = new FormData();
        payload.append("file", selectedFile, selectedFile.name);
        payload.append("visibility", visibilityInput.value);
        // uploader、ownerUserId 和 organizationId 均不发送，由后端从当前 Token 身份推导。

        const docTitle = document.querySelector("#docTitle").value.trim();
        if (docTitle) payload.append("docTitle", docTitle);
        if (EXCEL_EXTENSIONS.has(extension)) payload.append("tableName", tableNameInput.value.trim());

        const xhr = new XMLHttpRequest();
        const startedAt = performance.now();
        activeRequest = xhr;
        currentDocId = null;
        queryButton.disabled = true;
        submitButton.disabled = true;
        submitButton.textContent = "上传中...";
        responseMeta.textContent = "请求进行中...";
        responseOutput.textContent = "等待服务器响应...";
        setProgress(0, "正在上传...");

        xhr.open("POST", endpointInput.value.trim());
        // 上传进度依赖 XMLHttpRequest，因此在这里显式注入与 RagAuth.fetch 相同的 Token。
        xhr.setRequestHeader("Authorization", `Bearer ${RagAuth.token()}`);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.upload.addEventListener("progress", (progressEvent) => {
            if (!progressEvent.lengthComputable) return;
            const percent = Math.min(99, Math.round(progressEvent.loaded / progressEvent.total * 100));
            setProgress(percent, percent === 99 ? "等待服务器处理..." : "正在上传...");
        });
        xhr.addEventListener("load", () => {
            const elapsed = Math.round(performance.now() - startedAt);
            setProgress(100, xhr.status >= 200 && xhr.status < 300 ? "请求完成" : "请求失败");
            displayResponse(xhr.status, xhr.responseText, elapsed);
            currentDocId = parseDocId(xhr.responseText);
            queryButton.disabled = !currentDocId;
        });
        xhr.addEventListener("error", () => {
            const elapsed = Math.round(performance.now() - startedAt);
            setProgress(0, "网络错误");
            displayResponse(0, "无法连接接口。请确认应用已启动、接口地址和端口正确。", elapsed);
        });
        xhr.addEventListener("abort", () => setProgress(0, "请求已取消"));
        xhr.addEventListener("loadend", () => {
            activeRequest = null;
            submitButton.disabled = false;
            submitButton.textContent = "开始上传";
        });
        xhr.send(payload);
    });

    queryButton.addEventListener("click", async () => {
        if (!currentDocId) return;
        queryButton.disabled = true;
        const startedAt = performance.now();
        try {
            const response = await RagAuth.fetch(
                documentUrl(endpointInput.value.trim(), currentDocId));
            const body = await response.text();
            displayResponse(response.status, body, Math.round(performance.now() - startedAt));
        } catch (error) {
            displayResponse(0, `状态查询失败：${error.message}`, Math.round(performance.now() - startedAt));
        } finally {
            queryButton.disabled = false;
        }
    });

    resetButton.addEventListener("click", () => {
        if (activeRequest) activeRequest.abort();
        form.reset();
        selectedFile = null;
        currentDocId = null;
        fileTitle.textContent = "点击选择或拖拽文件到这里";
        fileHint.textContent = "支持 PDF、Word、CSV、Excel、PPT，最大 50MB";
        endpointInput.value = defaultEndpoint();
        setExcelMode(false);
        clearError();
        progressPanel.hidden = true;
        progressBar.style.width = "0";
        responseMeta.textContent = "尚未发起请求";
        responseOutput.textContent = "等待上传...";
        queryButton.disabled = true;
    });

    logoutButton.addEventListener("click", () => void RagAuth.logout());

    /**
     * 页面加载时校验 Token、展示当前账号，并按用户组织状态限制可见范围选项。
     */
    async function initializeAuth() {
        const user = await RagAuth.requireUser();
        currentUserLabel.textContent = `${user.displayName} · ${user.role}`
            + (user.organizationName ? ` · ${user.organizationName}` : "");
        const organizationOption = visibilityInput.querySelector(
            'option[value="ORGANIZATION"]');
        // 无组织用户不能创建 ORGANIZATION 文档，组织 ID 也不会由页面伪造提交。
        organizationOption.disabled = !user.organizationId;
        if (!user.organizationId && visibilityInput.value === "ORGANIZATION") {
            visibilityInput.value = "PRIVATE";
        }
    }

    void initializeAuth();
})();
