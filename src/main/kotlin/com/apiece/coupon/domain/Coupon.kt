package com.apiece.coupon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "coupon")
class Coupon (
    //생성자 만들면 그냥 getter, setter 자동 생성
    @Column(nullable = false, length = 80)
    var name: String,

    @Column(name = "total_quantity", nullable = false)
    var totalQuantity: Int,

    @Column(name = "issued_quantity", nullable = false)
    var issuedQuantity: Int = 0,

    @Column(name = "validity_days", nullable = false)
    var validityDays: Int = 7,

    @Column(name = "starts_at")
    var startsAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    ) {
    fun isBookingOpen(now: LocalDateTime): Boolean =
        startsAt?.let { !now.isBefore(it) } ?: true
    //startsAt(시작 시간)이 현재 시간보다 전이 아니면 발급 가능이니까 true 반환, 없으면 true 반환
    fun isSoldOut(): Boolean = issuedQuantity >= totalQuantity
}