package dev.soulacy.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.soulacy.android.R
import dev.soulacy.android.inbox.ApprovalTiming
import dev.soulacy.android.ui.Connection
import dev.soulacy.android.ui.text.Markdown
import dev.soulacy.android.ui.text.MdBlock
import dev.soulacy.android.ui.text.MdSpan
import dev.soulacy.android.ui.theme.Soulacy
import kotlinx.coroutines.delay
import java.time.Instant

/** An agent's face: initials on its stable gradient, the same colour as on iOS and the web. */
@Composable
fun AgentAvatar(id: String, name: String, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(Soulacy.avatarBrush(id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name.ifBlank { id }), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.36f).sp)
    }
}

fun initials(name: String): String {
    val words = name.split(' ', '-', '_').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
        else -> "?"
    }
}

@Composable
fun BrandMark(size: Int = 72) {
    Image(painterResource(R.drawable.brand_mark), contentDescription = "Soulacy", modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.24f).dp)))
}

/** The script logotype, as on the iOS Home bar. */
@Composable
fun Wordmark(size: Int = 30) {
    Text("soulacy", fontFamily = Soulacy.wordmark, fontSize = size.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { contentDescription = "Soulacy" })
}

/** A raised card on the canvas, with the hairline border the iOS cards have. */
@Composable
fun SoulCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Soulacy.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (c.dark) c.raised.copy(alpha = 0.72f) else Color.White,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.55f) ?: if (c.dark) Color.White.copy(alpha = 0.08f) else Color(0x14000000)),
        tonalElevation = 0.dp,
        shadowElevation = if (c.dark) 0.dp else 1.dp,
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.25f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = color, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

/** A hairline under the top bar: nothing when connected, moving while checking, coral when offline. */
@Composable
fun ConnectionLine(connection: Connection) {
    when (connection) {
        Connection.Connected -> Spacer(Modifier.height(2.dp))
        Connection.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Soulacy.colors.brand, trackColor = Color.Transparent)
        is Connection.Offline -> Box(Modifier.fillMaxWidth().height(2.dp).background(Soulacy.colors.coral.copy(alpha = 0.8f)))
    }
}

/** "Updated 4 min ago · can't reach the gateway", under any list that has loaded once. */
@Composable
fun StalenessFooter(updatedAt: Long?, connection: Connection) {
    val now = rememberNow(30_000)
    if (updatedAt == null && connection !is Connection.Offline) return
    val age = updatedAt?.let { "Updated " + ApprovalTiming.ago(Instant.ofEpochMilli(it), now) } ?: "Not loaded yet"
    val tail = (connection as? Connection.Offline)?.let { " · offline" }.orEmpty()
    Text(age + tail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** The offline explanation, shown once above a list instead of a dialog. */
@Composable
fun OfflineBanner(connection: Connection) {
    val off = connection as? Connection.Offline ?: return
    Text(
        off.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = Soulacy.colors.brand, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Ticks [now] every [periodMs] so countdowns and "x min ago" stay true. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Instant {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); t = System.currentTimeMillis() } }
    return Instant.ofEpochMilli(t)
}

// --- Markdown ---

@Composable
fun MarkdownText(source: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(source) { Markdown.parse(source) }
    val linkColor = Soulacy.colors.brand
    val codeBg = if (Soulacy.colors.dark) Color.White.copy(alpha = 0.08f) else Color(0x0F000000)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(
                spans(b.spans, linkColor, codeBg), color = color, fontWeight = FontWeight.Bold,
                style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall },
            )
            is MdBlock.Paragraph -> Text(spans(b.spans, linkColor, codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
            is MdBlock.Bullet -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                Text(b.ordinal ?: "•", color = Soulacy.colors.brand, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(22.dp))
                Text(spans(b.spans, linkColor, codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
            }
            is MdBlock.Quote -> Row {
                Box(Modifier.width(3.dp).height(22.dp).background(Soulacy.colors.brand.copy(alpha = 0.6f)))
                Spacer(Modifier.width(10.dp))
                Text(spans(b.spans, linkColor, codeBg), color = color.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            }
            is MdBlock.Code -> Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(12.dp),
            ) { Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color, softWrap = false) }
        }
    }
}

private fun spans(spans: List<MdSpan>, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) when (s) {
        is MdSpan.Text -> withStyle(SpanStyle(fontWeight = if (s.bold) FontWeight.Bold else null, fontStyle = if (s.italic) FontStyle.Italic else null)) { append(s.text) }
        is MdSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${s.text} ") }
        is MdSpan.Link -> withLink(LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(s.text) }
    }
}

@Composable
fun FullScreenCenter(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
