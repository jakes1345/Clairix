package app.clairix

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CaAuditScanner
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.PatternOfLife
import app.clairix.analysis.PatternReport
import app.clairix.analysis.StalkwareScanner
import app.clairix.analysis.SuspiciousCa
import app.clairix.analysis.TlsCanary
import app.clairix.analysis.TlsCanaryResult
import app.clairix.analysis.clock
import app.clairix.comms.CommsRepository
import app.clairix.comms.Contact
import app.clairix.comms.formatClairixNumber
import app.clairix.detect.BleCanary
import app.clairix.detect.BleCanaryState
import app.clairix.detect.DultInterrogator
import app.clairix.detect.DultResult
import app.clairix.detect.LocatorState
import app.clairix.detect.RssiLocator
import app.clairix.detect.RssiLocatorView
import app.clairix.security.BiometricTripwire
import app.clairix.security.BiometricTripwireResult
import app.clairix.security.DuressPhrase
import app.clairix.security.FakeCall
import app.clairix.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Building blocks shared by the on-demand tool pages: the colour tokens, the
 * page skeleton and the cards, badges, buttons and fields they are made of.
 * The pages themselves live with the category they belong to.
 */

// ── Colour tokens shared by the tool pages ────────────────────────────────────

internal val TGroundClr   = Color(0xFF0E1116)
internal val TPanelClr    = Color(0xFF161B23)
internal val TPanelHiClr  = Color(0xFF1D2430)
internal val TInkClr      = Color(0xFFE6EAF1)
internal val TInkDimClr   = Color(0xFFA8B2C1)
internal val TMutedClr    = Color(0xFF6F7A8B)
internal val TRuleClr     = Color(0xFF262E3A)
internal val TAccentClr   = Color(0xFFFF7A3D)
internal val TCriticalClr = Color(0xFFF2545B)
internal val TCautionClr  = Color(0xFFE8B33D)
internal val TClearClr    = Color(0xFF3DB88A)
internal val TBlueClr     = Color(0xFF4A8FD4)
internal val TOnAccentClr = Color(0xFF12161D)
internal val TCardShape   = RoundedCornerShape(4.dp)

private val tFullFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val tDayFmt  = SimpleDateFormat("MMM d yyyy", Locale.US)
private val tTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

internal fun tFull(ts: Long): String = tFullFmt.format(Date(ts))
internal fun tDay(ts: Long): String = tDayFmt.format(Date(ts))
internal fun tTime(ts: Long): String = tTimeFmt.format(Date(ts))

internal fun tSeverityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> TCriticalClr
    Severity.HIGH -> TAccentClr
    Severity.MEDIUM -> TCautionClr
    Severity.LOW -> TMutedClr
}

/** Green at 0, yellow at 0.5, red at 1. */
internal fun tMeterColor(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return if (f <= 0.5f) lerp(TClearClr, TCautionClr, f * 2f) else lerp(TCautionClr, TCriticalClr, (f - 0.5f) * 2f)
}

// ── Shared building blocks ─────────────────────────────────────────────────────

@Composable
internal fun ToolHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TInkClr)
        }
        Text(
            title, color = TInkClr,
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The page skeleton every tool shares: a spaced list and a footer. A page shown
 * on its own screen passes [onBack] and gets a back header with [title]; a page
 * shown under the app shell's top bar, which already carries the title and the
 * back arrow, passes null and starts straight with its content.
 */
@Composable
internal fun ToolPageList(title: String, onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) item(key = "§header") { ToolHeader(title, onBack) }
        else item(key = "§top") { Spacer(Modifier.height(4.dp)) }
        content()
        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun TSectionLabel(text: String) {
    Text(
        text,
        color = TAccentClr, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
internal fun TCard(spacing: Int = 8, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content
    )
}

@Composable
internal fun TKv(label: String, value: String, valueColor: Color = TInkDimClr) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = TMutedClr, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        Text(
            value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End
        )
    }
}

@Composable
internal fun TBadge(label: String, color: Color) {
    Text(
        label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
internal fun TNotice(color: Color, tag: String, text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), TCardShape)
            .border(1.dp, color.copy(alpha = 0.5f), TCardShape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tag, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

internal enum class TButtonStyle { PRIMARY, ACCENT, CRITICAL }

@Composable
internal fun TButton(
    text: String,
    onClick: () -> Unit,
    style: TButtonStyle = TButtonStyle.ACCENT,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val colors = when (style) {
        TButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = TAccentClr, contentColor = TOnAccentClr,
            disabledContainerColor = TAccentClr.copy(alpha = 0.3f), disabledContentColor = TOnAccentClr.copy(alpha = 0.6f)
        )
        TButtonStyle.ACCENT -> ButtonDefaults.buttonColors(
            containerColor = TAccentClr.copy(alpha = 0.15f), contentColor = TAccentClr,
            disabledContainerColor = TAccentClr.copy(alpha = 0.06f), disabledContentColor = TAccentClr.copy(alpha = 0.4f)
        )
        TButtonStyle.CRITICAL -> ButtonDefaults.buttonColors(
            containerColor = TCriticalClr.copy(alpha = 0.15f), contentColor = TCriticalClr,
            disabledContainerColor = TCriticalClr.copy(alpha = 0.06f), disabledContentColor = TCriticalClr.copy(alpha = 0.4f)
        )
    }
    Button(onClick = onClick, colors = colors, shape = TCardShape, enabled = enabled, modifier = modifier) {
        Text(text, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
    }
}

@Composable
internal fun TField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TMutedClr) },
        placeholder = placeholder?.let { { Text(it, color = TMutedClr.copy(alpha = 0.6f)) } },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TInkClr,
            unfocusedTextColor = TInkClr,
            focusedBorderColor = TAccentClr,
            unfocusedBorderColor = TRuleClr,
            cursorColor = TAccentClr
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}

@Composable
internal fun TProgress(text: String) {
    TCard {
        Text(text, color = TInkDimClr, fontSize = 13.sp)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = TAccentClr, trackColor = TRuleClr
        )
    }
}

/** Wall-clock that ticks every [periodMs] so countdowns and "ago" times stay honest. */
@Composable
internal fun rememberToolTick(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

internal fun openExternal(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
