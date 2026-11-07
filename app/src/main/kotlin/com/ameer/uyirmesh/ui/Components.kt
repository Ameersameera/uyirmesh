@file:OptIn(ExperimentalMaterial3Api::class)

package com.ameer.uyirmesh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.PersonalInjury
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PregnantWoman
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every icon the app uses, in one place. */
object Ic {
    val Back = Icons.AutoMirrored.Filled.ArrowBack
    val Sos = Icons.Filled.Warning
    val Info = Icons.Filled.Info
    val Settings = Icons.Filled.Settings
    val Person = Icons.Filled.Person
    val Group = Icons.Filled.Group
    val Chat = Icons.Filled.Chat
    val Send = Icons.Filled.Send
    val Call = Icons.Filled.Call
    val Sms = Icons.Filled.Sms
    val Share = Icons.Filled.Share
    val Search = Icons.Filled.Search
    val Add = Icons.Filled.Add
    val Delete = Icons.Filled.Delete
    val Check = Icons.Filled.Check
    val CheckCircle = Icons.Filled.CheckCircle
    val Close = Icons.Filled.Close
    val Cancel = Icons.Filled.Cancel
    val Refresh = Icons.Filled.Refresh
    val Play = Icons.Filled.PlayArrow
    val Stop = Icons.Filled.Stop
    val Mic = Icons.Filled.Mic
    val Camera = Icons.Filled.CameraAlt
    val Torch = Icons.Filled.FlashlightOn
    val Siren = Icons.Filled.VolumeUp
    val Vibrate = Icons.Filled.Vibration
    val Alert = Icons.Filled.Campaign
    val Location = Icons.Filled.LocationOn
    val MyLocation = Icons.Filled.MyLocation
    val Map = Icons.Filled.Map
    val Radar = Icons.Filled.Radar
    val Navigate = Icons.Filled.Navigation
    val Run = Icons.Filled.DirectionsRun
    val Home = Icons.Filled.Home
    val Shelter = Icons.Filled.Hotel
    val Hospital = Icons.Filled.LocalHospital
    val Medical = Icons.Filled.MedicalServices
    val Healing = Icons.Filled.Healing
    val Heart = Icons.Filled.Favorite
    val Bluetooth = Icons.Filled.Bluetooth
    val Wifi = Icons.Filled.Wifi
    val Battery = Icons.Filled.BatteryAlert
    val Lock = Icons.Filled.Lock
    val Security = Icons.Filled.Security
    val Qr = Icons.Filled.QrCode
    val QrScan = Icons.Filled.QrCodeScanner
    val Report = Icons.Filled.Report
    val History = Icons.Filled.History
    val Dashboard = Icons.Filled.Dashboard
    val Computer = Icons.Filled.Computer
    val Doc = Icons.Filled.Description
    val Pdf = Icons.Filled.PictureAsPdf
    val Chart = Icons.Filled.BarChart
    val Timeline = Icons.Filled.Timeline
    val Network = Icons.Filled.DeviceHub
    val NetworkCheck = Icons.Filled.NetworkCheck
    val Bug = Icons.Filled.BugReport
    val Language = Icons.Filled.Language
    val Text = Icons.Filled.TextFields
    val Theme = Icons.Filled.Brightness4
    val Water = Icons.Filled.LocalDrink
    val Food = Icons.Filled.Restaurant
    val Medicine = Icons.Filled.LocalPharmacy
    val Child = Icons.Filled.ChildCare
    val Accessible = Icons.Filled.Accessible
    val Pregnant = Icons.Filled.PregnantWoman
    val Boat = Icons.Filled.DirectionsBoat
    val Fire = Icons.Filled.Whatshot
    val PersonAdd = Icons.Filled.PersonAdd
    val Task = Icons.Filled.Assignment
    val Error = Icons.Filled.Error
    val Logout = Icons.Filled.ExitToApp
    val Arrow = Icons.Filled.KeyboardArrowRight
    val Time = Icons.Filled.AccessTime
    val Pets = Icons.Filled.Pets
    val Timer = Icons.Filled.Timer
    val Explore = Icons.Filled.Explore
    val Mute = Icons.Filled.VolumeOff
    val Touch = Icons.Filled.TouchApp
    val Speak = Icons.Filled.RecordVoiceOver
    val Checklist = Icons.Filled.Checklist
    val Game = Icons.Filled.SportsEsports
    val Blood = Icons.Filled.Bloodtype
    val Poll = Icons.Filled.Poll
    val Sensor = Icons.Filled.Sensors
    val Cloud = Icons.Filled.CloudUpload
    val Block = Icons.Filled.Block
    val Swap = Icons.Filled.SwapHoriz
    val Family = Icons.Filled.FamilyRestroom
    val Hazard = Icons.Filled.ReportProblem
    val Injury = Icons.Filled.PersonalInjury
    val Tower = Icons.Filled.CellTower
    val HowToReg = Icons.Filled.HowToReg
    val Backpack = Icons.Filled.Backpack
    val Speed = Icons.Filled.Speed
    val Walkie = Icons.Filled.SettingsVoice
}

@Composable
fun ScreenBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Ic.Back, contentDescription = tr("back")) }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier.fillMaxWidth().padding(vertical = 4.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = m, colors = CardDefaults.cardColors(containerColor = color)) {
            Column(Modifier.padding(14.dp), content = content)
        }
    } else {
        Card(modifier = m, colors = CardDefaults.cardColors(containerColor = color)) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

@Composable
fun MenuTile(icon: ImageVector, title: String, subtitle: String = "", tint: Color = MaterialTheme.colorScheme.primary, badge: Int = 0, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (badge > 0) {
                Badge(badge.toString(), Palette.Red)
                Spacer(Modifier.width(6.dp))
            }
            Icon(Ic.Arrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.5f)), modifier = modifier) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(48.dp)) {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TonalButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp)) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String = "", checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int = 0, max: Int = 99, icon: ImageVector? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { if (value > min) onChange(value - 1) }, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−", fontSize = 22.sp)
        }
        Text(value.toString(), Modifier.width(44.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { if (value < max) onChange(value + 1) }, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+", fontSize = 22.sp)
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, number: Boolean = false, password: Boolean = false, minLines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                password && number -> KeyboardType.NumberPassword
                password -> KeyboardType.Password
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun StatusDot(ok: Boolean, warn: Boolean = false) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) Palette.Green else if (warn) Palette.Amber else Palette.Red))
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

fun timeText(at: Long): String {
    if (at <= 0) return "-"
    val now = System.currentTimeMillis()
    val pattern = if (now - at < 20 * 3600_000L) "HH:mm" else "dd MMM HH:mm"
    return SimpleDateFormat(pattern, Locale.US).format(Date(at))
}

fun agoText(at: Long): String {
    if (at <= 0) return "-"
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 0 -> timeText(at)
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        else -> "${s / 86_400} d"
    }
}
