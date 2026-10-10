package com.example

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class ReplaceResult(
    val success: Boolean,
    val message: String,
    val backend: String = ""
)

class Replacer(private val context: Context) {

    private val adbManager = AdbManager.getInstance(context)
    private val adbExecutor = AdbExecutor(adbManager)
    private val shizukuExecutor = ShizukuExecutor.getInstance(context)
    private val rootExecutor = RootExecutor()
    private val optionStore = OptionStore.getInstance(context)

    companion object {
        fun escapeShellPath(path: String): String {
            return "'" + path.replace("'", "'\\''") + "'"
        }

        fun isRestrictedPath(path: String): Boolean {
            val normalized = path.replace("/storage/emulated/0", "/sdcard")
                .replace("/storage/self/primary", "/sdcard")
            return normalized.contains("/Android/data") || normalized.contains("/Android/obb")
        }

        fun extractPackageName(destination: String): String {
            val regex = Regex("/Android/(data|obb)/([^/]+)/?")
            val match = regex.find(destination)
            return match?.groupValues?.get(2) ?: ""
        }
    }

    suspend fun run(option: Option, onProgress: ((String) -> Unit)? = null): ReplaceResult = withContext(Dispatchers.IO) {
        val src = option.source.trim()
        val dst = option.destination.trim()

        if (src.isEmpty() || dst.isEmpty()) {
            val err = "Source or destination path is empty"
            onProgress?.invoke("✖ $err")
            return@withContext ReplaceResult(false, err)
        }

        onProgress?.invoke("Starting '${option.name}'...")

        val pkg = if (option.packageName.isNotBlank()) {
            option.packageName.trim()
        } else {
            extractPackageName(dst)
        }

        if (option.forceStop && pkg.isNotBlank()) {
            onProgress?.invoke("Killing process '$pkg'...")
        }

        // Fast path: if neither source nor destination is inside Android/data or Android/obb
        // and source exists as normal file
        if (!isRestrictedPath(src) && !isRestrictedPath(dst)) {
            onProgress?.invoke("Copying file directly...")
            val fastResult = tryPlainFileCopy(option)
            if (fastResult.success) {
                onProgress?.invoke("✔ Success: ${option.name} (Direct)")
                return@withContext fastResult
            }
        }

        // Elevated execution
        val mode = optionStore.getSelectedMode()
        onProgress?.invoke("Preparing elevated execution...")
        val res = runWithElevatedBackend(option, mode, onProgress)
        if (res.success) {
            onProgress?.invoke("✔ Success: ${option.name} via ${res.backend}")
        } else {
            onProgress?.invoke("✖ Error: ${res.message}")
        }
        return@withContext res
    }

    suspend fun killApp(option: Option, onProgress: ((String) -> Unit)? = null): ReplaceResult = withContext(Dispatchers.IO) {
        val pkg = if (option.packageName.isNotBlank()) {
            option.packageName.trim()
        } else {
            extractPackageName(option.destination)
        }
        if (pkg.isBlank()) {
            val err = "No target package detected to kill"
            onProgress?.invoke("✖ $err")
            return@withContext ReplaceResult(false, err)
        }
        onProgress?.invoke("Killing process '$pkg' (am force-stop + pkill)...")
        val escapedPkg = escapeShellPath(pkg)
        val script = "am force-stop $escapedPkg 2>/dev/null\npkill -9 -f $escapedPkg 2>/dev/null\necho OK\n"

        val mode = optionStore.getSelectedMode()
        val executor = when (mode) {
            OptionStore.MODE_ADB_ONLY -> {
                if (!adbExecutor.isReady()) adbManager.tryAutoConnect()
                if (adbExecutor.isReady()) adbExecutor else null
            }
            OptionStore.MODE_SHIZUKU_ONLY -> {
                if (shizukuExecutor.isReady()) shizukuExecutor else null
            }
            OptionStore.MODE_ROOT_ONLY -> {
                if (rootExecutor.isReady()) rootExecutor else null
            }
            else -> {
                if (!adbExecutor.isReady() && optionStore.isAdbPaired()) {
                    adbManager.tryAutoConnect()
                }
                if (adbExecutor.isReady()) adbExecutor
                else if (shizukuExecutor.isReady()) shizukuExecutor
                else if (rootExecutor.isReady()) rootExecutor
                else null
            }
        }

        if (executor == null) {
            val err = "No elevated access available to kill app"
            onProgress?.invoke("✖ $err")
            return@withContext ReplaceResult(false, err)
        }

        val (code, output) = executor.exec(script)
        if (code == 0 && output.contains("OK")) {
            onProgress?.invoke("✔ Killed $pkg successfully")
            ReplaceResult(true, "✔ Killed $pkg", "Kill")
        } else {
            onProgress?.invoke("✖ Failed to kill $pkg ($output)")
            ReplaceResult(false, "✖ Kill failed", "Kill")
        }
    }

    private fun tryPlainFileCopy(option: Option): ReplaceResult {
        return try {
            val srcFile = File(option.source)
            if (!srcFile.exists()) {
                return ReplaceResult(false, "Source file not found")
            }
            val dstFile = File(option.destination)

            // Optional backup
            if (option.backup && dstFile.exists()) {
                val bakFile = File("${option.destination}.bak")
                if (!bakFile.exists()) {
                    copyFileOrDirectory(dstFile, bakFile)
                }
            }

            dstFile.parentFile?.mkdirs()
            copyFileOrDirectory(srcFile, dstFile)
            ReplaceResult(true, "✔ ${option.name} done", "Direct File")
        } catch (e: Exception) {
            // If direct file access throws security exception, fall through to elevated backend
            ReplaceResult(false, e.message ?: "Direct copy failed")
        }
    }

    private fun copyFileOrDirectory(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyFileOrDirectory(child, File(dst, child.name))
            }
        } else {
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun buildScript(option: Option, useCatFallback: Boolean = false): String {
        val escapedSrc = escapeShellPath(option.source)
        val escapedDst = escapeShellPath(option.destination)
        val pkg = if (option.packageName.isNotBlank()) {
            option.packageName.trim()
        } else {
            extractPackageName(option.destination)
        }
        val escapedPkg = escapeShellPath(pkg)

        val sb = StringBuilder()
        sb.append("#!/bin/sh\n")

        // 1. Optional force-stop and process kill
        if (option.forceStop && pkg.isNotBlank()) {
            sb.append("am force-stop $escapedPkg 2>/dev/null\n")
            sb.append("pkill -9 -f $escapedPkg 2>/dev/null\n")
        }

        // 2. Source check
        sb.append("[ -e $escapedSrc ] || { echo 'SOURCE_NOT_FOUND'; exit 3; }\n")

        // 3. Ensure parent directory
        sb.append("mkdir -p \"$(dirname $escapedDst)\"\n")

        // 4. Optional backup (first time only)
        if (option.backup) {
            val escapedBak = escapeShellPath("${option.destination}.bak")
            sb.append("[ -e $escapedDst ] && [ ! -e $escapedBak ] && cp -a $escapedDst $escapedBak 2>/dev/null\n")
        }

        // 5. Copy and overwrite
        if (useCatFallback) {
            sb.append("cat $escapedSrc > $escapedDst || { echo 'COPY_FAILED'; exit 4; }\n")
        } else {
            sb.append("cp -rf $escapedSrc $escapedDst || { echo 'COPY_FAILED'; exit 4; }\n")
        }

        // 6. Optional permission fix (never fails the job)
        if (option.fixPermissions) {
            if (pkg.isNotBlank()) {
                val dataPkgPath = escapeShellPath("/sdcard/Android/data/$pkg")
                sb.append("U=\$(stat -c %u $dataPkgPath 2>/dev/null)\n")
                sb.append("[ -n \"\$U\" ] && chown -R \"\$U:\$U\" $escapedDst 2>/dev/null\n")
            }
            sb.append("chmod 660 $escapedDst 2>/dev/null\n")
            sb.append("restorecon -R $escapedDst 2>/dev/null\n")
        }

        sb.append("echo OK\n")
        return sb.toString()
    }

    private fun runWithElevatedBackend(option: Option, mode: Int, onProgress: ((String) -> Unit)? = null): ReplaceResult {
        when (mode) {
            OptionStore.MODE_ADB_ONLY -> {
                if (!adbExecutor.isReady()) {
                    onProgress?.invoke("Connecting to built-in ADB...")
                    adbManager.tryAutoConnect()
                }
                if (!adbExecutor.isReady()) {
                    return ReplaceResult(false, "Built-in ADB not connected. Pair/Connect in settings.", "ADB")
                }
                return executeSingleOption(option, adbExecutor, "ADB", onProgress)
            }

            OptionStore.MODE_SHIZUKU_ONLY -> {
                if (!shizukuExecutor.isReady()) {
                    return ReplaceResult(false, "Shizuku not ready or permission not granted.", "Shizuku")
                }
                return executeSingleOption(option, shizukuExecutor, "Shizuku", onProgress)
            }

            OptionStore.MODE_ROOT_ONLY -> {
                if (!rootExecutor.isReady()) {
                    return ReplaceResult(false, "Root access (su) not available.", "Root")
                }
                return executeSingleOption(option, rootExecutor, "Root", onProgress)
            }

            else -> {
                // AUTO: ADB -> Shizuku -> Root
                val candidateExecutors = mutableListOf<Pair<String, Executor>>()

                // 1. Try ADB
                if (!adbExecutor.isReady() && optionStore.isAdbPaired()) {
                    adbManager.tryAutoConnect()
                }
                if (adbExecutor.isReady()) {
                    candidateExecutors.add(Pair("ADB", adbExecutor))
                }

                // 2. Try Shizuku
                if (shizukuExecutor.isReady()) {
                    candidateExecutors.add(Pair("Shizuku", shizukuExecutor))
                }

                // 3. Try Root
                if (rootExecutor.isReady()) {
                    candidateExecutors.add(Pair("Root", rootExecutor))
                }

                if (candidateExecutors.isEmpty()) {
                    return ReplaceResult(
                        false,
                        "No elevated access. Turn on Wireless debugging and tap Connect, start Shizuku, or use root.",
                        "None"
                    )
                }

                var lastError = "Execution failed"
                for ((name, executor) in candidateExecutors) {
                    onProgress?.invoke("Attempting execution via $name...")
                    val result = executeSingleOption(option, executor, name, onProgress)
                    if (result.success) {
                        return result
                    }
                    lastError = result.message
                    onProgress?.invoke("Notice: $name failed ($lastError). Trying fallback...")
                }
                return ReplaceResult(false, lastError, candidateExecutors.first().first)
            }
        }
    }

    private fun executeSingleOption(
        option: Option,
        executor: Executor,
        backendName: String,
        onProgress: ((String) -> Unit)? = null
    ): ReplaceResult {
        onProgress?.invoke("Executing script via $backendName...")
        var script = buildScript(option, useCatFallback = false)
        var (code, output) = executor.exec(script)

        // If cp into destination failed with permission or denial, retry once with cat
        if (code != 0 && (output.contains("COPY_FAILED") || output.contains("Permission denied", ignoreCase = true))) {
            onProgress?.invoke("cp denied, retrying with cat...")
            script = buildScript(option, useCatFallback = true)
            val retry = executor.exec(script)
            code = retry.first
            output = retry.second
        }

        if (code == 0 && output.contains("OK")) {
            return ReplaceResult(true, "✔ ${option.name} done", backendName)
        }

        val errMsg = when {
            output.contains("SOURCE_NOT_FOUND") -> "Source file not found"
            output.contains("COPY_FAILED") || output.contains("Permission denied", ignoreCase = true) ->
                "Copy failed: check connection/permission"
            output.isNotBlank() -> output.lines().lastOrNull { it.isNotBlank() } ?: "Error $code"
            else -> "Execution failed with code $code"
        }

        return ReplaceResult(false, "✖ $errMsg", backendName)
    }
}
