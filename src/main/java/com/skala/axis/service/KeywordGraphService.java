package com.skala.axis.service;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.repository.CardNewsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.skala.axis.query.KeywordGraphQueries.CARD_NEWS_IDS_BY_RAW_COMPANY_SQL;
import static com.skala.axis.query.KeywordGraphQueries.CARD_NEWS_IDS_BY_RAW_KEYWORD_SQL;
import static com.skala.axis.query.KeywordGraphQueries.KEYWORD_RELATIONS_SQL;
import static com.skala.axis.query.KeywordGraphQueries.rawArticleCardsSql;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KeywordGraphService {
    private static final int MAX_KEYWORD_NODES = 30;
    private static final int MAX_VISIBLE_KEYWORDS_PER_COMPANY = 10;
    private static final List<CompanyNode> COMPANIES = List.of(
            new CompanyNode("sk-axis", "SK AX", "sk_ax", 450, 280),
            new CompanyNode("samsung-sds", "삼성 SDS", "samsung_sds", 155, 118),
            new CompanyNode("lg-cns", "LG CNS", "lg_cns", 742, 124),
            new CompanyNode("hyundai-autoever", "현대 오토에버", "hyundai_autoever", 174, 446),
            new CompanyNode("posco-dx", "포스코 DX", "posco_dx", 744, 450)
    );

    private final JdbcTemplate jdbcTemplate;
    private final CardNewsRepository cardNewsRepository;
    private final CardNewsService cardNewsService;
    private final AtomicReference<CachedPayload> graphCache = new AtomicReference<>();
    private final ConcurrentMap<String, CachedPayload> cardsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> keywordLabelsByNodeId = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);

    @PostConstruct
    void initializeKeywordGraphCache() {
        graphCache.set(new CachedPayload(withCacheMetadata(buildGraph(List.of()), Instant.now(), "empty"), Instant.now()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmKeywordGraphCacheAfterStartup() {
        CompletableFuture.runAsync(this::refreshKeywordGraphCache);
    }

    @Scheduled(cron = "${axis.keyword-graph.refresh-cron:0 10 * * * *}", zone = "Asia/Seoul")
    public void refreshKeywordGraphCache() {
        if (!refreshRunning.compareAndSet(false, true)) {
            log.info("Keyword graph cache refresh already running. Skipping.");
            return;
        }
        Instant startedAt = Instant.now();
        try {
            List<KeywordRelation> relations = loadKeywordRelations();
            Map<String, Object> graph = withCacheMetadata(buildGraph(relations), startedAt, "scheduled");
            graphCache.set(new CachedPayload(graph, startedAt));
            refreshKeywordLabelCache(graph);
            cardsCache.clear();
            warmKeywordGraphCardsCache(graph);
            log.info("Keyword graph cache refreshed | nodes={} edges={} elapsed_ms={}",
                    sizeOfList(graph.get("nodes")),
                    sizeOfList(graph.get("edges")),
                    java.time.Duration.between(startedAt, Instant.now()).toMillis());
        } catch (RuntimeException error) {
            log.warn("Keyword graph cache refresh failed. Keeping previous cache.", error);
        } finally {
            refreshRunning.set(false);
        }
    }

    public Map<String, Object> keywordGraph() {
        CachedPayload cached = graphCache.get();
        return cached == null ? withCacheMetadata(buildGraph(List.of()), Instant.now(), "empty") : cached.payload();
    }

    public Map<String, Object> keywordGraphCards(String nodeId, Map<String, String> params) {
        int limit = intValue(params, "limit", 6);
        int offset = intValue(params, "offset", 0);
        CachedPayload cached = cardsCache.computeIfAbsent(nodeId, ignored ->
                new CachedPayload(buildKeywordGraphCards(nodeId), Instant.now())
        );
        return paginateCachedCards(cached.payload(), limit, offset);
    }

    private Map<String, Object> buildKeywordGraphCards(String nodeId) {
        try {
            CompanyNode company = companyByNodeId(nodeId);
            String keyword = company == null ? resolveKeyword(nodeId).orElse(nodeId) : company.label();
            List<CardNewsResponse> relatedCards = company == null
                    ? relatedCards(keyword)
                    : relatedCompanyCards(company);
            return Map.of(
                    "nodeId", nodeId,
                    "keyword", keyword,
                    "items", relatedCards,
                    "total", relatedCards.size(),
                    "cachedAt", Instant.now().toString()
            );
        } catch (RuntimeException error) {
            log.warn("Keyword graph card list failed for {}", nodeId, error);
            return Map.of(
                    "nodeId", nodeId,
                    "keyword", nodeId,
                    "items", List.of(),
                    "total", 0,
                    "cachedAt", Instant.now().toString()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paginateCachedCards(Map<String, Object> cached, int limit, int offset) {
        List<CardNewsResponse> relatedCards = (List<CardNewsResponse>) cached.getOrDefault("items", List.of());
        List<CardNewsResponse> items = relatedCards.stream()
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
        return Map.of(
                "nodeId", cached.getOrDefault("nodeId", ""),
                "keyword", cached.getOrDefault("keyword", ""),
                "items", items,
                "total", relatedCards.size(),
                "limit", limit,
                "offset", offset,
                "cachedAt", cached.getOrDefault("cachedAt", "")
        );
    }

    @SuppressWarnings("unchecked")
    private void warmKeywordGraphCardsCache(Map<String, Object> graph) {
        Object nodesValue = graph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return;
        }
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> node)) {
                continue;
            }
            Object id = node.get("id");
            if (id instanceof String nodeId) {
                cardsCache.put(nodeId, new CachedPayload(buildKeywordGraphCards(nodeId), Instant.now()));
            }
        }
    }

    private Map<String, Object> withCacheMetadata(Map<String, Object> payload, Instant cachedAt, String refreshMode) {
        Map<String, Object> response = new LinkedHashMap<>(payload);
        response.put("cachedAt", cachedAt.toString());
        response.put("refreshMode", refreshMode);
        return response;
    }

    private int sizeOfList(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private List<CardNewsResponse> relatedCompanyCards(CompanyNode company) {
        Map<String, CardNewsResponse> responsesById = new LinkedHashMap<>();

        try {
            appendCardResponses(responsesById, cardsByIds(cardNewsIdsByRawCompany(company.peerId())));
        } catch (RuntimeException error) {
            log.warn("Keyword graph company card lookup by raw article failed for {}", company.peerId(), error);
        }

        List<CardNews> activeCards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
        appendCardResponses(
                responsesById,
                activeCards.stream()
                        .filter(card -> cardMatchesCompany(card, company))
                        .toList()
        );
        rawArticleCards(company, 30).forEach(response -> responsesById.putIfAbsent(response.getId(), response));

        if (responsesById.isEmpty()) {
            List<KeywordRelation> companyKeywords = loadKeywordRelations().stream()
                    .filter(relation -> company.peerId().equals(relation.companyId()))
                    .sorted(Comparator.comparingInt(KeywordRelation::weight).reversed())
                    .limit(5)
                    .toList();
            for (KeywordRelation relation : companyKeywords) {
                relatedCards(relation.keyword()).forEach(response -> responsesById.putIfAbsent(response.getId(), response));
            }
        }

        return new ArrayList<>(responsesById.values());
    }

    private Map<String, Object> buildGraph(List<KeywordRelation> relations) {
        Map<String, KeywordAggregate> keywords = new LinkedHashMap<>();
        Map<String, Integer> companyMentionCounts = COMPANIES.stream()
                .collect(Collectors.toMap(CompanyNode::peerId, ignored -> 0, Integer::sum, LinkedHashMap::new));

        for (KeywordRelation relation : relations) {
            KeywordAggregate aggregate = keywords.computeIfAbsent(
                    relation.keywordKey(),
                    ignored -> new KeywordAggregate(relation.keyword(), relation.keywordKey())
            );
            aggregate.add(relation.companyId(), relation.weight());
            companyMentionCounts.computeIfPresent(relation.companyId(), (ignored, count) -> count + relation.weight());
        }

        List<KeywordAggregate> rankedKeywords = keywords.values().stream()
                .sorted(Comparator.comparingInt(KeywordAggregate::totalWeight).reversed())
                .limit(MAX_KEYWORD_NODES)
                .toList();

        List<Map<String, Object>> nodes = new ArrayList<>();
        int maxCompanyMentions = companyMentionCounts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        for (CompanyNode company : COMPANIES) {
            int mentions = companyMentionCounts.getOrDefault(company.peerId(), 0);
            nodes.add(node(
                    company.id(),
                    company.label(),
                    "기업",
                    scaledSize(mentions, maxCompanyMentions, 34, company.id().equals("sk-axis") ? 46 : 40),
                    company.x(),
                    company.y(),
                    mentions,
                    "raw_articles"
            ));
        }

        int maxKeywordWeight = rankedKeywords.stream().mapToInt(KeywordAggregate::totalWeight).max().orElse(1);
        Map<String, Integer> companyKeywordRanks = companyKeywordRanks(rankedKeywords);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int index = 0; index < rankedKeywords.size(); index++) {
            KeywordAggregate keyword = rankedKeywords.get(index);
            String keywordNodeId = keywordNodeId(keyword.keywordKey());
            int[] position = keywordPosition(index, rankedKeywords.size());
            nodes.add(node(
                    keywordNodeId,
                    keyword.keyword(),
                    categoryOf(keyword.keyword()),
                    scaledSize(keyword.totalWeight(), maxKeywordWeight, 18, 32),
                    position[0],
                    position[1],
                    keyword.totalWeight(),
                    "raw_articles"
            ));

            keyword.companyWeights().forEach((companyId, weight) -> {
                CompanyNode company = companyByPeerId(companyId);
                if (company == null || !isVisibleCompanyKeywordRelation(keyword.keywordKey(), companyId, companyKeywordRanks)) {
                    return;
                }
                edges.add(Map.of(
                        "source", company.id(),
                        "target", keywordNodeId,
                        "weight", Math.max(2, Math.min(7, weight + 1)),
                        "relationType", "기사 " + weight + "건"
                ));
            });
        }

        return Map.of(
                "selectedId", "sk-axis",
                "nodes", nodes,
                "edges", edges,
                "trendData", List.of()
        );
    }

    private Map<String, Integer> companyKeywordRanks(List<KeywordAggregate> keywords) {
        Map<String, List<CompanyKeywordWeight>> byCompany = new LinkedHashMap<>();
        for (KeywordAggregate keyword : keywords) {
            keyword.companyWeights().forEach((companyId, weight) ->
                    byCompany.computeIfAbsent(companyId, ignored -> new ArrayList<>())
                            .add(new CompanyKeywordWeight(keyword.keywordKey(), weight))
            );
        }

        Map<String, Integer> ranks = new LinkedHashMap<>();
        byCompany.forEach((companyId, weights) -> {
            weights.sort(Comparator.comparingInt(CompanyKeywordWeight::weight).reversed());
            for (int index = 0; index < weights.size(); index++) {
                ranks.put(companyId + "|" + weights.get(index).keywordKey(), index + 1);
            }
        });
        return ranks;
    }

    private boolean isVisibleCompanyKeywordRelation(String keywordKey, String companyId, Map<String, Integer> companyKeywordRanks) {
        int rank = companyKeywordRanks.getOrDefault(companyId + "|" + keywordKey, Integer.MAX_VALUE);
        return rank <= MAX_VISIBLE_KEYWORDS_PER_COMPANY;
    }

    private List<KeywordRelation> loadKeywordRelations() {
        return jdbcTemplate.query(KEYWORD_RELATIONS_SQL, (rs, rowNum) -> new KeywordRelation(
                rs.getString("company_id"),
                rs.getString("keyword_key"),
                rs.getString("keyword"),
                rs.getInt("weight")
        ));
    }

    private List<CardNewsResponse> relatedCards(String keyword) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }
        try {
            List<String> cardIds = cardNewsIdsByRawKeyword(normalizedKeyword);
            if (!cardIds.isEmpty()) {
                Map<String, CardNews> cardsById = cardNewsRepository.findAllById(cardIds).stream()
                        .collect(Collectors.toMap(CardNews::getId, card -> card, (left, right) -> left, LinkedHashMap::new));
                return cardIds.stream()
                        .map(cardsById::get)
                        .filter(card -> card != null && card.getStatusOrDefault() == CardNewsStatus.ACTIVE)
                        .map(this::safeCardNewsResponse)
                        .flatMap(Optional::stream)
                        .toList();
            }
        } catch (RuntimeException error) {
            log.warn("Keyword graph card lookup by raw article failed for {}", keyword, error);
        }
        List<CardNews> cards = cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE);
        return cards.stream()
                .filter(card -> cardMatches(card, normalizedKeyword))
                .map(this::safeCardNewsResponse)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<CardNewsResponse> safeCardNewsResponse(CardNews card) {
        try {
            return Optional.of(cardNewsService.toResponse(card));
        } catch (RuntimeException error) {
            log.warn("Skipping card_news {} in keyword graph card list", card.getId(), error);
            return Optional.empty();
        }
    }

    private List<String> cardNewsIdsByRawKeyword(String normalizedKeyword) {
        return jdbcTemplate.query(CARD_NEWS_IDS_BY_RAW_KEYWORD_SQL, (rs, rowNum) -> rs.getString("id"), normalizedKeyword);
    }

    private List<String> cardNewsIdsByRawCompany(String companyId) {
        return jdbcTemplate.query(CARD_NEWS_IDS_BY_RAW_COMPANY_SQL, (rs, rowNum) -> rs.getString("id"), companyId);
    }

    private List<CardNews> cardsByIds(List<String> cardIds) {
        if (cardIds.isEmpty()) {
            return List.of();
        }
        Map<String, CardNews> cardsById = cardNewsRepository.findAllById(cardIds).stream()
                .collect(Collectors.toMap(CardNews::getId, card -> card, (left, right) -> left, LinkedHashMap::new));
        return cardIds.stream()
                .map(cardsById::get)
                .filter(card -> card != null && card.getStatusOrDefault() == CardNewsStatus.ACTIVE)
                .toList();
    }

    private void appendCardResponses(Map<String, CardNewsResponse> responsesById, List<CardNews> cards) {
        cards.stream()
                .map(this::safeCardNewsResponse)
                .flatMap(Optional::stream)
                .forEach(response -> responsesById.putIfAbsent(response.getId(), response));
    }

    private List<CardNewsResponse> rawArticleCards(CompanyNode company, int limit) {
        List<String> aliases = companyAliases(company);
        String whereSql = aliases.stream()
                .map(ignored -> "company @> CAST(? AS jsonb)")
                .collect(Collectors.joining(" OR "));
        List<Object> args = aliases.stream()
                .map(alias -> "[\"" + alias.replace("\"", "\\\"") + "\"]")
                .collect(Collectors.toCollection(ArrayList::new));
        args.add(Math.max(1, limit));

        String sql = rawArticleCardsSql(whereSql);
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String title = rs.getString("title");
            String url = rs.getString("url");
            String publishedDate = dateOnly(rs.getString("published_at"), rs.getString("created_at"));
            String sourceName = Optional.ofNullable(rs.getString("publisher"))
                    .filter(value -> !value.isBlank())
                    .orElse("원문 기사");
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", 1);
            source.put("title", title);
            source.put("url", url);
            source.put("source_name", sourceName);
            if (publishedDate != null) {
                source.put("published_at", publishedDate);
            }
            List<String> summary = List.of(title == null || title.isBlank() ? company.label() + " 관련 뉴스" : title);
            return CardNewsResponse.builder()
                    .id("raw-" + rs.getLong("id"))
                    .peerId(company.peerId())
                    .title(title)
                    .category("기업 뉴스")
                    .date(publishedDate == null ? "" : publishedDate.replace("-", "."))
                    .categoryLabel("기업")
                    .primaryKeywordCategory("기업")
                    .keywords(List.of(company.label()))
                    .publishedDate(publishedDate)
                    .summaryLines(summary)
                    .summary(summary)
                    .source(sourceName)
                    .sourceUrl(url)
                    .coverImageAlt((title == null || title.isBlank() ? company.label() : title) + " 대표 이미지")
                    .detailDescription(company.label() + " 관련 원문 기사입니다.")
                    .detailPoints(List.of())
                    .insights(List.of())
                    .actionItems(List.of())
                    .implication(Map.of())
                    .sources(List.of(source))
                    .sourceCount(1)
                    .validationPass(true)
                    .isHumanReviewed(false)
                    .build();
        }, args.toArray());
    }

    private boolean cardMatches(CardNews card, String normalizedKeyword) {
        return contains(card.getTitle(), normalizedKeyword)
                || contains(card.getPrimaryKeywordCategory(), normalizedKeyword)
                || contains(card.getEventType(), normalizedKeyword)
                || Arrays.stream(Optional.ofNullable(card.getKeywords()).orElse(new String[0]))
                .anyMatch(value -> contains(value, normalizedKeyword))
                || Optional.ofNullable(card.getKeywordFrequency()).orElse(Map.of()).keySet().stream()
                .anyMatch(value -> contains(value, normalizedKeyword));
    }

    private boolean cardMatchesCompany(CardNews card, CompanyNode company) {
        if (company.peerId().equals(card.getPeerCompanyId()) || company.peerId().equals(card.getPeerId())) {
            return true;
        }
        return companyAliases(company).stream().anyMatch(alias ->
                contains(card.getTitle(), alias)
                        || contains(card.getPrimaryKeywordCategory(), alias)
                        || contains(card.getEventType(), alias)
                        || Arrays.stream(Optional.ofNullable(card.getKeywords()).orElse(new String[0]))
                        .anyMatch(value -> contains(value, alias))
                        || Optional.ofNullable(card.getKeywordFrequency()).orElse(Map.of()).keySet().stream()
                        .anyMatch(value -> contains(value, alias))
        );
    }

    private List<String> companyAliases(CompanyNode company) {
        return switch (company.peerId()) {
            case "sk_ax" -> List.of("sk_ax", "SK AX", "skaxis", "skax", "sk에이엑스", "에스케이에이엑스", "skc&c", "sk㈜c&c", "sk주식회사c&c", "에스케이씨앤씨");
            case "samsung_sds" -> List.of("samsung_sds", "삼성SDS", "Samsung SDS", "samsungsds", "삼성sds", "삼성에스디에스");
            case "lg_cns" -> List.of("lg_cns", "LG CNS", "lgcns", "엘지씨엔에스");
            case "hyundai_autoever" -> List.of("hyundai_autoever", "현대오토에버", "Hyundai AutoEver", "hyundaiautoever");
            case "posco_dx" -> List.of("posco_dx", "포스코DX", "POSCO DX", "poscodx", "포스코dx", "포스코디엑스", "포스코ict", "poscoict");
            default -> List.of(company.peerId(), company.label());
        };
    }

    private String dateOnly(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.length() >= 10 ? value.substring(0, 10) : value)
                .findFirst()
                .orElse(null);
    }

    private Optional<String> resolveKeyword(String nodeId) {
        String cachedKeyword = keywordLabelsByNodeId.get(nodeId);
        if (cachedKeyword != null && !cachedKeyword.isBlank()) {
            return Optional.of(cachedKeyword);
        }
        try {
            return loadKeywordRelations().stream()
                    .filter(relation -> keywordNodeId(relation.keywordKey()).equals(nodeId))
                    .map(KeywordRelation::keyword)
                    .findFirst();
        } catch (RuntimeException error) {
            log.warn("Keyword node lookup failed for {}", nodeId, error);
            return Optional.empty();
        }
    }

    private void refreshKeywordLabelCache(Map<String, Object> graph) {
        keywordLabelsByNodeId.clear();
        Object nodesValue = graph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return;
        }
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> node)) {
                continue;
            }
            Object id = node.get("id");
            Object label = node.get("label");
            Object category = node.get("category");
            if (id instanceof String nodeId && label instanceof String keyword && !"기업".equals(category)) {
                keywordLabelsByNodeId.put(nodeId, keyword);
            }
        }
    }

    private Map<String, Object> node(String id, String label, String category, int size, int x, int y, int score, String sourceType) {
        return Map.of(
                "id", id,
                "label", label,
                "category", category,
                "size", size,
                "score", score,
                "changeRate", 0,
                "sourceType", sourceType,
                "x", x,
                "y", y
        );
    }

    private int scaledSize(int value, int max, int min, int maxSize) {
        if (max <= 0 || value <= 0) {
            return min;
        }
        double ratio = Math.sqrt((double) value / (double) max);
        return (int) Math.round(min + (maxSize - min) * ratio);
    }

    private int[] keywordPosition(int index, int total) {
        double angle = (Math.PI * 2 * index) / Math.max(1, total);
        double radius = 180 + (index % 3) * 46;
        return new int[] {
                (int) Math.round(450 + Math.cos(angle) * radius),
                (int) Math.round(280 + Math.sin(angle) * radius)
        };
    }

    private String keywordNodeId(String keywordKey) {
        return "kw-" + Integer.toUnsignedString(keywordKey.hashCode(), 36);
    }

    private String categoryOf(String keyword) {
        String normalized = normalize(keyword);
        if (containsAny(normalized, "보안", "security", "xdr", "제로트러스트", "zero trust")) {
            return "보안";
        }
        if (containsAny(normalized, "클라우드", "cloud", "데이터센터", "gpu", "인프라", "kubernetes")) {
            return "인프라";
        }
        if (containsAny(normalized, "수주", "계약", "deal", "공급", "우선협상", "프로젝트")) {
            return "수주";
        }
        return "AX";
    }

    private boolean contains(String value, String normalizedNeedle) {
        return value != null && normalize(value).contains(normalizedNeedle);
    }

    private boolean containsAny(String normalizedValue, String... needles) {
        return Arrays.stream(needles).anyMatch(needle -> normalizedValue.contains(normalize(needle)));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private CompanyNode companyByPeerId(String peerId) {
        return COMPANIES.stream()
                .filter(company -> company.peerId().equals(peerId))
                .findFirst()
                .orElse(null);
    }

    private CompanyNode companyByNodeId(String nodeId) {
        return COMPANIES.stream()
                .filter(company -> company.id().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    private int intValue(Map<String, String> params, String key, int defaultValue) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record CompanyNode(String id, String label, String peerId, int x, int y) {
    }

    private record KeywordRelation(String companyId, String keywordKey, String keyword, int weight) {
    }

    private record CompanyKeywordWeight(String keywordKey, int weight) {
    }

    private record CachedPayload(Map<String, Object> payload, Instant refreshedAt) {
    }

    private static final class KeywordAggregate {
        private final String keyword;
        private final String keywordKey;
        private final Map<String, Integer> companyWeights = new LinkedHashMap<>();

        private KeywordAggregate(String keyword, String keywordKey) {
            this.keyword = keyword;
            this.keywordKey = keywordKey;
        }

        private void add(String companyId, int weight) {
            companyWeights.merge(companyId, weight, Integer::sum);
        }

        private String keyword() {
            return keyword;
        }

        private String keywordKey() {
            return keywordKey;
        }

        private Map<String, Integer> companyWeights() {
            return companyWeights;
        }

        private int totalWeight() {
            return companyWeights.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
