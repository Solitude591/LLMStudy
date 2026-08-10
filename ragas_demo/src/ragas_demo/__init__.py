import asyncio
import os
from openai import AsyncOpenAI
from dotenv import load_dotenv
from ragas.llms import llm_factory
from ragas.metrics.collections import ContextPrecision

# 加载 .env 文件中的环境变量（如 OPENAI_API_KEY）
load_dotenv()

# --- 设置大语言模型 (LLM) ---
# 初始化 OpenAI 异步客户端
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
# 使用工厂方法创建 ragas 所需的 LLM 实例（这里以 qwen3.7-max 为例）
llm = llm_factory("qwen3.7-max", client=openai_client)

# --- 创建评估指标 ---
# 初始化“上下文精确度(Context Precision)”评分器
scorer = ContextPrecision(llm=llm)


async def main():
    print("开始执行 RAGAS 上下文精确度评估...")

    # 调用 ascore 进行异步评分
    result = await scorer.ascore(
        user_input = "杭州西湖有哪些著名的景点？",
	    reference = "杭州西湖拥有许多著名景点，包括断桥、苏堤、雷峰塔等。",
	    retrieved_contexts = [
	        "杭州西湖是中国著名的旅游景点，其中断桥残雪是西湖十景之一。",
	        "雷峰塔位于西湖南岸，是西湖的标志性建筑之一。",
	        "苏堤春晓也是西湖十景之一，贯穿西湖南北。",
	        "北京的故宫是中国明清两代的皇家宫殿。"  # 这是一个不相关的干扰项
	    ]
    )

    # 打印最终的评估结果
    print(f"评估完成！上下文精确度得分 (Context Precision Score): {result.value}")

# 在脚本入口通过 asyncio.run() 驱动执行异步主函数
if __name__ == "__main__":
    print("--- 脚本启动 ---")
    asyncio.run(main())
    print("--- 脚本结束 ---")
