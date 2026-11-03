package com.taotao.music.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.taotao.music.R
import com.taotao.music.model.Song
import java.io.File
import java.io.FileOutputStream

/**
 * 已经落盘的分享海报。
 *
 * [file] 位于 `cache/share/`，已通过 FileProvider 暴露给系统分享面板；
 * [url] 是海报二维码里的短链，也是「复制链接 / 只分享链接」用的地址。
 */
data class SharePoster(
    val file: File,
    val song: Song,
    val url: String,
)

/**
 * 生成一张 1080 × 1620 的歌曲分享海报。
 *
 * 海报是纯 Android Graphics 绘制的静态图，不依赖 Compose 快照：分享面板在另一个
 * 应用里打开图片，快照方案容易把弹层、状态栏一起截进去。版式从上到下依次是
 * 品牌 logo 与字标、专辑封面、歌曲信息、二维码与短链，保证图片保存下来后
 * 仍能独立表达「哪首歌、在哪听、怎么听」。
 */
object SharePosterRenderer {
    private const val Width = 1080f
    private const val Height = 1620f
    private const val Padding = 72f

    private const val IconSize = 104f
    private const val HeaderTop = 64f
    private const val CoverTop = 208f
    private const val CoverSize = 936f
    private const val InfoPanelTop = 1196f
    private const val QrCardSize = 196f
    private const val QrCardTop = 1390f

    private val BackgroundTop = Color.parseColor("#FFFBF9")
    private val BackgroundBottom = Color.parseColor("#FFEDE7")
    private val Coral = Color.parseColor("#FA5E5B")
    private val Ink = Color.parseColor("#231E1D")
    private val InkSecondary = Color.parseColor("#8A8280")
    private val InkFaint = Color.parseColor("#A69B98")
    private val Divider = Color.parseColor("#1A231E1D")
    private val CardBorder = Color.parseColor("#33FA5E5B")
    private val FallbackCover = Color.parseColor("#FFB4A2")

    /** 在 IO 线程调用：会读封面、编码二维码并写 PNG 文件。 */
    suspend fun render(context: Context, song: Song, url: String): SharePoster {
        val bitmap = Bitmap.createBitmap(Width.toInt(), Height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas)
        drawHeader(canvas, context)
        drawCover(canvas, context, song)
        drawInfoPanel(canvas)
        drawSongText(canvas, song)
        drawQrSection(canvas, url)

        val directory = File(context.cacheDir, "share").apply { mkdirs() }
        // 上一次分享的海报已经完成使命，换新图前统一清掉，避免缓存越积越多。
        directory.listFiles()?.forEach { runCatching { it.delete() } }
        val file = File(directory, "taotao-share-${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "分享图写入失败" }
            }
        } finally {
            bitmap.recycle()
        }
        return SharePoster(file = file, song = song, url = url)
    }

    private fun drawBackground(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                Height,
                BackgroundTop,
                BackgroundBottom,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, Width, Height, paint)

        // 两颗低透明度珊瑚圆只做氛围，不参与信息表达；压在内容下方，避免干扰文字。
        val decor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(22, 250, 94, 91) }
        canvas.drawCircle(Width - 40f, 40f, 300f, decor)
        canvas.drawCircle(24f, Height - 90f, 220f, decor)
    }

    private fun drawHeader(canvas: Canvas, context: Context) {
        val icon = runCatching { decodeAppIcon(context) }.getOrNull()
        if (icon != null) {
            val iconRect = RectF(Padding, HeaderTop, Padding + IconSize, HeaderTop + IconSize)
            canvas.save()
            canvas.clipPath(
                Path().apply {
                    addRoundRect(iconRect, 26f, 26f, Path.Direction.CW)
                },
            )
            canvas.drawBitmap(icon, null, iconRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            icon.recycle()
        }

        val wordmarkX = Padding + IconSize + 28f
        val wordmark = textPaint(44f, Ink, bold = true)
        canvas.drawText("桃桃音乐", wordmarkX, HeaderTop + 56f, wordmark)
        canvas.drawText("听见这一刻", wordmarkX, HeaderTop + 98f, textPaint(24f, InkSecondary))

        // 右上角小标：强调这是「分享图」，同时平衡左侧字标的重量。
        val tagPaint = textPaint(23f, Coral, bold = true)
        val tag = "与你分享"
        val tagWidth = tagPaint.measureText(tag)
        canvas.drawText(tag, Width - Padding - tagWidth, HeaderTop + 62f, tagPaint)
    }

    private suspend fun drawCover(canvas: Canvas, context: Context, song: Song) {
        val coverRect = RectF(Padding, CoverTop, Padding + CoverSize, CoverTop + CoverSize)
        val radius = 40f

        // 柔和投影：BlurMaskFilter 只在软件画布生效，本函数正是画在内存 Bitmap 上。
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(42, 0, 0, 0)
            maskFilter = BlurMaskFilter(34f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(coverRect.left, coverRect.top + 18f, coverRect.right, coverRect.bottom + 18f),
            radius,
            radius,
            shadow,
        )

        val cover = runCatching { loadCover(context, song.coverUri) }.getOrNull()
        canvas.save()
        canvas.clipPath(
            Path().apply {
                addRoundRect(coverRect, radius, radius, Path.Direction.CW)
            },
        )
        if (cover != null) {
            val src = centerCropRect(cover.width, cover.height, coverRect.width(), coverRect.height())
            canvas.drawBitmap(
                cover,
                src,
                coverRect,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            cover.recycle()
        } else {
            // 封面缺失或加载失败时用歌曲身份色兜底，保持每首歌的视觉记忆点。
            val coverColor = song.color.toInt().let { if (Color.alpha(it) == 0) FallbackCover else it }
            canvas.drawRect(coverRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coverColor })
            val note = textPaint(180f, Color.argb(232, 255, 255, 255), bold = true).apply {
                textAlign = Paint.Align.CENTER
            }
            // 基线要下移约 0.35 倍字号，视觉中心才与封面中心重合。
            canvas.drawText("♪", coverRect.centerX(), coverRect.centerY() + 63f, note)
        }
        canvas.restore()
    }

    private fun drawInfoPanel(canvas: Canvas) {
        val panel = RectF(0f, InfoPanelTop, Width, Height)
        val radius = 44f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(panel, radius, radius, paint)
        // 面板下沿要贴住海报底边：补一块方角矩形盖掉底部两角的圆角。
        canvas.drawRect(0f, Height - radius, Width, Height, paint)
    }

    private fun drawSongText(canvas: Canvas, song: Song) {
        val contentWidth = Width - Padding * 2
        val titlePaint = textPaint(54f, Ink, bold = true)
        canvas.drawText(
            ellipsize(titlePaint, song.title, contentWidth),
            Padding,
            1278f,
            titlePaint,
        )

        val metaPaint = textPaint(28f, InkSecondary)
        val meta = listOf(song.artist, song.album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        canvas.drawText(ellipsize(metaPaint, meta, contentWidth), Padding, 1332f, metaPaint)
        canvas.drawLine(
            Padding,
            1364f,
            Width - Padding,
            1364f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Divider
                strokeWidth = 2f
            },
        )
    }

    private fun drawQrSection(canvas: Canvas, url: String) {
        val card = RectF(Padding, QrCardTop, Padding + QrCardSize, QrCardTop + QrCardSize)
        val radius = 24f
        val cardShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, 0, 0, 0)
            maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(card.left, card.top + 6f, card.right, card.bottom + 6f),
            radius,
            radius,
            cardShadow,
        )
        canvas.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawRoundRect(
            card,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CardBorder
                style = Paint.Style.STROKE
                strokeWidth = 2f
            },
        )

        val qr = encodeQr(url, 480)
        val inset = 14f
        val qrRect = RectF(card.left + inset, card.top + inset, card.right - inset, card.bottom - inset)
        canvas.drawBitmap(
            qr,
            null,
            qrRect,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        qr.recycle()

        val textX = card.right + 28f
        val textWidth = Width - Padding - textX
        canvas.drawText("扫码听这首歌", textX, QrCardTop + 54f, textPaint(34f, Coral, bold = true))
        val urlPaint = textPaint(24f, InkSecondary)
        canvas.drawText(ellipsize(urlPaint, url, textWidth), textX, QrCardTop + 100f, urlPaint)
        canvas.drawText(
            "长按识别二维码 · 打开桃桃音乐",
            textX,
            QrCardTop + 144f,
            textPaint(23f, InkFaint),
        )
    }

    private fun decodeAppIcon(context: Context): Bitmap =
        BitmapFactory.decodeResource(
            context.resources,
            R.drawable.app_icon,
            BitmapFactory.Options().apply { inScaled = false },
        )

    private suspend fun loadCover(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(CoverSize.toInt())
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        return (result as? SuccessResult)?.drawable?.toBitmap()
    }

    /** 把任意比例的封面裁成与目标框同比例的中心区域，避免拉伸变形。 */
    private fun centerCropRect(srcWidth: Int, srcHeight: Int, dstWidth: Float, dstHeight: Float): Rect {
        val srcRatio = srcWidth.toFloat() / srcHeight
        val dstRatio = dstWidth / dstHeight
        return if (srcRatio > dstRatio) {
            val width = (srcHeight * dstRatio).toInt().coerceAtLeast(1)
            val left = (srcWidth - width) / 2
            Rect(left, 0, left + width, srcHeight)
        } else {
            val height = (srcWidth / dstRatio).toInt().coerceAtLeast(1)
            val top = (srcHeight - height) / 2
            Rect(0, top, srcWidth, top + height)
        }
    }

    private fun encodeQr(content: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            ),
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            } else {
                Typeface.SANS_SERIF
            }
        }

    /** 单行截断：海报不接受换行，标题与歌手名必须在一行内收住。 */
    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String =
        TextUtils.ellipsize(text, TextPaint(paint), maxWidth, TextUtils.TruncateAt.END).toString()
}
