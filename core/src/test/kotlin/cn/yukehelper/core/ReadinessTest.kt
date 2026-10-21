package cn.yukehelper.core

import org.junit.Assert.*
import org.junit.Test

class ReadinessTest {
    @Test fun aFreshInstallIsMissingEverything() {
        val state = StartupReadiness()
        assertEquals(
            listOf(ReadinessRequirement.NOTIFICATIONS, ReadinessRequirement.NOTIFICATION_CHANNELS,
                ReadinessRequirement.BATTERY_UNRESTRICTED, ReadinessRequirement.BACKGROUND_ALLOWED),
            state.missing(),
        )
        assertFalse(state.ready)
    }

    @Test fun systemItemsAreBlockingButTheBrandSwitchNeedsOnlyConfirmation() {
        val state = StartupReadiness(notificationsGranted = true, batteryUnrestricted = true)
        assertEquals(listOf(ReadinessRequirement.BACKGROUND_ALLOWED), state.missing())
        assertTrue("nothing the system can verify is left", state.blocking().isEmpty())
        assertFalse("monitoring still waits for the manual confirmation", state.ready)
    }

    @Test fun eitherDisabledChannelBlocksTheSession() {
        val base = StartupReadiness(notificationsGranted = true, batteryUnrestricted = true, backgroundConfirmed = true)
        assertTrue(base.ready)
        assertEquals(listOf(ReadinessRequirement.NOTIFICATION_CHANNELS), base.copy(homeworkChannelEnabled = false).missing())
        assertEquals(listOf(ReadinessRequirement.NOTIFICATION_CHANNELS), base.copy(classroomChannelEnabled = false).missing())
    }

    @Test fun missingItemsAreStableAndDeduplicated() {
        // Both channels disabled must not produce two rows for the same requirement.
        val state = StartupReadiness(notificationsGranted = false, classroomChannelEnabled = false,
            homeworkChannelEnabled = false, batteryUnrestricted = false)
        assertEquals(listOf(ReadinessRequirement.NOTIFICATIONS, ReadinessRequirement.NOTIFICATION_CHANNELS,
            ReadinessRequirement.BATTERY_UNRESTRICTED, ReadinessRequirement.BACKGROUND_ALLOWED), state.missing())
        assertEquals(3, state.blocking().size)
        assertEquals(4, ReadinessRequirement.entries.size)
    }

    @Test fun fullyConfiguredDeviceIsReady() {
        val state = StartupReadiness(notificationsGranted = true, classroomChannelEnabled = true, homeworkChannelEnabled = true,
            batteryUnrestricted = true, backgroundConfirmed = true)
        assertTrue(state.ready)
        assertTrue(state.missing().isEmpty())
        assertTrue(state.blocking().isEmpty())
    }
}
