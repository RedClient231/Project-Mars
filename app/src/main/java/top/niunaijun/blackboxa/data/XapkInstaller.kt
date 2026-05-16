package top.niunaijun.blackboxa.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment

/**
 * Handles XAPK file import for Project Mars / NewBlackbox.
 *
 * XAPK is a ZIP archive that can contain:
 * - Type A (supported): One APK + optional OBB files
 * - Type B (not supported): Multiple split APKs (base.apk + split_config.*.apk)
 *
 * Phase 1: Supports single APK + OBB only.
 * Split XAPK is detected and rejected with a clear message.
 */
object XapkInstaller {

    private const val TAG = "XapkInstaller"

    data class Result(
        val success: Boolean,
        val packageName: String? = null,
        val message: String
    )

    /**
     * Checks whether the given source string refers to an XAPK file.
     * The source can be a file path or a content:// Uri string.
     */
    fun isXapkSource(context: Context, source: String): Boolean {
        val lower = source.lowercase()
        if (lower.endsWith(".xapk")) return true

        // Check display name from content resolver for Uri sources
        if (URLUtil.isValidUrl(source)) {
            try {
                val uri = Uri.parse(source)
                val displayName = queryDisplayName(context, uri)?.lowercase()
                return displayName?.endsWith(".xapk") == true
            } catch (e: Exception) {
                Log.w(TAG, "Could not query display name for Uri: ${e.message}")
            }
        }

        return false
    }

    /**
     * Installs an XAPK file into the virtual environment.
     *
     * Steps:
     * 1. Copy source to a temporary .xapk file in cacheDir.
     * 2. Open it as ZipFile.
     * 3. Find APK entries inside the ZIP.
     * 4. If zero APKs: return failure.
     * 5. If more than one APK: return split-APK-not-supported error.
     * 6. Extract the single APK to a temp file.
     * 7. Install extracted APK using BlackBoxCore.
     * 8. Copy matching OBB files into BEnvironment.getExternalObbDir().
     * 9. Clean up temp files.
     * 10. Return result.
     */
    fun installXapk(context: Context, source: String, userId: Int): Result {
        val workDir = File(context.cacheDir, "xapk-${UUID.randomUUID()}")
        workDir.mkdirs()

        return try {
            // Step 1: Copy source to temp file
            val xapkFile = File(workDir, "source.xapk")
            copySourceToFile(context, source, xapkFile)

            // Step 2: Open as ZIP
            val zip = ZipFile(xapkFile)
            zip.use { zipFile ->
                // Step 3: Find APK entries
                val apkEntries = zipFile.entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                    .toList()

                // Step 4: No APK found
                if (apkEntries.isEmpty()) {
                    return Result(false, message = "No APK found inside XAPK file.")
                }

                // Step 5: Multiple APKs = split XAPK, not supported in Phase 1
                if (apkEntries.size > 1) {
                    val apkNames = apkEntries.joinToString(", ") { it.name }
                    Log.w(TAG, "Split APK XAPK detected: $apkNames")
                    return Result(
                        false,
                        message = "Split APK XAPK detected ($apkNames). " +
                                "Current version supports only single APK + OBB XAPK. " +
                                "Split APK support requires Bcore package model changes."
                    )
                }

                // Step 6: Extract the single APK
                val apkEntry = apkEntries.first()
                val apkFile = File(workDir, "base.apk")
                extractEntry(zipFile, apkEntry, apkFile)

                Log.d(TAG, "Extracted APK: ${apkEntry.name} (${apkEntry.size} bytes)")

                // Step 7: Install APK into virtual environment
                val installResult = BlackBoxCore.get()
                    .installPackageAsUser(apkFile, userId)

                if (!installResult.success) {
                    return Result(
                        false,
                        message = "XAPK APK install failed: ${installResult.msg}"
                    )
                }

                val packageName = installResult.packageName
                Log.d(TAG, "APK installed successfully: $packageName")

                // Step 8: Copy OBB files
                val copiedObbCount = copyObbEntries(zipFile, packageName, userId)
                Log.d(TAG, "OBB files copied: $copiedObbCount for package: $packageName")

                // Step 9 & 10: Return success
                Result(
                    true,
                    packageName,
                    "XAPK installed successfully. Package: $packageName. OBB files copied: $copiedObbCount"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "XAPK install failed", t)
            Result(false, message = "XAPK install failed: ${t.message}")
        } finally {
            // Step 9: Clean up temp files
            try {
                workDir.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete temp dir: ${e.message}")
            }
        }
    }

    /**
     * Copies OBB entries from the XAPK into the virtual OBB directory.
     *
     * OBB files are identified by .obb extension.
     * Only OBB files whose filename contains the installed package name are copied.
     *
     * Accepted OBB patterns:
     * - main.<version>.<package>.obb
     * - patch.<version>.<package>.obb
     * - Android/obb/<package>/main.<version>.<package>.obb
     * - Android/obb/<package>/patch.<version>.<package>.obb
     */
    private fun copyObbEntries(zipFile: ZipFile, packageName: String, userId: Int): Int {
        val obbDir = BEnvironment.getExternalObbDir(packageName, userId)
        obbDir.mkdirs()

        var count = 0
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue

            val fileName = File(entry.name).name
            val lower = fileName.lowercase()

            // Check if this is an OBB file
            if (!lower.endsWith(".obb")) continue

            // Only copy OBB files matching the installed package name
            // Pattern: main.123.com.example.game.obb or patch.123.com.example.game.obb
            if (!fileName.contains(".$packageName.")) {
                Log.d(TAG, "Skipping OBB not matching package $packageName: $fileName")
                continue
            }

            val outFile = File(obbDir, fileName)
            Log.d(TAG, "Copying OBB: $fileName -> ${outFile.absolutePath}")
            extractEntry(zipFile, entry, outFile)
            count++
        }
        return count
    }

    /**
     * Extracts a single ZipEntry to an output file using streaming copy.
     */
    private fun extractEntry(zipFile: ZipFile, entry: ZipEntry, outFile: File) {
        outFile.parentFile?.mkdirs()
        zipFile.getInputStream(entry).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
            }
        }
    }

    /**
     * Copies the source XAPK to a temporary file.
     * Source can be a content:// Uri or a local file path.
     */
    private fun copySourceToFile(context: Context, source: String, outFile: File) {
        if (URLUtil.isValidUrl(source)) {
            val uri = Uri.parse(source)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open XAPK Uri: $source" }
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
        } else {
            // Source is a local file path
            File(source).inputStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
        }
    }

    /**
     * Queries the display name of a content:// Uri using ContentResolver.
     */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query display name: ${e.message}")
            null
        }
    }
}
