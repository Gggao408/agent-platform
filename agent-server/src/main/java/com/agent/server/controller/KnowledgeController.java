package com.agent.server.controller;

import com.agent.rag.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库 API 控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 文档上传接口。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("file") FilePart filePart) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("upload_", "_" + filePart.filename());
        } catch (Exception e) {
            return Mono.error(new RuntimeException("创建临时文件失败", e));
        }
        Path finalTempFile = tempFile;
        return filePart.transferTo(tempFile)
                .then(Mono.fromCallable(() -> {
                    UUID docId = knowledgeService.uploadDocument(finalTempFile, filePart.filename());
                    return Map.of("documentId", docId, "status", "COMPLETED");
                }));
    }

    /**
     * 知识库检索接口（调试用）。
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String query,
                                       @RequestParam(defaultValue = "3") int topK) {
        String result = knowledgeService.search(query, topK);
        return Map.of("query", query, "topK", topK, "result", result);
    }
}
