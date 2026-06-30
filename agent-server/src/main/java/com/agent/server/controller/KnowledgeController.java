package com.agent.server.controller;

import com.agent.rag.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map> upload(@RequestPart("file") FilePart filePart) {
        Path tempFile;
        try {
            tempFile = Files.createTempFile("upload_", "_" + filePart.filename());
        } catch (Exception e) {
            return Mono.just(Map.of("error", "创建临时文件失败: " + e.getMessage()));
        }
        Path finalTempFile = tempFile;
        return filePart.transferTo(tempFile)
                .then(Mono.fromCallable(() -> {
                    UUID docId = knowledgeService.uploadDocument(finalTempFile, filePart.filename());
                    return (Map) Map.of("documentId", docId.toString(), "status", "COMPLETED");
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(e -> {
                    log.error("文档上传失败", e);
                    return Mono.just(Map.of("error", "文档上传失败: " + e.getMessage()));
                });
    }

    @GetMapping("/search")
    public Mono<Map> search(@RequestParam("q") String query,
                             @RequestParam(defaultValue = "3") int topK) {
        return Mono.fromCallable(() -> {
                    String result = knowledgeService.search(query, topK);
                    return (Map) Map.of("query", query, "topK", topK, "result", result);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
