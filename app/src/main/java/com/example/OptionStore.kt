package com.example

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class OptionStore(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quick_replacer_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val KEY_OPTIONS_JSON = "options_json"
        const val KEY_EXECUTION_MODE = "execution_mode"
        const val KEY_FLOATING_SERVICE_ENABLED = "floating_service_enabled"
        const val KEY_ICON_X = "floating_icon_x"
        const val KEY_ICON_Y = "floating_icon_y"
        const val KEY_ADB_PAIRED = "adb_paired"
        const val KEY_ADB_LAST_PORT = "adb_last_port"

        const val MODE_AUTO = 0
        const val MODE_ADB_ONLY = 1
        const val MODE_SHIZUKU_ONLY = 2
        const val MODE_ROOT_ONLY = 3

        @Volatile
        private var instance: OptionStore? = null

        fun getInstance(context: Context): OptionStore {
            return instance ?: synchronized(this) {
                instance ?: OptionStore(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getOptions(): List<Option> {
        val json = prefs.getString(KEY_OPTIONS_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Option>>() {}.type
            gson.fromJson<List<Option>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOptions(options: List<Option>) {
        val json = gson.toJson(options)
        prefs.edit().putString(KEY_OPTIONS_JSON, json).apply()
    }

    fun addOption(option: Option) {
        val current = getOptions().toMutableList()
        current.add(option)
        saveOptions(current)
    }

    fun updateOption(option: Option) {
        val current = getOptions().toMutableList()
        val index = current.indexOfFirst { it.id == option.id }
        if (index >= 0) {
            current[index] = option
            saveOptions(current)
        } else {
            addOption(option)
        }
    }

    fun deleteOption(id: String) {
        val current = getOptions().toMutableList()
        current.removeAll { it.id == id }
        saveOptions(current)
    }

    fun getSelectedMode(): Int {
        return prefs.getInt(KEY_EXECUTION_MODE, MODE_AUTO)
    }

    fun setSelectedMode(mode: Int) {
        prefs.edit().putInt(KEY_EXECUTION_MODE, mode).apply()
    }

    fun isFloatingServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_FLOATING_SERVICE_ENABLED, false)
    }

    fun setFloatingServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_SERVICE_ENABLED, enabled).apply()
    }

    fun getFloatingIconPosition(): Pair<Int, Int> {
        val x = prefs.getInt(KEY_ICON_X, 0)
        val y = prefs.getInt(KEY_ICON_Y, 200)
        return Pair(x, y)
    }

    fun saveFloatingIconPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_ICON_X, x).putInt(KEY_ICON_Y, y).apply()
    }

    fun isAdbPaired(): Boolean {
        return prefs.getBoolean(KEY_ADB_PAIRED, false)
    }

    fun setAdbPaired(paired: Boolean) {
        prefs.edit().putBoolean(KEY_ADB_PAIRED, paired).apply()
    }

    fun getLastAdbPort(): Int {
        return prefs.getInt(KEY_ADB_LAST_PORT, 5555)
    }

    fun setLastAdbPort(port: Int) {
        prefs.edit().putInt(KEY_ADB_LAST_PORT, port).apply()
    }

    fun exportToJson(destFile: File): Boolean {
        return try {
            val json = gson.toJson(getOptions())
            destFile.parentFile?.mkdirs()
            destFile.writeText(json)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun importFromJson(sourceFile: File): Boolean {
        return try {
            if (!sourceFile.exists()) return false
            val json = sourceFile.readText()
            val type = object : TypeToken<List<Option>>() {}.type
            val imported = gson.fromJson<List<Option>>(json, type) ?: return false
            saveOptions(imported)
            true
        } catch (e: Exception) {
            false
        }
    }
}
