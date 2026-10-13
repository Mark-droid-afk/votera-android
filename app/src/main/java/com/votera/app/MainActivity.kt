@file:OptIn(ExperimentalMaterial3Api::class)

package com.votera.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

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
    // Administrator-only data
    var votes by mutableStateOf<List<VoteAuditDto>>(emptyList())
    var voters by mutableStateOf<List<VoterDto>>(emptyList())
    var turnoutRows by mutableStateOf<List<TurnoutRowDto>>(emptyList())

    var busy by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var liveState by mutableStateOf("off")  // live, reconnecting or off (off = refreshing every 15 seconds)
    var showReceipt by mutableStateOf(false)
    var showTutorial by mutableStateOf(false)
    var comparePicked by mutableStateOf(listOf<Int>())  // kept here so the Compare tab remembers its choice when you switch tabs
    var adminSub by mutableStateOf("overview")
    var candidateQuery by mutableStateOf("")
    var candidateSort by mutableStateOf("default")  // default, name or credibility
    var deadline by mutableStateOf<Long?>(null)      // when voting closes automatically, on this phone's clock (elapsedRealtime)
    var credentials by mutableStateOf<CredentialsRes?>(null)
    var importResult by mutableStateOf<ImportRes?>(null)
    var needServer by mutableStateOf(false)  // set when login cannot reach the server, so the login screen offers the address field
    var receiptName by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)               // true after the first full load, so lists show placeholders instead of "empty" while loading
    var offline by mutableStateOf(false)              // last background refresh could not reach the server
    var banner by mutableStateOf<AnnouncementDto?>(null)  // a new announcement that just arrived (students)
    var matchMode by mutableStateOf(false)            // Compare tab: false = side by side, true = "Match me"
    var priorities by mutableStateOf(listOf(5f, 5f, 5f, 5f))  // Match me: originality, achievements, leadership, community (0 to 10)
    var auditLog by mutableStateOf<List<AuditDto>>(emptyList())
    var auditOnlyProblems by mutableStateOf(false)
    var selfTest by mutableStateOf<SelfTestRes?>(null)
    var selfTesting by mutableStateOf(false)
    var candidatesListState = LazyListState()         // remembers where you were in the candidate list
    private var knownAnnouncements: Set<Int>? = null

    private var liveJob: Job? = null
    private var pollJob: Job? = null
    private var syncing = false
    private var syncAgain = false

    private fun handleError(t: Throwable) {
        if (t is HttpException && t.code() == 401 && stage == "main") {
            message = "Session expired. Please log in again."
            logout()
        } else {
            if (t is IOException && stage != "main") needServer = true
            message = errorMessage(t)
        }
    }

    private fun launchTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) { handleError(t) }
            finally { busy = false }
        }
    }

    /** Keeps the election and its automatic closing time together. The server sends seconds_left, so phone clock errors do not matter. */
    private fun applyElection(e: ElectionDto) {
        election = e
        deadline = e.secondsLeft?.let { SystemClock.elapsedRealtime() + it * 1000L }
    }

    private suspend fun loadAll() {
        val api = Net.api
        applyElection(api.election())
        candidates = api.candidates()
        val fresh = api.announcements()
        val seen = knownAnnouncements
        if (seen != null && user?.role == "student") fresh.firstOrNull { it.id !in seen }?.let { banner = it }
        knownAnnouncements = fresh.map { it.id }.toSet()
        announcements = fresh
        results = api.results()
        if (user?.role == "student") voteStatus = api.voteStatus()
        if (user?.role == "admin") {
            flagged = api.flagged().flagged
            votes = api.votes()
            voters = api.voters()
            turnoutRows = api.turnoutByProgram()
        }
        loaded = true
        offline = false
    }

    /** portal is "student" or "admin": the login screen the person used. The server rejects a mismatch. */
    fun login(studentNumber: String, password: String, portal: String) = launchTask {
        val r = Net.api.login(LoginReq(studentNumber.trim(), password, portal))
        otpToken = r.otpToken
        needServer = false
        devOtp = r.devOtp  // OTP delivery is simulated in the prototype, so the code is shown on screen
        stage = "otp"
    }

    fun verifyOtp(code: String) = launchTask {
        val r = Net.api.verifyOtp(OtpReq(otpToken, code.trim()))
        Session.token = r.accessToken
        user = r.user
        loadAll()
        stage = "main"
        showTutorial = !Prefs.tutorialSeen(r.user.studentNumber)  // tutorial on the first login only
        startLive()
    }

    fun backToLogin() { stage = "login"; otpToken = ""; devOtp = "" }

    /** Pull-to-refresh and the refresh button. */
    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            try { loadAll() }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) { handleError(t) }
            finally { refreshing = false }
        }
    }

    /** Quiet refresh used by live sync: no loading bar, and events that arrive mid-refresh are merged into one more pass. */
    private fun quickSync() {
        viewModelScope.launch {
            if (stage != "main") return@launch
            if (syncing) { syncAgain = true; return@launch }
            syncing = true
            try {
                do {
                    syncAgain = false
                    try { loadAll() }
                    catch (e: CancellationException) { throw e }
                    catch (t: Throwable) {
                        if (t is HttpException && t.code() == 401) { handleError(t); return@launch }
                        if (t is IOException) offline = true
                    }
                } while (syncAgain && stage == "main")
            } finally { syncing = false }
        }
    }

    /** Live sync: a WebSocket pushes "something changed" and the app re-fetches. If the socket is off or down,
     *  a 15-second refresh keeps the screens current. */
    fun startLive() {
        stopLive()
        val token = Session.token ?: return
        liveJob = viewModelScope.launch {
            while (isActive) {
                if (Prefs.live) {
                    liveState = "reconnecting"
                    Live.connect(
                        token,
                        onReady = { viewModelScope.launch { liveState = "live" }; quickSync() },
                        onChanged = { quickSync() }
                    )
                    liveState = "reconnecting"
                } else {
                    liveState = "off"
                    delay(1000)
                    continue
                }
                delay(3000)
            }
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (stage == "main" && liveState != "live") quickSync()
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel(); pollJob?.cancel()
        liveJob = null; pollJob = null
        liveState = "off"
    }

    override fun onCleared() { stopLive() }

    fun vote(c: CandidateDto) = launchTask {
        val r = Net.api.vote(VoteReq(c.id))
        voteStatus = VoteStatusRes(true, r.transactionId)
        receiptName = c.fullName
        showReceipt = true
        results = Net.api.results()
    }

    fun logout() {
        stopLive()
        Session.token = null
        user = null; election = null; candidates = emptyList(); announcements = emptyList()
        results = null; voteStatus = null; flagged = emptyList(); otpToken = ""; devOtp = ""
        votes = emptyList(); voters = emptyList(); turnoutRows = emptyList()
        showReceipt = false; receiptName = null; showTutorial = false; importResult = null; credentials = null; deadline = null; candidateQuery = ""; candidateSort = "default"; comparePicked = emptyList(); adminSub = "overview"
        loaded = false; offline = false; banner = null; knownAnnouncements = null; matchMode = false; priorities = listOf(5f, 5f, 5f, 5f)
        auditLog = emptyList(); auditOnlyProblems = false; selfTest = null; selfTesting = false; candidatesListState = LazyListState()
        stage = "login"
    }

    // Admin
    fun setStatus(status: String) = launchTask { applyElection(Net.api.setStatus(StatusReq(status))); loadAll() }
    /** minutes = null clears the automatic closing time. */
    fun setSchedule(minutes: Int?) = launchTask {
        applyElection(Net.api.setSchedule(ScheduleReq(minutes)))
        message = if (minutes == null) "Automatic closing time cleared" else "Voting will close automatically in $minutes minutes"
    }
    /** Creates new passwords for every student (or one program) and keeps the sheet so it can be shared once. */
    fun regenerateCredentials(program: String?) = launchTask {
        credentials = Net.api.regenerateCredentials(CredentialsReq(program))
    }
    fun saveScores(id: Int, a: Double, l: Double, c: Double) = launchTask {
        Net.api.setScores(id, ScoresReq(a, l, c))
        loadAll()
        message = "Scores saved"
    }
    fun postAnnouncement(title: String, body: String) = launchTask {
        Net.api.postAnnouncement(AnnouncementReq(title, body))
        announcements = Net.api.announcements()
        message = "Announcement posted"
    }
    fun addCandidate(req: CandidateReq, photo: ByteArray?) = launchTask {
        val c = Net.api.addCandidate(req)
        if (photo != null) Net.api.uploadPhoto(c.id, jpegBody(photo))
        loadAll()
        message = "Candidate added. Similarity and credibility were recomputed."
    }
    fun editCandidate(id: Int, req: CandidateReq, photo: ByteArray?) = launchTask {
        Net.api.editCandidate(id, req)
        if (photo != null) Net.api.uploadPhoto(id, jpegBody(photo))
        loadAll()
        message = "Candidate updated. Similarity and credibility were recomputed."
    }
    fun deleteCandidate(id: Int) = launchTask {
        Net.api.deleteCandidate(id)
        loadAll()
        message = "Candidate removed"
    }
    fun removePhoto(id: Int) = launchTask {
        Net.api.deletePhoto(id)
        loadAll()
        message = "Photo removed"
    }
    /** Sends the sheet (CSV text) to the server, which checks every row and reports what was added and what was skipped. */
    fun importVoters(csv: String) = launchTask {
        importResult = Net.api.importVoters(ImportReq(csv))
        loadAll()
    }
    /** Loads the read-only activity log (logins, failed attempts, status changes, votes cast without their choice). */
    fun loadAudit() {
        viewModelScope.launch {
            try { auditLog = Net.api.auditLog(150, auditOnlyProblems) }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) { if (t is HttpException && t.code() == 401) handleError(t) }
        }
    }
    /** Runs the server's self-test of the Chapter 3 methods on known inputs. */
    fun runSelfTest() {
        viewModelScope.launch {
            selfTesting = true
            try { selfTest = Net.api.selfTest() }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) { handleError(t) }
            finally { selfTesting = false }
        }
    }
    fun resetElection() = launchTask {
        val r = Net.api.resetElection()
        loadAll()
        message = "Election reset. ${r.votesRemoved} votes removed."
    }
    fun demoFill(count: Int) = launchTask {
        val r = Net.api.demoFill(DemoReq(count))
        loadAll()
        message = "Added ${r.added} demo votes"
    }
    /** Downloads the vote list as CSV text; the screen then hands it to the Android share sheet. */
    fun exportVotes(onText: (String) -> Unit) = launchTask {
        val body = Net.api.exportVotesCsv()
        val text = withContext(Dispatchers.IO) { body.string() }
        onText(text)
    }
}

// ---------------- Theme ----------------
// Brand colors taken from the VotEra logo
val MAROON = Color(0xFF720927)
val GOLD = Color(0xFFD4A033)
val GREEN = Color(0xFF2E7D32)

val PALETTE = listOf(
    MAROON, GOLD, Color(0xFF1F6F8B), Color(0xFF4C7A34),
    Color(0xFF6A4C93), Color(0xFFC1440E), Color(0xFF555555)
)

private val LightColors = lightColorScheme(
    primary = MAROON, onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DCE2), onPrimaryContainer = Color(0xFF3A0413),
    secondary = GOLD, background = Color(0xFFFAF8F6), surface = Color(0xFFFAF8F6),
    surfaceVariant = Color(0xFFE9E3E0), onSurfaceVariant = Color(0xFF5E5658),
    surfaceContainer = Color(0xFFF3EEEC), surfaceContainerHighest = Color(0xFFF0EAE7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE9A0B4), onPrimary = Color(0xFF3A0413),
    primaryContainer = Color(0xFF5A1429), onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = GOLD, background = Color(0xFF151112), surface = Color(0xFF151112),
    onBackground = Color(0xFFEDE0E2), onSurface = Color(0xFFEDE0E2),
    surfaceVariant = Color(0xFF3A3234), onSurfaceVariant = Color(0xFFCFC3C6),
    surfaceContainer = Color(0xFF1F191B), surfaceContainerHighest = Color(0xFF2A2325)
)

// High-contrast variants (Settings > Accessibility): pure black or white backgrounds, darker accent, stronger text.
private val HighLight = lightColorScheme(
    primary = Color(0xFF4A0519), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAD0D8), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF7A5200), background = Color.White, surface = Color.White,
    onBackground = Color.Black, onSurface = Color.Black,
    surfaceVariant = Color(0xFFC9C0C2), onSurfaceVariant = Color(0xFF1F1A1B),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHighest = Color(0xFFE6E6E6),
    error = Color(0xFF8C0009), errorContainer = Color(0xFFFFD6D6), onErrorContainer = Color.Black, outline = Color.Black
)

private val HighDark = darkColorScheme(
    primary = Color(0xFFFFB3C6), onPrimary = Color.Black,
    primaryContainer = Color(0xFF5A1429), onPrimaryContainer = Color.White,
    secondary = GOLD, background = Color.Black, surface = Color.Black,
    onBackground = Color.White, onSurface = Color.White,
    surfaceVariant = Color(0xFF4A4244), onSurfaceVariant = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFF121212), surfaceContainerHighest = Color(0xFF242424),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF7A0010), onErrorContainer = Color.White, outline = Color.White
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerConfig.init(applicationContext)  // loads the saved server address, if any
        Prefs.init(applicationContext)         // loads theme, text size and the other settings
        window.statusBarColor = MAROON.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent { VoteraApp() }
    }
}

// ---------------- Small shared helpers and widgets ----------------
val SN_REGEX = Regex("""^\d{4}-\d{5}-CM-\d$""", RegexOption.IGNORE_CASE)  // 2020-00000-CM-0

fun fmt(d: Double?) = if (d == null) "-" else "%.1f".format(d)
fun fmt0(d: Double?) = if (d == null) "-" else "%.0f".format(d)

fun initials(name: String) =
    name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

@Composable
fun Avatar(name: String, id: Int, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(PALETTE[(id and Int.MAX_VALUE) % PALETTE.size]), contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 3).sp)
    }
}

@Composable
fun Logo(height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color.White)) {
        Image(painterResource(R.drawable.votera_logo), contentDescription = "VotEra logo", modifier = Modifier.height(height))
    }
}

@Composable
fun Empty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun StatusPill(status: String) {
    val color = when (status) { "open" -> GREEN; "upcoming" -> GOLD; else -> Color(0xFF777777) }
    Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(status.replaceFirstChar { it.uppercase() }, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LiveBadge(state: String) {
    val (label, color) = when (state) {
        "live" -> "Live" to Color(0xFF7BD88F)
        "reconnecting" -> "Reconnecting" to GOLD
        else -> "Refreshing every 15 s" to Color(0xFFBDBDBD)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}

/** "Voting closes in 01:23:45", counting down every second. Null when no closing time is set. */
@Composable
fun countdownText(deadline: Long?): String? {
    var now by remember(deadline) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) { if (deadline != null) while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    if (deadline == null) return null
    val left = ((deadline - now) / 1000).coerceAtLeast(0)
    if (left == 0L) return "Time is up. Voting is closing."
    val h = left / 3600; val m = (left % 3600) / 60; val sec = left % 60
    return "Voting closes in " + (if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec))
}

/** Credibility score as a ring, 0 to 100. */
@Composable
fun CredRing(score: Double?, diameter: Dp, stroke: Dp = 5.dp) {
    val target = ((score ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val p by animateFloatAsState(target, animationSpec = tween(700), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(diameter).clearAndSetSemantics { contentDescription = "Credibility score ${fmt0(score)} out of 100" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val box = Size(this.size.width - s, this.size.height - s)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(s / 2, s / 2), size = box, style = Stroke(width = s))
            drawArc(color = color, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                topLeft = Offset(s / 2, s / 2), size = box, style = Stroke(width = s, cap = StrokeCap.Round))
        }
        Text(fmt0(score), fontWeight = FontWeight.Bold, fontSize = (diameter.value / 3.2f).sp)
    }
}

/** One criterion of the Weighted Sum Model as an animated bar. */
@Composable
fun ScoreBar(label: String, weight: String, value: Double?) {
    val p by animateFloatAsState(((value ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), animationSpec = tween(600), label = "bar")
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label ($weight)", fontSize = 13.sp)
            Text(fmt(value), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
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

// ---------------- App shell ----------------
@Composable
fun VoteraApp(vm: AppVM = viewModel()) {
    val dark = when (Prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val base = LocalDensity.current
    // Text size setting multiplies the phone's own font scale.
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * Prefs.textScale)) {
        MaterialTheme(colorScheme = when { dark && Prefs.highContrast -> HighDark; dark -> DarkColors; Prefs.highContrast -> HighLight; else -> LightColors }) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1100); splash = false }
                if (splash) SplashScreen() else AppShell(vm)
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Logo(200.dp)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** How "deep" a screen is, so the transition knows whether to slide forward or back. */
private fun screenDepth(s: String): Int = when {
    s == "login" -> 0
    s == "otp" -> 1
    s.startsWith("tab:") -> 2
    else -> 3
}

private data class TabDef(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun AppShell(vm: AppVM) {
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf("candidates") }
    var detailId by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<CandidateDto?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val isAdmin = vm.user?.role == "admin"
    val tabs = buildList {
        add(TabDef("candidates", "Candidates", VIcons.People))
        add(TabDef("compare", "Compare", VIcons.Compare))
        add(TabDef("updates", "Updates", VIcons.Bell))
        add(TabDef("results", "Results", VIcons.Bars))
        if (isAdmin) add(TabDef("admin", "Admin", VIcons.Shield))
    }

    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    // A new-announcement banner hides itself after a few seconds.
    LaunchedEffect(vm.banner) { if (vm.banner != null) { delay(8000); vm.banner = null } }
    // Administrators land on the Admin tab; students (and a logged-out app) land on Candidates.
    LaunchedEffect(vm.user?.role) { tab = if (vm.user?.role == "admin") "admin" else "candidates"; detailId = null; showSettings = false }
    BackHandler(enabled = vm.stage == "main" && showSettings) { showSettings = false }
    BackHandler(enabled = vm.stage == "main" && !showSettings && vm.showReceipt) { vm.showReceipt = false }
    BackHandler(enabled = vm.stage == "main" && !showSettings && detailId != null) { detailId = null }
    BackHandler(enabled = vm.stage == "otp") { vm.backToLogin() }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AnimatedVisibility(visible = vm.stage == "main" && !vm.showTutorial,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) { TopAppBar(
                title = {
                    if (showSettings) Text("Settings", fontSize = 18.sp)
                    else Column {
                        Text(vm.election?.title ?: "VotEra", maxLines = 1, fontSize = 16.sp)
                        LiveBadge(vm.liveState)
                    }
                },
                navigationIcon = {
                    if (showSettings) IconButton(onClick = { showSettings = false }) { Icon(VIcons.Back, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MAROON, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { vm.showTutorial = true }) { Icon(VIcons.Help, contentDescription = "How to use VotEra") }
                        IconButton(onClick = { showSettings = true }) { Icon(VIcons.Tune, contentDescription = "Settings") }
                        IconButton(onClick = { tab = "candidates"; detailId = null; vm.logout() }) { Icon(VIcons.Logout, contentDescription = "Log out") }
                    }
                }
            ) }
        },
        bottomBar = {
            AnimatedVisibility(visible = vm.stage == "main" && !vm.showTutorial,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) { NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.id && !showSettings,
                        onClick = { tab = t.id; detailId = null; showSettings = false; vm.showReceipt = false },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            } }
        }
    ) { pad ->
        val tabIds = tabs.map { it.id }
        // One name per screen. AnimatedContent slides and fades between them instead of swapping instantly.
        val screen = when {
            vm.stage == "login" -> "login"
            vm.stage == "otp" -> "otp"
            vm.showTutorial -> "tutorial"
            showSettings -> "settings"
            vm.showReceipt && vm.voteStatus?.hasVoted == true -> "receipt"
            detailId != null && vm.candidates.any { it.id == detailId } -> "detail:$detailId"
            else -> "tab:$tab"
        }
        Box(Modifier.padding(pad).fillMaxSize()) {
            AnimatedContent(
                targetState = screen, label = "screens", modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (Prefs.reduceMotion) fadeIn(tween(1)) togetherWith fadeOut(tween(1)) else {
                    val from = initialState
                    val to = targetState
                    if (from.startsWith("tab:") && to.startsWith("tab:")) {
                        // Moving between tabs: slide a little in the direction of the tab.
                        val dir = if (tabIds.indexOf(to.removePrefix("tab:")) >= tabIds.indexOf(from.removePrefix("tab:"))) 1 else -1
                        (slideInHorizontally(tween(280)) { dir * it / 5 } + fadeIn(tween(240, delayMillis = 60))) togetherWith
                            (slideOutHorizontally(tween(240)) { -dir * it / 5 } + fadeOut(tween(120)))
                    } else if (screenDepth(to) > screenDepth(from)) {
                        // Going deeper (open a candidate, settings, receipt): new screen slides in from the right.
                        (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(260, delayMillis = 60))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 6 } + fadeOut(tween(140)))
                    } else {
                        // Going back: the reverse.
                        (slideInHorizontally(tween(300)) { -it / 6 } + fadeIn(tween(260, delayMillis = 60))) togetherWith
                            (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(140)))
                    }
                    }
                }
            ) { s ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when {
                        s == "login" -> LoginScreen(vm)
                        s == "otp" -> OtpScreen(vm)
                        s == "tutorial" -> TutorialScreen(isAdmin) { vm.user?.let { Prefs.markTutorial(it.studentNumber) }; vm.showTutorial = false }
                        s == "settings" -> SettingsScreen(vm)
                        s == "receipt" -> ReceiptScreen(vm, onResults = { vm.showReceipt = false; tab = "results"; detailId = null }, onDone = { vm.showReceipt = false })
                        s.startsWith("detail:") -> {
                            val detail = vm.candidates.firstOrNull { it.id == s.removePrefix("detail:").toIntOrNull() }
                            if (detail != null) CandidateDetail(detail, vm, onBack = { detailId = null }, onVote = { confirm = detail })
                        }
                        else -> when (s.removePrefix("tab:")) {
                            "candidates" -> CandidatesTab(vm) { detailId = it }
                            "compare" -> CompareTab(vm)
                            "updates" -> UpdatesTab(vm)
                            "results" -> ResultsTab(vm)
                            "admin" -> if (isAdmin) AdminTab(vm)
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.TopCenter)) {
                AnimatedVisibility(visible = vm.stage == "main" && vm.offline && !vm.showTutorial) {
                    InfoBanner("No connection", "Showing the last information loaded. It updates again when the connection returns.", null, {}, null, warn = true)
                }
                val b = vm.banner
                AnimatedVisibility(visible = vm.stage == "main" && b != null && !vm.showTutorial) {
                    if (b != null) InfoBanner("New announcement: ${b.title}", b.body, "View",
                        { tab = "updates"; detailId = null; showSettings = false; vm.showReceipt = false; vm.banner = null }, { vm.banner = null })
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    confirm?.let { c ->
        ModalBottomSheet(onDismissRequest = { confirm = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Confirm your vote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CandidateAvatar(c, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.fullName, fontWeight = FontWeight.Bold)
                        Text("${c.position} | ${c.program}", fontSize = 13.sp)
                    }
                    CredRing(c.credibilityScore, 46.dp, 4.dp)
                }
                Text("Each student may vote once. Your vote cannot be changed after it is recorded.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirm = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.vote(c); confirm = null }, modifier = Modifier.weight(1f)) { Text("Confirm vote") }
                }
            }
        }
    }
}

// ---------------- Login (Student or Administrator: password, then OTP) ----------------
@Composable
fun LoginScreen(vm: AppVM) {
    var admin by remember { mutableStateOf(false) }
    var sn by remember { mutableStateOf(if (Prefs.rememberSn) Prefs.savedSn else "") }
    var pw by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(0) }
    var server by remember { mutableStateOf(ServerConfig.baseUrl) }
    val serverVisible = showServer || vm.needServer
    val snValid = admin || SN_REGEX.matches(sn.trim())
    val canSubmit = sn.isNotBlank() && snValid && pw.isNotEmpty() && !vm.busy
    fun submit() {
        if (!canSubmit) return
        if (!admin) Prefs.saveSn(sn.trim())
        vm.login(sn, pw, if (admin) "admin" else "student")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        // Tapping the logo five times opens the connection settings (kept out of sight for students).
        Logo(170.dp, Modifier.align(Alignment.CenterHorizontally).clickable { taps++; if (taps >= 5) { showServer = true; taps = 0 } })
        Spacer(Modifier.height(8.dp))
        Text(if (admin) "Administrator login for election staff" else "Simulated Supreme Student Council election, PUP Quezon City",
            Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally), fontSize = 13.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            FilterChip(selected = !admin, onClick = { admin = false; sn = if (Prefs.rememberSn) Prefs.savedSn else ""; pw = ""; showPw = false }, label = { Text("Student") })
            FilterChip(selected = admin, onClick = { admin = true; sn = ""; pw = ""; showPw = false }, label = { Text("Administrator") })
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            sn, { sn = if (admin) it else it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' } },
            label = { Text(if (admin) "Administrator ID" else "Student number") },
            placeholder = { Text(if (admin) "ADMIN-001" else "2020-00000-CM-0") },
            supportingText = {
                if (!admin) Text(if (sn.isNotEmpty() && !snValid) "Use the format YYYY-NNNNN-CM-N" else "Format: YYYY-NNNNN-CM-N, for example 2020-00000-CM-0")
            },
            isError = !admin && sn.isNotEmpty() && !snValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = { TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth())
        if (!admin) Row(Modifier.clickable { Prefs.updateRememberSn(!Prefs.rememberSn) }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = Prefs.rememberSn, onCheckedChange = { Prefs.updateRememberSn(it) })
            Text("Remember my student number", fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text(if (admin) "Continue as administrator" else "Continue")
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (serverVisible) Card(Modifier.padding(top = 12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (vm.needServer) "VotEra cannot reach the election server." else "Connection", fontWeight = FontWeight.Bold)
                Text("Ask your administrator for the server address and enter it here. This is only needed once.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val n = ServerConfig.normalize(server)
                        if (n == null) vm.message = "That server address is not valid"
                        else { ServerConfig.save(n); server = n; vm.needServer = false; vm.message = "Server address saved. Try logging in again." }
                    }) { Text("Save") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Research prototype for PUP Quezon City. Candidates and data are fictional. " +
            "Participation is voluntary and responses are kept confidential (R.A. 10173).", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun OtpScreen(vm: AppVM) {
    var code by remember { mutableStateOf("") }
    // Verifies by itself as soon as the sixth digit is in. A wrong code stays in the box so it can be corrected.
    LaunchedEffect(code) { if (code.length == 6 && !vm.busy) vm.verifyOtp(code) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your one-time password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Simulated OTP delivery (prototype)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(vm.devOtp, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                TextButton(onClick = { code = vm.devOtp }) { Text("Fill in this code for me") }
            }
        }
        Text("Step 2 of 2. Logging in needs a 6-digit code as a second check. A real system would send it by SMS or email. " +
            "In this prototype it is shown above, so type it in below.", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

// ---------------- Candidates and profile ----------------
@Composable
fun CandidatesTab(vm: AppVM, onOpen: (Int) -> Unit) {
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = vm.candidatesListState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val e = vm.election
                val vs = vm.voteStatus
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Election status", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f))
                            StatusPill(e?.status ?: "closed")
                        }
                        vm.user?.let { Text("Signed in as ${it.fullName} (${it.studentNumber})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        if (e?.status == "open") countdownText(vm.deadline)?.let {
                            Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        if (vs?.hasVoted == true) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("You have voted. Transaction ID: ${vs.transactionId?.take(16)}...", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.showReceipt = true }) { Text("View receipt") }
                        }
                        else if (vm.user?.role == "student") Text("Open a candidate to read the profile and vote.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (vm.user?.role == "student") item { HowToVote(done = vm.voteStatus?.hasVoted == true, open = vm.election?.status == "open") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(vm.candidateQuery, { vm.candidateQuery = it }, placeholder = { Text("Search by name, program or position") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("default" to "Default", "name" to "Name A to Z", "credibility" to "Credibility").forEach { (id, label) ->
                            FilterChip(selected = vm.candidateSort == id, onClick = { vm.candidateSort = id }, label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                }
            }
            val q = vm.candidateQuery.trim()
            val shown = vm.candidates
                .filter { q.isEmpty() || it.fullName.contains(q, true) || it.program.contains(q, true) || it.position.contains(q, true) }
                .let { list ->
                    when (vm.candidateSort) {
                        "name" -> list.sortedBy { it.fullName }
                        "credibility" -> list.sortedByDescending { it.credibilityScore ?: -1.0 }
                        else -> list
                    }
                }
            if (shown.isEmpty()) {
                if (!vm.loaded) items(3) { SkeletonCard() }
                else item { Empty(if (vm.candidates.isEmpty()) "No candidates yet." else "No candidate matches your search.") }
            }
            items(shown, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(c.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CandidateAvatar(c, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${c.position} | ${c.program}", fontSize = 13.sp)
                            if (c.flagged) Text("Manifesto flagged for review", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CredRing(c.credibilityScore, 48.dp, 4.dp)
                            Text("credibility", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CandidateDetail(c: CandidateDto, vm: AppVM, onBack: () -> Unit, onVote: () -> Unit) {
    val isStudent = vm.user?.role == "student"
    val isAdmin = vm.user?.role == "admin"
    val open = vm.election?.status == "open"
    val voted = vm.voteStatus?.hasVoted == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Icon(VIcons.Back, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back to candidates") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CandidateAvatar(c, 80.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.fullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${c.position} | ${c.program}")
            }
            CredRing(c.credibilityScore, 64.dp, 6.dp)
        }
        if (c.flagged) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("This manifesto is very similar to another candidate's (${fmt((c.highestSimilarity ?: 0.0) * 100)}% similarity). " +
                "It is flagged for administrator review. A flag does not disqualify the candidate.",
                Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (isAdmin && c.mostSimilarName != null) Text("Most similar to: ${c.mostSimilarName} (${fmt((c.highestSimilarity ?: 0.0) * 100)}%)",
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Block("Platform / manifesto", c.manifesto)
        Block("Verified achievements", c.achievements)
        Block("Leadership experience", c.leadership)
        Block("Community involvement", c.community)
        if (!c.qa.isNullOrBlank()) Block("Questions and answers", c.qa)
        if (!c.videoUrl.isNullOrBlank()) {
            val uri = LocalUriHandler.current
            OutlinedButton(onClick = { runCatching { uri.openUri(c.videoUrl) } }, modifier = Modifier.fillMaxWidth()) { Text("Watch platform video") }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Credibility score breakdown", style = MaterialTheme.typography.titleMedium)
                ScoreBar("Originality", "35%", c.originalityScore)
                ScoreBar("Verified achievements", "30%", c.achievementsScore)
                ScoreBar("Leadership experience", "20%", c.leadershipScore)
                ScoreBar("Community involvement", "15%", c.communityScore)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Credibility Score", fontWeight = FontWeight.Bold)
                    Text(fmt(c.credibilityScore), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                var how by remember { mutableStateOf(false) }
                TextButton(onClick = { how = !how }) { Text(if (how) "Hide explanation" else "How is this calculated?") }
                if (how) Text("Originality = 100 minus the highest similarity (as a percent) between this manifesto and the other candidates' manifestos, " +
                    "found with TF-IDF and cosine similarity. The administrator scores achievements, leadership and community from validated documents. " +
                    "The Credibility Score is the weighted sum: originality 35%, achievements 30%, leadership 20%, community 15%. " +
                    "A manifesto at 70% similarity or higher is flagged for administrator review, because word overlap can miss reworded copying or catch common campaign phrases.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isStudent) Button(onClick = onVote, enabled = open && !voted, modifier = Modifier.fillMaxWidth()) {
            Text(when { voted -> "You have already voted"; !open -> "Voting is not open"; else -> "Vote for ${c.fullName}" })
        }
    }
}

// ---------------- Vote receipt ----------------
@Composable
fun ReceiptScreen(vm: AppVM, onResults: () -> Unit, onDone: () -> Unit) {
    val tx = vm.voteStatus?.transactionId.orEmpty()
    val clip = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(VIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
        }
        Text("Vote recorded", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        vm.receiptName?.let { Text("You voted for $it") }
        vm.user?.respondentCode?.let { Text("Your respondent code: $it. Write it on the post-pilot survey.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transaction ID", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(tx, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(vm.election?.title ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { clip.setText(AnnotatedString(tx)); vm.message = "Transaction ID copied" }) {
                    Icon(VIcons.Copy, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy")
                }
            }
        }
        Text("This code is a SHA-256 hash made from your student number and the election. Keep it as proof that your vote was recorded.",
            fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onResults, modifier = Modifier.fillMaxWidth()) { Text("View live results") }
        vm.election?.surveyUrl?.let { link ->
            OutlinedButton(onClick = { uri.openUri(link) }, modifier = Modifier.fillMaxWidth()) { Text("Open the post-pilot survey") }
        }
        OutlinedButton(onClick = { printHtml(ctx, "VotEra vote receipt", receiptHtml(vm)) }, modifier = Modifier.fillMaxWidth()) { Text("Print or save as PDF") }
        TextButton(onClick = onDone) { Text("Done") }
    }
}

// ---------------- Side-by-side comparison ----------------
@Composable
fun CompareTab(vm: AppVM) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !vm.matchMode, onClick = { vm.matchMode = false }, label = { Text("Side by side") })
            FilterChip(selected = vm.matchMode, onClick = { vm.matchMode = true }, label = { Text("Match me") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { if (vm.matchMode) MatchMe(vm) else SideBySide(vm) }
    }
}

@Composable
private fun SideBySide(vm: AppVM) {
    val picked = vm.comparePicked
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text("Select two or more candidates to compare side by side.", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.candidates) { c ->
                FilterChip(selected = c.id in picked,
                    onClick = { vm.comparePicked = if (c.id in picked) picked - c.id else picked + c.id },
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CandidateAvatar(c, 44.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(c.program, fontSize = 13.sp)
                                }
                                CredRing(c.credibilityScore, 46.dp, 4.dp)
                            }
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
        } else Empty("Pick at least two candidates above.")
    }
}

// ---------------- Announcements ----------------
@Composable
fun UpdatesTab(vm: AppVM) {
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.announcements.isEmpty()) item { Empty("No announcements yet.") }
            items(vm.announcements, key = { it.id }) { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(a.createdAt.take(10), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(a.body)
                    }
                }
            }
        }
    }
}

// ---------------- Results: turnout, bar chart, pie chart (totals only) ----------------
@Composable
fun PieChart(rows: List<ResultRowDto>) {
    val total = rows.sumOf { it.votes }
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            if (total == 0) drawCircle(empty)
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
fun ResultRow(r: ResultRowDto, candidate: CandidateDto?, showBar: Boolean) {
    val color = PALETTE[r.candidateId % PALETTE.size]
    val p by animateFloatAsState((r.percent / 100).toFloat().coerceIn(0f, 1f), animationSpec = tween(600), label = "result")
    val leader = r.rank == 1 && r.votes > 0
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (candidate != null) CandidateAvatar(candidate, 32.dp) else Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text("${r.rank}. ${r.fullName}", Modifier.weight(1f), fontWeight = if (leader) FontWeight.Bold else FontWeight.Normal)
            if (leader) {
                Text("Leading", fontSize = 11.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(50)).background(GOLD)
                    .padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("${r.votes} votes (${fmt(r.percent)}%)")
        }
        if (showBar) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(color))
            }
        }
    }
}

@Composable
fun ResultsTab(vm: AppVM) {
    var mode by remember { mutableStateOf("bar") }
    val res = vm.results
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        if (res == null) Empty("No results yet.")
        else {
            val t = res.turnout
            val tp by animateFloatAsState((t.turnoutPercent / 100).toFloat().coerceIn(0f, 1f), animationSpec = tween(600), label = "turnout")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Voter turnout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusPill(res.election.status)
                        }
                        Text("${t.votesCast} of ${t.eligibleVoters} eligible voters (${fmt(t.turnoutPercent)}%)")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { tp }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "bar", onClick = { mode = "bar" }, label = { Text("Bar chart") })
                    FilterChip(selected = mode == "pie", onClick = { mode = "pie" }, label = { Text("Pie chart") })
                }
                if (mode == "pie") PieChart(res.results)
                res.results.forEach { r -> ResultRow(r, vm.candidates.firstOrNull { it.id == r.candidateId }, showBar = mode == "bar") }
                Text("Totals only. Individual choices are not shown to other students.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Three-step guide on the student home screen. */
@Composable
fun HowToVote(done: Boolean, open: Boolean) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How to vote", style = MaterialTheme.typography.titleMedium)
            listOf(
                Triple("1", "Read the candidates", "Tap a candidate to read the platform. Use the Compare tab to see two or more side by side."),
                Triple("2", if (done) "You have voted" else if (open) "Cast your vote" else "Voting opens soon", if (done) "Your receipt is saved on this screen." else "Open a candidate and tap Vote, then confirm. You can vote once."),
                Triple("3", "Watch the results", "The Results tab updates live with totals. Individual votes are not shown.")
            ).forEachIndexed { i, (n, head, body) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (i == 1 && done) GREEN else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        if (i == 1 && done) Icon(VIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        else Text(n, color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(head, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
