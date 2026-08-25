package com.kyant.amdl.scene

import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.amdl.downloader.Task
import com.kyant.amdl.ui.Indication
import com.kyant.amdl.ui.Palette
import com.kyant.amdl.ui.TopBar
import com.kyant.amdl.ui.verticalGradient
import com.kyant.shapes.RoundedRectangle

@Composable
fun MainScene(appState: AppState) {
    Box(Modifier.fillMaxSize()) {
        val tasks by appState.downloadManager.tasks.collectAsState()
        val failedTasks by appState.downloadManager.failedTasks.collectAsState()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = WindowInsets.safeDrawing.asPaddingValues() +
                    PaddingValues(bottom = 104f.dp) + PaddingValues(16f.dp),
            verticalArrangement = Arrangement.spacedBy(8f.dp)
        ) {
            item {
                TopBar(
                    "AMDL",
                    actionButtonTitle = "设置",
                    onActionButtonClick = { appState.navBackStack += Scene.Settings }
                )
            }

            if (failedTasks.isNotEmpty()) {
                item {
                    BasicText(
                        "失败任务",
                        Modifier.padding(16f.dp, 8f.dp, 0f.dp, 0f.dp),
                        style = TextStyle(Palette.content.copy(0.6f), 16f.sp)
                    )
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8f.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedRectangle(16f.dp))
                                .clickable { appState.downloadManager.retryAllFailedTasks() }
                                .background(Palette.card)
                                .padding(16f.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                "重试全部",
                                style = TextStyle(Palette.content, 16f.sp)
                            )
                        }

                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedRectangle(16f.dp))
                                .clickable { appState.downloadManager.clearAllFailedTasks() }
                                .background(Palette.card)
                                .padding(16f.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                "清除全部",
                                style = TextStyle(Palette.content, 16f.sp)
                            )
                        }
                    }
                }

                items(failedTasks, key = { it.id }) { task ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedRectangle(16f.dp))
                            .clickable { appState.downloadManager.retryTask(task) }
                            .background(Palette.card)
                            .padding(16f.dp),
                        verticalArrangement = Arrangement.spacedBy(2f.dp)
                    ) {
                        BasicText(
                            "${task.track.name} - ${task.track.artistName}",
                            style = TextStyle(Palette.content, 16f.sp)
                        )
                        BasicText(
                            (task.status as? Task.Status.Failed)?.error ?: "未知错误",
                            style = TextStyle(Color.Red, 14f.sp)
                        )
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                item {
                    BasicText(
                        "当前任务",
                        Modifier.padding(16f.dp, 8f.dp, 0f.dp, 0f.dp),
                        style = TextStyle(Palette.content.copy(0.6f), 16f.sp)
                    )
                }

                items(tasks, key = { it.id }) { task ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedRectangle(16f.dp))
                            .background(Palette.card)
                            .padding(16f.dp),
                        verticalArrangement = Arrangement.spacedBy(2f.dp)
                    ) {
                        BasicText(
                            "${task.track.name} - ${task.track.artistName}",
                            style = TextStyle(Palette.content, 16f.sp)
                        )
                        BasicText(
                            when (task.status) {
                                is Task.Status.Pending -> "等待中"
                                is Task.Status.Preparing -> "准备中"
                                is Task.Status.Downloading -> "下载中"
                                is Task.Status.Processing -> "处理中"
                                is Task.Status.Completed -> "已完成"
                                is Task.Status.Failed -> "失败"
                            },
                            style = TextStyle(Palette.content.copy(0.6f), 14f.sp)
                        )
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter),
            Alignment.BottomCenter
        ) {
            Column {
                val color = Palette.background.copy(0.9f)
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(104f.dp)
                        .verticalGradient(color, 0f, 1f, EaseOut)
                )
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.safeDrawing)
                        .background(color)
                )
            }

            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(32f.dp, 16f.dp)
                    .fillMaxWidth()
                    .height(56f.dp)
                    .clip(RoundedRectangle(16f.dp))
                    .clickable(
                        interactionSource = null,
                        indication = Indication(Color.White)
                    ) { appState.parseFromClipboard() }
                    .background(Palette.accent)
                    .padding(horizontal = 16f.dp),
                Alignment.Center
            ) {
                BasicText(
                    "从剪贴板解析链接",
                    style = TextStyle(Color.White, 16f.sp, textAlign = TextAlign.Center),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1
                )
            }
        }
    }
}
