package com.llmstudy.rag.controller.dev;

import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("dev")
@RequestMapping("/chat/client")
public class QueryTransformerController {

    private final QueryRewriter queryRewriter;

    public QueryTransformerController(QueryRewriter queryRewriter) {
        this.queryRewriter = queryRewriter;
    }

    /** Compatibility keeps the historical misspelled debug endpoint. */
    @GetMapping("/test-transfomrer")
    public Map<String, String> transform(@RequestParam String query) {
        RewrittenQuery rewritten = queryRewriter.rewrite(new RagRequest(query, "无"));
        return Map.of("transformedQuery", rewritten.rewrittenQuestion(),
                "originalQuery", rewritten.originalQuestion());
    }
}
