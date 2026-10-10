package com.example

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val KEY_PAIRING_CODE = "key_pairing_code"
        const val EXTRA_PORT = "extra_port"
        const val ACTION_PAIR_SUBMIT = "com.example.ACTION_PAIR_SUBMIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PAIR_SUBMIT) {
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            val code = remoteInput?.getCharSequence(KEY_PAIRING_CODE)?.toString()?.trim() ?: ""
            val port = intent.getIntExtra(EXTRA_PORT, 0)

            if (code.isNotEmpty() && port > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    val adb = AdbManager.getInstance(context)
                    val success = adb.pairDevice("127.0.0.1", port, code)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "Pairing successful! Connecting...", Toast.LENGTH_SHORT).show()
                            adb.tryAutoConnect()
                        } else {
                            Toast.makeText(context, "Pairing failed. Check code and port.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
