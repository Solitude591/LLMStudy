package com.llmstudy.rag.dto;

import java.util.List;
import java.util.Map;

/**
 * 不同文档解析策略的统一输出。
 *
 * <p>PDF 策略会返回 Markdown、结构化 content_list 和图片；TXT 策略只返回文本内容。
 * 服务层统一消费该模型并负责图片上传、Markdown 改写以及结果持久化。</p>
 */
public class DocumentParseResult {

    /** 解析后的 Markdown 或可直接作为 Markdown 保存的纯文本 */
    private final String markdown;

    /** 结构化内容元素；不支持结构提取的策略返回空列表 */
    private final List<MineruContentElement> contentList;

    /** 解析产物中的本地图片，key 为解析内容里引用的相对路径 */
    private final Map<String, ImageResource> images;

    public DocumentParseResult(String markdown,
                               List<MineruContentElement> contentList,
                               Map<String, ImageResource> images) {
        this.markdown = markdown;
        this.contentList = contentList == null ? List.of() : List.copyOf(contentList);
        this.images = images == null ? Map.of() : Map.copyOf(images);
    }

    public static DocumentParseResult text(String content) {
        return new DocumentParseResult(content, List.of(), Map.of());
    }

    public String getMarkdown() {
        return markdown;
    }

    public List<MineruContentElement> getContentList() {
        return contentList;
    }

    public Map<String, ImageResource> getImages() {
        return images;
    }

    public boolean hasContentList() {
        return !contentList.isEmpty();
    }

    public static class ImageResource {

        /** 图片在 MinerU ZIP 中的规范化相对路径，用于和 Markdown 引用建立对应关系。 */
        private final String path;

        /** 图片解压后的原始二进制数据，后续直接上传到 MinIO 或传给视觉模型。 */
        private final byte[] data;

        /** 根据文件扩展名识别出的 MIME 类型，用于 MinIO Content-Type 和视觉模型 data URL。 */
        private final String contentType;

        public ImageResource(String path, byte[] data, String contentType) {
            this.path = path;
            this.data = data;
            this.contentType = contentType;
        }

        public String getPath() {
            return path;
        }

        public byte[] getData() {
            return data;
        }

        public String getContentType() {
            return contentType;
        }

        public int getSize() {
            return data == null ? 0 : data.length;
        }
    }
}
