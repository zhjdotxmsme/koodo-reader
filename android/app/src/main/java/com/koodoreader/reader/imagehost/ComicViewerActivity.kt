package com.koodoreader.reader.imagehost

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koodoreader.engine.image.ViewerSnapshot
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.I18nState
import com.koodoreader.reader.shell.LocalI18n
import com.koodoreader.reader.shell.ReaderProgressMath
import com.koodoreader.reader.shell.ReaderProgressPrefs
import com.koodoreader.reader.shell.RememberReadingSession
import com.koodoreader.reader.shell.ShellAppearancePrefs
import com.koodoreader.reader.shell.darkThemeOf
import com.koodoreader.reader.shell.t
import com.koodoreader.engine.image.ArchiveExtractors
import com.koodoreader.engine.image.ComicPage
import com.koodoreader.engine.image.ComicViewerModel
import com.koodoreader.engine.image.DefaultPageLoader
import com.koodoreader.reader.imagehost.ComicViewerHost
import com.koodoreader.core.ui.theme.KoodoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 漫画原生阅读入口（P5-CBZ-5, t-mufbaovl）：VIEW/SEND intent 的
 * CBZ/CBT/CB7 原生路由落点。文件由 MainActivity 复制到 cache 后以
 * `EXTRA_FILE` 传入（content:// 的读授权只对调用方进程有效，必须落盘）。
 *
 * 页面加载/翻页落点/缩放全部在 engine:image 纯 Kotlin 侧
 * （[ComicViewerModel] + [DefaultPageLoader] + [ComicViewerHost]）；
 * 这里只做：打开归档 → 位图解码回调（inSampleSize 降采样）→ 组装宿主。
 */
class ComicViewerActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "comic-decode").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)
        if (file == null || !file.isFile) {
            finishWithError("The comic file is unavailable.")
            return
        }

        val i18n = I18nState.create(this)
        val appearance = ShellAppearancePrefs(applicationContext)
        setContent {
            CompositionLocalProvider(LocalI18n provides i18n) {
                val appMode by appearance.appThemeModeFlow.collectAsStateWithLifecycle()
                KoodoTheme(darkTheme = appMode.darkThemeOf(isSystemInDarkTheme())) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ComicViewerScreen(file) { finish() }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ComicViewerScreen(file: File, onClose: () -> Unit) {
        val model = remember(file) {
            runCatching {
                ComicViewerModel(
                    DefaultPageLoader(ArchiveExtractors.open(file), executor = decodeExecutor),
                )
            }.getOrNull()
        }
        if (model == null) {
            // 打不开（损坏/加密/不支持的过滤器如 BCJ2）：给可执行的提示。
            Surface(modifier = Modifier.fillMaxSize()) {
                Text(
                    "This comic archive cannot be opened natively.\n" +
                        "Import it from inside the app to use the web viewer.",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return
        }
        val progressPrefs = remember { ReaderProgressPrefs(this) }
        val progressKey = file.nameWithoutExtension
        RememberReadingSession(progressKey)
        val savedPage = remember(file) {
            progressPrefs.cfiOf(progressKey)
                ?.removePrefix("comic:")
                ?.toIntOrNull()
                ?: 0
        }
        var chrome by remember { mutableStateOf(true) }
        var snap by remember { mutableStateOf<ViewerSnapshot?>(null) }
        LaunchedEffect(snap?.spread?.start, model.pageCount) {
            val page = model.currentIndex
            if (model.pageCount > 0) {
                progressPrefs.save(
                    progressKey,
                    "comic:$page",
                    ReaderProgressMath.fraction(page, model.pageCount),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            ComicViewerHost(
                model = model,
                decoder = ComicPageDecoder { page, targetWidth ->
                    decodePage(page, targetWidth)
                },
                ioScope = ioScope,
                modifier = Modifier.fillMaxSize(),
                onUiToggle = { chrome = !chrome },
                onExitRequested = onClose,
                onSnapshot = { snap = it },
                initialIndex = savedPage,
            )
            if (chrome) {
                TopAppBar(
                    title = {
                        val page = (model.currentIndex + 1).coerceAtLeast(1)
                        Text("${file.nameWithoutExtension} · $page / ${model.pageCount}")
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = t("Back"))
                        }
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                LinearProgressIndicator(
                    progress = snap?.progress ?: 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }
        }
    }

    /**
     * `ComicPage.bytes` → 屏幕位图：按目标宽度算 inSampleSize 降采样，
     * 避免 4K 彩页直接占满堆（ComicViewerHost KDoc 的要求）。
     */
    private suspend fun decodePage(page: ComicPage, targetWidth: Int): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(page.bytes, 0, page.bytes.size, bounds)
            var sample = 1
            var width = bounds.outWidth
            if (width > targetWidth) {
                while (width / 2 >= targetWidth) {
                    sample *= 2
                    width /= 2
                }
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(page.bytes, 0, page.bytes.size, opts)?.asImageBitmap()
        }

    private fun finishWithError(message: String) {
        val layout = FrameLayout(this)
        val text = TextView(this).apply {
            this.text = message
            setPadding(48, 48, 48, 48)
        }
        layout.addView(text)
        setContentView(layout)
        // 给用户一瞬看到提示，然后回到发起方。
        android.os.Handler(mainLooper).postDelayed({ finish() }, 2500)
    }

    override fun onDestroy() {
        ioScope.cancel()
        decodeExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE = "comic_file"

        /** 工厂入口：MainActivity 路由用（带文件直接跳原生漫画屏）。 */
        fun intent(context: android.content.Context, file: File): Intent =
            Intent(context, ComicViewerActivity::class.java)
                .putExtra(EXTRA_FILE, file.absolutePath)
    }
}
