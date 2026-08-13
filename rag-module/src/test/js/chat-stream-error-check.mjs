/**
 * 前端 SSE 终态契约自检：改写失败必须把 ERROR.content 当作用户可见错误。
 *
 * 运行：node src/test/js/chat-stream-error-check.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const chatJs = path.resolve(
        path.dirname(fileURLToPath(import.meta.url)),
        "../../../src/main/resources/static/js/chat.js");
const source = fs.readFileSync(chatJs, "utf8");

function assert(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}

assert(/case\s+"ERROR"\s*:/.test(source), "chat.js 缺少 ERROR 事件分支");
assert(/throw new Error\(event\.content\s*\|\|\s*"模型内部错误"\)/.test(source),
        "ERROR 分支必须抛出 event.content（回退「模型内部错误」）");
assert(/assistantMessage\.pending\s*=\s*false/.test(source),
        "ERROR 分支必须清除 pending，避免一直显示思考中");
assert(/event\s*=\s*JSON\.parse\(raw\)[\s\S]*?catch\s*\([^)]*\)\s*\{[\s\S]*?无法解析流式事件[\s\S]*?\}[\s\S]*?onEvent\(event\)/.test(source),
        "JSON 解析与事件回调必须分开，避免 ERROR 文案被包装成解析失败");
assert(!/onEvent\(JSON\.parse\(raw\)\)/.test(source),
        "不得在同一个 try/catch 中执行 JSON 解析和事件回调");

console.log("chat-stream-error-check: ok");
