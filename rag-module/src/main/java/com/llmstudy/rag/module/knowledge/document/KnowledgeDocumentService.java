package com.llmstudy.rag.module.knowledge.document;

import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.dto.DocumentVersionVO;
import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.DocumentVisibility;
import com.llmstudy.rag.module.knowledge.model.DocumentProcessingOutcome;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理服务接口。
 *
 * <p>所有面向用户的读写方法都显式接收 {@link AccessContext}，调用方不能传入
 * uploader 或 ownerUserId 冒充其他用户。</p>
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文件并创建文档记录。
     *
     * <p>上传完成后发布 {@code DocumentUploadedEvent}，由异步监听器接管后续
     * 解析、分片、向量化流程，本方法立即返回，不阻塞 HTTP 响应。</p>
     *
     * @param file       上传的文件
     * @param docTitle   文档标题（可选，默认取原始文件名）
     * @param actor      请求入口处捕获的当前用户访问上下文
     * @param visibility 可见范围
     * @param tableName  Excel 目标 MySQL 基础表名；非 Excel 可为空
     * @return 文档响应对象（status=uploaded）
     */
    DocumentVO uploadDocument(MultipartFile file,
                              String docTitle,
                              AccessContext actor,
                              DocumentVisibility visibility,
                              String tableName);

    /**
     * 为已有逻辑文档上传一个新的物理版本。
     *
     * <p>新版本独立执行解析、分片和向量化；进入 READY 前以及显式发布前，
     * 都不会改变逻辑文档的 current_version_id。</p>
     *
     * @param actor 当前用户；服务会在读取旧文档后执行写权限检查
     */
    DocumentVO uploadNewVersion(String docId,
                                MultipartFile file,
                                AccessContext actor,
                                String changeSummary);

    /**
     * 根据 doc_id 查询文档元数据。
     *
     * @param docId 文档业务 ID
     * @param actor 当前用户；普通读者只能看到已发布的当前版本
     * @return 文档响应对象；不存在或普通读者访问未发布文档时返回 null
     */
    DocumentVO getDocument(String docId, AccessContext actor);

    /**
     * 返回逻辑文档下的全部物理版本，按 version_no 降序。
     * 版本历史可能包含未发布内容，因此仅允许具有写权限的用户查看。
     */
    List<DocumentVersionVO> listVersions(String docId, AccessContext actor);

    /**
     * 返回指定物理版本；文档或版本不存在、归属不匹配时返回 null。
     * 指定历史版本同样要求文档写权限。
     */
    DocumentVersionVO getVersion(String docId, String versionId, AccessContext actor);

    /**
     * 列出当前用户有权读取的文档；无写权限的读者只会看到已发布文档。
     */
    List<DocumentVO> listAccessibleDocuments(AccessContext actor);

    /**
     * 修改文档可见范围。
     *
     * <p>切换为组织可见时，组织 ID 根据文档所有者在服务端推导。</p>
     */
    DocumentVO updateVisibility(String docId, DocumentVisibility visibility,
                                AccessContext actor);

    /**
     * 异步处理上传的物理版本（供事件监听器调用）。
     *
     * <p>以版本为主键驱动解析 → 分片 → 向量化流水线。
     * MinerU 使用版本记录的公网 docUrl 拉取原始文件。</p>
     *
     * @param versionId 物理版本 ID
     * @return 本次处理结果，决定监听器是否发布后续 RAG 事件
     */
    DocumentProcessingOutcome processDocument(String versionId);
}
