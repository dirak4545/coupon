-- 쿠폰 MVP 스키마 (MySQL 8.4)
--
-- 런타임 스키마는 Hibernate ddl-auto 가 관리한다 (application.yaml).
-- 이 파일은 리뷰/온보딩용 기준 DDL 이며, 엔티티(Coupon, Issuance)와 1:1 로 맞춘다.

CREATE TABLE coupon (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(80)  NOT NULL,
    total_quantity    INT          NOT NULL,
    issued_quantity   INT          NOT NULL DEFAULT 0,
    validity_days     INT          NOT NULL DEFAULT 7,
    starts_at         DATETIME     NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE issuance (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    coupon_id       BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ISSUED',
    issued_at       DATETIME     NOT NULL,
    expires_at      DATETIME     NOT NULL,
    used_at         DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_issuance_user_coupon (user_id, coupon_id),
    KEY idx_issuance_status (status),
    KEY idx_issuance_coupon (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
