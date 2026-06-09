package com.skala.axis.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 공유 볼륨에서 이미지 파일을 읽어 응답으로보낸다.
 *
 * <p>V30 이후 {@code article_images} 테이블은 제거되었고 metadata는
 * {@code card_news.image_assets} 또는 {@code legacy_records}에 보관된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleImageService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${axis.image.storage-path:/data/images}")
    private String storageRootRaw;

    public record ImagePayload(Resource resource, String contentType, long contentLength) {}

    public ImagePayload load(Long id) {
        ImageMeta meta = findImageMeta(id)
                .orElseThrow(() -> new EntityNotFoundException("이미지 없음: id=" + id));

        Path resolved = resolveSafe(meta.storagePath);
        if (!Files.isReadable(resolved)) {
            log.warn("이미지 파일 없음 또는 권한 없음 | id={} path={}", id, resolved);
            throw new EntityNotFoundException("이미지 파일 없음: id=" + id);
        }

        long size = sizeOrZero(resolved);
        String contentType = meta.contentType != null && !meta.contentType.isBlank()
                ? meta.contentType
                : "application/octet-stream";

        return new ImagePayload(new PathResource(resolved), contentType, size);
    }

    private java.util.Optional<ImageMeta> findImageMeta(Long id) {
        java.util.Optional<ImageMeta> legacy = jdbcTemplate.query(
                """
                SELECT payload->>'storage_path' AS storage_path,
                       payload->>'content_type' AS content_type
                FROM legacy_records
                WHERE source_table = 'article_images'
                  AND source_pk = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new ImageMeta(
                        rs.getString("storage_path"),
                        rs.getString("content_type")),
                String.valueOf(id))
                .stream()
                .filter(meta -> meta.storagePath != null && !meta.storagePath.isBlank())
                .findFirst();
        if (legacy.isPresent()) {
            return legacy;
        }

        return jdbcTemplate.query(
                """
                SELECT elem->>'storage_path' AS storage_path,
                       elem->>'content_type' AS content_type
                FROM card_news cn,
                     jsonb_array_elements(cn.image_assets) elem
                WHERE (elem->>'id')::bigint = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new ImageMeta(
                        rs.getString("storage_path"),
                        rs.getString("content_type")),
                id)
                .stream()
                .filter(meta -> meta.storagePath != null && !meta.storagePath.isBlank())
                .findFirst();
    }

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

    private long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private record ImageMeta(String storagePath, String contentType) {}
}
