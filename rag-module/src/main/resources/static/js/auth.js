(() => {
    "use strict";

    // 使用 sessionStorage 让 Token 仅在当前标签页会话中存活，关闭页面后不会长期残留。
    const TOKEN_KEY = "rag-studio-sa-token";
    // 保存原生 fetch 引用，避免其他页面包装 fetch 后产生递归调用。
    const nativeFetch = window.fetch.bind(window);

    /** 读取当前标签页保存的 Sa-Token 值。 */
    function token() {
        return sessionStorage.getItem(TOKEN_KEY);
    }

    /** 清除本地 Token；服务端登出失败时也必须移除本地凭证。 */
    function clear() {
        sessionStorage.removeItem(TOKEN_KEY);
    }

    /**
     * 跳转登录页并记录当前相对地址，登录成功后可返回原页面。
     */
    function redirectToLogin() {
        if (!window.location.pathname.endsWith("/login.html")) {
            const next = encodeURIComponent(window.location.pathname + window.location.search);
            window.location.replace(`/login.html?next=${next}`);
        }
    }

    /**
     * 在保留调用方请求头的基础上注入 Authorization: Bearer Token。
     */
    function headers(input) {
        const result = new Headers(input || {});
        const value = token();
        if (value) result.set("Authorization", `Bearer ${value}`);
        return result;
    }

    /**
     * 统一发起需要登录的请求；收到 401 时立即清理过期 Token 并跳转登录页。
     */
    async function authenticatedFetch(url, options = {}) {
        const response = await nativeFetch(url, { ...options, headers: headers(options.headers) });
        if (response.status === 401) {
            // 401 代表 Token 不存在、已过期或账号已停用，不能继续复用该凭证。
            clear();
            redirectToLogin();
        }
        return response;
    }

    /**
     * 页面初始化时校验登录态并返回当前用户资料。
     */
    async function requireUser() {
        if (!token()) {
            redirectToLogin();
            throw new Error("未登录");
        }
        const response = await authenticatedFetch("/auth/me", {
            headers: { "Accept": "application/json" }
        });
        const body = await response.json();
        if (!response.ok || body.code !== 0) {
            throw new Error(body.message || "登录状态已失效");
        }
        return body.data;
    }

    /**
     * 先通知服务端删除 Redis 登录态，再清理浏览器 Token 并返回登录页。
     */
    async function logout() {
        try {
            if (token()) await authenticatedFetch("/auth/logout", { method: "POST" });
        } finally {
            // 即使 Redis 或网络暂时不可用，也不能把旧 Token 留在页面中继续使用。
            clear();
            window.location.replace("/login.html");
        }
    }

    window.RagAuth = {
        token,
        saveToken: value => sessionStorage.setItem(TOKEN_KEY, value),
        clear,
        headers,
        fetch: authenticatedFetch,
        requireUser,
        logout,
        redirectToLogin
    };
})();
