package com.llmstudy.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.regex.Pattern;

/**
 * MinerU content_list.json 中的单个内容元素。
 *
 * <p>典型结构：</p>
 * <pre>
 * {"type":"text","text":"3.2 文档处理流程","text_level":2,"page_idx":2}
 * {"type":"image","img_path":"images/abc.jpg","image_caption":["图 1 系统架构"],"page_idx":2}
 * {"type":"table","table_body":"&lt;table&gt;...","table_caption":["表 1 参数说明"],"page_idx":3}
 * </pre>
 *
 * <p>text_level 为标题层级（1=H1、2=H2……），普通正文没有该字段。
 * 后续「基于标题的父子分段」直接消费这些字段构建标题树。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MineruContentElement {

    private static final Pattern CAPTION_PREFIX = Pattern.compile(
            "^(?:(?:图|表)\\s*[A-Za-z]?\\d+|(?:figure|fig\\.?|table|chart)\\s*[A-Za-z]?\\d+)",
            Pattern.CASE_INSENSITIVE);

    /** 元素类型：text / image / table / equation */
    private String type;

    /** 正文或标题文本 */
    private String text;

    /** 标题层级：1=H1、2=H2……；正文为 null */
    @JsonProperty("text_level")
    private Integer textLevel;

    /** 图片在 ZIP 中的相对路径；解析完成后会被改写为 MinIO 公网 URL */
    @JsonProperty("img_path")
    private String imgPath;

    /** PDF 原文中的图注，比视觉模型生成的描述更可信 */
    @JsonProperty("image_caption")
    private List<String> imageCaption;

    /** 图片脚注 */
    @JsonProperty("image_footnote")
    private List<String> imageFootnote;

    /** 表格 HTML 结构 */
    @JsonProperty("table_body")
    private String tableBody;

    /** 表格标题 */
    @JsonProperty("table_caption")
    private List<String> tableCaption;

    /** 表格脚注（显著性、缩写说明等） */
    @JsonProperty("table_footnote")
    private List<String> tableFootnote;

    /** 图表标题（MinerU 的 chart 类型使用该字段，而不是 image_caption） */
    @JsonProperty("chart_caption")
    private List<String> chartCaption;

    /** 图表脚注 */
    @JsonProperty("chart_footnote")
    private List<String> chartFootnote;

    /** 所在页码，从 0 开始 */
    @JsonProperty("page_idx")
    private Integer pageIdx;

    /**
     * 视觉模型生成的图片描述。非 MinerU 原生字段，由本项目解析流程回填，
     * 供分块阶段直接使用，无需再次调用视觉模型。
     */
    @JsonProperty("vision_description")
    private String visionDescription;

    public boolean isImage() {
        return "image".equals(type);
    }

    /**
     * MinerU 的 chart/table 元素也可能通过 img_path 指向渲染图片。
     */
    public boolean isVisualElement() {
        return "image".equals(type) || "chart".equals(type) || "table".equals(type);
    }

    public boolean isHeading() {
        return "text".equals(type) && textLevel != null && textLevel > 0;
    }

    /**
     * 获取最适合用作 Markdown alt 的图注。
     *
     * <p>MinerU 有时会把列标题、图例文字和真正图注一起放进 caption 数组。
     * 优先选择以“图/Figure/Fig./表/Table”等开头的正式图注，避免生成
     * {@code ![images](...)}、{@code ![Ground Truth](...)} 这类无效描述。</p>
     */
    public String firstCaption() {
        List<String> primaryCaptions;
        if ("chart".equals(type)) {
            primaryCaptions = chartCaption;
        } else if ("table".equals(type)) {
            primaryCaptions = tableCaption;
        } else {
            primaryCaptions = imageCaption;
        }

        String caption = preferredCaption(primaryCaptions);
        if (caption != null) {
            return caption;
        }

        // 兼容 MinerU 不同版本偶尔把标题放进其他 caption 字段的情况。
        caption = preferredCaption(imageCaption);
        if (caption != null) {
            return caption;
        }
        caption = preferredCaption(chartCaption);
        if (caption != null) {
            return caption;
        }
        return preferredCaption(tableCaption);
    }

    private String preferredCaption(List<String> captions) {
        if (captions == null) {
            return null;
        }
        String formalCaption = captions.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .filter(c -> CAPTION_PREFIX.matcher(c).find())
                .findFirst()
                .orElse(null);
        if (formalCaption != null) {
            return formalCaption;
        }
        return captions.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    // ========== Getters & Setters ==========

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getTextLevel() {
        return textLevel;
    }

    public void setTextLevel(Integer textLevel) {
        this.textLevel = textLevel;
    }

    public String getImgPath() {
        return imgPath;
    }

    public void setImgPath(String imgPath) {
        this.imgPath = imgPath;
    }

    public List<String> getImageCaption() {
        return imageCaption;
    }

    public void setImageCaption(List<String> imageCaption) {
        this.imageCaption = imageCaption;
    }

    public List<String> getImageFootnote() {
        return imageFootnote;
    }

    public void setImageFootnote(List<String> imageFootnote) {
        this.imageFootnote = imageFootnote;
    }

    public String getTableBody() {
        return tableBody;
    }

    public void setTableBody(String tableBody) {
        this.tableBody = tableBody;
    }

    public List<String> getTableCaption() {
        return tableCaption;
    }

    public void setTableCaption(List<String> tableCaption) {
        this.tableCaption = tableCaption;
    }

    public List<String> getTableFootnote() {
        return tableFootnote;
    }

    public void setTableFootnote(List<String> tableFootnote) {
        this.tableFootnote = tableFootnote;
    }

    public List<String> getChartCaption() {
        return chartCaption;
    }

    public void setChartCaption(List<String> chartCaption) {
        this.chartCaption = chartCaption;
    }

    public List<String> getChartFootnote() {
        return chartFootnote;
    }

    public void setChartFootnote(List<String> chartFootnote) {
        this.chartFootnote = chartFootnote;
    }

    public Integer getPageIdx() {
        return pageIdx;
    }

    public void setPageIdx(Integer pageIdx) {
        this.pageIdx = pageIdx;
    }

    public String getVisionDescription() {
        return visionDescription;
    }

    public void setVisionDescription(String visionDescription) {
        this.visionDescription = visionDescription;
    }
}
