package com.focus.launcher

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.BlockedSection
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.policy.SectionBlockerService
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
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
            "Entries also cover subdomains. Blocking works by dropping DNS lookups, so it applies in every browser and app.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

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
                        if (newDomain.contains(".")) {
                            val next = (domains + newDomain).distinct().sorted()
                            store.saveBlockedDomains(next.toSet())
                            domains = next
                            newDomain = ""
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
                // Removing a site is a relaxation, so it is intentionally
                // routed through settings rather than being instant here.
                Text("locked", color = Focus.Ghost, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "start site filter",
            color = Focus.Primary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.Surface)
                .clickable {
                    val consent = VpnService.prepare(context)
                    if (consent != null) {
                        context.startActivity(consent)
                    } else {
                        context.startForegroundService(
                            Intent(context, SiteBlockerVpnService::class.java)
                        )
                    }
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
            "Only the apps listed here are watched. Everything else on the phone is invisible to the blocker.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(18.dp))

        SectionBlockerService.DEFAULT_SECTIONS.forEach { preset ->
            val active = sections.any { it.packageName == preset.packageName }
            SectionRow(preset, active) {
                val next = if (active) sections.filterNot { it.packageName == preset.packageName }
                else sections + preset
                store.saveSections(next)
                sections = next
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "These hints match the current app layouts. If a vendor redesigns its feed, the block will stop working until the hint is updated.",
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun SectionRow(section: BlockedSection, active: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(if (active) Focus.SurfacePressed else Focus.Surface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column(Modifier.weight(1f)) {
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
            fontSize = 12.sp
        )
    }
}
