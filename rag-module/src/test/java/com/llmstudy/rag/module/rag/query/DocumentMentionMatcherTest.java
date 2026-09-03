package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentMentionMatcherTest {

    @Test
    void matchesLongNamesFirstWithoutLosingStandaloneUNet() {
        List<KnowledgeDocument> documents = List.of(
                document("2015_MICCAI_U-Net", "v-unet"),
                document("2018_MIDL_Attention-U-Net", "v-attention"),
                document("2020_Nature-Methods_nnU-Net", "v-nnunet"));

        List<String> versions = DocumentMentionMatcher.mentionedVersionIds(
                "比较 U-Net、Attention U-Net 和 nnU-Net 三篇论文", documents);

        assertEquals(List.of("v-attention", "v-nnunet", "v-unet"), versions);
    }

    @Test
    void attentionNameDoesNotAlsoSelectPlainUNet() {
        List<KnowledgeDocument> documents = List.of(
                document("2015_MICCAI_U-Net", "v-unet"),
                document("2018_MIDL_Attention-U-Net.pdf", "v-attention"));

        assertEquals(List.of("v-attention"),
                DocumentMentionMatcher.mentionedVersionIds(
                        "Attention U-Net 的注意力门是什么？", documents));
    }

    @Test
    void stripsLayoutSuffixFromUploadedTitle() {
        assertEquals("MDCL-UNet", DocumentMentionMatcher.alias(
                "2026_Cognitive-Computation_MDCL-UNet_双栏"));
    }

    @Test
    void removesNamesButPreservesRetrievalTopic() {
        List<KnowledgeDocument> documents = List.of(
                document("2015_MICCAI_U-Net", "v-unet"),
                document("2018_MIDL_Attention-U-Net", "v-attention"),
                document("2020_Nature-Methods_nnU-Net", "v-nnunet"));

        String focused = DocumentMentionMatcher.withoutDocumentMentions(
                "How do U-Net, Attention U-Net and nnU-Net handle GPU memory?", documents);

        assertEquals("How do , and handle GPU memory?", focused);
    }

    private static KnowledgeDocument document(String title, String versionId) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocTitle(title);
        document.setCurrentVersionId(versionId);
        return document;
    }

    @Test
    void recognizesShortAliasesAndChineseBoundariesWithoutSubstringMatches() {
        List<KnowledgeDocument> documents = List.of(
                document("2020_NeurIPS_RAG", "rag"),
                document("2023_arXiv_RAGAS", "ragas"),
                document("2020_EMNLP_DPR", "dpr"),
                document("2023_ICCV_Segment-Anything", "sam"),
                document("2021_ICLR_Vision-Transformer", "vit"));
        assertEquals(List.of("ragas"), DocumentMentionMatcher.mentionedVersionIds("在RAGAS中", documents));
        assertEquals(List.of("rag", "dpr"), DocumentMentionMatcher.mentionedVersionIds("DPR和RAG有什么区别", documents));
        assertEquals(List.of("sam", "vit"), DocumentMentionMatcher.mentionedVersionIds("SAM与ViT", documents));
        assertEquals(List.of(), DocumentMentionMatcher.mentionedVersionIds("SAM-Med2D", documents));
    }

    @Test
    void duplicateTitlesDoNotResolveToAnArbitraryDocument() {
        assertEquals(List.of(), DocumentMentionMatcher.mentionedVersionIds("RAG的机制", List.of(
                document("2020_RAG", "a"), document("2021_RAG", "b"))));
    }
}
