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
    body.append("<tr><th>Transaction ID (SHA-256)</th><td class=\"mono\">${htmlEscape(vm.voteStatus?.transactionId ?: "")}</td></tr></table>")
    body.append("<p><small>Keep this as proof that your vote was recorded. Each student can vote once.</small></p>")
    return pageHtml("VotEra vote receipt", body.toString())
}

/** The election report as a printable page. Same recorded data as the Report tab. */
fun reportHtml(vm: AppVM): String {
    val b = StringBuilder()
    val res = vm.results
    b.append("<p>${htmlEscape(vm.election?.title ?: "")} (status: ${htmlEscape(vm.election?.status ?: "-")})</p>")
    if (res != null) {
        b.append("<p>Turnout: ${res.turnout.votesCast} of ${res.turnout.eligibleVoters} eligible voters (${fmt(res.turnout.turnoutPercent)}%)</p>")
        b.append("<h2>Final ranking</h2><table><tr><th>Rank</th><th>Candidate</th><th>Votes</th><th>Share</th></tr>")
        res.results.forEach { b.append("<tr><td>${it.rank}</td><td>${htmlEscape(it.fullName)}</td><td>${it.votes}</td><td>${fmt(it.percent)}%</td></tr>") }
        b.append("</table><p><small>Counted with a hash map and ordered with Quick Sort, highest to lowest.</small></p>")
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
