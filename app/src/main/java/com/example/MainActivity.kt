package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: OptionStore
    private lateinit var adbManager: AdbManager
    private lateinit var shizukuExecutor: ShizukuExecutor
    private lateinit var optionAdapter: OptionAdapter

    private val adbConnectionListener = object : AdbManager.ConnectionStateListener {
        override fun onConnectionChanged(connected: Boolean) {
            runOnUiThread { updateStatusCard() }
        }
    }

    private val shizukuStateListener = object : ShizukuExecutor.ShizukuStateListener {
        override fun onShizukuStateChanged() {
            runOnUiThread { updateStatusCard() }
        }
    }

    private val editOptionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadOptionsList()
            }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                result.data?.data?.let { uri ->
                    importFromUri(uri)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        store = OptionStore.getInstance(this)
        adbManager = AdbManager.getInstance(this)
        shizukuExecutor = ShizukuExecutor.getInstance(this)

        setupStatusCard()
        setupModeSelector()
        setupFloatingSwitch()
        setupOptionsRecyclerView()

        adbManager.addListener(adbConnectionListener)
        shizukuExecutor.addListener(shizukuStateListener)
    }

    override fun onResume() {
        super.onResume()
        updateStatusCard()
        loadOptionsList()
        binding.switchFloatingService.isChecked = store.isFloatingServiceEnabled()
    }

    override fun onDestroy() {
        super.onDestroy()
        adbManager.removeListener(adbConnectionListener)
        shizukuExecutor.removeListener(shizukuStateListener)
        adbManager.stopMdnsDiscovery()
    }

    private fun setupStatusCard() {
        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnGrantAllFiles.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        binding.btnPairAdb.setOnClickListener { showPairAdbDialog() }

        binding.btnConnectAdb.setOnClickListener { showConnectAdbDialog() }

        binding.btnDevOptions.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Developer options", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuRequest.setOnClickListener {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(0)
                } else {
                    Toast.makeText(this, "Shizuku permission already granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Shizuku is not running", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuOpen.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Shizuku app not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatusCard() {
        // 1. Overlay
        val hasOverlay = Settings.canDrawOverlays(this)
        setIndicatorDot(binding.dotOverlay, hasOverlay)
        binding.tvOverlayStatus.text = if (hasOverlay) getString(R.string.status_granted) else getString(R.string.status_missing)
        binding.btnGrantOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE

        // 2. All files
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        setIndicatorDot(binding.dotAllFiles, hasAllFiles)
        binding.tvAllFilesStatus.text = if (hasAllFiles) getString(R.string.status_granted) else getString(R.string.status_missing)
        binding.btnGrantAllFiles.visibility = if (hasAllFiles) View.GONE else View.VISIBLE

        // 3. ADB
        val isAdbConnected = adbManager.isConnected
        val isAdbPaired = store.isAdbPaired()
        if (isAdbConnected) {
            setIndicatorDot(binding.dotAdb, true)
            binding.tvAdbStatus.text = getString(R.string.adb_status_connected)
            binding.tvAdbStatus.setTextColor(Color.parseColor("#10B981"))
        } else if (isAdbPaired) {
            setIndicatorDot(binding.dotAdb, false, warning = true)
            binding.tvAdbStatus.text = getString(R.string.adb_status_disconnected)
            binding.tvAdbStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            setIndicatorDot(binding.dotAdb, false)
            binding.tvAdbStatus.text = getString(R.string.adb_status_unpaired)
            binding.tvAdbStatus.setTextColor(Color.parseColor("#F43F5E"))
        }

        // 4. Shizuku
        val isShizukuInstalled = shizukuExecutor.isShizukuInstalled()
        val isShizukuRunning = shizukuExecutor.isShizukuRunning()
        val hasShizukuPerm = shizukuExecutor.hasShizukuPermission()

        when {
            !isShizukuInstalled -> {
                setIndicatorDot(binding.dotShizuku, false)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_status_not_installed)
                binding.tvShizukuStatus.setTextColor(Color.parseColor("#64748B"))
                binding.btnShizukuRequest.visibility = View.GONE
            }
            !isShizukuRunning -> {
                setIndicatorDot(binding.dotShizuku, false, warning = true)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_status_not_running)
                binding.tvShizukuStatus.setTextColor(Color.parseColor("#F59E0B"))
                binding.btnShizukuRequest.visibility = View.GONE
            }
            !hasShizukuPerm -> {
                setIndicatorDot(binding.dotShizuku, false, warning = true)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_status_permission_needed)
                binding.tvShizukuStatus.setTextColor(Color.parseColor("#F59E0B"))
                binding.btnShizukuRequest.visibility = View.VISIBLE
            }
            else -> {
                setIndicatorDot(binding.dotShizuku, true)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_status_ready)
                binding.tvShizukuStatus.setTextColor(Color.parseColor("#10B981"))
                binding.btnShizukuRequest.visibility = View.GONE
            }
        }

        // 5. Root
        val isRoot = RootExecutor().isReady()
        setIndicatorDot(binding.dotRoot, isRoot)
        if (isRoot) {
            binding.tvRootStatus.text = getString(R.string.root_status_available)
            binding.tvRootStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvRootStatus.text = getString(R.string.root_status_unavailable)
            binding.tvRootStatus.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun setIndicatorDot(view: View, ok: Boolean, warning: Boolean = false) {
        val color = when {
            ok -> Color.parseColor("#10B981") // Green
            warning -> Color.parseColor("#F59E0B") // Amber
            else -> Color.parseColor("#F43F5E") // Red
        }
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        view.background = drawable
    }

    private fun setupModeSelector() {
        when (store.getSelectedMode()) {
            OptionStore.MODE_AUTO -> binding.rbModeAuto.isChecked = true
            OptionStore.MODE_ADB_ONLY -> binding.rbModeAdb.isChecked = true
            OptionStore.MODE_SHIZUKU_ONLY -> binding.rbModeShizuku.isChecked = true
            OptionStore.MODE_ROOT_ONLY -> binding.rbModeRoot.isChecked = true
        }

        binding.rgModeSelector.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_mode_adb -> OptionStore.MODE_ADB_ONLY
                R.id.rb_mode_shizuku -> OptionStore.MODE_SHIZUKU_ONLY
                R.id.rb_mode_root -> OptionStore.MODE_ROOT_ONLY
                else -> OptionStore.MODE_AUTO
            }
            store.setSelectedMode(mode)
        }
    }

    private fun setupFloatingSwitch() {
        binding.switchFloatingService.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    buttonView.isChecked = false
                    Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnCheckedChangeListener
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    }
                }

                val serviceIntent = Intent(this, FloatingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                val serviceIntent = Intent(this, FloatingService::class.java).apply {
                    action = FloatingService.ACTION_STOP_SERVICE
                }
                startService(serviceIntent)
            }
        }
    }

    private fun setupOptionsRecyclerView() {
        optionAdapter = OptionAdapter(
            onTest = { option -> testOption(option) },
            onKill = { option -> killOptionTarget(option) },
            onEdit = { option ->
                val intent = Intent(this, EditOptionActivity::class.java).apply {
                    putExtra(EditOptionActivity.EXTRA_OPTION_ID, option.id)
                }
                editOptionLauncher.launch(intent)
            },
            onDelete = { option -> confirmDeleteOption(option) }
        )

        binding.rvOptions.layoutManager = LinearLayoutManager(this)
        binding.rvOptions.adapter = optionAdapter

        binding.btnAddOption.setOnClickListener {
            val intent = Intent(this, EditOptionActivity::class.java)
            editOptionLauncher.launch(intent)
        }
    }

    private fun loadOptionsList() {
        val options = store.getOptions()
        optionAdapter.submitList(options)
        binding.tvEmptyOptions.visibility = if (options.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun killOptionTarget(option: Option) {
        Toast.makeText(this, "Killing process for '${option.name}'...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val replacer = Replacer(this@MainActivity)
            val result = replacer.killApp(option)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testOption(option: Option) {
        Toast.makeText(this, "Running '${option.name}'...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val replacer = Replacer(this@MainActivity)
            val result = replacer.run(option)
            withContext(Dispatchers.Main) {
                val msg = if (result.success) {
                    "${result.message} via ${result.backend}"
                } else {
                    "${result.message} (${result.backend})"
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (result.success) "Success" else "Error")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun confirmDeleteOption(option: Option) {
        AlertDialog.Builder(this)
            .setTitle("Delete Option")
            .setMessage("Are you sure you want to delete '${option.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteOption(option.id)
                loadOptionsList()
                Toast.makeText(this, "Deleted ${option.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPairAdbDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pair_adb, null)
        val etPort = dialogView.findViewById<EditText>(R.id.et_pair_port)
        val etCode = dialogView.findViewById<EditText>(R.id.et_pair_code)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_pair_cancel).setOnClickListener { dialog.dismiss() }

        dialogView.findViewById<View>(R.id.btn_pair_submit).setOnClickListener {
            val portStr = etPort.text?.toString()?.trim() ?: ""
            val code = etCode.text?.toString()?.trim() ?: ""
            val port = portStr.toIntOrNull()

            if (port == null || port <= 0) {
                Toast.makeText(this, "Enter a valid pairing port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (code.length != 6) {
                Toast.makeText(this, "Enter 6-digit pairing code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            postPairNotification(port)

            lifecycleScope.launch(Dispatchers.IO) {
                val success = adbManager.pairDevice("127.0.0.1", port, code)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Pairing successful! Connecting...", Toast.LENGTH_SHORT).show()
                        updateStatusCard()
                        adbManager.tryAutoConnect()
                    } else {
                        Toast.makeText(this@MainActivity, "Pairing failed. Check port & code.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun postPairNotification(port: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("adb_pair_channel", "ADB Pairing", NotificationManager.IMPORTANCE_HIGH)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val remoteInput = RemoteInput.Builder(PairNotificationReceiver.KEY_PAIRING_CODE)
            .setLabel("Enter 6-digit code")
            .build()

        val submitIntent = Intent(this, PairNotificationReceiver::class.java).apply {
            action = PairNotificationReceiver.ACTION_PAIR_SUBMIT
            putExtra(PairNotificationReceiver.EXTRA_PORT, port)
        }
        val pIntent = PendingIntent.getBroadcast(
            this,
            202,
            submitIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_floating,
            "Submit Code",
            pIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, "adb_pair_channel")
            .setSmallIcon(R.drawable.ic_floating)
            .setContentTitle("Wireless Debugging Pairing")
            .setContentText("Pairing port: $port. Enter pairing code:")
            .addAction(action)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(303, notification)
    }

    private fun showConnectAdbDialog() {
        val lastPort = store.getLastAdbPort()

        val dialogView = layoutInflater.inflate(R.layout.dialog_connect_adb, null)
        val etPort = dialogView.findViewById<EditText>(R.id.et_connect_port)
        if (lastPort > 0) etPort.setText(lastPort.toString())

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_connect_cancel).setOnClickListener { dialog.dismiss() }

        dialogView.findViewById<View>(R.id.btn_connect_submit).setOnClickListener {
            val port = etPort.text?.toString()?.trim()?.toIntOrNull()
            if (port == null || port <= 0) {
                Toast.makeText(this, "Enter a valid port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            Toast.makeText(this, "Connecting to 127.0.0.1:$port...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO) {
                val success = adbManager.connectDevice("127.0.0.1", port)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Connected to built-in ADB!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Connection failed. Check Wireless debugging status.", Toast.LENGTH_LONG).show()
                    }
                    updateStatusCard()
                }
            }
        }

        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, R.string.menu_export)
        menu?.add(0, 2, 0, R.string.menu_import)
        menu?.add(0, 3, 0, R.string.menu_setup_guide)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                exportOptions()
                true
            }
            2 -> {
                importOptions()
                true
            }
            3 -> {
                showSetupGuideDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportOptions() {
        val qrDir = File(Environment.getExternalStorageDirectory(), "QuickReplacer")
        qrDir.mkdirs()
        val file = File(qrDir, "options.json")
        val success = store.exportToJson(file)
        if (success) {
            Toast.makeText(this, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Export failed. Check storage permissions.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importOptions() {
        val qrFile = File(Environment.getExternalStorageDirectory(), "QuickReplacer/options.json")
        if (qrFile.exists()) {
            val success = store.importFromJson(qrFile)
            if (success) {
                loadOptionsList()
                Toast.makeText(this, "Imported from ${qrFile.absolutePath}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        importFileLauncher.launch(intent)
    }

    private fun importFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                val temp = File(cacheDir, "import_temp.json")
                temp.writeText(text)
                if (store.importFromJson(temp)) {
                    loadOptionsList()
                    Toast.makeText(this, "Options imported successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid options JSON", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSetupGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_guide_title)
            .setMessage(R.string.setup_guide_content)
            .setPositiveButton("Open Dev Options") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (ignored: Exception) {}
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
