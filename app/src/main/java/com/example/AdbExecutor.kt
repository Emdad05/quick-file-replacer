package com.example

class AdbExecutor(private val adbManager: AdbManager) : Executor {
    override fun isReady(): Boolean {
        return adbManager.isConnected
    }

    override fun exec(script: String): Pair<Int, String> {
        return adbManager.executeCommand(script)
    }
}
