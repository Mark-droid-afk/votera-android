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
import okhttp3.OkHttpClient
import okhttp3.Request
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
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// Default server address. Emulator: http://10.0.2.2:8000/ . Real phone: your laptop's Wi-Fi IP.
// It can also be changed on the login screen or in Settings (Server address) without rebuilding the app.
const val BASE_URL = "http://192.168.1.12:8000/"

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

    fun init(context: Context) {
        val p = context.getSharedPreferences("votera_prefs", Context.MODE_PRIVATE)
        sp = p
        theme = p.getString("theme", "system") ?: "system"
        textScale = p.getFloat("text_scale", 1.0f)
        live = p.getBoolean("live", true)
        rememberSn = p.getBoolean("remember_sn", false)
        savedSn = p.getString("saved_sn", "") ?: ""
    }

    fun setTheme(v: String) { theme = v; sp?.edit()?.putString("theme", v)?.apply() }
    fun setTextScale(v: Float) { textScale = v; sp?.edit()?.putFloat("text_scale", v)?.apply() }
    fun setLive(v: Boolean) { live = v; sp?.edit()?.putBoolean("live", v)?.apply() }
    fun setRememberSn(v: Boolean) {
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
        setTheme("system"); setTextScale(1.0f); setLive(true); setRememberSn(false)
        ServerConfig.reset()
    }
}

object Session { @Volatile var token: String? = null }

// Field names are camelCase here and become snake_case in JSON (naming policy below).
// portal is "student" or "admin": which login screen was used. The server rejects a mismatch (RBAC).
data class LoginReq(val studentNumber: String, val password: String, val portal: String)
data class LoginRes(val otpToken: String, val devOtp: String)
data class OtpReq(val otpToken: String, val code: String)
data class UserDto(val studentNumber: String, val fullName: String, val program: String, val role: String)
data class TokenRes(val accessToken: String, val user: UserDto)
data class ElectionDto(val id: Int, val title: String, val status: String, val surveyUrl: String? = null)
data class StatusReq(val status: String)
data class CandidateDto(
    val id: Int, val fullName: String, val program: String, val position: String,
    val manifesto: String, val achievements: String, val leadership: String, val community: String,
    val highestSimilarity: Double?, val originalityScore: Double?, val flagged: Boolean,
    val achievementsScore: Double?, val leadershipScore: Double?, val communityScore: Double?,
    val credibilityScore: Double?,
    val mostSimilarName: String? = null  // sent to administrators only
)
data class CandidateReq(
    val fullName: String, val program: String, val position: String, val manifesto: String,
    val achievements: String, val leadership: String, val community: String,
    val achievementsScore: Double?, val leadershipScore: Double?, val communityScore: Double?
)
data class ScoresReq(val achievementsScore: Double, val leadershipScore: Double, val communityScore: Double)
data class FlaggedRes(val threshold: Double, val flagged: List<CandidateDto>)
data class AnnouncementDto(val id: Int, val title: String, val body: String, val createdAt: String)
data class AnnouncementReq(val title: String, val body: String)
data class VoteStatusRes(val hasVoted: Boolean, val transactionId: String?)
data class VoteReq(val candidateId: Int)
data class VoteRes(val message: String, val transactionId: String)
data class TurnoutDto(val votesCast: Int, val eligibleVoters: Int, val turnoutPercent: Double)
data class ResultRowDto(val rank: Int, val candidateId: Int, val fullName: String, val votes: Int, val percent: Double)
data class ResultsRes(val election: ElectionDto, val turnout: TurnoutDto, val results: List<ResultRowDto>)

// Administrator-only data
data class VoteAuditDto(
    val studentNumber: String, val fullName: String, val program: String,
    val candidateId: Int, val candidateName: String, val transactionId: String, val castAt: String
)
data class VoterDto(val studentNumber: String, val fullName: String, val program: String, val hasVoted: Boolean, val votedAt: String?)
data class TurnoutRowDto(val program: String, val eligible: Int, val voted: Int, val percent: Double)
data class DeletedRes(val deleted: Int)
data class ResetRes(val votesRemoved: Int, val status: String)
data class DemoReq(val count: Int)
data class DemoRes(val added: Int)
data class HealthRes(val status: String, val election: String?)

interface VoteraApi {
    @GET("health") suspend fun health(): HealthRes
    @POST("auth/login") suspend fun login(@Body b: LoginReq): LoginRes
    @POST("auth/verify-otp") suspend fun verifyOtp(@Body b: OtpReq): TokenRes
    @GET("election") suspend fun election(): ElectionDto
    @PUT("admin/election/status") suspend fun setStatus(@Body b: StatusReq): ElectionDto
    @GET("candidates") suspend fun candidates(): List<CandidateDto>
    @PUT("admin/candidates/{id}/scores") suspend fun setScores(@Path("id") id: Int, @Body b: ScoresReq): CandidateDto
    @POST("admin/candidates") suspend fun addCandidate(@Body b: CandidateReq): CandidateDto
    @PUT("admin/candidates/{id}") suspend fun editCandidate(@Path("id") id: Int, @Body b: CandidateReq): CandidateDto
    @DELETE("admin/candidates/{id}") suspend fun deleteCandidate(@Path("id") id: Int): DeletedRes
    @GET("admin/flagged") suspend fun flagged(): FlaggedRes
    @GET("admin/votes") suspend fun votes(): List<VoteAuditDto>
    @GET("admin/voters") suspend fun voters(): List<VoterDto>
    @GET("admin/turnout") suspend fun turnoutByProgram(): List<TurnoutRowDto>
    @GET("admin/export/votes.csv") suspend fun exportVotesCsv(): ResponseBody
    @POST("admin/election/reset") suspend fun resetElection(): ResetRes
    @POST("admin/demo/fill") suspend fun demoFill(@Body b: DemoReq): DemoRes
    @GET("announcements") suspend fun announcements(): List<AnnouncementDto>
    @POST("announcements") suspend fun postAnnouncement(@Body b: AnnouncementReq): AnnouncementDto
    @GET("vote/status") suspend fun voteStatus(): VoteStatusRes
    @POST("vote") suspend fun vote(@Body b: VoteReq): VoteRes
    @GET("results") suspend fun results(): ResultsRes
}

object Net {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
    suspend fun connect(token: String, onReady: () -> Unit, onChanged: () -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val wsUrl = ServerConfig.baseUrl.replaceFirst("http", "ws") + "ws"
            val socket = Net.wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"token":"$token"}""")  // first message authenticates the connection
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val type = JsonParser.parseString(text).asJsonObject.get("type").asString
                        if (type == "ready") onReady() else if (type == "changed") onChanged()
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
