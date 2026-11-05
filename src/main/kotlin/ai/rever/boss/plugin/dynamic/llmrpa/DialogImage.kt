package ai.rever.boss.plugin.dynamic.llmrpa

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * BOSS draws a page's alert, confirm and prompt outside the page, so a screenshot never shows them. This paints one onto the last
 * screenshot the way Chrome does (the page dimmed, a white box with the origin, the message, a prompt's input and the buttons), so
 * Jev can see what it is answering. The result is a new JPEG next to the screenshot.
 */
internal object DialogImage {
    private const val DEFAULT_W = 1120
    private const val DEFAULT_H = 780

    /**
     * The dialog [d] drawn over the screenshot at [sourcePath] (or over a blank page of [fallbackW] x [fallbackH] when it cannot be
     * read), saved as `<name>-dialog.jpg` beside it. Returns the new path, or null when nothing could be written.
     */
    fun render(sourcePath: String, d: Cu.DialogInfo, fallbackW: Int, fallbackH: Int): String? = runCatching {
        val src = runCatching { ImageIO.read(File(sourcePath)) }.getOrNull()
        val w = src?.width ?: fallbackW.takeIf { it > 0 } ?: DEFAULT_W
        val h = src?.height ?: fallbackH.takeIf { it > 0 } ?: DEFAULT_H
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // The page, as it was, dimmed to 40% black.
            if (src != null) g.drawImage(src, 0, 0, null) else { g.color = Color(0xF4, 0xF4, 0xF4); g.fillRect(0, 0, w, h) }
            g.color = Color(0, 0, 0, 102)
            g.fillRect(0, 0, w, h)
            draw(g, d, w, h)
        } finally {
            g.dispose()
        }
        val src0 = File(sourcePath)
        val out = if (sourcePath.isNotEmpty() && src0.parentFile != null) File(src0.parentFile, src0.nameWithoutExtension + "-dialog.jpg")
        else File(System.getProperty("java.io.tmpdir"), "llmrpa-dialog-${System.nanoTime()}.jpg")
        writeJpeg(img, out)
        out.absolutePath
    }.getOrNull()

    private fun draw(g: java.awt.Graphics2D, d: Cu.DialogInfo, w: Int, h: Int) {
        // Sizes follow the image, so a downscaled screenshot gets a proportionally smaller dialog.
        val s = w / DEFAULT_W.toDouble()
        fun px(v: Double) = (v * s).toInt().coerceAtLeast(1)
        val boxW = (w * 0.4).toInt().coerceAtLeast(px(300.0))
        val pad = px(20.0)
        val titleFont = Font(Font.SANS_SERIF, Font.BOLD, px(15.0))
        val bodyFont = Font(Font.SANS_SERIF, Font.PLAIN, px(14.0))
        val btnFont = Font(Font.SANS_SERIF, Font.PLAIN, px(13.0))
        val origin = (d.url?.let { host(it) }?.takeIf { it.isNotBlank() }?.let { u -> hostWithPort(d.url!!, u) } ?: "This page") + " says"
        val body = g.getFontMetrics(bodyFont)
        val lines = wrap(d.message.ifBlank { " " }, body, boxW - 2 * pad).take(8)
        val prompt = d.type == "prompt"
        val inputH = if (prompt) px(34.0) else 0
        val titleH = g.getFontMetrics(titleFont).height
        val btnH = px(34.0)
        val gap = px(14.0)
        val boxH = pad + titleH + gap + lines.size * body.height + (if (prompt) gap + inputH else 0) + gap + btnH + pad
        val x = (w - boxW) / 2
        val y = ((h - boxH) / 2.5).toInt().coerceAtLeast(px(20.0))
        // Shadow, then the white box with rounded corners.
        g.color = Color(0, 0, 0, 60)
        g.fill(RoundRectangle2D.Double(x.toDouble() + px(2.0), y.toDouble() + px(4.0), boxW.toDouble(), boxH.toDouble(), px(14.0).toDouble(), px(14.0).toDouble()))
        g.color = Color.WHITE
        g.fill(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), boxW.toDouble(), boxH.toDouble(), px(14.0).toDouble(), px(14.0).toDouble()))
        var cy = y + pad
        g.color = Color(0x20, 0x21, 0x24)
        g.font = titleFont
        g.drawString(origin, x + pad, cy + g.fontMetrics.ascent)
        cy += titleH + gap
        g.font = bodyFont
        g.color = Color(0x3C, 0x40, 0x43)
        lines.forEach { line -> g.drawString(line, x + pad, cy + g.fontMetrics.ascent); cy += g.fontMetrics.height }
        if (prompt) {
            cy += gap
            g.color = Color(0x1A, 0x73, 0xE8)
            g.stroke = java.awt.BasicStroke(px(2.0).toFloat())
            g.drawRoundRect(x + pad, cy, boxW - 2 * pad, inputH, px(6.0), px(6.0))
            g.color = Color(0x20, 0x21, 0x24)
            g.font = bodyFont
            g.drawString(d.defaultValue.orEmpty(), x + pad + px(8.0), cy + (inputH + g.fontMetrics.ascent - g.fontMetrics.descent) / 2)
            cy += inputH
        }
        cy += gap
        // Buttons at the right: Cancel (outlined), then OK (filled blue). An alert has only OK.
        g.font = btnFont
        val ok = d.okText?.takeIf { it.isNotBlank() } ?: "OK"
        val cancel = d.cancelText?.takeIf { it.isNotBlank() } ?: "Cancel"
        val bm = g.fontMetrics
        fun btnW(t: String) = bm.stringWidth(t) + 2 * px(18.0)
        var bx = x + boxW - pad
        val okW = btnW(ok)
        bx -= okW
        g.color = Color(0x1A, 0x73, 0xE8)
        g.fillRoundRect(bx, cy, okW, btnH, px(18.0), px(18.0))
        g.color = Color.WHITE
        g.drawString(ok, bx + (okW - bm.stringWidth(ok)) / 2, cy + (btnH + bm.ascent - bm.descent) / 2)
        if (d.type != "alert") {
            val cw = btnW(cancel)
            bx -= px(10.0) + cw
            g.color = Color(0xDA, 0xDC, 0xE0)
            g.stroke = java.awt.BasicStroke(px(1.0).toFloat())
            g.drawRoundRect(bx, cy, cw, btnH, px(18.0), px(18.0))
            g.color = Color(0x1A, 0x73, 0xE8)
            g.drawString(cancel, bx + (cw - bm.stringWidth(cancel)) / 2, cy + (btnH + bm.ascent - bm.descent) / 2)
        }
    }

    /** "127.0.0.1:8811" from an address: the host and its port. */
    private fun hostWithPort(url: String, host: String): String =
        runCatching { java.net.URI(url).let { u -> if (u.port > 0) "${u.host}:${u.port}" else host } }.getOrDefault(host)

    private fun wrap(text: String, fm: FontMetrics, maxW: Int): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(next) <= maxW || line.isEmpty()) line = next else { out += line; line = word }
            }
            out += line
        }
        return out
    }

    private fun writeJpeg(img: BufferedImage, out: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
        out.parentFile?.mkdirs()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
    }
}
