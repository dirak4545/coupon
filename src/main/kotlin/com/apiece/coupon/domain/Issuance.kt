package com.apiece.coupon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Index

@Entity
@Table(
    name = "issuance",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_coupon_user",
            columnNames = ["user_id", "coupon_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_issuance_status", columnList = "status"),
        Index(name = "ids_issuance_coupon", columnList = "coupon_id"),
        ],
    )
class Issuance (
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "coupon_id", nullable = false)
    var couponId: Long,

    @Column(name = "issued_at", nullable = false, updatable = false)
    var issuedAt: LocalDateTime,

    @Column(name = "expired_at", nullable = false)
    var expiresAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: IssuanceStatus = IssuanceStatus.ISSUED,

    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
) {
    fun isExpired(now: LocalDateTime): Boolean = !now.isBefore(expiresAt)

    fun markUsed(now: LocalDateTime) {
        status = IssuanceStatus.USED
        usedAt = now
    }

    fun markExpired(now: LocalDateTime) {
        status = IssuanceStatus.EXPIRED
    }
}