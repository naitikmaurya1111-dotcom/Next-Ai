package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay

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

@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val cleanText = remember(text) { sanitizeMarkdownInput(text) }
    val sections = remember(cleanText) { parseMarkdownBlocks(cleanText) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (section in sections) {
            when (section) {
                is MarkdownBlock.Code -> {
                    CodeBlockView(language = section.language, code = section.code)
                }
                is MarkdownBlock.Table -> {
                    TableBlockView(headers = section.headers, rows = section.rows)
                }
                is MarkdownBlock.Heading -> {
                    val style = when (section.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            lineHeight = 26.sp
                        )
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                            lineHeight = 22.sp
                        )
                        else -> MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = buildFormattedInlineText(section.text, textColor),
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
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
                                    Text(
                                        text = buildFormattedInlineText(calloutBody, textColor),
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color = textColor
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
                            Text(
                                text = buildFormattedInlineText(section.text, MaterialTheme.colorScheme.onSurfaceVariant),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Text(
                            text = buildFormattedInlineText(section.text, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        thickness = 0.8.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildFormattedInlineText(section.text, textColor),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 23.sp,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
fun TableBlockView(headers: List<String>, rows: List<List<String>>) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val headerBg = if (isDark) Color(0xFF1E1E22) else Color(0xFFECEAE4)
    val rowAltBg = if (isDark) Color(0xFF18181C) else Color(0xFFF7F6F2)
    val rowNormBg = if (isDark) Color(0xFF141416) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color(0xFF2E2E36) else Color(0xFFE2E0D8)

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
                headers.forEach { header ->
                    Text(
                        text = header.trim(),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .widthIn(min = 90.dp, max = 220.dp)
                            .padding(end = 12.dp)
                    )
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
                    row.forEachIndexed { _, cell ->
                        Text(
                            text = cell.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .widthIn(min = 90.dp, max = 220.dp)
                                .padding(end = 12.dp)
                        )
                    }
                }
                if (index < rows.size - 1) {
                    HorizontalDivider(thickness = 0.5.dp, color = borderColor.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun CodeBlockView(language: String, code: String) {
    val context = LocalContext.current
    val displayLang = if (language.isNotBlank()) language.lowercase() else "code"
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CodeBlockBg)
            .border(1.dp, CodeBlockBorder, RoundedCornerShape(12.dp))
    ) {
        // Modern ChatGPT Code Header Bar
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeBlockHeader)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayLang,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color(0xFFA6A6B0),
                    fontWeight = FontWeight.Medium
                )

                Surface(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Code", code))
                        isCopied = true
                        Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2B2B32).copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFF3E3E48))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = if (isCopied) "Copied" else "Copy code",
                            modifier = Modifier.size(13.dp),
                            tint = if (isCopied) ChatGptEmerald else Color(0xFFA6A6B0)
                        )
                        Text(
                            text = if (isCopied) "Copied!" else "Copy code",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = if (isCopied) ChatGptEmerald else Color(0xFFA6A6B0)
                        )
                    }
                }
            }
        }

        // Code Content with Horizontal Scroll and full text selection
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 19.sp,
                    fontSize = 12.5.sp
                ),
                color = Color(0xFFEDEDF0),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            )
        }
    }
}

/**
 * Converts raw LaTeX math expressions and symbols into clean, human-readable Unicode text
 * seamlessly inline without boxes, cards, or webviews.
 */
fun formatLatexToReadableMath(raw: String): String {
    if (raw.isBlank() || (!raw.contains('\\') && !raw.contains('$') && !raw.contains("frac{") && !raw.contains("sqrt{"))) {
        return raw
    }

    var text = raw

    // Strip LaTeX equation and align environments
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
            num.contains("+") || num.contains("-") -> "($num)/$den"
            den.contains("+") || den.contains("-") -> "$num/($den)"
            else -> "$num/$den"
        }
        text = text.replaceRange(m.range, rep)
        m = fracRegex.find(text)
    }

    // Square roots: \sqrt{x} -> √(x)
    text = text.replace(Regex("""\\?sqrt\{([^{}]+)\}""")) { "√(${it.groupValues[1]})" }

    // Greek letters and math symbols
    val symbols = listOf(
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
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '⁸', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )
    text = text.replace(Regex("""_\{([^{}]+)\}|_([0-9a-zA-Z\+\-])""")) { matchResult ->
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
 * Parses markdown inline styles like **bold**, *italic*, ~~strikethrough~~, `inline code`, and [link](url).
 */
@Composable
fun buildFormattedInlineText(raw: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    val clean = remember(raw) { formatLatexToReadableMath(raw) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val inlineCodeBg = if (isDark) Color(0xFF2C2B27) else Color(0xFFEFECE5)
    val inlineCodeText = if (isDark) Color(0xFFF0EBE1) else Color(0xFF9C4927)

    val pattern = remember {
        Regex("(\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|~~(.+?)~~|`(.+?)`|\\[(.+?)\\]\\((.+?)\\))")
    }

    return buildAnnotatedString {
        var cursor = 0
        val matches = pattern.findAll(clean)

        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > cursor) {
                append(clean.substring(cursor, start))
            }

            val fullMatch = match.value
            when {
                fullMatch.startsWith("**") -> {
                    val content = match.groupValues.getOrNull(2) ?: ""
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(content)
                    }
                }
                fullMatch.startsWith("*") -> {
                    val content = match.groupValues.getOrNull(3) ?: ""
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(content)
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
                    withStyle(
                        SpanStyle(
                            color = ChatGptBlue,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(linkText)
                    }
                }
                else -> {
                    append(fullMatch)
                }
            }
            cursor = end
        }

        if (cursor < clean.length) {
            append(clean.substring(cursor))
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
    object Divider : MarkdownBlock()
}

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
                blocks.add(MarkdownBlock.Paragraph(content))
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
                blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
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
        blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
    } else {
        flushPara()
    }

    return blocks
}
