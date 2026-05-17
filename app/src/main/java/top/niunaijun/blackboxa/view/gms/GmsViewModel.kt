package top.niunaijun.blackboxa.view.gms

import android.content.Context
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

    /** Launch test result */
    val mLaunchTestLiveData = MutableLiveData<GmsCore.LaunchTestResult>()

    /** Runtime diagnostic copy result */
    val mRuntimeDiagnosticLiveData = MutableLiveData<String>()

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
}
