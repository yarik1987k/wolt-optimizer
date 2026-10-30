package dev.yk.offercollector

import dev.yk.offercollector.core.NotificationDeduplicator
import dev.yk.offercollector.core.NotificationEventType.POSTED
import dev.yk.offercollector.core.NotificationEventType.REMOVED
import dev.yk.offercollector.core.NotificationEventType.UPDATED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationDeduplicatorTest {
    @Test fun updatesAreNotNewNotifications() {
        val d = NotificationDeduplicator()
        assertEquals(POSTED, d.onPosted("k1", "sigA", 0))
        assertEquals(UPDATED, d.onPosted("k1", "sigB", 500))
        assertEquals(UPDATED, d.onPosted("k1", "sigC", 900))
    }

    @Test fun exactRepeatsWithinWindowAreDropped() {
        val d = NotificationDeduplicator(repeatWindowMs = 1500)
        assertEquals(POSTED, d.onPosted("k1", "sig", 0))
        assertNull(d.onPosted("k1", "sig", 200))
        assertEquals(UPDATED, d.onPosted("k1", "sig", 5000)) // same content much later = a re-post update
    }

    @Test fun sameKeyAfterRemovalIsANewNotification() {
        val d = NotificationDeduplicator()
        assertEquals(POSTED, d.onPosted("k1", "sig", 0))
        assertEquals(REMOVED, d.onRemoved("k1", 3000))
        assertEquals(POSTED, d.onPosted("k1", "sig", 60_000))
    }

    @Test fun duplicateRemovalFromASecondListenerInstanceIsDropped() {
        val d = NotificationDeduplicator(repeatWindowMs = 1500)
        assertEquals(POSTED, d.onPosted("k1", "s", 0))
        assertEquals(REMOVED, d.onRemoved("k1", 1000))
        assertNull(d.onRemoved("k1", 1010)) // same removal delivered twice
        assertEquals(REMOVED, d.onRemoved("k2", 1020)) // unknown key: still reported once
    }

    @Test fun differentKeysAreIndependent() {
        val d = NotificationDeduplicator()
        assertEquals(POSTED, d.onPosted("k1", "s", 0))
        assertEquals(POSTED, d.onPosted("k2", "s", 10))
    }

    @Test fun seededStateSurvivesServiceRestart() {
        val d = NotificationDeduplicator()
        d.seed("k1", active = true, signature = "sig", atMs = 0)
        assertEquals(UPDATED, d.onPosted("k1", "sig2", 100))
        val d2 = NotificationDeduplicator()
        d2.seed("k1", active = false, signature = "sig", atMs = 0)
        assertEquals(POSTED, d2.onPosted("k1", "sig", 100))
    }
}
