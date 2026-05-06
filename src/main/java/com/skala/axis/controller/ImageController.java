package com.skala.axis.controller;

import com.skala.axis.service.ArticleImageService;
import com.skala.axis.service.ArticleImageService.ImagePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ArticleImageService imageService;

    /**
     * 이미지 바이너리 반환. CDN 도입 전에는 backend 가 직접 서빙한다.
     * 캐시 1년 (storage_path 가 immutable key 역할).
     */
    @GetMapping("/{id}")
    public ResponseEntity<Resource> getImage(@PathVariable Long id) {
        ImagePayload payload = imageService.load(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .contentLength(payload.contentLength())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .body(payload.resource());
    }
}
