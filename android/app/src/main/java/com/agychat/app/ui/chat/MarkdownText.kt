package com.agychat.app.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.LruCache
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay
import org.json.JSONObject

// ─── Process-level caches for zero-allocation scrolling ───────────────────────
private val markdownBlockCache    = LruCache<String, List<MarkdownBlock>>(300)
private val latexUnicodeCache     = LruCache<String, String>(400)
private val syntaxHighlightCache  = LruCache<String, androidx.compose.ui.text.AnnotatedString>(150)
private val inlineTextCache       = LruCache<String, androidx.compose.ui.text.AnnotatedString>(400)

// ─── Sanitise raw streamed/LLM markdown ───────────────────────────────────────
fun sanitizeMarkdownInput(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
        .replace(Regex("//#\\][^\r\n]*"), "")
        .replace(Regex("\\[\\?[0-9;]*[a-zA-Z]"), "")
        .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]"), "")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
}

// ─── LaTeX normaliser ─────────────────────────────────────────────────────────
/**
 * Strips outer delimiters and fixes the most common LLM-produced LaTeX mistakes
 * without being over-aggressive (no stripping of \left/\right unless dangling).
 */
fun normalizeLatexFormula(raw: String): String {
    var s = raw.trim()

    // Strip outermost delimiters
    if (s.startsWith("\$\$") && s.endsWith("\$\$") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("\\(") && s.endsWith("\\)") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("\$") && s.endsWith("\$") && s.length >= 2 && !s.startsWith("\$\$")) {
        s = s.substring(1, s.length - 1).trim()
    }

    // Normalise KaTeX-unsupported environments → supported equivalents
    s = s
        .replace(Regex("""\\begin\{align\*?\}""")) { "\\begin{aligned}" }
        .replace(Regex("""\\end\{align\*?\}"""))   { "\\end{aligned}" }
        .replace(Regex("""\\begin\{gather\*?\}""")) { "\\begin{gathered}" }
        .replace(Regex("""\\end\{gather\*?\}"""))   { "\\end{gathered}" }
        .replace(Regex("""\\begin\{eqnarray\*?\}""")){ "\\begin{aligned}" }
        .replace(Regex("""\\end\{eqnarray\*?\}""")) { "\\end{aligned}" }

    // Fix markdown-escaped chars inside math
    s = s.replace("\\_", "_").replace("\\&", "&")

    // Strip invisible LaTeX null delimiters
    s = s.replace(Regex("""\\(?:left|right)\."""), "")

    // Fix missing backslash on bare LaTeX keywords (e.g. "frac{a}{b}" → "\frac{a}{b}")
    val missingSlash = Regex("""(?<!\\)\b(frac|sqrt|sum|int|prod|partial|hbar|alpha|beta|gamma|delta|epsilon|theta|lambda|mu|pi|rho|sigma|tau|phi|psi|omega|Delta|Theta|Lambda|Sigma|Phi|Psi|Omega|ket|bra|braket|hat|vec|mathcal|mathbf|mathbb)\b""")
    s = s.replace(missingSlash) { "\\${it.value}" }

    // Strip dangling trailing backslash from streaming
    s = s.replace(Regex("""\\+\s*$"""), "")

    // Auto-balance unclosed curly braces (common during streaming)
    val open  = s.count { it == '{' }
    val close = s.count { it == '}' }
    if (open > close) s += "}".repeat(open - close)

    return s.trim()
}

// ─── Balanced Brace & Math Extraction Helpers ─────────────────────────────────
fun findMatchingBrace(text: CharSequence, openIdx: Int): Int {
    var depth = 0
    for (i in openIdx until text.length) {
        if (text[i] == '{') depth++
        else if (text[i] == '}') {
            depth--
            if (depth == 0) return i
        }
    }
    return -1
}

fun extractBraceArg(text: String, startIndex: Int): Pair<String, Int>? {
    val openIdx = text.indexOf('{', startIndex)
    if (openIdx == -1) return null
    val closeIdx = findMatchingBrace(text, openIdx)
    if (closeIdx == -1) return null
    return Pair(text.substring(openIdx + 1, closeIdx), closeIdx + 1)
}

// ─── Math Tokens for Native Stacked Fractions, Radicals & Vectors ─────────────
sealed class MathToken {
    data class Text(val text: String) : MathToken()
    data class Fraction(val numerator: String, val denominator: String) : MathToken()
    data class Radical(val degree: String?, val content: String) : MathToken()
    data class Vector(val base: String, val subscript: String) : MathToken()
}

fun tokenizeMathFormula(formula: String): List<MathToken> {
    val tokens = mutableListOf<MathToken>()
    var curr = 0
    val tokenRegex = Regex("""(\\?(?:d|t|c)?frac\s*\{|\\sqrt(?:\[([0-9]+)\])?\s*\{|\\vec(?:\s*\{|\s+([a-zA-Z])|([a-zA-Z])))""")
    while (curr < formula.length) {
        val m = tokenRegex.find(formula, curr)
        if (m == null) {
            val tail = formula.substring(curr)
            if (tail.isNotEmpty()) tokens.add(MathToken.Text(tail))
            break
        }
        val startIdx = m.range.first
        if (startIdx > curr) {
            tokens.add(MathToken.Text(formula.substring(curr, startIdx)))
        }
        val matchedStr = m.value
        when {
            matchedStr.contains("frac") -> {
                val numArg = extractBraceArg(formula, startIdx)
                if (numArg == null) {
                    tokens.add(MathToken.Text(formula.substring(startIdx, m.range.last + 1)))
                    curr = m.range.last + 1
                    continue
                }
                val (num, nextIdx) = numArg
                val denArg = extractBraceArg(formula, nextIdx)
                if (denArg == null) {
                    tokens.add(MathToken.Text(formula.substring(startIdx, nextIdx)))
                    curr = nextIdx
                    continue
                }
                val (den, endIdx) = denArg
                tokens.add(MathToken.Fraction(num, den))
                curr = endIdx
            }
            matchedStr.contains("sqrt") -> {
                val degree = m.groupValues[2].ifBlank { null }
                val arg = extractBraceArg(formula, startIdx)
                if (arg == null) {
                    tokens.add(MathToken.Text(formula.substring(startIdx, m.range.last + 1)))
                    curr = m.range.last + 1
                    continue
                }
                tokens.add(MathToken.Radical(degree, arg.first))
                curr = arg.second
            }
            matchedStr.contains("vec") -> {
                var base = ""
                var nextPos = 0
                if (matchedStr.contains("{")) {
                    val arg = extractBraceArg(formula, startIdx)
                    if (arg != null) {
                        base = arg.first
                        nextPos = arg.second
                    }
                } else {
                    base = m.groupValues[3].ifEmpty { m.groupValues[4] }
                    nextPos = m.range.last + 1
                }
                if (base.isEmpty()) {
                    tokens.add(MathToken.Text(matchedStr))
                    curr = m.range.last + 1
                    continue
                }
                var sub = ""
                if (nextPos < formula.length && formula[nextPos] == '_') {
                    if (nextPos + 1 < formula.length && formula[nextPos + 1] == '{') {
                        val subArg = extractBraceArg(formula, nextPos + 1)
                        if (subArg != null) {
                            sub = subArg.first
                            nextPos = subArg.second
                        }
                    } else if (nextPos + 1 < formula.length && formula[nextPos + 1].isLetterOrDigit()) {
                        sub = formula[nextPos + 1].toString()
                        nextPos += 2
                    }
                }
                tokens.add(MathToken.Vector(base, sub))
                curr = nextPos
            }
        }
    }
    return tokens
}

// ─── High-quality Unicode fallback for inline math ───────────────────────────
/**
 * Converts LaTeX notation into clean Unicode. Used as the immediate fallback
 * while the WebView loads (display blocks) and as the primary renderer for
 * short inline formulas that don't warrant a WebView.
 */
fun formatLatexToUnicode(raw: String): String {
    if (raw.isBlank()) return ""
    val cached = latexUnicodeCache.get(raw)
    if (cached != null) return cached
    var text = normalizeLatexFormula(raw)

    // Strip invisible null delimiters
    text = text.replace(Regex("""\\(?:left|right)\."""), "")
    // Strip sizing prefixes preserving delimiters (\left[, \left(, \left\{, etc.)
    text = text.replace(Regex("""\\(?:left|right)(?![a-zA-Z])"""), "")
    text = text.replace(Regex("""\\(?:big|Big|bigg|Bigg)[lrm]?(?![a-zA-Z])"""), "")

    // Degree symbols: 0^\circ -> 0°, 180^\circ -> 180°, \degree -> °
    text = text.replace(Regex("""\^(?:\\circ|\{?\\circ\}?|°)"""), "°")
    text = text.replace("\\degree", "°").replace("\\circ", "°")

    // Matrix environments
    text = text.replace(Regex("""\\begin\{(?:bmatrix)\}([\s\S]*?)\\end\{(?:bmatrix)\}""")) {
        val inner = it.groupValues[1].replace("&", "   ").replace("\\\\", " ┃ ").trim()
        "⎡ $inner ⎤"
    }
    text = text.replace(Regex("""\\begin\{(?:pmatrix)\}([\s\S]*?)\\end\{(?:pmatrix)\}""")) {
        val inner = it.groupValues[1].replace("&", "   ").replace("\\\\", " ┃ ").trim()
        "⎛ $inner ⎞"
    }
    text = text.replace(Regex("""\\begin\{(?:vmatrix)\}([\s\S]*?)\\end\{(?:vmatrix)\}""")) {
        val inner = it.groupValues[1].replace("&", "   ").replace("\\\\", " ┃ ").trim()
        "| $inner |"
    }
    text = text.replace(Regex("""\\begin\{(?:matrix|array)\}(?:\{[^\}]*\})?([\s\S]*?)\\end\{(?:matrix|array)\}""")) {
        val inner = it.groupValues[1].replace("&", "   ").replace("\\\\", " ┃ ").trim()
        "[ $inner ]"
    }
    text = text.replace(Regex("""\\begin\{(?:equation\*?|align\*?|aligned|gather\*?|split)\}"""), "")
    text = text.replace(Regex("""\\end\{(?:equation\*?|align\*?|aligned|gather\*?|split)\}"""), "")

    // Quantum bra/ket with balanced braces
    text = text.replace("\\langle", "⟨").replace("\\rangle", "⟩")
    var qIter = 0
    while (qIter++ < 10) {
        val m = Regex("""\\(?:braket|bra|ket)\s*\{""").find(text) ?: break
        val startIdx = m.range.first
        val isBraket = text.startsWith("\\braket", startIdx)
        val isBra    = text.startsWith("\\bra", startIdx)
        val isKet    = text.startsWith("\\ket", startIdx)
        val firstArg = extractBraceArg(text, startIdx) ?: break
        if (isBraket) {
            val secondArg = extractBraceArg(text, firstArg.second)
            if (secondArg != null) {
                text = text.substring(0, startIdx) + "⟨${firstArg.first}|${secondArg.first}⟩" + text.substring(secondArg.second)
            } else {
                text = text.substring(0, startIdx) + "⟨${firstArg.first}⟩" + text.substring(firstArg.second)
            }
        } else if (isBra) {
            text = text.substring(0, startIdx) + "⟨${firstArg.first}|" + text.substring(firstArg.second)
        } else if (isKet) {
            text = text.substring(0, startIdx) + "|${firstArg.first}⟩" + text.substring(firstArg.second)
        }
    }

    // Accents with balanced braces
    val accentTags = listOf(
        "\\vec" to "⃗", "\\dot" to "̇", "\\ddot" to "̈",
        "\\bar" to "̄", "\\tilde" to "̃", "\\hat" to "̂"
    )
    for ((tag, mark) in accentTags) {
        var aIter = 0
        while (aIter++ < 15) {
            val idx = text.indexOf(tag)
            if (idx == -1) break
            val arg = extractBraceArg(text, idx)
            if (arg != null) {
                text = text.substring(0, idx) + "${arg.first}$mark" + text.substring(arg.second)
            } else {
                val after = text.substring(idx + tag.length).trimStart()
                if (after.isNotEmpty() && after.first().isLetter()) {
                    val ch = after.first()
                    text = text.substring(0, idx) + "$ch$mark" + after.substring(1)
                } else {
                    text = text.replaceFirst(tag, "")
                }
            }
        }
    }

    // Text & Math decorators with balanced braces
    val decoratorRegex = Regex("""\\(?:text|mathrm|operatorname\*?|mathbf|boldsymbol|bm|mathit|mathsf|textbf)\s*\{""")
    var decIter = 0
    while (decIter++ < 25) {
        val m = decoratorRegex.find(text) ?: break
        val startIdx = m.range.first
        val arg = extractBraceArg(text, startIdx) ?: break
        val isBold = text.startsWith("\\mathbf", startIdx) || text.startsWith("\\boldsymbol", startIdx) || text.startsWith("\\bm", startIdx)
        val inner = if (isBold) {
            val boldMap = mapOf(
                'E' to "𝐄", 'B' to "𝐁", 'F' to "𝐅", 'v' to "𝐯", 'p' to "𝐩",
                'r' to "𝐫", 'J' to "𝐉", 'A' to "𝐀", 'S' to "𝐒", 'k' to "𝐤",
                'l' to "𝐥", 'n' to "𝐧", 'I' to "𝐈", 'q' to "𝐪", 'a' to "𝐚",
                'b' to "𝐛", 'c' to "𝐜", 'd' to "𝐝", 'e' to "𝐞", 'm' to "𝐦",
                'H' to "𝐇", 'M' to "𝐌", 'C' to "𝐂", 'P' to "𝐏", 'T' to "𝐓",
                'Q' to "𝐐", 'R' to "𝐑", 'u' to "𝐮", 'w' to "𝐰", 'x' to "𝐱",
                'y' to "𝐲", 'z' to "𝐳"
            )
            arg.first.map { boldMap[it] ?: it.toString() }.joinToString("")
        } else {
            arg.first
        }
        text = text.substring(0, startIdx) + inner + text.substring(arg.second)
    }

    // Roots with balanced braces
    val sqrtRegex = Regex("""\\?sqrt(?:\[([0-9]+)\])?\s*\{""")
    var sIter = 0
    while (sIter++ < 15) {
        val m = sqrtRegex.find(text) ?: break
        val startIdx = m.range.first
        val degree = m.groupValues[1]
        val arg = extractBraceArg(text, startIdx) ?: break
        val prefix = when (degree) { "3" -> "∛"; "4" -> "∜"; else -> "√" }
        text = text.substring(0, startIdx) + "$prefix(${arg.first})" + text.substring(arg.second)
    }

    // Fractions with balanced braces (nested fractions handled cleanly)
    val fracRegex = Regex("""\\?(?:d|t|c)?frac\s*\{""")
    var fIter = 0
    while (fIter++ < 25) {
        val m = fracRegex.find(text) ?: break
        val startIdx = m.range.first
        val numArg = extractBraceArg(text, startIdx) ?: break
        val denArg = extractBraceArg(text, numArg.second) ?: break
        val num = numArg.first.trim()
        val den = denArg.first.trim()

        val rep = when {
            num == "1" && den == "2" -> "½"
            num == "1" && den == "3" -> "⅓"
            num == "2" && den == "3" -> "⅔"
            num == "1" && den == "4" -> "¼"
            num == "3" && den == "4" -> "¾"
            num == "1" && den == "5" -> "⅕"
            num == "2" && den == "5" -> "⅖"
            num == "3" && den == "5" -> "⅗"
            num == "4" && den == "5" -> "⅘"
            num == "1" && den == "6" -> "⅙"
            num == "5" && den == "6" -> "⅚"
            num == "1" && den == "8" -> "⅛"
            num == "3" && den == "8" -> "⅜"
            num == "5" && den == "8" -> "⅝"
            num == "7" && den == "8" -> "⅞"
            num == "d" && den == "dx" -> "d/dx"
            num == "d" && den == "dt" -> "d/dt"
            num == "u" && den == "v"  -> "u/v"
            num.length == 1 && den.length == 1 && num.first().isLetterOrDigit() && den.first().isLetterOrDigit() -> "$num/$den"
            else -> {
                val n = if (num.contains(Regex("[+\\-\\s]")) && !num.startsWith("(") && !num.endsWith(")")) "($num)" else num
                val d = if (den.contains(Regex("[+\\-\\s\\\\]")) && !den.startsWith("(") && !den.endsWith(")")) "($den)" else den
                "$n / $d"
            }
        }
        text = text.substring(0, startIdx) + rep + text.substring(denArg.second)
    }
    // Clean up any remaining bare \frac or frac
    text = text.replace(Regex("""\\?(?:d|t|c)?frac\b"""), "")

    // Blackboard bold
    val bbMap = mapOf("R" to "ℝ", "C" to "ℂ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "H" to "ℍ")
    for ((k, v) in bbMap) text = text.replace("\\mathbb{$k}", v).replace("\\mathbb $k", v)

    val calMap = mapOf("H" to "ℋ","E" to "ℰ","L" to "ℒ","M" to "ℳ","F" to "ℱ","O" to "𝒪","P" to "𝒫","D" to "𝒟","C" to "𝒞","N" to "𝒩","B" to "ℬ","A" to "𝒜")
    for ((k, v) in calMap) {
        text = text.replace("\\mathcal{$k}", v).replace("\\mathcal $k", v)
            .replace("\\mathscr{$k}", v).replace("\\mathscr $k", v)
    }

    val hats = mapOf("H" to "Ĥ","A" to "Â","B" to "B̂","p" to "p̂","x" to "x̂","y" to "ŷ","z" to "ẑ","\\rho" to "ρ̂","rho" to "ρ̂","\\psi" to "ψ̂","psi" to "ψ̂","\\phi" to "ϕ̂","phi" to "ϕ̂")
    for ((k, v) in hats) text = text.replace("\\hat{$k}", v).replace("\\hat $k", v)

    // Greek letters and math symbols with proper letter boundaries
    val symbols = listOf(
        "\\varepsilon_0" to "ε₀","\\epsilon_0" to "ε₀",
        "\\mu_0" to "μ₀","\\Phi_0" to "Φ₀","\\nu_0" to "ν₀","\\lambda_0" to "λ₀",
        "\\vec{\\tau}" to "τ⃗","\\vec{\\mu}" to "μ⃗","\\vec{p}" to "p⃗","\\vec{r}" to "r⃗",
        "\\vec{E}" to "E⃗","\\vec{B}" to "B⃗","\\vec{F}" to "F⃗","\\vec{v}" to "v⃗","\\vec{A}" to "A⃗",
        "\\hat{r}" to "r̂","\\hat{p}" to "p̂","\\hat{n}" to "n̂","\\hat{i}" to "î","\\hat{j}" to "ĵ","\\hat{k}" to "k̂",
        "\\hbar" to "ℏ","\\dagger" to "†","\\partial" to "∂","\\nabla" to "∇","\\infty" to "∞",
        "\\sum" to "∑","\\prod" to "∏","\\int" to "∫","\\iint" to "∬","\\iiint" to "∭","\\oint" to "∮",
        "\\alpha" to "α","\\beta" to "β","\\gamma" to "γ","\\delta" to "δ","\\epsilon" to "ε",
        "\\varepsilon" to "ε","\\zeta" to "ζ","\\eta" to "η","\\theta" to "θ","\\vartheta" to "ϑ",
        "\\iota" to "ι","\\kappa" to "κ","\\lambda" to "λ","\\mu" to "μ","\\nu" to "ν",
        "\\xi" to "ξ","\\pi" to "π","\\varpi" to "ϖ","\\rho" to "ρ","\\varrho" to "ϱ",
        "\\sigma" to "σ","\\varsigma" to "ς","\\tau" to "τ","\\upsilon" to "υ","\\phi" to "ϕ",
        "\\varphi" to "φ","\\chi" to "χ","\\psi" to "ψ","\\omega" to "ω",
        "\\Gamma" to "Γ","\\Delta" to "Δ","\\Theta" to "Θ","\\Lambda" to "Λ","\\Xi" to "Ξ",
        "\\Pi" to "Π","\\Sigma" to "Σ","\\Upsilon" to "Υ","\\Phi" to "Φ","\\Psi" to "Ψ","\\Omega" to "Ω",
        "\\parallel" to "∥","\\nparallel" to "∦","\\perp" to "⊥",
        "\\pm" to "±","\\mp" to "∓","\\times" to "×","\\div" to "÷",
        "\\approx" to "≈","\\equiv" to "≡","\\neq" to "≠","\\ne" to "≠",
        "\\leq" to "≤","\\le" to "≤","\\geq" to "≥","\\ge" to "≥","\\ll" to "≪","\\gg" to "≫",
        "\\sim" to "∼","\\simeq" to "≃","\\cong" to "≅","\\propto" to "∝",
        "\\rightarrow" to "→","\\leftarrow" to "←","\\to" to "→","\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐","\\Leftrightarrow" to "⇔","\\iff" to "⇔","\\implies" to "⇒",
        "\\notin" to "∉","\\ni" to "∋","\\in" to "∈",
        "\\subset" to "⊂","\\supset" to "⊃","\\subseteq" to "⊆","\\supseteq" to "⊇",
        "\\cup" to "∪","\\cap" to "∩","\\emptyset" to "∅",
        "\\forall" to "∀","\\exists" to "∃","\\nexists" to "∄",
        "\\circ" to "°","\\degree" to "°","\\prime" to "′",
        "\\ldots" to "…","\\cdots" to "⋯","\\dots" to "…",
        "\\cos" to "cos","\\sin" to "sin","\\tan" to "tan","\\det" to "det",
        "\\gcd" to "gcd","\\lim" to "lim","\\ln" to "ln","\\log" to "log","\\exp" to "exp",
        "\\{" to "{","\\}" to "}","\\," to "\u2009","\\;" to " ","\\:" to " ","\\!" to "",
        "\\quad" to " ","\\qquad" to "  "
    )
    for ((k, v) in symbols) {
        if (k.length > 2 && k[1].isLetter()) {
            text = text.replace(Regex("""\Q$k\E(?![a-zA-Z])"""), v)
        } else {
            text = text.replace(k, v)
        }
    }
    // Handle \cdot ensuring proper spacing even when followed by units like \cdotm/A
    text = text.replace(Regex("""\\cdot\s*"""), " · ")

    // Superscripts with balanced braces
    val supsMap = mapOf('0' to '⁰','1' to '¹','2' to '²','3' to '³','4' to '⁴','5' to '⁵','6' to '⁶','7' to '⁷','8' to '⁸','9' to '⁹','+' to '⁺','-' to '⁻','=' to '⁼','(' to '⁽',')' to '⁾','a' to 'ᵃ','b' to 'ᵇ','c' to 'ᶜ','d' to 'ᵈ','e' to 'ᵉ','f' to 'ᶠ','g' to 'ᵍ','h' to 'ʰ','i' to 'ⁱ','j' to 'ʲ','k' to 'ᵏ','l' to 'ˡ','m' to 'ᵐ','n' to 'ⁿ','o' to 'ᵒ','p' to 'ᵖ','r' to 'ʳ','s' to 'ˢ','t' to 'ᵗ','u' to 'ᵘ','v' to 'ᵛ','w' to 'ʷ','x' to 'ˣ','y' to 'ʸ','z' to 'ᶻ','†' to '†')
    var supIter = 0
    while (supIter++ < 15) {
        val idx = text.indexOf("^{")
        if (idx == -1) break
        val arg = extractBraceArg(text, idx + 1) ?: break
        val converted = arg.first.map { supsMap[it] ?: it }.joinToString("")
        text = text.substring(0, idx) + converted + text.substring(arg.second)
    }
    text = text.replace(Regex("""\^([0-9a-zA-Z+\-†])""")) { m ->
        val c = m.groupValues[1].firstOrNull() ?: ' '
        (supsMap[c] ?: c).toString()
    }

    // Subscripts with balanced braces - clean standard map without tone mark
    val subsMap = mapOf('0' to '₀','1' to '₁','2' to '₂','3' to '₃','4' to '₄','5' to '₅','6' to '₆','7' to '₇','8' to '₈','9' to '₉','+' to '₊','-' to '₋','=' to '₌','(' to '₍',')' to '₎','a' to 'ₐ','e' to 'ₑ','h' to 'ₕ','i' to 'ᵢ','j' to 'ⱼ','k' to 'ₖ','l' to 'ₗ','m' to 'ₘ','n' to 'ₙ','o' to 'ₒ','p' to 'ₚ','r' to 'ᵣ','s' to 'ₛ','t' to 'ₜ','u' to 'ᵤ','v' to 'ᵥ','x' to 'ₓ')
    var subIter = 0
    while (subIter++ < 15) {
        val idx = text.indexOf("_{")
        if (idx == -1) break
        val arg = extractBraceArg(text, idx + 1) ?: break
        val converted = arg.first.map { subsMap[it] ?: it }.joinToString("")
        text = text.substring(0, idx) + converted + text.substring(arg.second)
    }
    text = text.replace(Regex("""_([0-9a-zA-Z+\-])""")) { m ->
        val c = m.groupValues[1].firstOrNull() ?: ' '
        (subsMap[c] ?: c).toString()
    }

    // Clean remaining delimiters and stray backslashes
    text = text.replace(Regex("""\\(?:left|right)(?![a-zA-Z])"""), "")
    text = text.replace("\$\$", "").replace("\$", "")
    text = text.replace("\\[", "").replace("\\]", "")
    text = text.replace("\\(", "").replace("\\)", "")
    text = text.replace(Regex("""\\+([a-zA-Z]+)""")) { it.groupValues[1] }
    text = text.replace("\\", "")

    val result = text.trim()
    latexUnicodeCache.put(raw, result)
    return result
}

// ─── Format LaTeX symbols into clean typography ──────────────────────────────
fun formatLatexSymbols(raw: String): String {
    if (raw.isBlank()) return ""
    var text = raw

    // Strip invisible LaTeX null delimiters and sizing prefixes
    text = text.replace(Regex("""\\(?:left|right)\."""), "")
    text = text.replace(Regex("""\\(?:left|right)(?![a-zA-Z])"""), "")
    text = text.replace(Regex("""\\(?:big|Big|bigg|Bigg)[lrm]?(?![a-zA-Z])"""), "")

    // Degree symbols: 0^\circ -> 0°, 180^\circ -> 180°, \degree -> °
    text = text.replace(Regex("""\^(?:\\circ|\{?\\circ\}?|°)"""), "°")
    text = text.replace("\\degree", "°").replace("\\circ", "°")

    // Text & Math font decorators
    text = text.replace(Regex("""\\(?:text|mathrm|operatorname\*?|mathbf|boldsymbol|bm|mathit|mathsf|textbf)\s*\{([^}]*)\}""")) { it.groupValues[1] }
    text = text.replace(Regex("""\\(?:mathcal|mathscr)\s*\{([^}]*)\}""")) { m ->
        val k = m.groupValues[1].trim()
        val calMap = mapOf("H" to "ℋ","E" to "ℰ","L" to "ℒ","M" to "ℳ","F" to "ℱ","O" to "𝒪","P" to "𝒫","D" to "𝒟","C" to "𝒞","N" to "𝒩","B" to "ℬ","A" to "𝒜")
        calMap[k] ?: k
    }
    val bbMap = mapOf("R" to "ℝ", "C" to "ℂ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "H" to "ℍ")
    for ((k, v) in bbMap) text = text.replace("\\mathbb{$k}", v).replace("\\mathbb $k", v)

    // Hats and accent marks
    val hats = mapOf(
        "\\hat{r}" to "r̂", "\\hat{p}" to "p̂", "\\hat{n}" to "n̂", "\\hat{i}" to "î", "\\hat{j}" to "ĵ", "\\hat{k}" to "k̂",
        "\\hat{x}" to "x̂", "\\hat{y}" to "ŷ", "\\hat{z}" to "ẑ", "\\hat r" to "r̂", "\\hat p" to "p̂", "\\hat n" to "n̂",
        "\\hat i" to "î", "\\hat j" to "ĵ", "\\hat k" to "k̂"
    )
    for ((k, v) in hats) text = text.replace(k, v)

    // General \hat{...}
    var hIter = 0
    while (hIter++ < 15) {
        val idx = text.indexOf("\\hat{")
        if (idx == -1) break
        val arg = extractBraceArg(text, idx) ?: break
        val baseClean = formatLatexSymbols(arg.first)
        text = text.substring(0, idx) + "${baseClean}̂" + text.substring(arg.second)
    }

    // Roots: \sqrt{...} and \sqrt[n]{...} with balanced braces
    val sqrtRegex = Regex("""\\?sqrt(?:\[([0-9]+)\])?\s*\{""")
    var sIter = 0
    while (sIter++ < 15) {
        val m = sqrtRegex.find(text) ?: break
        val startIdx = m.range.first
        val deg = m.groupValues[1]
        val arg = extractBraceArg(text, startIdx) ?: break
        val prefix = when (deg) { "3" -> "∛"; "4" -> "∜"; else -> "√" }
        val inner = formatLatexSymbols(arg.first)
        text = text.substring(0, startIdx) + "$prefix($inner)" + text.substring(arg.second)
    }

    // Fractions: \frac{num}{den} with balanced braces
    val fracRegex = Regex("""\\?(?:d|t|c)?frac\s*\{""")
    var fIter = 0
    while (fIter++ < 25) {
        val m = fracRegex.find(text) ?: break
        val startIdx = m.range.first
        val numArg = extractBraceArg(text, startIdx) ?: break
        val denArg = extractBraceArg(text, numArg.second) ?: break
        val num = formatLatexSymbols(numArg.first.trim())
        val den = formatLatexSymbols(denArg.first.trim())

        val rep = when {
            num == "1" && den == "2" -> "½"
            num == "1" && den == "3" -> "⅓"
            num == "2" && den == "3" -> "⅔"
            num == "1" && den == "4" -> "¼"
            num == "3" && den == "4" -> "¾"
            num == "1" && den == "5" -> "⅕"
            num == "2" && den == "5" -> "⅖"
            num == "3" && den == "5" -> "⅗"
            num == "4" && den == "5" -> "⅘"
            num == "1" && den == "6" -> "⅙"
            num == "5" && den == "6" -> "⅚"
            num == "1" && den == "8" -> "⅛"
            num == "3" && den == "8" -> "⅜"
            num == "5" && den == "8" -> "⅝"
            num == "7" && den == "8" -> "⅞"
            num.length == 1 && den.length == 1 && num.first().isLetterOrDigit() && den.first().isLetterOrDigit() -> "$num/$den"
            else -> {
                val n = if (num.contains(Regex("[+\\-\\s]")) && !num.startsWith("(") && !num.endsWith(")")) "($num)" else num
                val d = if (den.contains(Regex("[+\\-\\s\\\\]")) && !den.startsWith("(") && !den.endsWith(")")) "($den)" else den
                "$n / $d"
            }
        }
        text = text.substring(0, startIdx) + rep + text.substring(denArg.second)
    }
    text = text.replace(Regex("""\\?(?:d|t|c)?frac\b"""), "")

    val symbolPairs = listOf(
        "\\parallel" to "∥", "\\nparallel" to "∦", "\\perp" to "⊥",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←", "\\leftrightarrow" to "⇔",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\implies" to "⇒", "\\iff" to "⇔",
        "\\varepsilon_0" to "ε₀", "\\epsilon_0" to "ε₀",
        "\\mu_0" to "μ₀", "\\Phi_0" to "Φ₀", "\\nu_0" to "ν₀", "\\lambda_0" to "λ₀",
        "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\div" to "÷",
        "\\approx" to "≈", "\\equiv" to "≡", "\\neq" to "≠", "\\ne" to "≠",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\ll" to "≪", "\\gg" to "≫",
        "\\sim" to "∼", "\\simeq" to "≃", "\\cong" to "≅", "\\propto" to "∝",
        "\\notin" to "∉", "\\ni" to "∋", "\\in" to "∈",
        "\\subset" to "⊂", "\\supset" to "⊃", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
        "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅",
        "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄",
        "\\circ" to "°", "\\degree" to "°", "\\prime" to "′",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…",
        "\\hbar" to "ℏ", "\\dagger" to "†", "\\partial" to "∂", "\\nabla" to "∇", "\\infty" to "∞",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\vartheta" to "ϑ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν",
        "\\xi" to "ξ", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\varrho" to "ϱ",
        "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "ϕ",
        "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ",
        "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\cos" to "cos", "\\sin" to "sin", "\\tan" to "tan", "\\det" to "det",
        "\\gcd" to "gcd", "\\lim" to "lim", "\\ln" to "ln", "\\log" to "log", "\\exp" to "exp",
        "\\{" to "{", "\\}" to "}", "\\," to "\u2009", "\\;" to " ", "\\:" to " ", "\\!" to "",
        "\\quad" to " ", "\\qquad" to "  "
    )
    for ((tag, rep) in symbolPairs) {
        if (tag.length > 2 && tag[1].isLetter()) {
            text = text.replace(Regex("""\Q$tag\E(?![a-zA-Z])"""), rep)
        } else {
            text = text.replace(tag, rep)
        }
    }
    // Handle \cdot ensuring proper spacing even when followed by units like \cdotm/A
    text = text.replace(Regex("""\\cdot\s*"""), " · ")

    // Clean any remaining command backslashes without corrupting mathematical letters
    text = text.replace(Regex("""\\+([a-zA-Z]+)""")) { it.groupValues[1] }
    text = text.replace("\\", "")
    return text.trim()
}

// ─── Native Rich AnnotatedString Math Builder (Subscripts & Superscripts) ────
fun buildAnnotatedMathString(raw: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    if (raw.isBlank()) return androidx.compose.ui.text.AnnotatedString("")
    val vecRegex = Regex("""\\vec(?:\s*\{([^}]+)\}|\s+([a-zA-Z])|([a-zA-Z]))(?:\s*_(?:\{([^}]+)\}|([a-zA-Z0-9])))?""")

    return buildAnnotatedString {
        var cursor = 0
        val matches = vecRegex.findAll(raw).toList()
        for (m in matches) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > cursor) {
                appendMathSegment(raw.substring(cursor, start), baseColor)
            }
            val base = (m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }).trim()
            val sub = (m.groupValues[4].ifEmpty { m.groupValues[5] }).trim()
            val cleanBase = formatLatexSymbols(base)
            val cleanSub = if (sub.isNotEmpty()) formatLatexSymbols(sub) else ""

            withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)) {
                append(cleanBase)
            }
            withStyle(SpanStyle(fontFamily = FontFamily.Default, fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 0.72.em, baselineShift = BaselineShift.Superscript)) {
                append("→")
            }
            if (cleanSub.isNotEmpty()) {
                withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 0.72.em, baselineShift = BaselineShift(-0.25f))) {
                    append(cleanSub)
                }
            }
            cursor = end
        }
        if (cursor < raw.length) {
            appendMathSegment(raw.substring(cursor), baseColor)
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendMathSegment(segment: String, baseColor: Color) {
    if (segment.isEmpty()) return
    val text = formatLatexSymbols(segment)
    var i = 0
    val n = text.length
    val normalBuf = StringBuilder()

    fun flushNormal() {
        if (normalBuf.isNotEmpty()) {
            append(normalBuf.toString())
            normalBuf.setLength(0)
        }
    }

    while (i < n) {
        val c = text[i]
        if (c == '_' || c == '^') {
            val isSub = (c == '_')
            val startIdx = i + 1
            if (startIdx < n && text[startIdx] == '{') {
                val close = text.indexOf('}', startIdx + 1)
                if (close != -1) {
                    flushNormal()
                    val inner = text.substring(startIdx + 1, close)
                    withStyle(
                        SpanStyle(
                            baselineShift = if (isSub) BaselineShift(-0.25f) else BaselineShift.Superscript,
                            fontSize = 0.72.em
                        )
                    ) {
                        append(formatLatexSymbols(inner))
                    }
                    i = close + 1
                    continue
                }
            } else if (startIdx < n && (text[startIdx].isLetterOrDigit() || text[startIdx] == '+' || text[startIdx] == '-' || text[startIdx] == '°' || text[startIdx] == '′' || text[startIdx] == '″' || text[startIdx] == '∥' || text[startIdx] == '⊥')) {
                flushNormal()
                val ch = text[startIdx]
                withStyle(
                    SpanStyle(
                        baselineShift = if (isSub) BaselineShift(-0.25f) else BaselineShift.Superscript,
                        fontSize = 0.72.em
                    )
                ) {
                    append(ch.toString())
                }
                i = startIdx + 1
                continue
            }
            // Neither matched: do NOT advance past startIdx!
            normalBuf.append(c)
            i++
            continue
        }
        normalBuf.append(c)
        i++
    }
    flushNormal()
}

// ─── Category badge helper ────────────────────────────────────────────────────
fun detectMathCategory(formula: String): String {
    val f = formula.lowercase()
    return when {
        f.contains("matrix") || f.contains("pmatrix") || f.contains("bmatrix") || f.contains("det(") -> "Matrix"
        f.contains("\\int") || f.contains("\\iint") || f.contains("\\iiint") || f.contains("\\oint") -> "Integral"
        f.contains("\\sum") || f.contains("\\prod") -> "Series"
        f.contains("\\frac{d") || f.contains("\\partial") || f.contains("\\nabla") -> "Calculus"
        f.contains("\\vec") || f.contains("\\hat") || f.contains("\\dot") -> "Vectors"
        f.contains("\\ket") || f.contains("\\bra") || f.contains("\\hbar") -> "Quantum"
        f.contains("\\lim") -> "Limit"
        f.contains("=") -> "Equation"
        else -> "Formula"
    }
}

// ─── Pure-equation-line heuristic ────────────────────────────────────────────
fun isPureEquationLine(raw: String): Boolean {
    var s = raw.trim()
    if (s.isBlank() || s.startsWith("#") || s.startsWith(">") || s.startsWith("|") || s.startsWith("```") ||
        s.contains("](") || s.contains("][") || s.startsWith("![") || s.startsWith("<")
    ) return false

    // 1. Prose label prefix with colon (e.g. "Relation to speed of light:", "Note:", "For example:")
    if (Regex("""^[a-zA-Z\s]{3,}\s*:""").containsMatchIn(s)) return false

    // 2. If line has inline $...$ with surrounding prose, it is prose!
    if (s.contains("$")) {
        val cleanDollar = s.trimEnd('.', ',', ';').trim()
        val isEntirelyDollar = (
            (cleanDollar.startsWith("$$") && cleanDollar.endsWith("$$") && cleanDollar.length >= 4) ||
            (cleanDollar.startsWith("$") && cleanDollar.endsWith("$") && cleanDollar.length >= 2)
        )
        if (!isEntirelyDollar) return false
    }

    // Strip enclosing single or double dollars or brackets if present
    var wasDelimited = false
    if (s.startsWith("$$") && s.endsWith("$$") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
        wasDelimited = true
    } else if (s.startsWith("$") && s.endsWith("$") && s.length >= 2) {
        s = s.substring(1, s.length - 1).trim()
        wasDelimited = true
    } else if (s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
        wasDelimited = true
    }

    if (s.contains("**") || s.contains("~~")) return false
    if (s.matches(Regex("""^\d+\.\s+.*""")) || s.startsWith("- ") || s.startsWith("* ")) return false

    // If it's a currency like "$50" or "100 USD", not an equation
    if (s.matches(Regex("""^\d+(?:[.,]\d+)?\s*(?:USD|EUR|GBP|INR|dollars?|cents?)?$"""))) return false

    // Strip trailing bracketed unit descriptions like [SI Unit: A*m^2 or J*T^-1]
    val sClean = s.replace(Regex("""\[\s*(?:SI\s+)?(?:Unit|unit)[^\]]*\]"""), "").trim()
    // Strip subscripts and superscripts for prose word analysis
    val sNoSub = sClean.replace(Regex("""_[{][^}]*[}]|_([a-zA-Z0-9])"""), "")
        .replace(Regex("""\^[{][^}]*[}]|\^([a-zA-Z0-9])"""), "")

    val latexKeywords = setOf(
        "frac", "dfrac", "tfrac", "cfrac", "sqrt", "sum", "int", "iint", "iiint", "oint",
        "prod", "partial", "hbar", "dagger", "ket", "bra", "braket", "hat", "vec",
        "dot", "ddot", "bar", "tilde", "alpha", "beta", "gamma", "delta", "epsilon",
        "varepsilon", "zeta", "eta", "theta", "vartheta", "iota", "kappa", "lambda",
        "mu", "nu", "xi", "pi", "varpi", "rho", "varrho", "sigma", "varsigma", "tau",
        "upsilon", "phi", "varphi", "chi", "psi", "omega", "Delta", "Theta", "Lambda",
        "Sigma", "Phi", "Psi", "Omega", "mathcal", "mathscr", "mathbf", "mathbb",
        "mathrm", "operatorname", "left", "right", "pm", "times", "cdot", "div",
        "infty", "approx", "equiv", "neq", "le", "ge", "leq", "geq", "ll", "gg",
        "sim", "to", "rightarrow", "leftarrow", "Rightarrow", "implies", "iff", "in",
        "notin", "cos", "sin", "tan", "sec", "csc", "cot", "det", "exp", "ln",
        "log", "lim", "text", "unit", "si", "quad", "qquad", "parallel", "perp"
    )

    val rawTokens = Regex("""[a-zA-Z]+""").findAll(sNoSub).map { it.value }.toList()
    if (rawTokens.isEmpty()) return false

    // Treat uppercase tokens like NAB, BIL, EMF, RMS as variables
    val proseWords = rawTokens.filter {
        val lower = it.lowercase()
        lower !in latexKeywords && it.length >= 3 && it != it.uppercase()
    }

    val mathTokens = listOf(
        "\\frac","frac{","\\int","int_","\\sum","sum_","\\prod","prod_","\\sqrt","sqrt{",
        "\\partial","\\nabla","\\hbar","\\dagger","\\ket{","\\bra{","\\braket{",
        "\\hat{","\\vec{","\\dot{","\\ddot{","\\bar{","\\tilde{",
        "\\alpha","\\beta","\\gamma","\\delta","\\epsilon","\\varepsilon","\\theta","\\lambda",
        "\\mu","\\nu","\\pi","\\rho","\\sigma","\\tau","\\phi","\\varphi","\\psi","\\omega",
        "\\Delta","\\Theta","\\Lambda","\\Sigma","\\Phi","\\Psi","\\Omega",
        "\\mathcal","\\mathscr","\\mathbf","\\mathbb","\\mathrm","\\operatorname","\\adj","\\det",
        "\\left","\\right","\\pm","\\times","\\cdot","\\infty","\\approx","\\equiv","\\neq",
        "\\le","\\ge","\\in","\\to","\\vmatrix","\\bmatrix","\\pmatrix","\\parallel","\\perp"
    )
    val hasMathToken = mathTokens.any { sClean.contains(it) }
    val hasMathRelation = sClean.contains("=") || sClean.contains("\\approx") || sClean.contains("\\sim") ||
        sClean.contains("\\le") || sClean.contains("\\ge") || sClean.contains("\\in") || sClean.contains("\\to") ||
        sClean.contains("\\neq") || sClean.contains("\\Rightarrow") || sClean.contains("\\implies") ||
        sClean.contains("<") || sClean.contains(">") || sClean.contains("+") || sClean.contains("-")

    if (wasDelimited && proseWords.isEmpty()) return true

    if (hasMathToken && (hasMathRelation || sClean.startsWith("\\frac") || sClean.startsWith("frac{") || sClean.startsWith("\\int") || sClean.startsWith("\\sum") || sClean.contains("\\vmatrix") || sClean.contains("\\bmatrix")) && proseWords.isEmpty()) return true
    if (sClean.contains("=") && (sClean.contains("\\") || sClean.contains("^") || sClean.contains("_") || sClean.contains("[") || sClean.contains("{") || sClean.contains("|")) && proseWords.isEmpty()) return true
    return false
}

// ═══════════════════════════════════════════════════════════════════════════════
//  KaTeX WebView — clean, single-page, no bitmap hacks
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * AndroidBridge that receives document.body.scrollHeight from the HTML page.
 */
class KaTeXBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(h: Int) { onHeight(h) }
    // Legacy compat
    @JavascriptInterface
    fun onRenderComplete(w: Double, h: Double) { onHeight(h.toInt()) }
    @JavascriptInterface
    fun onSize(w: Double, h: Double) { onHeight(h.toInt()) }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Native Compose Stacked Fraction & Math Views (Textbook vinculum layout)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Renders a stacked mathematical fraction with a crisp horizontal division bar
 * (vinculum), centering the numerator above and denominator below.
 */
@Composable
fun StackedFractionView(
    numerator: String,
    denominator: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val normNum = remember(numerator) { normalizeLatexFormula(numerator) }
    val normDen = remember(denominator) { normalizeLatexFormula(denominator) }
    val numTokens = remember(normNum) { tokenizeMathFormula(normNum) }
    val denTokens = remember(normDen) { tokenizeMathFormula(normDen) }
    val hasComplexNum = remember(numTokens) { numTokens.any { it !is MathToken.Text } }
    val hasComplexDen = remember(denTokens) { denTokens.any { it !is MathToken.Text } }

    Column(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Numerator
        if (hasComplexNum) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 1.dp)) {
                numTokens.forEach { token ->
                    when (token) {
                        is MathToken.Text -> {
                            val formatted = remember(token.text, textColor) { buildAnnotatedMathString(token.text, textColor) }
                            if (formatted.isNotBlank()) {
                                Text(
                                    text = formatted,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontSize = 14.5.sp,
                                        lineHeight = 18.sp
                                    ),
                                    color = textColor
                                )
                            }
                        }
                        is MathToken.Fraction -> StackedFractionView(numerator = token.numerator, denominator = token.denominator, textColor = textColor)
                        is MathToken.Radical -> RadicalEquationView(degree = token.degree, content = token.content, textColor = textColor)
                        is MathToken.Vector -> VectorSymbolView(base = token.base, subscript = token.subscript, textColor = textColor)
                    }
                }
            }
        } else {
            val formattedNum = remember(normNum, textColor) { buildAnnotatedMathString(normNum, textColor) }
            Text(
                text = formattedNum,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    lineHeight = 18.sp
                ),
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }

        // Solid horizontal fraction line (vinculum) "------"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp, horizontal = 1.dp)
                .height(1.8.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(textColor.copy(alpha = 0.85f))
        )

        // Denominator
        if (hasComplexDen) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
                denTokens.forEach { token ->
                    when (token) {
                        is MathToken.Text -> {
                            val formatted = remember(token.text, textColor) { buildAnnotatedMathString(token.text, textColor) }
                            if (formatted.isNotBlank()) {
                                Text(
                                    text = formatted,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontSize = 14.5.sp,
                                        lineHeight = 18.sp
                                    ),
                                    color = textColor
                                )
                            }
                        }
                        is MathToken.Fraction -> StackedFractionView(numerator = token.numerator, denominator = token.denominator, textColor = textColor)
                        is MathToken.Radical -> RadicalEquationView(degree = token.degree, content = token.content, textColor = textColor)
                        is MathToken.Vector -> VectorSymbolView(base = token.base, subscript = token.subscript, textColor = textColor)
                    }
                }
            }
        } else {
            val formattedDen = remember(normDen, textColor) { buildAnnotatedMathString(normDen, textColor) }
            Text(
                text = formattedDen,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    lineHeight = 18.sp
                ),
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/**
 * Vector symbol with an authentic centered overhead arrow and subscript alignment.
 */
@Composable
fun VectorSymbolView(
    base: String,
    subscript: String = "",
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val cleanBase = remember(base) { formatLatexSymbols(base) }
    val cleanSub  = remember(subscript) { if (subscript.isNotEmpty()) formatLatexSymbols(subscript) else "" }

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.padding(horizontal = 2.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Overhead vector arrow centered above base
            Text(
                text = "→",
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    lineHeight = 10.sp
                ),
                color = textColor.copy(alpha = 0.9f)
            )
            // Base symbol
            Text(
                text = cleanBase,
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    lineHeight = 19.sp
                ),
                color = textColor
            )
        }
        if (cleanSub.isNotBlank()) {
            Text(
                text = cleanSub,
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.5.sp,
                    lineHeight = 13.sp
                ),
                color = textColor,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

/**
 * Radical expression view (square root / nth-root) with authentic vinculum overbar.
 */
@Composable
fun RadicalEquationView(
    degree: String?,
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val tokens = remember(content) { tokenizeMathFormula(content) }
    val hasFraction = remember(tokens) { tokens.any { it is MathToken.Fraction } }

    Row(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!degree.isNullOrBlank()) {
            Text(
                text = degree,
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                ),
                color = textColor,
                modifier = Modifier.padding(end = 1.dp, bottom = if (hasFraction) 14.dp else 6.dp)
            )
        }
        // Radical symbol (scaled to fit fraction height if stacked)
        Text(
            text = "√",
            style = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Light,
                fontSize = if (hasFraction) 32.sp else 20.sp,
                lineHeight = if (hasFraction) 34.sp else 22.sp
            ),
            color = textColor
        )
        // Overhead vinculum line & content
        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.8.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(textColor.copy(alpha = 0.85f))
            )
            Row(
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tokens.forEach { token ->
                    when (token) {
                        is MathToken.Text -> {
                            val formatted = remember(token.text, textColor) {
                                buildAnnotatedMathString(token.text, textColor)
                            }
                            if (formatted.isNotBlank()) {
                                Text(
                                    text = formatted,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 16.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = textColor
                                )
                            }
                        }
                        is MathToken.Fraction -> {
                            StackedFractionView(
                                numerator = token.numerator,
                                denominator = token.denominator,
                                textColor = textColor
                            )
                        }
                        is MathToken.Radical -> {
                            RadicalEquationView(
                                degree = token.degree,
                                content = token.content,
                                textColor = textColor
                            )
                        }
                        is MathToken.Vector -> {
                            VectorSymbolView(
                                base = token.base,
                                subscript = token.subscript,
                                textColor = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Native Compose mathematical equation view:
 * Tokenizes LaTeX formulas into text terms, stacked fractions, radicals, and vectors,
 * aligning math operators with the fraction line for an authentic textbook appearance.
 */
@Composable
fun NativeMathEquationView(
    formula: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val tokens = remember(formula) { tokenizeMathFormula(formula) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        tokens.forEach { token ->
            when (token) {
                is MathToken.Text -> {
                    val formatted = remember(token.text, textColor) {
                        buildAnnotatedMathString(token.text, textColor)
                    }
                    if (formatted.isNotBlank()) {
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                fontSize = 17.sp,
                                letterSpacing = 0.2.sp
                            ),
                            color = textColor
                        )
                    }
                }
                is MathToken.Fraction -> {
                    StackedFractionView(
                        numerator = token.numerator,
                        denominator = token.denominator,
                        textColor = textColor
                    )
                }
                is MathToken.Radical -> {
                    RadicalEquationView(
                        degree = token.degree,
                        content = token.content,
                        textColor = textColor
                    )
                }
                is MathToken.Vector -> {
                    VectorSymbolView(
                        base = token.base,
                        subscript = token.subscript,
                        textColor = textColor
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXMathView(
    formula: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    isDisplayMode: Boolean = true
) {
    val density = LocalDensity.current

    // Start at a sensible default height so the layout doesn't collapse
    var heightDp by remember(formula) { mutableStateOf(if (isDisplayMode) 52.dp else 28.dp) }
    var isReady  by remember { mutableStateOf(false) }

    // Track the WebView instance and page-loaded state
    var webViewRef     by remember { mutableStateOf<WebView?>(null) }
    var pageFinished   by remember { mutableStateOf(false) }

    // Whenever formula / dark-mode / page changes → inject renderMath()
    LaunchedEffect(formula, isDark, isDisplayMode, pageFinished) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!pageFinished) return@LaunchedEffect
        val safe = JSONObject.quote(formula)
        wv.post {
            wv.evaluateJavascript(
                "window.renderMath($safe, $isDisplayMode, $isDark);",
                null
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isDisplayMode) Modifier.height(heightDp) else Modifier.wrapContentHeight()),
        contentAlignment = Alignment.CenterStart
    ) {
        // ── Native Compose math fallback (shown with stacked fractions while WebView loads) ──
        if (!isReady) {
            NativeMathEquationView(
                formula   = formula,
                textColor = if (isDark) Color(0xFFECECF1) else Color(0xFF0D0D0D),
                modifier  = Modifier.fillMaxWidth()
            )
        }

        // ── KaTeX WebView ────────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0x00000000)           // fully transparent
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess  = true
                    settings.allowContentAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.useWideViewPort  = false
                    settings.loadWithOverviewMode = false
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled   = false
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    val bridge = KaTeXBridge { hPx ->
                        post {
                            if (hPx > 0) {
                                val newDp = with(density) { (hPx + 6).coerceIn(24, 1200).toDp() }
                                if (newDp != heightDp) heightDp = newDp
                                isReady = true
                            }
                        }
                    }
                    addJavascriptInterface(bridge, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            pageFinished = true
                            val safe = JSONObject.quote(formula)
                            view?.evaluateJavascript("window.renderMath($safe, $isDisplayMode, $isDark);", null)
                        }
                    }

                    loadUrl("file:///android_asset/katex/katex_container.html")
                    webViewRef = this
                }
            },
            update = { wv ->
                webViewRef = wv
                if (pageFinished) {
                    val safe = JSONObject.quote(formula)
                    wv.post {
                        wv.evaluateJavascript(
                            "window.renderMath($safe, $isDisplayMode, $isDark);",
                            null
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isDisplayMode) Modifier.height(heightDp) else Modifier.wrapContentHeight())
                .alpha(if (isReady) 1f else 0.01f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Math Equation Block (ChatGPT-style unboxed display math)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun MathEquationBlockView(
    formula: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val context     = LocalContext.current
    val haptic      = LocalHapticFeedback.current
    val normFormula = remember(formula) { normalizeLatexFormula(formula) }

    // Pure unboxed equation container matching ChatGPT:
    // No box, no card background, no border, no category badges, no "formula no." labels.
    // Long-press anywhere on the equation directly copies clean LaTeX with haptic feedback & toast.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp, start = 2.dp, end = 2.dp)
            .pointerInput(normFormula) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("LaTeX Formula", normFormula))
                        Toast.makeText(context, "Formula copied", Toast.LENGTH_SHORT).show()
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        DisableSelection {
            NativeMathEquationView(
                formula   = normFormula,
                textColor = textColor,
                modifier  = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Main MarkdownContent composable
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null
) {
    val cleanText = remember(text) { sanitizeMarkdownInput(text) }
    val sections  = remember(cleanText, isStreaming) {
        parseMarkdownBlocks(cleanText, skipCache = isStreaming)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sections.forEachIndexed { index, section ->
            val isLast             = index == sections.lastIndex
            val isTrailingStreaming = isStreaming && isLast

            key(index, section.javaClass.name) {
                when (section) {
                    is MarkdownBlock.Artifact -> {
                        InlineArtifactCardView(
                            artifact     = section,
                            onOpenArtifact = { onLinkClick?.invoke(section.identifier) }
                        )
                        if (isTrailingStreaming) StreamingCursorBox()
                    }
                    is MarkdownBlock.MathEquation -> {
                        MathEquationBlockView(formula = section.formula, textColor = textColor)
                        if (isTrailingStreaming) StreamingCursorBox()
                    }
                    is MarkdownBlock.Code -> {
                        CodeBlockView(language = section.language, code = section.code)
                        if (isTrailingStreaming) StreamingCursorBox()
                    }
                    is MarkdownBlock.Table -> {
                        TableBlockView(headers = section.headers, rows = section.rows, onLinkClick = onLinkClick)
                        if (isTrailingStreaming) StreamingCursorBox()
                    }
                    is MarkdownBlock.Heading -> {
                        val style = when (section.level) {
                            1    -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold,     letterSpacing = (-0.4).sp, lineHeight = 28.sp, fontSize = 22.sp)
                            2    -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, lineHeight = 24.sp, fontSize = 18.sp)
                            3    -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold,  letterSpacing = (-0.1).sp, lineHeight = 22.sp, fontSize = 15.sp)
                            4    -> MaterialTheme.typography.bodyLarge.copy(fontWeight  = FontWeight.SemiBold,  letterSpacing = 0.sp,      lineHeight = 21.sp, fontSize = 14.sp)
                            else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold,  letterSpacing = 0.sp,      lineHeight = 20.sp, fontSize = 13.sp)
                        }
                        FormattedMarkdownText(
                            text              = section.text,
                            style             = style,
                            color             = textColor,
                            modifier          = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            showTrailingCursor = isTrailingStreaming,
                            onLinkClick       = onLinkClick
                        )
                    }
                    is MarkdownBlock.Blockquote -> {
                        BlockquoteView(
                            text              = section.text,
                            textColor         = textColor,
                            isTrailingStreaming = isTrailingStreaming,
                            onLinkClick       = onLinkClick
                        )
                    }
                    is MarkdownBlock.ListBlock -> {
                        Column(
                            modifier              = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalArrangement   = Arrangement.spacedBy(4.dp)
                        ) {
                            section.items.forEachIndexed { itemIdx, item ->
                                val isItemLast = itemIdx == section.items.lastIndex
                                ListItemRow(
                                    item              = item,
                                    textColor         = textColor,
                                    showTrailingCursor = isTrailingStreaming && isItemLast,
                                    onLinkClick       = onLinkClick
                                )
                            }
                        }
                    }
                    is MarkdownBlock.ListItem -> {
                        ListItemRow(
                            item              = section,
                            textColor         = textColor,
                            showTrailingCursor = isTrailingStreaming,
                            onLinkClick       = onLinkClick
                        )
                    }
                    is MarkdownBlock.Divider -> {
                        HorizontalDivider(
                            modifier  = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            thickness = 0.8.dp,
                            color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    }
                    is MarkdownBlock.Paragraph -> {
                        FormattedMarkdownText(
                            text              = section.text,
                            style             = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                            color             = textColor,
                            showTrailingCursor = isTrailingStreaming,
                            onLinkClick       = onLinkClick
                        )
                    }
                }
            }
        }
    }
}

// ─── Small helpers extracted for readability ───────────────────────────────────

@Composable
private fun StreamingCursorBox() {
    Box(modifier = Modifier.padding(top = 2.dp)) { StreamingCursorBlink() }
}

@Composable
private fun ListItemRow(
    item: MarkdownBlock.ListItem,
    textColor: Color,
    showTrailingCursor: Boolean,
    onLinkClick: ((String) -> Unit)?
) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (item.isOrdered) {
            Text(
                text     = "${item.index}.",
                style    = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color    = ClaudeTerracotta,
                modifier = Modifier.width(22.dp)
            )
        } else {
            Box(modifier = Modifier.padding(top = 8.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(ClaudeTerracotta))
        }
        FormattedMarkdownText(
            text              = item.text,
            style             = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
            color             = textColor,
            modifier          = Modifier.weight(1f),
            showTrailingCursor = showTrailingCursor,
            onLinkClick       = onLinkClick
        )
    }
}

@Composable
private fun BlockquoteView(
    text: String,
    textColor: Color,
    isTrailingStreaming: Boolean,
    onLinkClick: ((String) -> Unit)?
) {
    val isCallout = text.startsWith("[!") && text.contains("]")
    if (isCallout) {
        val calloutType = text.substringAfter("[!").substringBefore("]").uppercase()
        val calloutBody = text.substringAfter("]").trim()
        val (calloutColor, calloutIcon, calloutTitle) = when (calloutType) {
            "NOTE"      -> Triple(ChatGptBlue,         Icons.Default.Info,         "Note")
            "TIP"       -> Triple(ChatGptEmerald,      Icons.Default.Lightbulb,    "Tip")
            "WARNING"   -> Triple(ChatGptAmber,        Icons.Default.Warning,      "Warning")
            "IMPORTANT" -> Triple(ClaudeTerracotta,    Icons.Default.PriorityHigh, "Important")
            "CAUTION"   -> Triple(MaterialTheme.colorScheme.error, Icons.Default.Error, "Caution")
            else        -> Triple(ClaudeTerracotta, Icons.Default.Info, calloutType.lowercase().replaceFirstChar { it.uppercase() })
        }
        Surface(
            shape    = RoundedCornerShape(10.dp),
            color    = calloutColor.copy(alpha = 0.08f),
            border   = androidx.compose.foundation.BorderStroke(1.dp, calloutColor.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(calloutIcon, contentDescription = null, tint = calloutColor, modifier = Modifier.size(15.dp))
                    Text(
                        text  = calloutTitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = calloutColor
                    )
                }
                if (calloutBody.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    val innerBlocks = remember(calloutBody) { parseMarkdownBlocks(calloutBody) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        innerBlocks.forEach { block ->
                            when (block) {
                                is MarkdownBlock.MathEquation -> {
                                    MathEquationBlockView(formula = block.formula, textColor = textColor)
                                }
                                is MarkdownBlock.Paragraph -> {
                                    FormattedMarkdownText(
                                        text              = block.text,
                                        style             = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color             = textColor,
                                        showTrailingCursor = isTrailingStreaming,
                                        onLinkClick       = onLinkClick
                                    )
                                }
                                is MarkdownBlock.ListBlock -> {
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        block.items.forEach { item ->
                                            ListItemRow(item = item, textColor = textColor, showTrailingCursor = false, onLinkClick = onLinkClick)
                                        }
                                    }
                                }
                                else -> {
                                    FormattedMarkdownText(
                                        text              = block.toString(),
                                        style             = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color             = textColor,
                                        showTrailingCursor = isTrailingStreaming,
                                        onLinkClick       = onLinkClick
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .drawBehind {
                    drawRoundRect(
                        color        = ClaudeTerracotta,
                        topLeft      = Offset(0f, 0f),
                        size         = Size(3.5.dp.toPx(), size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 10.dp)
        ) {
            val innerBlocks = remember(text) { parseMarkdownBlocks(text) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                innerBlocks.forEach { block ->
                    when (block) {
                        is MarkdownBlock.MathEquation -> {
                            MathEquationBlockView(formula = block.formula, textColor = textColor)
                        }
                        is MarkdownBlock.Paragraph -> {
                            FormattedMarkdownText(
                                text              = block.text,
                                style             = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, lineHeight = 22.sp),
                                color             = MaterialTheme.colorScheme.onSurfaceVariant,
                                showTrailingCursor = isTrailingStreaming,
                                onLinkClick       = onLinkClick
                            )
                        }
                        else -> {
                            FormattedMarkdownText(
                                text              = block.toString(),
                                style             = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, lineHeight = 22.sp),
                                color             = MaterialTheme.colorScheme.onSurfaceVariant,
                                showTrailingCursor = isTrailingStreaming,
                                onLinkClick       = onLinkClick
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Inline text renderer — uses Unicode math for short/simple $...$,
//  and a tiny KaTeX WebView for \(...\) and $$...$$ inside paragraphs
// ═══════════════════════════════════════════════════════════════════════════════

private val inlineMarkdownRegex = Regex(
    "(\\*\\*(.+?)\\*\\*|" +
    "\\*(.+?)\\*|" +
    "~~(.+?)~~|" +
    "`(.+?)`|" +
    "\\[(.+?)\\]\\((.+?)\\)|" +
    "\\$\\$([\\s\\S]+?)\\$\\$|" +
    "\\$([^$\n]+?)\\$|" +
    "\\\\\\(([\\s\\S]+?)\\\\\\)|" +
    "\\\\\\[([\\s\\S]+?)\\\\\\])"
)

/**
 * Builds a rich [AnnotatedString] for inline markdown.
 * Inline `$...$` and `\(...\)` use the Unicode converter (fast, no WebView).
 * Display `$$...$$` and `\[...\]` inside a paragraph are also Unicode-converted
 * here — those are rare; the parser catches most of them as standalone blocks.
 */
fun buildFormattedInlineTextInternal(
    raw: String,
    baseColor: Color,
    isDark: Boolean
): androidx.compose.ui.text.AnnotatedString {
    if (raw.isBlank()) return androidx.compose.ui.text.AnnotatedString("")
    val cacheKey = "$isDark:${baseColor.value}:$raw"
    inlineTextCache.get(cacheKey)?.let { return it }

    val inlineCodeBg   = if (isDark) Color(0xFF2C2B27) else Color(0xFFEFECE5)
    val inlineCodeText = if (isDark) Color(0xFFF0EBE1) else Color(0xFF9C4927)
    val mathColor      = if (isDark) Color(0xFFDFDFF5) else Color(0xFF202050)

    val built = buildAnnotatedString {
        var cursor = 0
        for (match in inlineMarkdownRegex.findAll(raw)) {
            val start = match.range.first
            val end   = match.range.last + 1
            if (start > cursor) append(raw.substring(cursor, start))

            val full = match.value
            when {
                full.startsWith("**") -> {
                    val content = match.groupValues.getOrNull(2) ?: ""
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(content.replace(Regex("""\$([^$]+)\$""")) { formatLatexToUnicode(it.groupValues[1]) })
                    }
                }
                full.startsWith("*") -> {
                    val content = match.groupValues.getOrNull(3) ?: ""
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(content.replace(Regex("""\$([^$]+)\$""")) { formatLatexToUnicode(it.groupValues[1]) })
                    }
                }
                full.startsWith("~~") -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = baseColor.copy(alpha = 0.6f))) {
                        append(match.groupValues.getOrNull(4) ?: "")
                    }
                }
                full.startsWith("`") -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBg, color = inlineCodeText, fontSize = 13.sp)) {
                        append(" ${match.groupValues.getOrNull(5) ?: ""} ")
                    }
                }
                full.startsWith("[") -> {
                    val linkText = match.groupValues.getOrNull(6) ?: ""
                    val linkUrl  = match.groupValues.getOrNull(7) ?: ""
                    pushStringAnnotation(tag = "URL", annotation = linkUrl)
                    withStyle(SpanStyle(color = ChatGptBlue, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) {
                        append(linkText)
                    }
                    pop()
                }
                // Display $$ or \[ inside paragraph → Unicode (block parser catches most)
                full.startsWith("\$\$") || full.startsWith("\\[") -> {
                    val mathContent = (match.groupValues.getOrNull(8) ?: "") + (match.groupValues.getOrNull(11) ?: "")
                    withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, color = mathColor, letterSpacing = 0.3.sp)) {
                        append(" ")
                        append(buildAnnotatedMathString(mathContent, mathColor))
                        append(" ")
                    }
                }
                // Inline $ or \(
                full.startsWith("\$") || full.startsWith("\\(") -> {
                    val mathContent = (match.groupValues.getOrNull(9) ?: "") + (match.groupValues.getOrNull(10) ?: "")
                    val trimmed = mathContent.trim()
                    // Don't render currency or plain words as math
                    val isCurrency = trimmed.matches(Regex("""^\d+(?:[.,]\d+)?\s*(?:USD|EUR|GBP|INR|dollars?|cents?)?$"""))
                    val isPlainWord = trimmed.all { it.isLetter() || it.isWhitespace() } && trimmed.contains(" ")
                    if (isCurrency || isPlainWord) {
                        append(full)
                    } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, color = mathColor, letterSpacing = 0.2.sp)) {
                            append(buildAnnotatedMathString(trimmed, mathColor))
                        }
                    }
                }
                else -> append(full)
            }
            cursor = end
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }
    inlineTextCache.put(cacheKey, built)
    return built
}

@Composable
fun buildFormattedInlineText(raw: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    return remember(raw, baseColor, isDark) { buildFormattedInlineTextInternal(raw, baseColor, isDark) }
}

// ─── FormattedMarkdownText ────────────────────────────────────────────────────
@Composable
fun FormattedMarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    showTrailingCursor: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "inlineCursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.15f,
        animationSpec = infiniteRepeatable(animation = tween(550, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "inlineCursorAlpha"
    )

    val isDark            = MaterialTheme.colorScheme.background.red < 0.5f
    val baseAnnotated     = remember(text, color, isDark) { buildFormattedInlineTextInternal(text, color, isDark) }
    val annotatedString   = if (showTrailingCursor) {
        buildAnnotatedString {
            append(baseAnnotated)
            withStyle(SpanStyle(color = ClaudeTerracotta.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold)) { append(" ▍") }
        }
    } else baseAnnotated

    val hasUrl          = remember(annotatedString) {
        annotatedString.getStringAnnotations("URL", 0, annotatedString.length).isNotEmpty()
    }
    val uriHandler      = LocalUriHandler.current
    var layoutResult    by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text     = annotatedString,
        style    = style,
        color    = color,
        modifier = if (hasUrl) modifier.pointerInput(annotatedString) {
            detectTapGestures { offset ->
                layoutResult?.let { layout ->
                    val off = layout.getOffsetForPosition(offset)
                    annotatedString.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                        if (onLinkClick != null) onLinkClick(ann.item)
                        else try { uriHandler.openUri(ann.item) } catch (_: Throwable) {}
                    }
                }
            }
        } else modifier,
        onTextLayout = { layoutResult = it }
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Syntax highlighter (unchanged — already solid)
// ═══════════════════════════════════════════════════════════════════════════════
object SyntaxHighlighter {
    private val keywords = setOf(
        "abstract","as","async","await","break","case","catch","class","const","continue",
        "data","default","def","delete","do","elif","else","enum","export","extends","false",
        "fi","final","finally","fn","for","from","fun","function","if","implements","import",
        "in","inline","instanceof","interface","internal","is","let","match","mut","new",
        "nil","none","null","object","open","operator","out","override","package","private",
        "protected","public","return","sealed","select","self","static","struct","super",
        "suspend","switch","then","this","throw","true","try","type","typeof","val","var",
        "void","when","where","while","yield","echo","printf","insert","update",
        "create","table","alter","drop","join","True","False","None","cls",
        "with","lambda","pass","raise","except","global","nonlocal","assert","and","or","not"
    )
    private val commonTypes = setOf(
        "Int","Long","Float","Double","String","Boolean","Char","Byte","Short","Unit","Any",
        "List","Map","Set","Array","int","float","double","bool","char","void",
        "Promise","Observable","Flow","StateFlow","Composable","Modifier","Box","Row","Column",
        "Text","Image","Surface","Button","dict","str","list","set","tuple","Exception","Throwable"
    )
    enum class TokenType { COMMENT, STRING, NUMBER, KEYWORD, TYPE, ANNOTATION, PLAIN }
    data class TokenSpan(val start: Int, val end: Int, val type: TokenType)

    fun highlight(code: String, language: String, isDark: Boolean): androidx.compose.ui.text.AnnotatedString {
        if (code.isBlank()) return androidx.compose.ui.text.AnnotatedString("")
        val cacheKey = "$language:$isDark:${code.hashCode()}:${code.length}"
        syntaxHighlightCache.get(cacheKey)?.let { return it }

        val commentColor    = if (isDark) Color(0xFF8B949E) else Color(0xFF6E7781)
        val stringColor     = if (isDark) Color(0xFF7EE787) else Color(0xFF116329)
        val numberColor     = if (isDark) Color(0xFF79C0FF) else Color(0xFF0550AE)
        val keywordColor    = if (isDark) Color(0xFFFF7B72) else Color(0xFFCF222E)
        val typeColor       = if (isDark) Color(0xFFFFA657) else Color(0xFF953800)
        val annotationColor = if (isDark) Color(0xFFD2A8FF) else Color(0xFF8250DF)
        val defaultColor    = if (isDark) Color(0xFFE6EDF3) else Color(0xFF1F2328)
        val langLower       = language.lowercase()
        val isHashLang      = langLower in setOf("python","py","bash","sh","shell","yaml","yml","dockerfile","r")

        val commentRx    = if (isHashLang) Regex("""(#.*)""") else Regex("""(//.*|/\*[\s\S]*?\*/|#.*)""")
        val stringRx     = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\[\s\S]|[^`\\])*`""")
        val annotationRx = Regex("""@[A-Za-z0-9_]+""")
        val numberRx     = Regex("""\b(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\b""")
        val wordRx       = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

        val spans  = mutableListOf<TokenSpan>()
        val taken  = java.util.BitSet(code.length)

        fun mark(s: Int, e: Int, t: TokenType) { for (i in s until e) taken.set(i); spans.add(TokenSpan(s, e, t)) }

        for (m in commentRx.findAll(code))    { val s=m.range.first; if (!taken[s]) mark(s, m.range.last+1, TokenType.COMMENT) }
        for (m in stringRx.findAll(code))     { val s=m.range.first; if (!taken[s]) mark(s, m.range.last+1, TokenType.STRING) }
        for (m in annotationRx.findAll(code)) { val s=m.range.first; if (!taken[s]) mark(s, m.range.last+1, TokenType.ANNOTATION) }
        for (m in numberRx.findAll(code))     { val s=m.range.first; if (!taken[s]) mark(s, m.range.last+1, TokenType.NUMBER) }
        for (m in wordRx.findAll(code)) {
            val s=m.range.first; val e=m.range.last+1
            if (!taken[s]) when {
                m.value in keywords       -> mark(s, e, TokenType.KEYWORD)
                m.value in commonTypes || (m.value.firstOrNull()?.isUpperCase() == true && m.value.length > 1) -> mark(s, e, TokenType.TYPE)
            }
        }
        spans.sortBy { it.start }

        val built = buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                if (span.start > cursor) withStyle(SpanStyle(color = defaultColor)) { append(code.substring(cursor, span.start)) }
                val chunk = code.substring(span.start, span.end)
                withStyle(when (span.type) {
                    TokenType.COMMENT    -> SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)
                    TokenType.STRING     -> SpanStyle(color = stringColor)
                    TokenType.NUMBER     -> SpanStyle(color = numberColor)
                    TokenType.KEYWORD    -> SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)
                    TokenType.TYPE       -> SpanStyle(color = typeColor)
                    TokenType.ANNOTATION -> SpanStyle(color = annotationColor)
                    TokenType.PLAIN      -> SpanStyle(color = defaultColor)
                }) { append(chunk) }
                cursor = span.end
            }
            if (cursor < code.length) withStyle(SpanStyle(color = defaultColor)) { append(code.substring(cursor)) }
        }
        syntaxHighlightCache.put(cacheKey, built)
        return built
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Code block view
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun CodeBlockView(language: String, code: String, modifier: Modifier = Modifier, showLineNumbers: Boolean = true) {
    val context     = LocalContext.current
    val haptic      = LocalHapticFeedback.current
    val isDark      = MaterialTheme.colorScheme.background.red < 0.5f
    val displayLang = if (language.isNotBlank()) language.uppercase() else "CODE"
    var isCopied    by remember { mutableStateOf(false) }
    var isWrapped   by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) { if (isCopied) { delay(2000); isCopied = false } }

    val lines           = remember(code) { code.lines() }
    val lineCount       = lines.size
    val highlightedCode = remember(code, language, isDark) { SyntaxHighlighter.highlight(code, language, isDark) }
    val headerBg        = if (isDark) Color(0xFF1B1B20) else Color(0xFFEAE8E2)
    val bodyBg          = if (isDark) Color(0xFF101014) else Color(0xFFF9F9F8)
    val borderColor     = if (isDark) Color(0xFF2C2C34) else Color(0xFFDDDCD5)
    val lineNumColor    = if (isDark) Color(0xFF555562) else Color(0xFFA0A0A8)

    Column(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bodyBg).border(1.dp, borderColor, RoundedCornerShape(12.dp))) {
        DisableSelection {
            Row(
                modifier              = Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (isDark) Color(0xFF282830) else Color(0xFFDEDBD4)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(text = displayLang, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp), color = if (isDark) Color(0xFFD0D0D8) else Color(0xFF404048))
                    }
                    if (lineCount > 1) { Spacer(Modifier.width(8.dp)); Text(text = "$lineCount lines", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = if (isDark) Color(0xFF7E7E8A) else Color(0xFF888892)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); isWrapped = !isWrapped }, shape = RoundedCornerShape(6.dp), color = if (isWrapped) ClaudeTerracotta.copy(alpha = 0.15f) else Color.Transparent) {
                        Text(text = if (isWrapped) "Wrap" else "Scroll", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = if (isWrapped) ClaudeTerracotta else if (isDark) Color(0xFFA6A6B0) else Color(0xFF606068), modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                    Surface(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code", code)); isCopied = true; Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show() }, shape = RoundedCornerShape(6.dp), color = if (isCopied) ChatGptEmerald.copy(alpha = 0.15f) else if (isDark) Color(0xFF282832) else Color(0xFFDCDAD2)) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp), tint = if (isCopied) ChatGptEmerald else if (isDark) Color(0xFFA6A6B0) else Color(0xFF505058))
                            Text(text = if (isCopied) "Copied!" else "Copy", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold), color = if (isCopied) ChatGptEmerald else if (isDark) Color(0xFFA6A6B0) else Color(0xFF505058))
                        }
                    }
                }
            }
        }
        SelectionContainer {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                val shouldShowNums = showLineNumbers && !isWrapped && lineCount > 1
                if (shouldShowNums) {
                    DisableSelection {
                        Text(
                            text     = (1..lineCount).joinToString("\n"),
                            style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp, fontSize = 12.sp, textAlign = TextAlign.End),
                            color    = lineNumColor,
                            modifier = Modifier.padding(start = 10.dp, end = 8.dp).widthIn(min = 22.dp).drawBehind {
                                drawLine(color = borderColor.copy(alpha = 0.6f), start = Offset(size.width, 0f), end = Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                            }
                        )
                    }
                }
                Text(
                    text     = highlightedCode,
                    style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp, fontSize = 12.sp),
                    modifier = if (isWrapped) Modifier.weight(1f).padding(horizontal = 12.dp)
                               else Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Table view
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun TableBlockView(headers: List<String>, rows: List<List<String>>, onLinkClick: ((String) -> Unit)? = null) {
    val isDark      = MaterialTheme.colorScheme.background.red < 0.5f
    val headerBg    = if (isDark) Color(0xFF1E1E22) else Color(0xFFECEAE4)
    val rowAltBg    = if (isDark) Color(0xFF18181C) else Color(0xFFF7F6F2)
    val rowNormBg   = if (isDark) Color(0xFF141416) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color(0xFF2E2E36) else Color(0xFFE2E0D8)

    val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 1)
    val colWidths = remember(headers, rows) {
        (0 until colCount).map { ci ->
            val hLen = headers.getOrNull(ci)?.length ?: 0
            val rMax = rows.maxOfOrNull { it.getOrNull(ci)?.length ?: 0 } ?: 0
            (maxOf(hLen, rMax) * 9.5).coerceIn(100.0, 320.0).dp
        }
    }

    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, borderColor, RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp), color = rowNormBg) {
        Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(modifier = Modifier.background(headerBg).padding(horizontal = 14.dp, vertical = 10.dp)) {
                (0 until colCount).forEach { ci ->
                    Box(modifier = Modifier.width(colWidths.getOrElse(ci) { 120.dp }).padding(end = 12.dp)) {
                        FormattedMarkdownText(text = headers.getOrNull(ci)?.trim() ?: "", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, onLinkClick = onLinkClick)
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = borderColor)
            rows.forEachIndexed { ri, row ->
                Row(modifier = Modifier.background(if (ri % 2 == 1) rowAltBg else rowNormBg).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    (0 until colCount).forEach { ci ->
                        Box(modifier = Modifier.width(colWidths.getOrElse(ci) { 120.dp }).padding(end = 12.dp)) {
                            FormattedMarkdownText(text = row.getOrNull(ci)?.trim() ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, onLinkClick = onLinkClick)
                        }
                    }
                }
                if (ri < rows.size - 1) HorizontalDivider(thickness = 0.5.dp, color = borderColor.copy(alpha = 0.5f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Artifact card view
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun InlineArtifactCardView(artifact: MarkdownBlock.Artifact, onOpenArtifact: () -> Unit, modifier: Modifier = Modifier) {
    val context  = LocalContext.current
    val haptic   = LocalHapticFeedback.current
    val isDark   = MaterialTheme.colorScheme.background.red < 0.5f
    var isCopied by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) { if (isCopied) { delay(2000); isCopied = false } }

    val typeLower = artifact.type.lowercase()
    val langLower = artifact.language.lowercase()
    val (icon, tint, badgeLabel) = when {
        typeLower.contains("code") || langLower in setOf("python","py","kotlin","kt","js","ts","java","cpp","c","rust","rs","go","sh","bash") -> Triple(Icons.Default.Code, ChatGptBlue, if (artifact.language.isNotBlank()) artifact.language.uppercase() else "CODE")
        typeLower.contains("html") || langLower in setOf("html","htm") -> Triple(Icons.Default.Language, Color(0xFFE65100), "HTML")
        typeLower.contains("svg")  || langLower == "svg"               -> Triple(Icons.Default.Brush, Color(0xFF9C27B0), "SVG")
        typeLower.contains("markdown") || langLower in setOf("markdown","md") -> Triple(Icons.Default.Article, ClaudeTerracotta, "NOTES")
        typeLower.contains("csv")  || typeLower.contains("json") || langLower in setOf("csv","json") -> Triple(Icons.Default.TableChart, ChatGptEmerald, "DATA")
        else -> Triple(Icons.Default.Description, ClaudeTerracotta, if (artifact.language.isNotBlank()) artifact.language.uppercase() else "DOC")
    }

    val cardBg      = if (isDark) Color(0xFF1B1C22) else Color(0xFFF7F6F3)
    val headerBg    = if (isDark) Color(0xFF22242C) else Color(0xFFEFECE5)
    val borderColor = if (isDark) Color(0xFF2F323E) else Color(0xFFDEDBD2)
    val previewLines = remember(artifact.content) { artifact.content.lines().take(5).joinToString("\n") }

    Surface(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, borderColor, RoundedCornerShape(14.dp)).clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onOpenArtifact() }, shape = RoundedCornerShape(14.dp), color = cardBg) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = artifact.title.ifBlank { artifact.identifier }, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = tint.copy(alpha = 0.15f)) {
                                Text(text = badgeLabel, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp), color = tint, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                        Text(text = "Artifact • Tap to view & save to phone", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(artifact.title, artifact.content)); isCopied = true; Toast.makeText(context, "Artifact content copied", Toast.LENGTH_SHORT).show() }, shape = RoundedCornerShape(6.dp), color = if (isCopied) ChatGptEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                        Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = "Copy", tint = if (isCopied) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                            Text(text = if (isCopied) "Copied" else "Copy", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = if (isCopied) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onOpenArtifact() }, shape = RoundedCornerShape(6.dp), color = ClaudeTerracotta) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                            Text(text = "Open", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White)
                        }
                    }
                }
            }
            if (previewLines.isNotBlank()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(text = previewLines, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Sealed block types
// ═══════════════════════════════════════════════════════════════════════════════
sealed class MarkdownBlock {
    data class Paragraph(val text: String)    : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Blockquote(val text: String)   : MarkdownBlock()
    data class ListBlock(val items: List<ListItem>) : MarkdownBlock()
    data class ListItem(val isOrdered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    data class MathEquation(val formula: String) : MarkdownBlock()
    data class Artifact(val identifier: String, val title: String, val type: String, val language: String, val content: String) : MarkdownBlock()
    object Divider : MarkdownBlock()
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Markdown block parser  — fixed LaTeX delimiter handling
// ═══════════════════════════════════════════════════════════════════════════════
fun parseMarkdownBlocks(raw: String, skipCache: Boolean = false): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    if (!skipCache) markdownBlockCache.get(raw)?.let { return it }

    val blocks     = mutableListOf<MarkdownBlock>()
    val lines      = raw.split("\n")
    var inCodeBlock = false
    var codeLang    = ""
    val codeBuffer  = StringBuilder()
    val paraBuffer  = StringBuilder()
    var listIndex   = 1

    fun flushPara() {
        val content = paraBuffer.toString().trim()
        if (content.isNotEmpty()) {
            if (isPureEquationLine(content)) blocks.add(MarkdownBlock.MathEquation(content))
            else blocks.add(MarkdownBlock.Paragraph(content))
        }
        paraBuffer.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line    = lines[i]
        val trimmed = line.trim()

        // ── Artifact blocks ──────────────────────────────────────────────────
        if (trimmed.startsWith("<antArtifact") || trimmed.startsWith("<artifact")) {
            flushPara()
            val isAnt    = trimmed.startsWith("<antArtifact")
            val closeTag = if (isAnt) "</antArtifact>" else "</artifact>"
            val id       = Regex("""identifier=["']([^"']+)["']""").find(trimmed)?.groupValues?.get(1) ?: "artifact"
            val title    = Regex("""title=["']([^"']+)["']""").find(trimmed)?.groupValues?.get(1) ?: id
            val type     = Regex("""type=["']([^"']+)["']""").find(trimmed)?.groupValues?.get(1) ?: "text/plain"
            val language = Regex("""language=["']([^"']+)["']""").find(trimmed)?.groupValues?.get(1) ?: ""

            if (trimmed.contains(closeTag)) {
                val inner    = trimmed.substringAfter(">").substringBefore(closeTag)
                blocks.add(MarkdownBlock.Artifact(id, title, type, language, inner))
                val after = trimmed.substringAfter(closeTag).trim()
                if (after.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(after) }
                i++; continue
            } else {
                val artBuf  = StringBuilder()
                val afterOpen = trimmed.substringAfter(">").trim()
                if (afterOpen.isNotEmpty()) artBuf.append(afterOpen).append("\n")
                i++
                while (i < lines.size) {
                    val cur = lines[i]
                    if (cur.contains(closeTag)) {
                        val before = cur.substringBefore(closeTag); if (before.isNotEmpty()) artBuf.append(before).append("\n")
                        val after  = cur.substringAfter(closeTag).trim(); if (after.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(after) }
                        i++; break
                    } else { artBuf.append(cur).append("\n"); i++ }
                }
                blocks.add(MarkdownBlock.Artifact(id, title, type, language, artBuf.toString().trimEnd()))
                continue
            }
        }

        // ── Code fences ──────────────────────────────────────────────────────
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                val isMath = codeLang.equals("math",ignoreCase=true) || codeLang.equals("latex",ignoreCase=true) || codeLang.equals("katex",ignoreCase=true)
                if (isMath) blocks.add(MarkdownBlock.MathEquation(codeBuffer.toString().trimEnd()))
                else        blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
                codeBuffer.clear(); codeLang = ""; inCodeBlock = false
            } else {
                flushPara(); codeLang = trimmed.removePrefix("```").trim(); inCodeBlock = true
            }
            i++; continue
        }
        if (inCodeBlock) { codeBuffer.append(line).append("\n"); i++; continue }

        // ── Display math: $$ ... $$ (single line) ───────────────────────────
        if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4) {
            flushPara()
            val formula = trimmed.substring(2, trimmed.length - 2).trim()
            if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
            i++; continue
        }

        // ── Display math: $$ (multiline opening) ────────────────────────────
        if (trimmed.startsWith("$$") && !trimmed.endsWith("$$")) {
            flushPara()
            val mathBuf = StringBuilder()
            val first = trimmed.removePrefix("$$").trim()
            if (first.isNotEmpty()) mathBuf.append(first).append("\n")
            i++
            while (i < lines.size) {
                val cur = lines[i].trim()
                if (cur.endsWith("$$")) {
                    val before = cur.removeSuffix("$$").trim(); if (before.isNotEmpty()) mathBuf.append(before).append("\n")
                    i++; break
                } else if (cur.contains("$$")) {
                    val before = cur.substringBefore("$$").trim(); if (before.isNotEmpty()) mathBuf.append(before).append("\n")
                    val after  = cur.substringAfter("$$").trim();  if (after.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(after) }
                    i++; break
                } else { mathBuf.append(lines[i]).append("\n"); i++ }
            }
            val formula = mathBuf.toString().trim()
            if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
            continue
        }

        // ── Inline $$...$$  within a paragraph line ──────────────────────────
        if (trimmed.contains("$$") && !trimmed.startsWith("$$")) {
            val before = trimmed.substringBefore("$$").trim()
            val rem    = trimmed.substringAfter("$$")
            if (rem.contains("$$")) {
                val formula = rem.substringBefore("$$").trim()
                val after   = rem.substringAfter("$$").trim()
                if (before.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(before); flushPara() } else flushPara()
                if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
                if (after.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(after) }
                i++; continue
            }
            // No closing $$ on same line — fall through to paragraph
        }

        // ── Display math: \[ ... \] (single line) ───────────────────────────
        if (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4) {
            flushPara()
            val formula = trimmed.substring(2, trimmed.length - 2).trim()
            if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
            i++; continue
        }

        // ── Display math: \[ (multiline opening) ─────────────────────────────
        if (trimmed.startsWith("\\[") && !trimmed.endsWith("\\]")) {
            flushPara()
            val mathBuf = StringBuilder()
            val first = trimmed.removePrefix("\\[").trim()
            if (first.isNotEmpty()) mathBuf.append(first).append("\n")
            i++
            while (i < lines.size) {
                val cur = lines[i].trim()
                if (cur.endsWith("\\]")) {
                    val before = cur.removeSuffix("\\]").trim(); if (before.isNotEmpty()) mathBuf.append(before).append("\n")
                    i++; break
                } else if (cur.contains("\\]")) {
                    val before = cur.substringBefore("\\]").trim(); if (before.isNotEmpty()) mathBuf.append(before).append("\n")
                    val after  = cur.substringAfter("\\]").trim();  if (after.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(after) }
                    i++; break
                } else { mathBuf.append(lines[i]).append("\n"); i++ }
            }
            val formula = mathBuf.toString().trim()
            if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
            continue
        }

        // ── \[ ... \] same line but not at start (inline within paragraph) ───
        if (trimmed.contains("\\[") && trimmed.contains("\\]") && !trimmed.startsWith("\\[")) {
            val before  = trimmed.substringBefore("\\[").trim()
            val formula = trimmed.substringAfter("\\[").substringBefore("\\]").trim()
            val after   = trimmed.substringAfter("\\]").trim()
            if (before.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(before); flushPara() } else flushPara()
            if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
            if (after.isNotEmpty()) { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(after) }
            i++; continue
        }

        // ── \begin{...} environments ─────────────────────────────────────────
        if (trimmed.startsWith("\\begin{")) {
            flushPara()
            val env     = Regex("""\\begin\{([a-zA-Z0-9*]+)\}""").find(trimmed)?.groupValues?.get(1) ?: ""
            val mathBuf = StringBuilder(line).append("\n")
            i++
            while (i < lines.size && !lines[i].contains("\\end{$env}")) { mathBuf.append(lines[i]).append("\n"); i++ }
            if (i < lines.size) { mathBuf.append(lines[i]).append("\n"); i++ }
            val formula = mathBuf.toString().trim()
            if (formula.isNotEmpty()) blocks.add(MarkdownBlock.MathEquation(formula))
            continue
        }

        // ── Standalone $ math $ line ─────────────────────────────────────────
        if (trimmed.startsWith("$") && trimmed.endsWith("$") && trimmed.length >= 3 && !trimmed.startsWith("$$")) {
            val formula = trimmed.substring(1, trimmed.length - 1).trim()
            if (formula.isNotEmpty()) { flushPara(); blocks.add(MarkdownBlock.MathEquation(formula)); i++; continue }
        }

        // ── Table ────────────────────────────────────────────────────────────
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size) {
            val next = lines[i + 1].trim()
            if (next.startsWith("|") && next.contains("---")) {
                flushPara()
                val headers = trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    rows.add(lines[i].trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }); i++
                }
                blocks.add(MarkdownBlock.Table(headers, rows)); continue
            }
        }

        // ── Blockquotes / callouts ───────────────────────────────────────────
        if (trimmed.startsWith(">")) {
            flushPara()
            val qBuf = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                val qLine = lines[i].trim().removePrefix(">").trim()
                if (qBuf.isNotEmpty()) qBuf.append("\n"); qBuf.append(qLine); i++
            }
            val quoteText = qBuf.toString().trim()
            if (quoteText.isNotEmpty()) blocks.add(MarkdownBlock.Blockquote(quoteText))
            continue
        }

        // ── Lists ────────────────────────────────────────────────────────────
        val isBullet  = trimmed.startsWith("- ") || trimmed.startsWith("* ")
        val isOrdered = trimmed.matches(Regex("""^\d+\.\s+.*"""))
        if (isBullet || isOrdered) {
            flushPara()
            val items = mutableListOf<MarkdownBlock.ListItem>()
            while (i < lines.size) {
                val l = lines[i].trim()
                when {
                    l.startsWith("- ") || l.startsWith("* ") -> {
                        items.add(MarkdownBlock.ListItem(false, 0, (if (l.startsWith("- ")) l.removePrefix("- ") else l.removePrefix("* ")).trim())); i++
                    }
                    l.matches(Regex("""^\d+\.\s+.*""")) -> {
                        val m = Regex("""^(\d+)\.\s+(.*)""").find(l)
                        val num = m?.groupValues?.get(1)?.toIntOrNull() ?: listIndex
                        items.add(MarkdownBlock.ListItem(true, num, (m?.groupValues?.get(2) ?: l).trim())); listIndex = num + 1; i++
                    }
                    else -> break
                }
            }
            if (items.isNotEmpty()) blocks.add(MarkdownBlock.ListBlock(items))
            continue
        }

        // ── Headings, dividers, and paragraph text ───────────────────────────
        when {
            trimmed.startsWith("##### ") -> { flushPara(); blocks.add(MarkdownBlock.Heading(5, trimmed.removePrefix("##### ").trim())) }
            trimmed.startsWith("#### ")  -> { flushPara(); blocks.add(MarkdownBlock.Heading(4, trimmed.removePrefix("#### ").trim())) }
            trimmed.startsWith("### ")   -> { flushPara(); blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim())) }
            trimmed.startsWith("## ")    -> { flushPara(); blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim())) }
            trimmed.startsWith("# ")     -> { flushPara(); blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim())) }
            trimmed == "---" || trimmed == "***" -> { flushPara(); blocks.add(MarkdownBlock.Divider) }
            isPureEquationLine(trimmed)  -> { flushPara(); blocks.add(MarkdownBlock.MathEquation(trimmed)) }
            trimmed.isEmpty()            -> { flushPara(); listIndex = 1 }
            else -> { if (paraBuffer.isNotEmpty()) paraBuffer.append("\n"); paraBuffer.append(line) }
        }
        i++
    }

    // Flush any trailing code or paragraph
    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        val isMath = codeLang.equals("math",ignoreCase=true) || codeLang.equals("latex",ignoreCase=true) || codeLang.equals("katex",ignoreCase=true)
        if (isMath) blocks.add(MarkdownBlock.MathEquation(codeBuffer.toString().trimEnd()))
        else        blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
    } else {
        flushPara()
    }

    if (!skipCache) markdownBlockCache.put(raw, blocks)
    return blocks
}

// ─── Compat stubs for SettingsScreen — bitmap cache removed in v3.0.3 ─────────
/** Returns 0 — KaTeX bitmap cache was removed; WebView renders live now. */
fun getKaTeXCacheCount(): Int = 0

/** No-op — KaTeX bitmap cache was removed; returns 0. */
fun clearKaTeXCache(): Int = 0
