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

object XapkInstaller {

    private const val TAG = "XapkInstaller"

    data class Result(
        val success: Boolean,
        val packageName: String? = null,
        val message: String
    )

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

    private val SPLIT_NAME_TO_ABI = mapOf(
        "armeabi_v7a" to "armeabi-v7a",
        "arm64_v8a" to "arm64-v8a",
        "x86" to "x86",
        "x86_64" to "x86_64"
    )

    fun isXapkSource(context: Context, source: String): Boolean {
        val lower = source.lowercase()
        if (lower.endsWith(".xapk")) return true
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

    fun installXapk(context: Context, source: String, userId: Int): Result {
        val workDir = File(context.cacheDir, "xapk-${UUID.randomUUID()}")
        workDir.mkdirs()

        return try {
            val xapkFile = File(workDir, "source.xapk")
            copySourceToFile(context, source, xapkFile)

            val zip = ZipFile(xapkFile)
            zip.use { zipFile ->
                val apkEntries = zipFile.entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                    .toList()

                if (apkEntries.isEmpty()) {
                    return Result(false, message = "No APK found inside XAPK file.")
                }

                val baseApks = apkEntries.filter { isBaseApkEntry(it) }

                if (baseApks.size != 1) {
                    val apkNames = apkEntries.joinToString(", ") { it.name }
                    return Result(
                        false,
                        message = "XAPK must contain exactly one base APK. Found: $apkNames"
                    )
                }

                val baseApkEntry = baseApks.first()
                val splitEntries = apkEntries.filter { !isBaseApkEntry(it) }

                // Create install-set directory where base + splits live side by side.
                // Bcore will detect sibling split APKs in the same directory.
                val installSetDir = File(workDir, "install-set")
                installSetDir.mkdirs()

                // Extract base APK
                val baseApkFile = File(installSetDir, "base.apk")
                extractEntry(zipFile, baseApkEntry, baseApkFile)
                Log.d(TAG, "Extracted base APK: ${baseApkEntry.name} (${baseApkEntry.size} bytes)")

                // Determine if we have ABI splits for native lib extraction
                val abiSplits = splitEntries.filter { isAbiSplitEntry(it) }
                val hasAbiSplits = abiSplits.isNotEmpty()
                val selectedAbiSplit = if (hasAbiSplits) chooseBestAbiSplit(abiSplits) else null

                // Extract ALL split APKs (ABI + resource + density + language) to install-set dir.
                // Bcore will detect them as sibling splits and copy them into virtual storage.
                for ((index, splitEntry) in splitEntries.withIndex()) {
                    // Use safe filename: sanitize the entry name
                    val safeName = splitEntry.name.replace("/", "_")
                    val splitFile = File(installSetDir, safeName)
                    extractEntry(zipFile, splitEntry, splitFile)
                    Log.d(TAG, "Extracted split APK: ${splitEntry.name} -> $safeName (${splitEntry.size} bytes)")
                }

                // Install the base APK. Bcore's BPackageManagerService will detect
                // sibling split APKs in the same directory and attach them automatically.
                val installResult = if (splitEntries.isEmpty()) {
                    // Single APK XAPK -- install normally
                    Log.d(TAG, "Single APK XAPK -- installing normally")
                    BlackBoxCore.get().installPackageAsUser(baseApkFile, userId)
                } else {
                    // Multi-APK XAPK -- install with skipAbiCheck because base APK
                    // may have no native libs (they're in the ABI split)
                    Log.d(TAG, "Multi-APK XAPK with ${splitEntries.size} split(s) -- installing with split support")
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

                // For ABI splits, also extract native libs manually to the virtual lib dir.
                // This is needed because the CopyExecutor's NativeUtils.copyNativeLib only
                // processes the base APK + split APKs after they are copied into virtual storage.
                // But the ABI detection in NativeUtils uses Build.CPU_ABI which may not match
                // the split APK's lib directory structure. So we do it explicitly here too.
                var copiedLibCount = 0
                if (selectedAbiSplit != null) {
                    val selectedAbi = extractAbiFromSplitName(selectedAbiSplit.name)!!
                    Log.d(TAG, "Selected ABI split: ${selectedAbiSplit.name} -> $selectedAbi")

                    val safeName = selectedAbiSplit.name.replace("/", "_")
                    val splitApkFile = File(installSetDir, safeName)
                    copiedLibCount = copyNativeLibsFromSplit(splitApkFile, packageName, selectedAbi)
                    Log.d(TAG, "Copied $copiedLibCount native libs from ${selectedAbiSplit.name} for package $packageName")
                }

                val copiedObbCount = copyObbEntries(zipFile, packageName, userId)
                Log.d(TAG, "OBB files copied: $copiedObbCount for package: $packageName")

                var resultMsg = "XAPK installed successfully. Package: $packageName."
                if (splitEntries.isNotEmpty()) {
                    val splitNames = splitEntries.joinToString(", ") { it.name }
                    resultMsg += " Split APKs: $splitNames."
                }
                if (copiedLibCount > 0) {
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

    private fun isBaseApkEntry(entry: ZipEntry): Boolean {
        val fileName = File(entry.name).name.lowercase()
        if (fileName == "base.apk") return true
        return !fileName.startsWith("config.") && !fileName.startsWith("split_config.")
    }

    private fun isAbiSplitEntry(entry: ZipEntry): Boolean {
        val fileName = File(entry.name).name.lowercase()
        return ABI_SPLIT_PREFIXES.any { prefix ->
            fileName.startsWith(prefix) && fileName.endsWith(".apk")
        }
    }

    private fun extractAbiFromSplitName(fileName: String): String? {
        val name = File(fileName).name.lowercase().removeSuffix(".apk")
        val abiPart = name.substringAfterLast(".")
        return SPLIT_NAME_TO_ABI[abiPart]
    }

    private fun chooseBestAbiSplit(abiSplits: List<ZipEntry>): ZipEntry? {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        Log.d(TAG, "Device supported ABIs: ${supportedAbis.joinToString(", ")}")

        val splitByAbi = mutableMapOf<String, ZipEntry>()
        for (split in abiSplits) {
            val abi = extractAbiFromSplitName(split.name)
            if (abi != null) {
                splitByAbi[abi] = split
                Log.d(TAG, "ABI split available: ${split.name} -> $abi")
            }
        }

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

    private fun copyNativeLibsFromSplit(
        splitApk: File,
        packageName: String,
        selectedAbi: String
    ): Int {
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
                    if (!entryName.startsWith(libPrefix)) continue
                    if (!entryName.endsWith(".so")) continue

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

            if (!lower.endsWith(".obb")) continue
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

    private fun extractEntry(zipFile: ZipFile, entry: ZipEntry, outFile: File) {
        outFile.parentFile?.mkdirs()
        zipFile.getInputStream(entry).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
            }
        }
    }

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
            File(source).inputStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
        }
    }

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
