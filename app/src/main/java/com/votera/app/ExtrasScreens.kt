package com.votera.app

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ---------------- Small helpers ----------------

/** Turns the server's UTC time ("2026-10-13T07:22:01Z") into the phone's local time. */
fun localTime(iso: String): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val d = parser.parse(iso.take(19))
    if (d != null) SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()).format(d) else iso
} catch (e: Exception) { iso.replace("T", " ").take(19) }

/** Adds https:// when the administrator typed a link without it. Blank means no link. */
fun normalizeUrl(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "https://$s"
}

private fun shareText(ctx: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Share"))
}

// ---------------- Print or save as PDF (Android's own print dialog, no permissions needed) ----------------
private var printHolder: WebView? = null  // keeps the page alive while the print dialog is open

fun htmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun pageHtml(title: String, body: String): String =
    "<html><head><meta charset=\"utf-8\"><style>" +
        "body{font-family:sans-serif;margin:24px;color:#222}h1{color:#720927;font-size:22px;margin-bottom:4px}" +
        "h2{font-size:16px;color:#720927;margin-top:22px;margin-bottom:6px}table{border-collapse:collapse;width:100%}" +
        "td,th{border:1px solid #bbb;padding:6px 8px;font-size:13px;text-align:left}th{background:#f1dce2}" +
        "small{color:#555}.mono{font-family:monospace;word-break:break-all}</style></head><body>" +
        "<h1>${htmlEscape(title)}</h1><small>VotEra research prototype, PUP Quezon City. Simulated election with fictional candidates.</small>" +
        body + "</body></html>"

/** Opens the system print dialog, where the person can print or choose "Save as PDF". */
fun printHtml(ctx: Context, jobName: String, html: String) {
    val web = WebView(ctx)
    printHolder = web
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

/** The vote receipt. It shows the transaction ID, never who the student voted for. */
fun receiptHtml(vm: AppVM): String {
    val u = vm.user
    val body = StringBuilder()
    body.append("<h2>Vote receipt</h2><table>")
    body.append("<tr><th>Election</th><td>${htmlEscape(vm.election?.title ?: "")}</td></tr>")
    body.append("<tr><th>Student</th><td>${htmlEscape(u?.fullName ?: "")} (${htmlEscape(u?.studentNumber ?: "")})</td></tr>")
    if (u?.respondentCode != null) body.append("<tr><th>Respondent code</th><td>${htmlEscape(u.respondentCode)}</td></tr>")
    body.append("</table><h2>Votes</h2><table><tr><th>Position</th><th>Candidate</th><th>Transaction ID (SHA-256)</th></tr>")
    vm.voteStatus?.positions?.filter { it.voted }?.sortedBy { positionRank(it.position) }?.forEach {
        body.append("<tr><td>${htmlEscape(it.position)}</td><td>${htmlEscape(it.candidateName ?: "")}</td><td class=\"mono\">${htmlEscape(it.transactionId ?: "")}</td></tr>")
    }
    body.append("</table><p><small>Keep this as proof that your votes were recorded. Each student votes once for each position.</small></p>")
    return pageHtml("VotEra vote receipt", body.toString())
}

/** The election report as a printable page. Same recorded data as the Report tab. */
fun reportHtml(vm: AppVM): String {
    val b = StringBuilder()
    val res = vm.results
    b.append("<p>${htmlEscape(vm.election?.title ?: "")} (status: ${htmlEscape(vm.election?.status ?: "-")})</p>")
    if (res != null) {
        b.append("<p>Turnout: ${res.turnout.votesCast} of ${res.turnout.eligibleVoters} eligible voters (${fmt(res.turnout.turnoutPercent)}%)</p>")
        resultGroups(res.results).forEach { (pos, rows) ->
            b.append("<h2>${htmlEscape(pos)}</h2><table><tr><th>Rank</th><th>Candidate</th><th>Votes</th><th>Share</th></tr>")
            rows.forEach { b.append("<tr><td>${it.rank}</td><td>${htmlEscape(it.fullName)}</td><td>${it.votes}</td><td>${fmt(it.percent)}%</td></tr>") }
            b.append("</table>")
        }
        b.append("<p><small>Counted with a hash map and ordered with Quick Sort, highest to lowest, separately for each position.</small></p>")
    }
    b.append("<h2>Turnout per program</h2><table><tr><th>Program</th><th>Voted</th><th>Eligible</th><th>Rate</th></tr>")
    vm.turnoutRows.forEach { b.append("<tr><td>${htmlEscape(it.program)}</td><td>${it.voted}</td><td>${it.eligible}</td><td>${fmt(it.percent)}%</td></tr>") }
    b.append("</table><h2>Credibility scores</h2><table><tr><th>Candidate</th><th>Originality</th><th>Achievements</th><th>Leadership</th><th>Community</th><th>Credibility</th></tr>")
    vm.candidates.forEach {
        b.append("<tr><td>${htmlEscape(it.fullName)}${if (it.flagged) " (flagged for review)" else ""}</td><td>${fmt(it.originalityScore)}</td>" +
            "<td>${fmt(it.achievementsScore)}</td><td>${fmt(it.leadershipScore)}</td><td>${fmt(it.communityScore)}</td><td>${fmt(it.credibilityScore)}</td></tr>")
    }
    b.append("</table><p><small>Credibility = originality x 35% + achievements x 30% + leadership x 20% + community x 15%. " +
        "Originality = 100 minus the highest cosine similarity x 100 (TF-IDF). A manifesto at 0.70 or higher is flagged for administrator review, which is not a rejection.</small></p>")
    return pageHtml("VotEra election report", b.toString())
}

// ---------------- Banners and loading placeholders ----------------
@Composable
fun InfoBanner(title: String, body: String?, actionLabel: String?, onAction: () -> Unit, onDismiss: (() -> Unit)?,
               warn: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = bg), elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg)
                if (!body.isNullOrBlank()) Text(body, fontSize = 12.sp, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
            if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(VIcons.Close, contentDescription = "Dismiss", tint = fg) }
        }
    }
}

/** A gray, softly pulsing card shown while the first load is still running. */
@Composable
fun SkeletonCard() {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    val c = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a * 0.35f)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c))
                Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
            }
        }
    }
}

// ---------------- Admin: activity log ----------------
private fun prettyAction(a: String): String = when (a) {
    "login" -> "Signed in"
    "login_failed" -> "Failed login"
    "password_ok" -> "Password accepted"
    "otp_failed" -> "Wrong OTP code"
    "election_status" -> "Election status changed"
    "vote_cast" -> "Vote cast"
    "announcement" -> "Announcement posted"
    "candidate_added" -> "Candidate added"
    "candidate_edited" -> "Candidate edited"
    "candidate_deleted" -> "Candidate removed"
    "election_reset" -> "Election reset"
    "demo_fill" -> "Demo votes added"
    "voters_import" -> "Voters imported"
    "schedule" -> "Closing time changed"
    "credentials_sheet" -> "Credentials sheet created"
    "selftest" -> "Self-test run"
    else -> a.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun AdminActivity(vm: AppVM) {
    val ctx = LocalContext.current
    // Reload when the filter changes, and whenever votes or the status change (so it follows the election live).
    LaunchedEffect(vm.auditOnlyProblems, vm.results?.turnout?.votesCast, vm.election?.status) { vm.loadAudit() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Activity log", style = MaterialTheme.typography.titleMedium)
                Text("A read-only record of sign-ins, failed attempts, status changes and administrator actions. It never shows passwords, " +
                    "one-time codes or who a student voted for. Times use your phone's time zone.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !vm.auditOnlyProblems, onClick = { vm.auditOnlyProblems = false }, label = { Text("All") })
                    FilterChip(selected = vm.auditOnlyProblems, onClick = { vm.auditOnlyProblems = true }, label = { Text("Problems only") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.loadAudit() }) { Text("Refresh") }
                }
                if (vm.auditLog.isNotEmpty()) TextButton(onClick = {
                    shareText(ctx, "VotEra activity log", vm.auditLog.joinToString("\n") {
                        "${localTime(it.at)} | ${it.actor} | ${prettyAction(it.action)} | ${it.detail}" + if (it.ok) "" else " [problem]"
                    })
                }) { Text("Share this log as text") }
            }
        }
        if (vm.auditLog.isEmpty()) item { Empty(if (vm.auditOnlyProblems) "No problems recorded." else "No activity yet.") }
        items(vm.auditLog, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape)
                        .background(if (a.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(prettyAction(a.action) + if (a.ok) "" else " (problem)", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = if (a.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        Text(listOf(a.actor, a.role).filter { it.isNotBlank() }.joinToString(" | "), fontSize = 12.sp)
                        if (a.detail.isNotBlank()) Text(a.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(localTime(a.at), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------- Admin: self-test ----------------
@Composable
fun AdminSelfTest(vm: AppVM) {
    val ctx = LocalContext.current
    val st = vm.selfTest
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("System self-test", style = MaterialTheme.typography.titleMedium)
        Text("Runs the project's core methods on known sample inputs and reports pass or fail: password hashing, one-time password, session token, " +
            "role-based access, login lockout, SHA-256 transaction ID, one student one vote, hash map tally, Quick Sort, TF-IDF with cosine similarity, " +
            "the originality formula, the 0.70 flag and the Weighted Sum Model. It does not change any election data, so it is safe to run during a demo.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.runSelfTest() }, enabled = !vm.selfTesting, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.selfTesting) "Running..." else if (st == null) "Run self-test" else "Run again")
        }
        if (vm.selfTesting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (st != null) {
            val all = st.passed == st.total
            Card(colors = CardDefaults.cardColors(containerColor = if (all) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${st.passed} of ${st.total} checks passed", fontWeight = FontWeight.Bold)
                    Text(if (all) "Every method returned the expected result." else "Some checks failed. See the list below.", fontSize = 12.sp)
                }
            }
            st.checks.groupBy { it.group }.forEach { (group, list) ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        list.forEach { c ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(if (c.ok) VIcons.Check else VIcons.Close, contentDescription = if (c.ok) "Passed" else "Failed",
                                    tint = if (c.ok) GREEN else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    if (c.detail.isNotBlank()) Text(c.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                shareText(ctx, "VotEra self-test", "VotEra self-test: ${st.passed} of ${st.total} checks passed\n\n" +
                    st.checks.joinToString("\n") { (if (it.ok) "PASS" else "FAIL") + " | ${it.group} | ${it.name} | ${it.detail}" })
            }, modifier = Modifier.fillMaxWidth()) { Text("Share results as text") }
        }
    }
}

// ---------------- Student: "Match me" (advisory only) ----------------
@Composable
fun MatchMe(vm: AppVM) {
    val labels = listOf("Originality", "Verified achievements", "Leadership experience", "Community involvement")
    val p = vm.priorities
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Match me", style = MaterialTheme.typography.titleMedium)
        Text("Move each slider to show how much that quality matters to you. The list re-orders using your own weights. " +
            "It is only a guide. It does not change the official Credibility Score, and it does not vote for you.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                labels.forEachIndexed { i, label ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 13.sp)
                        Text("${p[i].toInt()} of 10", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = p[i], onValueChange = { v -> vm.priorities = vm.priorities.toMutableList().also { it[i] = v } },
                        valueRange = 0f..10f, steps = 9)
                }
                TextButton(onClick = { vm.priorities = listOf(5f, 5f, 5f, 5f) }) { Text("Reset sliders") }
            }
        }
        val total = p.sum()
        val scored = vm.candidates.mapNotNull { c ->
            val s = listOf(c.originalityScore, c.achievementsScore, c.leadershipScore, c.communityScore)
            if (s.any { it == null }) null else c to s.map { it ?: 0.0 }
        }
        val ranked = scored.map { (c, s) ->
            val mine = if (total > 0f) s.indices.sumOf { s[it] * p[it] } / total else 0.0
            c to mine
        }.sortedByDescending { it.second }
        if (total <= 0f) Text("Set at least one slider above zero to see a match.", fontSize = 12.sp)
        else if (ranked.isEmpty()) Empty("No candidate has all four scores yet.")
        ranked.forEachIndexed { i, (c, mine) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                        CandidateAvatar(c, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ellipsize(c.fullName, 24), fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("Official credibility ${fmt(c.credibilityScore)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmt(mine), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress = { (mine / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val missing = vm.candidates.filter { c -> listOf(c.originalityScore, c.achievementsScore, c.leadershipScore, c.communityScore).any { it == null } }
        if (missing.isNotEmpty()) Text("Not included yet (scores still being verified): " + missing.joinToString { it.fullName }, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


// ---------------- Theme button, small design pieces ----------------
/** True when the screen is currently dark, whether that comes from the phone or from the setting. */
@Composable
fun isDarkNow(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Sun or moon icon that flips between light and dark mode. Used on the login, welcome and help screens. */
@Composable
fun ThemeToggleButton(tint: Color = LocalContentColor.current) {
    val dark = isDarkNow()
    IconButton(onClick = { Prefs.updateTheme(if (dark) "light" else "dark") }) {
        Icon(if (dark) VIcons.Sun else VIcons.Moon, contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode", tint = tint)
    }
}

/** Shortens text that is too long and ends it with dots, for example "BS - Entrepreneurship" becomes "BS - Entrep...". */
fun ellipsize(s: String, max: Int): String = if (s.length > max) s.take(max).trimEnd() + "..." else s

/** Cleans text typed into a field: collapses extra spaces and line breaks and stops it at a sensible length. */
fun cleanText(s: String, max: Int): String = s.replace(Regex("\\s+"), " ").trimStart().take(max)

fun programColor(program: String): Color = PALETTE[(program.hashCode() and Int.MAX_VALUE) % PALETTE.size]

/** Program name with its own accent color, so BSIT, BSCS and the rest are easy to tell apart. */
@Composable
fun ProgramChip(program: String) {
    val c = programColor(program)
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(ellipsize(program, 12), fontSize = 11.sp, maxLines = 1)
    }
}

/** Upcoming, Open, Closed as a small timeline. The current stage is bold. */
@Composable
fun ElectionTimeline(status: String, onColor: Color) {
    val steps = listOf("upcoming" to "Upcoming", "open" to "Open", "closed" to "Closed")
    val now = steps.indexOfFirst { it.first == status }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Election stage: ${steps[now].second}" }, verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (_, label) ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (i <= now) MaterialTheme.colorScheme.primary else onColor.copy(alpha = 0.3f)))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = if (i == now) FontWeight.Bold else FontWeight.Normal, color = onColor)
            if (i < steps.lastIndex) Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(2.dp).background(if (i < now) MaterialTheme.colorScheme.primary else onColor.copy(alpha = 0.3f)))
        }
    }
}

/** Choose, Review, Done. step = 1, 2 or 3 is the current step; 4 means all three are finished. */
@Composable
fun StepStrip(step: Int) {
    val labels = listOf("Choose", "Review", "Done")
    Row(Modifier.fillMaxWidth().semantics { contentDescription = if (step > 3) "Voting steps complete" else "Voting step $step of 3" }, verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l ->
            val n = i + 1
            val on = n <= step
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (n < step) Icon(VIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                else Text("$n", fontSize = 11.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            Text(l, fontSize = 12.sp, fontWeight = if (n == step) FontWeight.Bold else FontWeight.Normal)
            if (i < labels.lastIndex) Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp).background(if (n < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

// ---------------- Welcome tour (before login) ----------------
private data class WelcomePage(val icon: androidx.compose.ui.graphics.vector.ImageVector?, val title: String, val body: String, val points: List<String>)

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pages = remember {
        listOf(
            WelcomePage(null, "Welcome to VotEra", "A simulated student election, built for a research study at PUP Quezon City.", emptyList()),
            WelcomePage(VIcons.People, "Know your candidates", "Read every platform before you decide.",
                listOf("Open a candidate to see the platform, achievements and credibility score", "Compare candidates side by side", "Try Match me to see who fits what you care about")),
            WelcomePage(VIcons.Check, "Vote once, keep a receipt", "Voting takes three short steps.",
                listOf("Choose a candidate", "Review and confirm. A vote cannot be changed", "Keep your transaction ID as proof")),
            WelcomePage(VIcons.Bars, "Live results, your privacy", "Results update as votes come in.",
                listOf("Other students see totals only", "Candidates and data are fictional", "Handled under the Data Privacy Act of 2012 (R.A. 10173)"))
        )
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    BackHandler { onDone() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (last) Spacer(Modifier.height(48.dp)) else TextButton(onClick = onDone) { Text("Skip") }
                ThemeToggleButton()
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val p = pages[i]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (p.icon == null) Logo(210.dp)
                    else Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(p.body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.points.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            p.points.forEach { pt ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                    Spacer(Modifier.width(12.dp))
                                    Text(pt, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 22.dp else 8.dp, label = "dot")
                    Box(Modifier.padding(horizontal = 3.dp).height(8.dp).width(w).clip(RoundedCornerShape(4.dp))
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(if (last) "Get started" else "Next")
            }
        }
    }
}

// ---------------- Help center: FAQ, how the score works, privacy ----------------
@Composable
private fun FaqItem(q: String, a: String) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(q, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(if (open) "Hide" else "Show", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (open) Text(a, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MethodCard(n: String, title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(n, color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun HelpCenter(onBack: () -> Unit, onTour: () -> Unit) {
    BackHandler { onBack() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(MAROON).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(VIcons.Back, contentDescription = "Back", tint = Color.White) }
                Text("Help and privacy", Modifier.weight(1f), color = Color.White, fontSize = 18.sp)
                ThemeToggleButton(tint = Color.White)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Quick answers", style = MaterialTheme.typography.titleMedium)
                FaqItem("Why do I get a one-time password?", "Logging in needs your password and then a 6-digit code as a second check. In this prototype the code is shown on screen. A real system would send it by SMS or email.")
                FaqItem("Can I change my vote?", "No. Each student can vote once, and a vote cannot be changed after it is recorded. You get a receipt with a transaction ID.")
                FaqItem("What is the transaction ID?", "A SHA-256 hash made from your student number and the election. It does not show your student number, and you can keep it as proof that your vote was recorded.")
                FaqItem("What does a flagged manifesto mean?", "The manifesto is 70% or more similar to another candidate's. It is only flagged for an administrator to review. It is not a rejection and the candidate stays in the election.")
                FaqItem("Who can see how I voted?", "Other students see totals only. Election administrators can see the vote audit, which is part of how the system is checked.")
                FaqItem("The app says Reconnecting or No connection.", "The free server may be waking up, which can take up to a minute. The app keeps the last information it loaded and updates again when the connection returns. Pull down on a list to refresh.")
                FaqItem("Is this a real election?", "No. It is a simulated Supreme Student Council election with fictional candidates and sample voter accounts, made for a research study.")

                Spacer(Modifier.height(6.dp))
                Text("How the credibility score works", style = MaterialTheme.typography.titleMedium)
                MethodCard("1", "Originality (35%)", "TF-IDF turns each manifesto into numbers by weighting words that are rare across all manifestos. Cosine similarity then compares two manifestos. Originality = 100 minus the highest similarity x 100.")
                MethodCard("2", "Verified scores (30%, 20%, 15%)", "The administrator scores achievements (30%), leadership (20%) and community involvement (15%) from validated documents.")
                MethodCard("3", "Weighted Sum Model", "The four scores are multiplied by their weights and added up into one Credibility Score from 0 to 100.")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Weights", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                            listOf(35f to PALETTE[0], 30f to PALETTE[1], 20f to PALETTE[2], 15f to PALETTE[3]).forEach { (w, c) -> Box(Modifier.weight(w).fillMaxHeight().background(c)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("Originality 35", "Achievements 30", "Leadership 20", "Community 15").forEach { Text(it, fontSize = 10.sp) }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Your privacy", style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Data Privacy Act of 2012 (R.A. 10173)", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Taking part is voluntary. Personal and academic information is handled with strict confidentiality and results are reported in aggregate. " +
                            "Passwords are stored as bcrypt hashes, and all candidates and voters in this prototype are fictional.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                OutlinedButton(onClick = onTour, modifier = Modifier.fillMaxWidth()) { Text("Show the welcome tour again") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}


// ---------------- Positions (President, Vice President, Secretary, ...) ----------------
fun posKey(p: String): String = p.trim().replace(Regex("\\s+"), " ").lowercase()

/** A Student Council position with a short description of what it does (from the Commission on Election posters). */
data class SscPosition(val name: String, val abbr: String?, val duty: String)

val SSC_POSITIONS = listOf(
    SscPosition("President", null, "Leads the implementation of policies, rules and programs. Supervises the council and student organizations, presides over meetings, represents the student body in events and signs official documents. Also approves or modifies programs and projects, creates committees when needed and appoints their heads."),
    SscPosition("Vice President", null, "Assumes the duties of the President when the President is absent or unable. Assists in council matters, leads the External Affairs and Partnership Committee, oversees student organizations and links the council and the Council of Leaders. Also handles internal concerns involving officers and helps prepare the budget."),
    SscPosition("Secretary-General", null, "Leads the Publicity and Communications Committee and handles the council's documentation. Records meeting minutes, prepares the calendar of activities, maintains the Executive Board directory and makes sure accomplishment reports are documented. Also keeps the Executive Board updated."),
    SscPosition("Treasurer", null, "Keeps the financial records and handles the release of funds based on approved appropriations. Prepares and submits financial reports at the end of each semester and serves as the property custodian. Also performs other duties assigned by the President."),
    SscPosition("Auditor", null, "Checks the financial reports of the Treasurer and keeps an inventory of assets. Reports any financial discrepancies involving the council and student organizations to the President. Also performs other duties assigned by the President."),
    SscPosition("Publicity and Communications Councilor (PCC)", "PCC", "Prepares official documents such as letters, memoranda, minutes and announcements. Shares important information with students through appropriate platforms and coordinates with class and organization officers to carry out plans and activities."),
    SscPosition("Creatives and Media Councilor (CMC)", "CMC", "Works with the Publicity and Communications Councilor in creating publicity materials such as graphics and videos. Helps promote programs and activities through creative materials and assists with technical needs such as presentations and audio or sound systems."),
    SscPosition("Students' Rights and Welfare Councilor (STRAWC)", "STRAWC", "Protects the rights and welfare of students and helps uphold academic freedom. Manages the E-REPORT online helpdesk for student concerns, gathers and reports issues raised by students and recommends ways to address them. Also serves as the Legal Consultant."),
    SscPosition("Academics and Research Councilor (ARC)", "ARC", "Initiates programs and projects that support academic excellence, including student research activities. Promotes culture and arts initiatives and helps promote nationalistic, scientific and mass-oriented education. Also coordinates with research-related officers of student organizations."),
    SscPosition("Gender and Development Councilor (GADC)", "GADC", "Promotes a safe space for people with diverse sexual orientations, gender identities, gender expressions and sex characteristics. Develops programs and projects related to gender and development and coordinates with the Guidance and Counselling Office and other related organizations."),
    SscPosition("External Affairs and Partnership Councilor (EAPC)", "EAPC", "Seeks partnership and sponsorship opportunities to support programs and increase participation. Maintains a directory and coordinates with external affairs-related officers of student organizations to carry out plans and activities."),
    SscPosition("Logistics and Technical Councilor (LTC)", "LTC", "Conducts site visits and prepares floor plans before activities. Ensures the safety and proper condition of venues before, during and after events. Handles technical equipment such as desktops and projectors and coordinates with the Treasurer and Auditor for materials and supplies.")
)

/** Finds the standard position for what was typed, also accepting the abbreviation (PCC) and the old names "Secretary" and "SSC President". */
fun findPosition(p: String): SscPosition? {
    val k = posKey(p)
    if (k.isEmpty()) return null
    SSC_POSITIONS.firstOrNull { posKey(it.name) == k || it.abbr?.lowercase() == k }?.let { return it }
    return when (k) { "secretary", "secretary general" -> SSC_POSITIONS[2]; "ssc president" -> SSC_POSITIONS[0]; else -> null }
}

fun isCouncilor(p: String): Boolean = SSC_POSITIONS.indexOf(findPosition(p)) >= 5

/** Short label for tight spaces: the abbreviation for councilors (PCC, CMC, ...), otherwise the name. */
fun posShort(p: String): String = findPosition(p)?.abbr ?: p

/** Ballot order: the standard SSC list first, then custom positions A to Z. Same order as the server. */
fun positionRank(p: String): Int {
    val i = SSC_POSITIONS.indexOf(findPosition(p))
    if (i >= 0) return i
    val k = posKey(p)
    return when {
        k.contains("vice") && k.contains("president") -> 1
        k.contains("president") -> 0
        k.contains("treasurer") -> 3
        k.contains("auditor") -> 4
        else -> 100
    }
}

/** Candidates grouped by position, in ballot order. */
fun groupByPosition(list: List<CandidateDto>): List<Pair<String, List<CandidateDto>>> =
    list.groupBy { posKey(it.position).ifEmpty { "candidate" } }.values
        .map { g -> (g.first().position.trim().replace(Regex("\\s+"), " ").ifEmpty { "Candidate" }) to g }
        .sortedWith(compareBy({ positionRank(it.first) }, { posKey(it.first) }))

// ---------------- Updates: unique "ballot stub" post cards, categories and filters ----------------
fun categoryColor(c: String): Color = when (c) {
    "Urgent" -> Color(0xFFC62828)
    "Schedule" -> GOLD
    "Reminder" -> Color(0xFF1F6F8B)
    "Rules" -> MAROON
    "Results" -> GREEN
    else -> Color(0xFF777777)
}

/** "Just now", "5 min ago", "3 h ago", "2 d ago", then the date. */
fun timeAgo(iso: String): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val t = parser.parse(iso.take(19))!!.time
    val s = (System.currentTimeMillis() - t) / 1000
    when {
        s < 60 -> "Just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 7 * 86400 -> "${s / 86400} d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(java.util.Date(t))
    }
} catch (e: Exception) { iso.take(10) }

@Composable
fun CategoryChip(category: String) {
    val c = categoryColor(category)
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(category, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun PostImage(a: AnnouncementDto) {
    if (a.imageVersion <= 0) return
    var bmp by remember(a.id, a.imageVersion) { mutableStateOf(PostImageCache.get(a.id, a.imageVersion)) }
    LaunchedEffect(a.id, a.imageVersion) { if (bmp == null) bmp = PostImageCache.load(a.id, a.imageVersion) }
    val b = bmp
    val ratio = if (b != null) (b.width.toFloat() / b.height).coerceIn(0.9f, 1.91f) else 16f / 9f
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (b != null) Image(bitmap = b, contentDescription = "Picture for ${a.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** A post: committee header, optional picture, title, a dashed "ballot stub" line, then the message. The stripe on the left shows the category. */
@Composable
fun PostCard(a: AnnouncementDto, onPin: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val color = categoryColor(a.category)
    var expanded by remember(a.id) { mutableStateOf(false) }
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f)) {
                Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(MAROON), contentAlignment = Alignment.Center) {
                        Text("V", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VotEra Election Committee", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(timeAgo(a.createdAt) + if (a.pinned) "  |  Pinned" else "", fontSize = 11.sp, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    CategoryChip(a.category)
                }
                PostImage(a)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ellipsize(a.title, 80), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                        drawLine(line, Offset(0f, 1f), Offset(size.width, 1f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                    }
                    if (a.body.isNotBlank()) {
                        Text(a.body, fontSize = 14.sp, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        if (a.body.length > 140) Text(if (expanded) "Show less" else "Read more", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { expanded = !expanded })
                    }
                    if (onPin != null || onDelete != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onPin != null) TextButton(onClick = onPin) { Text(if (a.pinned) "Unpin" else "Pin to top", maxLines = 1) }
                        Spacer(Modifier.weight(1f))
                        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** The posts that match the search box and the selected category. */
fun visiblePosts(vm: AppVM): List<AnnouncementDto> {
    val q = vm.updatesQuery.trim()
    val present = vm.announcements.map { it.category }.toSet()
    val cat = if (vm.updatesCategory == "All" || vm.updatesCategory in present) vm.updatesCategory else "All"
    return vm.announcements.filter {
        (cat == "All" || it.category == cat) &&
            (q.isEmpty() || it.title.contains(q, true) || it.body.contains(q, true) || it.category.contains(q, true))
    }
}

/** Search box and category chips (All, Schedule, Reminder, Rules, Results, Urgent, General) with a count on each. */
@Composable
fun PostFilters(vm: AppVM) {
    val present = vm.announcements.groupingBy { it.category }.eachCount()
    val cat = if (vm.updatesCategory == "All" || vm.updatesCategory in present.keys) vm.updatesCategory else "All"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(vm.updatesQuery, { vm.updatesQuery = cleanText(it, 60) }, placeholder = { Text("Search updates") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cat == "All", onClick = { vm.updatesCategory = "All" }, label = { Text("All (${vm.announcements.size})", maxLines = 1) })
            POST_CATEGORIES.filter { it in present.keys }.forEach { c ->
                FilterChip(selected = cat == c, onClick = { vm.updatesCategory = c }, label = { Text("$c (${present[c]})", maxLines = 1) })
            }
        }
    }
}

/** Admin > Posts: write a post with a category, an optional picture and an optional pin, then manage the posts already published. */
@Composable
fun AdminPosts(vm: AppVM) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var pinned by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var deleting by remember { mutableStateOf<AnnouncementDto?>(null) }
    val preview = remember(image) { image?.let { bytesToImage(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b = compressPostImage(ctx, uri)
            if (b == null) vm.message = "That picture could not be read. Try another one." else image = b
        }
    }
    val shown = visiblePosts(vm)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New post", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(title, { title = cleanText(it, 80) }, label = { Text("Title") }, supportingText = { Text("${title.length}/80") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it.take(1000) }, label = { Text("Message") }, supportingText = { Text("${body.length}/1000") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("Category", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        POST_CATEGORIES.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c, maxLines = 1) }) }
                    }
                    if (preview != null) Box {
                        Image(bitmap = preview, contentDescription = "Picture to post", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (image == null) "Add picture" else "Change picture", maxLines = 1) }
                        if (image != null) TextButton(onClick = { image = null }) { Text("Remove picture", maxLines = 1) }
                    }
                    Row(Modifier.clickable { pinned = !pinned }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = pinned, onCheckedChange = { pinned = it })
                        Spacer(Modifier.width(10.dp))
                        Text("Pin to the top", fontSize = 13.sp)
                    }
                    Button(onClick = {
                        vm.postAnnouncement(title.trim(), body.trim(), category, pinned, image)
                        title = ""; body = ""; category = "General"; pinned = false; image = null
                    }, enabled = title.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Post") }
                    Text("Students see new posts right away. Pictures are shrunk before they are sent.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Posts (${vm.announcements.size})", style = MaterialTheme.typography.titleMedium) }
        item { PostFilters(vm) }
        if (shown.isEmpty()) item { Empty(if (vm.announcements.isEmpty()) "No posts yet." else "No post matches your search.") }
        items(shown, key = { it.id }) { a -> PostCard(a, onPin = { vm.setPostPinned(a) }, onDelete = { deleting = a }) }
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this post?") },
            text = { Text("\"${ellipsize(a.title, 40)}\" is removed for every student.") },
            confirmButton = { TextButton(onClick = { vm.deletePost(a); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** A small "done" dialog with a green check, shown after something important succeeds. */
@Composable
fun SuccessDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Box(Modifier.size(48.dp).clip(CircleShape).background(GREEN), contentAlignment = Alignment.Center) {
            Icon(VIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) } },
        title = { Text(title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = { Text(text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}
