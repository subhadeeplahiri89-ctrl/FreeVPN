package com.example.freevpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireguard.config.Config
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.StringReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = VpnController.getInstance(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VpnDashboardScreen(controller)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnDashboardScreen(controller: VpnController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by controller.status.collectAsState()
    val stats by controller.trafficStats.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val activeProfileId by controller.activeProfileId.collectAsState()
    val proxyEnabled by controller.proxyEnabled.collectAsState()
    val hotspotIp by controller.hotspotIp.collectAsState()
    val errorMessage by controller.errorMessage.collectAsState()

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    var showServerSheet by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            controller.connectActiveProfile()
        }
    }

    val statusColor = when (status) {
        VpnStatus.CONNECTED -> Color(0xFF22C55E)
        VpnStatus.CONNECTING -> Color(0xFFF59E0B)
        VpnStatus.DISCONNECTED -> Color(0xFF64748B)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FreeVPN",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                FilledTonalButton(
                    onClick = {
                        editingProfile = null
                        showEditorDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Server", fontSize = 13.sp)
                }
            }

            // Active Server Selector Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerSheet = true },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = activeProfile?.name ?: "No Server Selected",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = activeProfile?.extractEndpoint() ?: "Tap to add a server",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "Switch Server",
                        tint = Color(0xFF94A3B8)
                    )
                }
            }

            // Center Power Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Button(
                    onClick = {
                        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
                            scope.launch { controller.disconnect() }
                        } else {
                            val consent = controller.preparePermission()
                            if (consent != null) {
                                permissionLauncher.launch(consent)
                            } else {
                                controller.connectActiveProfile()
                            }
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = statusColor),
                    modifier = Modifier.size(148.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (status == VpnStatus.CONNECTED) "DISCONNECT" else "CONNECT",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = status.name,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // Live Download / Upload Counters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("DOWNLOAD", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${VpnController.formatBytes(stats.rxBytesPerSec)}/s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Total: ${VpnController.formatBytes(stats.rxBytes)}",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("UPLOAD", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${VpnController.formatBytes(stats.txBytesPerSec)}/s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Total: ${VpnController.formatBytes(stats.txBytes)}",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Share VPN to Second Phone Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WifiTethering,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Share VPN to Other Phone",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Local HTTP/HTTPS Hotspot Proxy",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = proxyEnabled,
                            onCheckedChange = { controller.toggleHotspotProxy(it) }
                        )
                    }

                    if (proxyEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = Color(0xFF334155)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Proxy Host: $hotspotIp   |   Port: ${VpnController.PROXY_PORT}",
                                    color = Color(0xFF22C55E),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Turn on Hotspot, then enter Host & Port in Phone 2's Wi-Fi Proxy settings.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            IconButton(onClick = { controller.refreshHotspotIp() }) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = "Refresh IP",
                                    tint = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }

            // Always-on VPN & Kill Switch Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Always-on & Kill Switch",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Block non-VPN traffic in Android Settings",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    ) {
                        Text("Configure", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // Server List Bottom Sheet
    if (showServerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showServerSheet = false },
            containerColor = Color(0xFF1E293B)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Saved Servers (${profiles.size})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == activeProfile?.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.selectProfile(profile.id)
                                    showServerSheet = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF0F172A) else Color(0xFF334155)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        color = if (isSelected) Color(0xFF22C55E) else Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = profile.extractEndpoint(),
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp
                                    )
                                }
                                Row {
                                    IconButton(onClick = {
                                        editingProfile = profile
                                        showServerSheet = false
                                        showEditorDialog = true
                                    }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = Color(0xFF94A3B8))
                                    }
                                    if (profiles.size > 1) {
                                        IconButton(onClick = {
                                            controller.deleteProfile(profile.id)
                                        }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showEditorDialog) {
        ProfileEditorDialog(
            initialProfile = editingProfile,
            onDismiss = { showEditorDialog = false },
            onSave = { savedProfile ->
                controller.upsertProfile(savedProfile)
                controller.selectProfile(savedProfile.id)
                showEditorDialog = false
            }
        )
    }
}

@Composable
fun ProfileEditorDialog(
    initialProfile: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var config by remember { mutableStateOf(initialProfile?.config ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Text(
                text = if (initialProfile == null) "Add Server Profile" else "Edit Server Profile",
                color = Color.White
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Label (e.g., Mumbai Server)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    label = { Text("WireGuard .conf contents") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        validationError = "Enter a server name."
                        return@Button
                    }
                    try {
                        Config.parse(BufferedReader(StringReader(config.trim())))
                        onSave(
                            ServerProfile(
                                id = initialProfile?.id ?: java.util.UUID.randomUUID().toString(),
                                name = trimmedName,
                                config = config.trim()
                            )
                        )
                    } catch (e: Exception) {
                        validationError = "Invalid WireGuard config: ${e.message}"
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

