package com.llmstudy.rag.event;

import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.service.DocumentProcessingOutcome;
import com.llmstudy.rag.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DocumentLifecycleListenerTest {

    @Test
    void onDocumentUploaded_Excel导入后不发布Rag后续事件() {
        List<Object> publishedEvents = new ArrayList<>();
        DocumentLifecycleListener listener = new DocumentLifecycleListener(
                new StubDocumentService(DocumentProcessingOutcome.EXCEL_IMPORTED),
                null,
                publishedEvents::add);

        listener.onDocumentUploaded(new DocumentUploadedEvent(this, "1001"));

        assertEquals(List.of(), publishedEvents);
    }

    @Test
    void onDocumentUploaded_Rag文档解析后发布后续事件() {
        List<Object> publishedEvents = new ArrayList<>();
        DocumentLifecycleListener listener = new DocumentLifecycleListener(
                new StubDocumentService(DocumentProcessingOutcome.RAG_PARSED),
                null,
                publishedEvents::add);

        listener.onDocumentUploaded(new DocumentUploadedEvent(this, "1002"));

        assertEquals(1, publishedEvents.size());
        assertInstanceOf(DocumentParsedEvent.class, publishedEvents.getFirst());
    }

    private static final class StubDocumentService implements DocumentService {

        private final DocumentProcessingOutcome outcome;

        private StubDocumentService(DocumentProcessingOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public DocumentVO uploadDocument(MultipartFile file,
                                         String docTitle,
                                         String uploader,
                                         String visibility,
                                         String tableName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentVO getDocument(String docId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentProcessingOutcome processDocument(String docId) {
            return outcome;
        }
    }
}
