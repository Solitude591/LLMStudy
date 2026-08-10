import asyncio
import os

from dotenv import load_dotenv
from openai import AsyncOpenAI
from ragas.embeddings import OpenAIEmbeddings
from ragas.llms import llm_factory
from ragas.metrics.collections import AnswerCorrectness

# 加载 .env 文件中的环境变量（如 OPENAI_API_KEY）
load_dotenv()

# --- 设置大语言模型 (LLM) ---
# 初始化 OpenAI 异步客户端
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
# 使用工厂方法创建 ragas 所需的 LLM 实例（这里以 qwen3.7-max 为例）
llm = llm_factory("qwen3.7-max", client=openai_client , temperature=0.0 , seed=42)

# --- 设置嵌入模型 (Embeddings) ---
# Answer Correctness 默认会计算语义相似度，需要 embeddings
embeddings = OpenAIEmbeddings(client=openai_client, model="text-embedding-v3")

# --- 创建评估指标 ---
# 初始化“答案正确性(Answer Correctness)”评分器
scorer = AnswerCorrectness(llm=llm, embeddings=embeddings)


async def main():
    print("开始执行 RAGAS 答案正确性评估...")

    # user_input / response 与 faithfulness.py 保持一致
    user_input = "爱因斯坦在哪里出生，什么时候出生的？"
    response = "爱因斯坦于 1879 年 3 月 20 日 在德国出生。"
    # 标准答案与 response 部分一致（地点/年份正确），但出生日期不同（14 日 vs 20 日），预期得分约 0.5
    reference = "爱因斯坦于 1879 年 3 月 14 日在德国出生。"

    result = await scorer.ascore(
        user_input=user_input,
        response=response,
        reference=reference,
    )

    print(f"评估完成！答案正确性得分 (Answer Correctness Score): {result.value}")


if __name__ == "__main__":
    print("--- 脚本启动 ---")
    asyncio.run(main())
    print("--- 脚本结束 ---")
