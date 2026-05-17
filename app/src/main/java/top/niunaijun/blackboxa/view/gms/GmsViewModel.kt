package top.niunaijun.blackboxa.view.gms

import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.core.GmsCore
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.data.GmsRepository
import top.niunaijun.blackboxa.view.base.BaseViewModel


class GmsViewModel(val mRepo: GmsRepository) : BaseViewModel() {

    val mInstalledLiveData = MutableLiveData<List<GmsBean>>()
    val mUpdateInstalledLiveData = MutableLiveData<GmsInstallBean>()
    val mDiagnosticLiveData = MutableLiveData<String>()
    val mPlayGamesImportLiveData = MutableLiveData<GmsRepository.PlayGamesInstallResult>()
    val mReadinessLiveData = MutableLiveData<Pair<Boolean, String>>()

    /** Account diagnostic copy result */
    val mAccountDiagnosticLiveData = MutableLiveData<String>()

    /** Google Authenticator diagnostic copy result */
    val mAuthenticatorDiagnosticLiveData = MutableLiveData<String>()

    /** Launch test result */
    val mLaunchTestLiveData = MutableLiveData<GmsCore.LaunchTestResult>()

    /** Runtime diagnostic copy result */
    val mRuntimeDiagnosticLiveData = MutableLiveData<String>()

    /** Add Google Account result */
    val mAddAccountLiveData = MutableLiveData<AddAccountResult>()

    fun getInstalledUser() {
        launchOnUI {
            mRepo.getGmsInstalledList(mInstalledLiveData)
        }
    }

    fun installGms(userID: Int) {
        launchOnUI {
            mRepo.installGms(userID, mUpdateInstalledLiveData)
        }
    }

    fun uninstallGms(userID: Int) {
        launchOnUI {
            mRepo.uninstallGms(userID, mUpdateInstalledLiveData)
        }
    }

    /**
     * Get the full diagnostic text including Game Login Readiness.
     */
    fun getDiagnostic(userId: Int) {
        launchOnUI {
            val diagnostic = mRepo.getFullDiagnostic(userId)
            mDiagnosticLiveData.postValue(diagnostic)
        }
    }

    /**
     * Check and report Game Login Readiness for a specific user.
     */
    fun checkReadiness(userId: Int) {
        launchOnUI {
            val isReady = mRepo.isGameLoginReady(userId)
            val reportText = mRepo.getFullDiagnostic(userId)
            mReadinessLiveData.postValue(Pair(isReady, reportText))
        }
    }

    /**
     * Import a Play Games APK/XAPK file.
     */
    fun importPlayGames(context: Context, source: String, userId: Int) {
        launchOnUI {
            val result = mRepo.installPlayGames(context, source, userId)
            mPlayGamesImportLiveData.postValue(result)
            // Refresh readiness after import
            checkReadiness(userId)
        }
    }

    /**
     * Get AccountManager diagnostic and copy to clipboard.
     */
    fun getAccountDiagnostic() {
        launchOnUI {
            val diagnostic = mRepo.getAccountDiagnostic()
            mAccountDiagnosticLiveData.postValue(diagnostic)
        }
    }

    /**
     * Get Google Authenticator diagnostic and copy to clipboard.
     */
    fun getAuthenticatorDiagnostic(userId: Int) {
        launchOnUI {
            val diagnostic = mRepo.getGoogleAuthenticatorDiagnostic(userId)
            mAuthenticatorDiagnosticLiveData.postValue(diagnostic)
        }
    }

    /**
     * Test launch Play Games with watchdog.
     * This is a blocking operation — runs on background thread.
     */
    fun testLaunchPlayGames(userId: Int) {
        launchOnUI {
            val result = mRepo.testPlayGamesLaunch(userId)
            mLaunchTestLiveData.postValue(result)
        }
    }

    /**
     * Get comprehensive runtime diagnostic and copy to clipboard.
     */
    fun getRuntimeDiagnostic(userId: Int) {
        launchOnUI {
            val diagnostic = mRepo.getComprehensiveRuntimeDiagnostic(userId)
            mRuntimeDiagnosticLiveData.postValue(diagnostic)
        }
    }

    /**
     * Test adding a Google account using AccountManager.addAccount().
     * This does NOT fake account creation — it attempts to invoke the real
     * Google sign-in flow. If the authenticator service path is broken,
     * this will hang or return an error, which tells us exactly where
     * the problem is.
     */
    fun testAddGoogleAccount(activity: Activity) {
        try {
            val am = AccountManager.get(activity)
            val handler = Handler(Looper.getMainLooper())

            am.addAccount(
                "com.google",
                null,
                null,
                null,
                activity,
                AccountManagerCallback<Bundle> { future: AccountManagerFuture<Bundle> ->
                    try {
                        val result = future.result
                        if (result != null) {
                            val keys = result.keySet()
                            mAddAccountLiveData.postValue(
                                AddAccountResult(
                                    success = true,
                                    message = "addAccount returned a result. Keys: $keys",
                                    detail = "The Google sign-in flow completed. This means the authenticator service path works."
                                )
                            )
                        } else {
                            mAddAccountLiveData.postValue(
                                AddAccountResult(
                                    success = false,
                                    message = "addAccount returned null result.",
                                    detail = "The account flow was cancelled or returned no data."
                                )
                            )
                        }
                    } catch (e: android.accounts.OperationCanceledException) {
                        mAddAccountLiveData.postValue(
                            AddAccountResult(
                                success = false,
                                message = "addAccount was cancelled by user or system.",
                                detail = "OperationCanceledException: ${e.message}"
                            )
                        )
                    } catch (e: android.accounts.AuthenticatorException) {
                        mAddAccountLiveData.postValue(
                            AddAccountResult(
                                success = false,
                                message = "addAccount failed: AuthenticatorException",
                                detail = "AuthenticatorException: ${e.message}\nThis likely means the Google authenticator service cannot be reached or is not bound properly."
                            )
                        )
                    } catch (e: java.io.IOException) {
                        mAddAccountLiveData.postValue(
                            AddAccountResult(
                                success = false,
                                message = "addAccount failed: IOException",
                                detail = "IOException: ${e.message}\nThis may mean a network or IPC communication error with the authenticator."
                            )
                        )
                    } catch (e: Exception) {
                        mAddAccountLiveData.postValue(
                            AddAccountResult(
                                success = false,
                                message = "addAccount failed: ${e.javaClass.simpleName}",
                                detail = "${e.javaClass.simpleName}: ${e.message}"
                            )
                        )
                    }
                },
                handler
            )
        } catch (e: SecurityException) {
            mAddAccountLiveData.postValue(
                AddAccountResult(
                    success = false,
                    message = "addAccount failed: SecurityException",
                    detail = "SecurityException: ${e.message}\nThe app may not have GET_ACCOUNTS permission."
                )
            )
        } catch (e: Exception) {
            mAddAccountLiveData.postValue(
                AddAccountResult(
                    success = false,
                    message = "addAccount failed: ${e.javaClass.simpleName}",
                    detail = "${e.javaClass.simpleName}: ${e.message}"
                )
            )
        }
    }

    /**
     * Result of the Add Google Account test.
     */
    data class AddAccountResult(
        val success: Boolean,
        val message: String,
        val detail: String = ""
    )
}
