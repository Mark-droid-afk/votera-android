@file:OptIn(ExperimentalMaterial3Api::class)

package com.votera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

// ---------------- State and calls to the backend ----------------
class AppVM : ViewModel() {
    var stage by mutableStateOf("login")  // login -> otp -> main
    var user by mutableStateOf<UserDto?>(null)
    var devOtp by mutableStateOf("")
    private var otpToken = ""
    var election by mutableStateOf<ElectionDto?>(null)
    var candidates by mutableStateOf<List<CandidateDto>>(emptyList())
    var announcements by mutableStateOf<List<AnnouncementDto>>(emptyList())
    var results by mutableStateOf<ResultsRes?>(null)
    var voteStatus by mutableStateOf<VoteStatusRes?>(null)
    var flagged by mutableStateOf<List<CandidateDto>>(emptyList())
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    private fun launchTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) {
                if (t is HttpException && t.code() == 401 && stage == "main") {
                    message = "Session expired. Please log in again."
                    logout()
                } else message = errorMessage(t)
            } finally { busy = false }
        }
    }

    private suspend fun loadAll() {
        val api = Net.api
        election = api.election()
        candidates = api.candidates()
        announcements = api.announcements()
        results = api.results()
        if (user?.role == "student") voteStatus = api.voteStatus()
        if (user?.role == "admin") flagged = api.flagged().flagged
    }

    fun login(studentNumber: String, password: String) = launchTask {
        val r = Net.api.login(LoginReq(studentNumber.trim(), password))
        otpToken = r.otpToken
        devOtp = r.devOtp  // OTP delivery is simulated in the prototype, so the code is shown on screen
        stage = "otp"
    }

    fun verifyOtp(code: String) = launchTask {
        val r = Net.api.verifyOtp(OtpReq(otpToken, code.trim()))
        Session.token = r.accessToken
        user = r.user
        loadAll()
        stage = "main"
    }

    fun backToLogin() { stage = "login"; otpToken = ""; devOtp = "" }
    fun refresh() = launchTask { loadAll() }

    /** Quiet refresh used by the Results tab so charts update without a loading bar. */
    suspend fun silentRefreshResults() {
        try { val r = Net.api.results(); results = r; election = r.election }
        catch (e: CancellationException) { throw e }
        catch (_: Throwable) { }
    }

    fun vote(candidateId: Int) = launchTask {
        val r = Net.api.vote(VoteReq(candidateId))
        voteStatus = VoteStatusRes(true, r.transactionId)
        results = Net.api.results()
        message = "Vote recorded"
    }

    fun logout() {
        Session.token = null
        user = null; election = null; candidates = emptyList(); announcements = emptyList()
        results = null; voteStatus = null; flagged = emptyList(); otpToken = ""; devOtp = ""
        stage = "login"
    }

    // Admin
    fun setStatus(status: String) = launchTask { election = Net.api.setStatus(StatusReq(status)) }
    fun saveScores(id: Int, a: Double, l: Double, c: Double) = launchTask {
        Net.api.setScores(id, ScoresReq(a, l, c))
        candidates = Net.api.candidates()
        flagged = Net.api.flagged().flagged
        message = "Scores saved"
    }
    fun postAnnouncement(title: String, body: String) = launchTask {
        Net.api.postAnnouncement(AnnouncementReq(title, body))
        announcements = Net.api.announcements()
        message = "Announcement posted"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoteraApp() }
    }
}

private val PALETTE = listOf(
    Color(0xFF800000), Color(0xFFB8860B), Color(0xFF1F6F8B), Color(0xFF4C7A34),
    Color(0xFF6A4C93), Color(0xFFC1440E), Color(0xFF555555)
)

private fun fmt(d: Double?) = if (d == null) "-" else "%.1f".format(d)

// ---------------- App shell ----------------
@Composable
fun VoteraApp(vm: AppVM = viewModel()) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF800000), onPrimary = Color.White,
        primaryContainer = Color(0xFFF3DADA), onPrimaryContainer = Color(0xFF3B0000),
        secondary = Color(0xFFB8860B), background = Color(0xFFFAF8F6), surface = Color(0xFFFAF8F6)
    )) {
        val snack = remember { SnackbarHostState() }
        var tab by remember { mutableStateOf("candidates") }
        var detailId by remember { mutableStateOf<Int?>(null) }
        var confirm by remember { mutableStateOf<CandidateDto?>(null) }
        val isAdmin = vm.user?.role == "admin"
        val tabs = buildList {
            add("candidates" to "Candidates"); add("compare" to "Compare")
            add("updates" to "Updates"); add("results" to "Results")
            if (isAdmin) add("admin" to "Admin")
        }

        LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
        BackHandler(enabled = vm.stage == "main" && detailId != null) { detailId = null }

        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                if (vm.stage == "main") TopAppBar(
                    title = { Text(vm.election?.title ?: "Votera", maxLines = 1, fontSize = 16.sp) },
                    actions = {
                        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                        TextButton(onClick = { tab = "candidates"; detailId = null; vm.logout() }) { Text("Log out") }
                    }
                )
            },
            bottomBar = {
                if (vm.stage == "main") NavigationBar {
                    tabs.forEach { (id, label) ->
                        NavigationBarItem(selected = tab == id, onClick = { tab = id; detailId = null },
                            icon = {}, label = { Text(label, fontSize = 11.sp) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (vm.stage) {
                    "login" -> LoginScreen(vm)
                    "otp" -> OtpScreen(vm)
                    else -> {
                        val detail = vm.candidates.firstOrNull { it.id == detailId }
                        if (detail != null) CandidateDetail(detail, vm, onBack = { detailId = null }, onVote = { confirm = detail })
                        else when (tab) {
                            "candidates" -> CandidatesTab(vm) { detailId = it }
                            "compare" -> CompareTab(vm)
                            "updates" -> UpdatesTab(vm)
                            "results" -> ResultsTab(vm)
                            "admin" -> AdminTab(vm)
                        }
                    }
                }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        confirm?.let { c ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Confirm your vote") },
                text = { Text("Vote for ${c.fullName}? You cannot change your vote after it is recorded.") },
                confirmButton = { TextButton(onClick = { vm.vote(c.id); confirm = null }) { Text("Confirm vote") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
            )
        }
    }
}

// ---------------- Login (password, then OTP) ----------------
@Composable
fun LoginScreen(vm: AppVM) {
    var sn by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Votera", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text("Simulated Supreme Student Council election, PUP Quezon City", Modifier.padding(bottom = 24.dp))
        OutlinedTextField(sn, { sn = it }, label = { Text("Student number") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.login(sn, pw) }, enabled = sn.isNotBlank() && pw.isNotEmpty() && !vm.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
fun OtpScreen(vm: AppVM) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your one-time password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Simulated OTP delivery (prototype)", fontSize = 12.sp)
                Text(vm.devOtp, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.verifyOtp(code) }, enabled = code.length == 6 && !vm.busy,
                modifier = Modifier.weight(1f)) { Text("Verify and log in") }
            TextButton(onClick = { vm.backToLogin() }) { Text("Back") }
        }
    }
}

// ---------------- Candidates and profile ----------------
@Composable
fun CandidatesTab(vm: AppVM, onOpen: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val e = vm.election
            val vs = vm.voteStatus
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Election status: ${e?.status ?: "-"}", fontWeight = FontWeight.Bold)
                    if (vs?.hasVoted == true) Text("You have voted. Transaction ID: ${vs.transactionId?.take(16)}...", fontSize = 12.sp)
                    else if (vm.user?.role == "student") Text("Open a candidate to read the profile and vote.", fontSize = 12.sp)
                }
            }
        }
        items(vm.candidates) { c ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(c.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${c.position} | ${c.program}", fontSize = 13.sp)
                        if (c.flagged) Text("Manifesto flagged for review", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(c.credibilityScore), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("credibility", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Block(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(body.ifBlank { "-" })
    }
}

@Composable
fun ScoreRow(label: String, value: Double?, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(fmt(value), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun CandidateDetail(c: CandidateDto, vm: AppVM, onBack: () -> Unit, onVote: () -> Unit) {
    val isStudent = vm.user?.role == "student"
    val open = vm.election?.status == "open"
    val voted = vm.voteStatus?.hasVoted == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back to candidates") }
        Text(c.fullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${c.position} | ${c.program}")
        if (c.flagged) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("This manifesto is very similar to another candidate's (${fmt((c.highestSimilarity ?: 0.0) * 100)}% similarity). " +
                "It is flagged for administrator review. A flag does not disqualify the candidate.",
                Modifier.padding(12.dp), fontSize = 13.sp)
        }
        Block("Platform / manifesto", c.manifesto)
        Block("Verified achievements", c.achievements)
        Block("Leadership experience", c.leadership)
        Block("Community involvement", c.community)
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Credibility score breakdown", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                ScoreRow("Originality (35%)", c.originalityScore)
                ScoreRow("Verified achievements (30%)", c.achievementsScore)
                ScoreRow("Leadership experience (20%)", c.leadershipScore)
                ScoreRow("Community involvement (15%)", c.communityScore)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ScoreRow("Credibility Score", c.credibilityScore, bold = true)
                Text("Originality = 100 minus the highest similarity to another candidate's manifesto.", fontSize = 11.sp)
            }
        }
        if (isStudent) Button(onClick = onVote, enabled = open && !voted, modifier = Modifier.fillMaxWidth()) {
            Text(when { voted -> "You have already voted"; !open -> "Voting is not open"; else -> "Vote for ${c.fullName}" })
        }
    }
}

// ---------------- Side-by-side comparison ----------------
@Composable
fun CompareTab(vm: AppVM) {
    var picked by remember { mutableStateOf(listOf<Int>()) }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text("Select two or more candidates to compare side by side.", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.candidates) { c ->
                FilterChip(selected = c.id in picked,
                    onClick = { picked = if (c.id in picked) picked - c.id else picked + c.id },
                    label = { Text(c.fullName) })
            }
        }
        val chosen = vm.candidates.filter { it.id in picked }
        if (chosen.size >= 2) {
            Row(Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chosen.forEach { c ->
                    Card(Modifier.width(260.dp).fillMaxHeight()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(c.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(c.program, fontSize = 13.sp)
                            Text("Credibility ${fmt(c.credibilityScore)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Originality ${fmt(c.originalityScore)}", fontSize = 13.sp)
                            if (c.flagged) Text("Flagged for review", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            Block("Platform", c.manifesto)
                            Block("Achievements", c.achievements)
                            Block("Leadership", c.leadership)
                            Block("Community", c.community)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Announcements ----------------
@Composable
fun UpdatesTab(vm: AppVM) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (vm.announcements.isEmpty()) item { Text("No announcements yet.") }
        items(vm.announcements) { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(a.createdAt.take(10), fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(a.body)
                }
            }
        }
    }
}

// ---------------- Results: turnout, bar chart, pie chart ----------------
@Composable
fun PieChart(rows: List<ResultRowDto>) {
    val total = rows.sumOf { it.votes }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            if (total == 0) drawCircle(Color.LightGray)
            else {
                var start = -90f
                rows.forEach { r ->
                    val sweep = 360f * r.votes / total
                    drawArc(color = PALETTE[r.candidateId % PALETTE.size], startAngle = start, sweepAngle = sweep, useCenter = true)
                    start += sweep
                }
            }
        }
    }
}

@Composable
fun ResultsTab(vm: AppVM) {
    LaunchedEffect(Unit) { while (true) { delay(10_000); vm.silentRefreshResults() } }  // near real-time
    var mode by remember { mutableStateOf("bar") }
    val res = vm.results
    if (res == null) { Text("No results yet.", Modifier.padding(16.dp)); return }
    val t = res.turnout
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Voter turnout (${res.election.status})", style = MaterialTheme.typography.titleMedium)
                Text("${t.votesCast} of ${t.eligibleVoters} eligible voters (${fmt(t.turnoutPercent)}%)")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { (t.turnoutPercent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "bar", onClick = { mode = "bar" }, label = { Text("Bar chart") })
            FilterChip(selected = mode == "pie", onClick = { mode = "pie" }, label = { Text("Pie chart") })
        }
        if (mode == "pie") PieChart(res.results)
        res.results.forEach { r ->
            val color = PALETTE[r.candidateId % PALETTE.size]
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text("${r.rank}. ${r.fullName}", Modifier.weight(1f))
                    Text("${r.votes} votes (${fmt(r.percent)}%)")
                }
                if (mode == "bar") {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFFE4E0DC))) {
                        Box(Modifier.fillMaxWidth((r.percent / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(color))
                    }
                }
            }
        }
    }
}

// ---------------- Admin ----------------
@Composable
fun AdminTab(vm: AppVM) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Election status", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("upcoming", "open", "closed").forEach { s ->
                    FilterChip(selected = vm.election?.status == s, onClick = { vm.setStatus(s) },
                        label = { Text(s.replaceFirstChar { it.uppercase() }) })
                }
            }
        }
        item {
            Text("Flagged manifestos for review", style = MaterialTheme.typography.titleMedium)
            if (vm.flagged.isEmpty()) Text("None flagged.", fontSize = 13.sp)
            vm.flagged.forEach { Text("${it.fullName}: ${fmt((it.highestSimilarity ?: 0.0) * 100)}% similarity", fontSize = 13.sp) }
            Text("Flagged at 70% similarity or higher. A flag does not reject a candidate.", fontSize = 11.sp)
        }
        item { Text("Verified scores (0 to 100)", style = MaterialTheme.typography.titleMedium) }
        items(vm.candidates) { c ->
            var a by remember(c.id, c.achievementsScore) { mutableStateOf(c.achievementsScore?.let { fmt(it) } ?: "") }
            var l by remember(c.id, c.leadershipScore) { mutableStateOf(c.leadershipScore?.let { fmt(it) } ?: "") }
            var m by remember(c.id, c.communityScore) { mutableStateOf(c.communityScore?.let { fmt(it) } ?: "") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.fullName}  |  credibility ${fmt(c.credibilityScore)}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        OutlinedTextField(a, { a = it }, label = { Text("Achievements") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(l, { l = it }, label = { Text("Leadership") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(m, { m = it }, label = { Text("Community") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        val x = a.toDoubleOrNull(); val y = l.toDoubleOrNull(); val z = m.toDoubleOrNull()
                        if (x == null || y == null || z == null || listOf(x, y, z).any { it < 0 || it > 100 })
                            vm.message = "Scores must be numbers from 0 to 100"
                        else vm.saveScores(c.id, x, y, z)
                    }, enabled = !vm.busy) { Text("Save scores") }
                }
            }
        }
        item {
            Text("Post an announcement", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.postAnnouncement(title.trim(), body.trim()); title = ""; body = "" },
                enabled = title.isNotBlank() && !vm.busy) { Text("Post") }
        }
    }
}
