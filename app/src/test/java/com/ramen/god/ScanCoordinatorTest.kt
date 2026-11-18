package com.ramen.god

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoordinatorTest {
    @Test
    fun allowsOnlyOneActiveScan() {
        val coordinator = ScanCoordinator()

        val first = coordinator.begin()

        assertNotNull(first)
        assertNull(coordinator.begin())
    }

    @Test
    fun cancellationIsScopedToTheCurrentJob() {
        val coordinator = ScanCoordinator()
        val first = coordinator.begin()!!

        assertTrue(coordinator.cancel(first))
        assertTrue(first.isCancelled)
        assertFalse(coordinator.cancel(first))
    }

    @Test
    fun cancelActiveCancelsOnlyTheCurrentJob() {
        val coordinator = ScanCoordinator()
        val first = coordinator.begin()!!

        assertTrue(coordinator.cancelActive())
        assertTrue(first.isCancelled)
        coordinator.complete(first)

        val second = coordinator.begin()!!
        assertFalse(second.isCancelled)
    }

    @Test
    fun completingOldJobCannotClearNewJob() {
        val coordinator = ScanCoordinator()
        val first = coordinator.begin()!!
        coordinator.complete(first)
        val second = coordinator.begin()!!

        coordinator.complete(first)

        assertTrue(coordinator.isCurrent(second))
        assertNull(coordinator.begin())
    }

    @Test
    fun completionIsIdempotent() {
        val coordinator = ScanCoordinator()
        val job = coordinator.begin()!!

        coordinator.complete(job)
        coordinator.complete(job)

        assertFalse(coordinator.isCurrent(job))
        assertNotNull(coordinator.begin())
    }

    @Test
    fun cancelActiveReturnsFalseWhenIdle() {
        assertFalse(ScanCoordinator().cancelActive())
    }
}
