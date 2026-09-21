package com.skyanchor.anynote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.skyanchor.anynote.MainActivity.Companion.EXTRA_NOTE_ID
import com.skyanchor.anynote.data.AnyNoteRepository
import com.skyanchor.anynote.reminder.NotificationHelper
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.AppRoot
import com.skyanchor.anynote.ui.AppRouter
import com.skyanchor.anynote.ui.rememberAppRouter
import com.skyanchor.anynote.ui.theme.AnyNoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnyNoteTheme { AnyNoteRoot(intent) }
        }
    }

    /** 点击通知时把目标备忘录带到前台，而不是重建一个新的任务栈。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    }
}

@Composable
private fun AnyNoteRoot(intent: Intent?) {
    val container = (LocalContext.current.applicationContext as AnyNoteApp).container
    val router = rememberAppRouter()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val state = remember { com.skyanchor.anynote.ui.AppState() }
    val env = remember(router) {
        AppEnv(
            repository = container.repository,
            dataBackup = container.dataBackup,
            router = router,
            state = state,
            notifications = container.notifications,
            resyncAll = { scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.resync() } } },
            toast = { message -> scope.launch { snackbar.showSnackbar(message) } },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 通知权限从"关闭"变为"允许"后必须重建调度，否则此前登记的提醒全部失效（基线 §24）
        env.resyncAll()
        if (!granted) env.toast("未授予通知权限，提醒将无法送达")
    }

    LaunchedEffect(Unit) {
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPrompt) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            env.resyncAll()
        }
    }

    val targetNote = intent?.getStringExtra(EXTRA_NOTE_ID)
    LaunchedEffect(targetNote) {
        if (targetNote != null && env.repository.notes.get(targetNote) != null) {
            router.push(com.skyanchor.anynote.ui.Route.Detail(targetNote))
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AppRoot(env)
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}
