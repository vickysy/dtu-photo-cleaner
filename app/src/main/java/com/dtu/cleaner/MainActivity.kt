package com.dtu.cleaner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.dtu.cleaner.ui.AlertAction
import com.dtu.cleaner.ui.FilledButton
import com.dtu.cleaner.ui.IosAlert
import com.dtu.cleaner.util.Fmt
import com.dtu.cleaner.ui.IosType
import com.dtu.cleaner.ui.TextAction
import com.dtu.cleaner.ui.ios
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dtu.cleaner.ui.Access
import com.dtu.cleaner.ui.DtuTheme
import com.dtu.cleaner.ui.GridReviewScreen
import com.dtu.cleaner.ui.SimilarScreen
import com.dtu.cleaner.ui.SceneScreen
import com.dtu.cleaner.ui.HomeScreen
import com.dtu.cleaner.ui.MainViewModel
import com.dtu.cleaner.ui.Screen
import com.dtu.cleaner.ui.SettingsScreen
import com.dtu.cleaner.ui.TrashScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DtuTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(vm) }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** 桌面长按图标 →“看刚拍的” */
    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_RECENT) {
            intent.action = null
            vm.requestRecent()
        }
    }

    companion object {
        const val ACTION_RECENT = "com.dtu.cleaner.RECENT"
    }
}

object Perms {
    fun request(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    fun hasLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun access(ctx: Context): Access = when {
        Build.VERSION.SDK_INT >= 33 -> when {
            granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
            Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
            else -> Access.NONE
        }
        else -> if (granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)) Access.FULL else Access.NONE
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onAccessChanged(Perms.access(ctx))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAccessChanged(Perms.access(ctx)) }

    // 老用户升级上来没有“照片位置”权限：补问一次（多数手机不弹窗，直接给）
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(vm.access) {
        val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (vm.access == Access.FULL && !Perms.hasLocation(ctx) && !prefs.getBoolean("askedLocation", false)) {
            prefs.edit().putBoolean("askedLocation", true).apply()
            locLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onDeleteResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) { for (req in vm.deleteRequests) deleteLauncher.launch(req) }

    // 分析时保持亮屏
    val p by vm.progress.collectAsState()
    val view = LocalView.current
    DisposableEffect(p.running) {
        view.keepScreenOn = p.running
        onDispose { view.keepScreenOn = false }
    }

    if (vm.access == Access.NONE) {
        PermissionScreen(
            onGrant = { permLauncher.launch(Perms.request()) },
            onOpenSettings = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            },
        )
        return
    }

    vm.trashFallback?.let { f ->
        IosAlert(
            title = "这台手机不支持“移到回收站”",
            message = "可以直接删除这 ${f.items.size} 项（${Fmt.bytes(f.items.sumOf { it.size })}），删了就找不回来。也可以先取消，在“设置 → 删除方式”里看看。",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { vm.answerTrashFallback(false) },
                AlertAction("直接删除", AlertAction.Role.Destructive) { vm.answerTrashFallback(true) },
            ),
            onDismiss = { vm.answerTrashFallback(false) },
        )
    }

    when (val s = vm.current) {
        Screen.Home -> HomeScreen(vm, onRequestFullAccess = { permLauncher.launch(Perms.request()) })
        is Screen.Groups -> SimilarScreen(vm, s.session)
        is Screen.Scenes -> SceneScreen(vm, s.session)
        is Screen.Grid -> GridReviewScreen(vm, s.session)
        Screen.Trash -> TrashScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Notes -> com.dtu.cleaner.ui.NotesScreen(vm)
        Screen.Compress -> com.dtu.cleaner.ui.CompressScreen(vm)
        is Screen.VideoScenes -> com.dtu.cleaner.ui.VideoSceneScreen(vm, s.session)
        Screen.Recent -> com.dtu.cleaner.ui.RecentScreen(vm)
        Screen.Docs -> com.dtu.cleaner.ui.DocsScreen(vm)
        is Screen.Album -> com.dtu.cleaner.ui.AlbumScreen(vm, s.session)
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ios.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        com.dtu.cleaner.ui.BrandMark(96.dp)
        Spacer(Modifier.height(24.dp))
        Text("欢迎使用轻了相册", style = IosType.title1, color = ios.label, textAlign = TextAlign.Center)
        Text("清了没？相册轻了，心也轻了", style = IosType.subhead, color = ios.secondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Feature(Icons.Rounded.Collections, ios.brand, "每组只留最好的一张", "连拍、重复保存的照片，自动挑出最清楚、最好看的那张")
        Feature(Icons.Rounded.AutoAwesome, ios.yellow, "每个场景留几张精华", "同一个地方拍了几十张？帮你挑 3–5 张不重复的")
        Feature(Icons.Rounded.Lock, ios.brand, "照片不出手机", "没有联网权限。删除前都会问你，30 天内能恢复")
        Spacer(Modifier.weight(1.3f))
        Text("需要允许访问照片和视频", style = IosType.footnote, color = ios.secondary)
        Spacer(Modifier.height(12.dp))
        FilledButton("继续", onGrant, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        TextAction("弹窗没出来？在系统设置中打开", onOpenSettings, style = IosType.subhead)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = IosType.headline, color = ios.label)
            Text(text, style = IosType.subhead, color = ios.secondary)
        }
    }
}
