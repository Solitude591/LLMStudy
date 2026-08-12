package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.module.knowledge.ingestion.image.MarkdownImageProcessor;
import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTextSplitterTest {

    @Test
    void splitsLongChineseParagraphWithoutBlankLines() {
        String text = "这是第一句。这是第二句；这是从句。这是第三句，继续补充说明，然后结束。".repeat(20);
        SemanticTextSplitter splitter = new SemanticTextSplitter(40, 8);
        List<String> children = splitter.split(text);
        assertFalse(children.isEmpty());
        assertTrue(children.stream().allMatch(child ->
                SemanticTextSplitter.codePointCount(child) <= 40));
        assertCovers(text, children);
    }

    @Test
    void hardSplitsByUnicodeCodePointsWithoutBreakingSurrogatePairs() {
        String emoji = "😀".repeat(10) + "文字".repeat(100);
        SemanticTextSplitter splitter = new SemanticTextSplitter(30, 5);
        List<String> children = splitter.split(emoji);
        assertFalse(children.isEmpty());
        for (String child : children) {
            assertTrue(SemanticTextSplitter.codePointCount(child) <= 30);
            for (int offset = 0; offset < child.length(); ) {
                int codePoint = child.codePointAt(offset);
                assertFalse(Character.isSurrogate(child.charAt(offset))
                        && !Character.isSupplementaryCodePoint(codePoint));
                offset += Character.charCount(codePoint);
            }
        }
    }

    @Test
    void childrenCoverUniqueLongTextWithOverlap() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 80; index++) {
            text.append("段落").append(index).append("内容。");
        }
        String parent = text.toString();
        SemanticTextSplitter splitter = new SemanticTextSplitter(40, 8);
        List<String> children = splitter.split(parent);
        assertCovers(parent, children);
    }

    @Test
    void protectedRangeIsNotSplitInternally() {
        String text = "前文。" + "A".repeat(50) + "后文。";
        SemanticTextSplitter.IntRange protectedRange =
                new SemanticTextSplitter.IntRange(3, 53);
        SemanticTextSplitter splitter = new SemanticTextSplitter(20, 4);
        List<String> children = splitter.splitRespectingProtectedRanges(
                text, List.of(protectedRange));
        assertTrue(children.stream().anyMatch(child -> child.equals("A".repeat(50))));
    }

    @Test
    void prefersSentenceBoundaryOverWhitespaceNearLimit() {
        // 句号远早于 idealEnd，中间全是空格边界；必须先选句子，不能因空格更靠近 1000 而抢先。
        String earlySentence = "这是一句完整的中文说明。";
        String filler = "word ".repeat(180);
        String text = earlySentence + filler + "尾句。";
        SemanticTextSplitter splitter = new SemanticTextSplitter(100, 10);
        List<String> children = splitter.split(text);
        assertFalse(children.isEmpty());
        assertTrue(children.getFirst().endsWith("。"),
                () -> "首片应在句号处切开，实际=" + children.getFirst());
        assertTrue(children.getFirst().contains("这是一句完整的中文说明"));
    }

    private static void assertCovers(String parent, List<String> children) {
        int coveredUntil = 0;
        for (String child : children) {
            int idx = -1;
            int from = 0;
            while (from <= coveredUntil) {
                int candidate = parent.indexOf(child, from);
                if (candidate < 0 || candidate > coveredUntil) {
                    break;
                }
                if (candidate + child.length() > coveredUntil) {
                    idx = candidate;
                    break;
                }
                from = candidate + 1;
            }
            assertTrue(idx >= 0, "child 未能延伸覆盖 parent");
            coveredUntil = idx + child.length();
        }
        assertEquals(parent.length(), coveredUntil);
    }
}

class ContentListPaperChunkerTest {

    @Test
    void infersNumberedHeadingLevelsFromFlatMineruLevels() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        ContentListPaperChunker chunker = new ContentListPaperChunker(
                ids, new MarkdownImageProcessor(), 1000, 100);

        List<KnowledgeChunk> chunks = chunker.split(List.of(
                text("论文标题", 1, 0),
                text("2. Methodology", 2, 1),
                text("2.1. Problem Setting", 2, 2),
                text("问题定义正文。", null, 2),
                text("3. Experiments", 2, 3),
                text("3.1 Datasets", 2, 3),
                text("数据集正文。", null, 3)));

        assertEquals(2, chunks.size());
        assertEquals("论文标题 > 2. Methodology > 2.1. Problem Setting",
                chunks.get(0).metadata().get(SegmentMetadataKeys.HEADER_PATH));
        assertEquals("论文标题 > 3. Experiments > 3.1 Datasets",
                chunks.get(1).metadata().get(SegmentMetadataKeys.HEADER_PATH));
    }

    @Test
    void imagesAndTablesBecomeStandaloneWithPages() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        ContentListPaperChunker chunker = new ContentListPaperChunker(
                ids, new MarkdownImageProcessor(), 1000, 100);

        MineruContentElement title = text("论文标题", 1, 0);
        MineruContentElement section = text("方法", 2, 1);
        MineruContentElement body = text("短正文。", null, 1);
        MineruContentElement image = image(2);
        MineruContentElement table = table(3);

        List<KnowledgeChunk> chunks = chunker.split(
                List.of(title, section, body, image, table));

        assertEquals(3, chunks.size());
        KnowledgeChunk imageChunk = chunks.get(1);
        KnowledgeChunk tableChunk = chunks.get(2);
        assertTrue(imageChunk.text().contains("!["));
        assertTrue(tableChunk.text().contains("<table>"));
        assertEquals(3, imageChunk.metadata().get(SegmentMetadataKeys.PAGE_START));
        assertEquals(4, tableChunk.metadata().get(SegmentMetadataKeys.PAGE_END));
        assertAllowedMetadataKeys(chunks);
    }

    @Test
    void longTextProducesParentAndChildren() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        ContentListPaperChunker chunker = new ContentListPaperChunker(
                ids, new MarkdownImageProcessor(), 50, 10);
        MineruContentElement body = text("这是一句完整的中文说明。".repeat(20), null, 4);

        List<KnowledgeChunk> chunks = chunker.split(List.of(body));
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.getFirst().skipEmbedding());
        assertTrue(chunks.stream().skip(1).noneMatch(KnowledgeChunk::skipEmbedding));
        assertTrue(chunks.stream().skip(1).allMatch(chunk ->
                chunk.metadata().containsKey(SegmentMetadataKeys.PARENT_CHUNK_ID)));
        assertAllowedMetadataKeys(chunks);
    }

    @Test
    void keepsCodeListRefTextAndTableFootnotes() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        ContentListPaperChunker chunker = new ContentListPaperChunker(
                ids, new MarkdownImageProcessor(), 1000, 100);

        MineruContentElement body = text("普通正文。", null, 0);
        MineruContentElement code = new MineruContentElement();
        code.setType("code");
        code.setText("def f():\n  return 1");
        code.setPageIdx(0);
        MineruContentElement list = new MineruContentElement();
        list.setType("list");
        list.setText("- a\n- b");
        list.setPageIdx(0);
        MineruContentElement ref = new MineruContentElement();
        ref.setType("ref_text");
        ref.setText("[1] Author. Title.");
        ref.setPageIdx(1);
        MineruContentElement imageTable = new MineruContentElement();
        imageTable.setType("table");
        imageTable.setTableCaption(List.of("表 2 参数"));
        imageTable.setImgPath("https://minio/table.png");
        imageTable.setVisionDescription("参数对照表");
        imageTable.setTableFootnote(List.of("*p<0.05", "AUC: area under curve"));
        imageTable.setPageIdx(2);

        List<KnowledgeChunk> chunks = chunker.split(
                List.of(body, code, list, ref, imageTable));
        String packed = chunks.getFirst().text();
        assertTrue(packed.contains("普通正文"));
        assertTrue(packed.contains("def f():"));
        assertTrue(packed.contains("- a"));
        assertTrue(packed.contains("[1] Author"));
        KnowledgeChunk tableChunk = chunks.get(1);
        assertTrue(tableChunk.text().contains("表 2 参数"));
        assertTrue(tableChunk.text().contains("![参数对照表](https://minio/table.png)"));
        assertTrue(tableChunk.text().contains("脚注: *p<0.05"));
        assertTrue(tableChunk.text().contains("脚注: AUC: area under curve"));
    }

    private static MineruContentElement text(String value, Integer level, int pageIdx) {
        MineruContentElement element = new MineruContentElement();
        element.setType("text");
        element.setText(value);
        element.setTextLevel(level);
        element.setPageIdx(pageIdx);
        return element;
    }

    private static MineruContentElement image(int pageIdx) {
        MineruContentElement element = new MineruContentElement();
        element.setType("image");
        element.setImgPath("https://minio/img.png");
        element.setImageCaption(List.of("图 1 架构"));
        element.setVisionDescription("系统架构图");
        element.setPageIdx(pageIdx);
        return element;
    }

    private static MineruContentElement table(int pageIdx) {
        MineruContentElement element = new MineruContentElement();
        element.setType("table");
        element.setTableCaption(List.of("表 1 结果"));
        element.setTableBody("<table><tr><td>1</td></tr></table>");
        element.setPageIdx(pageIdx);
        return element;
    }

    private static void assertAllowedMetadataKeys(List<KnowledgeChunk> chunks) {
        Set<String> allowed = Set.of(
                SegmentMetadataKeys.PARENT_CHUNK_ID,
                SegmentMetadataKeys.HEADER_PATH,
                SegmentMetadataKeys.PAGE_START,
                SegmentMetadataKeys.PAGE_END);
        for (KnowledgeChunk chunk : chunks) {
            assertTrue(allowed.containsAll(chunk.metadata().keySet()),
                    () -> "unexpected keys: " + chunk.metadata().keySet());
            assertFalse(chunk.metadata().containsValue(""));
        }
    }
}

class MarkdownAstPaperChunkerTest {

    @Test
    void infersNumberedHeadingLevelsFromFlatMarkdown() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        MarkdownAstPaperChunker chunker = new MarkdownAstPaperChunker(ids, 1000, 100);
        String markdown = """
                # 论文标题

                ## 2 Methodology

                ## 2.1 Problem Setting

                问题定义正文。

                ## 3. Experiments

                ## 3.1. Datasets

                数据集正文。
                """;

        List<KnowledgeChunk> chunks = chunker.split(markdown);

        assertEquals(2, chunks.size());
        assertEquals("论文标题 > 2 Methodology > 2.1 Problem Setting",
                chunks.get(0).metadata().get(SegmentMetadataKeys.HEADER_PATH));
        assertEquals("论文标题 > 3. Experiments > 3.1. Datasets",
                chunks.get(1).metadata().get(SegmentMetadataKeys.HEADER_PATH));
    }

    @Test
    void imageParagraphBecomesStandaloneAndFallbackHasNoPages() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        MarkdownAstPaperChunker chunker = new MarkdownAstPaperChunker(ids, 1000, 100);
        String markdown = """
                # 标题

                一段正文。

                ![图注](https://minio/a.png)

                后续正文。
                """;
        List<KnowledgeChunk> chunks = chunker.split(markdown);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("![图注]")));
        assertTrue(chunks.stream().noneMatch(chunk ->
                chunk.metadata().containsKey(SegmentMetadataKeys.PAGE_START)));
        Set<String> keys = chunks.stream()
                .flatMap(chunk -> chunk.metadata().keySet().stream())
                .collect(Collectors.toSet());
        assertTrue(Set.of(SegmentMetadataKeys.HEADER_PATH,
                SegmentMetadataKeys.PARENT_CHUNK_ID).containsAll(keys)
                || keys.stream().allMatch(key ->
                key.equals(SegmentMetadataKeys.HEADER_PATH)
                        || key.equals(SegmentMetadataKeys.PARENT_CHUNK_ID)));
    }

    @Test
    void gfmPipeTableBecomesStandaloneWithoutInternalSplit() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        // chunkSize 很小：若被当成 Paragraph，管道表会在中间被切开。
        MarkdownAstPaperChunker chunker = new MarkdownAstPaperChunker(ids, 40, 5);
        String markdown = """
                # 结果

                | Metric | Value |
                | --- | --- |
                | Dice | 0.91 |
                | IoU | 0.84 |
                | HD95 | 12.3 |

                后续说明文字。
                """;
        List<KnowledgeChunk> chunks = chunker.split(markdown);
        KnowledgeChunk tableChunk = chunks.stream()
                .filter(chunk -> chunk.text().contains("| Dice | 0.91 |"))
                .findFirst()
                .orElseThrow();
        assertTrue(tableChunk.text().contains("| Metric | Value |"));
        assertTrue(tableChunk.text().contains("| HD95 | 12.3 |"));
        assertFalse(tableChunk.metadata().containsKey(SegmentMetadataKeys.PARENT_CHUNK_ID));
    }
}

class HeaderPathStackTest {

    @Test
    void leavesNonNumberedAndAlreadyCorrectLevelsUnchanged() {
        HeaderPathStack headers = new HeaderPathStack();
        headers.push(1, "论文标题");
        headers.push(2, "2. Methodology");
        headers.push(3, "2.1. Problem Setting");
        assertEquals("论文标题 > 2. Methodology > 2.1. Problem Setting", headers.path());

        headers.push(2, "3D Segmentation");
        assertEquals("论文标题 > 3D Segmentation", headers.path());
    }
}
