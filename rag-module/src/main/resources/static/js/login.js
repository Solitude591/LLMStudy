(() => {
    "use strict";

    // 登录页不使用 RagAuth.fetch，否则尚未登录的请求会被统一 401 跳转逻辑再次重定向。
    const form = document.querySelector("#loginForm");
    const error = document.querySelector("#loginError");
    const button = document.querySelector("#loginButton");

    /** 提交账号密码，保存服务端签发的 Token，并返回原业务页面。 */
    form.addEventListener("submit", async event => {
        event.preventDefault();
        error.hidden = true;
        button.disabled = true;
        button.textContent = "登录中…";
        try {
            const response = await fetch("/auth/login", {
                method: "POST",
                headers: { "Accept": "application/json", "Content-Type": "application/json" },
                body: JSON.stringify({
                    username: form.username.value.trim(),
                    password: form.password.value
                })
            });
            const body = await response.json();
            if (!response.ok || body.code !== 0) {
                throw new Error(body.message || "登录失败");
            }
            RagAuth.saveToken(body.data.token);
            const next = new URLSearchParams(location.search).get("next");
            // 只接受站内绝对路径，拒绝 //example.com 形式的协议相对开放重定向。
            const safeNext = next && next.startsWith("/") && !next.startsWith("//")
                ? next : "/chat.html";
            location.replace(safeNext);
        } catch (exception) {
            error.textContent = exception.message || "登录失败";
            error.hidden = false;
        } finally {
            button.disabled = false;
            button.textContent = "登录";
        }
    });
})();
