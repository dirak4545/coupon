package com.apiece.coupon.api.dto

class CreateCouponRequest (
    val name: String,
    val totalQuantity: Int = 5000,
    val validityDays: Int = 7,
    val startsAt: String? = null,
)