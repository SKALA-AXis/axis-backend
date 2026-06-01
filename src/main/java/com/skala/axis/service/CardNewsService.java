package com.skala.axis.service;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.domain.RawArticle;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.RawArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardNewsService {
    private static final DateTimeFormatter LEGACY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final CardNewsRepository cardNewsRepository;
    private final RawArticleRepository rawArticleRepository;

    public List<CardNewsResponse> getTodayCards(String peerId, String importance) {
        LocalDateTime since = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<CardNews> cards = cardNewsRepository.findTodayCards(since, CardNewsStatus.ACTIVE);
        return mapCards(cards, peerId, importance, null);
    }

    public List<CardNewsResponse> getAll(String peerId, String importance, String eventType) {
        List<CardNews> cards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
        return mapCards(cards, peerId, importance, eventType);
    }

    public CardNewsResponse getById(String id) {
        CardNews card = cardNewsRepository.findByIdAndStatus(id, CardNewsStatus.ACTIVE)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("카드 뉴스 없음: " + id));
        return toResponse(card, rawArticleById(List.of(card)));
    }

    private List<CardNewsResponse> mapCards(
            List<CardNews> cards,
            String peerId,
            String importance,
            String eventType
    ) {
        List<CardNews> filtered = cards.stream()
                .filter(card -> matchesPeer(card, peerId))
                .filter(card -> importance == null || importance.isBlank() || importance.equals(card.getImportance()))
                .filter(card -> eventType == null || eventType.isBlank() || eventType.equals(card.getEventType()))
                .toList();

        Map<Long, RawArticle> rawArticleById = rawArticleById(filtered);
        return filtered.stream()
                .map(card -> toResponse(card, rawArticleById))
                .collect(Collectors.toList());
    }

    public CardNewsResponse toResponse(CardNews card) {
        return toResponse(card, rawArticleById(List.of(card)));
    }

    private CardNewsResponse toResponse(CardNews card, Map<Long, RawArticle> rawArticleById) {
        Map<String, Object> implication = mapOrEmpty(card.getImplication());
        Map<String, Object> responseImplication = responseImplication(implication);
        Map<String, Object> sectorMeta = nestedMap(implication, "sector_meta");
        List<Map<String, Object>> sources = effectiveSources(card);
        Map<String, Object> primarySource = sources.isEmpty() ? Map.of() : sources.get(0);
        RawArticle primaryRawArticle = primaryRawArticle(card, rawArticleById);
        Map<String, Object> coverImage = firstImageAsset(card.getImageAssets());

        String peerId = resolvedPeerId(card);
        String sector = stringValue(sectorMeta.get("sector"), stringValue(implication.get("sector"), "other"));
        List<String> sectors = stringList(sectorMeta.get("sectors"));
        if (sectors.isEmpty()) {
            sectors = stringList(implication.get("sectors"));
        }
        if (sectors.isEmpty() && sector != null) {
            sectors = List.of(sector);
        }

        List<String> summaryLines = summaryLines(card);
        String publishedDate = publishedDate(primarySource, primaryRawArticle, card.getCreatedAt());
        String sourceUrl = stringValue(primarySource.get("url"), primaryRawArticle == null ? null : primaryRawArticle.getUrl());
        String sourceName = displaySourceName(primarySource, sourceUrl);
        List<Map<String, Object>> responseSources = new ArrayList<>(sources);
        if (responseSources.isEmpty() && sourceUrl != null && !sourceUrl.isBlank()) {
            Map<String, Object> fallbackSource = new LinkedHashMap<>();
            fallbackSource.put("index", 1);
            fallbackSource.put("title", firstNonBlank(card.getTitle(), sourceName, "대표 원문"));
            fallbackSource.put("url", sourceUrl);
            fallbackSource.put("source_name", sourceName);
            if (publishedDate != null) {
                fallbackSource.put("published_at", publishedDate);
            }
            responseSources.add(fallbackSource);
        }
        Float trustScore = trustScore(responseSources, card.getValidationScScore());
        String coverImageUrl = firstNonBlank(
                imageUrl(coverImage),
                rawArticleImageUrl(primaryRawArticle)
        );
        String coverImageAlt = firstNonBlank(
                stringValue(coverImage.get("alt_text"), null),
                stringValue(coverImage.get("caption"), null),
                rawArticleImageAlt(primaryRawArticle),
                card.getTitle() == null ? null : card.getTitle() + " 대표 이미지"
        );
        String whyImportant = stringValue(responseImplication.get("why_important"), null);
        String potentialImpact = stringValue(responseImplication.get("potential_impact"), null);
        List<String> suggestedActions = stringList(responseImplication.get("suggested_actions"));
        List<String> insights = potentialImpact == null || potentialImpact.isBlank()
                ? List.of()
                : List.of(potentialImpact);

        return CardNewsResponse.builder()
                .id(card.getId())
                .peerId(peerId)
                .clusterId(card.getClusterId())
                .title(card.getTitle())
                .subtitle(rawArticleSubtitle(primaryRawArticle))
                .category(categoryLabel(sector, card.getPrimaryKeywordCategory()))
                .date(legacyDate(publishedDate, card.getCreatedAt()))
                .eventType(card.getEventType())
                .sector(sector)
                .sectors(sectors)
                .categoryLabel(categoryLabel(sector, card.getPrimaryKeywordCategory()))
                .exposureBand(stringValue(
                        sectorMeta.get("exposure_band"),
                        stringValue(implication.get("exposure_band"), card.getImportance())
                ))
                .exposureScore(floatValue(
                        sectorMeta.get("exposure_score"),
                        floatValue(implication.get("exposure_score"), card.getImportanceScore())
                ))
                .trustScore(trustScore)
                .primaryKeywordCategory(stringValue(card.getPrimaryKeywordCategory(), sector))
                .keywords(keywords(card.getKeywords()))
                .keywordCategories(keywordCategories(card.getKeywordCategories()))
                .keywordFrequency(card.getKeywordFrequency() == null ? Map.of() : card.getKeywordFrequency())
                .importance(card.getImportance())
                .importanceScore(card.getImportanceScore())
                .publishedDate(publishedDate)
                .createdAt(card.getCreatedAt())
                .summaryLines(summaryLines)
                .summary(summaryLines)
                .source(sourceName)
                .sourceUrl(sourceUrl)
                .coverImageUrl(coverImageUrl)
                .coverImageAttribution(stringValue(coverImage.get("attribution"), null))
                .coverImageAlt(coverImageAlt)
                .detailDescription(whyImportant)
                .detailPoints(List.of())
                .insights(insights)
                .actionItems(suggestedActions)
                .implication(responseImplication)
                .sources(responseSources)
                .sourceCount(responseSources.size())
                .validationPass(card.getValidationPass())
                .isHumanReviewed(Boolean.TRUE.equals(card.getIsHumanReviewed()))
                .build();
    }

    private Map<Long, RawArticle> rawArticleById(List<CardNews> cards) {
        Set<Long> articleIds = new LinkedHashSet<>();
        cards.forEach(card -> {
            if (card.getPrimaryRawArticleId() != null) {
                articleIds.add(card.getPrimaryRawArticleId());
            }
            if (card.getSourceRawArticleIds() != null) {
                articleIds.addAll(Arrays.stream(card.getSourceRawArticleIds())
                        .filter(Objects::nonNull)
                        .toList());
            }
        });
        if (articleIds.isEmpty()) {
            return Map.of();
        }

        return rawArticleRepository.findAllById(articleIds).stream()
                .collect(Collectors.toMap(RawArticle::getId, article -> article, (left, right) -> left, LinkedHashMap::new));
    }

    private boolean matchesPeer(CardNews card, String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return true;
        }
        return peerId.equals(resolvedPeerId(card))
                || peerId.equals(card.getPeerId())
                || peerId.equals(card.getPeerCompanyId());
    }

    private String resolvedPeerId(CardNews card) {
        return firstNonBlank(card.getPeerCompanyId(), card.getPeerId());
    }

    private List<String> summaryLines(CardNews card) {
        List<String> fromColumn = stringList(card.getSummaryLines());
        if (!fromColumn.isEmpty()) {
            return fromColumn;
        }
        if (card.getTitle() == null || card.getTitle().isBlank()) {
            return List.of("요약 정보가 아직 정리되지 않았습니다.");
        }
        return List.of(card.getTitle());
    }

    private List<String> keywords(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private List<Object> keywordCategories(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Map<?, ?> map) {
            return new ArrayList<>(map.values());
        }
        return List.of(value);
    }

    private List<Map<String, Object>> effectiveSources(CardNews card) {
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        appendSources(deduped, card.getSources());
        appendSources(deduped, card.getSourceArticles());
        return List.copyOf(deduped.values());
    }

    private void appendSources(Map<String, Map<String, Object>> deduped, List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> normalized = normalizeSource(candidate);
            String key = firstNonBlank(
                    stringValue(normalized.get("url"), null),
                    stringValue(normalized.get("title"), null)
            );
            if (key == null) {
                continue;
            }
            deduped.putIfAbsent(key, normalized);
        }
    }

    private Map<String, Object> normalizeSource(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>(source);
        normalized.computeIfAbsent("title", ignored -> sourceTitle(source));
        return normalized;
    }

    private String sourceTitle(Map<String, Object> source) {
        return firstNonBlank(
                stringValue(source.get("title"), null),
                stringValue(source.get("headline"), null),
                stringValue(source.get("article_title"), null),
                stringValue(source.get("source_name"), null)
        );
    }

    private RawArticle primaryRawArticle(CardNews card, Map<Long, RawArticle> rawArticleById) {
        if (card.getPrimaryRawArticleId() != null) {
            RawArticle article = rawArticleById.get(card.getPrimaryRawArticleId());
            if (article != null) {
                return article;
            }
        }
        if (card.getSourceRawArticleIds() == null) {
            return null;
        }
        return Arrays.stream(card.getSourceRawArticleIds())
                .filter(Objects::nonNull)
                .map(rawArticleById::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> responseImplication(Map<String, Object> implication) {
        Map<String, Object> frontend = new LinkedHashMap<>(nestedMap(implication, "frontend"));
        Map<String, Object> skAx = nestedMap(implication, "skax_implication");
        if (frontend.isEmpty()) {
            frontend.putAll(implication);
        }
        putIfAbsent(frontend, "why_important",
                firstNonBlank(
                        stringValue(frontend.get("why_important"), null),
                        stringValue(implication.get("why_important"), null),
                        stringValue(skAx.get("why_important"), null)
                ));
        putIfAbsent(frontend, "potential_impact",
                firstNonBlank(
                        stringValue(frontend.get("potential_impact"), null),
                        stringValue(implication.get("potential_impact"), null),
                        stringValue(skAx.get("potential_impact"), null)
                ));
        if (stringList(frontend.get("suggested_actions")).isEmpty()) {
            List<String> actions = stringList(skAx.get("recommended_actions"));
            if (actions.isEmpty()) {
                actions = stringList(implication.get("recommended_actions"));
            }
            if (!actions.isEmpty()) {
                frontend.put("suggested_actions", actions);
            }
        }
        if (stringList(frontend.get("follow_up_questions")).isEmpty()) {
            List<String> questions = stringList(implication.get("follow_up_questions"));
            if (!questions.isEmpty()) {
                frontend.put("follow_up_questions", questions);
            }
        }
        return frontend;
    }

    private void putIfAbsent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank() && !target.containsKey(key)) {
            target.put(key, value);
        }
    }

    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> result.put(String.valueOf(nestedKey), nestedValue));
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> mapOrEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private Map<String, Object> firstImageAsset(List<Map<String, Object>> imageAssets) {
        if (imageAssets == null || imageAssets.isEmpty()) {
            return Map.of();
        }
        return imageAssets.stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.isEmpty())
                .findFirst()
                .orElse(Map.of());
    }

    private String imageUrl(Map<String, Object> image) {
        if (image.isEmpty()) {
            return null;
        }
        return firstNonBlank(
                stringValue(image.get("image_url"), null),
                stringValue(image.get("asset_url"), null),
                stringValue(image.get("source_url"), null)
        );
    }

    private String rawArticleImageUrl(RawArticle article) {
        if (article == null || article.getMetadata() == null) {
            return null;
        }
        Object imageUrls = article.getMetadata().get("image_urls");
        return stringList(imageUrls).stream().findFirst().orElse(null);
    }

    private String rawArticleImageAlt(RawArticle article) {
        if (article == null) {
            return null;
        }
        return firstNonBlank(
                stringValue(article.getTitle(), null),
                stringValue(article.getPublisher(), null)
        );
    }

    private String rawArticleSubtitle(RawArticle article) {
        if (article == null || article.getMetadata() == null) {
            return null;
        }
        return stringValue(article.getMetadata().get("subtitle"), null);
    }

    private String publishedDate(Map<String, Object> primarySource, RawArticle primaryRawArticle, LocalDateTime createdAt) {
        String publishedAt = stringValue(primarySource.get("published_at"), null);
        if (publishedAt != null && publishedAt.length() >= 10) {
            return publishedAt.substring(0, 10);
        }
        if (primaryRawArticle != null && primaryRawArticle.getPublishedAt() != null) {
            return primaryRawArticle.getPublishedAt().toLocalDate().toString();
        }
        return createdAt == null ? null : createdAt.toLocalDate().toString();
    }

    private String legacyDate(String publishedDate, LocalDateTime createdAt) {
        if (publishedDate != null && !publishedDate.isBlank()) {
            return publishedDate.replace('-', '.');
        }
        if (createdAt == null) {
            return null;
        }
        return createdAt.toLocalDate().format(LEGACY_DATE_FORMAT);
    }

    private Float trustScore(List<Map<String, Object>> sources, Float fallback) {
        List<Float> scores = sources.stream()
                .map(source -> floatValue(source.get("credibility_score"), null))
                .filter(Objects::nonNull)
                .toList();
        if (!scores.isEmpty()) {
            float sum = 0f;
            for (Float score : scores) {
                sum += score;
            }
            return sum / scores.size();
        }
        return fallback;
    }

    private String displaySourceName(Map<String, Object> primarySource, String sourceUrl) {
        String sourceName = stringValue(primarySource.get("source_name"), null);
        if (sourceName != null && !"naver_news".equalsIgnoreCase(sourceName)) {
            return sourceName;
        }
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return sourceName;
        }
        try {
            String host = URI.create(sourceUrl).getHost();
            if (host == null || host.isBlank()) {
                return sourceName;
            }
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return sourceName;
        }
    }

    private String categoryLabel(String sector, String primaryKeywordCategory) {
        String candidate = firstNonBlank(primaryKeywordCategory, sector);
        if (candidate == null) {
            return "AX";
        }
        return switch (candidate.toLowerCase(Locale.ROOT)) {
            case "ax", "ai" -> "AX";
            case "security" -> "보안";
            case "infra", "cloud" -> "인프라";
            case "deal", "contract", "new_biz" -> "수주";
            default -> "AX";
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstOrNull(Collection<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
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
        if (value instanceof String[] array) {
            return Arrays.stream(array)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
        }
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }
}
