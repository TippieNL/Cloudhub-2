package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.MIN_PASSWORD
import nl.tippie.cloudhub.ui.StorageMeter
import nl.tippie.cloudhub.ui.ThemeChoice
import nl.tippie.cloudhub.ui.passwordProblem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What "how much space is left" means.
 *
 * Three things can be the real ceiling and only one applies at a time; picking
 * the wrong one tells someone they have room when they do not. Pure, so every
 * case runs with no server and no device.
 */
class StorageMeterTest {

    private val gb = 1024L * 1024 * 1024

    private fun reading(
        used: Long = 0,
        quota: Long = 0,
        storeUsed: Long = 0,
        storeLimit: Long = 0,
        diskFree: Long = 500 * gb,
        diskTotal: Long = 1000 * gb,
    ) = StorageMeter.of(used, quota, storeUsed, storeLimit, diskFree, diskTotal)

    @Test
    fun `a personal quota outranks everything else`() {
        val r = reading(used = 2 * gb, quota = 10 * gb, storeUsed = 900 * gb, storeLimit = 1000 * gb)
        assertEquals(StorageMeter.Against.QUOTA, r.against)
        assertEquals(8 * gb, r.remainingBytes)
        assertEquals(10 * gb, r.totalBytes)
    }

    @Test
    fun `without a quota the store limit is the ceiling`() {
        val r = reading(used = 2 * gb, quota = 0, storeUsed = 300 * gb, storeLimit = 400 * gb)
        assertEquals(StorageMeter.Against.STORE, r.against)
        // The whole store's usage, not this account's: the limit applies to
        // everyone's files together.
        assertEquals(300 * gb, r.usedBytes)
        assertEquals(100 * gb, r.remainingBytes)
    }

    @Test
    fun `with no limit configured the disk is the ceiling`() {
        // The case that matters most on a self-hosted box: reporting
        // "unlimited" to someone whose drive is nearly full would be a lie.
        val r = reading(used = 2 * gb, quota = 0, storeLimit = 0, diskFree = 40 * gb, diskTotal = 500 * gb)
        assertEquals(StorageMeter.Against.DISK, r.against)
        assertEquals(40 * gb, r.remainingBytes)
        assertEquals(460 * gb, r.usedBytes)
        assertTrue(r.unlimited)
    }

    @Test
    fun `the warning fires only when nearly full`() {
        assertFalse(reading(used = 89 * gb, quota = 100 * gb).nearlyFull)
        assertTrue(reading(used = 90 * gb, quota = 100 * gb).nearlyFull)
        assertTrue(reading(used = 100 * gb, quota = 100 * gb).nearlyFull)
    }

    @Test
    fun `overshooting a quota does not report negative space`() {
        // A quota is checked before an upload, not enforced on the disk, so
        // usage really can exceed it -- and "-2 GB left" is nonsense.
        val r = reading(used = 12 * gb, quota = 10 * gb)
        assertEquals(0L, r.remainingBytes)
        assertEquals(1f, r.fraction)
        assertTrue(r.nearlyFull)
    }

    @Test
    fun `an unmeasured server does not divide by zero`() {
        // Every figure is zero until the first measurement lands.
        val r = StorageMeter.of(0, 0, 0, 0, 0, 0)
        assertEquals(0f, r.fraction)
        assertEquals(0L, r.remainingBytes)
        assertFalse(r.nearlyFull)
    }

    @Test
    fun `the fraction stays within the bar`() {
        assertEquals(0.5f, reading(used = 5 * gb, quota = 10 * gb).fraction)
        assertEquals(1f, reading(used = 99 * gb, quota = 10 * gb).fraction)
        assertEquals(0f, reading(used = -5, quota = 10 * gb).fraction)
    }
}

/**
 * Which theme applies.
 *
 * The app followed the system with no override; "system" now has to be the
 * only choice that defers to it.
 */
class ThemeChoiceTest {

    @Test
    fun `system follows the phone`() {
        assertTrue(ThemeChoice.resolve(ThemeChoice.SYSTEM, systemDark = true))
        assertFalse(ThemeChoice.resolve(ThemeChoice.SYSTEM, systemDark = false))
    }

    @Test
    fun `an explicit choice ignores the phone`() {
        assertFalse(ThemeChoice.resolve(ThemeChoice.LIGHT, systemDark = true))
        assertTrue(ThemeChoice.resolve(ThemeChoice.DARK, systemDark = false))
    }

    @Test
    fun `an unknown stored value falls back to following the system`() {
        // Preferences outlive the code that wrote them.
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of("sepia"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.of("DARK"))
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.of("light"))
    }
}

/** What stops a password change before it costs a round trip. */
class PasswordProblemTest {

    private val good = "a-long-enough-password"

    @Test
    fun `a well-formed change is accepted`() {
        assertNull(passwordProblem("current-password", good, good))
    }

    @Test
    fun `a mistyped repeat is caught here, not by the server`() {
        assertTrue(passwordProblem("current-password", good, good + "x") != null)
    }

    @Test
    fun `the new password must clear the server's minimum`() {
        val short = "a".repeat(MIN_PASSWORD - 1)
        assertTrue(passwordProblem("current-password", short, short) != null)
        val exact = "a".repeat(MIN_PASSWORD)
        assertNull(passwordProblem("current-password", exact, exact))
    }

    @Test
    fun `changing a password to itself is refused`() {
        assertTrue(passwordProblem(good, good, good) != null)
    }

    @Test
    fun `the current password is required`() {
        assertTrue(passwordProblem("", good, good) != null)
    }
}
