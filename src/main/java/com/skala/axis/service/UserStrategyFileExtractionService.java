package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserStrategyFileExtractionService {
    private static final int MAX_TEXT_CHARS = 80_000;
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final AiClientService aiClientService;

    public Map<String, Object> extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("파일을 선택하세요.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("전략 자료 파일은 5MB 이내로 업로드하세요.");
        }

        String fileName = safeFileName(file.getOriginalFilename());
        ExtractedText extracted = extractLocalText(file, fileName);
        String normalized = normalizeText(extracted.text());
        String extractionMethod = extracted.method();
        boolean ocrUsed = false;

        if (normalized.isBlank() && extracted.ocrCandidate()) {
            normalized = normalizeText(extractByOcr(file, fileName));
            extractionMethod = "openai_vision_ocr";
            ocrUsed = true;
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("파일에서 읽을 수 있는 텍스트가 없습니다.");
        }

        boolean truncated = normalized.length() > MAX_TEXT_CHARS;
        if (truncated) {
            normalized = normalized.substring(0, MAX_TEXT_CHARS).trim();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", fileName);
        response.put("fileSize", file.getSize());
        response.put("contentType", file.getContentType());
        response.put("extractedText", normalized);
        response.put("truncated", truncated);
        response.put("extractionMethod", extractionMethod);
        response.put("ocrUsed", ocrUsed);
        return response;
    }

    private ExtractedText extractLocalText(MultipartFile file, String fileName) {
        String contentType = normalizeContentType(file.getContentType());
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        try {
            if (contentType.contains("pdf") || lowerName.endsWith(".pdf")) {
                try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                    return new ExtractedText(new PDFTextStripper().getText(document), "pdf_text", true);
                }
            }
            if (isPlainText(contentType, lowerName)) {
                return new ExtractedText(new String(file.getBytes(), StandardCharsets.UTF_8), "plain_text", false);
            }
            if (isImage(contentType, lowerName)) {
                return new ExtractedText("", "openai_vision_ocr_candidate", true);
            }
        } catch (IOException exc) {
            throw new IllegalArgumentException("파일 본문을 읽지 못했습니다.");
        }
        throw new IllegalArgumentException("현재는 텍스트, PDF, 이미지 파일만 지원합니다.");
    }

    private String extractByOcr(MultipartFile file, String fileName) {
        try {
            Map<String, Object> response = aiClientService.ocrUserStrategyFile(
                    fileName,
                    normalizeContentType(file.getContentType()),
                    file.getBytes()
            ).block();
            if (response == null) {
                return "";
            }
            Object text = response.get("extractedText");
            if (text == null) {
                text = response.get("extracted_text");
            }
            return text == null ? "" : String.valueOf(text);
        } catch (IOException exc) {
            throw new IllegalArgumentException("OCR 처리를 위한 파일 읽기에 실패했습니다.");
        }
    }

    private boolean isPlainText(String contentType, String lowerName) {
        return contentType.startsWith("text/")
                || contentType.contains("json")
                || lowerName.endsWith(".txt")
                || lowerName.endsWith(".md")
                || lowerName.endsWith(".csv")
                || lowerName.endsWith(".json")
                || lowerName.endsWith(".log");
    }

    private boolean isImage(String contentType, String lowerName) {
        return contentType.startsWith("image/")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".webp");
    }

    private String normalizeText(String value) {
        String text = String.valueOf(value == null ? "" : value)
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        return MANY_BLANK_LINES.matcher(text).replaceAll("\n\n").trim();
    }

    private String normalizeContentType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String value) {
        String fileName = value == null ? "uploaded-file" : value.trim();
        fileName = fileName.replace("\\", "/");
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash >= 0) {
            fileName = fileName.substring(lastSlash + 1);
        }
        return fileName.isBlank() ? "uploaded-file" : fileName;
    }

    private record ExtractedText(String text, String method, boolean ocrCandidate) {}
}
