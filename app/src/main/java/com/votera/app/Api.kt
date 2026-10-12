package com.votera.app

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit

// Emulator: 10.0.2.2 reaches your laptop. Real phone: use your laptop's Wi-Fi IP, e.g. "http://192.168.1.10:8000/".
// After deploying the backend, use its https address instead.
const val BASE_URL = "http://192.168.1.12:8000/"

object Session { @Volatile var token: String? = null }

// Field names are camelCase here and become snake_case in JSON (naming policy below).
data class LoginReq(val studentNumber: String, val password: String)
data class LoginRes(val otpToken: String, val devOtp: String)
data class OtpReq(val otpToken: String, val code: String)
data class UserDto(val studentNumber: String, val fullName: String, val program: String, val role: String)
data class TokenRes(val accessToken: String, val user: UserDto)
data class ElectionDto(val id: Int, val title: String, val status: String)
data class StatusReq(val status: String)
data class CandidateDto(
    val id: Int, val fullName: String, val program: String, val position: String,
    val manifesto: String, val achievements: String, val leadership: String, val community: String,
    val highestSimilarity: Double?, val originalityScore: Double?, val flagged: Boolean,
    val achievementsScore: Double?, val leadershipScore: Double?, val communityScore: Double?,
    val credibilityScore: Double?
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

interface VoteraApi {
    @POST("auth/login") suspend fun login(@Body b: LoginReq): LoginRes
    @POST("auth/verify-otp") suspend fun verifyOtp(@Body b: OtpReq): TokenRes
    @GET("election") suspend fun election(): ElectionDto
    @PUT("admin/election/status") suspend fun setStatus(@Body b: StatusReq): ElectionDto
    @GET("candidates") suspend fun candidates(): List<CandidateDto>
    @PUT("admin/candidates/{id}/scores") suspend fun setScores(@Path("id") id: Int, @Body b: ScoresReq): CandidateDto
    @GET("admin/flagged") suspend fun flagged(): FlaggedRes
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

    val api: VoteraApi by lazy {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        Retrofit.Builder().baseUrl(BASE_URL).client(client)
            .addConverterFactory(GsonConverterFactory.create(gson)).build()
            .create(VoteraApi::class.java)
    }
}

/** Turns a failed call into a message a student can read (FastAPI sends {"detail": "..."}). */
fun errorMessage(t: Throwable): String = when (t) {
    is HttpException -> try {
        val body = t.response()?.errorBody()?.string().orEmpty()
        val d = JsonParser.parseString(body).asJsonObject.get("detail")
        if (d != null && d.isJsonPrimitive) d.asString else "Invalid input"
    } catch (e: Exception) { "Request failed (${t.code()})" }
    is IOException -> "Cannot reach the server. Check your connection and the server address."
    else -> t.message ?: "Something went wrong"
}
