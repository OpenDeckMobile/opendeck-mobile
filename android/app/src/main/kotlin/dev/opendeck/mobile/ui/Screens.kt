package dev.opendeck.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.compose.foundation.layout.widthIn
import dev.opendeck.core.WakeOnLan
import dev.opendeck.mobile.AppViewModel
import dev.opendeck.mobile.Screen
import dev.opendeck.mobile.StoredHost
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

private const val NOTICE_MS = 5000L

@Composable
fun App(vm: AppViewModel) {
    val screen = vm.screen
    BackHandler(enabled = screen != Screen.Hosts) { vm.back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Keypad is fullscreen: insets (e.g. the camera cutout in landscape) would push the grid off-centre.
        Box(Modifier.fillMaxSize().then(if (screen == Screen.Keypad) Modifier else Modifier.safeDrawingPadding())) {
            when (screen) {
                Screen.Hosts -> HostsScreen(vm)
                is Screen.Busy -> {
                    val wakeHost = vm.stored.host(screen.wakeHostID)
                    var setUp by remember { mutableStateOf(false) }
                    BusyScreen(
                        screen.text,
                        onCancel = vm::disconnect,
                        action = wakeHost?.let { h -> "Wake ${h.name}" to { if (h.wakeMac != null) vm.wake() else setUp = true } },
                    )
                    if (setUp && wakeHost != null) WakeDialog(vm, wakeHost, thenWake = true, onDismiss = { setUp = false })
                }
                is Screen.Offline -> OfflineScreen(vm, screen)
                is Screen.Waking -> WakingScreen(vm, screen)
                is Screen.Pairing -> PairingScreen(vm, screen.waiting)
                Screen.Devices -> DevicesScreen(vm)
                Screen.Keypad -> KeypadScreen(vm)
            }
            val notice = vm.notice
            LaunchedEffect(notice) {
                if (notice != null) {
                    delay(NOTICE_MS)
                    vm.notice = null
                }
            }
            if (notice != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notice,
                            modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        TextButton(onClick = { vm.notice = null }) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostsScreen(vm: AppViewModel) {
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    var showAdd by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var wakeFor by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isHome = remember { context.isDefaultHome() }
    val saved = vm.stored.hosts
    val online = vm.discovered.map { it.workstationID }.toSet()
    val extra = vm.discovered.filter { d -> saved.none { it.workstationID == d.workstationID } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("OpenDeck", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.scanning) "Searching the local network…" else "Not searching",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { vm.startDiscovery() }, enabled = !vm.scanning) { Text("Search again") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (saved.isEmpty() && extra.isEmpty()) {
                item {
                    Text(
                        "No computers yet. Make sure the Stream Deck app is running on a computer in the same network, " +
                            "or add one by address.",
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(saved, key = { "saved:" + it.workstationID }) { h ->
                val status = buildString {
                    append(if (h.workstationID in online) "Online" else "Saved")
                    if (h.isAuthenticated) append(" · paired")
                    append(" · ${h.devices.size} device(s)")
                    if (h.wakeMac != null) append(if (h.autoWake) " · wakes automatically" else " · Wake-on-LAN")
                }
                ListRow(h.name, status, onLongClick = { wakeFor = h.workstationID }) { vm.connectSaved(h) }
            }
            items(extra, key = { "found:" + it.workstationID }) { d ->
                ListRow(d.name, "Discovered · ${d.endpoints.firstOrNull() ?: ""}") { vm.connectDiscovered(d) }
            }
        }
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add host") }
        if (isHome) OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Apps") }
    }
    if (showApps) AppsDialog(onDismiss = { showApps = false })
    vm.stored.host(wakeFor)?.let { h -> WakeDialog(vm, h, thenWake = false, onDismiss = { wakeFor = null }) }
    if (showAdd) {
        TextInputDialog(
            title = "Add host",
            label = "host[:port] or QR link",
            help = "Enter the computer's address (default port 28198), or paste the link encoded in the pairing QR code.",
            confirm = "Connect",
            onDismiss = { showAdd = false },
            onSubmit = { text -> vm.addHost(text).also { if (it == null) showAdd = false } },
        )
    }
}

internal fun Context.isDefaultHome(): Boolean =
    packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == packageName

/** App drawer for when OpenDeck is the home app, so the phone is never trapped in it. */
@Composable
internal fun AppsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val apps = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo }
            .sortedBy { it.first.lowercase() }
    }
    fun launch(intent: Intent) {
        onDismiss()
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Apps") },
        text = {
            LazyVerticalGrid(GridCells.Fixed(4)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListRow("Home app settings", "Switch back to another launcher") { launch(Intent(Settings.ACTION_HOME_SETTINGS)) }
                }
                items(apps, key = { it.second.packageName + "/" + it.second.name }) { (label, info) ->
                    // Loaded per visible cell, so opening the drawer doesn't decode every icon up front.
                    val icon = remember(info) { info.loadIcon(pm).toBitmap(144, 144).asImageBitmap() }
                    Column(
                        Modifier
                            .clickable {
                                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.packageName, info.name))
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(48.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListRow(title: String, subtitle: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

/** onSubmit returns an error message to show, or null to accept. [extra] is shown under the field. */
@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    help: String,
    confirm: String,
    onDismiss: () -> Unit,
    initial: String = "",
    extra: @Composable (text: String) -> Unit = {},
    onSubmit: (String) -> String?,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(help, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(label) },
                    singleLine = true,
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                extra(text)
            }
        },
        confirmButton = { TextButton(onClick = { error = onSubmit(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [action]: an optional extra button (label to click handler) above Cancel. */
@Composable
private fun BusyScreen(text: String, onCancel: () -> Unit, detail: String? = null, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, textAlign = TextAlign.Center)
        detail?.let {
            Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        action?.let { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** MAC address and auto-wake for a saved computer. [thenWake]: start waking it once saved. */
@Composable
private fun WakeDialog(vm: AppViewModel, host: StoredHost, thenWake: Boolean, onDismiss: () -> Unit) {
    var auto by remember { mutableStateOf(host.autoWake) }
    TextInputDialog(
        title = "Wake-on-LAN",
        label = "MAC address",
        help = "Lets this phone turn ${host.name} on when it is off or asleep. OpenDeck learns the MAC address of " +
            "its network adapter when it connects over Wi-Fi; if it couldn't, enter it here. On Windows, run getmac /v, " +
            "or open Settings > Network & internet > Ethernet and look for Physical address (MAC). Wake-on-LAN must also " +
            "be on in the BIOS and the adapter's settings.",
        confirm = if (thenWake) "Save and wake" else "Save",
        onDismiss = onDismiss,
        initial = host.wakeMac ?: "",
        extra = { text ->
            val mac = WakeOnLan.normalizeMac(text)
            val note = when {
                mac != null && mac == host.wakeMac && host.wakeMacLearned -> "Learned automatically"
                mac != null && mac != text.trim() -> "Saved as $mac"
                else -> null
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = text.isNotBlank()) { auto = !auto },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = auto && text.isNotBlank(), onCheckedChange = { auto = it }, enabled = text.isNotBlank())
                Text("Wake automatically when offline")
            }
        },
        onSubmit = { text ->
            vm.setWake(host.workstationID, text, auto).also { error ->
                if (error == null) {
                    onDismiss()
                    if (thenWake && text.isNotBlank()) vm.wake()
                }
            }
        },
    )
}

@Composable
private fun OfflineScreen(vm: AppViewModel, screen: Screen.Offline) {
    val host = vm.stored.host(screen.hostID) ?: return
    var setUp by remember { mutableStateOf(false) }
    val title = when {
        screen.refused -> "Stream Deck isn't running on ${host.name}"
        screen.afterWake -> "${host.name} is still offline"
        else -> "${host.name} is offline"
    }
    val body = when {
        screen.refused -> "${host.name} is on, but nothing accepts connections on the Stream Deck port. " +
            "Start the Stream Deck app on the computer, then try again."
        screen.afterWake -> "No answer after 3 minutes. Check that the MAC address is right and that Wake-on-LAN is on " +
            "in the BIOS and the network adapter. Waking from shut down can also need Windows Fast startup turned off."
        host.wakeMac != null -> "No answer from ${host.name}. It may be off or asleep. Wake it with a Wake-on-LAN packet " +
            "to ${host.wakeMac}?"
        else -> "No answer from ${host.name}. It may be off or asleep. Wake it with Wake-on-LAN? This phone hasn't " +
            "learned its MAC address yet, so you'll be asked for it."
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(body, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp))
        Spacer(Modifier.height(24.dp))
        if (!screen.refused) {
            Button(onClick = { if (host.wakeMac != null) vm.wake() else setUp = true }) { Text("Wake and connect") }
        }
        OutlinedButton(onClick = { vm.tryAgain() }) { Text("Try again") }
        TextButton(onClick = { vm.disconnect() }) { Text("Back") }
    }
    if (setUp) WakeDialog(vm, host, thenWake = true, onDismiss = { setUp = false })
}

@Composable
private fun WakingScreen(vm: AppViewModel, screen: Screen.Waking) {
    val host = vm.stored.host(screen.hostID)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(screen.since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val secs = ((now - screen.since) / 1000).coerceAtLeast(0)
    BusyScreen(
        "Waking ${host?.name ?: "computer"}…",
        onCancel = vm::disconnect,
        detail = "%d:%02d · Wake-on-LAN packet sent to %s. Connecting as soon as it answers.".format(secs / 60, secs % 60, host?.wakeMac ?: "?"),
    )
}

@Composable
private fun PairingScreen(vm: AppViewModel, waiting: Boolean) {
    val hostName = vm.currentHost?.name ?: "computer"
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pair with $hostName", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                vm.pairingCode,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(24.dp))
        if (waiting) {
            Text("Confirm code ${vm.pairingCode} on your computer.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.cancelPairing() }) { Text("Cancel") }
        } else {
            Text(
                "This computer has not approved this phone yet. Tap Pair, then accept the request in the Stream Deck app " +
                    "after checking that it shows the same code.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.pair() }) { Text("Pair") }
            TextButton(onClick = { vm.disconnect() }) { Text("Cancel") }
        }
    }
}

@Composable
private fun DevicesScreen(vm: AppViewModel) {
    val host = vm.currentHost
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(host?.name ?: "", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
        }
        Text("Virtual devices", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(host?.devices ?: emptyList(), key = { it.id }) { d ->
                ListRow(d.name.ifBlank { d.id }, "${d.cols} × ${d.rows} keys, ${d.iconSize} px icons") { vm.openDevice(d.id) }
            }
        }
        Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) { Text("New device") }
    }
    if (showNew) {
        TextInputDialog(
            title = "New device",
            label = "Name",
            help = "Creates another virtual Stream Deck on this computer.",
            confirm = "Create",
            onDismiss = { showNew = false },
            onSubmit = { name ->
                if (name.isBlank()) {
                    "Enter a name."
                } else {
                    vm.createDevice(name)
                    showNew = false
                    null
                }
            },
        )
    }
}
