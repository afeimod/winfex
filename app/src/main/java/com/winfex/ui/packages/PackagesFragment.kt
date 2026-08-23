package com.winfex.ui.packages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.winfex.R
import com.winfex.core.ImageFsInstaller
import com.winfex.databinding.FragmentPackagesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PackagesFragment : Fragment() {

    private var _b: FragmentPackagesBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPackagesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())

        b.refresh.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            ImageFsInstaller.progress.collectLatest { p ->
                if (p.installing) {
                    b.emptyView.visibility = View.GONE
                    b.installProgress.visibility = View.VISIBLE
                    b.tvInstallName.text = p.currentComponent
                    b.tvInstallMsg.text = p.message
                    val pct = if (p.totalCount > 0) (p.currentIndex * 100 / p.totalCount) else 0
                    b.pbInstall.progress = pct
                } else if (p.complete) {
                    b.installProgress.visibility = View.GONE
                    if (p.error != null) {
                        Snackbar.make(b.root, "安装失败: ${p.error}", 5000).show()
                    }
                    updateStatus()
                } else {
                    updateStatus()
                }
            }
        }

        updateStatus()
    }

    private fun updateStatus() {
        val components = ImageFsInstaller.getComponentStatus()
        val installed = components.count { it.installed }
        val total = components.size

        if (ImageFsInstaller.isInstalled()) {
            b.emptyView.visibility = View.GONE
            val adapter = ComponentStatusAdapter(components)
            b.recycler.adapter = adapter
        } else {
            b.emptyView.visibility = View.VISIBLE
            b.recycler.adapter = ComponentStatusAdapter(components)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
