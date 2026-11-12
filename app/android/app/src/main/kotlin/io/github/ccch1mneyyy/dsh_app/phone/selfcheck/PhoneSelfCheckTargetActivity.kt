package io.github.ccch1mneyyy.dsh_app.phone.selfcheck

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.phone.AppVisibility
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A local, inert target. Only the privileged read of this screen can establish a test result. */
class PhoneSelfCheckTargetActivity : Activity() {
    private var ticket: PhoneSelfCheckTarget.Ticket? = null
    private var phaseLabel: TextView? = null
    private var pattern: PatternView? = null
    private var backLabel: TextView? = null
    private var backCount = 0
    private var registered = false
    private var registeredDisplayId = -1

    private companion object {
        const val TAG = "DshSelfCheckTarget"
        const val REGISTER_RETRIES = 20
        const val REGISTER_RETRY_MS = 50L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val original = PhoneSelfCheckTarget.find(intent.identifier)
        ticket = original
        if (original == null) {
            finish()
            return
        }
        val density = resources.displayMetrics.density
        val inset = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }
        val scrollLabel = TextView(this).apply {
            id = R.id.phone_selfcheck_target_scroll
            text = TargetContract.scroll(0)
            textSize = 12f
        }
        backLabel = TextView(this).apply {
            id = R.id.phone_selfcheck_target_back
            text = TargetContract.back(0)
            textSize = 12f
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scrollLabel)
            addView(backLabel)
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(View(this), LinearLayout.LayoutParams(1, (64 * density).toInt()))
        body.addView(TextView(this).apply {
            text = AppStrings.get(this@PhoneSelfCheckTargetActivity, R.string.phone_selfcheck_target_title)
            textSize = 20f
        })
        body.addView(View(this).apply {
            id = R.id.phone_selfcheck_target_marker
            contentDescription = TargetContract.marker(original.state.targetRun)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(0xff3878a8.toInt())
        }, LinearLayout.LayoutParams(48, 48))
        val counter = TextView(this).apply {
            id = R.id.phone_selfcheck_target_counter
            text = TargetContract.counter(0)
            textSize = 24f
        }
        body.addView(counter)
        body.addView(Button(this).apply {
            id = R.id.phone_selfcheck_target_button
            text = AppStrings.get(this@PhoneSelfCheckTargetActivity, R.string.phone_selfcheck_target_tap)
            var count = 0
            setOnClickListener { count++; counter.text = TargetContract.counter(count) }
        })
        if (original.state.profile == TargetContract.Profile.C2) {
            // This fixed replacement API test never requests the IME or submits a form.
            body.isFocusableInTouchMode = true
            body.addView(EditText(this).apply {
                id = R.id.phone_selfcheck_target_edit
                hint = AppStrings.get(this@PhoneSelfCheckTargetActivity, R.string.phone_selfcheck_target_input)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                showSoftInputOnFocus = false
            })
            val phase = TextView(this).apply {
                id = R.id.phone_selfcheck_target_phase
                text = "0"
            }
            phaseLabel = phase
            body.addView(phase)
            val pixels = PatternView(this, original.state.targetRun)
            pattern = pixels
            body.addView(pixels, LinearLayout.LayoutParams(TargetFramePattern.WIDTH_PX, TargetFramePattern.HEIGHT_PX))
            body.requestFocus()
        }
        body.addView(View(this), LinearLayout.LayoutParams(1, (960 * density).toInt()))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> scrollLabel.text = TargetContract.scroll(scrollY) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        registerIfVisible()
    }

    /**
     * ColorOS may expose the Activity display only after the window is attached. Never register a
     * guessed display id: wait briefly for the real display, then fail without claiming creation.
     */
    private fun registerIfVisible(attempt: Int = 0) {
        val original = ticket ?: return
        if (registered || isFinishing || isDestroyed) return
        val displayId = display?.displayId ?: -1
        if (displayId <= 0) {
            if (attempt < REGISTER_RETRIES) {
                window.decorView.postDelayed({ registerIfVisible(attempt + 1) }, REGISTER_RETRY_MS)
            } else {
                android.util.Log.w(TAG, "target register display unavailable after ${attempt}x$REGISTER_RETRY_MS ms")
                original.state.launchUnavailable()
                finish()
            }
            return
        }
        if (!original.state.register(this, displayId, taskId)) {
            android.util.Log.w(TAG, "target register rejected displayId=$displayId taskId=$taskId")
            finish()
            return
        }
        registered = true
        registeredDisplayId = displayId
        android.util.Log.i(TAG, "target register displayId=$displayId taskId=$taskId")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                backCount++
                backLabel?.text = TargetContract.back(backCount)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    internal fun advance(original: PhoneSelfCheckTarget.Ticket, stage: TargetContract.FrameStage): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val label = phaseLabel ?: return false
        val pixels = pattern ?: return false
        if (ticket !== original || !registered || isFinishing || isDestroyed ||
            !original.state.advance(this, registeredDisplayId, stage)) return false
        label.text = stage.phase.toString()
        pixels.phase = stage.phase
        pixels.invalidate()
        return true // Requested drawing only. The backend must still read the actual Bitmap.
    }

    private class PatternView(context: Context, private val run: String) : View(context) {
        var phase = 0
        private val paint = Paint().apply { isAntiAlias = false }
        init {
            id = R.id.phone_selfcheck_target_pattern
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = AppStrings.get(context, R.string.phone_selfcheck_target_pattern)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (row in 0 until TargetFramePattern.GRID_ROWS) for (column in 0 until TargetFramePattern.GRID_COLUMNS) {
                paint.color = TargetFramePattern.colorAt(run, phase, column, row)
                canvas.drawRect(TargetFramePattern.cellLeft(column, width).toFloat(),
                    TargetFramePattern.cellTop(row, height).toFloat(),
                    TargetFramePattern.cellLeft(column + 1, width).toFloat(),
                    TargetFramePattern.cellTop(row + 1, height).toFloat(), paint)
            }
        }
    }

    override fun onDestroy() {
        val original = ticket
        val decor = window.peekDecorView()
        super.onDestroy()
        if (original != null && original.state.destroyed(this, isFinishing, isChangingConfigurations))
            PhoneSelfCheckTarget.afterDestroy(original, this, decor)
    }
}

/** One run, one original launch job. A timeout never cancels a queued Android operation. */
internal object PhoneSelfCheckTarget {
    internal class Ticket internal constructor(internal val state: TargetState) {
        internal val frameJobs = arrayOfNulls<FutureTask<Boolean>>(TargetContract.FrameStage.values().size)
        internal var launchJob: FutureTask<Unit>? = null
        internal var finishJob: FutureTask<Unit>? = null
        @Volatile internal var teardownJob: FutureTask<Unit>? = null
        @Volatile internal var teardownCheck: (() -> Unit)? = null
        internal var finishingInstance: Activity? = null
        @Volatile internal var app: Context? = null
        internal var appTask: ActivityManager.AppTask? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var current: Ticket? = null
    private const val WAIT_MS = 2_000L

    fun prepare(targetRun: String, profile: TargetContract.Profile = TargetContract.Profile.C1): Ticket = synchronized(lock) {
        check(current?.state?.facts()?.closed != false)
        Ticket(TargetState(targetRun, profile)).also { current = it }
    }

    internal fun find(identifier: String?): Ticket? = synchronized(lock) {
        current?.takeIf { identifier == TargetContract.identifier(it.state.targetRun) }
    }

    fun snapshot(ticket: Ticket): TargetState.Facts = ticket.state.facts()
    fun seal(ticket: Ticket) = ticket.state.seal()

    internal fun afterDestroy(ticket: Ticket, activity: Activity, decor: View?) {
        // ActivityThread removes the original window after onDestroy returns, in this same
        // main transaction. This queued barrier cannot run inside that transaction. A preserved
        // or deferred attached window is not acknowledged, even if TaskInfo excludes finishing.
        val check: () -> Unit = {
            if (decor?.isAttachedToWindow != true) ticket.state.teardownCompleted(activity)
        }
        ticket.teardownCheck = check
        val job = FutureTask<Unit> { check() }
        ticket.teardownJob = job
        main.post(job)
    }

    /** Coordinator worker only. host/live are resolved in the original main-thread launch job. */
    fun launch(ticket: Ticket, displayId: Int, host: () -> Activity?, live: () -> Boolean): TargetState.Facts {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (ticket.state.schedule(displayId)) {
            val job = FutureTask<Unit> {
                try {
                    val activity = host() ?: return@FutureTask Unit
                    if (!live() || activity.isFinishing || activity.isDestroyed || !AppVisibility.isResumed(activity))
                        return@FutureTask Unit
                    ticket.app = activity.applicationContext
                    if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
                        return@FutureTask Unit
                    val intent = Intent(activity, PhoneSelfCheckTargetActivity::class.java).apply {
                        identifier = TargetContract.identifier(ticket.state.targetRun)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
                    val manager = activity.getSystemService(ActivityManager::class.java)
                    if (!manager.isActivityStartAllowedOnDisplay(activity, displayId, intent))
                        return@FutureTask Unit
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                    if (ticket.state.attempt(manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE))
                        activity.startActivity(intent, options.toBundle())
                } finally {
                    ticket.state.launchReturned()
                }
            }
            ticket.launchJob = job
            if (!main.post(job)) ticket.state.launchReturned()
        }
        await(ticket.launchJob)
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        while (!ticket.state.hasCreated() && ticket.state.facts().launchAttempted && SystemClock.elapsedRealtime() < until) {
            inspectTask(ticket)
            if (!pause(ticket)) break
        }
        inspectTask(ticket)
        return snapshot(ticket)
    }

    /** Coordinator worker only; the next stage is called only after the previous pixel ACK. */
    fun advance(ticket: Ticket, stage: TargetContract.FrameStage): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        var job = ticket.frameJobs[stage.ordinal]
        if (job == null) {
            job = FutureTask<Boolean> {
                val activity = ticket.state.instance() as? PhoneSelfCheckTargetActivity
                activity?.advance(ticket, stage) == true
            }
            ticket.frameJobs[stage.ordinal] = job
            if (!main.post(job)) return false
        }
        return await(job) == true
    }

    /** Seal first, then finish only the original instance; task IDs never select a finish target. */
    fun close(ticket: Ticket): TargetState.Facts {
        check(Looper.myLooper() != Looper.getMainLooper())
        seal(ticket)
        await(ticket.launchJob)
        val check = ticket.teardownCheck
        if (check != null && ticket.teardownJob?.isDone == true && ticket.state.taskReadStamp() == null) {
            // One pure check per explicit cleanup attempt, after a previously deferred teardown.
            // It never replays finish/start and does not poll the main queue without a bound.
            val job = FutureTask<Unit> { check() }
            ticket.teardownJob = job
            main.post(job)
        }
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        do {
            val activity = ticket.state.instance() as? Activity
            if (activity != null && ticket.finishingInstance !== activity) {
                ticket.finishingInstance = activity
                val job = FutureTask<Unit> { if (!activity.isDestroyed) activity.finish() }
                ticket.finishJob = job
                main.post(job)
            }
            await(ticket.finishJob)
            await(ticket.teardownJob)
            inspectTask(ticket)
            if (ticket.state.finishIfClosed()) {
                ticket.finishingInstance = null
                ticket.finishJob = null
                ticket.teardownJob = null
                ticket.teardownCheck = null
                ticket.appTask = null
                ticket.frameJobs.fill(null)
                break
            }
            if (!pause(ticket)) break
        } while (SystemClock.elapsedRealtime() < until)
        return snapshot(ticket)
    }

    /** Absence and Binder exceptions are not proof that a queued launch never happened. */
    private fun inspectTask(ticket: Ticket) {
        val app = ticket.app ?: return
        try {
            val candidates = ticket.appTask?.let { listOf(it) }
                ?: app.getSystemService(ActivityManager::class.java).appTasks
            for (task in candidates) {
                val readStamp = ticket.state.taskReadStamp()
                val info = task.taskInfo
                if (info.baseIntent.component?.packageName != app.packageName) continue
                // TaskInfo.displayId is hidden on the Android versions supported by this probe.
                // When reflection is blocked, keep the task identity evidence and treat the display
                // fact as unreadable; the structural empty-task proof remains authoritative.
                val displayId = try {
                    info.javaClass.getField("displayId").getInt(info)
                } catch (_: ReflectiveOperationException) {
                    null
                }
                if (ticket.state.observeTask(readStamp, info.taskId, info.baseIntent.component?.className,
                        info.baseIntent.identifier, displayId, info.isRunning,
                        info.numActivities, info.topActivity == null)) {
                    ticket.appTask = task
                    return
                }
            }
        } catch (_: ReflectiveOperationException) {
            // Missing or blocked hidden API evidence leaves this original ticket pending.
        } catch (_: RuntimeException) {
            // Retain the ticket and retry. In particular, a removed AppTask can throw here.
        }
    }

    private fun <T> await(job: FutureTask<T>?): T? {
        if (job == null) return null
        return try {
            job.get(WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The same job may complete later; do not submit another operation or cancel it.
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: java.util.concurrent.ExecutionException) {
            // Attempt and callback facts remain authoritative even when start/finish throws.
            null
        }
    }

    private fun pause(ticket: Ticket): Boolean = try {
        ticket.state.awaitChange(50)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
