package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.koodoreader.reader.AppLog
import com.koodoreader.reader.R

/**
 * 应用内日志屏 (设置 → 数据 → 日志): 展示 [AppLog] 环形缓冲 —— 含 WebView
 * console 捕获 (pdfjs / EPUB 引擎报错) 与导入/打开书本关键路径. 顶部两个
 * 动作: 复制全文到剪贴板 (方便粘贴回来报障) / 清空.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    // revision 驱动重组: 进入时 +1 取一次快照; "刷新" 再 +1 重取.
    var revision by remember { mutableIntStateOf(0) }
    val lines = remember(revision) { AppLog.dump() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val copiedMsg = t("Copied to clipboard")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${t("Logs")} (${lines.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        revision++
                    }) {
                        Icon(painterResource(R.drawable.ic_restore), contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        if (AppLog.copyToClipboard(context)) {
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = "Copy all")
                    }
                    IconButton(onClick = { AppLog.clear(); revision++ }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    t("No logs yet"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}
