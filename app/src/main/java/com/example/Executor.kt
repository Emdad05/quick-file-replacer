package com.example

interface Executor {
    fun isReady(): Boolean
    /** Runs a shell script string, returns Pair(exitCode, outputText). Never throws; returns exitCode -1 on failure. */
    fun exec(script: String): Pair<Int, String>
}
