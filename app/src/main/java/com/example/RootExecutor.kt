package com.example

import com.topjohnwu.superuser.Shell

class RootExecutor : Executor {

    override fun isReady(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
    }

    override fun exec(script: String): Pair<Int, String> {
        return try {
            val result = Shell.cmd(script).exec()
            val output = (result.out + result.err).joinToString("\n").trim()
            Pair(result.code, output)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Root execution error")
        }
    }
}
