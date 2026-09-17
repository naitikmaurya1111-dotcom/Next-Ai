package com.agychat.app.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.LruCache
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay
import org.json.JSONObject

// Zero-allocation caches for 120 FPS buttery smooth scrolling
// NOTE: markdownBlockCache is intentionally not used during streaming (isStreaming=true path)
// to prevent partial LaTeX blocks from getting frozen/cached mid-render
private val markdownBlockCache = LruCache<String, List<MarkdownBlock>>(300)
private val latexUnicodeCache = LruCache<String, String>(300)
private val syntaxHighlightCache = LruCache<String, androidx.compose.ui.text.AnnotatedString>(150)
private val inlineTextCache = LruCache<String, androidx.compose.ui.text.AnnotatedString>(400)

/**
 * Sanitizes markdown input by removing ANSI escape sequences and terminal noise.
 */
fun sanitizeMarkdownInput(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        // Strip ANSI escape codes (e.g. \u001B[31m, \u001B[0m)
        .replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
        // Strip terminal query responses and bracketed paste noise (e.g. //#]jsi^9)
        .replace(Regex("//#\\][^\r\n]*"), "")
        .replace(Regex("\\[\\?[0-9;]*[a-zA-Z]"), "")
        // Strip non-printable ASCII control characters except \n, \r, \t
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]"), "")
}

/**
 * Normalizes user-entered or LLM-streamed LaTeX formulas:
 * - Strips enclosing delimiters ($$, \[, \], $)
 * - Adds missing leading backslashes to common math operators (frac -> \frac)
 * - Cleans dangling trailing backslashes or unfinished \left delimiters during streaming
 */
fun normalizeLatexFormula(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("$$") && s.endsWith("$$") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("$") && s.endsWith("$") && s.length >= 2) {
        s = s.substring(1, s.length - 1).trim()
    }

    // Convert top-level environments that KaTeX rejects in non-top-level display math to aligned/gathered
    s = s.replace(Regex("""\\begin\{align\*?\}""")) { "\\begin{aligned}" }
        .replace(Regex("""\\end\{align\*?\}""")) { "\\end{aligned}" }
        .replace(Regex("""\\begin\{gather\*?\}""")) { "\\begin{gathered}" }
        .replace(Regex("""\\end\{gather\*?\}""")) { "\\end{gathered}" }
        .replace(Regex("""\\begin\{eqnarray\*?\}""")) { "\\begin{aligned}" }
        .replace(Regex("""\\end\{eqnarray\*?\}""")) { "\\end{aligned}" }

    // Fix markdown escaped underscores or ampersands within math mode
    s = s.replace("\\_", "_").replace("\\&", "&")

    // Fix missing leading backslashes on common LaTeX keywords (e.g. "frac{" -> "\frac{")
    val missingSlashRegex = Regex("""(?<!\\)\b(frac|sqrt|sum|int|prod|partial|hbar|alpha|beta|gamma|delta|epsilon|theta|lambda|mu|pi|rho|sigma|tau|phi|psi|omega|Delta|Theta|Lambda|Sigma|Phi|Psi|Omega|ket|bra|braket|hat|vec|mathcal|mathbf|mathbb)\b""")
    s = s.replace(missingSlashRegex) { "\\${it.value}" }

    // Strip dangling backslash at the very end of string (common in streaming)
    s = s.replace(Regex("""\\+\s*$"""), "")

    // Balance unclosed \left and \right delimiters (unmatched delimiters cause KaTeX parse abort)
    val leftCount = Regex("""\\left\b""").findAll(s).count()
    val rightCount = Regex("""\\right\b""").findAll(s).count()
    if (leftCount != rightCount) {
        s = s.replace(Regex("""\\left\s*\."""), "")
            .replace(Regex("""\\right\s*\."""), "")
            .replace(Regex("""\\left\s*([(\[{|])"""), "$1")
            .replace(Regex("""\\right\s*([)\]}|])"""), "$1")
    }

    // Auto-balance unclosed curly braces { } (common during streaming)
    val openBraces = s.count { it == '{' }
    val closeBraces = s.count { it == '}' }
    if (openBraces > closeBraces) {
        s += "}".repeat(openBraces - closeBraces)
    }

    return s.trim()
}

/**
 * High-quality Unicode mathematical symbol formatter.
 * Converts LaTeX math notation into clean, human-readable Unicode math symbols.
 * Used for instant previews, fallbacks, and inline math formatting.
 */
fun formatLatexToUnicode(raw: String): String {
    if (raw.isBlank()) return ""
    val cached = latexUnicodeCache.get(raw)
    if (cached != null) return cached
    var text = normalizeLatexFormula(raw)

    // Handle matrix environments nicely in Unicode
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

    // Strip remaining LaTeX equation/alignment environments
    text = text.replace(Regex("""\\begin\{(?:equation\*?|align\*?|aligned|gather\*?|split)\}"""), "")
    text = text.replace(Regex("""\\end\{(?:equation\*?|align\*?|aligned|gather\*?|split)\}"""), "")

    // Quantum physics bras and kets: \ket{\psi} -> |ψ⟩, \bra{\phi} -> ⟨ϕ|, \braket{a}{b} -> ⟨a|b⟩
    text = text.replace(Regex("""\\braket\{([^{}]+)\}\{([^{}]+)\}""")) { "⟨${it.groupValues[1]}|${it.groupValues[2]}⟩" }
    text = text.replace(Regex("""\\ket\{([^{}]+)\}""")) { "|${it.groupValues[1]}⟩" }
    text = text.replace(Regex("""\\bra\{([^{}]+)\}""")) { "⟨${it.groupValues[1]}|" }
    text = text.replace("\\langle", "⟨").replace("\\rangle", "⟩")

    // Vectors and accents: \vec{r} -> r⃗, \hat{H} -> Ĥ, \dot{x} -> ẋ
    text = text.replace(Regex("""\\vec\{([^{}]+)\}""")) { "${it.groupValues[1]}⃗" }
    text = text.replace(Regex("""\\dot\{([^{}]+)\}""")) { "${it.groupValues[1]}̇" }
    text = text.replace(Regex("""\\ddot\{([^{}]+)\}""")) { "${it.groupValues[1]}̈" }
    text = text.replace(Regex("""\\bar\{([^{}]+)\}""")) { "${it.groupValues[1]}̄" }
    text = text.replace(Regex("""\\tilde\{([^{}]+)\}""")) { "${it.groupValues[1]}̃" }

    // Remove \left and \right
    text = text.replace("\\left", "").replace("\\right", "")

    // Remove \text{...}, \mathrm{...}, \mathbf{...}, \boldsymbol{...}
    text = text.replace(Regex("""\\text\{([^}]+)\}""")) { it.groupValues[1] }
    text = text.replace(Regex("""\\mathrm\{([^}]+)\}""")) { it.groupValues[1] }
    text = text.replace(Regex("""\\mathbf\{([^}]+)\}""")) { it.groupValues[1] }
    text = text.replace(Regex("""\\boldsymbol\{([^}]+)\}""")) { it.groupValues[1] }

    // Blackboard bold: \mathbb{R} -> ℝ, \mathbb{C} -> ℂ, etc.
    val bbMap = mapOf("R" to "ℝ", "C" to "ℂ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "H" to "ℍ")
    for ((k, v) in bbMap) {
        text = text.replace("\\mathbb{$k}", v).replace("\\mathbb $k", v)
    }

    // Calligraphic: \mathcal{H} -> ℋ
    val calMap = mapOf(
        "H" to "ℋ", "E" to "ℰ", "L" to "ℒ", "M" to "ℳ", "F" to "ℱ",
        "O" to "𝒪", "P" to "𝒫", "D" to "𝒟", "C" to "𝒞", "N" to "𝒩",
        "B" to "ℬ", "A" to "𝒜"
    )
    for ((k, v) in calMap) {
        text = text.replace("\\mathcal{$k}", v).replace("\\mathcal $k", v)
    }

    // Hats and operators: \hat{H} -> Ĥ, \hat{\rho} -> ρ̂
    val hats = mapOf(
        "H" to "Ĥ", "A" to "Â", "B" to "B̂", "p" to "p̂", "x" to "x̂", "y" to "ŷ", "z" to "ẑ",
        "\\rho" to "ρ̂", "rho" to "ρ̂", "\\psi" to "ψ̂", "psi" to "ψ̂", "\\phi" to "ϕ̂", "phi" to "ϕ̂"
    )
    for ((k, v) in hats) {
        text = text.replace("\\hat{$k}", v).replace("\\hat $k", v)
    }

    // Fractions: \frac{a}{b} or frac{a}{b}
    val fracRegex = Regex("""\\?frac\{([^{}]+)\}\{([^{}]+)\}""")
    var safety = 0
    var m = fracRegex.find(text)
    while (m != null && safety < 15) {
        safety++
        val num = m.groupValues[1].trim()
        val den = m.groupValues[2].trim()
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
            den.length > 2 || den.contains("+") || den.contains("-") || den.contains(" ") || den.contains("\\") -> {
                if (num.contains("+") || num.contains("-") || num.contains(" ")) "($num) ⁄ ($den)" else "$num ⁄ ($den)"
            }
            num.contains("+") || num.contains("-") || num.contains(" ") -> "($num) ⁄ $den"
            else -> "$num ⁄ $den"
        }
        text = text.replaceRange(m.range, rep)
        m = fracRegex.find(text)
    }

    // Roots: \sqrt[3]{x} -> ∛(x), \sqrt{x} -> √(x)
    text = text.replace(Regex("""\\?sqrt\[3\]\{([^{}]+)\}""")) { "∛(${it.groupValues[1]})" }
    text = text.replace(Regex("""\\?sqrt\[4\]\{([^{}]+)\}""")) { "∜(${it.groupValues[1]})" }
    text = text.replace(Regex("""\\?sqrt\{([^{}]+)\}""")) { "√(${it.groupValues[1]})" }

    // Greek letters and math symbols (ordered from specific to general)
    val symbols = listOf(
        "\\varepsilon_0" to "ε₀", "\\epsilon_0" to "ε₀", "\\theta_0" to "θ₀",
        "\\vec{\\tau}" to "τ⃗", "\\vec{\\mu}" to "μ⃗", "\\vec{p}" to "p⃗",
        "\\vec{r}" to "r⃗", "\\vec{E}" to "E⃗", "\\vec{B}" to "B⃗",
        "\\vec{F}" to "F⃗", "\\vec{v}" to "v⃗", "\\vec{A}" to "A⃗",
        "\\hat{r}" to "r̂", "\\hat{p}" to "p̂", "\\hat{n}" to "n̂",
        "\\hat{i}" to "î", "\\hat{j}" to "ĵ", "\\hat{k}" to "k̂",
        "\\hbar" to "ℏ", "\\dagger" to "†", "\\partial" to "∂", "\\nabla" to "∇", "\\infty" to "∞",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\vartheta" to "ϑ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν",
        "\\xi" to "ξ", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\varrho" to "ϱ",
        "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "ϕ",
        "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ",
        "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ", "\\Phi" to "Φ", "\\Psi" to "Ψ",
        "\\Omega" to "Ω",
        "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\cdot" to "·", "\\div" to "÷",
        "\\approx" to "≈", "\\equiv" to "≡", "\\neq" to "≠", "\\ne" to "≠", "\\le" to "≤",
        "\\leq" to "≤", "\\ge" to "≥", "\\geq" to "≥", "\\ll" to "≪", "\\gg" to "≫",
        "\\sim" to "∼", "\\simeq" to "≃", "\\cong" to "≅", "\\propto" to "∝",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔", "\\iff" to "⇔", "\\implies" to "⇒",
        "\\in" to "∈", "\\notin" to "∉", "\\ni" to "∋", "\\subset" to "⊂", "\\supset" to "⊃",
        "\\subseteq" to "⊆", "\\supseteq" to "⊇", "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅",
        "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄",
        "\\circ" to "°", "\\degree" to "°", "\\prime" to "′",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…",
        "\\cos" to "cos", "\\sin" to "sin", "\\tan" to "tan",
        "\\det" to "det", "\\gcd" to "gcd", "\\lim" to "lim",
        "\\ln" to "ln", "\\log" to "log", "\\exp" to "exp",
        "\\{" to "{", "\\}" to "}", "\\," to " ", "\\;" to " ", "\\quad" to " ", "\\qquad" to "  "
    )
    for ((k, v) in symbols) {
        text = text.replace(k, v)
    }

    // Superscripts: ^{content} or ^char
    val supsMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
        '†' to '†'
    )
    text = text.replace(Regex("""\^\{([^{}]+)\}|\^([0-9a-zA-Z\+\-†])""")) { matchResult ->
        val content = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
        content.map { supsMap[it] ?: it }.joinToString("")
    }

    // Subscripts: _{content} or _char
    val subsMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ', 'θ' to 'θ', 'ϕ' to 'ϕ'
    )
    text = text.replace("_{θ}", "θ").replace("_{ϕ}", "ϕ")
    text = text.replace(Regex("""_\{([^{}]+)\}|_([0-9a-zA-Z\+\-θϕ])""")) { matchResult ->
        val content = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
        content.map { subsMap[it] ?: it }.joinToString("")
    }

    // Strip inline and display math delimiter markers $$ or $ or \[ or \]
    text = text.replace("$$", "").replace("$", "")
    text = text.replace("\\[", "").replace("\\]", "")

    // Clean up remaining dangling backslashes before plain words
    text = text.replace(Regex("""\\+([a-zA-Z]+)""")) { it.groupValues[1] }
    text = text.replace("\\", "")

    val result = text.trim()
    latexUnicodeCache.put(raw, result)
    return result
}

/**
 * Detects the mathematical domain/category of a formula for UI badging.
 */
fun detectMathCategory(formula: String): String {
    val f = formula.lowercase()
    return when {
        f.contains("matrix") || f.contains("pmatrix") || f.contains("bmatrix") || f.contains("vmatrix") || f.contains("det(") || f.contains("\\det") -> "Matrix"
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

/**
 * Detects if a standalone line is purely a mathematical equation (like `\frac{d\rho}{dt} = ...`)
 * while strictly ignoring regular English sentences/paragraphs and list items.
 */
fun isPureEquationLine(raw: String): Boolean {
    val s = raw.trim()
    if (s.isBlank() || s.startsWith("#") || s.startsWith("-") || s.startsWith("* ") ||
        s.startsWith(">") || s.startsWith("|") || s.startsWith("```") ||
        s.matches(Regex("""^\d+\.\s+.*""")) || s.contains("**") || s.contains("~~") ||
        s.contains("](") || s.contains("][") || s.startsWith("![") || s.startsWith("<") ||
        s.contains("$") // Lines containing '$' are inline math prose (e.g. "When $\Delta G < 0$"), NOT standalone equations!
    ) {
        return false
    }

    // Common English prose words: if present, the line is prose, NOT a standalone equation!
    val englishWords = setOf(
        "the", "is", "of", "and", "in", "to", "that", "this", "we", "can",
        "for", "with", "as", "by", "from", "are", "which", "where", "quantum",
        "physics", "system", "systems", "state", "states", "rather", "than",
        "classical", "microscopic", "action", "scales", "comparable", "constant",
        "pure", "physical", "space", "spaces", "represented", "vector", "vectors",
        "potential", "electric", "dipole", "field", "charge", "energy", "force",
        "surface", "volume", "point", "distance", "plane", "line", "axis", "note",
        "equation", "equations", "formula", "formulas", "consider", "assume", "given",
        "when", "if", "then", "since", "so", "thus", "hence", "therefore", "because",
        "work", "expansion", "process", "spontaneous", "equilibrium", "temperature",
        "pressure", "enthalpy", "entropy", "reaction", "forward", "reverse", "path"
    )

    val words = s.split(Regex("\\s+")).map { it.lowercase().filter { ch -> ch.isLetter() } }.filter { it.isNotBlank() }
    val firstWord = words.firstOrNull() ?: ""
    val proseStarters = setOf(
        "when", "if", "then", "where", "for", "with", "since", "so", "thus",
        "hence", "therefore", "because", "assuming", "let", "given", "here",
        "also", "note", "by", "at", "in", "from", "substituting", "using",
        "under", "on", "as", "always", "spontaneous", "non"
    )
    if (proseStarters.contains(firstWord)) return false

    val matchedEng = words.count { it in englishWords }
    if (matchedEng >= 1 && words.size > 2) return false
    if (words.size > 4) return false

    val mathTokens = listOf(
        "\\frac", "frac{", "\\int", "int_", "\\sum", "sum_", "\\prod", "prod_", "\\sqrt", "sqrt{",
        "\\partial", "\\nabla", "\\hbar", "\\dagger", "\\ket{", "\\bra{", "\\braket{",
        "\\hat{", "\\vec{", "\\dot{", "\\ddot{", "\\bar{", "\\tilde{",
        "\\alpha", "\\beta", "\\gamma", "\\delta", "\\epsilon", "\\varepsilon", "\\theta", "\\lambda",
        "\\mu", "\\nu", "\\pi", "\\rho", "\\sigma", "\\tau", "\\phi", "\\varphi", "\\psi", "\\omega",
        "\\Delta", "\\Theta", "\\Lambda", "\\Sigma", "\\Phi", "\\Psi", "\\Omega",
        "\\mathcal", "\\mathbf", "\\mathbb", "\\mathrm", "\\left", "\\right", "\\pm", "\\times",
        "\\cdot", "\\infty", "\\approx", "\\equiv", "\\neq", "\\le", "\\ge", "\\in", "\\to"
    )
    val hasMathToken = mathTokens.any { s.contains(it) }
    val hasMathRelation = s.contains("=") || s.contains("\\approx") || s.contains("\\sim") ||
        s.contains("\\le") || s.contains("\\ge") || s.contains("\\in") || s.contains("\\to") ||
        s.contains("<") || s.contains(">") || s.contains("+") || s.contains("-")

    if (hasMathToken && (hasMathRelation || s.startsWith("\\frac") || s.startsWith("frac{") || s.startsWith("\\int") || s.startsWith("\\sum"))) {
        return true
    }

    // Bare mathematical expressions like `[H, \rho]` or `H\psi = E\psi` or `E = mc^2`
    if (s.contains("=") && (s.contains("\\") || s.contains("^") || s.contains("_") || s.contains("[") || s.contains("{"))) {
        if (matchedEng == 0) return true
    }

    return false
}

/**
 * Main ChatGPT-grade Response Panel Composable.
 * Seamlessly integrates rich typography, syntax-highlighted code blocks, responsive tables,
 * callouts, and pixel-perfect publication-quality LaTeX math equations (without boxed frames).
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null
) {
    val cleanText = remember(text) { sanitizeMarkdownInput(text) }
    // Live re-parse when text changes or when streaming finishes so blocks finalize smoothly
    val sections = remember(cleanText, isStreaming) {
        parseMarkdownBlocks(cleanText, skipCache = isStreaming)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        sections.forEachIndexed { index, section ->
            val isLast = index == sections.lastIndex
            val isTrailingStreaming = isStreaming && isLast

            key(index, section.javaClass.name) {
                when (section) {
                is MarkdownBlock.Artifact -> {
                    InlineArtifactCardView(
                        artifact = section,
                        onOpenArtifact = {
                            if (onLinkClick != null) {
                                onLinkClick(section.identifier)
                            }
                        }
                    )
                    if (isTrailingStreaming) {
                        Box(modifier = Modifier.padding(top = 2.dp)) {
                            StreamingCursorBlink()
                        }
                    }
                }
                is MarkdownBlock.MathEquation -> {
                    MathEquationBlockView(formula = section.formula, textColor = textColor)
                    if (isTrailingStreaming) {
                        Box(modifier = Modifier.padding(top = 2.dp)) {
                            StreamingCursorBlink()
                        }
                    }
                }
                is MarkdownBlock.Code -> {
                    CodeBlockView(language = section.language, code = section.code)
                    if (isTrailingStreaming) {
                        Box(modifier = Modifier.padding(top = 2.dp)) {
                            StreamingCursorBlink()
                        }
                    }
                }
                is MarkdownBlock.Table -> {
                    TableBlockView(headers = section.headers, rows = section.rows, onLinkClick = onLinkClick)
                    if (isTrailingStreaming) {
                        Box(modifier = Modifier.padding(top = 2.dp)) {
                            StreamingCursorBlink()
                        }
                    }
                }
                is MarkdownBlock.Heading -> {
                    val style = when (section.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp,
                            lineHeight = 28.sp,
                            fontSize = 22.sp
                        )
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.3).sp,
                            lineHeight = 24.sp,
                            fontSize = 18.sp
                        )
                        3 -> MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.1).sp,
                            lineHeight = 22.sp,
                            fontSize = 15.sp
                        )
                        4 -> MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            lineHeight = 21.sp,
                            fontSize = 14.sp
                        )
                        else -> MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            lineHeight = 20.sp,
                            fontSize = 13.sp
                        )
                    }
                    FormattedMarkdownText(
                        text = section.text,
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        showTrailingCursor = isTrailingStreaming,
                        onLinkClick = onLinkClick
                    )
                }
                is MarkdownBlock.Blockquote -> {
                    val isCallout = section.text.startsWith("[!") && section.text.contains("]")
                    if (isCallout) {
                        val calloutType = section.text.substringAfter("[!").substringBefore("]").uppercase()
                        val calloutBody = section.text.substringAfter("]").trim()
                        val (calloutColor, calloutIcon, calloutTitle) = when (calloutType) {
                            "NOTE" -> Triple(ChatGptBlue, Icons.Default.Info, "Note")
                            "TIP" -> Triple(ChatGptEmerald, Icons.Default.Lightbulb, "Tip")
                            "WARNING" -> Triple(ChatGptAmber, Icons.Default.Warning, "Warning")
                            "IMPORTANT" -> Triple(ClaudeTerracotta, Icons.Default.PriorityHigh, "Important")
                            "CAUTION" -> Triple(MaterialTheme.colorScheme.error, Icons.Default.Error, "Caution")
                            else -> Triple(ClaudeTerracotta, Icons.Default.Info, calloutType.lowercase().replaceFirstChar { it.uppercase() })
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = calloutColor.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, calloutColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(calloutIcon, contentDescription = null, tint = calloutColor, modifier = Modifier.size(15.dp))
                                    Text(
                                        text = calloutTitle,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = calloutColor
                                    )
                                }
                                if (calloutBody.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    FormattedMarkdownText(
                                        text = calloutBody,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color = textColor,
                                        showTrailingCursor = isTrailingStreaming,
                                        onLinkClick = onLinkClick
                                    )
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
                                        color = ClaudeTerracotta,
                                        topLeft = Offset(0f, 0f),
                                        size = Size(3.5.dp.toPx(), size.height),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                    )
                                }
                                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 10.dp)
                        ) {
                            FormattedMarkdownText(
                                text = section.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                showTrailingCursor = isTrailingStreaming,
                                onLinkClick = onLinkClick
                            )
                        }
                    }
                }
                is MarkdownBlock.ListBlock -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        section.items.forEachIndexed { itemIdx, item ->
                            val isItemLast = itemIdx == section.items.lastIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (item.isOrdered) {
                                    Text(
                                        text = "${item.index}.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = ClaudeTerracotta,
                                        modifier = Modifier.width(22.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 8.dp, end = 10.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(ClaudeTerracotta)
                                    )
                                }
                                FormattedMarkdownText(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                                    color = textColor,
                                    modifier = Modifier.weight(1f),
                                    showTrailingCursor = isTrailingStreaming && isItemLast,
                                    onLinkClick = onLinkClick
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (section.isOrdered) {
                            Text(
                                text = "${section.index}.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = ClaudeTerracotta,
                                modifier = Modifier.width(22.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp, end = 10.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(ClaudeTerracotta)
                            )
                        }
                        FormattedMarkdownText(
                            text = section.text,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                            color = textColor,
                            modifier = Modifier.weight(1f),
                            showTrailingCursor = isTrailingStreaming,
                            onLinkClick = onLinkClick
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        thickness = 0.8.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    FormattedMarkdownText(
                        text = section.text,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                        color = textColor,
                        showTrailingCursor = isTrailingStreaming,
                        onLinkClick = onLinkClick
                    )
                }
            }
            }
        }
    }
}

/**
 * High-performance JavaScript bridge for KaTeX HTML container.
 * Reports dynamic content width and height in real time via ResizeObserver and render completion events.
 */
class KaTeXCaptureBridge(private val onMeasured: (Int, Int) -> Unit) {
    @JavascriptInterface
    fun onRenderComplete(w: Double, h: Double) { onMeasured(w.toInt(), h.toInt()) }
    @JavascriptInterface
    fun onSize(w: Double, h: Double) { onMeasured(w.toInt(), h.toInt()) }
    @JavascriptInterface
    fun onHeight(h: Double) { onMeasured(0, h.toInt()) }
}

/**
 * Direct, vector-quality KaTeX Math View.
 * Embeds a transparent, hardware-accelerated WebView that renders formulas directly from local assets.
 * Self-sizing via AndroidBridge and supports live-streaming equation rendering during active chat.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXMathView(
    formula: String,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val currentFormula by rememberUpdatedState(formula)
    val currentIsDark by rememberUpdatedState(isDark)
    var measuredHeightDp by remember { mutableStateOf(52.dp) }
    var isLoaded by remember { mutableStateOf(false) }
    val unicodeFallback = remember(formula) { formatLatexToUnicode(formula) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPageFinished by remember { mutableStateOf(false) }

    // Live update WebView whenever formula, dark theme, or page load state changes
    LaunchedEffect(formula, isDark, isPageFinished) {
        val wv = webViewRef
        if (wv != null && isPageFinished) {
            val safeFormula = JSONObject.quote(formula)
            wv.evaluateJavascript(
                "if (window.renderMath) { window.renderMath($safeFormula, $isDark); }",
                null
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(measuredHeightDp),
        contentAlignment = Alignment.Center
    ) {
        // Fallback display while KaTeX is first initializing
        if (!isLoaded) {
            Text(
                text = unicodeFallback,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    letterSpacing = 0.4.sp
                ),
                color = if (isDark) Color(0xFFECECF1) else Color(0xFF1A1A1E),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0) // 100% transparent background
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    isHorizontalScrollBarEnabled = true
                    isVerticalScrollBarEnabled = false
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    val bridge = KaTeXCaptureBridge { _, hPx ->
                        post {
                            if (hPx > 0) {
                                val targetDp = (hPx + 10).coerceIn(36, 800).dp
                                measuredHeightDp = targetDp
                                isLoaded = true
                            }
                        }
                    }
                    addJavascriptInterface(bridge, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isPageFinished = true
                            try {
                                view?.evaluateJavascript(
                                    "if (window.renderMath) { window.renderMath(${JSONObject.quote(currentFormula)}, $currentIsDark); }",
                                    null
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                    loadUrl("file:///android_asset/katex/katex_container.html")
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
                try {
                    val safeFormula = JSONObject.quote(formula)
                    webView.evaluateJavascript(
                        "if (window.renderMath) { window.renderMath($safeFormula, $isDark); }",
                        null
                    )
                } catch (_: Throwable) {}
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(measuredHeightDp)
                .alpha(if (isLoaded) 1f else 0.01f)
        )
    }
}

/**
 * Modern, publication-grade mathematical formula block view.
 * Embeds crisp vector KaTeX rendering inside a dedicated math card with domain category badge,
 * TeX source toggle, smooth horizontal scrolling for matrices, and one-tap copy with haptic feedback.
 */
@Composable
fun MathEquationBlockView(
    formula: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val normalizedFormula = remember(formula) { normalizeLatexFormula(formula) }
    val category = remember(normalizedFormula) { detectMathCategory(normalizedFormula) }
    var isCopied by remember { mutableStateOf(false) }
    var showRawLatex by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val cardBg = if (isDark) Color(0xFF1E1F24) else Color(0xFFF4F6FA)
    val borderColor = if (isDark) Color(0xFF2E313A) else Color(0xFFDCE1EB)
    val badgeTint = ClaudeTerracotta

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = androidx.compose.foundation.BorderStroke(0.8.dp, borderColor),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Header Action Bar: Category Badge + TeX toggle + Copy button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Category Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeTint.copy(alpha = 0.12f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = when (category) {
                                    "Matrix" -> "⊞"
                                    "Integral" -> "∫"
                                    "Series" -> "∑"
                                    "Calculus" -> "∂"
                                    "Vectors" -> "→"
                                    "Quantum" -> "Ψ"
                                    else -> "ƒ"
                                },
                                fontSize = 11.sp,
                                color = badgeTint,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = category.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = badgeTint
                            )
                        }
                    }

                    // Actions: TeX Toggle + Copy Formula
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Raw LaTeX toggle
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showRawLatex = !showRawLatex
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = if (showRawLatex) "Preview" else "TeX",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }

                        // Copy Button
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("LaTeX Formula", normalizedFormula))
                                isCopied = true
                                Toast.makeText(context, "Formula copied", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCopied) ChatGptEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = if (isCopied) "Copied" else "Copy LaTeX",
                                    tint = if (isCopied) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = if (isCopied) "Copied" else "Copy",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (isCopied) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Formula Content Area
                if (showRawLatex) {
                    SelectionContainer {
                        Text(
                            text = normalizedFormula,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            color = if (isDark) Color(0xFFE2E2E8) else Color(0xFF1E1E24),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 6.dp)
                        )
                    }
                } else {
                    DisableSelection {
                        KaTeXMathView(
                            formula = normalizedFormula,
                            isDark = isDark,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modern Claude/Gemini-style Interactive Inline Artifact Card.
 * Renders document, code, markdown, html, or svg artifacts generated directly in assistant messages
 * with rich iconography, copy action, and one-tap full-screen / bottom-sheet exploration.
 */
@Composable
fun InlineArtifactCardView(
    artifact: MarkdownBlock.Artifact,
    onOpenArtifact: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val typeLower = artifact.type.lowercase()
    val langLower = artifact.language.lowercase()
    val (icon, tint, badgeLabel) = when {
        typeLower.contains("code") || langLower in setOf("python", "py", "kotlin", "kt", "js", "ts", "java", "cpp", "c", "rust", "rs", "go", "sh", "bash") ->
            Triple(Icons.Default.Code, ChatGptBlue, if (artifact.language.isNotBlank()) artifact.language.uppercase() else "CODE")
        typeLower.contains("html") || langLower in setOf("html", "htm") ->
            Triple(Icons.Default.Language, Color(0xFFE65100), "HTML")
        typeLower.contains("svg") || langLower == "svg" ->
            Triple(Icons.Default.Brush, Color(0xFF9C27B0), "SVG")
        typeLower.contains("markdown") || langLower in setOf("markdown", "md") ->
            Triple(Icons.Default.Article, ClaudeTerracotta, "NOTES")
        typeLower.contains("csv") || typeLower.contains("json") || langLower in setOf("csv", "json") ->
            Triple(Icons.Default.TableChart, ChatGptEmerald, "DATA")
        else ->
            Triple(Icons.Default.Description, ClaudeTerracotta, if (artifact.language.isNotBlank()) artifact.language.uppercase() else "DOC")
    }

    val cardBg = if (isDark) Color(0xFF1B1C22) else Color(0xFFF7F6F3)
    val headerBg = if (isDark) Color(0xFF22242C) else Color(0xFFEFECE5)
    val borderColor = if (isDark) Color(0xFF2F323E) else Color(0xFFDEDBD2)
    val previewLines = remember(artifact.content) {
        artifact.content.lines().take(5).joinToString("\n")
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenArtifact()
            },
        shape = RoundedCornerShape(14.dp),
        color = cardBg
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = artifact.title.ifBlank { artifact.identifier },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = tint.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    ),
                                    color = tint,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Artifact • Tap to view & save to phone",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                // Actions: Open & Copy
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(artifact.title, artifact.content))
                            isCopied = true
                            Toast.makeText(context, "Artifact content copied", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isCopied) ChatGptEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = if (isCopied) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = if (isCopied) "Copied" else "Copy",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = if (isCopied) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenArtifact()
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = ClaudeTerracotta
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                            Text(
                                text = "Open",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Code / Text Preview Snippet
            if (previewLines.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = previewLines,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Public JavaScript bridge for KaTeX HTML container communication.
 * Handles size changes dynamically for free-size math rendering.
 */
object SyntaxHighlighter {
    private val keywords = setOf(
        "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
        "data", "default", "def", "delete", "do", "elif", "else", "enum", "export", "extends", "false",
        "fi", "final", "finally", "fn", "for", "from", "fun", "function", "if", "implements", "import",
        "in", "inline", "instanceof", "interface", "internal", "is", "let", "match", "mut", "new",
        "nil", "none", "null", "object", "open", "operator", "out", "override", "package", "private",
        "protected", "public", "return", "sealed", "select", "self", "static", "struct", "super",
        "suspend", "switch", "then", "this", "throw", "true", "try", "type", "typeof", "val", "var",
        "void", "when", "where", "while", "yield", "echo", "printf", "insert", "update",
        "create", "table", "alter", "drop", "join", "True", "False", "None", "self", "cls",
        "with", "lambda", "pass", "raise", "except", "global", "nonlocal", "assert", "and", "or", "not"
    )

    private val commonTypes = setOf(
        "Int", "Long", "Float", "Double", "String", "Boolean", "Char", "Byte", "Short",
        "Unit", "Any", "List", "Map", "Set", "Array", "int", "float", "double", "bool",
        "char", "void", "Promise", "Observable", "Flow", "StateFlow", "Composable",
        "Modifier", "Box", "Row", "Column", "Text", "Image", "Surface", "Button",
        "dict", "str", "list", "set", "tuple", "Exception", "Throwable"
    )

    enum class TokenType {
        COMMENT, STRING, NUMBER, KEYWORD, TYPE, ANNOTATION, PLAIN
    }

    data class TokenSpan(val start: Int, val end: Int, val type: TokenType)

    fun highlight(code: String, language: String, isDark: Boolean): androidx.compose.ui.text.AnnotatedString {
        if (code.isBlank()) return androidx.compose.ui.text.AnnotatedString("")
        val cacheKey = "$language:$isDark:${code.hashCode()}:${code.length}"
        val cached = syntaxHighlightCache.get(cacheKey)
        if (cached != null) return cached

        val commentColor = if (isDark) Color(0xFF8B949E) else Color(0xFF6E7781)
        val stringColor = if (isDark) Color(0xFF7EE787) else Color(0xFF116329)
        val numberColor = if (isDark) Color(0xFF79C0FF) else Color(0xFF0550AE)
        val keywordColor = if (isDark) Color(0xFFFF7B72) else Color(0xFFCF222E)
        val typeColor = if (isDark) Color(0xFFFFA657) else Color(0xFF953800)
        val annotationColor = if (isDark) Color(0xFFD2A8FF) else Color(0xFF8250DF)
        val defaultTextColor = if (isDark) Color(0xFFE6EDF3) else Color(0xFF1F2328)

        val langLower = language.lowercase()
        val isHashCommentLang = langLower in setOf("python", "py", "bash", "sh", "shell", "yaml", "yml", "dockerfile", "r")

        val commentRegex = if (isHashCommentLang) {
            Regex("""(#.*)""")
        } else {
            Regex("""(//.*|/\*[\s\S]*?\*/|#.*)""")
        }

        val stringRegex = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\[\s\S]|[^`\\])*`""")
        val annotationRegex = Regex("""@[A-Za-z0-9_]+""")
        val numberRegex = Regex("""\b(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\b""")
        val wordRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

        val spans = mutableListOf<TokenSpan>()
        val taken = java.util.BitSet(code.length)

        fun markRange(start: Int, end: Int, type: TokenType) {
            for (idx in start until end) taken.set(idx)
            spans.add(TokenSpan(start, end, type))
        }

        for (m in commentRegex.findAll(code)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (!taken.get(s)) markRange(s, e, TokenType.COMMENT)
        }

        for (m in stringRegex.findAll(code)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (!taken.get(s)) markRange(s, e, TokenType.STRING)
        }

        for (m in annotationRegex.findAll(code)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (!taken.get(s)) markRange(s, e, TokenType.ANNOTATION)
        }

        for (m in numberRegex.findAll(code)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (!taken.get(s)) markRange(s, e, TokenType.NUMBER)
        }

        for (m in wordRegex.findAll(code)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (!taken.get(s)) {
                val w = m.value
                when {
                    w in keywords -> markRange(s, e, TokenType.KEYWORD)
                    w in commonTypes || (w.firstOrNull()?.isUpperCase() == true && w.length > 1) -> markRange(s, e, TokenType.TYPE)
                }
            }
        }

        spans.sortBy { it.start }

        val built = buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                if (span.start > cursor) {
                    withStyle(SpanStyle(color = defaultTextColor)) {
                        append(code.substring(cursor, span.start))
                    }
                }
                val chunk = code.substring(span.start, span.end)
                val style = when (span.type) {
                    TokenType.COMMENT -> SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)
                    TokenType.STRING -> SpanStyle(color = stringColor)
                    TokenType.NUMBER -> SpanStyle(color = numberColor)
                    TokenType.KEYWORD -> SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)
                    TokenType.TYPE -> SpanStyle(color = typeColor)
                    TokenType.ANNOTATION -> SpanStyle(color = annotationColor)
                    TokenType.PLAIN -> SpanStyle(color = defaultTextColor)
                }
                withStyle(style) {
                    append(chunk)
                }
                cursor = span.end
            }
            if (cursor < code.length) {
                withStyle(SpanStyle(color = defaultTextColor)) {
                    append(code.substring(cursor))
                }
            }
        }
        syntaxHighlightCache.put(cacheKey, built)
        return built
    }
}

/**
 * Modern ChatGPT Code Block with language badge, copy action, line numbers, and syntax highlighting.
 */
@Composable
fun CodeBlockView(
    language: String,
    code: String,
    modifier: Modifier = Modifier,
    showLineNumbers: Boolean = true
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val displayLang = if (language.isNotBlank()) language.uppercase() else "CODE"
    var isCopied by remember { mutableStateOf(false) }
    var isWrapped by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val lines = remember(code) { code.lines() }
    val lineCount = lines.size
    val highlightedCode = remember(code, language, isDark) {
        SyntaxHighlighter.highlight(code, language, isDark)
    }

    val headerBg = if (isDark) Color(0xFF1B1B20) else Color(0xFFEAE8E2)
    val bodyBg = if (isDark) Color(0xFF101014) else Color(0xFFF9F9F8)
    val borderColor = if (isDark) Color(0xFF2C2C34) else Color(0xFFDDDCD5)
    val lineNumColor = if (isDark) Color(0xFF555562) else Color(0xFFA0A0A8)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bodyBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    ) {
        // Modern ChatGPT Code Header Bar
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isDark) Color(0xFF282830) else Color(0xFFDEDBD4))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = displayLang,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp
                            ),
                            color = if (isDark) Color(0xFFD0D0D8) else Color(0xFF404048)
                        )
                    }

                    if (lineCount > 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$lineCount lines",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = if (isDark) Color(0xFF7E7E8A) else Color(0xFF888892)
                            )
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Wrap / Scroll toggle
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isWrapped = !isWrapped
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isWrapped) ClaudeTerracotta.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Text(
                            text = if (isWrapped) "Wrap" else "Scroll",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isWrapped) ClaudeTerracotta else if (isDark) Color(0xFFA6A6B0) else Color(0xFF606068),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    // Copy code button
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Code", code))
                            isCopied = true
                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isCopied) ChatGptEmerald.copy(alpha = 0.15f)
                                else if (isDark) Color(0xFF282832) else Color(0xFFDCDAD2)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = if (isCopied) "Copied" else "Copy code",
                                modifier = Modifier.size(12.dp),
                                tint = if (isCopied) ChatGptEmerald else if (isDark) Color(0xFFA6A6B0) else Color(0xFF505058)
                            )
                            Text(
                                text = if (isCopied) "Copied!" else "Copy",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = if (isCopied) ChatGptEmerald else if (isDark) Color(0xFFA6A6B0) else Color(0xFF505058)
                            )
                        }
                    }
                }
            }
        }

        // Code Content with Line Numbers and Selection
        SelectionContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                val shouldShowLineNumbers = showLineNumbers && !isWrapped && lineCount > 1
                if (shouldShowLineNumbers) {
                    DisableSelection {
                        val lineNumsText = (1..lineCount).joinToString("\n")
                        Text(
                            text = lineNumsText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 20.sp,
                                fontSize = 12.sp,
                                textAlign = TextAlign.End
                            ),
                            color = lineNumColor,
                            modifier = Modifier
                                .padding(start = 10.dp, end = 8.dp)
                                .widthIn(min = 22.dp)
                                .drawBehind {
                                    // Clean vertical separator line without unbounded fillMaxHeight
                                    drawLine(
                                        color = borderColor.copy(alpha = 0.6f),
                                        start = Offset(size.width, 0f),
                                        end = Offset(size.width, size.height),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                        )
                    }
                }

                val codeModifier = if (isWrapped) {
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                }

                Text(
                    text = highlightedCode,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                        fontSize = 12.sp
                    ),
                    modifier = codeModifier
                )
            }
        }
    }
}

/**
 * Modern Markdown Table with horizontal scroll, zebra rows, and synchronized columns.
 */
@Composable
fun TableBlockView(
    headers: List<String>,
    rows: List<List<String>>,
    onLinkClick: ((String) -> Unit)? = null
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val headerBg = if (isDark) Color(0xFF1E1E22) else Color(0xFFECEAE4)
    val rowAltBg = if (isDark) Color(0xFF18181C) else Color(0xFFF7F6F2)
    val rowNormBg = if (isDark) Color(0xFF141416) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color(0xFF2E2E36) else Color(0xFFE2E0D8)

    val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 1)
    val colWidths = remember(headers, rows) {
        (0 until colCount).map { colIdx ->
            val hLen = headers.getOrNull(colIdx)?.length ?: 0
            val rMaxLen = rows.maxOfOrNull { it.getOrNull(colIdx)?.length ?: 0 } ?: 0
            val maxLen = maxOf(hLen, rMaxLen)
            (maxLen * 9.5).coerceIn(100.0, 320.0).dp
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = rowNormBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .background(headerBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                (0 until colCount).forEach { colIdx ->
                    val header = headers.getOrNull(colIdx) ?: ""
                    val colWidth = colWidths.getOrElse(colIdx) { 120.dp }
                    Box(
                        modifier = Modifier
                            .width(colWidth)
                            .padding(end = 12.dp)
                    ) {
                        FormattedMarkdownText(
                            text = header.trim(),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            onLinkClick = onLinkClick
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = borderColor)

            // Data Rows
            rows.forEachIndexed { index, row ->
                val bg = if (index % 2 == 1) rowAltBg else rowNormBg
                Row(
                    modifier = Modifier
                        .background(bg)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    (0 until colCount).forEach { colIdx ->
                        val cell = row.getOrNull(colIdx) ?: ""
                        val colWidth = colWidths.getOrElse(colIdx) { 120.dp }
                        Box(
                            modifier = Modifier
                                .width(colWidth)
                                .padding(end = 12.dp)
                        ) {
                            FormattedMarkdownText(
                                text = cell.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                onLinkClick = onLinkClick
                            )
                        }
                    }
                }
                if (index < rows.size - 1) {
                    HorizontalDivider(thickness = 0.5.dp, color = borderColor.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * Clickable and richly formatted markdown text block.
 * Supports inline annotations, clickable links, and graceful fallback.
 */
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
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "inlineCursorAlpha"
    )

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val baseAnnotatedString = remember(text, color, isDark) {
        buildFormattedInlineTextInternal(text, color, isDark)
    }
    val annotatedString = if (showTrailingCursor) {
        buildAnnotatedString {
            append(baseAnnotatedString)
            withStyle(SpanStyle(color = ClaudeTerracotta.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold)) {
                append(" ▍")
            }
        }
    } else {
        baseAnnotatedString
    }

    val hasUrl = remember(annotatedString) {
        annotatedString.getStringAnnotations(tag = "URL", start = 0, end = annotatedString.length).isNotEmpty()
    }
    val uriHandler = LocalUriHandler.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotatedString,
        style = style,
        color = color,
        modifier = if (hasUrl) {
            modifier.pointerInput(annotatedString) {
                detectTapGestures { offset ->
                    layoutResult?.let { layout ->
                        val charOffset = layout.getOffsetForPosition(offset)
                        annotatedString.getStringAnnotations(tag = "URL", start = charOffset, end = charOffset)
                            .firstOrNull()?.let { annotation ->
                                val url = annotation.item
                                if (onLinkClick != null) {
                                    onLinkClick(url)
                                } else {
                                    try {
                                        uriHandler.openUri(url)
                                    } catch (_: Throwable) {}
                                }
                            }
                    }
                }
            }
        } else {
            modifier
        },
        onTextLayout = { layoutResult = it }
    )
}

private val inlineMarkdownRegex = Regex(
    "(\\*\\*(.+?)\\*\\*|" +
    "\\*(.+?)\\*|" +
    "~~(.+?)~~|" +
    "`(.+?)`|" +
    "\\[(.+?)\\]\\((.+?)\\)|" +
    "\\$\\$([\\s\\S]+?)\\$\\$|" +
    "\\$([^$\\n]+?)\\$|" +
    "\\\\\\(([\\s\\S]+?)\\\\\\)|" +
    "\\\\\\[([\\s\\S]+?)\\\\\\])"
)

/**
 * Parses markdown inline styles with zero-allocation LRU cache.
 */
fun buildFormattedInlineTextInternal(raw: String, baseColor: Color, isDark: Boolean): androidx.compose.ui.text.AnnotatedString {
    if (raw.isBlank()) return androidx.compose.ui.text.AnnotatedString("")
    val cacheKey = "$isDark:${baseColor.value}:$raw"
    val cached = inlineTextCache.get(cacheKey)
    if (cached != null) return cached

    val inlineCodeBg = if (isDark) Color(0xFF2C2B27) else Color(0xFFEFECE5)
    val inlineCodeText = if (isDark) Color(0xFFF0EBE1) else Color(0xFF9C4927)
    val mathColor = if (isDark) Color(0xFFEAEAF2) else Color(0xFF202028)

    val built = buildAnnotatedString {
        var cursor = 0
        val matches = inlineMarkdownRegex.findAll(raw)

        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > cursor) {
                val plainChunk = raw.substring(cursor, start)
                append(plainChunk)
            }

            val fullMatch = match.value
            when {
                fullMatch.startsWith("**") -> {
                    val content = match.groupValues.getOrNull(2) ?: ""
                    val inlineFormatted = if (content.contains("$")) {
                        content.replace(Regex("""\$([^$]+)\$""")) { formatLatexToUnicode(it.groupValues[1]) }
                    } else content
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(inlineFormatted)
                    }
                }
                fullMatch.startsWith("*") -> {
                    val content = match.groupValues.getOrNull(3) ?: ""
                    val inlineFormatted = if (content.contains("$")) {
                        content.replace(Regex("""\$([^$]+)\$""")) { formatLatexToUnicode(it.groupValues[1]) }
                    } else content
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(inlineFormatted)
                    }
                }
                fullMatch.startsWith("~~") -> {
                    val content = match.groupValues.getOrNull(4) ?: ""
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = baseColor.copy(alpha = 0.6f))) {
                        append(content)
                    }
                }
                fullMatch.startsWith("`") -> {
                    val content = match.groupValues.getOrNull(5) ?: ""
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = inlineCodeBg,
                            color = inlineCodeText,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" $content ")
                    }
                }
                fullMatch.startsWith("[") -> {
                    val linkText = match.groupValues.getOrNull(6) ?: ""
                    val linkUrl = match.groupValues.getOrNull(7) ?: ""
                    pushStringAnnotation(tag = "URL", annotation = linkUrl)
                    withStyle(
                        SpanStyle(
                            color = ChatGptBlue,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(linkText)
                    }
                    pop()
                }
                fullMatch.startsWith("$$") -> {
                    val mathContent = match.groupValues.getOrNull(8) ?: ""
                    val cleanMath = formatLatexToUnicode(mathContent)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            color = mathColor,
                            letterSpacing = 0.3.sp
                        )
                    ) {
                        append(" $cleanMath ")
                    }
                }
                fullMatch.startsWith("$") -> {
                    val mathContent = match.groupValues.getOrNull(9) ?: ""
                    val trimmedMath = mathContent.trim()
                    val hasMathOperator = trimmedMath.contains("=") || trimmedMath.contains("\\") ||
                        trimmedMath.contains("^") || trimmedMath.contains("_") ||
                        trimmedMath.contains("+") || trimmedMath.contains("-") ||
                        trimmedMath.contains("<") || trimmedMath.contains(">") ||
                        trimmedMath.contains("≤") || trimmedMath.contains("≥") ||
                        trimmedMath.contains("≠") || trimmedMath.contains("≈") ||
                        trimmedMath.contains("→") || trimmedMath.contains("∫") ||
                        trimmedMath.contains("∑") || trimmedMath.contains("∏") ||
                        trimmedMath.contains("∂") || trimmedMath.contains("∞") ||
                        trimmedMath.contains("∈") || trimmedMath.contains("∉") ||
                        trimmedMath.contains("∀") || trimmedMath.contains("∃") ||
                        trimmedMath.contains("frac") || trimmedMath.contains("sqrt") ||
                        trimmedMath.contains("cdot") || trimmedMath.contains("times") ||
                        (trimmedMath.length <= 3 && trimmedMath.all { it.isLetter() })
                    val isCurrencyOrPlain = trimmedMath.matches(Regex("""^\d+(?:[.,]\d+)?\s*(?:USD|EUR|GBP|INR|dollars?|cents?)?$""")) ||
                        (trimmedMath.contains(" ") && !hasMathOperator)
                    if (isCurrencyOrPlain) {
                        append(fullMatch)
                    } else {
                        val cleanMath = formatLatexToUnicode(trimmedMath)
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                color = mathColor,
                                letterSpacing = 0.2.sp
                            )
                        ) {
                            append(cleanMath)
                        }
                    }
                }
                fullMatch.startsWith("\\(") -> {
                    val mathContent = match.groupValues.getOrNull(10) ?: ""
                    val cleanMath = formatLatexToUnicode(mathContent)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            color = mathColor,
                            letterSpacing = 0.2.sp
                        )
                    ) {
                        append(cleanMath)
                    }
                }
                fullMatch.startsWith("\\[") -> {
                    val mathContent = match.groupValues.getOrNull(11) ?: ""
                    val cleanMath = formatLatexToUnicode(mathContent)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            color = mathColor,
                            letterSpacing = 0.3.sp
                        )
                    ) {
                        append(" $cleanMath ")
                    }
                }
                else -> {
                    append(fullMatch)
                }
            }
            cursor = end
        }

        if (cursor < raw.length) {
            val remainingChunk = raw.substring(cursor)
            append(remainingChunk)
        }
    }
    inlineTextCache.put(cacheKey, built)
    return built
}

@Composable
fun buildFormattedInlineText(raw: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    return remember(raw, baseColor, isDark) {
        buildFormattedInlineTextInternal(raw, baseColor, isDark)
    }
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class ListBlock(val items: List<ListItem>) : MarkdownBlock()
    data class ListItem(val isOrdered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    data class MathEquation(val formula: String) : MarkdownBlock()
    data class Artifact(
        val identifier: String,
        val title: String,
        val type: String,
        val language: String,
        val content: String
    ) : MarkdownBlock()
    object Divider : MarkdownBlock()
}

/**
 * Intelligent Markdown and Math block parser.
 * Dispatches code blocks, tables, headings, lists, blockquotes, display math equations, and paragraphs.
 */
fun parseMarkdownBlocks(raw: String, skipCache: Boolean = false): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    if (!skipCache) {
        val cached = markdownBlockCache.get(raw)
        if (cached != null) return cached
    }
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = raw.split("\n")
    var inCodeBlock = false
    var codeLang = ""
    val codeBuffer = StringBuilder()
    val paraBuffer = StringBuilder()
    var listIndex = 1

    fun flushPara() {
        if (paraBuffer.isNotEmpty()) {
            val content = paraBuffer.toString().trim()
            if (content.isNotEmpty()) {
                if (isPureEquationLine(content)) {
                    blocks.add(MarkdownBlock.MathEquation(content))
                } else {
                    blocks.add(MarkdownBlock.Paragraph(content))
                }
            }
            paraBuffer.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Claude & Antigravity Inline Artifact Blocks: <antArtifact ...>...</antArtifact> or <artifact ...>...</artifact>
        if (trimmed.startsWith("<antArtifact") || trimmed.startsWith("<artifact")) {
            flushPara()
            val isAnt = trimmed.startsWith("<antArtifact")
            val closeTag = if (isAnt) "</antArtifact>" else "</artifact>"
            val idMatch = Regex("""identifier=["']([^"']+)["']""").find(trimmed)
            val titleMatch = Regex("""title=["']([^"']+)["']""").find(trimmed)
            val typeMatch = Regex("""type=["']([^"']+)["']""").find(trimmed)
            val langMatch = Regex("""language=["']([^"']+)["']""").find(trimmed)
            val id = idMatch?.groupValues?.get(1) ?: "artifact"
            val title = titleMatch?.groupValues?.get(1) ?: id
            val type = typeMatch?.groupValues?.get(1) ?: "text/plain"
            val language = langMatch?.groupValues?.get(1) ?: ""

            if (trimmed.contains(closeTag)) {
                val inner = trimmed.substringAfter(">").substringBefore(closeTag)
                blocks.add(MarkdownBlock.Artifact(
                    identifier = id,
                    title = title,
                    type = type,
                    language = language,
                    content = inner
                ))
                val afterClose = trimmed.substringAfter(closeTag).trim()
                if (afterClose.isNotEmpty()) {
                    if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                    paraBuffer.append(afterClose)
                }
                i++
                continue
            } else {
                val artBuffer = StringBuilder()
                val afterOpen = trimmed.substringAfter(">").trim()
                if (afterOpen.isNotEmpty()) artBuffer.append(afterOpen).append("\n")
                i++
                while (i < lines.size) {
                    val curLine = lines[i]
                    if (curLine.contains(closeTag)) {
                        val beforeClose = curLine.substringBefore(closeTag)
                        if (beforeClose.isNotEmpty()) artBuffer.append(beforeClose).append("\n")
                        val afterClose = curLine.substringAfter(closeTag).trim()
                        if (afterClose.isNotEmpty()) {
                            if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                            paraBuffer.append(afterClose)
                        }
                        i++
                        break
                    } else {
                        artBuffer.append(curLine).append("\n")
                        i++
                    }
                }
                blocks.add(MarkdownBlock.Artifact(
                    identifier = id,
                    title = title,
                    type = type,
                    language = language,
                    content = artBuffer.toString().trimEnd()
                ))
                continue
            }
        }

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                if (codeLang.equals("math", ignoreCase = true) || codeLang.equals("latex", ignoreCase = true) || codeLang.equals("katex", ignoreCase = true)) {
                    blocks.add(MarkdownBlock.MathEquation(codeBuffer.toString().trimEnd()))
                } else {
                    blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
                }
                codeBuffer.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                flushPara()
                codeLang = trimmed.removePrefix("```").trim()
                inCodeBlock = true
            }
            i++
            continue
        }

        if (inCodeBlock) {
            codeBuffer.append(line).append("\n")
            i++
            continue
        }

        // Display Math Block $$ ... $$ (standalone or embedded in text)
        if (trimmed.contains("$$") && !trimmed.startsWith("```")) {
            val before = trimmed.substringBefore("$$").trim()
            val remainder = trimmed.substringAfter("$$")
            if (remainder.contains("$$")) {
                val formula = remainder.substringBefore("$$").trim()
                val after = remainder.substringAfter("$$").trim()
                if (before.isNotEmpty()) {
                    if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                    paraBuffer.append(before)
                    flushPara()
                } else {
                    flushPara()
                }
                if (formula.isNotEmpty()) {
                    blocks.add(MarkdownBlock.MathEquation(formula))
                }
                if (after.isNotEmpty()) {
                    if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                    paraBuffer.append(after)
                }
                i++
                continue
            } else {
                if (before.isNotEmpty()) {
                    if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                    paraBuffer.append(before)
                    flushPara()
                } else {
                    flushPara()
                }
                val mathBuffer = StringBuilder()
                val first = remainder.trim()
                if (first.isNotEmpty()) mathBuffer.append(first).append("\n")
                i++
                while (i < lines.size) {
                    val curLine = lines[i]
                    val curTrimmed = curLine.trim()
                    if (curTrimmed.contains("$$")) {
                        val beforeEnd = curTrimmed.substringBefore("$$").trim()
                        if (beforeEnd.isNotEmpty()) mathBuffer.append(beforeEnd).append("\n")
                        val afterEnd = curTrimmed.substringAfter("$$").trim()
                        if (afterEnd.isNotEmpty()) {
                            if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                            paraBuffer.append(afterEnd)
                        }
                        i++
                        break
                    } else {
                        mathBuffer.append(curLine).append("\n")
                        i++
                    }
                }
                val formula = mathBuffer.toString().trim()
                if (formula.isNotEmpty()) {
                    blocks.add(MarkdownBlock.MathEquation(formula))
                }
                continue
            }
        }

        // Display Math Block \[ ... \] (standalone or embedded in text)
        if (trimmed.contains("\\[") && trimmed.contains("\\]") && !trimmed.startsWith("```")) {
            val before = trimmed.substringBefore("\\[").trim()
            val formula = trimmed.substringAfter("\\[").substringBefore("\\]").trim()
            val after = trimmed.substringAfter("\\]").trim()
            if (before.isNotEmpty()) {
                if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                paraBuffer.append(before)
                flushPara()
            } else {
                flushPara()
            }
            if (formula.isNotEmpty()) {
                blocks.add(MarkdownBlock.MathEquation(formula))
            }
            if (after.isNotEmpty()) {
                if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                paraBuffer.append(after)
            }
            i++
            continue
        }

        // Display Math Block \[ ... \] multi-line
        if (trimmed.startsWith("\\[")) {
            flushPara()
            if (trimmed.length > 2 && trimmed.endsWith("\\]")) {
                val formula = trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
                if (formula.isNotEmpty()) {
                    blocks.add(MarkdownBlock.MathEquation(formula))
                }
                i++
                continue
            } else {
                val mathBuffer = StringBuilder()
                val first = trimmed.removePrefix("\\[").trim()
                if (first.isNotEmpty()) mathBuffer.append(first).append("\n")
                i++
                while (i < lines.size) {
                    val curLine = lines[i]
                    val curTrimmed = curLine.trim()
                    if (curTrimmed.contains("\\]")) {
                        val beforeEnd = curTrimmed.substringBefore("\\]").trim()
                        if (beforeEnd.isNotEmpty()) mathBuffer.append(beforeEnd).append("\n")
                        val afterEnd = curTrimmed.substringAfter("\\]").trim()
                        if (afterEnd.isNotEmpty()) {
                            if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                            paraBuffer.append(afterEnd)
                        }
                        i++
                        break
                    } else {
                        mathBuffer.append(curLine).append("\n")
                        i++
                    }
                }
                val formula = mathBuffer.toString().trim()
                if (formula.isNotEmpty()) {
                    blocks.add(MarkdownBlock.MathEquation(formula))
                }
                continue
            }
        }

        // Standalone single-dollar math equation line: $ ... $
        if (trimmed.startsWith("$") && trimmed.endsWith("$") && trimmed.length >= 3 && !trimmed.startsWith("$$")) {
            val formula = trimmed.substring(1, trimmed.length - 1).trim()
            if (formula.isNotEmpty()) {
                flushPara()
                blocks.add(MarkdownBlock.MathEquation(formula))
                i++
                continue
            }
        }

        // LaTeX environment blocks \begin{...} ... \end{...}
        if (trimmed.startsWith("\\begin{")) {
            flushPara()
            val envMatch = Regex("""\\begin\{([a-zA-Z0-9*]+)\}""").find(trimmed)
            val env = envMatch?.groupValues?.get(1) ?: trimmed.substringAfter("\\begin{").substringBefore("}").trim()
            val mathBuffer = StringBuilder(line).append("\n")
            i++
            while (i < lines.size && !lines[i].contains("\\end{$env}")) {
                mathBuffer.append(lines[i]).append("\n")
                i++
            }
            if (i < lines.size) {
                mathBuffer.append(lines[i]).append("\n")
                i++
            }
            val formula = mathBuffer.toString().trim()
            if (formula.isNotEmpty()) {
                blocks.add(MarkdownBlock.MathEquation(formula))
            }
            continue
        }

        // Table detection: line starts and ends with '|' and next line contains '---'
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size) {
            val nextTrimmed = lines[i + 1].trim()
            if (nextTrimmed.startsWith("|") && nextTrimmed.contains("---")) {
                flushPara()
                val headers = trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                i += 2 // skip header and divider line
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    val cells = lines[i].trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                    rows.add(cells)
                    i++
                }
                blocks.add(MarkdownBlock.Table(headers, rows))
                continue
            }
        }

        // Group consecutive Blockquotes & Callouts
        if (trimmed.startsWith(">")) {
            flushPara()
            val quoteBuffer = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                val qLine = lines[i].trim().removePrefix(">").trim()
                if (quoteBuffer.isNotEmpty()) quoteBuffer.append("\n")
                quoteBuffer.append(qLine)
                i++
            }
            val quoteText = quoteBuffer.toString().trim()
            if (quoteText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Blockquote(quoteText))
            }
            continue
        }

        // Group consecutive Lists (both unordered and ordered)
        val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ")
        val isOrdered = trimmed.matches(Regex("^\\d+\\.\\s+.*"))
        if (isBullet || isOrdered) {
            flushPara()
            val listItems = mutableListOf<MarkdownBlock.ListItem>()
            while (i < lines.size) {
                val lTrim = lines[i].trim()
                if (lTrim.startsWith("- ") || lTrim.startsWith("* ")) {
                    val itemText = if (lTrim.startsWith("- ")) lTrim.removePrefix("- ") else lTrim.removePrefix("* ")
                    listItems.add(MarkdownBlock.ListItem(isOrdered = false, index = 0, text = itemText.trim()))
                    i++
                } else if (lTrim.matches(Regex("^\\d+\\.\\s+.*"))) {
                    val match = Regex("^(\\d+)\\.\\s+(.*)").find(lTrim)
                    val num = match?.groupValues?.get(1)?.toIntOrNull() ?: listIndex
                    val itemText = match?.groupValues?.get(2) ?: lTrim
                    listItems.add(MarkdownBlock.ListItem(isOrdered = true, index = num, text = itemText.trim()))
                    listIndex = num + 1
                    i++
                } else {
                    break
                }
            }
            if (listItems.isNotEmpty()) {
                blocks.add(MarkdownBlock.ListBlock(listItems))
            }
            continue
        }

        when {
            trimmed.startsWith("##### ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(5, trimmed.removePrefix("##### ").trim()))
            }
            trimmed.startsWith("#### ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(4, trimmed.removePrefix("#### ").trim()))
            }
            trimmed.startsWith("### ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            }
            trimmed.startsWith("## ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            }
            trimmed == "---" || trimmed == "***" -> {
                flushPara()
                blocks.add(MarkdownBlock.Divider)
            }
            isPureEquationLine(trimmed) -> {
                flushPara()
                blocks.add(MarkdownBlock.MathEquation(trimmed))
            }
            trimmed.isEmpty() -> {
                flushPara()
                listIndex = 1
            }
            else -> {
                if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                paraBuffer.append(line)
            }
        }
        i++
    }

    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        if (codeLang.equals("math", ignoreCase = true) || codeLang.equals("latex", ignoreCase = true) || codeLang.equals("katex", ignoreCase = true)) {
            blocks.add(MarkdownBlock.MathEquation(codeBuffer.toString().trimEnd()))
        } else {
            blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
        }
    } else {
        flushPara()
    }

    if (!skipCache) {
        markdownBlockCache.put(raw, blocks)
    }
    return blocks
}
