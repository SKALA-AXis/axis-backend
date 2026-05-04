package com.skala.axis.service;

import com.skala.axis.domain.ArticleImage;
import com.skala.axis.repository.ArticleImageRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 공유 볼륨에서 이미지 파일을 읽어 응답으로 내보낸다.
 *
 * 파일 자체의 INSERT/저장은 axis-ai (ImageFetchAgent) 담당.
 * 본 서비스는 read-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleImageService {

    private final ArticleImageRepository repository;

    /** axis.image.storage-path — 공유 볼륨 마운트 경로 (default /data/images). */
    @Value("${axis.image.storage-path:/data/images}")
    private String storageRootRaw;

    public record ImagePayload(Resource resource, String contentType, long contentLength) {}

    public ImagePayload load(Long id) {
        ArticleImage image = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("이미지 없음: id=" + id));

        Path resolved = resolveSafe(image.getStoragePath());
        if (!Files.isReadable(resolved)) {
            log.warn("이미지 파일 없음 또는 권한 없음 | id={} path={}", id, resolved);
            throw new EntityNotFoundException("이미지 파일 없음: id=" + id);
        }

        long size = sizeOrZero(resolved);
        String contentType = image.getContentType() != null
                ? image.getContentType()
                : "application/octet-stream";

        return new ImagePayload(new PathResource(resolved), contentType, size);
    }

    /**
     * storage_path 가 storage root 내부를 가리키는지 검증한다 (path-traversal 방어).
     * 실패 시 IllegalArgumentException — 컨트롤러에서 400 으로 변환.
     */
    Path resolveSafe(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("빈 storage_path");
        }
        Path root = Paths.get(storageRootRaw).toAbsolutePath().normalize();
        Path candidate = root.resolve(storagePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("storage_path 가 root 밖을 가리킴: " + storagePath);
        }
        return candidate;
    }

    private long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }
}
