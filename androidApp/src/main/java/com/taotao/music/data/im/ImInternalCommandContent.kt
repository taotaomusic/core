package com.taotao.music.data.im

import com.xinbida.wukongim.message.type.WKMsgContentType
import com.xinbida.wukongim.msgmodel.WKMessageContent
import org.json.JSONObject

/**
 * 悟空 SDK 识别的内部命令消息。它走原生二进制协议，SDK 收到后交给 CMDManager，
 * 不会作为普通聊天气泡展示。
 */
internal class ImInternalCommandContent(
    private val command: String,
    private val parameters: JSONObject,
) : WKMessageContent() {
    init {
        type = WKMsgContentType.WK_INSIDE_MSG
    }

    override fun encodeMsg(): JSONObject = JSONObject()
        .put("type", type)
        .put("cmd", command)
        .put("param", parameters)
}
