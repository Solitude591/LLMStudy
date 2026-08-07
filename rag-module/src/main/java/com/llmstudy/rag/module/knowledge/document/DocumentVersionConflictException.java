package com.llmstudy.rag.module.knowledge.document;

/** 当前版本指针或版本状态与调用方预期不一致。 */
public class DocumentVersionConflictException extends RuntimeException {

    public DocumentVersionConflictException(String message) {
        super(message);
    }
}
