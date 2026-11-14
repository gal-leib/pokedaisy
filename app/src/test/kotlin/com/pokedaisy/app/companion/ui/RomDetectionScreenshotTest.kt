package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.*
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.ui.theme.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RomDetectionScreenshotTest {
    private val tablet = DeviceConfig.PIXEL_5.copy(screenWidth = 1536, screenHeight = 2048,
        density = Density.XHIGH, orientation = ScreenOrientation.PORTRAIT)
    private val phone = tablet.copy(screenWidth = 1206, screenHeight = 2622, density = Density.XXHIGH)
    @get:Rule val paparazzi = Paparazzi(deviceConfig = tablet, theme = "android:Theme.Material.NoActionBar")
    @Before fun hosts() = installTestHosts(paparazzi.context)

    @Test fun iPadPortrait() = paparazzi.snapshot { Scene() }
    @Test fun iPadLandscape() = render(tablet.copy(orientation = ScreenOrientation.LANDSCAPE))
    @Test fun iPhonePortrait() = render(phone)
    @Test fun iPhoneLandscape() = render(phone.copy(orientation = ScreenOrientation.LANDSCAPE))
    @Test fun iPhonePlaysAs() = render(phone, settings = true)

    private fun render(device: DeviceConfig, settings: Boolean = false) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device)
        installTestHosts(paparazzi.context)
        paparazzi.snapshot { Scene(settings) }
    }

    @Composable private fun Scene(showSettings: Boolean = false) {
        AppTheme {
            val style = TouchControlsStyle(xyButtons = false, extras = setOf(TouchButton.FF))
            val snapshot = SnapshotView(connected = false, unsupported = true, error = "Unrecognized ROM",
                gamePicker = GamePickerView(listOf(GameOption("EMERALD", "Ember Road", true, false),
                    GameOption("FIRERED", "Sea Glass", false, false)), miss = BestEffort.Miss.NO_PARTY))
            val settings = object : CompanionSettings by SampleCompanion.Settings() {
                override val gamePickBadge = NewFeatures.NEW_TAG
                override val shownRows = setOf("LANGUAGE", "HELP")
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val dp = LocalDensity.current.density
                val stage = gamePanelLayout(maxWidth.value * dp, maxHeight.value * dp, dp, style = style)
                GameScreen(stage,
                    game = { Box(Modifier.fillMaxSize().background(Color(0xFF38684B))) },
                    companion = { CompanionScreen(snapshot, settings = settings, gamePick = SamplePicker.actions,
                        initialTab = if (showSettings) "SETTINGS" else "PARTY") },
                    controls = { TouchControls(style, stage.portrait, TouchInput(), Modifier.fillMaxSize()) })
            }
        }
    }
}
