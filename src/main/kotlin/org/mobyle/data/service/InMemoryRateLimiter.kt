package org.mobyle.data.service

import org.mobyle.domain.repository.RateLimiter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window limiter kept in memory. Enough for a single instance; counts are lost on
 * restart, which only resets the windows.
 */
class InMemoryRateLimiter(private val now: () -> Long = System::currentTimeMillis) : RateLimiter {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    override fun tryAcquire(key: String, limit: Int, windowMs: Long): Boolean {
        val queue = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            val time = now()
            while (queue.isNotEmpty() && queue.peekFirst() <= time - windowMs) queue.pollFirst()
            if (queue.size >= limit) return false
            queue.addLast(time)
            return true
        }
    }
}
