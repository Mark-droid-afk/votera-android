package com.votera.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- Election report (admin) ----------------
// Everything here comes from data the system already recorded. Nothing is estimated or added.

@Composable
private fun Row3(a: String, b: String, c: String, bold: Boolean = false, wa: Float = 3f) {
    val w = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(a, Modifier.weight(wa), fontSize = 13.sp, fontWeight = w)
        Text(b, Modifier.weight(1f), fontSize = 13.sp, fontWeight = w)
        Text(c, Modifier.weight(1f), fontSize = 13.sp, fontWeight = w)
    }
}

/** The same report as plain text, for sharing to Docs, Messenger, email and so on. */
fun buildReportText(vm: AppVM): String {
    val e = vm.election
    val res = vm.results
    val sb = StringBuilder()
    sb.appendLine("VotEra election report")
    sb.appendLine("${e?.title ?: "Simulated SSC election"} (status: ${e?.status ?: "-"})")
    sb.appendLine("Fictional candidates, simulated election, research prototype.")
    sb.appendLine()
    if (res != null) {
        sb.appendLine("Turnout: ${res.turnout.votesCast} of ${res.turnout.eligibleVoters} eligible voters (${fmt(res.turnout.turnoutPercent)}%)")
        sb.appendLine()
        sb.appendLine("Ranking (hash map tally, Quick Sort, highest to lowest)")
        res.results.forEach { sb.appendLine("${it.rank}. ${it.fullName}: ${it.votes} votes (${fmt(it.percent)}%)") }
        sb.appendLine()
    }
    sb.appendLine("Turnout per program")
    vm.turnoutRows.forEach { sb.appendLine("${it.program}: ${it.voted}/${it.eligible} (${fmt(it.percent)}%)") }
    sb.appendLine()
    sb.appendLine("Credibility scores (originality 35%, achievements 30%, leadership 20%, community 15%)")
    vm.candidates.forEach {
        sb.appendLine("${it.fullName}: originality ${fmt(it.originalityScore)}, achievements ${fmt(it.achievementsScore)}, leadership ${fmt(it.leadershipScore)}, " +
            "community ${fmt(it.communityScore)}, credibility ${fmt(it.credibilityScore)}" + if (it.flagged) " [flagged for review]" else "")
    }
    sb.appendLine()
    sb.appendLine("Method: TF-IDF with cosine similarity; originality = 100 minus highest similarity x 100; manifestos at 0.70 or higher are flagged for administrator review.")
    return sb.toString()
}

private fun shareReport(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "VotEra election report"); putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Share report"))
}

@Composable
fun AdminReport(vm: AppVM) {
    val ctx = LocalContext.current
    val res = vm.results
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Election report", style = MaterialTheme.typography.titleMedium)
        Text("A summary of what the system recorded. It updates live and can be shared as text for your paper.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (res == null) Empty("No results yet.")
        else {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ellipsize(res.election.title, 40), maxLines = 1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusPill(res.election.status)
                    }
                    Text("${res.turnout.votesCast} of ${res.turnout.eligibleVoters} eligible voters voted (${fmt(res.turnout.turnoutPercent)}%)")
                }
            }
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Final ranking", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row3("Candidate", "Votes", "Share", bold = true)
                    HorizontalDivider()
                    res.results.forEach { Row3("${it.rank}. ${it.fullName}", "${it.votes}", "${fmt(it.percent)}%", bold = it.rank == 1 && it.votes > 0) }
                    val top = res.results.maxOfOrNull { it.votes } ?: 0
                    val leaders = res.results.filter { it.votes == top && top > 0 }
                    Spacer(Modifier.height(6.dp))
                    Text(when {
                        leaders.isEmpty() -> "No votes have been cast yet."
                        leaders.size > 1 -> "Tie for first place: ${leaders.joinToString { it.fullName }}."
                        res.election.status == "closed" -> "Winner: ${leaders[0].fullName}."
                        else -> "Currently leading: ${leaders[0].fullName}. The election is not closed yet."
                    }, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Counted with a hash map and ordered with Quick Sort, highest to lowest.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Turnout per program", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row3("Program", "Voted", "Rate", bold = true, wa = 3f)
                HorizontalDivider()
                if (vm.turnoutRows.isEmpty()) Text("No data yet.", fontSize = 13.sp)
                vm.turnoutRows.forEach { Row3(ellipsize(it.program, 22), "${it.voted}/${it.eligible}", "${fmt(it.percent)}%") }
            }
        }
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Credibility scores", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row3("Candidate", "Orig.", "Cred.", bold = true)
                HorizontalDivider()
                vm.candidates.forEach { Row3(it.fullName + if (it.flagged) " (flagged)" else "", fmt(it.originalityScore), fmt(it.credibilityScore)) }
                Spacer(Modifier.height(6.dp))
                Text("Credibility = originality x 35% + achievements x 30% + leadership x 20% + community x 15%. " +
                    "Originality = 100 minus the highest cosine similarity x 100. A manifesto at 0.70 or higher is flagged for administrator review, which is not a rejection.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = { shareReport(ctx, buildReportText(vm)) }, modifier = Modifier.fillMaxWidth()) { Text("Share report as text") }
        OutlinedButton(onClick = { printHtml(ctx, "VotEra election report", reportHtml(vm)) }, modifier = Modifier.fillMaxWidth()) { Text("Print or save as PDF") }
        Spacer(Modifier.height(8.dp))
    }
}
