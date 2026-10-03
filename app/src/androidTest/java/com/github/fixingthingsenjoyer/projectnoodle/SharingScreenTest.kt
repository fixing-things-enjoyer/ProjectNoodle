package com.github.fixingthingsenjoyer.projectnoodle

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.fixingthingsenjoyer.projectnoodle.ui.SharingScreen
import com.github.fixingthingsenjoyer.projectnoodle.ui.theme.ProjectNoodleTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharingScreenTest {
    @get:Rule val compose = createComposeRule()
    private var chosen = false
    private var started = false
    private var stopped = false

    private fun show(state: SharingState, folderName: String?) {
        compose.setContent {
            ProjectNoodleTheme(dynamicColor = false) {
                SharingScreen(
                    state,
                    folderName,
                    true,
                    false,
                    false,
                    onChooseFolder = { chosen = true },
                    onStart = { started = true },
                    onStop = { stopped = true },
                    onApprovalChange = {},
                    onHttpsChange = {},
                    onCopy = {},
                    onShare = {},
                    onAllowClient = {},
                    onDeclineClient = {},
                )
            }
        }
    }

    @Test
    fun firstRunHasClearFolderAction() {
        show(SharingState(), null)
        compose.onNodeWithText("Choose a folder").performClick()
        assertTrue(chosen)
    }

    @Test
    fun selectedFolderCanStartSharing() {
        show(SharingState(), "Downloads")
        compose.onNodeWithText("Start sharing").performClick()
        assertTrue(started)
    }

    @Test
    fun runningSessionCanStopAndCannotChangeFolder() {
        show(
            SharingState(
                status = "Running",
                folderName = "Downloads",
                address = "http://192.168.1.10:8080",
            ),
            "Downloads",
        )
        compose.onNodeWithText("Change folder").assertDoesNotExist()
        compose.onNodeWithText("Stop sharing").performClick()
        assertTrue(stopped)
    }

    @Test
    fun pendingClientIsVisibleInApp() {
        show(SharingState(status = "Running", pendingClients = listOf("192.168.1.20")), "Downloads")
        compose.onNodeWithText("192.168.1.20").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
    }
}
