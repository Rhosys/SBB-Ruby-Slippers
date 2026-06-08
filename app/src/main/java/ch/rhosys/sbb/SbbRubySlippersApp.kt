package ch.rhosys.sbb

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ch.rhosys.sbb.ui.widget.JourneyWidgetSyncer
import ch.rhosys.sbb.worker.GtfsImportWorker
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SbbRubySlippersApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var journeyWidgetSyncer: JourneyWidgetSyncer

    var startupError: Throwable? = null
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        journeyWidgetSyncer.start()
        GtfsImportWorker.schedule(this)
        installCrashHandler()

        try {
            initPostHog()
        } catch (e: Throwable) {
            Log.e("SbbRubySlippersApp", "PostHog init failed", e)
            startupError = e
        }
    }

    private fun initPostHog() {
        val config = PostHogAndroidConfig(
            apiKey = "phc_D195RxeDm7isiEPFR31SxBu0KED0Bdc0z9nwSlWM58",
            host = "https://live.rhosys.ch",
        ).apply {
            captureApplicationLifecycleEvents = true
            captureDeepLinks = true
            sessionReplay = true
        }
        PostHogAndroid.setup(this, config)
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                PostHog.capture(
                    event = "app_crashed",
                    properties = mapOf(
                        "exception" to throwable.javaClass.name,
                        "message" to (throwable.message ?: ""),
                        "stacktrace" to throwable.stackTraceToString(),
                    ),
                )
                PostHog.flush()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
