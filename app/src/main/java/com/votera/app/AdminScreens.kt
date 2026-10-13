@file:OptIn(ExperimentalMaterial3Api::class)

package com.votera.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- Administrator area: Overview, Candidates, Vote audit, Voters ----------------
@Composable
fun AdminTab(vm: AppVM) {
    val sub = vm.adminSub
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("overview" to "Overview", "candidates" to "Candidates", "audit" to "Vote audit", "voters" to "Voters").forEach { (id, label) ->
                FilterChip(selected = sub == id, onClick = { vm.adminSub = id }, label = { Text(label) })
            }
        }
        AnimatedContent(targetState = sub, label = "adminSub", modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) }) { s ->
            when (s) {
                "overview" -> AdminOverview(vm)
                "candidates" -> AdminCandidates(vm)
                "audit" -> AdminAudit(vm)
                else -> AdminVoters(vm)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ----- Overview -----
@Composable
fun AdminOverview(vm: AppVM) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pendingStatus by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDemo by remember { mutableStateOf(false) }
    val t = vm.results?.turnout

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Election status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill(vm.election?.status ?: "closed")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("upcoming", "open", "closed").forEach { s ->
                            FilterChip(selected = vm.election?.status == s, onClick = { if (vm.election?.status != s) pendingStatus = s },
                                label = { Text(s.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    Text("Students see a status change on their phones right away.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("votes cast", "${t?.votesCast ?: 0}", Modifier.weight(1f))
                StatCard("eligible voters", "${t?.eligibleVoters ?: 0}", Modifier.weight(1f))
                StatCard("turnout", "${fmt(t?.turnoutPercent ?: 0.0)}%", Modifier.weight(1f))
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Turnout per program", style = MaterialTheme.typography.titleMedium)
                    if (vm.turnoutRows.isEmpty()) Text("No data yet.", fontSize = 13.sp)
                    vm.turnoutRows.forEach { r ->
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(r.program, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("${r.voted}/${r.eligible} (${fmt(r.percent)}%)", fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(progress = { (r.percent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Flagged manifestos for review", style = MaterialTheme.typography.titleMedium)
                    if (vm.flagged.isEmpty()) Text("None flagged.", fontSize = 13.sp)
                    vm.flagged.forEach {
                        Text("${it.fullName}: ${fmt((it.highestSimilarity ?: 0.0) * 100)}% similar to ${it.mostSimilarName ?: "another candidate"}", fontSize = 13.sp)
                    }
                    Text("Flagged at 70% similarity or higher. A flag does not reject a candidate.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Post an announcement", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.postAnnouncement(title.trim(), body.trim()); title = ""; body = "" },
                        enabled = title.isNotBlank() && !vm.busy) { Text("Post") }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tools", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {
                        vm.exportVotes { text ->
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "votera_votes.csv"); putExtra(Intent.EXTRA_TEXT, text) }
                            ctx.startActivity(Intent.createChooser(send, "Export vote list (CSV)"))
                        }
                    }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(VIcons.Download, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export vote list (CSV)")
                    }
                    OutlinedButton(onClick = { confirmDemo = true }, enabled = !vm.busy && vm.election?.status == "open", modifier = Modifier.fillMaxWidth()) {
                        Text("Demo mode: add 20 sample votes")
                    }
                    Text("Demo mode votes for accounts that have not voted, so the live charts can be shown. Reset the election afterwards.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { confirmReset = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                        Text("Reset election")
                    }
                }
            }
        }
    }

    pendingStatus?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingStatus = null },
            title = { Text("Change status to ${s.replaceFirstChar { it.uppercase() }}?") },
            text = { Text(when (s) { "open" -> "Students will be able to vote."; "closed" -> "Voting will stop. Results stay visible."; else -> "Voting will be paused until you open the election again." }) },
            confirmButton = { TextButton(onClick = { vm.setStatus(s); pendingStatus = null }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pendingStatus = null }) { Text("Cancel") } }
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset the election?") },
        text = { Text("This removes every recorded vote and sets the election back to Upcoming. Students will be able to vote again. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.resetElection(); confirmReset = false }) { Text("Reset", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
    if (confirmDemo) AlertDialog(
        onDismissRequest = { confirmDemo = false },
        title = { Text("Add sample votes?") },
        text = { Text("20 student accounts that have not voted will cast random votes. Use this only for demonstrations.") },
        confirmButton = { TextButton(onClick = { vm.demoFill(20); confirmDemo = false }) { Text("Add votes") } },
        dismissButton = { TextButton(onClick = { confirmDemo = false }) { Text("Cancel") } }
    )
}

// ----- Candidates: add, edit, delete, verified scores -----
@Composable
fun AdminCandidates(vm: AppVM) {
    var editing by remember { mutableStateOf<CandidateDto?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CandidateDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Candidates (${vm.candidates.size})", style = MaterialTheme.typography.titleMedium)
                        Text("Verified scores are 0 to 100.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { adding = true }) { Icon(VIcons.Add, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add") }
                }
            }
            if (vm.candidates.isEmpty()) item { Empty("No candidates yet. Tap Add.") }
            items(vm.candidates, key = { it.id }) { c ->
                var a by remember(c.id, c.achievementsScore) { mutableStateOf(c.achievementsScore?.let { fmt(it) } ?: "") }
                var l by remember(c.id, c.leadershipScore) { mutableStateOf(c.leadershipScore?.let { fmt(it) } ?: "") }
                var m by remember(c.id, c.communityScore) { mutableStateOf(c.communityScore?.let { fmt(it) } ?: "") }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CandidateAvatar(c, 48.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.fullName, fontWeight = FontWeight.Bold)
                                Text("${c.position} | ${c.program}", fontSize = 12.sp)
                            }
                            CredRing(c.credibilityScore, 46.dp, 4.dp)
                        }
                        if (c.mostSimilarName != null) Text(
                            "Most similar to: ${c.mostSimilarName} (${fmt((c.highestSimilarity ?: 0.0) * 100)}%)" + if (c.flagged) "  |  flagged" else "",
                            fontSize = 12.sp, color = if (c.flagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            OutlinedTextField(a, { a = it }, label = { Text("Achievements") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                            OutlinedTextField(l, { l = it }, label = { Text("Leadership") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                            OutlinedTextField(m, { m = it }, label = { Text("Community") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                val x = a.toDoubleOrNull(); val y = l.toDoubleOrNull(); val z = m.toDoubleOrNull()
                                if (x == null || y == null || z == null || listOf(x, y, z).any { it < 0 || it > 100 })
                                    vm.message = "Scores must be numbers from 0 to 100"
                                else vm.saveScores(c.id, x, y, z)
                            }, enabled = !vm.busy) { Text("Save scores") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { editing = c }) { Icon(VIcons.Edit, contentDescription = "Edit candidate") }
                            IconButton(onClick = { deleting = c }) { Icon(VIcons.Delete, contentDescription = "Delete candidate", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (adding) CandidateEditor(null, onDismiss = { adding = false }, onRemovePhoto = {}) { req, photo -> vm.addCandidate(req, photo); adding = false }
    editing?.let { c ->
        CandidateEditor(c, onDismiss = { editing = null }, onRemovePhoto = { vm.removePhoto(c.id); editing = null }) { req, photo ->
            vm.editCandidate(c.id, req, photo); editing = null
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${c.fullName}?") },
            text = { Text("The candidate is removed and similarity scores are recalculated for the rest. A candidate who already has votes cannot be removed until the election is reset.") },
            confirmButton = { TextButton(onClick = { vm.deleteCandidate(c.id); deleting = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** Add or edit a candidate. Saving recomputes TF-IDF similarity for every candidate, because one manifesto changes the reference set. */
@Composable
fun CandidateEditor(initial: CandidateDto?, onDismiss: () -> Unit, onRemovePhoto: () -> Unit, onSave: (CandidateReq, ByteArray?) -> Unit) {
    var name by remember { mutableStateOf(initial?.fullName ?: "") }
    var program by remember { mutableStateOf(initial?.program ?: "") }
    var position by remember { mutableStateOf(initial?.position ?: "SSC President") }
    var manifesto by remember { mutableStateOf(initial?.manifesto ?: "") }
    var ach by remember { mutableStateOf(initial?.achievements ?: "") }
    var lead by remember { mutableStateOf(initial?.leadership ?: "") }
    var comm by remember { mutableStateOf(initial?.community ?: "") }
    var sa by remember { mutableStateOf(initial?.achievementsScore?.let { fmt(it) } ?: "") }
    var sl by remember { mutableStateOf(initial?.leadershipScore?.let { fmt(it) } ?: "") }
    var sc by remember { mutableStateOf(initial?.communityScore?.let { fmt(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    val preview: ImageBitmap? = remember(photoBytes) { photoBytes?.let { bytesToImage(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b = compressPhoto(ctx, uri)
            if (b == null) error = "That picture could not be read. Try another one." else { photoBytes = b; error = null }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add candidate" else "Edit candidate") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (preview != null) Image(bitmap = preview, contentDescription = "Chosen photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape))
                    else if (initial != null) CandidateAvatar(initial, 72.dp)
                    else Avatar(name.ifBlank { "?" }, 1, 72.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (preview != null || (initial?.photoVersion ?: 0) > 0) "Change photo" else "Choose photo") }
                        if (initial != null && initial.photoVersion > 0 && preview == null) TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                        Text("Square photos look best.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(program, { program = it }, label = { Text("Program") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(position, { position = it }, label = { Text("Position") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(manifesto, { manifesto = it }, label = { Text("Manifesto") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ach, { ach = it }, label = { Text("Verified achievements") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lead, { lead = it }, label = { Text("Leadership experience") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(comm, { comm = it }, label = { Text("Community involvement") }, modifier = Modifier.fillMaxWidth())
                Text("Scores 0 to 100 (optional now, can be entered later)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    OutlinedTextField(sa, { sa = it }, label = { Text("Ach.") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    OutlinedTextField(sl, { sl = it }, label = { Text("Lead.") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    OutlinedTextField(sc, { sc = it }, label = { Text("Comm.") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                }
                Text("Saving recalculates TF-IDF similarity and credibility for every candidate.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun score(s: String): Double? = s.trim().toDoubleOrNull()
                val scores = listOf(sa, sl, sc)
                val bad = scores.any { it.isNotBlank() && (score(it) == null || score(it)!! < 0 || score(it)!! > 100) }
                when {
                    name.isBlank() -> error = "Enter the candidate's name"
                    bad -> error = "Scores must be numbers from 0 to 100"
                    else -> onSave(CandidateReq(name.trim(), program.trim(), position.trim(), manifesto.trim(), ach.trim(), lead.trim(), comm.trim(),
                        score(sa), score(sl), score(sc)), photoBytes)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ----- Vote audit: who voted for whom (administrator only) -----
@Composable
fun AdminAudit(vm: AppVM) {
    var q by remember { mutableStateOf("") }
    val rows = vm.votes.filter {
        q.isBlank() || it.fullName.contains(q, true) || it.studentNumber.contains(q, true) || it.candidateName.contains(q, true) || it.program.contains(q, true)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Vote audit (${vm.votes.size} votes)", style = MaterialTheme.typography.titleMedium)
                Text("Visible to administrators only. Students see totals only. Handle this list as confidential under R.A. 10173. Times are UTC.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(q, { q = it }, label = { Text("Search name, student number, program or candidate") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        if (rows.isEmpty()) item { Empty(if (vm.votes.isEmpty()) "No votes recorded yet." else "No matches.") }
        items(rows, key = { it.transactionId }) { v ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(v.fullName.ifBlank { v.studentNumber }, v.studentNumber.hashCode(), 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.fullName.ifBlank { v.studentNumber }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${v.studentNumber} | ${v.program}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(v.candidateName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    }
                    Text("${v.castAt.replace("T", " ").take(16)} | TX ${v.transactionId.take(12)}...", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ----- Voters: who has and has not voted -----
@Composable
fun AdminVoters(vm: AppVM) {
    var showImport by remember { mutableStateOf(false) }
    var showAddOne by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val voted = vm.voters.count { it.hasVoted }
    val rows = vm.voters.filter {
        (filter == "all" || (filter == "voted") == it.hasVoted) &&
            (q.isBlank() || it.fullName.contains(q, true) || it.studentNumber.contains(q, true) || it.program.contains(q, true))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Voters", style = MaterialTheme.typography.titleMedium)
                        Text("$voted of ${vm.voters.size} have voted", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { showAddOne = true }) { Icon(VIcons.Add, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("One") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { showImport = true }) { Icon(VIcons.Download, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Upload sheet") }
                }
                OutlinedTextField(q, { q = it }, label = { Text("Search name, student number or program") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "voted" to "Voted", "not" to "Not yet").forEach { (id, label) ->
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Empty("No matches.") }
        items(rows, key = { it.studentNumber }) { v ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(v.fullName, v.studentNumber.hashCode(), 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.fullName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${v.studentNumber} | ${v.program}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (v.hasVoted) "Voted" else "Not yet", fontSize = 12.sp,
                    color = if (v.hasVoted) GREEN else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showImport) VoterImportDialog(vm, onDismiss = { showImport = false })
    if (showAddOne) AddOneVoterDialog(vm, onDismiss = { showAddOne = false })
    vm.importResult?.let { r -> ImportResultDialog(r, onDismiss = { vm.importResult = null }) }
}

// ----- Add voters from a spreadsheet -----
// This text must match what the server accepts (see /admin/voters/import in main.py).
private const val SHEET_COLUMNS = "student_number,full_name,program,password"
private const val SHEET_EXAMPLE = SHEET_COLUMNS + "\n2022-90251-CM-0,Juan Dela Cruz,BSIT,\n2022-90252-CM-0,Maria Santos,BSBA - HRM,mypass123"
private val PROGRAM_LIST = listOf("BSIT", "BTLEd - Home Economics", "BTLEd - ICT", "BSBA - HRM", "BSBA - MM",
    "BS Entrepreneurship", "BPA - Fiscal Administration", "DOMT - Medical Office Management")

private fun shareText(ctx: android.content.Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, subject))
}

@Composable
fun VoterImportDialog(vm: AppVM, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
            if (text.isNullOrBlank()) problem = "That file could not be read. Save the sheet as CSV and try again."
            else { vm.importVoters(text); onDismiss() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add voters from a sheet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use a spreadsheet saved as CSV (in Google Sheets or Excel: File, Download or Save as, CSV). The first row must be:", fontSize = 13.sp)
                Text(SHEET_COLUMNS, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp))
                Text("Example rows:", fontSize = 13.sp)
                Text("2022-90251-CM-0,Juan Dela Cruz,BSIT,\n2022-90252-CM-0,Maria Santos,BSBA - HRM,mypass123", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp))
                Text("Rules", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("- Student number format: YYYY-NNNNN-CM-N (for example 2020-00000-CM-0).\n" +
                    "- Program must be one of: ${PROGRAM_LIST.joinToString("; ")}.\n" +
                    "- Password is optional. Leave it blank and VotEra makes one for each student (you can share the list afterwards). A typed password needs 6 or more characters.\n" +
                    "- Up to 200 students per upload. Students who already have an account are skipped.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { shareText(ctx, "votera_voters_sample.csv", SHEET_EXAMPLE) }, modifier = Modifier.fillMaxWidth()) { Text("Share a sample sheet to fill in") }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = { picker.launch("*/*") }, enabled = !vm.busy) { Text("Choose CSV file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ImportResultDialog(r: ImportRes, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (r.created > 0) "${r.created} voter${if (r.created == 1) "" else "s"} added" else "No voters were added") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.skipped > 0) Text("${r.skipped} row${if (r.skipped == 1) " was" else "s were"} skipped:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                r.errors.take(12).forEach { Text("Row ${it.row}${if (it.studentNumber.isNotBlank()) " (${it.studentNumber})" else ""}: ${it.reason}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                if (r.errors.size > 12) Text("and ${r.errors.size - 12} more", fontSize = 12.sp)
                if (r.generated.isNotEmpty()) {
                    Text("${r.generated.size} password${if (r.generated.size == 1) " was" else "s were"} generated. They are shown only now, so share the list with the students.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        val csv = "student_number,full_name,password\n" + r.generated.joinToString("\n") { "${it.studentNumber},\"${it.fullName}\",${it.password}" }
                        shareText(ctx, "votera_new_passwords.csv", csv)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Share the new passwords (CSV)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun AddOneVoterDialog(vm: AppVM, onDismiss: () -> Unit) {
    var sn by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var program by remember { mutableStateOf(PROGRAM_LIST[0]) }
    var pw by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val snOk = SN_REGEX.matches(sn.trim())
    fun cell(x: String) = "\"" + x.replace("\"", "\"\"") + "\""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add one voter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sn, { sn = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' } }, label = { Text("Student number") },
                    placeholder = { Text("2020-00000-CM-0") }, isError = sn.isNotEmpty() && !snOk,
                    supportingText = { Text("Format: YYYY-NNNNN-CM-N") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(program, maxLines = 1) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        PROGRAM_LIST.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { program = p; menu = false }) }
                    }
                }
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional)") }, singleLine = true,
                    supportingText = { Text("Leave blank to generate one") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = snOk && name.isNotBlank() && !vm.busy, onClick = {
                vm.importVoters(SHEET_COLUMNS + "\n" + listOf(sn.trim(), name.trim(), program, pw.trim()).joinToString(",") { cell(it) })
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
