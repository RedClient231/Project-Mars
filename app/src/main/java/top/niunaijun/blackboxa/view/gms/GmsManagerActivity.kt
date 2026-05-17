package top.niunaijun.blackboxa.view.gms

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.databinding.ActivityGmsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity


class GmsManagerActivity : LoadingActivity() {

    private lateinit var viewModel: GmsViewModel

    private lateinit var mAdapter: RVAdapter<GmsBean>

    private val viewBinding: ActivityGmsBinding by inflate()

    /** Track the first virtual user ID for readiness/diagnostic checks */
    private var currentUserId: Int = 0

    /** File picker for importing Play Games APK/XAPK */
    private val playGamesPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importPlayGames(this, it.toString(), currentUserId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.gms_manager, true)
        initViewModel()
        initRecyclerView()
        initActionButtons()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java]
        showLoading()

        viewModel.mInstalledLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
            // Update user ID and refresh readiness
            if (it.isNotEmpty()) {
                currentUserId = it[0].userID
            }
            refreshReadiness()
        }

        viewModel.mUpdateInstalledLiveData.observe(this) { result ->
            if (result == null) {
                return@observe
            }

            val items = mAdapter.getItems()
            for (index in items.indices) {
                val bean = items[index]
                if (bean.userID == result.userID) {
                    if (result.success) {
                        bean.isInstalledGms = !bean.isInstalledGms
                    }
                    mAdapter.replaceAt(index, bean)
                    break
                }
            }

            hideLoading()

            if (result.success) {
                toast(result.msg)
            } else {
                MaterialDialog(this).show {
                    title(R.string.gms_manager)
                    message(text = result.msg)
                    positiveButton(R.string.done)
                }
            }

            // Refresh readiness after install/uninstall
            refreshReadiness()
        }

        viewModel.mDiagnosticLiveData.observe(this) { diagnosticText ->
            // Copy to clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("GMS Diagnostic", diagnosticText)
            clipboard.setPrimaryClip(clip)
            toast(R.string.gms_diagnostic_copied)
        }

        viewModel.mPlayGamesImportLiveData.observe(this) { result ->
            hideLoading()
            MaterialDialog(this).show {
                title(R.string.gms_import_play_games)
                message(text = result.message)
                positiveButton(R.string.done)
            }
        }

        viewModel.mReadinessLiveData.observe(this) { (isReady, _) ->
            updateReadinessUI(isReady)
        }

        viewModel.mAccountDiagnosticLiveData.observe(this) { diagnosticText ->
            hideLoading()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("Account Diagnostic", diagnosticText)
            clipboard.setPrimaryClip(clip)
            toast(R.string.gms_account_diagnostic_copied)
        }

        viewModel.mAuthenticatorDiagnosticLiveData.observe(this) { diagnosticText ->
            hideLoading()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("Authenticator Diagnostic", diagnosticText)
            clipboard.setPrimaryClip(clip)
            toast(R.string.gms_authenticator_diagnostic_copied)
        }

        viewModel.mLaunchTestLiveData.observe(this) { result ->
            hideLoading()
            // Show launch test result in a dialog
            val report = result.toReportString()
            // Also copy to clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("Launch Test", report)
            clipboard.setPrimaryClip(clip)

            MaterialDialog(this).show {
                title(R.string.gms_launch_test)
                message(text = report)
                positiveButton(R.string.done)
            }
        }

        viewModel.mRuntimeDiagnosticLiveData.observe(this) { diagnosticText ->
            hideLoading()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("Runtime Diagnostic", diagnosticText)
            clipboard.setPrimaryClip(clip)
            toast(R.string.gms_runtime_diagnostic_copied)
        }

        viewModel.mAddAccountLiveData.observe(this) { result ->
            hideLoading()
            MaterialDialog(this).show {
                title(R.string.gms_add_google_account)
                message(text = result.message + "\n\n" + result.detail)
                positiveButton(R.string.done)
            }
        }

        viewModel.getInstalledUser()
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<GmsBean>(this, GmsAdapter()).bind(viewBinding.recyclerView)
            .setItemClickListener { view, item, _ ->
                val checkbox = view.findViewById<Switch>(R.id.checkbox)
                if (item.isInstalledGms) {
                    uninstallGms(item.userID, checkbox)
                } else {
                    installGms(item.userID, checkbox)
                }
            }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun initActionButtons() {
        // Copy GMS diagnostic button
        viewBinding.btnCopyDiagnostic.setOnClickListener {
            showLoading()
            viewModel.getDiagnostic(currentUserId)
        }

        // Copy Account diagnostic button
        viewBinding.btnCopyAccountDiagnostic.setOnClickListener {
            showLoading()
            viewModel.getAccountDiagnostic(currentUserId)
        }

        // Copy Authenticator diagnostic button
        viewBinding.btnCopyAuthenticatorDiagnostic.setOnClickListener {
            showLoading()
            viewModel.getAuthenticatorDiagnostic(currentUserId)
        }

        // Test Launch Play Games button
        viewBinding.btnTestLaunchPlayGames.setOnClickListener {
            showLoading()
            toast(R.string.gms_launch_test_starting)
            viewModel.testLaunchPlayGames(currentUserId)
        }

        // Copy Runtime diagnostic button
        viewBinding.btnCopyRuntimeDiagnostic.setOnClickListener {
            showLoading()
            viewModel.getRuntimeDiagnostic(currentUserId)
        }

        // Add Google Account test button
        viewBinding.btnAddGoogleAccount.setOnClickListener {
            showLoading()
            viewModel.testAddGoogleAccount(this)
        }

        // Import Play Games button (bottom)
        viewBinding.btnImportPlayGames2.setOnClickListener {
            launchPlayGamesPicker()
        }

        // Import Play Games button (in warning card)
        viewBinding.btnImportPlayGames.setOnClickListener {
            launchPlayGamesPicker()
        }
    }

    private fun launchPlayGamesPicker() {
        try {
            playGamesPicker.launch("*/*")
        } catch (e: Exception) {
            toast("Failed to open file picker: ${e.message}")
        }
    }

    /**
     * Refresh the Game Login Readiness status.
     */
    private fun refreshReadiness() {
        viewModel.checkReadiness(currentUserId)
    }

    /**
     * Update the UI based on game login readiness.
     * Show warning if Play Games is missing, show success if ready.
     */
    private fun updateReadinessUI(isReady: Boolean) {
        val playGamesMissing = viewModel.mRepo.isPlayGamesMissing(currentUserId)

        if (playGamesMissing) {
            // Show warning
            viewBinding.playGamesWarning.visibility = View.VISIBLE
            viewBinding.readinessStatus.visibility = View.GONE
        } else if (isReady) {
            // Show success but with note that READY ≠ working
            viewBinding.playGamesWarning.visibility = View.GONE
            viewBinding.readinessStatus.visibility = View.VISIBLE
            viewBinding.readinessText.text = getString(R.string.gms_game_login_readiness) + ": " + getString(R.string.gms_ready)
        } else {
            // GMS not installed at all - hide both
            viewBinding.playGamesWarning.visibility = View.GONE
            viewBinding.readinessStatus.visibility = View.GONE
        }
    }

    private fun installGms(userID: Int, checkbox: Switch) {
        MaterialDialog(this).show {
            title(R.string.enable_gms)
            message(R.string.enable_gms_hint)
            positiveButton(R.string.done) {
                showLoading()
                viewModel.installGms(userID)
            }
            negativeButton(R.string.cancel) {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private fun uninstallGms(userID: Int, checkbox: Switch) {
        MaterialDialog(this).show {
            title(R.string.disable_gms)
            message(R.string.disable_gms_hint)
            positiveButton(R.string.done) {
                showLoading()
                viewModel.uninstallGms(userID)
            }
            negativeButton(R.string.cancel) {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GmsManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}
