package com.simonmettler.timetracker

import android.graphics.Bitmap
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.glance.appwidget.AppWidgetId
import com.simonmettler.timetracker.widget.TrackerWidget
import com.simonmettler.timetracker.widget.TrackerWidgetReceiver
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.simonmettler.timetracker.data.TimeInterval
import com.simonmettler.timetracker.model.Boundary
import com.simonmettler.timetracker.model.TimeRules
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TrackerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestTrackerApplication>()
    @Before fun reset() = runBlocking(Dispatchers.IO) { app.database.clearAllTables() }

    @Test fun createColorButtonTrackAndReturnHome() {
        ui.onNodeWithText("Create your first button").performClick()
        ui.onNodeWithText("Button title").performTextInput("Work")
        ui.onNodeWithText("Custom color (hex RGB)").performTextReplacement("FFD540")
        ui.onNodeWithText("Save button").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Work. Start new interval.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Work. Start new interval.").performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.dao().active() != null } }
        ui.onNodeWithContentDescription("Tracking Work", substring = true).assertIsDisplayed()
        val saved = runBlocking { app.repository.widgetSnapshot() }.first.single()
        assertEquals(0xFFFFD540.toInt(), saved.colorArgb)
        ui.onNodeWithText("Calendar").performClick()
        ui.onNodeWithText("Home").performClick()
        ui.onNodeWithContentDescription("Tracking Work", substring = true).assertIsDisplayed()
    }

    @Test fun calendarEditPersistsChangedStart() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val start = Boundary.capture(now.minusSeconds(3600), zone)
        val end = Boundary.capture(now.minusSeconds(60), zone)
        runBlocking {
            val id = app.repository.saveActivity(null, "Reading", 0xFF7254B8.toInt())
            app.database.dao().insertInterval(TimeInterval("ui-entry", id, start.atMs, start.zoneId, start.offsetSeconds, end.atMs, end.zoneId, end.offsetSeconds, null, start.atMs, start.atMs))
        }
        ui.onNodeWithText("Calendar").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Reading").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Reading").performClick()
        val changed = Boundary.capture(now.minusSeconds(1800), zone)
        ui.onNodeWithText("Start date and time").performTextReplacement(TimeRules.originalLocal(changed))
        ui.onNodeWithText("Save entry").performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.dao().interval("ui-entry")!!.startAtMs == changed.atMs } }
        ui.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test fun calendarCanFillAHalfSecondGapAndCombineTheMatchingEntries() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(1).atTime(9, 0).atZone(zone).toInstant()
        val gapEnd = start.plusSeconds(60)
        val gapStart = gapEnd.minusMillis(500)
        runBlocking {
            val first = app.repository.saveActivity(null, "First", 0xFF19776A.toInt())
            val later = app.repository.saveActivity(null, "Later", 0xFFB6553C.toInt())
            listOf(first, later).forEachIndexed { index, activity ->
                val from = Boundary.capture(start.plusSeconds(index * 60L), zone)
                val to = Boundary.capture(start.plusSeconds((index + 1) * 60L), zone)
                app.database.dao().insertInterval(TimeInterval(
                    id = "gap-$index", activityId = activity,
                    startAtMs = from.atMs, startZoneId = from.zoneId, startOffsetSeconds = from.offsetSeconds,
                    endAtMs = to.atMs, endZoneId = to.zoneId, endOffsetSeconds = to.offsetSeconds,
                    activeSlot = null, createdAtMs = from.atMs, updatedAtMs = from.atMs
                ))
            }
        }

        ui.onNodeWithText("Calendar").performClick()
        ui.onNodeWithContentDescription("Previous day").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Tap an entry to edit its start and end.").fetchSemanticsNodes().isNotEmpty() }
        val firstRow = hasText("First") and hasClickAction()
        val combine = hasText("Combine") and hasClickAction()
        ui.onNode(hasScrollAction()).performScrollToNode(firstRow)
        ui.onNode(combine).assertDoesNotExist()
        ui.onNode(firstRow).performClick()
        ui.onNodeWithText("End date and time").performScrollTo()
            .performTextReplacement(TimeRules.originalLocal(Boundary.capture(gapStart, zone)))
        ui.onNodeWithText("Save entry").performClick()
        // Let the save confirmation clear before tapping a row near the bottom of the list.
        val confirmation = ui.onAllNodesWithText("Calendar entry saved")
        ui.waitUntil(10_000) { confirmation.fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { confirmation.fetchSemanticsNodes().isEmpty() }

        val gapRow = hasText("Untracked time") and hasClickAction()
        ui.onNode(hasScrollAction()).performScrollToNode(gapRow)
        ui.onNode(combine).assertDoesNotExist()
        ui.onNode(gapRow and hasText("500 ms", substring = true)).assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Fill untracked time").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Choose activity").performClick()
        ui.onNode(hasText("First") and hasAnyAncestor(isPopup())).performClick()
        ui.onNodeWithText("Add entry").performClick()
        val addedConfirmation = ui.onAllNodesWithText("Calendar entry added")
        ui.waitUntil(10_000) { addedConfirmation.fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { addedConfirmation.fetchSemanticsNodes().isEmpty() }
        ui.onNode(gapRow).assertDoesNotExist()
        val saved = runBlocking { app.database.dao().intervals() }.single { it.id !in listOf("gap-0", "gap-1") }
        assertEquals(gapStart.toEpochMilli(), saved.startAtMs)
        assertEquals(gapEnd.toEpochMilli(), saved.endAtMs)

        val firstCombine = combine and hasAnyAncestor(hasText("09:00 – 09:00", substring = true))
        val addedCombine = combine and hasAnyAncestor(hasText("09:00 – 09:01", substring = true))
        ui.onNode(hasScrollAction()).performScrollToNode(firstCombine)
        ui.onNode(firstCombine).assertIsDisplayed()
        ui.onNode(hasScrollAction()).performScrollToNode(addedCombine)
        ui.onNode(addedCombine).assertIsDisplayed().performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("Calendar entries combined").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNode(combine).assertDoesNotExist()
        val combined = runBlocking { app.database.dao().intervals() }
        assertEquals(listOf("gap-0", "gap-1"), combined.map { it.id })
        assertEquals(start.toEpochMilli(), combined[0].startAtMs)
        assertEquals(gapEnd.toEpochMilli(), combined[0].endAtMs)
    }

    @Test fun calendarCanDeleteRepeatedMistakesAndCreditThePreviousActivity() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(1).atTime(9, 0).atZone(zone).toInstant()
        val endOfMistakes = start.plusSeconds(180).toEpochMilli()
        runBlocking {
            val focus = app.repository.saveActivity(null, "Focus", 0xFF19776A.toInt())
            val mistake = app.repository.saveActivity(null, "Mistake", 0xFF7254B8.toInt())
            val later = app.repository.saveActivity(null, "Later", 0xFFB6553C.toInt())
            listOf(focus, mistake, mistake, later).forEachIndexed { index, activity ->
                val from = Boundary.capture(start.plusSeconds(index * 60L), zone)
                val to = Boundary.capture(start.plusSeconds((index + 1) * 60L), zone)
                app.database.dao().insertInterval(TimeInterval(
                    id = "delete-$index", activityId = activity,
                    startAtMs = from.atMs, startZoneId = from.zoneId, startOffsetSeconds = from.offsetSeconds,
                    endAtMs = to.atMs, endZoneId = to.zoneId, endOffsetSeconds = to.offsetSeconds,
                    activeSlot = null, createdAtMs = from.atMs, updatedAtMs = from.atMs
                ))
            }
        }

        ui.onNodeWithText("Calendar").performClick()
        ui.onNodeWithContentDescription("Previous day").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Tap an entry to edit its start and end.").fetchSemanticsNodes().isNotEmpty() }
        val row = hasText("Mistake") and hasClickAction()
        ui.onNode(hasScrollAction()).performScrollToNode(row)
        ui.onAllNodes(row).onFirst().performClick()
        ui.onNodeWithContentDescription("Delete entry").performClick()
        ui.onNodeWithText("Include all 2 consecutive entries").performClick()
        ui.onNodeWithText("This time will be added to Focus.").assertIsDisplayed()
        ui.onNodeWithText("Delete 2 entries").performClick()

        ui.waitUntil(10_000) { runBlocking { app.database.dao().intervals().size == 2 } }
        val remaining = runBlocking { app.database.dao().intervals() }
        assertEquals(listOf("delete-0", "delete-3"), remaining.map { it.id })
        assertEquals(endOfMistakes, remaining[0].endAtMs)
        assertEquals(endOfMistakes, remaining[1].startAtMs)
    }

    @Test fun longDashboardCanReachLastButtonAndCaptureScreens() {
        runBlocking {
            repeat(12) { app.repository.saveActivity(null, "Activity ${it + 1}", listOf(0xFF19776A, 0xFF7254B8, 0xFFB6553C)[it % 3].toInt()) }
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Activity 1").fetchSemanticsNodes().isNotEmpty() }
        capture("dashboard")
        ui.onNodeWithText("Activity 1").performClick()
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Activity 12"))
        ui.onNodeWithText("Activity 12").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.dao().active()?.let { app.database.dao().activity(it.activityId)?.title } == "Activity 12" } }
        capture("last-button")
        ui.onNodeWithText("Calendar").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Activity 12").fetchSemanticsNodes().isNotEmpty() }
        capture("calendar")
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun realWidgetHostRendersButtonsAndDeliversRepeatedTaps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun awaitNative(timeout: Long, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            while (!condition()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for the native widget" }
                Thread.sleep(50)
            }
        }
        runBlocking {
            app.repository.saveActivity(null, "Widget Work", 0xFF19776A.toInt())
            app.repository.saveActivity(null, "Widget Travel", 0xFF7254B8.toInt())
        }
        val host = AppWidgetHost(app, 1024)
        var widgetId = -1
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            widgetId = host.allocateAppWidgetId()
            val manager = AppWidgetManager.getInstance(app)
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
            }
            assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(app, TrackerWidgetReceiver::class.java), options))
            host.startListening()
            lateinit var root: View
            instrumentation.runOnMainSync {
                root = host.createView(ui.activity, widgetId, manager.getAppWidgetInfo(widgetId))
                ui.activity.setContentView(root)
            }
            runBlocking { TrackerWidget().update(app, AppWidgetId(widgetId)) }
            fun find(view: View, title: String): TextView? {
                if (view is TextView && view.text.toString().contains(title)) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), title)?.let { return it }
                return null
            }
            fun click(title: String) {
                awaitNative(30_000) {
                    var found = false
                    instrumentation.runOnMainSync { found = find(root, title) != null }
                    found
                }
                instrumentation.runOnMainSync {
                    var target: View = requireNotNull(find(root, title))
                    while (!target.hasOnClickListeners() && target.parent is View) target = target.parent as View
                    assertTrue("Widget button should deliver its PendingIntent", target.performClick())
                }
            }
            click("Widget Work")
            awaitNative(15_000) { runBlocking { app.database.dao().active() != null } }
            click("Widget Travel")
            awaitNative(15_000) { runBlocking { app.database.dao().intervals().size == 2 } }
            click("Widget Travel")
            awaitNative(15_000) { runBlocking { app.database.dao().intervals().size == 3 } }
            assertEquals("Widget Travel", runBlocking { app.database.dao().activity(app.database.dao().active()!!.activityId)!!.title })
            capture("widget")
        } finally {
            if (widgetId != -1) host.deleteAppWidgetId(widgetId)
            host.stopListening()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun capture(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(screenshot)
        val directory = File(app.cacheDir, "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
