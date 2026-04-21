package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "peer_companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PeerCompany {
    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
