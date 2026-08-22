package com.apiece.coupon.application

import com.apiece.coupon.api.dto.CreateCouponRequest
import com.apiece.coupon.domain.Coupon
import com.apiece.coupon.domain.CouponRepository
import com.apiece.coupon.domain.Issuance
import com.apiece.coupon.domain.IssuanceRepository
import com.apiece.coupon.support.AlreadyIssuedException
import com.apiece.coupon.support.CouponNotFoundException
import com.apiece.coupon.support.NotStartedException
import com.apiece.coupon.support.SoldOutException
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class CouponService (
    private val couponRepository: CouponRepository,
    private val issuedRepository: IssuanceRepository,
) {
    @Transactional
    fun createCoupon(request: CreateCouponRequest): Coupon {
        val coupon = couponRepository.save(
            Coupon(
                name = request.name,
                totalQuantity = request.totalQuantity,
                validityDays = request.validityDays,
                startsAt = request.startsAt?.let { LocalDateTime.parse(it) },
            )
        )
        return coupon
    }

    @Transactional
    fun issue(couponId: Long, userId: Long): Issuance {
        val coupon = couponRepository.findById(couponId)
            .orElseThrow { CouponNotFoundException() }

        val now = LocalDateTime.now()
        if (!coupon.isBookingOpen(now)) {
            throw NotStartedException()
        }
        if (coupon.isSoldOut()) {
            throw SoldOutException()
        }
        if (issuedRepository.existsByUserIdAndCouponId(userId, couponId)) {
            throw AlreadyIssuedException()
        }

        val updated = couponRepository.incrementIssuedQuantity(couponId)
        if (updated == 0) {
            throw SoldOutException()
        }

        val issuance = Issuance(
            userId = userId,
            couponId = couponId,
            issuedAt = now,
            expiresAt = now.plusDays(coupon.validityDays.toLong()),
        )
        return issuedRepository.save(issuance)
    }
}
