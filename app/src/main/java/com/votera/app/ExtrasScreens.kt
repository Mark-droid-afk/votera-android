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
                            Text(c.fullName, fontWeight = FontWeight.Bold)
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
