ALTER TABLE raw_articles
    DROP COLUMN IF EXISTS credibility_score,
    DROP COLUMN IF EXISTS credibility_grade;
