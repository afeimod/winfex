package com.winfex.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.winfex.R
import com.winfex.core.GameLibrary
import com.winfex.core.InputController
import com.winfex.core.ImageFsInstaller
import com.winfex.core.ShortcutImporter
import com.winfex.core.WinfexPaths
import com.winfex.core.WinePrefixManager
import com.winfex.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var navDrawer: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer)
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottom_nav)
        navDrawer = findViewById(R.id.nav_drawer_inner)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }

        // FragmentContainerView 作为 NavHost 时，需要通过 FragmentManager 获取 NavController
        // 直接用 findNavController(R.id.nav_host) 会崩溃：
        // "does not have a NavController set on <id>"
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController
        val appBarConfig = AppBarConfiguration(
            setOf(
                R.id.libraryFragment,
                R.id.prefixesFragment,
                R.id.packagesFragment,
                R.id.inputFragment,
                R.id.shortcutsFragment
            ),
            drawer
        )
        toolbar.setupWithNavController(navController, appBarConfig)
        bottomNav.setupWithNavController(navController)

        navDrawer.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.drawer_settings -> SettingsActivity.start(this)
                R.id.drawer_about -> showAbout()
                R.id.drawer_logs -> {
                    android.widget.Toast.makeText(this,
                        "日志目录: ${WinfexPaths.logsDir.absolutePath}",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            drawer.closeDrawer(GravityCompat.START); true
        }

        bootstrap()
    }

    private fun bootstrap() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 加载所有仓库
            WinePrefixManager.loadAll()
            GameLibrary.loadAll()
            InputController.loadAll()
            ShortcutImporter.loadAll()
            // RatPackageManager.warmup() 已在 Application.onCreate 调用，
            // 但它可能阻塞，这里再检查一次缺失
            checkMissingPackages()
        }
    }

    private fun checkMissingPackages() {
        val missing = emptyList<String>()
        if (missing.isEmpty()) return

        lifecycleScope.launch(Dispatchers.Main) {
            val map = mapOf(
                "Core" to "Core 运行时库（libc++, X11, PulseAudio, Zink）",
                "Wine" to "Wine（x86_64 ELF，由 Box64 翻译执行）",
                "Box64" to "Box64 翻译器（ARM64 设备必需）",
                "DXVK" to "DXVK（DirectX 9/10/11 → Vulkan）",
                "VulkanDriver" to "Vulkan 驱动（Turnip 或系统 wrapper）"
            )
            val msg = missing.joinToString("\n\n") { "• ${map[it] ?: it}" }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.native_missing_title)
                .setMessage(getString(R.string.native_missing_message, msg))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun showAbout() {
        val sb = StringBuilder()
        sb.append("Winfex v0.1.0\n\n")
        sb.append("Android PC 模拟器壳子\n")
        sb.append("基于 Wine + Box64 + DXVK + Turnip + PulseAudio\n")
        sb.append("参考 MiceWine 架构（.rat 包 + bionic libc 交叉编译）\n\n")
        sb.append("包名: com.winfex\n")
        sb.append("设备 ABI: ${WinfexPaths.deviceAbi}\n\n")
        sb.append("私有目录:\n${WinfexPaths.baseDir.absolutePath}\n\n")
        val pkgs = ImageFsInstaller.getComponentStatus()
        sb.append("已安装包: ${pkgs.size} 个\n")
        sb.append("已创建前缀: ${WinePrefixManager.prefixes.value.size} 个\n")

        MaterialAlertDialogBuilder(this)
            .setTitle("关于 Winfex")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> SettingsActivity.start(this)
        }
        return super.onOptionsItemSelected(item)
    }
}
