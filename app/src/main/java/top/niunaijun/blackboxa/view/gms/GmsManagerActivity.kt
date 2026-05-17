package top.niunaijun.blackboxa.view.gms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.gms_manager, true)
        initViewModel()

        initRecyclerView()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java]
        showLoading()

        viewModel.mInstalledLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
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
                    mAdapter.replaceAt( index,bean)
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
        }

        viewModel.mDiagnosticLiveData.observe(this) { diagInfo ->
            hideLoading()
            if (diagInfo != null) {
                showDiagnosticDialog(diagInfo)
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

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.add(0, MENU_DIAGNOSTIC, 0, R.string.gms_diagnostic)?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_DIAGNOSTIC) {
            showDiagnosticForUser0()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showDiagnosticForUser0() {
        // Get diagnostic for user 0 (the default user)
        showLoading()
        viewModel.loadDiagnosticInfo(0)
    }

    private fun showDiagnosticDialog(diagInfo: String) {
        MaterialDialog(this).show {
            title(R.string.gms_diagnostic)
            message(text = diagInfo)
            positiveButton(R.string.gms_copy_diagnostic) {
                copyToClipboard(diagInfo)
            }
            negativeButton(R.string.done)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GMS Diagnostic", text)
            clipboard.setPrimaryClip(clip)
            toast(getString(R.string.gms_diagnostic_copied))
        } catch (e: Exception) {
            toast("Failed to copy: ${e.message}")
        }
    }

    private fun installGms(userID: Int, checkbox: Switch){
        MaterialDialog(this).show {
            title(R.string.enable_gms)
            message(R.string.enable_gms_hint)
            positiveButton(R.string.done){
                showLoading()
                viewModel.installGms(userID)
            }
            negativeButton(R.string.cancel){
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private fun uninstallGms(userID: Int, checkbox: Switch){
        MaterialDialog(this).show {
            title(R.string.disable_gms)
            message(R.string.disable_gms_hint)
            positiveButton(R.string.done){
                showLoading()
                viewModel.uninstallGms(userID)
            }
            negativeButton(R.string.cancel){
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }


    companion object{
        private const val MENU_DIAGNOSTIC = 100

        fun start(context: Context){
            val intent = Intent(context,GmsManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}
