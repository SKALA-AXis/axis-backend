package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "user_card_news_bookmarks",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_card_news_bookmark", columnNames = {"user_id", "card_news_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCardNewsBookmark {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "card_news_id", nullable = false, length = 50)
    private String cardNewsId;

    @Column
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static UserCardNewsBookmark create(User user, String cardNewsId, String note) {
        UserCardNewsBookmark bookmark = new UserCardNewsBookmark();
        bookmark.user = user;
        bookmark.cardNewsId = cardNewsId;
        bookmark.note = note;
        bookmark.createdAt = Instant.now();
        return bookmark;
    }
}
