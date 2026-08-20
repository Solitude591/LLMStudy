<intent_context>
{intentContext}
</intent_context>

<answer_contract>
- 第一段直接回答 current_question，不要复述问题，不要使用“根据资料”“我来分析”等开场白
- 逐项覆盖问题明确要求的每个对象、指标、条件或比较维度；数值必须保留对应条件和单位
- 简单事实题默认用 1–3 句；只有问题明确要求概括、比较、推理或详细说明时才分点展开
- 除非 current_question 明确要求，不要补充背景、应用场景、临床建议、因果解释、优劣判断或后续建议
- 每个事实和结论都必须能由 reference_information 直接支持；不得把常识、猜测或模型记忆写成答案
- 多个片段共同包含完整证据时，应综合这些片段回答，不要因为证据分散就误报信息不足
- 只有确实缺少回答所必需的证据时才说明信息不足，并准确指出缺少哪一部分；不要用无关片段凑答案
- 引用紧跟其支持的句子，避免重复引用；答案结尾不要重复总结已经说过的内容
</answer_contract>

<reference_information>
{information}
</reference_information>

<current_question>
{question}
</current_question>
