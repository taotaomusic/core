package com.taotao.music.crypto

import java.io.IOException

/** 连接准备失败时给用户可操作的提示，技术原因只保留在异常链和诊断日志中。 */
class CryptoConnectionException(cause: Throwable? = null) : IOException(
    "暂时无法连接服务，请稍后重试",
    cause,
)
