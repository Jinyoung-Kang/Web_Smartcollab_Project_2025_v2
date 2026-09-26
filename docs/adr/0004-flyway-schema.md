# ADR-0004 Flyway 로 스키마 관리

## 배경
v1 은 운영에서도 `spring.jpa.hibernate.ddl-auto: update` 로 엔티티를 보고 스키마를 자동 변경했습니다. 컬럼 삭제·이름 변경이 반영되지 않고, 어떤 변경이 언제 적용됐는지 기록이 없으며, 인덱스·FK 동작(`ON DELETE SET NULL`)을 의도대로 지정하기 어렵습니다.

## 결정
- `db/migration/V1__init_schema.sql` 로 스키마를 명시 (인덱스, 유니크 제약, FK 동작, `utf8mb4_0900_ai_ci`)
- Hibernate 는 `ddl-auto: validate` — 엔티티와 스키마가 어긋나면 기동 실패
- 시각은 `DATETIME(6)` UTC + `Instant` (`hibernate.jdbc.time_zone=UTC`)

## 결과
- 모든 통합 테스트가 운영과 같은 마이그레이션으로 만든 MySQL 에서 실행
- 이후 변경은 `V2__…sql` 처럼 누적 기록
