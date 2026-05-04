package com.skala.axis.service;

import com.skala.axis.domain.ArticleImage;
import com.skala.axis.domain.IssueCard;
import com.skala.axis.dto.IssueCardResponse;
import com.skala.axis.repository.ArticleImageRepository;
import com.skala.axis.repository.IssueCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueCardService {
    private final IssueCardRepository issueCardRepository;
    private final ArticleImageRepository articleImageRepository;

    public List<IssueCardResponse> getTodayIssues(String peerId, String importance) {
        LocalDateTime since = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        List<IssueCard> cards = issueCardRepository.findTodayIssues(since);
        return cards.stream()
                .filter(c -> peerId == null || peerId.equals(c.getPeerId()))
                .filter(c -> importance == null || importance.equals(c.getImportance()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<IssueCardResponse> getAll(String peerId, String importance, String eventType) {
        return issueCardRepository.findAll().stream()
                .filter(c -> peerId == null || peerId.equals(c.getPeerId()))
                .filter(c -> importance == null || importance.equals(c.getImportance()))
                .filter(c -> eventType == null || eventType.equals(c.getEventType()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public IssueCardResponse getById(String id) {
        return issueCardRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("이슈 카드 없음: " + id));
    }

    private IssueCardResponse toResponse(IssueCard card) {
        Optional<ArticleImage> image = articleImageRepository
                .findFirstByIssueCardIdOrderByCreatedAtDesc(card.getId());

        return IssueCardResponse.builder()
                .id(card.getId())
                .peerId(card.getPeerId())
                .clusterId(card.getClusterId())
                .title(card.getTitle())
                .eventType(card.getEventType())
                .importance(card.getImportance())
                .importanceScore(card.getImportanceScore())
                .createdAt(card.getCreatedAt())
                .imageUrl(image.map(i -> "/api/images/" + i.getId()).orElse(null))
                .imageAttribution(image.map(ArticleImage::getAttribution).orElse(null))
                .imageAlt(image.map(ArticleImage::getAltText).orElse(null))
                .build();
    }
}
