package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
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
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final String SELF_PEER_ID = "sk_ax";
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CardNewsRepository cardNewsRepository;
    private final RawArticleRepository rawArticleRepository;
    private final ObjectMapper objectMapper;

    public List<CardNewsResponse> getTodayCards(String peerId, String importance) {
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        List<CardNews> cards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
        return mapCards(cards, peerId, importance, null, today, true);
    }

    public List<CardNewsResponse> getAll(String peerId, String importance, String eventType) {
        List<CardNews> cards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
        return mapCards(cards, peerId, importance, eventType, null, false);
    }

    public CardNewsResponse getById(String id) {
        CardNews card = cardNewsRepository.findByIdAndStatus(id, CardNewsStatus.ACTIVE)
                .filter(cardItem -> !isSelfCompanyCard(cardItem))
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("카드 뉴스 없음: " + id));
        return toResponse(card, rawArticleById(List.of(card)));
    }

    private List<CardNewsResponse> mapCards(
            List<CardNews> cards,
            String peerId,
            String importance,
            String eventType,
            LocalDate basisDate,
            boolean importanceFirst
    ) {
        List<CardNews> filtered = cards.stream()
                .filter(card -> !isSelfCompanyCard(card))
                .filter(card -> matchesPeer(card, peerId))
                .filter(card -> importance == null || importance.isBlank() || importance.equals(card.getImportance()))
                .filter(card -> eventType == null || eventType.isBlank() || eventType.equals(card.getEventType()))
                .toList();

        Map<Long, RawArticle> rawArticleById = rawArticleById(filtered);
        Comparator<CardNews> basisComparator = Comparator
                .comparing((CardNews card) -> sortableBasisAt(card, rawArticleById))
                .reversed();
        Comparator<CardNews> importanceComparator = Comparator
                .comparing((CardNews card) -> card.getImportanceScore() == null ? -1f : card.getImportanceScore())
                .reversed();
        Comparator<CardNews> comparator = importanceFirst
                ? importanceComparator.thenComparing(basisComparator)
                : basisComparator.thenComparing(importanceComparator);
        return filtered.stream()
                .filter(card -> basisDate == null || basisDate.equals(cardBasisDate(card, rawArticleById)))
                .sorted(comparator)
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
        LocalDateTime basisAt = cardBasisAt(card, rawArticleById);
        String publishedDate = publishedDate(primarySource, primaryRawArticle, basisAt, card.getCreatedAt());
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
        int sourceCount = sourceCount(card, responseSources);
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
        List<String> insights = stringList(responseImplication.get("key_implications"));
        if (insights.isEmpty() && potentialImpact != null && !potentialImpact.isBlank()) {
            insights = List.of(potentialImpact);
        }
        List<Map<String, Object>> insightDetails = structuredTextItems(
                responseImplication,
                List.of("key_implication_blocks", "key_implication_items"),
                insights
        );
        List<Map<String, Object>> actionDetails = structuredTextItems(
                responseImplication,
                List.of("response_direction_blocks", "suggested_action_items", "skax_checkpoint_blocks"),
                suggestedActions
        );

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
                .insightDetails(insightDetails)
                .actionDetails(actionDetails)
                .implication(responseImplication)
                .sources(responseSources)
                .sourceRawArticleIds(sourceRawArticleIds(card))
                .sourceCount(sourceCount)
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

    private LocalDate cardBasisDate(CardNews card, Map<Long, RawArticle> rawArticleById) {
        LocalDateTime basisAt = cardBasisAt(card, rawArticleById);
        String basisDate = localDateInDisplayZone(basisAt);
        return basisDate == null ? null : LocalDate.parse(basisDate);
    }

    private LocalDateTime sortableBasisAt(CardNews card, Map<Long, RawArticle> rawArticleById) {
        LocalDateTime basisAt = cardBasisAt(card, rawArticleById);
        return basisAt == null ? LocalDateTime.MIN : basisAt;
    }

    private LocalDateTime cardBasisAt(CardNews card, Map<Long, RawArticle> rawArticleById) {
        LocalDateTime latestPublishedAt = latestSourcePublishedAt(card, rawArticleById);
        return latestPublishedAt == null ? card.getCreatedAt() : latestPublishedAt;
    }

    private LocalDateTime latestSourcePublishedAt(CardNews card, Map<Long, RawArticle> rawArticleById) {
        List<LocalDateTime> candidates = new ArrayList<>();
        effectiveSources(card).stream()
                .map(source -> parseSourcePublishedAt(source.get("published_at")))
                .filter(Objects::nonNull)
                .forEach(candidates::add);

        LinkedHashSet<Long> articleIds = new LinkedHashSet<>();
        if (card.getPrimaryRawArticleId() != null) {
            articleIds.add(card.getPrimaryRawArticleId());
        }
        if (card.getSourceRawArticleIds() != null) {
            Arrays.stream(card.getSourceRawArticleIds())
                    .filter(Objects::nonNull)
                    .forEach(articleIds::add);
        }
        articleIds.stream()
                .map(rawArticleById::get)
                .filter(Objects::nonNull)
                .map(RawArticle::getPublishedAt)
                .filter(Objects::nonNull)
                .forEach(candidates::add);

        return candidates.stream().max(LocalDateTime::compareTo).orElse(null);
    }

    private LocalDateTime parseSourcePublishedAt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String text = String.valueOf(value).trim();
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Fall through to local date-time/date parsing for legacy source payloads.
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // Fall through.
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length()))).atStartOfDay();
        } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private boolean matchesPeer(CardNews card, String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return true;
        }
        return peerId.equals(resolvedPeerId(card))
                || peerId.equals(card.getPeerId())
                || peerId.equals(card.getPeerCompanyId());
    }

    private boolean isSelfCompanyCard(CardNews card) {
        return SELF_PEER_ID.equals(resolvedPeerId(card))
                || SELF_PEER_ID.equals(card.getPeerId())
                || SELF_PEER_ID.equals(card.getPeerCompanyId());
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

    private int sourceCount(CardNews card, List<Map<String, Object>> responseSources) {
        List<Long> sourceRawArticleIds = sourceRawArticleIds(card);
        if (!sourceRawArticleIds.isEmpty()) {
            return sourceRawArticleIds.size();
        }
        List<Long> provenanceIds = longList(nestedMap(card.getEvidencePayload(), "provenance").get("raw_article_ids"));
        if (!provenanceIds.isEmpty()) {
            return (int) provenanceIds.stream().filter(Objects::nonNull).distinct().count();
        }
        List<Long> evidenceIds = longList(nestedMap(card.getEvidencePayload(), "evidence_chain").get("raw_article_ids"));
        if (!evidenceIds.isEmpty()) {
            return (int) evidenceIds.stream().filter(Objects::nonNull).distinct().count();
        }
        return Math.max(responseSources.size(), 1);
    }

    private List<Long> sourceRawArticleIds(CardNews card) {
        if (card.getSourceRawArticleIds() != null && card.getSourceRawArticleIds().length > 0) {
            return Arrays.stream(card.getSourceRawArticleIds())
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private List<Long> longList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::longValue)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        if (value instanceof Object[] array) {
            return Arrays.stream(array)
                    .map(this::longValue)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        Long single = longValue(value);
        return single == null ? List.of() : List.of(single);
    }

    private void appendSources(Map<String, Map<String, Object>> deduped, Object candidates) {
        List<Map<String, Object>> normalizedCandidates = mapList(candidates);
        if (normalizedCandidates.isEmpty()) {
            return;
        }
        for (Map<String, Object> candidate : normalizedCandidates) {
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

    private Map<String, Object> firstImageAsset(Object imageAssets) {
        List<Map<String, Object>> normalizedAssets = mapList(imageAssets);
        if (normalizedAssets.isEmpty()) {
            return Map.of();
        }
        return normalizedAssets.stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.isEmpty())
                .findFirst()
                .orElse(Map.of());
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> map = objectMap(item);
                if (!map.isEmpty()) {
                    result.add(map);
                }
            }
            return result;
        }
        Object parsed = parsedJsonValue(value);
        if (parsed != value) {
            return mapList(parsed);
        }
        Map<String, Object> single = objectMap(value);
        return single.isEmpty() ? List.of() : List.of(single);
    }

    private List<Map<String, Object>> structuredTextItems(
            Map<String, Object> source,
            List<String> keys,
            List<String> fallbackLines
    ) {
        for (String key : keys) {
            List<Map<String, Object>> blocks = normalizeStructuredTextBlocks(mapList(source.get(key)));
            if (!blocks.isEmpty()) {
                return blocks;
            }
        }
        return structuredTextBlocksFromLines(fallbackLines);
    }

    private List<Map<String, Object>> normalizeStructuredTextBlocks(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Map<String, Object> item : values) {
            String main = firstNonBlank(
                    stringValue(item.get("main"), null),
                    stringValue(item.get("sentence"), null)
            );
            String detail = firstNonBlank(
                    stringValue(item.get("detail"), null),
                    stringValue(item.get("evidence_sentence"), null)
            );
            if (main == null) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("main", main);
            block.put("detail", detail == null ? "" : detail);
            blocks.add(block);
        }
        return blocks;
    }

    private List<Map<String, Object>> structuredTextBlocksFromLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (String line : lines) {
            Map<String, Object> block = splitMainDetailBlock(line);
            if (!stringValue(block.get("main"), "").isBlank()) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private Map<String, Object> splitMainDetailBlock(String line) {
        String text = line == null ? "" : line.trim().replaceAll("\\s+", " ");
        text = text.replaceFirst("^핵심\\s*(?:시사점|대응)\\s*[:：]\\s*", "").trim();
        String[] parts = text.split("\\s*근거\\s*/?\\s*설명\\s*[:：]\\s*", 2);
        String main = parts.length > 0 ? parts[0].trim() : "";
        String detail = parts.length > 1 ? parts[1].trim() : "";
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("main", main);
        block.put("detail", detail);
        return block;
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        Object parsed = parsedJsonValue(value);
        if (parsed != value) {
            return objectMap(parsed);
        }
        return Map.of();
    }

    private Object parsedJsonValue(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
            return value;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return value;
        }
        try {
            if (text.startsWith("[")) {
                return objectMapper.readValue(text, MAP_LIST_TYPE);
            }
            if (text.startsWith("{")) {
                return objectMapper.readValue(text, MAP_TYPE);
            }
        } catch (Exception ignored) {
            return value;
        }
        return value;
    }

    private String imageUrl(Map<String, Object> image) {
        if (image.isEmpty()) {
            return null;
        }
        return firstNonBlank(
                stringValue(image.get("url"), null),
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

    private String publishedDate(
            Map<String, Object> primarySource,
            RawArticle primaryRawArticle,
            LocalDateTime basisAt,
            LocalDateTime createdAt
    ) {
        String basisDate = localDateInDisplayZone(basisAt);
        if (basisDate != null) {
            return basisDate;
        }
        String publishedAt = stringValue(primarySource.get("published_at"), null);
        String sourceDate = localDateInDisplayZone(publishedAt);
        if (sourceDate != null) {
            return sourceDate;
        }
        if (primaryRawArticle != null && primaryRawArticle.getPublishedAt() != null) {
            return localDateInDisplayZone(primaryRawArticle.getPublishedAt());
        }
        return localDateInDisplayZone(createdAt);
    }

    private String localDateInDisplayZone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim())
                    .atZoneSameInstant(DISPLAY_ZONE)
                    .toLocalDate()
                    .toString();
        } catch (DateTimeParseException ignored) {
            if (value.length() >= 10) {
                return value.substring(0, 10);
            }
            return null;
        }
    }

    private String localDateInDisplayZone(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(DISPLAY_ZONE)
                .toLocalDate()
                .toString();
    }

    private String legacyDate(String publishedDate, LocalDateTime createdAt) {
        if (publishedDate != null && !publishedDate.isBlank()) {
            return publishedDate.replace('-', '.');
        }
        if (createdAt == null) {
            return null;
        }
        return localDateInDisplayZone(createdAt).replace('-', '.');
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

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
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
