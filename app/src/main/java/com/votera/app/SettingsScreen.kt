@file:OptIn(ExperimentalMaterial3Api::class)

package com.votera.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------- Settings ----------------
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(vm: AppVM) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(ServerConfig.baseUrl) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var howScoring by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Appearance") {
            Text("Theme", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
                    FilterChip(selected = Prefs.theme == id, onClick = { Prefs.setTheme(id) }, label = { Text(label) })
                }
            }
            Text("Text size", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.9f to "Small", 1.0f to "Default", 1.15f to "Large").forEach { (v, label) ->
                    FilterChip(selected = Prefs.textScale == v, onClick = { Prefs.setTextScale(v) }, label = { Text(label) })
                }
            }
        }

        Section("Updates") {
            SwitchRow("Live updates", "Votes, status and announcements appear on every device as they happen. If off, the app refreshes every 15 seconds.",
                Prefs.live) { Prefs.setLive(it); vm.startLive() }
            OutlinedButton(onClick = { vm.refresh() }, enabled = !vm.refreshing) { Text("Refresh now") }
        }

        if (vm.user?.role == "student") Section("Login") {
            SwitchRow("Remember my student number", "Fills in your student number on this phone. Your password is never saved.",
                Prefs.rememberSn) { Prefs.setRememberSn(it) }
        }

        Section("Help") {
            OutlinedButton(onClick = { vm.showTutorial = true }, modifier = Modifier.fillMaxWidth()) { Text("Replay the tutorial") }
            TextButton(onClick = { howScoring = !howScoring }) { Text(if (howScoring) "Hide how scoring works" else "How scoring works") }
            if (howScoring) Text(
                "Credibility Score = originality x 35% + verified achievements x 30% + leadership experience x 20% + community involvement x 15%. " +
                    "Originality is 100 minus the highest similarity between a manifesto and the other candidates' manifestos (TF-IDF with cosine similarity). " +
                    "A manifesto at 70% similarity or higher is flagged for administrator review. A flag does not disqualify anyone, because word overlap can miss reworded copying and common campaign phrases can raise similarity.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Server connection") {
            Text("Only change this if your administrator gives you a new address.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val n = ServerConfig.normalize(server)
                    if (n == null) testResult = "That address is not valid"
                    else {
                        ServerConfig.save(n); server = n; testing = true; testResult = "Testing..."
                        scope.launch {
                            testResult = try {
                                val h = Net.api.health()
                                "Connected. Election status: ${h.election ?: "not set up"}"
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (t: Throwable) { errorMessage(t) }
                            testing = false
                        }
                    }
                }, enabled = !testing) { Text("Save and test connection") }
            }
            testResult?.let { Text(it, fontSize = 12.sp) }
        }

        Section("About VotEra") {
            Text("VotEra is a research prototype of a digital student election platform for PUP Quezon City, built for a descriptive-developmental study. " +
                "It runs one simulated Supreme Student Council election with fictional candidates and pre-loaded voter accounts. One-time passwords are simulated on screen.",
                fontSize = 13.sp)
            Text("Participation is voluntary. Personal and academic information is handled with strict confidentiality in compliance with the Data Privacy Act of 2012 (R.A. 10173), and results are reported in aggregate.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Version 1.0 prototype", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.weight(1f)) { Text("Reset settings") }
            Button(onClick = { vm.logout() }, modifier = Modifier.weight(1f)) { Text("Log out") }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset settings?") },
        text = { Text("Theme, text size, live updates and the server address go back to their defaults.") },
        confirmButton = { TextButton(onClick = { Prefs.resetAll(); server = ServerConfig.baseUrl; vm.startLive(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
}

// ---------------- First-login tutorial ----------------
private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val STUDENT_SLIDES = listOf(
    Slide(VIcons.Shield, "Welcome to VotEra", "This is a simulated Supreme Student Council election for a research study at PUP Quezon City. The candidates and data are fictional."),
    Slide(VIcons.People, "Read the candidates", "Open the Candidates tab and tap a candidate to read the platform. Use the Compare tab to place two or more candidates side by side."),
    Slide(VIcons.Bars, "Understand the credibility score", "Each candidate has a score from 0 to 100: originality 35%, verified achievements 30%, leadership 20%, community 15%. A flag means a manifesto is very similar to another and is under review. It does not disqualify anyone."),
    Slide(VIcons.Check, "Vote once", "Open a candidate, tap Vote, then confirm. Each student can vote once and a vote cannot be changed. You get a receipt with a transaction ID."),
    Slide(VIcons.Bell, "Follow the results", "The Results tab shows turnout and totals, updating live on every device. Other students' individual votes are never shown. Announcements are in the Updates tab.")
)

private val ADMIN_SLIDES = listOf(
    Slide(VIcons.Shield, "Welcome, administrator", "You run the simulated election. The Admin tab has four parts: Overview, Candidates, Vote audit and Voters."),
    Slide(VIcons.Bell, "Overview", "Open or close the election, watch turnout per program, post announcements, and review manifestos flagged at 70% similarity or higher."),
    Slide(VIcons.People, "Candidates", "Add a candidate if one is missing, edit a manifesto, and enter the verified achievements, leadership and community scores from 0 to 100. Similarity and credibility are recalculated for everyone each time."),
    Slide(VIcons.Bars, "Vote audit and voters", "The Vote audit shows who voted for whom. It is visible to administrators only and is confidential under R.A. 10173. Students only ever see totals. Voters shows who has and has not voted."),
    Slide(VIcons.Refresh, "Demo and reset", "Demo mode adds sample votes so the live charts can be shown. Reset election clears all votes and returns the status to Upcoming, ready for the next run.")
)

@Composable
fun TutorialScreen(isAdmin: Boolean, onFinish: () -> Unit) {
    val slides = if (isAdmin) ADMIN_SLIDES else STUDENT_SLIDES
    var i by remember { mutableStateOf(0) }
    val s = slides[i]
    val last = i == slides.lastIndex
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1} of ${slides.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!last) TextButton(onClick = onFinish) { Text("Skip") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(s.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(s.body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            slides.indices.forEach { d ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (d == i) 10.dp else 7.dp).clip(CircleShape)
                    .background(if (d == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (i > 0) OutlinedButton(onClick = { i-- }, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { if (last) onFinish() else i++ }, modifier = Modifier.weight(1f)) { Text(if (last) "Get started" else "Next") }
        }
    }
}
