package com.example

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

class ShizukuExecutor private constructor(private val context: Context) : Executor {

    interface ShizukuStateListener {
        fun onShizukuStateChanged()
    }

    private val listeners = CopyOnWriteArrayList<ShizukuStateListener>()
    private var userService: IUserService? = null
    private var isBinding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IUserService.Stub.asInterface(service)
            isBinding = false
            notifyStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            isBinding = false
            notifyStateChanged()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBindUserService()
        notifyStateChanged()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        notifyStateChanged()
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            checkAndBindUserService()
            notifyStateChanged()
        }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            checkAndBindUserService()
        } catch (e: Exception) {
            Log.e("ShizukuExecutor", "Failed to register Shizuku listeners", e)
        }
    }

    companion object {
        @Volatile
        private var instance: ShizukuExecutor? = null

        fun getInstance(context: Context): ShizukuExecutor {
            return instance ?: synchronized(this) {
                instance ?: ShizukuExecutor(context.applicationContext).also { instance = it }
            }
        }
    }

    fun addListener(listener: ShizukuStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onShizukuStateChanged()
        }
    }

    fun removeListener(listener: ShizukuStateListener) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged() {
        for (l in listeners) {
            try {
                l.onShizukuStateChanged()
            } catch (e: Exception) {
                Log.e("ShizukuExecutor", "Listener error", e)
            }
        }
    }

    fun checkAndBindUserService() {
        if (userService != null || isBinding) return
        if (!isShizukuInstalled()) return
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        isBinding = true
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShizukuUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("service")
                .debuggable(BuildConfig.DEBUG)
                .version(1)

            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            isBinding = false
            Log.e("ShizukuExecutor", "Error binding Shizuku user service", e)
        }
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    override fun isReady(): Boolean {
        if (userService != null) return true
        if (hasShizukuPermission()) {
            checkAndBindUserService()
        }
        return userService != null
    }

    override fun exec(script: String): Pair<Int, String> {
        if (!isReady()) {
            return Pair(-1, "Shizuku service not bound or permission denied")
        }
        val service = userService ?: return Pair(-1, "Shizuku user service unavailable")

        return try {
            val raw = service.exec(script)
            val lines = raw.lines()
            if (lines.isNotEmpty() && lines[0].startsWith("EXIT=")) {
                val code = lines[0].removePrefix("EXIT=").trim().toIntOrNull() ?: -1
                val output = lines.drop(1).joinToString("\n").trim()
                Pair(code, output)
            } else {
                Pair(0, raw.trim())
            }
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Shizuku exec exception")
        }
    }
}
