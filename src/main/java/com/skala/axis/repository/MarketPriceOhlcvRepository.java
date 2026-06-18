/*
 * 작성일: 2026-05-22
 * 작성자: 안가은
 * 변경이력:
 *   2026-05-22 안가은 — 홈화면 그래프 데이터 연동을 위한 시세 OHLCV 리포지토리 추가
 */
package com.skala.axis.repository;

import com.skala.axis.domain.MarketPriceOhlcv;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

public interface MarketPriceOhlcvRepository extends JpaRepository<MarketPriceOhlcv, Long> {
    @Query(value = """
            SELECT DISTINCT trade_date
            FROM market_price_ohlcv
            WHERE trade_date <= :today
            ORDER BY trade_date DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Date> findRecentTradeDates(@Param("today") LocalDate today, @Param("limit") int limit);

    List<MarketPriceOhlcv> findByPeerIdInAndTradeDateInOrderByTradeDateAscPeerIdAsc(
            List<String> peerIds,
            List<LocalDate> tradeDates
    );
}
