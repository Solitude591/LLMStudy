import asyncio
import os

from dotenv import load_dotenv
from openai import AsyncOpenAI
from ragas.embeddings import OpenAIEmbeddings
from ragas.llms import llm_factory
from ragas.metrics.collections import AnswerRelevancy

# 加载 .env 文件中的环境变量（如 OPENAI_API_KEY）
load_dotenv()

# --- 设置大语言模型 (LLM) ---
# 初始化 OpenAI 异步客户端
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
# 使用工厂方法创建 ragas 所需的 LLM 实例（这里以 qwen3.7-max 为例）
llm = llm_factory("qwen3.7-max", client=openai_client)

# --- 设置嵌入模型 (Embeddings) ---
# Answer Relevancy 需要 embeddings 做语义相似度比较（DashScope 兼容接口示例）
embeddings = OpenAIEmbeddings(client=openai_client, model="text-embedding-v3")


# --- 创建评估指标 ---
# 初始化“答案相关性(Answer Relevancy)”评分器
scorer = AnswerRelevancy(llm=llm, embeddings=embeddings)


async def main():
    print("开始执行 RAGAS 答案相关性评估...")

    # 调用 ascore 进行异步评分
    result = await scorer.ascore(
        user_input="杭州西湖有哪些著名的景点？",
        response="杭州西湖有断桥、苏堤、雷峰塔等著名景点，其中断桥残雪、苏堤春晓都属于西湖十景。",
    )

    # 打印最终的评估结果
    print(f"评估完成！答案相关性得分 (Answer Relevancy Score): {result.value}")


# 在脚本入口通过 asyncio.run() 驱动执行异步主函数
if __name__ == "__main__":
    print("--- 脚本启动 ---")
    asyncio.run(main())
    print("--- 脚本结束 ---")
