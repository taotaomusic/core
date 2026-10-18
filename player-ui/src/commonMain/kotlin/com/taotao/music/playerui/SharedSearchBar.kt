package com.taotao.music.playerui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.taotao.music.playerui.theme.TaotaoMiuixTheme
import com.taotao.music.playerui.theme.TaotaoSpacing
import top.yukonga.miuix.kmp.basic.InputField

/**
 * 搜索栏：Miuix 胶囊输入框 + 品牌色「搜索」动作按钮。
 *
 * 输入框走 Miuix 的 [InputField]——与底部标签栏同一个组件库（2026-10 起成为本项目的
 * 官方搜索输入控件），外观即 HyperOS 的胶囊搜索框：左侧放大镜、右侧一键清空、
 * 未输入时显示占位文案。外层必须包 [TaotaoMiuixTheme]，否则 Miuix 组件读不到
 * 本应用的配色，会退回 HyperOS 默认灰蓝。
 *
 * 动作按钮仍走 Material3，刻意不整行包进 Miuix 主题：那会把「搜索」按钮的按压反馈
 * 与过滚效果一起换成 Miuix 的（MiuixTheme 覆盖 LocalIndication）。按钮色用
 * [PlayerCoral]，与全应用主色一致。
 *
 * 单向数据流：关键词由调用方持有，组件只上报输入、提交与聚焦三种意图。
 */
@Composable
fun SharedSearchBar(
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    /**
     * 进入即自动聚焦并拉起键盘（搜索页用）；首页只把搜索栏当入口，不抢焦点。
     *
     * 实现方式是把 Miuix 的 `expanded` 初值置真——它内部据此请求输入框焦点，
     * 比自己再挂一个 FocusRequester 可靠：`InputField` 内部已经用了自己的
     * FocusRequester，同一个焦点目标上挂两个，先挂的那个会被后挂的覆盖。
     */
    autoFocus: Boolean = false,
) {
    // Miuix 的 `expanded` 语义是「已进入搜索态」：它同时决定占位文案的显隐和内部
    // 焦点请求。这里只让它随焦点单向置真，不给「收起」路径——InputField 在收起且
    // 仍聚焦时会清空关键词，而本项目的搜索结果渲染在搜索栏之外、关键词归页面状态
    // 所有，静默清空会和页面状态打架。
    var expanded by remember { mutableStateOf(autoFocus) }

    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 权重挂在 Box 上：TaotaoMiuixTheme 的 content 不是 RowScope，拿不到 weight。
        Box(Modifier.weight(1f)) {
            TaotaoMiuixTheme {
                InputField(
                    query = keyword,
                    onQueryChange = onKeywordChanged,
                    // Miuix 会把当前关键词回传，调用方已有同一份状态，不再借道传递。
                    onSearch = { onSearch() },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) onFocus() },
                    label = "搜索歌曲或歌手",
                )
            }
        }
        TextButton(onClick = onSearch) { Text("搜索", color = PlayerCoral) }
    }
}
