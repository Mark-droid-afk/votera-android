package com.votera.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// Default server address: the hosted VotEra backend. For local testing use http://10.0.2.2:8000/ (emulator)
// or your laptop's Wi-Fi IP. It can also be changed on the login screen (tap the logo 5 times) or in Settings
// (Server address) without rebuilding the app.
const val BASE_URL = "https://votera-backend.onrender.com/"

/** Holds the server address the app talks to. The saved address on the phone overrides BASE_URL. */
object ServerConfig {
    private var prefs: SharedPreferences? = null
    @Volatile var baseUrl: String = BASE_URL
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("votera_server", Context.MODE_PRIVATE)
        prefs = p
        p.getString("base_url", null)?.let { if (it.isNotBlank()) baseUrl = it }
    }

    /** Accepts "192.168.1.12", "192.168.1.12:8000", "http://192.168.1.12:8000" or an https address. Returns null if invalid. */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val schemeEnd = s.indexOf("://") + 3
        val hostEnd = s.indexOf('/', schemeEnd).let { if (it == -1) s.length else it }
        val authority = s.substring(schemeEnd, hostEnd)
        // A bare IP address with no port gets the backend's default port.
        if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(authority)) s = s.substring(0, hostEnd) + ":8000" + s.substring(hostEnd)
        if (!s.endsWith("/")) s += "/"
        return if (s.toHttpUrlOrNull() != null) s else null
    }

    fun save(url: String) {
        baseUrl = url
        prefs?.edit()?.putString("base_url", url)?.apply()
    }

    fun reset() {
        baseUrl = BASE_URL
        prefs?.edit()?.remove("base_url")?.apply()
    }
}

/** Settings stored on the phone. Each value is Compose state, so the screens update the moment it changes. */
object Prefs {
    private var sp: SharedPreferences? = null
    var theme by mutableStateOf("system")        // "system", "light" or "dark"
        private set
    var textScale by mutableStateOf(1.0f)        // 0.9 small, 1.0 default, 1.15 large
        private set
    var live by mutableStateOf(true)             // live updates through the WebSocket
        private set
    var rememberSn by mutableStateOf(false)      // keep the student number on the login screen
        private set
    var savedSn by mutableStateOf("")
        private set
    var highContrast by mutableStateOf(false)    // stronger colors and borders for low vision
        private set
    var reduceMotion by mutableStateOf(false)    // no sliding transitions
        private set
    var welcomeSeen by mutableStateOf(false)     // the welcome tour has been shown once
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("votera_prefs", Context.MODE_PRIVATE)
        sp = p
        theme = p.getString("theme", "system") ?: "system"
        textScale = p.getFloat("text_scale", 1.0f).coerceAtMost(1.15f)  // the old "Extra large" size was removed
        live = p.getBoolean("live", true)
        rememberSn = p.getBoolean("remember_sn", false)
        savedSn = p.getString("saved_sn", "") ?: ""
        highContrast = p.getBoolean("high_contrast", false)
        reduceMotion = p.getBoolean("reduce_motion", false)
        welcomeSeen = p.getBoolean("welcome_seen", false)
    }

    fun updateTheme(v: String) { theme = v; sp?.edit()?.putString("theme", v)?.apply() }
    fun updateTextScale(v: Float) { textScale = v; sp?.edit()?.putFloat("text_scale", v)?.apply() }
    fun updateLive(v: Boolean) { live = v; sp?.edit()?.putBoolean("live", v)?.apply() }
    fun updateHighContrast(v: Boolean) { highContrast = v; sp?.edit()?.putBoolean("high_contrast", v)?.apply() }
    fun markWelcomeSeen() { welcomeSeen = true; sp?.edit()?.putBoolean("welcome_seen", true)?.apply() }
    fun updateReduceMotion(v: Boolean) { reduceMotion = v; sp?.edit()?.putBoolean("reduce_motion", v)?.apply() }
    fun updateRememberSn(v: Boolean) {
        rememberSn = v
        if (!v) savedSn = ""
        sp?.edit()?.putBoolean("remember_sn", v)?.putString("saved_sn", if (v) savedSn else "")?.apply()
    }
    fun saveSn(v: String) { if (rememberSn) { savedSn = v; sp?.edit()?.putString("saved_sn", v)?.apply() } }

    // First-login tutorial: remembered per account, so it shows on the first login only.
    fun tutorialSeen(sn: String): Boolean = sp?.getStringSet("tutorial_done", emptySet())?.contains(sn.uppercase()) == true
    fun markTutorial(sn: String) {
        val cur = sp?.getStringSet("tutorial_done", emptySet()) ?: emptySet()
        sp?.edit()?.putStringSet("tutorial_done", cur.toMutableSet().apply { add(sn.uppercase()) })?.apply()
    }

    /** Restores every setting, including the server address, to its default. */
    fun resetAll() {
        updateTheme("system"); updateTextScale(1.0f); updateLive(true); updateRememberSn(false); updateHighContrast(false); updateReduceMotion(false)
        ServerConfig.reset()
    }
}

object Session { @Volatile var token: String? = null }

// Field names are camelCase here and become snake_case in JSON (naming policy below).
// portal is "student" or "admin": which login screen was used. The server rejects a mismatch (RBAC).
data class LoginReq(val studentNumber: String, val password: String, val portal: String)
data class LoginRes(val otpToken: String, val devOtp: String)
data class OtpReq(val otpToken: String, val code: String)
data class UserDto(val studentNumber: String, val fullName: String, val program: String, val role: String, val respondentCode: String? = null)
data class TokenRes(val accessToken: String, val user: UserDto)
data class ElectionDto(val id: Int, val title: String, val status: String, val surveyUrl: String? = null,
                       val closesAt: String? = null, val secondsLeft: Int? = null)
data class StatusReq(val status: String)
data class CandidateDto(
    val id: Int, val fullName: String, val program: String, val position: String,
    val manifesto: String, val achievements: String, val leadership: String, val community: String,
    val highestSimilarity: Double?, val originalityScore: Double?, val flagged: Boolean,
    val achievementsScore: Double?, val leadershipScore: Double?, val communityScore: Double?,
    val credibilityScore: Double?,
    val mostSimilarName: String? = null,  // sent to administrators only
    val photoVersion: Int = 0,            // 0 = no photo; changes whenever the photo is replaced
    val videoUrl: String? = null,         // optional link to a platform video
    val qa: String? = null,               // optional questions and answers (plain text)
    val proofCount: Int = 0,              // number of certificate pictures (students count only the verified ones)
    val proofAchievements: Int = 0,       // verified documents per category
    val proofLeadership: Int = 0,
    val proofCommunity: Int = 0,
    val missingProof: List<String>? = null,  // administrators only: written claims with no verified document yet
    val published: Boolean = true         // false = draft, hidden from students
)
data class CandidateReq(
    val fullName: String, val program: String, val position: String, val manifesto: String,
    val achievements: String, val leadership: String, val community: String,
    val achievementsScore: Double?, val leadershipScore: Double?, val communityScore: Double?,
    val videoUrl: String? = null, val qa: String? = null, val published: Boolean? = null
)
data class PublishedReq(val published: Boolean)
data class VoteLineDto(val position: String, val candidateName: String, val transactionId: String, val castAt: String? = null)
data class VoterProfileDto(val studentNumber: String, val fullName: String, val program: String, val respondentCode: String? = null,
                           val lastLogin: String? = null, val lastSeen: String? = null, val online: Boolean = false,
                           val positionsVoted: Int = 0, val positionsTotal: Int = 0, val votes: List<VoteLineDto> = emptyList())
data class NewPasswordDto(val studentNumber: String, val fullName: String, val password: String)
data class SessionDto(val studentNumber: String, val fullName: String, val role: String, val program: String, val lastLogin: String? = null, val lastSeen: String? = null)
data class SessionsRes(val minutes: Int = 15, val sessions: List<SessionDto> = emptyList())
data class EndedRes(val ended: String)
data class QuotaRowDto(val program: String, val target: Int, val started: Int, val finished: Int, val remaining: Int)
data class QuotaRes(val positions: Int = 0, val targetTotal: Int = 0, val finishedTotal: Int = 0, val startedTotal: Int = 0, val rows: List<QuotaRowDto> = emptyList())
data class SimPersonDto(val id: Int, val fullName: String, val position: String, val manifesto: String)
data class SharedTermDto(val term: String, val weight: Double)
data class SimilarityRes(val similarity: Double, val flagged: Boolean, val threshold: Double, val candidate: SimPersonDto,
                         val other: SimPersonDto? = null, val sharedTerms: List<SharedTermDto> = emptyList())
data class ScoresReq(val achievementsScore: Double, val leadershipScore: Double, val communityScore: Double)
data class FlaggedRes(val threshold: Double, val flagged: List<CandidateDto>)
data class AnnouncementDto(val id: Int, val title: String, val body: String, val createdAt: String,
                           val category: String = "General", val pinned: Boolean = false, val imageVersion: Int = 0,
                           val publishAt: String? = null, val scheduled: Boolean = false)
data class AnnouncementReq(val title: String, val body: String, val category: String = "General", val pinned: Boolean = false, val publishInMinutes: Int? = null)
data class AnnouncementEditReq(val title: String? = null, val body: String? = null, val category: String? = null, val pinned: Boolean? = null)
/** One line of the ballot: a position and what this student chose for it (null until they vote). */
data class PositionVoteDto(val position: String, val voted: Boolean, val candidateId: Int? = null, val candidateName: String? = null,
                           val transactionId: String? = null, val votedAt: String? = null)
data class VoteStatusRes(val hasVoted: Boolean, val complete: Boolean = false, val transactionId: String? = null,
                         val positions: List<PositionVoteDto> = emptyList())
data class VoteReq(val candidateId: Int)
data class VerifyRes(val valid: Boolean, val message: String, val position: String? = null, val castAt: String? = null,
                     val yours: Boolean = false, val candidateName: String? = null)
data class FeedbackReq(val ease: Int, val clarity: Int, val trust: Int, val overall: Int, val comment: String)
data class FeedbackMine(val submitted: Boolean, val ease: Int = 0, val clarity: Int = 0, val trust: Int = 0, val overall: Int = 0, val comment: String = "")
data class FeedbackComment(val program: String = "", val overall: Int = 0, val comment: String = "")
data class FeedbackSummary(val count: Int = 0, val ease: Double = 0.0, val clarity: Double = 0.0, val trust: Double = 0.0, val overall: Double = 0.0,
                           val comments: List<FeedbackComment> = emptyList())
data class MsgRes(val message: String = "")
data class VoteRes(val message: String, val transactionId: String, val position: String = "", val complete: Boolean = false)

/** Announcement categories, in the order the filter chips show them. */
val POST_CATEGORIES = listOf("General", "Schedule", "Reminder", "Rules", "Results", "Urgent")
data class TurnoutDto(val votesCast: Int, val eligibleVoters: Int, val turnoutPercent: Double)
data class ResultRowDto(val position: String = "", val rank: Int, val candidateId: Int, val fullName: String, val votes: Int, val percent: Double)
data class ResultsRes(val election: ElectionDto, val turnout: TurnoutDto, val positions: List<String> = emptyList(), val results: List<ResultRowDto>)

// Administrator-only data
data class VoteAuditDto(
    val studentNumber: String, val fullName: String, val program: String,
    val candidateId: Int, val candidateName: String, val transactionId: String, val castAt: String,
    val position: String = ""
)
data class VoterDto(val studentNumber: String, val fullName: String, val program: String, val hasVoted: Boolean, val votedAt: String?,
                    val respondentCode: String? = null, val positionsVoted: Int = 0, val positionsTotal: Int = 0)
data class TurnoutRowDto(val program: String, val eligible: Int, val voted: Int, val percent: Double)
data class DeletedRes(val deleted: Int)
data class ResetRes(val votesRemoved: Int, val status: String)
data class DemoReq(val count: Int)
data class DemoRes(val added: Int)
/** A picture of a certificate that backs up a candidate's achievements, leadership or community claim. */
data class ProofDto(val id: Int, val candidateId: Int, val category: String, val caption: String, val status: String, val createdAt: String? = null,
                    val issuer: String = "", val issuedOn: String = "", val verifiedAt: String? = null, val verifiedBy: String? = null)
data class ProofPatchReq(val status: String? = null, val caption: String? = null, val issuer: String? = null, val issuedOn: String? = null)
data class ScheduleReq(val minutes: Int?)
data class CredentialsReq(val program: String?)
data class CredRow(val studentNumber: String, val fullName: String, val program: String, val respondentCode: String?, val password: String)
data class CredentialsRes(val count: Int, val rows: List<CredRow>)
data class ImportReq(val csv: String)
data class ImportErr(val row: Int, val studentNumber: String, val reason: String)
data class GeneratedPw(val studentNumber: String, val fullName: String, val password: String)
data class ImportRes(val created: Int, val skipped: Int, val errors: List<ImportErr>, val generated: List<GeneratedPw>)
data class HealthRes(val status: String, val election: String?)
data class AuditDto(val id: Int, val at: String, val actor: String, val role: String, val action: String, val detail: String, val ok: Boolean)
data class SelfCheckDto(val group: String, val name: String, val ok: Boolean, val detail: String)
data class SelfTestRes(val passed: Int, val total: Int, val checks: List<SelfCheckDto>)

interface VoteraApi {
    @GET("health") suspend fun health(): HealthRes
    @GET("health/db") suspend fun healthDb(): HealthRes
    @POST("auth/login") suspend fun login(@Body b: LoginReq): LoginRes
    @POST("auth/verify-otp") suspend fun verifyOtp(@Body b: OtpReq): TokenRes
    @GET("election") suspend fun election(): ElectionDto
    @PUT("admin/election/status") suspend fun setStatus(@Body b: StatusReq): ElectionDto
    @GET("candidates") suspend fun candidates(): List<CandidateDto>
    @PUT("admin/candidates/{id}/scores") suspend fun setScores(@Path("id") id: Int, @Body b: ScoresReq): CandidateDto
    @POST("admin/candidates") suspend fun addCandidate(@Body b: CandidateReq): CandidateDto
    @PUT("admin/candidates/{id}") suspend fun editCandidate(@Path("id") id: Int, @Body b: CandidateReq): CandidateDto
    @DELETE("admin/candidates/{id}") suspend fun deleteCandidate(@Path("id") id: Int): DeletedRes
    @GET("candidates/{id}/photo") suspend fun photo(@Path("id") id: Int): ResponseBody
    @PUT("admin/candidates/{id}/photo") suspend fun uploadPhoto(@Path("id") id: Int, @Body body: RequestBody): CandidateDto
    @DELETE("admin/candidates/{id}/photo") suspend fun deletePhoto(@Path("id") id: Int): CandidateDto
    @PUT("admin/election/schedule") suspend fun setSchedule(@Body b: ScheduleReq): ElectionDto
    @POST("admin/credentials/regenerate") suspend fun regenerateCredentials(@Body b: CredentialsReq): CredentialsRes
    @POST("admin/voters/import") suspend fun importVoters(@Body b: ImportReq): ImportRes
    @GET("admin/audit-log") suspend fun auditLog(@Query("limit") limit: Int, @Query("only_problems") onlyProblems: Boolean): List<AuditDto>
    @GET("admin/selftest") suspend fun selfTest(): SelfTestRes
    @GET("admin/flagged") suspend fun flagged(): FlaggedRes
    @GET("admin/votes") suspend fun votes(): List<VoteAuditDto>
    @GET("admin/voters") suspend fun voters(): List<VoterDto>
    @PUT("admin/candidates/{id}/published") suspend fun setPublished(@Path("id") id: Int, @Body b: PublishedReq): CandidateDto
    @GET("admin/voters/{sn}/profile") suspend fun voterProfile(@Path("sn") sn: String): VoterProfileDto
    @POST("admin/voters/{sn}/reset-password") suspend fun resetOnePassword(@Path("sn") sn: String): NewPasswordDto
    @GET("admin/quota") suspend fun quota(): QuotaRes
    @GET("admin/candidates/{id}/similarity") suspend fun similarity(@Path("id") id: Int): SimilarityRes
    @GET("admin/sessions") suspend fun sessions(): SessionsRes
    @POST("admin/sessions/{sn}/end") suspend fun endSession(@Path("sn") sn: String): EndedRes
    @GET("admin/turnout") suspend fun turnoutByProgram(): List<TurnoutRowDto>
    @GET("admin/export/votes.csv") suspend fun exportVotesCsv(): ResponseBody
    @POST("admin/election/reset") suspend fun resetElection(): ResetRes
    @POST("admin/demo/fill") suspend fun demoFill(@Body b: DemoReq): DemoRes
    @GET("candidates/{id}/proofs") suspend fun listProofs(@Path("id") id: Int): List<ProofDto>
    @GET("proofs/{id}/image") suspend fun proofImage(@Path("id") id: Int): ResponseBody
    @POST("admin/candidates/{id}/proofs") suspend fun uploadProof(@Path("id") id: Int, @Query("category") category: String, @Query("caption") caption: String,
        @Query("issuer") issuer: String, @Query("issued_on") issuedOn: String, @Body body: RequestBody): ProofDto
    @PATCH("admin/proofs/{id}") suspend fun patchProof(@Path("id") id: Int, @Body body: ProofPatchReq): ProofDto
    @DELETE("admin/proofs/{id}") suspend fun deleteProof(@Path("id") id: Int): MsgRes
    @POST("admin/demo/candidates") suspend fun demoCandidates(@retrofit2.http.Query("names_only") namesOnly: Boolean = false): DemoRes
    @GET("announcements") suspend fun announcements(): List<AnnouncementDto>
    @POST("announcements") suspend fun postAnnouncement(@Body b: AnnouncementReq): AnnouncementDto
    @PUT("admin/announcements/{id}") suspend fun editAnnouncement(@Path("id") id: Int, @Body b: AnnouncementEditReq): AnnouncementDto
    @DELETE("admin/announcements/{id}") suspend fun deleteAnnouncement(@Path("id") id: Int): DeletedRes
    @GET("announcements/{id}/image") suspend fun announcementImage(@Path("id") id: Int): ResponseBody
    @PUT("admin/announcements/{id}/image") suspend fun uploadAnnouncementImage(@Path("id") id: Int, @Body body: RequestBody): AnnouncementDto
    @DELETE("admin/announcements/{id}/image") suspend fun deleteAnnouncementImage(@Path("id") id: Int): AnnouncementDto
    @GET("vote/status") suspend fun voteStatus(): VoteStatusRes
    @POST("vote") suspend fun vote(@Body b: VoteReq): VoteRes
    @GET("results") suspend fun results(): ResultsRes
    @GET("vote/verify") suspend fun verifyReceipt(@Query("tx") tx: String): VerifyRes
    @POST("feedback") suspend fun sendFeedback(@Body b: FeedbackReq): MsgRes
    @GET("feedback/mine") suspend fun myFeedback(): FeedbackMine
    @GET("admin/feedback") suspend fun feedbackSummary(): FeedbackSummary
    @GET("admin/backup") suspend fun backup(): ResponseBody
}

object Net {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // long enough for the credentials sheet (hashes up to 250 passwords)
        .addInterceptor { chain ->  // attaches the JWT to every request
            val b = chain.request().newBuilder()
            Session.token?.let { b.header("Authorization", "Bearer $it") }
            chain.proceed(b.build())
        }.build()

    /** WebSocket client: no read timeout, and a ping every 20 seconds so a dead connection is noticed. */
    val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var cachedUrl = ""
    private var cachedApi: VoteraApi? = null

    /** Rebuilt automatically whenever the server address changes. */
    val api: VoteraApi
        @Synchronized get() {
            val url = ServerConfig.baseUrl
            val existing = cachedApi
            if (existing != null && cachedUrl == url) return existing
            val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
            val built = Retrofit.Builder().baseUrl(url).client(client)
                .addConverterFactory(GsonConverterFactory.create(gson)).build()
                .create(VoteraApi::class.java)
            cachedApi = built
            cachedUrl = url
            return built
        }
}

/** Live sync. The server pushes {"type":"changed","topics":[...]} whenever votes, status, candidates or
 *  announcements change. The app then re-fetches through the normal REST calls, so the server's role checks
 *  still decide what each person is allowed to see (students get totals only). */
object Live {
    /** Opens the socket and suspends until it closes or fails. Cancelling the caller closes the socket. */
    suspend fun connect(token: String, onReady: () -> Unit, onChanged: (Set<String>) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val wsUrl = ServerConfig.baseUrl.replaceFirst("http", "ws") + "ws"
            val socket = Net.wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"token":"$token"}""")  // first message authenticates the connection
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = JsonParser.parseString(text).asJsonObject
                        val type = obj.get("type").asString
                        if (type == "ready") onReady()
                        else if (type == "changed") {
                            val topics = obj.getAsJsonArray("topics")?.map { it.asString }?.toSet() ?: emptySet()
                            onChanged(topics)
                        }
                    } catch (_: Exception) { }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { socket.cancel() }
        }
}

/** Turns a failed call into a message a student can read (FastAPI sends {"detail": "..."}). */
fun errorMessage(t: Throwable): String = when (t) {
    is HttpException -> try {
        val body = t.response()?.errorBody()?.string().orEmpty()
        val d = JsonParser.parseString(body).asJsonObject.get("detail")
        if (d != null && d.isJsonPrimitive) d.asString else "Invalid input"
    } catch (e: Exception) { "Request failed (${t.code()})" }
    is IOException -> "Cannot reach the server at ${ServerConfig.baseUrl}. Check your connection and the server address."
    else -> t.message ?: "Something went wrong"
}

fun jpegBody(bytes: ByteArray): RequestBody = bytes.toRequestBody("image/jpeg".toMediaType())
