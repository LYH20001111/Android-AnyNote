package com.skyanchor.anynote.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

/**
 * 应用自带的图标集。用 ImageVector.Builder 直接构造 Material 24dp 路径，
 * 避免依赖 material-icons 扩展包（Compose BOM 未必包含）。
 */
private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(pathData = svgPathNodes(path), fill = SolidColor(Color.Black)).build()

/**
 * ImageVector 只吃 PathNode 列表，Material 图标源是 SVG 路径字符串，这里做一次子集解析：
 * M/L/H/V/C/S/Q/T/Z（绝对与相对写法），输出统一为绝对坐标；弧线退化为直线。
 */
internal fun svgPathNodes(source: String): List<PathNode> {
    val out = ArrayList<PathNode>(64)
    val s = SvgScanner(source)
    var x = 0f
    var y = 0f
    var subX = 0f
    var subY = 0f
    var lastCtrlX = 0f
    var lastCtrlY = 0f
    var lastWasCubic = false
    var lastWasQuad = false

    while (true) {
        val cmd = s.nextCommand() ?: break
        val rel = cmd.isLowerCase()
        when (cmd.uppercaseChar()) {
            'Z' -> {
                x = subX
                y = subY
            }
            else -> {
                var first = true
                var more = true
                while (more) {
                    when (cmd.uppercaseChar()) {
                        'M' -> {
                            val px = s.number()
                            val py = s.number()
                            x = if (rel) x + px else px
                            y = if (rel) y + py else py
                            if (first) {
                                out += PathNode.MoveTo(x, y)
                                subX = x
                                subY = y
                            } else {
                                out += PathNode.LineTo(x, y)
                            }
                        }
                        'L' -> {
                            val px = s.number()
                            val py = s.number()
                            x = if (rel) x + px else px
                            y = if (rel) y + py else py
                            out += PathNode.LineTo(x, y)
                        }
                        'H' -> {
                            val px = s.number()
                            x = if (rel) x + px else px
                            out += PathNode.LineTo(x, y)
                        }
                        'V' -> {
                            val py = s.number()
                            y = if (rel) y + py else py
                            out += PathNode.LineTo(x, y)
                        }
                        'C' -> {
                            val ax = s.number()
                            val ay = s.number()
                            val bx = s.number()
                            val by = s.number()
                            val px = s.number()
                            val py = s.number()
                            val c1x = if (rel) x + ax else ax
                            val c1y = if (rel) y + ay else ay
                            val c2x = if (rel) x + bx else bx
                            val c2y = if (rel) y + by else by
                            val ex = if (rel) x + px else px
                            val ey = if (rel) y + py else py
                            out += PathNode.CurveTo(c1x, c1y, c2x, c2y, ex, ey)
                            lastCtrlX = c2x
                            lastCtrlY = c2y
                            lastWasCubic = true
                            lastWasQuad = false
                            x = ex
                            y = ey
                        }
                        'S' -> {
                            val bx = s.number()
                            val by = s.number()
                            val px = s.number()
                            val py = s.number()
                            val c1x = if (lastWasCubic) 2 * x - lastCtrlX else x
                            val c1y = if (lastWasCubic) 2 * y - lastCtrlY else y
                            val c2x = if (rel) x + bx else bx
                            val c2y = if (rel) y + by else by
                            val ex = if (rel) x + px else px
                            val ey = if (rel) y + py else py
                            out += PathNode.CurveTo(c1x, c1y, c2x, c2y, ex, ey)
                            lastCtrlX = c2x
                            lastCtrlY = c2y
                            lastWasCubic = true
                            lastWasQuad = false
                            x = ex
                            y = ey
                        }
                        'Q' -> {
                            val qx = s.number()
                            val qy = s.number()
                            val px = s.number()
                            val py = s.number()
                            val cx = if (rel) x + qx else qx
                            val cy = if (rel) y + qy else qy
                            val ex = if (rel) x + px else px
                            val ey = if (rel) y + py else py
                            out += PathNode.QuadTo(cx, cy, ex, ey)
                            lastCtrlX = cx
                            lastCtrlY = cy
                            lastWasCubic = false
                            lastWasQuad = true
                            x = ex
                            y = ey
                        }
                        'T' -> {
                            val px = s.number()
                            val py = s.number()
                            val cx = if (lastWasQuad) 2 * x - lastCtrlX else x
                            val cy = if (lastWasQuad) 2 * y - lastCtrlY else y
                            val ex = if (rel) x + px else px
                            val ey = if (rel) y + py else py
                            out += PathNode.QuadTo(cx, cy, ex, ey)
                            lastCtrlX = cx
                            lastCtrlY = cy
                            lastWasCubic = false
                            lastWasQuad = true
                            x = ex
                            y = ey
                        }
                        else -> {
                            // 弧线等未覆盖的指令：退化成直线，保证不崩，图标形状近似。
                            repeat(6) { s.number() }
                            val px = s.number()
                            val py = s.number()
                            x = if (rel) x + px else px
                            y = if (rel) y + py else py
                            out += PathNode.LineTo(x, y)
                            lastWasCubic = false
                            lastWasQuad = false
                        }
                    }
                    first = false
                    more = s.hasNumber()
                }
            }
        }
    }
    return out
}

object AppIcons {
    val Add = icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Back = icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val ChevronRight = icon("chevron_right", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    val ChevronLeft = icon("chevron_left", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z")
    val ChevronDown = icon("expand_more", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val Check = icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val DoneAll = icon("done_all", "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7zM22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41zM0.41,13.41L5,18l1.41,-1.41L1.83,12 0.41,13.41z")
    val Search = icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val Home = icon("home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
    val Calendar = icon("calendar", "M19,3h-1L18,1h-2v2L8,3L8,1L6,1v2L5,3c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,19L5,19L5,8h14v11zM7,10h5v5L7,15z")
    val Clock = icon("clock", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7L11,7v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")
    val History = icon("history", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8z")
    val Person = icon("person", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    val Delete = icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2L18,7L6,7v12zM19,4h-3.5l-1,-1h-5l-1,1L5,4v2h14L19,4z")
    val DeleteOutline = icon(
        "delete_outline",
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2L18,7L6,7v12zM8.5,10h7v2h-7v-2zM8.5,14h7v2h-7v-2zM18,4h-2.5l-0.71,-0.71c-0.18,-0.18 -0.43,-0.29 -0.7,-0.29h-4.18c-0.27,0 -0.52,0.11 -0.7,0.29L6,4L4,4v2h14L18,4z",
    )
    val Edit = icon("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    val Bell = icon("bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32L13.5,4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
    val Folder = icon("folder", "M10,4L4,4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val Briefcase = icon("briefcase", "M20,6h-4L16,4c0,-1.11 -0.89,-2 -2,-2h-4c-1.11,0 -2,0.89 -2,2v2L4,6c-1.11,0 -1.99,0.89 -1.99,2L2,19c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2L22,8c0,-1.11 -0.89,-2 -2,-2zM14,6h-4L10,4h4v2z")
    val Cart = icon("cart", "M7,18c-1.1,0 -1.99,0.9 -1.99,2S5.9,22 7,22s2,-0.9 2,-2 -0.9,-2 -2,-2zM1,2v2h2l3.6,7.59 -1.35,2.45c-0.16,0.28 -0.25,0.61 -0.25,0.96 0,1.1 0.9,2 2,2h12v-2L7.42,15c-0.14,0 -0.25,-0.11 -0.25,-0.25l0.03,-0.12 0.9,-1.63h7.45c0.75,0 1.41,-0.41 1.75,-1.03l3.58,-6.49c0.08,-0.14 0.12,-0.31 0.12,-0.48 0,-0.55 -0.45,-1 -1,-1L5.21,4l-0.94,-2L1,2zM17,18c-1.1,0 -1.99,0.9 -1.99,2s0.89,2 1.99,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    val School = icon("school", "M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z")
    val Circle = icon("circle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z")
    val More = icon("more", "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    val Repeat = icon("repeat", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4z")
    val SkipNext = icon("skip_next", "M6,18l8.5,-6L6,6v12zM16,6v12h2L18,6h-2z")
    val Tune = icon("tune", "M3,17v2h6v-2L3,17zM3,7v2h10L13,7L3,7zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM17,7h2L19,5h-2v2z")
    val Mic = icon("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val Image = icon("image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    val Attach = icon("attach", "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6L10,6v11.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,2.76 2.24,5 5,5s5,-2.24 5,-5V6h-1.5z")
    val File = icon("file", "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zM13,9V3.5L18.5,9H13z")
    val Play = icon("play", "M8,5v14l11,-7z")
    val Flag = icon("flag", "M14.4,6L14,4L5,4v17h2v-7h5.6l0.4,2h7L20.3,6z")
    val Warning = icon("warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    val Lock = icon("lock", "M18,8h-1L17,6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2L6,8c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L20,10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8L8.9,8L8.9,6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z")
    val Archive = icon("archive", "M20,5.41L18.59,4 7,15.59 8.41,17 20,5.41zM16,1L6,7l2,3 -2,3 10,6 2,-3 -2,-3 2,-3L16,1zM14,9.74L11.51,7 14,4.5 16.49,7 14,9.74z")
    val Restore = icon("restore", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8z")
    val Inbox = icon("inbox", "M19,3L4.99,3c-1.11,0 -1.98,0.9 -1.98,2L3,19c0,1.1 0.88,2 1.99,2L19,21c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,15h-4c0,1.66 -1.35,3 -3,3s-3,-1.34 -3,-3L4.99,15L4.99,5L19,5v10z")
    val Shield = icon("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4z")
    val Info = icon("info", "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z")
    val CheckBox = icon("check_box", "M19,3L5,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    val GridView = icon("grid_view", "M3,3h8v8L3,11L3,3zM13,3h8v8h-8L13,3zM3,13h8v8L3,21v-8zM13,13h8v8h-8v-8z")
    val Article = icon(
        "article",
        "M19,3L5,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM14,17L7,17v-2h7v2zM17,13L7,13v-2h10v2zM17,9L7,9L7,7h10v2z",
    )
    val ArrowUp = icon("arrow_up", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z")
    val ArrowDown = icon("arrow_down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z")
}

/** 分类图标按 key 取，未知分类回落到文件夹图标。 */
fun folderIcon(key: String?): ImageVector = when (key) {
    "work" -> AppIcons.Briefcase
    "life" -> AppIcons.Cart
    "family" -> AppIcons.Home
    "study" -> AppIcons.School
    else -> AppIcons.Folder
}

/** SVG 路径词法：命令是一个字母，数字之间可以只用空格、逗号，甚至负号分隔。 */
private class SvgScanner(private val src: String) {
    private var i = 0

    private fun skipSeparators() {
        while (i < src.length && (src[i] == ' ' || src[i] == ',' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r')) i++
    }

    fun nextCommand(): Char? {
        skipSeparators()
        if (i >= src.length) return null
        val c = src[i]
        if (!c.isLetter()) return null
        i++
        return c
    }

    fun hasNumber(): Boolean {
        skipSeparators()
        return i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == '-' || src[i] == '+')
    }

    fun number(): Float {
        skipSeparators()
        val start = i
        if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
        var seenDot = false
        while (i < src.length) {
            val c = src[i]
            if (c.isDigit()) {
                i++
            } else if (c == '.' && !seenDot) {
                seenDot = true
                i++
            } else {
                break
            }
        }
        return src.substring(start, i).toFloatOrNull() ?: 0f
    }
}
