package com.agychat.app.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay
import org.json.JSONObject

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

    // Fix missing leading backslashes on common LaTeX keywords (e.g. "frac{" -> "\frac{")
    val missingSlashRegex = Regex("""(?<!\\)\b(frac|sqrt|sum|int|prod|partial|hbar|alpha|beta|gamma|delta|epsilon|theta|lambda|mu|pi|rho|sigma|tau|phi|psi|omega|Delta|Theta|Lambda|Sigma|Phi|Psi|Omega|ket|bra|braket|hat|vec|mathcal|mathbf|mathbb)\b""")
    s = s.replace(missingSlashRegex) { "\\${it.value}" }

    // Strip dangling backslash at the very end of string (common in streaming)
    s = s.replace(Regex("""\\+\s*$"""), "")

    // Balance unclosed \left and \right delimiters (unmatched delimiters cause KaTeX parse abort)
    val leftCount = Regex("""\\left\b""").findAll(s).count()
    val rightCount = Regex("""\\right\b""").findAll(s).count()
    if (leftCount != rightCount) {
        s = s.replace(Regex("""\\left\s*([(\[{|.])"""), "$1")
            .replace(Regex("""\\right\s*([)\]}|.])"""), "$1")
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
    var text = normalizeLatexFormula(raw)

    // Strip LaTeX environments
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
            num == "1" && den == "4" -> "¼"
            num == "3" && den == "4" -> "¾"
            num == "1" && den == "3" -> "⅓"
            num == "2" && den == "3" -> "⅔"
            num == "1" && den == "8" -> "⅛"
            den.length > 2 || den.contains("+") || den.contains("-") || den.contains(" ") || den.contains("\\") -> {
                if (num.contains("+") || num.contains("-") || num.contains(" ")) "($num)/($den)" else "$num/($den)"
            }
            num.contains("+") || num.contains("-") || num.contains(" ") -> "($num)/$den"
            else -> "$num/$den"
        }
        text = text.replaceRange(m.range, rep)
        m = fracRegex.find(text)
    }

    // Square roots: \sqrt{x} -> √(x)
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

    return text.trim()
}

/**
 * Detects if a standalone line is purely a mathematical equation (like `\frac{d\rho}{dt} = ...`)
 * while strictly ignoring regular English sentences/paragraphs and list items.
 */
fun isPureEquationLine(raw: String): Boolean {
    val s = raw.trim()
    if (s.isBlank() || s.startsWith("#") || s.startsWith("-") || s.startsWith("*") ||
        s.startsWith(">") || s.startsWith("|") || s.startsWith("```") ||
        s.matches(Regex("""^\d+\.\s+.*""")) || s.contains("**") || s.contains("~~") || s.contains("[")
    ) {
        return false
    }

    // Common English prose words: if present with spaces, it is prose, NOT a standalone formula!
    val englishWords = setOf(
        "the", "is", "of", "and", "in", "to", "that", "this", "we", "can",
        "for", "with", "as", "by", "from", "are", "which", "where", "quantum",
        "physics", "system", "systems", "state", "states", "rather", "than",
        "classical", "microscopic", "action", "scales", "comparable", "constant",
        "pure", "physical", "space", "spaces", "represented", "vector", "vectors",
        "potential", "electric", "dipole", "field", "charge", "energy", "force",
        "surface", "volume", "point", "distance", "plane", "line", "axis", "note"
    )

    val words = s.split(Regex("\\s+")).map { it.lowercase().filter { ch -> ch.isLetter() } }.filter { it.isNotBlank() }
    val matchedEng = words.count { it in englishWords }
    if (matchedEng >= 1 && words.size > 2) return false

    val mathTokens = listOf(
        "\\frac", "frac{", "\\int", "\\sum", "\\prod", "\\sqrt", "\\partial",
        "\\nabla", "\\hbar", "\\dagger", "\\ket{", "\\bra{", "\\hat{", "\\vec{"
    )
    val hasMathToken = mathTokens.any { s.contains(it) }
    if (hasMathToken && (s.contains("=") || s.startsWith("\\frac") || s.startsWith("frac{") || s.startsWith("\\int") || s.startsWith("\\sum"))) {
        return true
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
    onLinkClick: ((String) -> Unit)? = null
) {
    val cleanText = remember(text) { sanitizeMarkdownInput(text) }
    val sections = remember(cleanText) { parseMarkdownBlocks(cleanText) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (section in sections) {
            when (section) {
                is MarkdownBlock.MathEquation -> {
                    MathEquationBlockView(formula = section.formula, textColor = textColor)
                }
                is MarkdownBlock.Code -> {
                    CodeBlockView(language = section.language, code = section.code)
                }
                is MarkdownBlock.Table -> {
                    TableBlockView(headers = section.headers, rows = section.rows, onLinkClick = onLinkClick)
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
                        else -> MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.1).sp,
                            lineHeight = 22.sp,
                            fontSize = 15.sp
                        )
                    }
                    FormattedMarkdownText(
                        text = section.text,
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
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
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(ClaudeTerracotta)
                            )
                            Spacer(Modifier.width(10.dp))
                            FormattedMarkdownText(
                                text = section.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                onLinkClick = onLinkClick
                            )
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
                        onLinkClick = onLinkClick
                    )
                }
            }
        }
    }
}

/**
 * ChatGPT-style Display Math View.
 * Seamless, centered, borderless equation presentation using KaTeX with instant Unicode preview.
 * Allows long-press / tap to copy LaTeX equation source with haptic feedback.
 */
@Composable
fun MathEquationBlockView(
    formula: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    DisableSelection {
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val isDark = MaterialTheme.colorScheme.background.red < 0.5f
        val normalizedFormula = remember(formula) { normalizeLatexFormula(formula) }
        val unicodePreview = remember(normalizedFormula) { formatLatexToUnicode(normalizedFormula) }
        var measuredHeightDp by remember { mutableStateOf(58.dp) }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("LaTeX Formula", normalizedFormula))
                    Toast.makeText(context, "Copied LaTeX equation", Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center
        ) {
            KaTeXDisplayView(
                formula = normalizedFormula,
                unicodeFallback = unicodePreview,
                isDark = isDark,
                onHeightMeasured = { cssPixels ->
                    // JavaScript WebView reports dimensions in CSS pixels (1 CSS px = 1 dp in viewport 1.0)
                    val dpVal = (cssPixels + 14).dp
                    if (dpVal in 44.dp..650.dp) {
                        measuredHeightDp = dpVal
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(measuredHeightDp)
            )
        }
    }
}

/**
 * Public JavaScript bridge for KaTeX HTML container communication.
 * Handles Double, Float, Int, and String height measurements safely without reflection errors.
 */
class KaTeXBridge(private val onHeightChanged: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(h: Double) {
        onHeightChanged(h.toInt())
    }

    @JavascriptInterface
    fun onHeight(h: Float) {
        onHeightChanged(h.toInt())
    }

    @JavascriptInterface
    fun onHeight(h: Int) {
        onHeightChanged(h)
    }

    @JavascriptInterface
    fun onHeight(h: String) {
        h.toDoubleOrNull()?.let { onHeightChanged(it.toInt()) }
    }
}

/**
 * Transparent, borderless KaTeX WebView display engine.
 * Renders publication-quality mathematical notation seamlessly inline on canvas.
 * Guaranteed to fail gracefully to mathematical Unicode typography if WebView is unavailable.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXDisplayView(
    formula: String,
    unicodeFallback: String,
    isDark: Boolean,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoaded by remember { mutableStateOf(false) }
    var webViewFailed by remember { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!webViewFailed) {
            DisableSelection {
                AndroidView(
                    factory = { ctx ->
                        try {
                            WebView(ctx).apply {
                                setBackgroundColor(0) // Transparent background
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = true
                                isNestedScrollingEnabled = false

                                val bridge = KaTeXBridge { heightPx ->
                                    post { onHeightMeasured(heightPx) }
                                }
                                addJavascriptInterface(bridge, "AndroidBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoaded = true
                                        try {
                                            evaluateJavascript(
                                                "renderMath(${JSONObject.quote(formula)}, $isDark);",
                                                null
                                            )
                                        } catch (_: Throwable) {}
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        errorCode: Int,
                                        description: String?,
                                        failingUrl: String?
                                    ) {
                                        webViewFailed = true
                                    }
                                }

                                loadUrl("file:///android_asset/katex/katex_container.html")
                            }
                        } catch (t: Throwable) {
                            android.util.Log.e("KaTeXDisplayView", "WebView creation failed, using unicode fallback", t)
                            webViewFailed = true
                            android.view.View(ctx)
                        }
                    },
                    update = { view ->
                        if (isLoaded && !webViewFailed && view is WebView) {
                            try {
                                view.evaluateJavascript(
                                    "renderMath(${JSONObject.quote(formula)}, $isDark);",
                                    null
                                )
                            } catch (t: Throwable) {
                                android.util.Log.w("KaTeXDisplayView", "evaluateJavascript failed", t)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Instant mathematical Serif preview while WebView is evaluating KaTeX or if WebView fails
        if (!isLoaded || webViewFailed) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Ultra-fast, zero-overhead syntax highlighter for Compose AnnotatedString.
 * Highlights keywords, types, strings, numbers, comments, and annotations
 * across Kotlin, Python, JavaScript, TypeScript, Bash, Java, C/C++, Rust, Go, SQL, JSON, etc.
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
        "void", "when", "where", "while", "yield", "echo", "printf", "select", "insert", "update",
        "delete", "from", "create", "table", "alter", "drop", "join"
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

        return buildAnnotatedString {
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
                if (showLineNumbers && lineCount > 1) {
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
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(borderColor.copy(alpha = 0.6f))
                    )
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
    onLinkClick: ((String) -> Unit)? = null
) {
    val annotatedString = buildFormattedInlineText(text, color)
    val hasUrl = remember(annotatedString) {
        annotatedString.getStringAnnotations(tag = "URL", start = 0, end = annotatedString.length).isNotEmpty()
    }
    val uriHandler = LocalUriHandler.current

    if (hasUrl) {
        ClickableText(
            text = annotatedString,
            style = style.copy(color = color),
            modifier = modifier,
            onClick = { offset ->
                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
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
        )
    } else {
        Text(
            text = annotatedString,
            style = style,
            color = color,
            modifier = modifier
        )
    }
}

/**
 * Parses markdown inline styles including:
 * - **bold** (with inline math support)
 * - *italic*
 * - ~~strikethrough~~
 * - `inline code`
 * - [link](url)
 * - $inline math$ and \(inline math\) with mathematical serif italic styling
 */
@Composable
fun buildFormattedInlineText(raw: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val inlineCodeBg = if (isDark) Color(0xFF2C2B27) else Color(0xFFEFECE5)
    val inlineCodeText = if (isDark) Color(0xFFF0EBE1) else Color(0xFF9C4927)
    val mathColor = if (isDark) Color(0xFFEAEAF2) else Color(0xFF202028)

    val pattern = remember {
        Regex(
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
    }

    return buildAnnotatedString {
        var cursor = 0
        val matches = pattern.findAll(raw)

        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > cursor) {
                val plainChunk = raw.substring(cursor, start)
                // Append plain English chunks exactly as-is to preserve spacing
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
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class ListItem(val isOrdered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    data class MathEquation(val formula: String) : MarkdownBlock()
    object Divider : MarkdownBlock()
}

/**
 * Intelligent Markdown and Math block parser.
 * Dispatches code blocks, tables, headings, lists, blockquotes, display math equations, and paragraphs.
 */
fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
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

        // Display Math Block $$ ... $$
        if (trimmed.startsWith("$$")) {
            flushPara()
            if (trimmed.length > 2 && trimmed.endsWith("$$")) {
                val formula = trimmed.removePrefix("$$").removeSuffix("$$").trim()
                if (formula.isNotEmpty()) {
                    blocks.add(MarkdownBlock.MathEquation(formula))
                }
                i++
                continue
            } else {
                val mathBuffer = StringBuilder()
                val first = trimmed.removePrefix("$$").trim()
                if (first.isNotEmpty()) mathBuffer.append(first).append("\n")
                i++
                while (i < lines.size && !lines[i].trim().startsWith("$$")) {
                    mathBuffer.append(lines[i]).append("\n")
                    i++
                }
                blocks.add(MarkdownBlock.MathEquation(mathBuffer.toString().trimEnd()))
                i++
                continue
            }
        }

        // Display Math Block \[ ... \]
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
                while (i < lines.size && !lines[i].trim().contains("\\]")) {
                    mathBuffer.append(lines[i]).append("\n")
                    i++
                }
                if (i < lines.size) {
                    val last = lines[i].trim().substringBefore("\\]").trim()
                    if (last.isNotEmpty()) mathBuffer.append(last).append("\n")
                    i++
                }
                blocks.add(MarkdownBlock.MathEquation(mathBuffer.toString().trimEnd()))
                continue
            }
        }

        // LaTeX environment blocks \begin{...} ... \end{...}
        if (trimmed.startsWith("\\begin{")) {
            flushPara()
            val env = trimmed.substringAfter("\\begin{").substringBefore("}")
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
            blocks.add(MarkdownBlock.MathEquation(mathBuffer.toString().trimEnd()))
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

        when {
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
            trimmed.startsWith("> ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Blockquote(trimmed.removePrefix("> ").trim()))
            }
            trimmed == "---" || trimmed == "***" -> {
                flushPara()
                blocks.add(MarkdownBlock.Divider)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                val itemText = if (trimmed.startsWith("- ")) trimmed.removePrefix("- ") else trimmed.removePrefix("* ")
                blocks.add(MarkdownBlock.ListItem(isOrdered = false, index = 0, text = itemText.trim()))
            }
            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushPara()
                val match = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
                if (match != null) {
                    val num = match.groupValues[1].toIntOrNull() ?: listIndex
                    val itemText = match.groupValues[2]
                    blocks.add(MarkdownBlock.ListItem(isOrdered = true, index = num, text = itemText.trim()))
                    listIndex = num + 1
                } else {
                    blocks.add(MarkdownBlock.Paragraph(trimmed))
                }
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

    return blocks
}
