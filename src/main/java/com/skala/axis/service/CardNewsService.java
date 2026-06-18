/*
 * 작성일: 2026-05-12
 * 작성자: 최종민
 * 변경이력:
 *   2026-05-12 최종민 — issue_cards를 card_news로 개편하고 DTO 이미지 필드를 cover*로 정렬
 *   2026-05-19 박진 — 불필요 기능 페이지 정리 및 카드뉴스 데이터 연동·UI 버튼 추가
 *   2026-05-27 안가은 — 카드뉴스 데이터 연동, 소프트삭제·관리자 감사로그·키워드 그래프 API 추가
 *   2026-06-10 심유정 — 카드뉴스 프런트 노출 필드 및 표시일 타임존 처리 보완, 전략 액션 프로젝션 추가
 *   2026-06-11 박지원 — 카드뉴스 소스 카운트·이미지 폴백·날짜 표시 수정, 자사 필터링·산업 트렌드 지원
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.domain.RawArticle;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.RawArticleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CardNewsService {
    private static final DateTimeFormatter LEGACY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final String SELF_PEER_ID = "sk_ax";
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CardNewsRepository cardNewsRepository;
    private final RawArticleRepository rawArticleRepository;
    private final AiClientService aiClientService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CardNewsService(
            CardNewsRepository cardNewsRepository,
            RawArticleRepository rawArticleRepository,
            AiClientService aiClientService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.cardNewsRepository = cardNewsRepository;
        this.rawArticleRepository = rawArticleRepository;
        this.aiClientService = aiClientService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CardNewsService(
            CardNewsRepository cardNewsRepository,
            RawArticleRepository rawArticleRepository,
            ObjectMapper objectMapper
    ) {
        this(cardNewsRepository, rawArticleRepository, null, objectMapper, null);
    }

    public List<CardNewsResponse> getTodayCards(String peerId, String importance) {
        return getTodayCards(peerId, importance, null);
    }

    public List<CardNewsResponse> getTodayCards(String peerId, String importance, UUID userId) {
      LocalDate today = LocalDate.now(DISPLAY_ZONE);
      List<CardNews> cards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
      return mapCards(cards, peerId, importance, null, today, false, userId);
}

    public List<CardNewsResponse> getAll(String peerId, String importance, String eventType) {
        return getAll(peerId, importance, eventType, null);
    }

    public List<CardNewsResponse> getAll(String peerId, String importance, String eventType, UUID userId) {
        List<CardNews> cards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
        return mapCards(cards, peerId, importance, eventType, null, false, userId);
    }

    public CardNewsResponse getById(String id) {
        return getById(id, null);
    }

    public CardNewsResponse getById(String id, UUID userId) {
        CardNews card = cardNewsRepository.findByIdAndStatus(id, CardNewsStatus.ACTIVE)
                .filter(cardItem -> !isSelfCompanyCard(cardItem))
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("카드 뉴스 없음: " + id));
        return toResponse(card, rawArticleById(List.of(card)), activeProjectionByCardId(List.of(card), userId).get(card.getId()));
    }

    @Transactional
    public CardNewsResponse applyStrategyContext(String id, UUID userId) {
        if (userId == null) {
            throw new IllegalStateException("로그인 사용자만 맞춤 전략을 적용할 수 있습니다.");
        }
        if (aiClientService == null) {
            throw new IllegalStateException("AI 클라이언트를 사용할 수 없습니다.");
        }
        CardNews card = cardNewsRepository.findByIdAndStatus(id, CardNewsStatus.ACTIVE)
                .filter(cardItem -> !isSelfCompanyCard(cardItem))
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("카드 뉴스 없음: " + id));

        Map<String, Object> evidencePayload = deepCopyMap(card.getEvidencePayload());
        Map<String, Object> analysisPackage = objectMap(evidencePayload.get("analysis_package"));
        if (analysisPackage.isEmpty()) {
            throw new IllegalStateException("analysis_package가 없어 전략 재생성을 할 수 없습니다.");
        }

        Map<String, Object> result = aiClientService.regenerateCardNewsStrategyContext(
                card.getId(),
                analysisPackage,
                userId
        ).block();
        Map<String, Object> cardProjection = objectMap(result == null ? null : result.get("card_news"));
        Map<String, Object> nextAnalysisPackage = objectMap(result == null ? null : result.get("analysis_package"));
        Map<String, Object> nextImplication = objectMap(cardProjection.get("implication"));
        if (nextImplication.isEmpty()) {
            nextImplication = objectMap(objectMap(result == null ? null : result.get("strategic_result")).get("implication"));
        }
        if (nextAnalysisPackage.isEmpty() || nextImplication.isEmpty()) {
            throw new IllegalStateException("전략 재생성 결과가 비어 있습니다.");
        }

        Map<String, Object> projection = upsertStrategyProjection(
                card.getId(),
                userId,
                appliedActionFromImplication(nextImplication)
        );
        return toResponse(card, rawArticleById(List.of(card)), projection);
    }

    @Transactional
    public CardNewsResponse revertStrategyContext(String id, UUID userId) {
        if (userId == null) {
            throw new IllegalStateException("로그인 사용자만 맞춤 전략 적용을 해제할 수 있습니다.");
        }
        CardNews card = cardNewsRepository.findByIdAndStatus(id, CardNewsStatus.ACTIVE)
                .filter(cardItem -> !isSelfCompanyCard(cardItem))
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("카드 뉴스 없음: " + id));

        deactivateStrategyProjection(card.getId(), userId);
        return toResponse(card, rawArticleById(List.of(card)), Map.of());
    }

    private List<CardNewsResponse> mapCards(
            List<CardNews> cards,
            String peerId,
            String importance,
            String eventType,
            LocalDate basisDate,
            boolean importanceFirst,
            UUID userId
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
        Map<String, Map<String, Object>> projectionByCardId = activeProjectionByCardId(filtered, userId);
        return filtered.stream()
                .filter(card -> basisDate == null || basisDate.equals(cardBasisDate(card, rawArticleById)))
                .sorted(comparator)
                .map(card -> toResponse(card, rawArticleById, projectionByCardId.get(card.getId())))
                .collect(Collectors.toList());
    }

    public CardNewsResponse toResponse(CardNews card) {
        return toResponse(card, rawArticleById(List.of(card)), null);
    }

    private CardNewsResponse toResponse(CardNews card, Map<Long, RawArticle> rawArticleById) {
        return toResponse(card, rawArticleById, null);
    }

    private CardNewsResponse toResponse(
            CardNews card,
            Map<Long, RawArticle> rawArticleById,
            Map<String, Object> activeProjection
    ) {
        Map<String, Object> projection = objectMap(activeProjection);
        Map<String, Object> implication = implicationWithAppliedAction(
                mapOrEmpty(card.getImplication()),
                objectMap(projection.get("applied_action"))
        );
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
        String publishedAt = publishedAt(basisAt);
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
        boolean strategyContextApplied = Boolean.TRUE.equals(projection.get("is_applied"));
        String strategyContextAppliedAt = stringValue(projection.get("applied_at"), null);
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
                .category(displayCategoryLabel(card, sector))
                .date(legacyDate(publishedDate, card.getCreatedAt()))
                .eventType(card.getEventType())
                .sector(sector)
                .sectors(sectors)
                .categoryLabel(displayCategoryLabel(card, sector))
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
                .publishedAt(publishedAt)
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
                .strategyContextApplied(strategyContextApplied)
                .strategyContextAppliedAt(strategyContextAppliedAt)
                .build();
    }

    private Map<String, Map<String, Object>> activeProjectionByCardId(List<CardNews> cards, UUID userId) {
        if (jdbcTemplate == null || userId == null || cards == null || cards.isEmpty()) {
            return Map.of();
        }
        List<String> cardIds = cards.stream()
                .map(CardNews::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = cardIds.stream()
                .map(ignored -> "?")
                .collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(cardIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT card_news_id,
                       applied_action,
                       is_applied,
                       applied_at
                  FROM card_news_strategy_context_projections
                 WHERE user_id = ?
                   AND is_applied = TRUE
                   AND card_news_id IN (%s)
                """.formatted(placeholders),
                args.toArray()
        );
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String cardId = stringValue(row.get("card_news_id"), null);
            if (cardId != null) {
                result.put(cardId, new LinkedHashMap<>(row));
            }
        }
        return result;
    }

    private Map<String, Object> upsertStrategyProjection(
            String cardNewsId,
            UUID userId,
            Map<String, Object> appliedAction
    ) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("맞춤 전략 projection 저장소를 사용할 수 없습니다.");
        }
        String appliedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                INSERT INTO card_news_strategy_context_projections (
                    card_news_id,
                    user_id,
                    applied_action,
                    is_applied,
                    applied_at,
                    reverted_at
                )
                VALUES (?, ?, CAST(? AS jsonb), TRUE, CAST(? AS timestamptz), NULL)
                ON CONFLICT (card_news_id, user_id)
                DO UPDATE SET
                    applied_action = EXCLUDED.applied_action,
                    is_applied = TRUE,
                    applied_at = EXCLUDED.applied_at,
                    reverted_at = NULL
                RETURNING card_news_id,
                          applied_action,
                          is_applied,
                          applied_at
                """,
                cardNewsId,
                userId,
                toJson(appliedAction),
                appliedAt
        );
        return new LinkedHashMap<>(row);
    }

    private void deactivateStrategyProjection(String cardNewsId, UUID userId) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("맞춤 전략 projection 저장소를 사용할 수 없습니다.");
        }
        jdbcTemplate.update(
                """
                UPDATE card_news_strategy_context_projections
                   SET is_applied = FALSE,
                       reverted_at = CAST(? AS timestamptz)
                 WHERE card_news_id = ?
                   AND user_id = ?
                """,
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                cardNewsId,
                userId
        );
    }

    private Map<String, Object> appliedActionFromImplication(Map<String, Object> implication) {
        Map<String, Object> frontend = responseImplication(implication);
        Map<String, Object> action = new LinkedHashMap<>();
        copyIfPresent(frontend, action, "suggested_actions");
        copyIfPresent(frontend, action, "response_directions");
        copyIfPresent(frontend, action, "skax_checkpoints");
        copyIfPresent(frontend, action, "response_direction_blocks");
        copyIfPresent(frontend, action, "suggested_action_items");
        copyIfPresent(frontend, action, "skax_checkpoint_blocks");

        Map<String, Object> frontendReady = objectMap(implication.get("frontend_ready"));
        Map<String, Object> suggestedAction = objectMap(frontendReady.get("suggested_action"));
        if (!suggestedAction.isEmpty()) {
            action.put("frontend_ready_suggested_action", suggestedAction);
        }

        Map<String, Object> industryReady = objectMap(implication.get("industry_frontend_ready"));
        List<Map<String, Object>> industryActions = industryReadyActions(industryReady);
        if (!industryActions.isEmpty()) {
            action.put("industry_frontend_ready_actions", industryActions);
        }
        if (action.isEmpty()) {
            throw new IllegalStateException("맞춤 전략 대응방안 결과가 비어 있습니다.");
        }
        return action;
    }

    private List<Map<String, Object>> industryReadyActions(Map<String, Object> industryReady) {
        List<Map<String, Object>> actions = new ArrayList<>();
        List<Map<String, Object>> items = mapList(industryReady.get("items"));
        for (Map<String, Object> item : items) {
            Map<String, Object> suggestedAction = objectMap(item.get("suggested_action"));
            if (!suggestedAction.isEmpty()) {
                actions.add(suggestedAction);
            }
        }
        return actions;
    }

    private Map<String, Object> implicationWithAppliedAction(
            Map<String, Object> baseImplication,
            Map<String, Object> appliedAction
    ) {
        Map<String, Object> merged = deepCopyMap(baseImplication);
        if (appliedAction.isEmpty()) {
            return merged;
        }
        Map<String, Object> frontend = new LinkedHashMap<>(nestedMap(merged, "frontend"));
        if (frontend.isEmpty()) {
            frontend.putAll(merged);
        }
        copyIfPresent(appliedAction, frontend, "suggested_actions");
        copyIfPresent(appliedAction, frontend, "response_directions");
        copyIfPresent(appliedAction, frontend, "skax_checkpoints");
        copyIfPresent(appliedAction, frontend, "response_direction_blocks");
        copyIfPresent(appliedAction, frontend, "suggested_action_items");
        copyIfPresent(appliedAction, frontend, "skax_checkpoint_blocks");
        merged.put("frontend", frontend);

        Map<String, Object> frontendReadyAction = objectMap(
                appliedAction.get("frontend_ready_suggested_action")
        );
        if (!frontendReadyAction.isEmpty()) {
            Map<String, Object> frontendReady = new LinkedHashMap<>(nestedMap(merged, "frontend_ready"));
            frontendReady.put("suggested_action", frontendReadyAction);
            merged.put("frontend_ready", frontendReady);
        }

        List<Map<String, Object>> industryActions = mapList(
                appliedAction.get("industry_frontend_ready_actions")
        );
        if (!industryActions.isEmpty()) {
            Map<String, Object> industryReady = new LinkedHashMap<>(
                    nestedMap(merged, "industry_frontend_ready")
            );
            List<Map<String, Object>> items = new ArrayList<>(mapList(industryReady.get("items")));
            for (int index = 0; index < items.size() && index < industryActions.size(); index++) {
                Map<String, Object> item = new LinkedHashMap<>(items.get(index));
                item.put("suggested_action", industryActions.get(index));
                items.set(index, item);
            }
            industryReady.put("items", items);
            merged.put("industry_frontend_ready", industryReady);
        }
        return merged;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 변환 실패", e);
        }
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
        LocalDateTime earliestPublishedAt = earliestSourcePublishedAt(card, rawArticleById);
        return earliestPublishedAt == null ? card.getCreatedAt() : earliestPublishedAt;
    }

    private LocalDateTime earliestSourcePublishedAt(CardNews card, Map<Long, RawArticle> rawArticleById) {
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

        return candidates.stream().min(LocalDateTime::compareTo).orElse(null);
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
        if ("industry_trend".equals(card.getPeerId())) {
            return "industry_trend";
        }
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
        if (source == null) {
            return Map.of();
        }
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

    private Map<String, Object> deepCopyMap(Object value) {
        Map<String, Object> source = objectMap(value);
        if (source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(source, MAP_TYPE);
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

    private String publishedAt(LocalDateTime basisAt) {
        if (basisAt == null) {
            return null;
        }
        return basisAt.atZone(DISPLAY_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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
        return value.toLocalDate().toString();
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

    private String displayCategoryLabel(CardNews card, String sector) {
        return categoryLabel(sector, card.getPrimaryKeywordCategory());
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
            case "industry", "industry_trend" -> "industry";
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
