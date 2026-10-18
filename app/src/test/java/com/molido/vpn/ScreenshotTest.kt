package com.molido.vpn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.robolectric.RuntimeEnvironment
import com.molido.vpn.ui.SmartConnectPanel
import com.molido.vpn.ui.state.SmartConnectState
import com.molido.vpn.ui.state.SmartConnectState.MethodState
import com.molido.vpn.ui.state.SmartConnectState.Phase
import com.molido.vpn.ui.state.SmartConnectState.Status
import com.molido.vpn.ui.state.SmartConnectState.UiState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the real screens to PNG on the JVM (Robolectric native graphics), for the PR and the
 * design review when no emulator is available. Skipped unless MOLIDO_SCREENS points at an
 * output directory, so CI and normal `testDebugUnitTest` runs are unaffected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {

    private val out: String? = System.getenv("MOLIDO_SCREENS")

    @Before
    fun gate() {
        assumeTrue("set MOLIDO_SCREENS=<dir> to render screenshots", !out.isNullOrBlank())
    }

    private val methods = listOf(
        "wireguard" to "WARP", "amnezia" to "AmneziaWG", "masque" to "MASQUE",
        "shard" to "SHARD", "v2ray" to "V2Ray", "gool" to "WARP-on-WARP",
        "psiphon-over-warp" to "Psiphon over WARP", "psiphon" to "Psiphon + Conduit", "tor" to "Tor",
    )

    private fun testingState() = UiState(
        phase = Phase.TESTING, network = "cell:43235", leader = "v2ray",
        methods = methods.map { (id, label) ->
            when (id) {
                "v2ray" -> MethodState(id, label, Status.OK, 91, 84, "84 ms · 3/3 stable")
                "wireguard" -> MethodState(id, label, Status.FAILED)
                "amnezia" -> MethodState(id, label, Status.TESTING)
                "masque" -> MethodState(id, label, Status.OK, 58, 212)
                "shard" -> MethodState(id, label, Status.OK, 72, 140)
                else -> MethodState(id, label, Status.QUEUED)
            }
        },
    )

    private fun connectedState() = testingState().copy(
        phase = Phase.CONNECTED, winner = "v2ray", winnerReason = "84 ms · 3/3 stable · 6.2 Mbit/s",
        runnerUp = "shard", runnerUpScore = 72, pingMs = 84, downKbps = 6_200, upKbps = 1_800,
        methods = testingState().methods.map { if (it.status == Status.QUEUED || it.status == Status.TESTING) it.copy(status = Status.SKIPPED) else it },
    )

    private fun prefs(night: Boolean) {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(AppAppearance.PREF_KEY, if (night) "dark" else "light")
            .putString(AppLanguage.PREF, "fa")
            .putBoolean("onboarding_done", true)
            .apply()
    }

    private fun save(view: View, name: String, width: Int, height: Int?) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            if (height == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            else View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out!!).mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))

    /** The whole home screen, with the real MainActivity. */
    private fun home(night: Boolean, state: UiState, name: String) {
        prefs(night)
        SmartConnectState.set(state)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        SmartConnectState.set(state)
        idle()
        val root = controller.get().window.decorView
        val dm = controller.get().resources.displayMetrics
        save(root, name, dm.widthPixels, dm.heightPixels)
        controller.pause().stop().destroy()
    }

    /** The Smart Connect panel alone (full height, nothing cropped). */
    private fun panel(night: Boolean, state: UiState, name: String) {
        prefs(night)
        val ctx = RuntimeEnvironment.getApplication()
        val palette = AppAppearance.load(ctx)
        val p = SmartConnectPanel(ctx, palette, Typefaces.regular(ctx), Typefaces.bold(ctx), Typefaces.numeric(ctx))
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(palette.canvas)
            setPadding(48, 48, 48, 48)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        p.render(state)
        save(frame, name, 1080, null)
        // Second pass after the progress bar's post{} ran.
        idle()
        save(frame, name, 1080, null)
    }

    @Test
    fun renderPanels() {
        panel(true, testingState(), "panel-testing-dark")
        panel(true, connectedState(), "panel-connected-dark")
        panel(false, testingState(), "panel-testing-light")
        panel(false, connectedState(), "panel-connected-light")
    }

    @Test
    fun renderHome() {
        home(true, UiState(), "home-idle-dark")
        home(true, testingState(), "home-testing-dark")
        home(true, connectedState(), "home-connected-dark")
        home(false, UiState(), "home-idle-light")
        home(false, testingState(), "home-testing-light")
        home(false, connectedState(), "home-connected-light")
    }
}
