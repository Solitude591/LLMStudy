package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.enums.DocumentLanguage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentLanguageDetectorTest {

    @Test
    void englishPaperWithChineseImageCaptionIsEn() {
        MineruContentElement title = text("Efficient Cross Teaching for 3D Medical Image Segmentation", 1);
        MineruContentElement body = text("We propose a semi-supervised framework. ".repeat(8), null);
        MineruContentElement image = new MineruContentElement();
        image.setType("image");
        image.setVisionDescription("图 1 展示了模型在肺部CT上的分割结果，红色为肿瘤区域。".repeat(5));
        image.setImageCaption(List.of("图 1 系统架构"));
        assertEquals(DocumentLanguage.EN,
                DocumentLanguageDetector.fromContentList(List.of(title, body, image)));
    }

    @Test
    void chinesePaperWithEnglishReferencesIsZh() {
        MineruContentElement title = text("基于深度学习的医学图像分割方法研究", 1);
        MineruContentElement body = text("本文提出一种用于肺部肿瘤分割的网络结构。".repeat(8), null);
        MineruContentElement references = text("References", 1);
        MineruContentElement ref = text(
                "Ronneberger O. U-Net: Convolutional Networks for Biomedical Image Segmentation. "
                        .repeat(10), null);
        assertEquals(DocumentLanguage.ZH,
                DocumentLanguageDetector.fromContentList(List.of(title, body, references, ref)));
    }

    @Test
    void pureEnglishAndChinese() {
        assertEquals(DocumentLanguage.EN, DocumentLanguageDetector.detect("English text. ".repeat(20)));
        assertEquals(DocumentLanguage.ZH, DocumentLanguageDetector.detect("中文正文内容。".repeat(20)));
    }

    @Test
    void shortTextIsUnknown() {
        assertEquals(DocumentLanguage.UNKNOWN, DocumentLanguageDetector.detect("hello 你好"));
        assertEquals(DocumentLanguage.UNKNOWN, DocumentLanguageDetector.fromContentList(List.of()));
        assertEquals(DocumentLanguage.UNKNOWN, DocumentLanguageDetector.fromMarkdown(""));
    }

    @Test
    void mixedTextUsesHanRatioThreshold() {
        String han = "汉".repeat(100);
        String latin = "a".repeat(400);
        assertEquals(DocumentLanguage.ZH, DocumentLanguageDetector.detect(han + latin));
        assertEquals(DocumentLanguage.EN, DocumentLanguageDetector.detect("汉".repeat(99) + latin));
    }

    @Test
    void markdownFallbackSkipsCodeTableImageAndReferences() {
        String markdown = """
                # Title

                %s

                ```
                %s
                ```

                | Metric | Value |
                | --- | --- |
                | Dice | 0.91 |

                ![图注中文描述很多字](https://example/a.png)

                # 9. References:

                Ronneberger O. U-Net convolutional networks for biomedical image segmentation.
                """.formatted("We describe the method and datasets in detail. ".repeat(8),
                "中文代码注释".repeat(40));
        assertEquals(DocumentLanguage.EN, DocumentLanguageDetector.fromMarkdown(markdown));
    }

    private static MineruContentElement text(String value, Integer level) {
        MineruContentElement element = new MineruContentElement();
        element.setType("text");
        element.setText(value);
        element.setTextLevel(level);
        return element;
    }
}
