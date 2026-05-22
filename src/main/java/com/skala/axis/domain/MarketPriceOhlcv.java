package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "market_price_ohlcv")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPriceOhlcv {
    @Id
    private Long id;

    @Column(name = "peer_id", nullable = false, length = 50)
    private String peerId;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "close")
    private BigDecimal close;
}
