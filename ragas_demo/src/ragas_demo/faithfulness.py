import asyncio
import os

from dotenv import load_dotenv
from openai import AsyncOpenAI
from ragas.llms import llm_factory
from ragas.metrics.collections import Faithfulness

# 加载 .env 文件中的环境变量（如 OPENAI_API_KEY）
load_dotenv()

# --- 设置大语言模型 (LLM) ---
# 初始化 OpenAI 异步客户端
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
# 使用工厂方法创建 ragas 所需的 LLM 实例（这里以 qwen3.7-max 为例）
llm = llm_factory("qwen3.7-max", client=openai_client)

# --- 创建评估指标 ---
# 初始化“忠实度(Faithfulness)”评分器
scorer = Faithfulness(llm=llm)


async def main():
    print("开始执行 RAGAS 忠实度评估...")

    # TODO: 自行填写以下字段
    # Faithfulness 检查 response 中的陈述是否可由 retrieved_contexts 支撑
    user_input = "爱因斯坦在哪里出生，什么时候出生的？"
    response = "爱因斯坦于 1879 年 3 月 20 日 在德国出生。"
    retrieved_contexts = [
        "Albert Einstein (born 14 March 1879) was a German-born theoretical physicist...",
    ]

    result = await scorer.ascore(
        user_input=user_input,
        response=response,
        retrieved_contexts=retrieved_contexts,
    )

    print(f"评估完成！忠实度得分 (Faithfulness Score): {result.value}")


if __name__ == "__main__":
    print("--- 脚本启动 ---")
    asyncio.run(main())
    print("--- 脚本结束 ---")
