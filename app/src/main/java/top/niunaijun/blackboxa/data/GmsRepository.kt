package top.niunaijun.blackboxa.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.GmsCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.util.getString
import java.io.File


class GmsRepository {

    companion object {
        private const val TAG = "GmsRepository"
    }

    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        BlackBoxCore.get().users.forEach {
            val userId = it.id
            val userName =
                AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: ""
            val isInstalled = BlackBoxCore.get().isInstallGms(userId)
            val bean = GmsBean(userId, userName, isInstalled)
            userList.add(bean)
        }

        mInstalledLiveData.postValue(userList)
    }

    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val installResult = BlackBoxCore.get().installGms(userID)

        val result = if (installResult.success) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, installResult.msg)
        }

        val bean = GmsInstallBean(userID, installResult.success, result)
        mUpdateInstalledLiveData.postValue(bean)
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID, isSuccess, result)

        mUpdateInstalledLiveData.postValue(bean)
    }

    /**
     * Get full GMS diagnostic info including Game Login Readiness.
     * This is meant to be copied/exported by the user without ADB.
     */
    fun getFullDiagnostic(userId: Int): String {
        val diagnosticInfo = GmsCore.getGmsDiagnosticInfo(userId)
        val readinessReport = GmsCore.getGameLoginReadinessReport(userId)
        return diagnosticInfo + "\n" + readinessReport
    }

    /**
     * Check if Play Games is missing for a given user.
     */
    fun isPlayGamesMissing(userId: Int): Boolean {
        return GmsCore.isPlayGamesMissing(userId)
    }

    /**
     * Check if game login is ready for a given user.
     */
    fun isGameLoginReady(userId: Int): Boolean {
        return GmsCore.isGameLoginReady(userId)
    }

    /**
     * Install a Play Games APK or XAPK file.
     * Verifies that the package name is exactly com.google.android.play.games.
     * Returns a result indicating success/failure and a message.
     */
    fun installPlayGames(
        context: Context,
        source: String,
        userId: Int
    ): PlayGamesInstallResult {
        // Route through XapkInstaller or regular install
        if (XapkInstaller.isXapkSource(context, source)) {
            val xapkResult = XapkInstaller.installXapk(context, source, userId)
            if (!xapkResult.success) {
                return PlayGamesInstallResult(
                    success = false,
                    message = getString(R.string.gms_import_failed, xapkResult.message)
                )
            }
            // Verify package name
            val installedPackage = xapkResult.packageName
            if (installedPackage != GmsCore.PLAY_GAMES_PKG) {
                // Wrong package - uninstall it immediately
                if (installedPackage != null) {
                    BlackBoxCore.get().uninstallPackageAsUser(installedPackage, userId)
                }
                return PlayGamesInstallResult(
                    success = false,
                    message = getString(R.string.gms_wrong_package, installedPackage ?: "unknown")
                )
            }
            return PlayGamesInstallResult(
                success = true,
                message = getString(R.string.gms_import_success)
            )
        }

        // Regular APK install
        // First, try to get the package name from the APK before installing
        val packageName = try {
            val filePath = if (source.startsWith("content://") || source.startsWith("file://")) {
                getFilePathFromUri(context, Uri.parse(source))
            } else {
                source
            }
            if (filePath != null) {
                BlackBoxCore.getPackageManager().getPackageArchiveInfo(filePath, 0)?.packageName
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read package name from APK: ${e.message}")
            null
        }

        // If we could read the package name, verify it before installing
        if (packageName != null && packageName != GmsCore.PLAY_GAMES_PKG) {
            return PlayGamesInstallResult(
                success = false,
                message = getString(R.string.gms_wrong_package, packageName)
            )
        }

        // Install the APK
        val installResult = if (source.startsWith("content://") || source.startsWith("file://")) {
            val uri = Uri.parse(source)
            BlackBoxCore.get().installPackageAsUser(uri, userId)
        } else {
            BlackBoxCore.get().installPackageAsUser(source, userId)
        }

        if (!installResult.success) {
            return PlayGamesInstallResult(
                success = false,
                message = getString(R.string.gms_import_failed, installResult.msg)
            )
        }

        // Verify the installed package name
        if (installResult.packageName != GmsCore.PLAY_GAMES_PKG) {
            // Wrong package - uninstall it
            BlackBoxCore.get().uninstallPackageAsUser(installResult.packageName, userId)
            return PlayGamesInstallResult(
                success = false,
                message = getString(R.string.gms_wrong_package, installResult.packageName ?: "unknown")
            )
        }

        return PlayGamesInstallResult(
            success = true,
            message = getString(R.string.gms_import_success)
        )
    }

    /**
     * Try to get a file path from a content URI by copying to cache.
     */
    private fun getFilePathFromUri(context: Context, uri: Uri): String? {
        return try {
            val tempFile = File(context.cacheDir, "play_games_import_${System.currentTimeMillis()}.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy URI to file: ${e.message}")
            null
        }
    }

    data class PlayGamesInstallResult(
        val success: Boolean,
        val message: String
    )
}
