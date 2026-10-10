package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityEditOptionBinding
import java.io.File
import java.io.FileOutputStream

class EditOptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditOptionBinding
    private lateinit var store: OptionStore
    private var editingOptionId: String? = null

    companion object {
        const val EXTRA_OPTION_ID = "extra_option_id"
    }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                result.data?.data?.let { uri ->
                    val resolved = resolveUriToPath(uri, isDirectory = false)
                    binding.etSource.setText(resolved)
                }
            }
        }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                result.data?.data?.let { uri ->
                    val resolved = resolveUriToPath(uri, isDirectory = true)
                    binding.etSource.setText(resolved)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditOptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = OptionStore.getInstance(this)

        editingOptionId = intent.getStringExtra(EXTRA_OPTION_ID)
        val isEditing = editingOptionId != null

        binding.toolbarEdit.title = if (isEditing) {
            getString(R.string.edit_option_title_edit)
        } else {
            getString(R.string.edit_option_title_new)
        }
        binding.toolbarEdit.setNavigationOnClickListener { finish() }

        if (isEditing) {
            store.getOptions().find { it.id == editingOptionId }?.let { opt ->
                binding.etName.setText(opt.name)
                binding.etSource.setText(opt.source)
                binding.etDestination.setText(opt.destination)
                binding.etPackage.setText(opt.packageName)
                binding.cbForceStop.isChecked = opt.forceStop
                binding.cbBackup.isChecked = opt.backup
                binding.cbFixPermissions.isChecked = opt.fixPermissions
            }
        }

        binding.btnBrowseFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            openDocumentLauncher.launch(intent)
        }

        binding.btnPickFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            openDocumentTreeLauncher.launch(intent)
        }

        // Auto-detect package name from destination
        binding.etDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val dst = s?.toString() ?: ""
                val detected = Replacer.extractPackageName(dst)
                if (detected.isNotBlank() && binding.etPackage.text.isNullOrBlank()) {
                    binding.etPackage.setText(detected)
                }
            }
        })

        binding.btnCancel.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener {
            val name = binding.etName.text?.toString()?.trim() ?: ""
            val src = binding.etSource.text?.toString()?.trim() ?: ""
            val dst = binding.etDestination.text?.toString()?.trim() ?: ""
            var pkg = binding.etPackage.text?.toString()?.trim() ?: ""

            if (name.isEmpty()) {
                binding.tilName.error = "Option name is required"
                return@setOnClickListener
            } else {
                binding.tilName.error = null
            }

            if (src.isEmpty()) {
                binding.tilSource.error = "Source path is required"
                return@setOnClickListener
            } else {
                binding.tilSource.error = null
            }

            if (dst.isEmpty()) {
                binding.tilDestination.error = "Destination path is required"
                return@setOnClickListener
            } else {
                binding.tilDestination.error = null
            }

            if (pkg.isEmpty()) {
                pkg = Replacer.extractPackageName(dst)
            }

            val option = Option(
                id = editingOptionId ?: java.util.UUID.randomUUID().toString(),
                name = name,
                source = src,
                destination = dst,
                forceStop = binding.cbForceStop.isChecked,
                packageName = pkg,
                backup = binding.cbBackup.isChecked,
                fixPermissions = binding.cbFixPermissions.isChecked
            )

            store.updateOption(option)
            Toast.makeText(this, "Saved: $name", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun resolveUriToPath(uri: Uri, isDirectory: Boolean): String {
        try {
            val docId = if (isDirectory) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }

            if (docId != null && docId.startsWith("primary:")) {
                val relativePath = docId.removePrefix("primary:")
                val file = File(Environment.getExternalStorageDirectory(), relativePath)
                return file.absolutePath
            }
        } catch (ignored: Exception) {}

        // Fallback: Copy content URI to /sdcard/QuickReplacer/
        return try {
            val qrDir = File(Environment.getExternalStorageDirectory(), "QuickReplacer")
            qrDir.mkdirs()
            val fileName = getFileNameFromUri(uri) ?: "source_file_${System.currentTimeMillis()}"
            val targetFile = File(qrDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            uri.path ?: uri.toString()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (ignored: Exception) {}
        return name
    }
}
