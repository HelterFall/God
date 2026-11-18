package com.ramen.god

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single active scan and keeps cancellation scoped to that scan.
 * All state transitions are synchronized; the cancellation flag is safe to read
 * from the worker thread.
 */
internal class ScanCoordinator {
    internal class ScanJob internal constructor() {
        private val cancelled = AtomicBoolean(false)
        val isCancelled: Boolean
            get() = cancelled.get()

        internal fun cancel(): Boolean = cancelled.compareAndSet(false, true)
    }

    private var activeJob: ScanJob? = null

    @Synchronized
    fun begin(): ScanJob? {
        if (activeJob != null) return null
        return ScanJob().also { activeJob = it }
    }

    @Synchronized
    fun cancel(job: ScanJob): Boolean {
        if (activeJob !== job) return false
        return job.cancel()
    }

    @Synchronized
    fun cancelActive(): Boolean {
        val job = activeJob ?: return false
        return job.cancel()
    }

    @Synchronized
    fun isCurrent(job: ScanJob): Boolean = activeJob === job

    @Synchronized
    fun complete(job: ScanJob) {
        if (activeJob === job) activeJob = null
    }
}
