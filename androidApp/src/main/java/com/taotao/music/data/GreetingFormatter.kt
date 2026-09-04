package com.taotao.music.data

/**
 * 首页问候语的纯数据规则。
 *
 * 页面只负责把结果画出来；将时间段文案收口在 data 层后，紧急调整措辞可由热修补接管，
 * 不需要修改 Compose 的组合结构。
 */
object GreetingFormatter {
    fun greetingForHour(hour: Int): String = when (hour) {
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..18 -> "下午好"
        in 19..22 -> "晚上好"
        else -> "你好"
    }
}
