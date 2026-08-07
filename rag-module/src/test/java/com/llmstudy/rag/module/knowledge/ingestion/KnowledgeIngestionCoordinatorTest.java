package com.llmstudy.rag.module.knowledge.ingestion;

import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.module.knowledge.document.KnowledgeDocumentService;
import com.llmstudy.rag.module.knowledge.ingestion.chunk.DocumentChunkingService;
import com.llmstudy.rag.module.knowledge.ingestion.embedding.SegmentEmbeddingService;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentParsedEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentUploadedEvent;
import com.llmstudy.rag.module.knowledge.model.DocumentProcessingOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionCoordinatorTest {

    @Test
    void excelImportStopsBeforeChunking() {
        KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
        DocumentChunkingService chunking = mock(DocumentChunkingService.class);
        SegmentEmbeddingService embedding = mock(SegmentEmbeddingService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(documents.processDocument("v-1"))
                .thenReturn(DocumentProcessingOutcome.EXCEL_IMPORTED);

        new KnowledgeIngestionCoordinator(documents, chunking, embedding, publisher)
                .onDocumentUploaded(new DocumentUploadedEvent(this, "v-1"));

        verify(publisher, never()).publishEvent(isA(DocumentParsedEvent.class));
    }

    @Test
    void parsedDocumentPublishesNextStage() {
        KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
        DocumentChunkingService chunking = mock(DocumentChunkingService.class);
        SegmentEmbeddingService embedding = mock(SegmentEmbeddingService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(chunking.splitDocument("v-1"))
                .thenReturn(new DocumentSplitResult("v-1", 3, "chunked", false));

        new KnowledgeIngestionCoordinator(documents, chunking, embedding, publisher)
                .onDocumentParsed(new DocumentParsedEvent(this, "v-1"));

        verify(publisher).publishEvent(any());
    }
}
