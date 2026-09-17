package com.chameleon.blend.core.img

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Runs disjoint row bands of a per-pixel pass on a small worker pool.
 *
 * The engine never nests these calls, so a single shared pool is enough and keeps the allocation
 * churn per frame at zero.
 */
object ParallelRows {

    private val workerCount: Int = max(1, min(8, Runtime.getRuntime().availableProcessors() - 1))

    private val pool = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "blend-worker").apply { isDaemon = true }
    }

    fun forEach(height: Int, block: (Int) -> Unit) {
        if (workerCount <= 1 || height < 48) {
            for (y in 0 until height) block(y)
            return
        }
        val latch = CountDownLatch(workerCount)
        val failure = AtomicReference<Throwable?>(null)
        val chunk = (height + workerCount - 1) / workerCount
        var scheduled = 0
        for (worker in 0 until workerCount) {
            val start = worker * chunk
            val end = min(height, start + chunk)
            if (start >= end) {
                latch.countDown()
                continue
            }
            scheduled++
            pool.execute {
                try {
                    for (y in start until end) {
                        block(y)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }
        if (scheduled == 0) return
        latch.await()
        failure.get()?.let { throw it }
    }
}
