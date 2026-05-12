package com.skala.axis.service;

import com.skala.axis.domain.ArticleImage;
import com.skala.axis.domain.CardNews;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.repository.ArticleImageRepository;
import com.skala.axis.repository.CardNewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardNewsService {
    private final CardNewsRepository cardNewsRepository;
    private final ArticleImageRepository articleImageRepository;

    public List<CardNewsResponse> getTodayCards(String peerId, String importance) {
        LocalDateTime since = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        List<CardNews> cards = cardNewsRepository.findTodayCards(since);
        return cards.stream()
                .filter(c -> peerId == null || peerId.equals(c.getPeerId()))
                .filter(c -> importance == null || importance.equals(c.getImportance()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<CardNewsResponse> getAll(String peerId, String importance, String eventType) {
        return cardNewsRepository.findAll().stream()
                .filter(c -> peerId == null || peerId.equals(c.getPeerId()))
                .filter(c -> importance == null || importance.equals(c.getImportance()))
                .filter(c -> eventType == null || eventType.equals(c.getEventType()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public CardNewsResponse getById(String id) {
        return cardNewsRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("카드 뉴스 없음: " + id));
    }

    private CardNewsResponse toResponse(CardNews card) {
        Optional<ArticleImage> image = articleImageRepository
                .findFirstByCardNewsIdOrderByCreatedAtDesc(card.getId());
        Map<String, Object> implication = card.getImplication() == null ? Map.of() : card.getImplication();
        String sector = stringValue(implication.get("sector"), "other");
        List<String> sectors = stringList(implication.get("sectors"));
        if (sectors.isEmpty()) {
            sectors = List.of(sector);
        }

        return CardNewsResponse.builder()
                .id(card.getId())
                .peerId(card.getPeerId())
                .clusterId(card.getClusterId())
                .title(card.getTitle())
                .eventType(card.getEventType())
                .sector(sector)
                .sectors(sectors)
                .exposureBand(stringValue(implication.get("exposure_band"), card.getImportance()))
                .exposureScore(floatValue(implication.get("exposure_score"), card.getImportanceScore()))
                .importance(card.getImportance())
                .importanceScore(card.getImportanceScore())
                .createdAt(card.getCreatedAt())
                .imageUrl(image.map(i -> "/api/images/" + i.getId()).orElse(null))
                .imageAttribution(image.map(ArticleImage::getAttribution).orElse(null))
                .imageAlt(image.map(ArticleImage::getAltText).orElse(null))
                .build();
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private Float floatValue(Object value, Float defaultValue) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }
}
