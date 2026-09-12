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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agychat.app.ui.theme.ChatGptEmerald
import com.agychat.app.ui.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import org.json.JSONObject

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
    val missingSlashRegex = Regex("""(?<!\\)\b(frac|sqrt|sum|int|prod|partial|hbar|alpha|beta|gamma|delta|epsilon|theta|lambda|mu|pi|rho|sigma|tau|phi|psi|omega|Delta|Theta|Lambda|Sigma|Phi|Psi|Omega)\b""")
    s = s.replace(missingSlashRegex) { "\\${it.value}" }

    // Strip dangling backslash at the very end of string (common in streaming)
    s = s.replace(Regex("""\\+\s*$"""), "")

    // Balance or strip lone trailing \left if not closed
    if (s.contains("""\left""") && !s.contains("""\right""")) {
        // Strip the trailing unclosed \left token so KaTeX can render the rest
        s = s.replace(Regex("""\\left\s*([(\[{|.])\s*$"""), "$1")
    }

    return s.trim()
}

/**
 * High-quality Unicode mathematical symbol formatter.
 * Converts LaTeX math notation into clean, human-readable Unicode math symbols.
 */
fun formatLatexToUnicode(raw: String): String {
    var s = normalizeLatexFormula(raw)

    // Remove \left and \right
    s = s.replace("\\left", "").replace("\\right", "")
    s = s.replace("\\{", "{").replace("\\}", "}")

    // Fractions \frac{a}{b}
    val fracRegex = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
    var match = fracRegex.find(s)
    var safetyLimit = 0
    while (match != null && safetyLimit < 20) {
        safetyLimit++
        val num = match.groupValues[1]
        val den = match.groupValues[2]
        val replacement = when {
            num == "1" && den == "2" -> "½"
            num == "1" && den == "4" -> "¼"
            num == "3" && den == "4" -> "¾"
            num == "1" && den == "3" -> "⅓"
            num == "2" && den == "3" -> "⅔"
            else -> "($num/$den)"
        }
        s = s.replaceRange(match.range, replacement)
        match = fracRegex.find(s)
    }

    // Square roots \sqrt{x} -> √(x)
    val sqrtRegex = Regex("""\\sqrt\{([^{}]+)\}""")
    s = s.replace(sqrtRegex) { "√(${it.groupValues[1]})" }

    // Hats and accents
    val hats = mapOf(
        "H" to "Ĥ", "A" to "Â", "B" to "B̂", "p" to "p̂", "x" to "x̂", "y" to "ŷ", "z" to "ẑ",
        "\\rho" to "ρ̂", "rho" to "ρ̂", "\\psi" to "ψ̂", "psi" to "ψ̂"
    )
    for ((k, v) in hats) {
        s = s.replace("\\hat{$k}", v).replace("\\hat $k", v)
    }

    // Common Greek and math symbols
    val symbols = mapOf(
        "\\hbar" to "ℏ", "\\dagger" to "†", "\\partial" to "∂", "\\nabla" to "∇", "\\infty" to "∞",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\phi" to "ϕ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Sigma" to "Σ", "\\Phi" to "Φ",
        "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\pm" to "±", "\\times" to "×", "\\cdot" to "·", "\\approx" to "≈", "\\equiv" to "≡",
        "\\le" to "≤", "\\ge" to "≥", "\\neq" to "≠", "\\to" to "→", "\\in" to "∈", "\\notin" to "∉",
        "\\subset" to "⊂", "\\subseteq" to "⊆", "\\cup" to "∪", "\\cap" to "∩"
    )
    for ((k, v) in symbols) {
        s = s.replace(k, v)
    }

    // Subscripts
    val subs = mapOf(
        "0" to "₀", "1" to "₁", "2" to "₂", "3" to "₃", "4" to "₄",
        "5" to "₅", "6" to "₆", "7" to "₇", "8" to "₈", "9" to "₉",
        "k" to "ₖ", "i" to "ᵢ", "j" to "ⱼ", "n" to "ₙ", "m" to "ₘ",
        "t" to "ₜ", "x" to "ₓ", "+" to "₊", "-" to "₋"
    )
    for ((k, v) in subs) {
        s = s.replace("_{$k}", v).replace("_$k", v)
    }

    // Superscripts
    val sups = mapOf(
        "0" to "⁰", "1" to "¹", "2" to "²", "3" to "³", "4" to "⁴",
        "5" to "⁵", "6" to "⁶", "7" to "⁷", "8" to "⁸", "9" to "⁹",
        "n" to "ⁿ", "i" to "ⁱ", "k" to "ᵏ", "t" to "ᵗ", "x" to "ˣ",
        "+" to "⁺", "-" to "⁻", "†" to "†"
    )
    for ((k, v) in sups) {
        s = s.replace("^{$k}", v).replace("^$k", v)
    }

    // Clean up spacing commands and remaining raw macro markers
    s = s.replace("\\,", " ").replace("\\;", " ").replace("\\quad", "   ").replace("\\qquad", "    ")
    s = s.replace(Regex("""\\([a-zA-Z]+)""")) { it.groupValues[1] }

    return s.trim()
}

/**
 * Detects whether a string is a LaTeX display math block or mathematical equation.
 */
fun isLikelyMathBlock(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 2) return true
    if (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 2) return true
    if (trimmed.startsWith("\\begin{") && trimmed.contains("\\end{")) return true

    val indicators = listOf(
        "\\frac", "frac{", "\\sum", "\\int", "\\prod", "\\sqrt", "\\partial", "\\nabla",
        "\\hbar", "\\dagger", "\\hat{", "\\vec{", "\\mathbf{",
        "\\alpha", "\\beta", "\\gamma", "\\delta", "\\rho", "\\sigma", "\\theta",
        "\\lambda", "\\psi", "\\phi", "\\omega", "\\Delta", "\\Theta", "\\Omega",
        "\\approx", "\\neq", "\\le", "\\ge", "\\left(", "\\right)"
    )

    var count = 0
    for (ind in indicators) {
        if (trimmed.contains(ind)) count++
    }

    if (count >= 2) return true
    if (count >= 1 && (trimmed.contains("=") || trimmed.contains("+") || trimmed.contains("-"))) return true

    return false
}

/**
 * Beautiful Math Card Composable with KaTeX offline WebView rendering,
 * instant Unicode math fallback, LaTeX Source toggle, and Copy action.
 */
@Composable
fun MathFormulaBlockView(
    formula: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    var isCopied by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    val normalizedFormula = remember(formula) { normalizeLatexFormula(formula) }
    val unicodePreview = remember(normalizedFormula) { formatLatexToUnicode(normalizedFormula) }
    var webViewHeightDp by remember { mutableStateOf(56.dp) }
    val density = LocalDensity.current

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val cardBg = if (isDark) Color(0xFF141418) else Color(0xFFF7F7FA)
    val cardBorder = if (isDark) Color(0xFF2C2C36) else Color(0xFFE2E2EA)
    val headerBg = if (isDark) Color(0xFF1C1C24) else Color(0xFFEFEFF6)
    val accentColor = ClaudeTerracotta

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = cardBg
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerBg)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = accentColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "∑ fx",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "Formula",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.3.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Toggle Source / Preview
                        Surface(
                            onClick = { showSource = !showSource },
                            shape = RoundedCornerShape(6.dp),
                            color = if (showSource) accentColor.copy(alpha = 0.18f) else Color.Transparent
                        ) {
                            Text(
                                text = if (showSource) "Preview" else "LaTeX",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = if (showSource) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        // Copy Formula Button
                        Surface(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Formula", normalizedFormula))
                                isCopied = true
                                Toast.makeText(context, "Formula copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF2B2B32).copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFF3E3E48))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = if (isCopied) "Copied" else "Copy formula",
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isCopied) ChatGptEmerald else Color(0xFFA6A6B0)
                                )
                                Text(
                                    text = if (isCopied) "Copied!" else "Copy",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (isCopied) ChatGptEmerald else Color(0xFFA6A6B0)
                                )
                            }
                        }
                    }
                }
            }

            // Body: Either KaTeX WebView Preview or Raw LaTeX Source View
            if (showSource) {
                SelectionContainer {
                    Text(
                        text = normalizedFormula,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(14.dp)
                    )
                }
            } else {
                KaTeXWebView(
                    formula = normalizedFormula,
                    unicodeFallback = unicodePreview,
                    isDark = isDark,
                    onHeightMeasured = { px ->
                        val dpVal = with(density) { px.toDp() }
                        if (dpVal > 30.dp) {
                            webViewHeightDp = dpVal.coerceIn(48.dp, 360.dp)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(webViewHeightDp)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Offline KaTeX WebView component using local assets.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXWebView(
    formula: String,
    unicodeFallback: String,
    isDark: Boolean,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoaded by remember { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0) // Transparent
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = true

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onHeight(h: Float) {
                            post { onHeightMeasured(h.toInt()) }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoaded = true
                            evaluateJavascript(
                                "renderMath(${JSONObject.quote(formula)}, $isDark);",
                                null
                            )
                        }
                    }

                    loadUrl("file:///android_asset/katex/katex_container.html")
                }
            },
            update = { webView ->
                webView.evaluateJavascript(
                    "renderMath(${JSONObject.quote(formula)}, $isDark);",
                    null
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        // Instant Unicode fallback while WebView loads initial HTML assets
        if (!isLoaded) {
            SelectionContainer {
                Text(
                    text = unicodeFallback,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        letterSpacing = 0.2.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
    }
}
