package top.niunaijun.blackboxa.data

import android.content.Context
import android.net.Uri
import android.os.Build
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
import top.niunaijun.blackbox.entity.pm.InstallOption

/**
 * Handles XAPK file import for Project Mars / NewBlackbox.
 *
 * XAPK is a ZIP archive that can contain:
 * - Type A: One APK + optional OBB files (fully supported)
 * - Type B: One base APK + ABI split APKs + optional OBB files (supported)
 * - Type C: One base APK + resource/language/density split APKs (NOT supported)
 *
 * ABI-split XAPK example:
 *   com.skgames.trafficrider.apk   (base APK with code/resources)
 *   config.armeabi_v7a.apk          (native libraries for ARMv7)
 *
 * For ABI splits, we:
 * 1. Install the base APK with ABI check skipped (libs are in the split, not base)
 * 2. Extract native .so files from the ABI split APK
 * 3. Copy those .so files into the virtual app lib directory
 * 4. Copy OBB files as usual
 */
object XapkInstaller {

    private const val TAG = "XapkInstaller"

    data class Result(
        val success: Boolean,
        val packageName: String? = null,
        val message: String
    )

    /** Known ABI split APK filename prefixes. */
    private val ABI_SPLIT_PREFIXES = listOf(
        "config.armeabi_v7a",
        "config.arm64_v8a",
        "config.x86",
        "config.x86_64",
        "split_config.armeabi_v7a",
        "split_config.arm64_v8a",
        "split_config.x86",
        "split_config.x86_64"
    )

    /** Mapping from split APK filename convention to Android ABI string. */
    private val SPLIT_NAME_TO_ABI = mapOf(
        "armeabi_v7a" to "armeabi-v7a",
        "arm64_v8a" to "arm64-v8a",
        "x86" to "x86",
        "x86_64" to "x86_64"
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
     * Classification logic:
     * - Exactly one APK -> single APK + OBB path (existing behavior)
     * - One base APK + ABI splits only -> ABI split install path
     * - Any non-ABI split APKs present -> rejected with clear message
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
                // Step 3: Find and classify APK entries
                val apkEntries = zipFile.entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                    .toList()

                if (apkEntries.isEmpty()) {
                    return Result(false, message = "No APK found inside XAPK file.")
                }

                // Classify APKs into base, ABI splits, and unsupported splits
                val baseApks = apkEntries.filter { isBaseApkEntry(it) }
                val abiSplits = apkEntries.filter { isAbiSplitEntry(it) }
                val unsupportedSplits = apkEntries.filter {
                    !isBaseApkEntry(it) && !isAbiSplitEntry(it)
                }

                if (baseApks.size != 1) {
                    val apkNames = apkEntries.joinToString(", ") { it.name }
                    return Result(
                        false,
                        message = "XAPK must contain exactly one base APK. Found: $apkNames"
                    )
                }

                if (unsupportedSplits.isNotEmpty()) {
                    val splitNames = unsupportedSplits.joinToString(", ") { it.name }
                    Log.w(TAG, "Unsupported split APKs detected: $splitNames")
                    return Result(
                        false,
                        message = "Resource/language/density split APK detected ($splitNames). " +
                                "ABI-only split XAPK is supported, but full split APK " +
                                "resource support is not implemented yet."
                    )
                }

                val baseApkEntry = baseApks.first()
                val baseApkFile = File(workDir, "base.apk")
                extractEntry(zipFile, baseApkEntry, baseApkFile)
                Log.d(TAG, "Extracted base APK: ${baseApkEntry.name} (${baseApkEntry.size} bytes)")

                // Step: Install base APK -- choose path based on whether ABI splits exist
                val installResult = if (abiSplits.isEmpty()) {
                    // Single APK + OBB path (existing behavior)
                    Log.d(TAG, "Single APK XAPK -- installing normally")
                    BlackBoxCore.get().installPackageAsUser(baseApkFile, userId)
                } else {
                    // ABI split path -- skip ABI check on base APK
                    val splitNames = abiSplits.joinToString(", ") { it.name }
                    Log.d(TAG, "ABI split XAPK detected -- splits: $splitNames")

                    // Select the best ABI split for this device
                    val selectedSplit = chooseBestAbiSplit(abiSplits)
                        ?: return Result(
                            false,
                            message = "XAPK contains ABI splits ($splitNames) but none " +
                                    "match this device's supported ABIs " +
                                    "(${Build.SUPPORTED_ABIS.joinToString(", ")}). " +
                                    "This device may not support the native architecture " +
                                    "required by this app."
                        )

                    val selectedAbi = extractAbiFromSplitName(selectedSplit.name)!!
                    Log.d(TAG, "Selected ABI split: ${selectedSplit.name} -> $selectedAbi")

                    // Install base APK with ABI check skipped
                    BlackBoxCore.getBPackageManager().installPackageAsUser(
                        baseApkFile.absolutePath,
                        InstallOption.installByStorage().skipAbiCheck(),
                        userId
                    )
                }

                if (!installResult.success) {
                    return Result(
                        false,
                        message = "XAPK base APK install failed: ${installResult.msg}"
                    )
                }

                val packageName = installResult.packageName
                Log.d(TAG, "Base APK installed successfully: $packageName")

                // Step: Copy native libs from ABI split APKs
                var copiedLibCount = 0
                if (abiSplits.isNotEmpty()) {
                    val selectedSplit = chooseBestAbiSplit(abiSplits)!!
                    val selectedAbi = extractAbiFromSplitName(selectedSplit.name)!!

                    // Extract the ABI split APK to a temp file
                    val splitApkFile = File(workDir, selectedSplit.name.replace("/", "_"))
                    extractEntry(zipFile, selectedSplit, splitApkFile)

                    // Copy native .so files from split into virtual lib dir
                    copiedLibCount = copyNativeLibsFromSplit(splitApkFile, packageName, selectedAbi)
                    Log.d(TAG, "Copied $copiedLibCount native libs from ${selectedSplit.name} for package $packageName")
                }

                // Step: Copy OBB files
                val copiedObbCount = copyObbEntries(zipFile, packageName, userId)
                Log.d(TAG, "OBB files copied: $copiedObbCount for package: $packageName")

                // Build result message
                var resultMsg = "XAPK installed successfully. Package: $packageName."
                if (abiSplits.isNotEmpty()) {
                    resultMsg += " ABI split native libs copied: $copiedLibCount."
                }
                resultMsg += " OBB files copied: $copiedObbCount."

                Result(true, packageName, resultMsg)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "XAPK install failed", t)
            Result(false, message = "XAPK install failed: ${t.message}")
        } finally {
            try {
                workDir.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete temp dir: ${e.message}")
            }
        }
    }

    // ==================== APK Classification ====================

    /**
     * Determines if a ZIP entry is a base APK (not a split).
     *
     * An APK is considered "base" if:
     * - Its filename is "base.apk", OR
     * - Its filename does NOT start with "config." or "split_config."
     */
    private fun isBaseApkEntry(entry: ZipEntry): Boolean {
        val fileName = File(entry.name).name.lowercase()
        if (fileName == "base.apk") return true
        // Anything that doesn't look like a config/split is a base candidate
        return !fileName.startsWith("config.") && !fileName.startsWith("split_config.")
    }

    /**
     * Determines if a ZIP entry is an ABI split APK.
     *
     * Recognized ABI split patterns:
     * - config.armeabi_v7a.apk
     * - config.arm64_v8a.apk
     * - config.x86.apk
     * - config.x86_64.apk
     * - split_config.armeabi_v7a.apk
     * - split_config.arm64_v8a.apk
     * - split_config.x86.apk
     * - split_config.x86_64.apk
     */
    private fun isAbiSplitEntry(entry: ZipEntry): Boolean {
        val fileName = File(entry.name).name.lowercase()
        return ABI_SPLIT_PREFIXES.any { prefix -> fileName.startsWith(prefix) && fileName.endsWith(".apk") }
    }

    // ==================== ABI Selection ====================

    /**
     * Extracts the ABI string from a split APK filename.
     *
     * Examples:
     * - "config.armeabi_v7a.apk" -> "armeabi-v7a"
     * - "split_config.arm64_v8a.apk" -> "arm64-v8a"
     */
    private fun extractAbiFromSplitName(fileName: String): String? {
        val name = File(fileName).name.lowercase()
            .removeSuffix(".apk")

        // Find the ABI part after the last dot
        val abiPart = name.substringAfterLast(".")
        return SPLIT_NAME_TO_ABI[abiPart]
    }

    /**
     * Chooses the best ABI split APK for the current device.
     *
     * Uses Build.SUPPORTED_ABIS to find the first split whose ABI
     * is supported by this device. This follows the Android ABI
     * preference order (arm64-v8a preferred over armeabi-v7a on 64-bit).
     *
     * Returns null if no compatible ABI split is found.
     */
    private fun chooseBestAbiSplit(abiSplits: List<ZipEntry>): ZipEntry? {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        Log.d(TAG, "Device supported ABIs: ${supportedAbis.joinToString(", ")}")

        // Build a map of normalized ABI -> ZipEntry
        val splitByAbi = mutableMapOf<String, ZipEntry>()
        for (split in abiSplits) {
            val abi = extractAbiFromSplitName(split.name)
            if (abi != null) {
                splitByAbi[abi] = split
                Log.d(TAG, "ABI split available: ${split.name} -> $abi")
            }
        }

        // Choose the first supported ABI that has a matching split
        for (supportedAbi in supportedAbis) {
            val match = splitByAbi[supportedAbi]
            if (match != null) {
                Log.d(TAG, "Selected ABI: $supportedAbi (from ${match.name})")
                return match
            }
        }

        Log.w(TAG, "No compatible ABI split found. Available: ${splitByAbi.keys}, Device: $supportedAbis")
        return null
    }

    // ==================== Native Lib Copy ====================

    /**
     * Copies native .so files from an ABI split APK into the virtual app lib directory.
     *
     * The split APK is opened as a ZipFile, and entries matching
     * lib/<abi>/*.so are extracted to BEnvironment.getAppLibDir(packageName).
     *
     * This follows the same pattern as Bcore's NativeUtils.copyNativeLib().
     *
     * @param splitApk The extracted ABI split APK file
     * @param packageName The installed package name
     * @param selectedAbi The normalized ABI string (e.g., "armeabi-v7a")
     * @return Number of .so files copied
     */
    private fun copyNativeLibsFromSplit(splitApk: File, packageName: String, selectedAbi: String): Int {
        val libDir = BEnvironment.getAppLibDir(packageName)
        libDir.mkdirs()

        var count = 0
        val libPrefix = "lib/$selectedAbi/"

        try {
            ZipFile(splitApk).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue

                    val entryName = entry.name

                    // Only copy entries matching lib/<abi>/*.so
                    if (!entryName.startsWith(libPrefix)) continue
                    if (!entryName.endsWith(".so")) continue

                    // Extract library name (e.g., "libtraffic.so")
                    val libName = entryName.substring(entryName.lastIndexOf('/') + 1)
                    val outFile = File(libDir, libName)

                    Log.d(TAG, "Copying native lib: $entryName -> ${outFile.absolutePath}")
                    extractEntry(zipFile, entry, outFile)
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying native libs from ABI split: ${e.message}", e)
        }

        return count
    }

    // ==================== OBB Copy ====================

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

    // ==================== Utility Methods ====================

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
