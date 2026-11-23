package com.koodoreader.reader.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.t

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderTocPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // 阅读路由没有外层 insets 避让：显式 statusBarsPadding +
            // 置零 TopAppBar 自带 windowInsets（与 ReaderTopBar 同款组合）。
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(SpaceTokens.TOP_BAR_HEIGHT.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.koodoPressScale()) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = title)
                    }
                },
            )
        },
        content = { scaffoldPadding ->
            Box(
                Modifier
                    .padding(scaffoldPadding)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
            ) {
                content(
                    PaddingValues(
                        horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
                        vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp,
                    ),
                )
            }
        },
    )
}
