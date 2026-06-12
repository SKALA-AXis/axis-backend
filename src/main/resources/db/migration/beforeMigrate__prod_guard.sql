-- beforeMigrate 가드: 운영 DB 에 배포 경로 밖 flyway migrate 를 차단한다.
--
-- 배경: 2026-06-11/12 — 로컬 bootRun(스키마 실험 override)이 port-forward 된
-- 운영 DB 에 미커밋 V44 초안을 적용 → 다음 배포가 체크섬 충돌로 CrashLoop.
-- 프로필 가드(application-local.yml flyway.enabled=false)는 localhost:5432 가
-- "어느 DB인지"를 검증하지 못한다 — 그래서 DB 쪽에 마커를 두고 여기서 거부한다.
--
-- 작동 조건:
-- - 운영 DB 1회 설정:  ALTER DATABASE axis SET axis.environment = 'prod';
-- - 클러스터(prod 프로필)만 placeholder axis_migrate_source=cluster 를 가진다
--   (application-prod.yml). 로컬/기본값은 'local'.
-- - 마커 미설정 DB(로컬 docker 등)에서는 current_setting 이 NULL → 통과.

DO $$
BEGIN
    IF current_setting('axis.environment', true) = 'prod'
        AND '${axis_migrate_source}' <> 'cluster' THEN
        RAISE EXCEPTION USING
            MESSAGE = 'axis 운영 DB 에 배포 경로 밖 flyway migrate 시도가 차단됨 '
                      '(axis_migrate_source=${axis_migrate_source}). '
                      '스키마 변경은 develop 머지 → 클러스터 배포 경로로만 적용하세요. '
                      '로컬 스키마 실험은 docker postgres 를 사용하고, port-forward 가 '
                      '5432 를 점유 중인지 먼저 확인하세요.';
    END IF;
END
$$;
