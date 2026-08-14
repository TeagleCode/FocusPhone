package com.focus.launcher

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.BlockedSection
import com.focus.launcher.data.PendingUnlock
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.UnlockKind
import com.focus.launcher.policy.FocusGuardService
import com.focus.launcher.policy.SiteBlockerVpnService
import com.focus.launcher.ui.Focus

/**
 * Two lists in one screen: domains the DNS filter should drop, and in-app
 * sections the accessibility service should close.
 *
 * The section list doubles as the accessibility service's scope: it only
 * receives events from packages named here, and from nothing else.
 */
class BlocklistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BlocklistScreen() }
    }
}

@Composable
private fun BlocklistScreen() {
    val context = LocalContext.current
    val store = remember { PolicyStore(context) }

    var domains by remember { mutableStateOf(store.blockedDomains().sorted()) }
    var sections by remember { mutableStateOf(store.blockedSections()) }
    var newDomain by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var editingHints by remember { mutableStateOf<BlockedSection?>(null) }

    editingHints?.let { section ->
        HintEditor(
            section = section,
            onDismiss = { editingHints = null },
            onSave = { updated ->
                store.upsertSection(updated)
                sections = store.blockedSections()
                FocusGuardService.refreshScope()
                editingHints = null
            }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "blocked sites",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Entries also cover subdomains. Blocking works by refusing DNS lookups, " +
                "so it applies in every browser and app. Only one VPN can be active " +
                "on Android, so this cannot run alongside a commercial VPN.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        notice?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Focus.Secondary, fontSize = Focus.MetaSize, lineHeight = 20.sp)
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = newDomain,
                onValueChange = { newDomain = it.trim().lowercase() },
                singleLine = true,
                textStyle = TextStyle(color = Focus.Primary, fontSize = 17.sp),
                cursorBrush = SolidColor(Focus.Secondary),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Focus.RadiusField))
                            .background(Focus.Surface)
                            .padding(horizontal = 18.dp, vertical = 15.dp)
                    ) {
                        if (newDomain.isEmpty()) {
                            Text("example.com", color = Focus.Tertiary, fontSize = 17.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "add",
                color = Focus.Primary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable {
                        val entry = newDomain.removePrefix("www.")
                        if (entry.contains(".") && !entry.contains("/")) {
                            store.addBlockedDomain(entry)
                            domains = store.blockedDomains().sorted()
                            newDomain = ""
                            notice = null
                        } else {
                            notice = "Enter a bare domain, like example.com — " +
                                "DNS blocking cannot match a path."
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 15.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        domains.forEach { domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Text(domain, color = Focus.Primary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                // Removing a site is a relaxation, so it goes through the same
                // 24-hour delay as unblocking an app.
                Text(
                    "request removal",
                    color = Focus.Secondary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Focus.RadiusRow))
                        .clickable { notice = requestDomainRemoval(store, domain) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // The service starts asynchronously, so its state is re-read on a tick
        // rather than once at first composition — otherwise the button keeps
        // saying "start" after the filter is already up.
        var vpnTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                vpnTick++
            }
        }
        val running = remember(vpnTick) { SiteBlockerVpnService.isRunning() }
        Text(
            if (running) "stop site filter" else "start site filter",
            color = Focus.Primary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.Surface)
                .clickable {
                    if (running) {
                        context.startService(
                            Intent(context, SiteBlockerVpnService::class.java)
                                .setAction(SiteBlockerVpnService.ACTION_STOP)
                        )
                    } else {
                        val consent = VpnService.prepare(context)
                        if (consent != null) context.startActivity(consent)
                        else context.startForegroundService(
                            Intent(context, SiteBlockerVpnService::class.java)
                        )
                    }
                    notice = null
                }
                .padding(horizontal = 22.dp, vertical = 14.dp)
        )

        Spacer(Modifier.height(44.dp))

        Text(
            "in-app sections",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Only the apps listed here are watched. Everything else on the phone " +
                "is invisible to the blocker.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        if (!FocusGuardService.isEnabled(context)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "The accessibility service is off, so nothing here is active yet. " +
                    "Turn it on in setup.",
                color = Focus.Secondary,
                fontSize = Focus.MetaSize,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(18.dp))

        FocusGuardService.DEFAULT_SECTIONS.forEach { preset ->
            val active = sections.firstOrNull { it.packageName == preset.packageName }
            SectionRow(
                section = active ?: preset,
                active = active != null,
                onToggle = {
                    val next = if (active != null) {
                        sections.filterNot { it.packageName == preset.packageName }
                    } else {
                        sections + preset
                    }
                    store.saveSections(next)
                    sections = next
                    // Without this the service keeps its old scope until it
                    // reconnects, so a newly added app would go unwatched.
                    FocusGuardService.refreshScope()
                },
                onEditHints = { editingHints = active ?: preset }
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "These hints match the current app layouts. When a vendor redesigns its " +
                "feed the block stops working — edit the hints here rather than " +
                "waiting for a new build.",
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(48.dp))
    }
}

private fun requestDomainRemoval(store: PolicyStore, domain: String): String {
    store.pendingUnlock()?.let {
        return "A request for \"${it.target}\" is already pending. Only one at a " +
            "time — cancel it in settings first."
    }
    store.setPendingUnlock(
        PendingUnlock(
            kind = UnlockKind.DOMAIN,
            target = domain,
            requestedAtMs = System.currentTimeMillis()
        )
    )
    return "Requested. \"$domain\" stays blocked for 24 hours, and then only comes " +
        "off once you confirm it in settings."
}

@Composable
private fun SectionRow(
    section: BlockedSection,
    active: Boolean,
    onToggle: () -> Unit,
    onEditHints: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(if (active) Focus.SurfacePressed else Focus.Surface)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle)
            ) {
                Text(
                    section.label.lowercase(),
                    color = if (active) Focus.Primary else Focus.Secondary,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(section.packageName, color = Focus.Ghost, fontSize = 11.sp)
            }
            Text(
                if (active) "watched" else "off",
                color = if (active) Focus.Secondary else Focus.Ghost,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        if (active) {
            Spacer(Modifier.height(10.dp))
            Text(
                "edit hints",
                color = Focus.Tertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable(onClick = onEditHints)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Vendors reshuffle their layouts regularly, so the detection hints have to be
 * fixable on the phone rather than only in a rebuild.
 */
@Composable
private fun HintEditor(
    section: BlockedSection,
    onDismiss: () -> Unit,
    onSave: (BlockedSection) -> Unit
) {
    var ids by remember { mutableStateOf(section.viewIdHints.joinToString("\n")) }
    var texts by remember { mutableStateOf(section.textHints.joinToString("\n")) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(section.label, color = Focus.Primary, fontSize = 24.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(4.dp))
        Text(section.packageName, color = Focus.Ghost, fontSize = 12.sp)

        Spacer(Modifier.height(24.dp))
        Text(
            "One hint per line. A section is closed when any view id matches, or " +
                "when any of the text hints is visible on screen.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(22.dp))
        HintField("view ids", ids) { ids = it }
        Spacer(Modifier.height(18.dp))
        HintField("visible text", texts) { texts = it }

        Spacer(Modifier.height(28.dp))
        Row {
            Text(
                "save",
                color = Focus.Primary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable {
                        onSave(
                            section.copy(
                                viewIdHints = ids.lines().map(String::trim).filter { it.isNotEmpty() },
                                textHints = texts.lines().map(String::trim).filter { it.isNotEmpty() }
                            )
                        )
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "back",
                color = Focus.Secondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun HintField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Focus.Primary, fontSize = 14.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(Focus.Secondary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp)
                        .clip(RoundedCornerShape(Focus.RadiusField))
                        .background(Focus.Surface)
                        .padding(16.dp)
                ) { inner() }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
