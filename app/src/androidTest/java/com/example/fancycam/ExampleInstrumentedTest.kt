package com.example.fancycam

import com.example.fancycam.ui.MainActivity
import com.example.fancycam.ui.SettingsActivity
import com.example.fancycam.ui.AppActivityLogger
import com.example.fancycam.network.AppConfig
import com.example.fancycam.storage.PhotoStorageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Comprehensive instrumented tests for FancyCam across different Android versions.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.fancycam", appContext.packageName)
    }

    @Test
    fun testAppConfigDefaultsAndEndpoints() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val host = AppConfig.getServerHost(appContext)
        val port = AppConfig.getServerPort(appContext)
        assertEquals(AppConfig.DEFAULT_HOST, host)
        assertEquals(AppConfig.DEFAULT_PORT, port)

        val candidateEndpoints = AppConfig.getCandidateEndpoints(appContext, "/exfil/photo")
        assertTrue("Should include at least 1 candidate endpoint", candidateEndpoints.isNotEmpty())
        assertTrue("Should include configured 127.0.0.1 endpoint", candidateEndpoints.any { it.contains("127.0.0.1:5000/exfil/photo") })
        assertTrue("Should include emulator fallback 10.0.2.2 endpoint", candidateEndpoints.any { it.contains("10.0.2.2:5000/exfil/photo") })
    }

    @Test
    fun testPhotoStorageManager() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dirs = PhotoStorageManager.getStorageDirectories(appContext)
        assertFalse("Storage directories must not be empty", dirs.isEmpty())

        val photoFile = PhotoStorageManager.createNewPhotoFile(appContext)
        assertNotNull("Created photo file should not be null", photoFile)
        assertTrue("Photo file name should start with IMG_", photoFile.name.startsWith("IMG_"))
        assertTrue("Photo file extension should be jpg", photoFile.extension.equals("jpg", ignoreCase = true))

        // Create temporary photo file in app cache and check details
        val testFile = File(appContext.cacheDir, "test_sample.jpg")
        testFile.writeBytes(ByteArray(1024))
        val details = PhotoStorageManager.getPhotoDetails(testFile)
        assertEquals(testFile.name, details.name)
        assertEquals(1024L, details.sizeBytes)
        testFile.delete()
    }

    @Test
    fun testAppActivityLogger() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        AppActivityLogger.clearActivities(appContext)
        assertTrue("Activities should be empty after clear", AppActivityLogger.getRecentActivities(appContext).isEmpty())

        AppActivityLogger.logActivity(appContext, "TEST", "Instrumentation Test Title", "Details here", true)
        val activities = AppActivityLogger.getRecentActivities(appContext)
        assertEquals(1, activities.size)
        assertEquals("TEST", activities[0].type)
        assertEquals("Instrumentation Test Title", activities[0].title)

        AppActivityLogger.clearActivities(appContext)
    }

    @Test
    fun testMainActivityLaunch() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull("MainActivity should not be null", activity)
            assertNotNull("Capture button should be present", activity.findViewById(R.id.captureButton))
            assertNotNull("Switch camera button should be present", activity.findViewById(R.id.switchCameraButton))
            assertNotNull("Flash button should be present", activity.findViewById(R.id.btnFlash))
        }
        assertTrue("MainActivity state should be at least STARTED", scenario.state.isAtLeast(Lifecycle.State.STARTED))
        scenario.close()
    }

    @Test
    fun testSettingsActivityLaunch() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull("SettingsActivity should not be null", activity)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        onView(withId(R.id.etServerHost)).check(matches(isDisplayed()))
        onView(withId(R.id.etServerPort)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSaveConfig)).check(matches(isDisplayed()))
        scenario.close()
    }
}