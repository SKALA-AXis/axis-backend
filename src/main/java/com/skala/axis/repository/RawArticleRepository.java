package com.skala.axis.repository;

import com.skala.axis.domain.RawArticle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawArticleRepository extends JpaRepository<RawArticle, Long> {
}
