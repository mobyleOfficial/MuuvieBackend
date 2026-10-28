package org.mobyle.domain.repository

/** Counts actions per key in a sliding window (B6: messages and follows). */
interface RateLimiter {
    /** True and counted when [key] is still under [limit] actions in the last [windowMs]. */
    fun tryAcquire(key: String, limit: Int, windowMs: Long): Boolean
}
