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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** Title and text of the "done" dialog shown after something important succeeds. */
    var success by mutableStateOf<Pair<String, String>?>(null)
    var confirmLogout by mutableStateOf(false)
    /** Choices made on the ballot but not sent yet: position key -> candidate id. They are sent together from the review screen. */
    val picks = mutableStateMapOf<String, Int>()
    var showReview by mutableStateOf(false)
    /** Position key of the tab open on the Candidates screen (President, Vice President, ...). */
    var positionTab by mutableStateOf("")
    var showFeedback by mutableStateOf(false)
    var showVerify by mutableStateOf(false)
    var feedbackSummary by mutableStateOf<FeedbackSummary?>(null)
    /** Last time the person touched the screen (SystemClock.elapsedRealtime). Used for the idle warning. */
    var lastTouch = SystemClock.elapsedRealtime()  // plain field on purpose: touching the screen must not recompose anything
    fun touch() { lastTouch = SystemClock.elapsedRealtime() }
    var liveState by mutableStateOf("off")  // live, reconnecting or off (off = refreshing every 15 seconds)
    var showReceipt by mutableStateOf(false)
    var showTutorial by mutableStateOf(false)   // set to true to start the guided tour (first login, Help button)
    val tour = TourState()
    fun finishTour() { tour.step = -1; user?.let { Prefs.markTutorial(it.studentNumber) } }
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
    var showWelcome by mutableStateOf(!Prefs.welcomeSeen)  // welcome tour, shown on first launch and from the help center
    var showHelp by mutableStateOf(false)             // help center: FAQ, how the score works, privacy
    var updatesCategory by mutableStateOf("All")      // Updates tab filter: All or one category
    var updatesQuery by mutableStateOf("")            // Updates tab search text
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
    private var pendingAll = false                       // a full refresh is waiting
    private val pendingTopics = mutableSetOf<String>()   // topics that changed while a refresh was running

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

    /** Loads what the screens need. All the requests run at the same time, so the wait is one round trip instead of
     *  eight in a row. topics = null loads everything; a live "changed" message lists only what changed, so a student's
     *  phone refetches just the results when someone votes. */
    private suspend fun loadAll(topics: Set<String>? = null) = coroutineScope {
        val api = Net.api
        val want: (String) -> Boolean = { t -> topics == null || t in topics }
        val admin = user?.role == "admin"
        val student = user?.role == "student"
        val dElection = if (want("election")) async { api.election() } else null
        val dCandidates = if (want("candidates")) async { api.candidates() } else null
        val dAnnouncements = if (want("announcements")) async { api.announcements() } else null
        val dResults = if (want("results")) async { api.results() } else null
        val dVote = if (student && topics == null) async { api.voteStatus() } else null
        val dFlagged = if (admin && want("candidates")) async { api.flagged().flagged } else null
        val dVotes = if (admin && want("results")) async { api.votes() } else null
        val dVoters = if (admin && (want("results") || want("voters"))) async { api.voters() } else null
        val dTurnout = if (admin && (want("results") || want("voters"))) async { api.turnoutByProgram() } else null

        dElection?.let { applyElection(it.await()) }
        dCandidates?.let { candidates = it.await() }
        dAnnouncements?.let {
            val fresh = it.await()
            val seen = knownAnnouncements
            if (seen != null && user?.role == "student") fresh.firstOrNull { a -> a.id !in seen }?.let { a -> banner = a }
            knownAnnouncements = fresh.map { a -> a.id }.toSet()
            announcements = fresh
        }
        dResults?.let { results = it.await() }
        dVote?.let { voteStatus = it.await() }
        dFlagged?.let { flagged = it.await() }
        dVotes?.let { votes = it.await() }
        dVoters?.let { voters = it.await() }
        dTurnout?.let { turnoutRows = it.await() }
        if (topics == null) loaded = true
        offline = false
    }

    /** Wakes the free server and database while the person is still typing the login, so the first login is not slow. */
    fun prewarm() {
        viewModelScope.launch {
            try { Net.api.healthDb() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
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
    private fun quickSync(topics: Set<String>? = null) {
        viewModelScope.launch {
            if (stage != "main") return@launch
            if (topics == null || topics.isEmpty()) pendingAll = true else pendingTopics.addAll(topics)
            if (syncing) return@launch
            syncing = true
            try {
                while (stage == "main" && (pendingAll || pendingTopics.isNotEmpty())) {
                    val t: Set<String>? = if (pendingAll) null else pendingTopics.toSet()
                    pendingAll = false
                    pendingTopics.clear()
                    try { loadAll(t) }
                    catch (e: CancellationException) { throw e }
                    catch (ex: Throwable) {
                        if (ex is HttpException && ex.code() == 401) { handleError(ex); return@launch }
                        if (ex is IOException) offline = true
                    }
                }
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
                        onChanged = { topics -> quickSync(topics) }
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

    /** One vote per position. When the last position is voted, the receipt opens; before that, the student goes back to the ballot. */
    fun vote(c: CandidateDto) = launchTask {
        val r = Net.api.vote(VoteReq(c.id))
        voteStatus = Net.api.voteStatus()
        receiptName = c.fullName
        results = Net.api.results()
        if (r.complete) showReceipt = true
        else {
            val left = voteStatus?.positions?.count { !it.voted } ?: 0
            message = "Vote recorded for ${r.position}. $left position${if (left == 1) "" else "s"} left."
        }
    }

    fun pick(c: CandidateDto) { picks[posKey(c.position)] = c.id }
    fun unpick(position: String) { picks.remove(posKey(position)) }

    /** Sends every selected choice, one vote per position. A position that was already voted is skipped; any other failure keeps the rest selected. */
    fun submitBallot() = launchTask {
        var sent = 0
        var failure: Throwable? = null
        for ((key, id) in picks.toList()) {
            try { Net.api.vote(VoteReq(id)); sent++; picks.remove(key) }
            catch (e: CancellationException) { throw e }
            catch (e: HttpException) { if (e.code() == 409) picks.remove(key) else { failure = e; break } }
            catch (t: Throwable) { failure = t; break }
        }
        voteStatus = Net.api.voteStatus()
        results = Net.api.results()
        if (failure != null) throw failure
        showReview = false
        if (voteStatus?.complete == true) showReceipt = true
        else {
            val left = voteStatus?.positions?.count { !it.voted } ?: 0
            success = "Ballot submitted" to "$sent vote${if (sent == 1) "" else "s"} recorded. $left position${if (left == 1) "" else "s"} left, you can vote for them any time while the election is open."
        }
    }

    fun logout() {
        stopLive()
        tour.step = -1; picks.clear(); showReview = false; positionTab = ""; showFeedback = false; showVerify = false; feedbackSummary = null
        Session.token = null
        user = null; election = null; candidates = emptyList(); announcements = emptyList()
        results = null; voteStatus = null; flagged = emptyList(); otpToken = ""; devOtp = ""
        votes = emptyList(); voters = emptyList(); turnoutRows = emptyList()
        showReceipt = false; receiptName = null; showTutorial = false; importResult = null; credentials = null; deadline = null; candidateQuery = ""; candidateSort = "default"; updatesCategory = "All"; updatesQuery = ""; comparePicked = emptyList(); adminSub = "overview"
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
        success = "Scores saved" to "The scores were saved and the credibility was recalculated."
    }
    fun postAnnouncement(title: String, body: String, category: String, pinned: Boolean, image: ByteArray?) = launchTask {
        val a = Net.api.postAnnouncement(AnnouncementReq(title, body, category, pinned))
        if (image != null) Net.api.uploadAnnouncementImage(a.id, jpegBody(image))
        announcements = Net.api.announcements()
        success = "Post published" to "Students can now see it in Updates."
    }
    fun setPostPinned(a: AnnouncementDto) = launchTask {
        Net.api.editAnnouncement(a.id, AnnouncementEditReq(pinned = !a.pinned))
        announcements = Net.api.announcements()
    }
    fun deletePost(a: AnnouncementDto) = launchTask {
        Net.api.deleteAnnouncement(a.id)
        announcements = Net.api.announcements()
        success = "Post deleted" to "The post was removed from Updates."
    }
    fun addCandidate(req: CandidateReq, photo: ByteArray?) = launchTask {
        val c = Net.api.addCandidate(req)
        if (photo != null) Net.api.uploadPhoto(c.id, jpegBody(photo))
        loadAll()
        success = "Candidate added" to "Similarity and credibility were recomputed for every candidate."
    }
    fun editCandidate(id: Int, req: CandidateReq, photo: ByteArray?) = launchTask {
        Net.api.editCandidate(id, req)
        if (photo != null) Net.api.uploadPhoto(id, jpegBody(photo))
        loadAll()
        success = "Candidate updated" to "Similarity and credibility were recomputed for every candidate."
    }
    fun deleteCandidate(id: Int) = launchTask {
        Net.api.deleteCandidate(id)
        loadAll()
        success = "Candidate removed" to "The candidate was removed from the ballot."
    }
    fun removePhoto(id: Int) = launchTask {
        Net.api.deletePhoto(id)
        loadAll()
        success = "Photo removed" to "The candidate now shows the initials avatar."
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
        success = "Election reset" to "${r.votesRemoved} votes were removed and the election is ready again."
    }
    /** Adds fictional candidates for Auditor and the councilor positions, so the ballot shows every SSC position. */
    fun demoCandidates() = launchTask {
        val r = Net.api.demoCandidates()
        loadAll()
        success = (if (r.added > 0) "Sample candidates added" else "Nothing to add") to
            (if (r.added > 0) "${r.added} fictional candidates were added for the other positions." else "The sample candidates are already on the ballot.")
    }
    fun demoFill(count: Int) = launchTask {
        val r = Net.api.demoFill(DemoReq(count))
        loadAll()
        success = "Demo votes added" to "${r.added} sample votes were added. Reset the election when you are done."
    }
    /** Downloads the vote list as CSV text; the screen then hands it to the Android share sheet. */
    /** Student: checks that a Transaction ID is recorded. */
    fun verifyReceipt(tx: String, onResult: (VerifyRes) -> Unit) = launchTask { onResult(Net.api.verifyReceipt(tx.trim())) }
    fun sendFeedback(r: FeedbackReq) = launchTask {
        Net.api.sendFeedback(r)
        showFeedback = false
        success = "Thank you" to "Your feedback was sent. You can change it later in Settings."
    }
    fun loadFeedback() = launchTask { feedbackSummary = Net.api.feedbackSummary() }
    /** Admin: one JSON file with everything recorded, for the paper's evidence. */
    fun exportBackup(onText: (String) -> Unit) = launchTask {
        val body = Net.api.backup()
        onText(withContext(Dispatchers.IO) { body.string() })
    }
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
    // The logo has a transparent background outside the circle. The dark version has a lighter ring and wordmark so it reads on dark screens.
    val dark = isDarkNow()
    Image(painterResource(if (dark) R.drawable.votera_logo_dark else R.drawable.votera_logo), contentDescription = "VotEra logo",
        modifier = modifier.height(height))
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
                LaunchedEffect(Unit) { vm.prewarm(); delay(700); splash = false }
                if (splash) SplashScreen() else CompositionLocalProvider(LocalTour provides vm.tour) { Box(Modifier.fillMaxSize()) {
                    AppShell(vm)
                    if (vm.showHelp) HelpCenter(onBack = { vm.showHelp = false }, onTour = { vm.showHelp = false; vm.showWelcome = true })
                    if (vm.showWelcome) WelcomeScreen(onDone = { Prefs.markWelcomeSeen(); vm.showWelcome = false })
                    if (vm.tour.active && vm.stage == "main") TourOverlay(vm.tour, remember(vm.user?.role) { tourSteps(vm.user?.role == "admin") }) { vm.finishTour() }
                } }
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
    var showSettings by remember { mutableStateOf(false) }
    var idleLeft by remember { mutableStateOf<Int?>(null) }
    // Idle session: warn one minute before logging out, so a phone left unlocked does not stay signed in.
    LaunchedEffect(vm.stage) {
        while (vm.stage == "main") {
            delay(1000)
            val idle = SystemClock.elapsedRealtime() - vm.lastTouch
            if (idle >= IDLE_OUT_MS) { idleLeft = null; tab = "candidates"; detailId = null; showSettings = false; vm.logout(); vm.message = "You were logged out because the app was idle." }
            else idleLeft = if (idle >= IDLE_WARN_MS) ((IDLE_OUT_MS - idle) / 1000).toInt() + 1 else null
        }
        idleLeft = null
    }
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

    // Guided tour: start it when asked, and show the tab each step talks about.
    val tourList = remember(isAdmin) { tourSteps(isAdmin) }
    LaunchedEffect(vm.showTutorial, vm.stage) {
        if (vm.showTutorial && vm.stage == "main" && !vm.showWelcome) { delay(600); vm.showTutorial = false; vm.tour.step = 0 }
    }
    LaunchedEffect(vm.tour.step) {
        val st = tourList.getOrNull(vm.tour.step) ?: return@LaunchedEffect
        st.tab?.let { tab = it; detailId = null; showSettings = false; vm.showReceipt = false }
        st.adminSub?.let { vm.adminSub = it }
        if (st.tab == "candidates") { try { vm.candidatesListState.scrollToItem(0) } catch (e: CancellationException) { throw e } catch (t: Throwable) { } }
    }

    Scaffold(
        modifier = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); vm.touch() } } },
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AnimatedVisibility(visible = vm.stage == "main",
                enter = fadeIn(tween(250)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) { TopAppBar(
                title = {
                    if (showSettings) Text("Settings", fontSize = 18.sp)
                    else Column(Modifier.tourTarget("title")) {
                        Text("VotEra: Every vote counts", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 16.sp)
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
                        IconButton(onClick = { vm.showTutorial = true }, modifier = Modifier.tourTarget("help")) { Icon(VIcons.Help, contentDescription = "Play the guided tour") }
                        IconButton(onClick = { showSettings = true }, modifier = Modifier.tourTarget("settings")) { Icon(VIcons.Tune, contentDescription = "Settings") }
                        IconButton(onClick = { vm.confirmLogout = true }, modifier = Modifier.tourTarget("logout")) { Icon(VIcons.Logout, contentDescription = "Log out") }
                    }
                }
            ) }
        },
        bottomBar = {
            AnimatedVisibility(visible = vm.stage == "main",
                enter = fadeIn(tween(250)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) { NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        modifier = Modifier.tourTarget("nav_${t.id}"),
                        selected = tab == t.id && !showSettings,
                        onClick = { if (tab != t.id) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); tab = t.id; detailId = null; showSettings = false; vm.showReceipt = false },
                        icon = {
                            val left = if (t.id == "candidates" && vm.user?.role == "student" && vm.election?.status == "open") vm.voteStatus?.positions?.count { !it.voted } ?: 0 else 0
                            if (left > 0) BadgedBox(badge = { Badge { Text("$left") } }) { Icon(t.icon, contentDescription = "${t.label}, $left positions left to vote") }
                            else Icon(t.icon, contentDescription = t.label)
                        },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
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
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
                  Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    when {
                        s == "login" -> LoginScreen(vm)
                        s == "otp" -> OtpScreen(vm)
                        s == "settings" -> SettingsScreen(vm)
                        s == "receipt" -> ReceiptScreen(vm, onResults = { vm.showReceipt = false; tab = "results"; detailId = null }, onDone = { vm.showReceipt = false })
                        s.startsWith("detail:") -> {
                            val detail = vm.candidates.firstOrNull { it.id == s.removePrefix("detail:").toIntOrNull() }
                            if (detail != null) CandidateDetail(detail, vm, onBack = { detailId = null }, onVote = { vm.pick(detail); detailId = null; vm.message = "Selected ${ellipsize(detail.fullName, 24)} for ${ellipsize(detail.position, 24)}. Review your ballot when you are ready." })
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
            }
            Column(Modifier.align(Alignment.TopCenter)) {
                AnimatedVisibility(visible = vm.stage == "main" && vm.offline) {
                    InfoBanner("No connection", "Showing the last information loaded. It updates again when the connection returns.", null, {}, null, warn = true)
                }
                val b = vm.banner
                AnimatedVisibility(visible = vm.stage == "main" && b != null) {
                    if (b != null) InfoBanner("New announcement: ${b.title}", b.body, "View",
                        { tab = "updates"; detailId = null; showSettings = false; vm.showReceipt = false; vm.banner = null }, { vm.banner = null })
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (vm.confirmLogout) AlertDialog(
        onDismissRequest = { vm.confirmLogout = false },
        title = { Text("Log out?") },
        text = { Text("You will need your student number and password again, and a new OTP, to get back in.") },
        confirmButton = { TextButton(onClick = { vm.confirmLogout = false; tab = "candidates"; detailId = null; showSettings = false; vm.logout() }) { Text("Log out") } },
        dismissButton = { TextButton(onClick = { vm.confirmLogout = false }) { Text("Stay") } })
    vm.success?.let { (t, d) -> SuccessDialog(t, d) { vm.success = null } }
    if (vm.showFeedback) FeedbackDialog(vm) { vm.showFeedback = false }
    if (vm.showVerify) VerifyReceiptDialog(vm) { vm.showVerify = false }
    idleLeft?.let { IdleWarningDialog(it) { vm.touch(); idleLeft = null } }

    if (vm.showReview) ReviewBallotSheet(vm, onChange = { vm.showReview = false; tab = "candidates"; detailId = null; showSettings = false })
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

    Column(Modifier.fillMaxSize()) {
    // Help and theme buttons stay pinned at the top; only the form below scrolls.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.showHelp = true }) { Icon(VIcons.Help, contentDescription = "Help, FAQ and privacy") }
        ThemeToggleButton()
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
        // Tapping the logo five times opens the connection settings (kept out of sight for students).
        Logo(170.dp, Modifier.align(Alignment.CenterHorizontally).clickable { taps++; if (taps >= 5) { showServer = true; taps = 0 } })
        Spacer(Modifier.height(8.dp))
        Text(if (admin) "Administrator login for election staff" else "Simulated Supreme Student Council election, PUP Quezon City",
            Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally), fontSize = 13.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            FilterChip(selected = !admin, onClick = { admin = false; sn = if (Prefs.rememberSn) Prefs.savedSn else ""; pw = ""; showPw = false }, leadingIcon = { Icon(VIcons.People, contentDescription = null, Modifier.size(16.dp)) }, label = { Text("Student", maxLines = 1) })
            FilterChip(selected = admin, onClick = { admin = true; sn = ""; pw = ""; showPw = false }, leadingIcon = { Icon(VIcons.Shield, contentDescription = null, Modifier.size(16.dp)) }, label = { Text("Administrator", maxLines = 1) })
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            sn, { sn = if (admin) it else it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' } },
            label = { Text(if (admin) "Administrator ID" else "Student number") },
            placeholder = { if (!admin) Text("2020-00000-CM-0") },
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
}

@Composable
fun OtpScreen(vm: AppVM) {
    var code by remember { mutableStateOf("") }
    // Verifies by itself as soon as the sixth digit is in. A wrong code stays in the box so it can be corrected.
    LaunchedEffect(code) { if (code.length == 6 && !vm.busy) vm.verifyOtp(code) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your one-time password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
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
                Card(Modifier.fillMaxWidth().tourTarget("status"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Election status", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f))
                            StatusPill(e?.status ?: "closed")
                        }
                        ElectionTimeline(e?.status ?: "closed", MaterialTheme.colorScheme.onPrimaryContainer)
                        vm.user?.let { Text("Signed in as ${ellipsize(it.fullName, 28)} (${it.studentNumber})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        if (e?.status == "open") countdownText(vm.deadline)?.let {
                            Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        if (vs?.hasVoted == true) Row(verticalAlignment = Alignment.CenterVertically) {
                            val done = vs.positions.count { it.voted }
                            val total = vs.positions.size
                            Text(if (vs.complete) "You have voted for all $total positions." else "You have voted for $done of $total positions.", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.showReceipt = true }) { Text("View receipt", maxLines = 1) }
                        }
                        else if (vm.user?.role == "student") Text("Open a candidate to read the profile and select. You vote once for each position.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (vm.user?.role == "student" && vm.election?.status == "open" && vm.voteStatus?.complete != true) item { BallotBar(vm) }
            if (vm.user?.role == "student") item { HowToVote(done = vm.voteStatus?.complete == true, open = vm.election?.status == "open") }
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
            val groups = groupByPosition(shown)
            if (groups.isNotEmpty()) {
                val sel = groups.indexOfFirst { posKey(it.first) == vm.positionTab }.let { if (it < 0) 0 else it }
                val isStudent = vm.user?.role == "student"
                item(key = "pos-tabs") {
                    ScrollableTabRow(selectedTabIndex = sel, modifier = Modifier.tourTarget("positions"), edgePadding = 0.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                        groups.forEachIndexed { i, (pos, list) ->
                            val m = vm.voteStatus?.positions?.firstOrNull { posKey(it.position) == posKey(pos) }
                            Tab(selected = i == sel, onClick = { vm.positionTab = posKey(pos) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(ellipsize(posShort(pos), 14), maxLines = 1, fontSize = 13.sp)
                                        if (isStudent && m?.voted == true) { Spacer(Modifier.width(4.dp)); Icon(VIcons.Check, contentDescription = "Voted", tint = GREEN, modifier = Modifier.size(14.dp)) }
                                        else if (isStudent && vm.picks[posKey(pos)] != null) { Spacer(Modifier.width(4.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                                        else { Spacer(Modifier.width(4.dp)); Text("${list.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                })
                        }
                    }
                }
                val (pos, list) = groups[sel]
                val mine = vm.voteStatus?.positions?.firstOrNull { posKey(it.position) == posKey(pos) }
                item(key = "pos:$pos") { PositionHeader(pos, list.size, mine, isStudent, vm.picks[posKey(pos)] != null) }
                items(list, key = { it.id }) { c ->
                    Box(if (c.id == list.first().id) Modifier.tourTarget("candidate") else Modifier) {
                        CandidateCard(c, mine?.candidateId == c.id, { onOpen(c.id) }, vm.picks[posKey(pos)] == c.id)
                    }
                }
                if (groups.size > 1) item(key = "pos-next") {
                    val nextIdx = (sel + 1) % groups.size
                    OutlinedButton(onClick = { vm.positionTab = posKey(groups[nextIdx].first) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Next position: ${ellipsize(posShort(groups[nextIdx].first), 28)}", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionHeader(position: String, count: Int, mine: PositionVoteDto?, student: Boolean, picked: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val info = findPosition(position)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().then(if (info != null) Modifier.clickable { open = !open } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Text(position, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3)
            if (info != null) { Spacer(Modifier.width(4.dp)); Icon(VIcons.Help, contentDescription = "About this position", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.weight(1f))
            if (student) {
                if (mine?.voted == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(VIcons.Check, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Voted", fontSize = 12.sp, color = GREEN, fontWeight = FontWeight.Medium, maxLines = 1)
                } else if (picked) Text("Selected", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                else Text("Not voted yet", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (count == 1) "1 candidate" else "$count candidates", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open && info != null) Text(info.duty, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CandidateCard(c: CandidateDto, votedForThis: Boolean, onOpen: () -> Unit, picked: Boolean = false) {
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Open profile of ${c.fullName}") { onOpen() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CandidateAvatar(c, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ellipsize(c.fullName, 28), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                ProgramChip(c.program)
                if (c.manifesto.isNotBlank()) Text(c.manifesto, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (votedForThis) Text("Your vote", fontSize = 12.sp, color = GREEN, fontWeight = FontWeight.Bold, maxLines = 1)
                else if (picked) Text("Selected, not sent yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                if (c.flagged) Text("Manifesto flagged for review", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CredRing(c.credibilityScore, 48.dp, 4.dp)
                Text("credibility", fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CandidateDetail(c: CandidateDto, vm: AppVM, onBack: () -> Unit, onVote: () -> Unit) {
    val isStudent = vm.user?.role == "student"
    val isAdmin = vm.user?.role == "admin"
    val open = vm.election?.status == "open"
    val mine = vm.voteStatus?.positions?.firstOrNull { posKey(it.position) == posKey(c.position) }
    val voted = mine?.voted == true  // already voted for THIS position
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Icon(VIcons.Back, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back to candidates") }
        if (isStudent && open && !voted) StepStrip(1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CandidateAvatar(c, 80.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ellipsize(c.fullName, 40), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(c.position, maxLines = 3)
                Text(ellipsize(c.program, 30), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CredRing(c.credibilityScore, 64.dp, 6.dp)
        }
        if (c.flagged) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
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
        Card(Modifier.fillMaxWidth()) {
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
        val picked = vm.picks[posKey(c.position)] == c.id
        if (isStudent) Button(onClick = { if (picked) vm.unpick(c.position) else onVote() }, enabled = open && !voted, modifier = Modifier.fillMaxWidth(),
            colors = if (picked) ButtonDefaults.buttonColors(containerColor = GREEN) else ButtonDefaults.buttonColors()) {
            Text(when {
                voted && mine?.candidateId == c.id -> "You voted for ${ellipsize(c.fullName, 20)}"
                voted -> "You already voted for ${c.position}"
                !open -> "Voting is not open"
                picked -> "Selected. Tap to undo"
                else -> "Select ${ellipsize(c.fullName, 22)} for ${c.position}"
            }, maxLines = 2)
        }
        if (isStudent && open && !voted) Text("Selecting does not send your vote. You review everything first.", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Vote receipt (one line per position) ----------------
@Composable
fun ReceiptScreen(vm: AppVM, onResults: () -> Unit, onDone: () -> Unit) {
    val vs = vm.voteStatus
    val clip = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val complete = vs?.complete == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        if (complete) StepStrip(4)
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(VIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
        }
        Text(if (complete) "All votes recorded" else "Your votes so far", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(vm.election?.title?.let { ellipsize(it, 40) } ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        vm.user?.respondentCode?.let { Text("Your respondent code: $it. Write it on the post-pilot survey.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        vs?.positions?.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.position, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, modifier = Modifier.weight(1f))
                        Text(if (p.voted) "Voted" else "Not voted yet", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                            color = if (p.voted) GREEN else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.voted) {
                        Text(ellipsize(p.candidateName ?: "", 32), fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(p.transactionId ?: "", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        OutlinedButton(onClick = { clip.setText(AnnotatedString(p.transactionId ?: "")); vm.message = "Transaction ID copied" }) {
                            Icon(VIcons.Copy, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy ID", maxLines = 1)
                        }
                    }
                }
            }
        }
        Text("Each code is a SHA-256 hash made from your student number, the election and the position. Keep them as proof that your votes were recorded.",
            fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onResults, modifier = Modifier.fillMaxWidth()) { Text("View live results") }
        vm.election?.surveyUrl?.let { link ->
            if (complete) OutlinedButton(onClick = { uri.openUri(link) }, modifier = Modifier.fillMaxWidth()) { Text("Open the post-pilot survey") }
        }
        OutlinedButton(onClick = { printHtml(ctx, "VotEra vote receipt", receiptHtml(vm)) }, modifier = Modifier.fillMaxWidth()) { Text("Print or save as PDF") }
        OutlinedButton(onClick = { vm.showVerify = true }, modifier = Modifier.fillMaxWidth()) { Text("Verify a receipt") }
        if (complete) OutlinedButton(onClick = { vm.showFeedback = true }, modifier = Modifier.fillMaxWidth()) { Text("Rate VotEra") }
        TextButton(onClick = onDone) { Text(if (complete) "Done" else "Back to the ballot") }
    }
}

// ---------------- Side-by-side comparison ----------------
@Composable
fun CompareTab(vm: AppVM) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !vm.matchMode, onClick = { vm.matchMode = false }, leadingIcon = { Icon(VIcons.Compare, contentDescription = null, Modifier.size(16.dp)) }, label = { Text("Side by side", maxLines = 1) })
            FilterChip(selected = vm.matchMode, onClick = { vm.matchMode = true }, leadingIcon = { Icon(VIcons.Check, contentDescription = null, Modifier.size(16.dp)) }, label = { Text("Match me", maxLines = 1) })
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
                    label = { Text(ellipsize(c.fullName, 18), maxLines = 1) })
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
                                    Text(ellipsize(c.fullName, 18), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(ellipsize(c.program, 18), fontSize = 13.sp, maxLines = 1)
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

// ---------------- Announcements: posts with categories, filters and pictures ----------------
@Composable
fun UpdatesTab(vm: AppVM) {
    val shown = visiblePosts(vm)
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.announcements.isNotEmpty()) item { PostFilters(vm) }
            if (shown.isEmpty()) {
                if (!vm.loaded) items(3) { SkeletonCard() }
                else item { Empty(if (vm.announcements.isEmpty()) "No announcements yet. Pull down to refresh." else "No update matches. Try another category or clear the search.") }
            }
            items(shown, key = { it.id }) { a -> PostCard(a) }
        }
    }
}

// ---------------- Results: turnout, bar chart, pie chart (totals only) ----------------
@Composable
fun PieChart(rows: List<ResultRowDto>) {
    val total = rows.sumOf { it.votes }
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(if (Prefs.reduceMotion) 1 else 800)) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp).clearAndSetSemantics { contentDescription = "Donut chart of $total votes" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 34.dp.toPx()
                val box = Size(this.size.width - s, this.size.height - s)
                val topLeft = Offset(s / 2, s / 2)
                if (total == 0) drawArc(color = empty, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(width = s))
                else {
                    var start = -90f
                    rows.forEach { r ->
                        val sweep = 360f * r.votes / total * grow.value
                        if (sweep > 0f) drawArc(color = PALETTE[r.candidateId % PALETTE.size], startAngle = start, sweepAngle = sweep, useCenter = false,
                            topLeft = topLeft, size = box, style = Stroke(width = s))
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$total", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(if (total == 1) "vote" else "votes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (!showBar) { Box(Modifier.size(10.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp)) }
            if (candidate != null) CandidateAvatar(candidate, 32.dp) else if (showBar) Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text("${r.rank}. ${ellipsize(r.fullName, 22)}", Modifier.weight(1f), maxLines = 1, fontWeight = if (leader) FontWeight.Bold else FontWeight.Normal)
            if (leader) {
                Text("Leading", fontSize = 11.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(50)).background(GOLD)
                    .padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("${r.votes} votes (${fmt(r.percent)}%)", maxLines = 1, fontSize = 13.sp)
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
    var picked by remember { mutableStateOf<String?>(null) }
    val res = vm.results
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        if (res == null) {
            if (!vm.loaded) Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { SkeletonCard(); SkeletonCard(); SkeletonCard() }
            else Empty("No results yet. Pull down to refresh.")
        } else {
            val t = res.turnout
            val tp by animateFloatAsState((t.turnoutPercent / 100).toFloat().coerceIn(0f, 1f), animationSpec = tween(600), label = "turnout")
            val positions = res.positions.ifEmpty { res.results.map { it.position }.distinct() }
            val pos = picked?.takeIf { it in positions } ?: positions.firstOrNull()
            val rows = res.results.filter { pos == null || it.position == pos }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {
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
                if (positions.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    positions.forEach { p -> FilterChip(selected = p == pos, onClick = { picked = p }, label = { Text(ellipsize(posShort(p), 18), maxLines = 1) }) }
                }
                if (pos != null) Text(pos, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "bar", onClick = { mode = "bar" }, leadingIcon = { Icon(VIcons.Bars, contentDescription = null, Modifier.size(16.dp)) }, label = { Text("Bar chart", maxLines = 1) })
                    FilterChip(selected = mode == "pie", onClick = { mode = "pie" }, leadingIcon = { Icon(VIcons.Dashboard, contentDescription = null, Modifier.size(16.dp)) }, label = { Text("Donut chart", maxLines = 1) })
                }
                if (mode == "pie") key(pos) { PieChart(rows) }
                rows.forEach { r -> ResultRow(r, vm.candidates.firstOrNull { it.id == r.candidateId }, showBar = mode == "bar") }
                Text("Totals only. Individual choices are not shown to other students.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Three-step guide on the student home screen. */
@Composable
fun HowToVote(done: Boolean, open: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How to vote", style = MaterialTheme.typography.titleMedium)
            listOf(
                Triple("1", "Read the candidates", "Tap a candidate to read the platform. Use the Compare tab to see two or more side by side."),
                Triple("2", if (done) "You have voted" else if (open) "Cast your vote" else "Voting opens soon", if (done) "Your receipt is saved on this screen." else "Open a candidate and tap Select. When you have chosen, tap Review ballot and submit. You vote once for each position, such as President and Vice President."),
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

// ---------------- Ballot bar and review screen ----------------
/** Shows how many positions are selected and which one is next, with a button to review before sending. */
@Composable
private fun BallotBar(vm: AppVM) {
    val positions = vm.voteStatus?.positions ?: return
    val left = positions.filter { !it.voted }
    val selected = left.count { vm.picks[posKey(it.position)] != null }
    val next = left.firstOrNull { vm.picks[posKey(it.position)] == null }
    Card(Modifier.fillMaxWidth().tourTarget("ballot"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your ballot", fontWeight = FontWeight.Bold, maxLines = 1)
                Text("$selected of ${left.size} open positions selected", fontSize = 12.sp, maxLines = 1)
                if (next != null) TextButton(onClick = { vm.positionTab = posKey(next.position) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Next: ${next.position}", fontSize = 12.sp, maxLines = 2)
                }
            }
            Button(onClick = { vm.showReview = true }, enabled = selected > 0) { Text("Review ballot", maxLines = 1) }
        }
    }
}

/** Last check before anything is sent. Voted positions are locked; selected ones can be changed; the rest can be skipped for now. */
@Composable
private fun ReviewBallotSheet(vm: AppVM, onChange: () -> Unit) {
    val positions = (vm.voteStatus?.positions ?: emptyList()).sortedBy { positionRank(it.position) }
    val count = positions.count { !it.voted && vm.picks[posKey(it.position)] != null }
    ModalBottomSheet(onDismissRequest = { vm.showReview = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepStrip(2)
            Text("Review your ballot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            positions.forEach { p ->
                val id = vm.picks[posKey(p.position)]
                val cand = id?.let { i -> vm.candidates.firstOrNull { it.id == i } }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.position, fontSize = 12.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when {
                            p.voted -> ellipsize(p.candidateName ?: "", 28)
                            cand != null -> ellipsize(cand.fullName, 28)
                            else -> "No selection yet"
                        }, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    when {
                        p.voted -> Text("Voted", fontSize = 12.sp, color = GREEN, fontWeight = FontWeight.Medium, maxLines = 1)
                        cand != null -> TextButton(onClick = { vm.unpick(p.position) }) { Text("Remove", maxLines = 1) }
                        else -> Text("Skipped", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("A recorded vote cannot be changed. Positions you skip stay open until the election closes.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onChange, modifier = Modifier.weight(1f)) { Text("Go back", maxLines = 1) }
                Button(onClick = { vm.submitBallot() }, enabled = count > 0 && !vm.busy, modifier = Modifier.weight(1f)) {
                    Text(if (count == 1) "Submit 1 vote" else "Submit $count votes", maxLines = 1)
                }
            }
        }
    }
}
