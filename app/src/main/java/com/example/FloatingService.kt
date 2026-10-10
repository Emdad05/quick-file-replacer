package com.example

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var optionStore: OptionStore
    private lateinit var replacer: Replacer

    private var floatingIconView: ImageView? = null
    private var iconLayoutParams: WindowManager.LayoutParams? = null

    private var touchCatcherOverlay: View? = null
    private var menuView: View? = null
    private var resultBubbleView: TextView? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isBusy = false
    private var isMenuShowing = false
    private var screenWidth = 0
    private var screenHeight = 0

    private val operationLogs = mutableListOf<String>()
    private var isLogExpanded = false
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    companion object {
        const val ACTION_STOP_SERVICE = "com.example.ACTION_STOP_FLOATING_SERVICE"
        private const val NOTIFICATION_ID = 9021
        private const val CHANNEL_ID = "quick_replacer_floating_channel"
        private const val TOUCH_SLOP = 10f
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        optionStore = OptionStore.getInstance(this)
        replacer = Replacer(this)

        updateScreenDimensions()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        createFloatingIcon()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            optionStore.setFloatingServiceEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        optionStore.setFloatingServiceEnabled(true)
        return START_STICKY
    }

    private fun updateScreenDimensions() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Quick Replacer floating overlay running"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setSmallIcon(R.drawable.ic_floating)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.fgs_action_stop), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingIcon() {
        val iconSize = dpToPx(52)
        val (savedX, savedY) = optionStore.getFloatingIconPosition()

        val params = WindowManager.LayoutParams(
            iconSize,
            iconSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, (screenWidth - iconSize).coerceAtLeast(0))
            y = savedY.coerceIn(100, (screenHeight - iconSize - 100).coerceAtLeast(100))
        }
        iconLayoutParams = params

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating)
            setBackgroundResource(R.drawable.bg_floating_button)
            alpha = 0.6f
            contentDescription = "Quick Replacer Floating Icon"
        }
        floatingIconView = iconView

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        iconView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    v.alpha = 1.0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                        isDragging = true
                        closeMenu()
                    }
                    if (isDragging) {
                        params.x = (initialX + dx.toInt()).coerceIn(0, screenWidth - iconSize)
                        params.y = (initialY + dy.toInt()).coerceIn(0, screenHeight - iconSize)
                        windowManager.updateViewLayout(v, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onIconClicked()
                    } else {
                        snapToNearestEdge(iconSize)
                    }
                    if (!isMenuShowing) {
                        v.alpha = 0.6f
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(iconView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun snapToNearestEdge(iconSize: Int) {
        val params = iconLayoutParams ?: return
        val iconView = floatingIconView ?: return

        val currentX = params.x
        val targetX = if (currentX + iconSize / 2 < screenWidth / 2) 0 else screenWidth - iconSize

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 200
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(iconView, params)
                } catch (ignored: Exception) {}
            }
        }
        animator.start()
        optionStore.saveFloatingIconPosition(targetX, params.y)
    }

    private fun onIconClicked() {
        if (isBusy) return
        if (isMenuShowing) {
            closeMenu()
        } else {
            openMenu()
        }
    }

    @SuppressLint("InflateParams")
    private fun openMenu() {
        if (isMenuShowing) return
        updateScreenDimensions()
        floatingIconView?.alpha = 1.0f

        val options = optionStore.getOptions()

        // 1. Transparent full-screen touch catcher to close menu when tapping outside
        val catcherParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        val catcher = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { closeMenu() }
        }
        touchCatcherOverlay = catcher
        try {
            windowManager.addView(catcher, catcherParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Menu Card View
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_QuickReplacer)
        val inflater = LayoutInflater.from(themedContext)
        val menu = inflater.inflate(R.layout.floating_menu_layout, null)
        menuView = menu

        val btnClose: View = menu.findViewById(R.id.btn_menu_close)
        val btnSettings: View = menu.findViewById(R.id.btn_menu_settings)
        val tvEmpty: TextView = menu.findViewById(R.id.tv_menu_empty)
        val optionsContainer: LinearLayout = menu.findViewById(R.id.ll_menu_options_container)

        btnClose.setOnClickListener { closeMenu() }
        btnSettings.setOnClickListener {
            closeMenu()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        if (options.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            optionsContainer.removeAllViews()

            for (opt in options) {
                val row = LinearLayout(themedContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dpToPx(6))
                    }
                }

                // 1. Primary Replace button (automatically stops/kills app first if configured, then swaps files)
                val btnReplace = MaterialButton(themedContext).apply {
                    text = opt.name
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1E293B"))
                    cornerRadius = dpToPx(8)
                    setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginEnd = dpToPx(6)
                    }
                    setOnClickListener {
                        expandLog()
                        executeOption(opt)
                    }
                }

                // 2. Dedicated Kill button (terminates target app on-demand without swapping files)
                val btnKill = MaterialButton(themedContext).apply {
                    text = "Kill"
                    textSize = 11f
                    setTextColor(Color.parseColor("#F43F5E"))
                    setBackgroundColor(Color.parseColor("#291722"))
                    strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#F43F5E"))
                    strokeWidth = dpToPx(1)
                    cornerRadius = dpToPx(8)
                    setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        expandLog()
                        executeKillApp(opt)
                    }
                }

                row.addView(btnReplace)
                row.addView(btnKill)
                optionsContainer.addView(row)
            }
        }

        // 3. Collapsible Log Section
        val llLogToggle: View = menu.findViewById(R.id.ll_log_toggle)
        val ivLogChevron: ImageView = menu.findViewById(R.id.iv_log_chevron)
        val llLogContent: View = menu.findViewById(R.id.ll_log_content)
        val btnClearLog: View = menu.findViewById(R.id.btn_clear_log)

        llLogContent.visibility = if (isLogExpanded) View.VISIBLE else View.GONE
        ivLogChevron.setImageResource(if (isLogExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)

        llLogToggle.setOnClickListener {
            isLogExpanded = !isLogExpanded
            llLogContent.visibility = if (isLogExpanded) View.VISIBLE else View.GONE
            ivLogChevron.setImageResource(if (isLogExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
            if (isLogExpanded) {
                updateLogView()
            }
        }

        btnClearLog.setOnClickListener {
            operationLogs.clear()
            updateLogView()
        }

        updateLogView()

        val iconParams = iconLayoutParams ?: return
        val iconSize = dpToPx(52)
        val menuWidth = dpToPx(270)

        // Calculate menu position relative to icon
        var menuX = if (iconParams.x > screenWidth / 2) {
            iconParams.x - menuWidth - dpToPx(8)
        } else {
            iconParams.x + iconSize + dpToPx(8)
        }
        menuX = menuX.coerceIn(dpToPx(8), screenWidth - menuWidth - dpToPx(8))

        var menuY = iconParams.y - dpToPx(20)
        menuY = menuY.coerceIn(dpToPx(30), (screenHeight - dpToPx(380)).coerceAtLeast(30))

        val menuParams = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = menuX
            y = menuY
        }

        try {
            windowManager.addView(menu, menuParams)
            isMenuShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeMenu() {
        if (!isMenuShowing) return
        touchCatcherOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            touchCatcherOverlay = null
        }
        menuView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            menuView = null
        }
        isMenuShowing = false
        floatingIconView?.alpha = 0.6f
    }

    private fun executeOption(option: Option) {
        if (isBusy) return
        isBusy = true
        flashIcon(Color.parseColor("#38BDF8")) // Flash cyan while working
        expandLog()

        serviceScope.launch {
            val result = replacer.run(option) { step ->
                appendLog(step)
            }
            isBusy = false

            if (result.success) {
                flashIcon(Color.parseColor("#10B981")) // Green
                showResultBubble("✔ ${option.name} done", isSuccess = true)
            } else {
                flashIcon(Color.parseColor("#F43F5E")) // Red
                val shortError = if (result.message.length > 40)
                    result.message.take(37) + "..."
                else
                    result.message
                showResultBubble(shortError, isSuccess = false)
            }
        }
    }

    private fun executeKillApp(option: Option) {
        if (isBusy) return
        isBusy = true
        flashIcon(Color.parseColor("#F43F5E"))
        expandLog()

        serviceScope.launch {
            val result = replacer.killApp(option) { step ->
                appendLog(step)
            }
            isBusy = false

            if (result.success) {
                flashIcon(Color.parseColor("#10B981"))
                showResultBubble(result.message, isSuccess = true)
            } else {
                flashIcon(Color.parseColor("#F43F5E"))
                showResultBubble(result.message, isSuccess = false)
            }
        }
    }

    private fun expandLog() {
        isLogExpanded = true
        mainHandler.post {
            menuView?.findViewById<View>(R.id.ll_log_content)?.visibility = View.VISIBLE
            menuView?.findViewById<ImageView>(R.id.iv_log_chevron)?.setImageResource(R.drawable.ic_chevron_down)
            updateLogView()
        }
    }

    private fun appendLog(msg: String) {
        val time = timeFormat.format(java.util.Date())
        val entry = "[$time] $msg"
        operationLogs.add(entry)
        if (operationLogs.size > 60) {
            operationLogs.removeAt(0)
        }
        mainHandler.post {
            updateLogView()
        }
    }

    private fun updateLogView() {
        val tvLogText: TextView? = menuView?.findViewById(R.id.tv_log_text)
        val svLogScroll: android.widget.ScrollView? = menuView?.findViewById(R.id.sv_log_scroll)
        if (tvLogText != null) {
            if (operationLogs.isEmpty()) {
                tvLogText.text = "No operations logged yet."
            } else {
                tvLogText.text = operationLogs.joinToString("\n")
            }
            svLogScroll?.post {
                svLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun flashIcon(color: Int) {
        val icon = floatingIconView ?: return
        icon.alpha = 1.0f
        val origBg = icon.background
        val flashBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dpToPx(52), dpToPx(52))
        }
        icon.background = flashBg

        mainHandler.postDelayed({
            icon.setBackgroundResource(R.drawable.bg_floating_button)
            if (!isMenuShowing) icon.alpha = 0.6f
        }, 500)
    }

    private fun showResultBubble(text: String, isSuccess: Boolean) {
        removeResultBubble()

        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_QuickReplacer)
        val bubble = LayoutInflater.from(themedContext).inflate(R.layout.floating_result_bubble, null) as TextView
        bubble.text = text
        val bgColor = if (isSuccess) Color.parseColor("#10B981") else Color.parseColor("#F43F5E")
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            setColor(bgColor)
        }
        bubble.background = bgDrawable

        val iconParams = iconLayoutParams ?: return
        val iconSize = dpToPx(52)

        var bubbleX = if (iconParams.x > screenWidth / 2) {
            iconParams.x - dpToPx(180)
        } else {
            iconParams.x + iconSize + dpToPx(8)
        }
        bubbleX = bubbleX.coerceIn(dpToPx(10), screenWidth - dpToPx(200))

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = (iconParams.y + dpToPx(6)).coerceIn(dpToPx(30), screenHeight - dpToPx(60))
        }

        resultBubbleView = bubble
        try {
            windowManager.addView(bubble, bubbleParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mainHandler.postDelayed({
            removeResultBubble()
        }, 2000)
    }

    private fun removeResultBubble() {
        resultBubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            resultBubbleView = null
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        closeMenu()
        removeResultBubble()
        floatingIconView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            floatingIconView = null
        }
    }
}
