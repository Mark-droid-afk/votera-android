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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val chipScroll = rememberScrollState()
    val chipIds = listOf("overview", "posts", "candidates", "audit", "voters", "report", "activity", "selftest")
    val chipDensity = androidx.compose.ui.platform.LocalDensity.current
    // Keeps the chosen chip in view (also when the guided tour jumps to it).
    LaunchedEffect(sub) { chipScroll.animateScrollTo(with(chipDensity) { (chipIds.indexOf(sub).coerceAtLeast(0) * 105).dp.toPx().toInt() }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(chipScroll).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("overview", "Overview", VIcons.Dashboard), Triple("posts", "Posts", VIcons.Bell), Triple("candidates", "Candidates", VIcons.People),
                Triple("audit", "Vote audit", VIcons.Ballot), Triple("voters", "Voters", VIcons.Check), Triple("report", "Report", VIcons.Doc),
                Triple("activity", "Activity", VIcons.Clock), Triple("selftest", "Self-test", VIcons.Shield)).forEach { (id, label, icon) ->
                FilterChip(selected = sub == id, onClick = { vm.adminSub = id }, modifier = Modifier.tourTarget("chip_$id"),
                    leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(16.dp)) },
                    label = { Text(label, maxLines = 1) })
            }
        }
        AnimatedContent(targetState = sub, label = "adminSub", modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (Prefs.reduceMotion) fadeIn(tween(1)) togetherWith fadeOut(tween(1)) else {
                    // Slide a little toward the side of the chip that was tapped, with a soft fade.
                    val dir = if (chipIds.indexOf(targetState) >= chipIds.indexOf(initialState)) 1 else -1
                    (slideInHorizontally(tween(320)) { dir * it / 6 } + fadeIn(tween(280, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(260)) { -dir * it / 6 } + fadeOut(tween(140)))
                }
            }) { s ->
            when (s) {
                "overview" -> AdminOverview(vm)
                "posts" -> AdminPosts(vm)
                "candidates" -> AdminCandidates(vm)
                "audit" -> AdminAudit(vm)
                "report" -> AdminReport(vm)
                "activity" -> AdminActivity(vm)
                "selftest" -> AdminSelfTest(vm)
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
    var pendingStatus by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDemo by remember { mutableStateOf(false) }
    var confirmCreds by remember { mutableStateOf(false) }
    val t = vm.results?.turnout

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic closing time", style = MaterialTheme.typography.titleMedium)
                    val left = countdownText(vm.deadline)
                    Text(if (left != null && vm.election?.status == "open") left else if (left != null) "A closing time is set. $left" else "None set. The election stays open until you close it.",
                        fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(30 to "30 min", 60 to "1 hour", 180 to "3 hours", 1440 to "1 day").forEach { (m, label) ->
                            FilterChip(selected = false, onClick = { vm.setSchedule(m) }, label = { Text(label) }, enabled = !vm.locked)
                        }
                        if (vm.deadline != null) AssistChip(onClick = { vm.setSchedule(null) }, label = { Text("Clear") })
                    }
                    Text("Counts down from now. Students see the countdown, and voting closes by itself at zero.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Turnout per program", style = MaterialTheme.typography.titleMedium)
                    if (vm.turnoutRows.isEmpty()) Text("No data yet.", fontSize = 13.sp)
                    vm.turnoutRows.forEach { r ->
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ellipsize(r.program, 22), fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${r.voted}/${r.eligible} (${fmt(r.percent)}%)", fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(progress = { (r.percent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        item { AdminInsights(vm) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Flagged manifestos for review", style = MaterialTheme.typography.titleMedium)
                    if (vm.flagged.isEmpty()) Text("None flagged.", fontSize = 13.sp)
                    vm.flagged.forEach {
                        Text("${ellipsize(it.fullName, 22)}: ${fmt((it.highestSimilarity ?: 0.0) * 100)}% similar to ${ellipsize(it.mostSimilarName ?: "another candidate", 22)}", fontSize = 13.sp)
                    }
                    Text("Flagged at 70% similarity or higher. A flag does not reject a candidate.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tools", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.showPreview = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Preview the student ballot")
                    }
                    OutlinedButton(onClick = {
                        vm.exportVotes { text ->
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "votera_votes.csv"); putExtra(Intent.EXTRA_TEXT, text) }
                            ctx.startActivity(Intent.createChooser(send, "Export vote list (CSV)"))
                        }
                    }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth()) {
                        Icon(VIcons.Download, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export vote list (CSV)")
                    }
                    OutlinedButton(onClick = {
                        vm.exportBackup { text ->
                            val send = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_SUBJECT, "votera_backup.json"); putExtra(Intent.EXTRA_TEXT, text) }
                            ctx.startActivity(Intent.createChooser(send, "Share backup (JSON)"))
                        }
                    }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth()) {
                        Icon(VIcons.Download, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Backup everything (JSON)")
                    }
                    OutlinedButton(onClick = { confirmDemo = true }, enabled = !vm.locked && vm.election?.status == "open", modifier = Modifier.fillMaxWidth()) {
                        Text("Demo mode: add 20 sample votes")
                    }
                    Text("Demo mode votes for accounts that have not voted, so the live charts can be shown. Reset the election afterwards.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { confirmCreds = true }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth()) {
                        Text("Create credentials sheet (new passwords)")
                    }
                    Text("Gives every student a new random password and a sheet you can print or send. It replaces the old passwords.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { confirmReset = true }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth(),
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
    if (confirmCreds) AlertDialog(
        onDismissRequest = { confirmCreds = false },
        title = { Text("Create new passwords?") },
        text = { Text("Every student gets a new random password. Old passwords stop working right away. The sheet is shown once, so share or save it before closing. This can take up to half a minute.") },
        confirmButton = { TextButton(onClick = { vm.regenerateCredentials(null); confirmCreds = false }) { Text("Create sheet") } },
        dismissButton = { TextButton(onClick = { confirmCreds = false }) { Text("Cancel") } }
    )
    vm.credentials?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.credentials = null },
            title = { Text("${c.count} new passwords created") },
            text = { Text("This sheet has each student's number, name, program, respondent code and new password. It is not saved anywhere else, so share it now and keep it private (R.A. 10173).") },
            confirmButton = {
                Button(onClick = {
                    val csv = "student_number,full_name,program,respondent_code,password\n" + c.rows.joinToString("\n") {
                        "${it.studentNumber},\"${it.fullName}\",\"${it.program}\",${it.respondentCode ?: ""},${it.password}"
                    }
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "votera_credentials.csv"); putExtra(Intent.EXTRA_TEXT, csv) }
                    ctx.startActivity(Intent.createChooser(send, "Share credentials sheet"))
                }) { Text("Share sheet (CSV)") }
            },
            dismissButton = { TextButton(onClick = { vm.credentials = null }) { Text("Close") } }
        )
    }
    if (vm.showPreview) {
        val shown = vm.candidates.filter { it.published }
        val drafts = vm.candidates.size - shown.size
        val groups = shown.groupBy { posKey(it.position) }.values.sortedBy { positionRank(it.first().position) }
        AlertDialog(
            onDismissRequest = { vm.showPreview = false },
            title = { Text("Student ballot preview") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This is what students will be able to choose from. Drafts are not shown.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (drafts > 0) Text("$drafts draft candidate(s) hidden.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    if (groups.isEmpty()) Text("No published candidates yet.", fontSize = 13.sp)
                    groups.forEach { g ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)) {
                            Text(g.first().position, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            g.forEach { c -> Text("- ${ellipsize(c.fullName, 30)}", fontSize = 13.sp, maxLines = 1) }
                            if (g.size == 1) Text("Only one candidate for this position", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.showPreview = false }) { Text("Close") } }
        )
    }
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
                OutlinedButton(onClick = { vm.demoCandidates() }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Add sample candidates for every position")
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
                                Text(ellipsize(c.fullName, 28), fontWeight = FontWeight.Bold, maxLines = 1)
                                if (!c.published) Text("DRAFT - hidden from students", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Text(c.position, fontSize = 12.sp, maxLines = 2)
                                Text(ellipsize(c.program, 28), fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CredRing(c.credibilityScore, 46.dp, 4.dp)
                        }
                        if (c.mostSimilarName != null) Text(
                            "Most similar to: ${c.mostSimilarName} (${fmt((c.highestSimilarity ?: 0.0) * 100)}%)" + if (c.flagged) "  |  flagged" else "",
                            fontSize = 12.sp, color = if (c.flagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Documents on file: achievements ${c.proofAchievements}, leadership ${c.proofLeadership}, community ${c.proofCommunity}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!c.missingProof.isNullOrEmpty()) Text("No proof yet for: " + c.missingProof.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            OutlinedTextField(a, { a = it }, label = { Text("Ach.", maxLines = 1) }, singleLine = true, keyboardOptions = kb, enabled = vm.canEdit, modifier = Modifier.weight(1f))
                            OutlinedTextField(l, { l = it }, label = { Text("Lead.", maxLines = 1) }, singleLine = true, keyboardOptions = kb, enabled = vm.canEdit, modifier = Modifier.weight(1f))
                            OutlinedTextField(m, { m = it }, label = { Text("Comm.", maxLines = 1) }, singleLine = true, keyboardOptions = kb, enabled = vm.canEdit, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                val x = a.toDoubleOrNull(); val y = l.toDoubleOrNull(); val z = m.toDoubleOrNull()
                                if (x == null || y == null || z == null || listOf(x, y, z).any { it < 0 || it > 100 })
                                    vm.message = "Scores must be numbers from 0 to 100"
                                else vm.saveScores(c.id, x, y, z)
                            }, enabled = !vm.locked) { Text("Save scores") }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = { vm.setCandidatePublished(c, !c.published) }, enabled = !vm.locked, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Text(if (c.published) "Make draft" else "Publish", maxLines = 1)
                            }
                            IconButton(onClick = { editing = c }) { Icon(VIcons.Edit, contentDescription = "Edit candidate") }
                            IconButton(onClick = { deleting = c }) { Icon(VIcons.Delete, contentDescription = "Delete candidate", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (adding) CandidateEditor(null, vm.canEdit, onDismiss = { adding = false }, onRemovePhoto = {}) { req, photo, proofs -> vm.addCandidate(req, photo, proofs); adding = false }
    editing?.let { c ->
        CandidateEditor(c, vm.canEdit, onDismiss = { editing = null }, onRemovePhoto = { vm.removePhoto(c.id); editing = null }) { req, photo, proofs ->
            vm.editCandidate(c.id, req, photo, proofs); editing = null
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${c.fullName}?") },
            text = { Text("The candidate is removed and similarity scores are recalculated for the rest. A candidate who already has votes cannot be removed until the election is reset.") },
            confirmButton = { TextButton(onClick = { vm.deleteCandidate(c.id); deleting = null }, enabled = vm.canEdit) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** Add or edit a candidate. Saving recomputes TF-IDF similarity for every candidate, because one manifesto changes the reference set. */
@Composable
fun CandidateEditor(initial: CandidateDto?, canSave: Boolean, onDismiss: () -> Unit, onRemovePhoto: () -> Unit, onSave: (CandidateReq, ByteArray?, List<ProofDraft>) -> Unit) {
    val proofDrafts = remember { mutableStateListOf<ProofDraft>() }
    var name by remember { mutableStateOf(initial?.fullName ?: "") }
    var program by remember { mutableStateOf(initial?.program ?: "") }
    val initPos = initial?.position?.let { findPosition(it)?.name ?: it } ?: ""
    var position by remember { mutableStateOf(initPos) }
    var customPos by remember { mutableStateOf(initial != null && initPos.isNotBlank() && findPosition(initPos) == null) }
    var posMenu by remember { mutableStateOf(false) }
    var manifesto by remember { mutableStateOf(initial?.manifesto ?: "") }
    var ach by remember { mutableStateOf(initial?.achievements ?: "") }
    var lead by remember { mutableStateOf(initial?.leadership ?: "") }
    var comm by remember { mutableStateOf(initial?.community ?: "") }
    var video by remember { mutableStateOf(initial?.videoUrl ?: "") }
    var qa by remember { mutableStateOf(initial?.qa ?: "") }
    var sa by remember { mutableStateOf(initial?.achievementsScore?.let { fmt(it) } ?: "") }
    var sl by remember { mutableStateOf(initial?.leadershipScore?.let { fmt(it) } ?: "") }
    var sc by remember { mutableStateOf(initial?.communityScore?.let { fmt(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var asDraft by remember { mutableStateOf(false) }
    var confirmPhoto by remember { mutableStateOf(false) }
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
                        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = canSave) { Text(if (preview != null || (initial?.photoVersion ?: 0) > 0) "Change photo" else "Choose photo") }
                        if (initial != null && initial.photoVersion > 0 && preview == null) TextButton(onClick = { confirmPhoto = true }, enabled = canSave) { Text("Remove photo") }
                        Text("Square photos look best.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(name, { name = cleanText(it, 60) }, label = { Text("Full name") }, singleLine = true, enabled = canSave, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(program, { program = cleanText(it, 40) }, label = { Text("Program") }, supportingText = { Text("${program.length}/40") }, singleLine = true, enabled = canSave, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = posMenu, onExpandedChange = { posMenu = it }) {
                    OutlinedTextField(
                        value = if (customPos) "Other (type your own)" else position.ifBlank { "" },
                        onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text("Position") }, placeholder = { Text("Choose a position") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = posMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = posMenu, onDismissRequest = { posMenu = false }) {
                        SSC_POSITIONS.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                onClick = { position = p.name; customPos = false; posMenu = false })
                        }
                        DropdownMenuItem(text = { Text("Other (type your own)") },
                            onClick = { if (!customPos) position = ""; customPos = true; posMenu = false })
                    }
                }
                if (customPos) OutlinedTextField(position, { position = cleanText(it, 60) }, label = { Text("Custom position name") },
                    supportingText = { Text("${position.length}/60. Example: Sports Councilor") }, singleLine = true, enabled = canSave, modifier = Modifier.fillMaxWidth())
                else findPosition(position)?.let { Text(it.duty, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                OutlinedTextField(manifesto, { manifesto = it.take(2000) }, label = { Text("Manifesto") }, supportingText = { Text("${manifesto.length}/2000") }, minLines = 3, enabled = canSave, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ach, { ach = it.take(600) }, label = { Text("Verified achievements") }, supportingText = { Text("${ach.length}/600") }, enabled = canSave, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lead, { lead = it.take(600) }, label = { Text("Leadership experience") }, supportingText = { Text("${lead.length}/600") }, enabled = canSave, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(comm, { comm = it.take(600) }, label = { Text("Community involvement") }, supportingText = { Text("${comm.length}/600") }, enabled = canSave, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(video, { video = it.trim().take(300) }, label = { Text("Platform video link (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = canSave, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(qa, { qa = it.take(1500) }, label = { Text("Questions and answers (optional)") }, minLines = 2,
                    supportingText = { Text("${qa.length}/1500. Example: Q: Why are you running? A: To ...") }, enabled = canSave, modifier = Modifier.fillMaxWidth())
                ProofSection(initial?.id, proofDrafts, canSave) { error = it }
                Text("Scores 0 to 100 (optional now, can be entered later)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (initial != null) Text("Documents on file: achievements ${initial.proofAchievements}, leadership ${initial.proofLeadership}, community ${initial.proofCommunity}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    OutlinedTextField(sa, { sa = it }, label = { Text("Ach.") }, singleLine = true, keyboardOptions = kb, enabled = canSave, modifier = Modifier.weight(1f))
                    OutlinedTextField(sl, { sl = it }, label = { Text("Lead.") }, singleLine = true, keyboardOptions = kb, enabled = canSave, modifier = Modifier.weight(1f))
                    OutlinedTextField(sc, { sc = it }, label = { Text("Comm.") }, singleLine = true, keyboardOptions = kb, enabled = canSave, modifier = Modifier.weight(1f))
                }
                if (initial == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save as draft", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Hidden from students until you publish it.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = asDraft, onCheckedChange = { asDraft = it }, enabled = canSave)
                }
                Text("Saving recalculates TF-IDF similarity and credibility for every candidate.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!canSave) Text("No connection. Saving is paused until the connection returns.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
                    position.isBlank() -> error = "Choose a position, or pick Other and type one"
                    bad -> error = "Scores must be numbers from 0 to 100"
                    else -> onSave(CandidateReq(name.trim(), program.trim(), position.trim(), manifesto.trim(), ach.trim(), lead.trim(), comm.trim(),
                        score(sa), score(sl), score(sc), normalizeUrl(video), qa.trim().ifBlank { null }, if (initial == null && asDraft) false else null), photoBytes, proofDrafts.toList())
                }
            }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmPhoto) AlertDialog(
        onDismissRequest = { confirmPhoto = false },
        title = { Text("Remove this photo?") },
        text = { Text("The candidate will show the initials avatar instead.") },
        confirmButton = { TextButton(onClick = { confirmPhoto = false; onRemovePhoto() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmPhoto = false }) { Text("Cancel") } })
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
                            Text(ellipsize(v.fullName.ifBlank { v.studentNumber }, 26), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text("${v.studentNumber} | ${ellipsize(v.program, 20)}", fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(ellipsize(v.candidateName, 22), maxLines = 1, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    }
                    Text("${ellipsize(posShort(v.position), 14)} | ${v.castAt.replace("T", " ").take(16)} | TX ${v.transactionId.take(8)}...", fontSize = 11.sp, maxLines = 1,
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
    var programFilter by remember { mutableStateOf("") }
    val voted = vm.voters.count { it.hasVoted }
    val programs = vm.voters.map { it.program }.distinct().sorted()
    val rows = vm.voters.filter {
        (filter == "all" || (filter == "voted") == it.hasVoted) &&
            (programFilter.isEmpty() || it.program == programFilter) &&
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
                val ctx = LocalContext.current
                val missing = vm.voters.filter { !it.hasVoted }
                OutlinedButton(onClick = {
                    val text = "Students who have not voted yet (${missing.size})\n" + missing.joinToString("\n") { "${it.fullName} - ${it.studentNumber} - ${it.program}" }
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "VotEra: who has not voted"); putExtra(Intent.EXTRA_TEXT, text) }
                    ctx.startActivity(Intent.createChooser(send, "Share the not-voted list"))
                }, enabled = missing.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(VIcons.Bell, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share list of ${missing.size} who have not voted", maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "voted" to "Voted", "not" to "Not yet").forEach { (id, label) ->
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = programFilter.isEmpty(), onClick = { programFilter = "" }, label = { Text("All programs") })
                    programs.forEach { pr ->
                        FilterChip(selected = programFilter == pr, onClick = { programFilter = if (programFilter == pr) "" else pr }, label = { Text(pr, maxLines = 1) })
                    }
                }
                Text("Tap a student to see a quick profile", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (rows.isEmpty()) item { Empty("No matches.") }
        items(rows, key = { it.studentNumber }) { v ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = !vm.busy) { vm.openVoterProfile(v.studentNumber) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(v.fullName, v.studentNumber.hashCode(), 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ellipsize(v.fullName, 28), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("${v.studentNumber} | ${ellipsize(v.program, 20)}" + (v.respondentCode?.let { " | $it" } ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (v.positionsTotal > 1 && v.positionsVoted > 0) "Voted ${v.positionsVoted}/${v.positionsTotal}" else if (v.hasVoted) "Voted" else "Not yet", fontSize = 12.sp, maxLines = 1,
                    color = if (v.hasVoted) GREEN else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showImport) VoterImportDialog(vm, onDismiss = { showImport = false })
    if (showAddOne) AddOneVoterDialog(vm, onDismiss = { showAddOne = false })
    vm.voterProfile?.let { VoterProfileDialog(vm, it) }
    vm.newPassword?.let { np -> NewPasswordDialog(np, onDismiss = { vm.newPassword = null }) }
    vm.importResult?.let { r -> ImportResultDialog(r, onDismiss = { vm.importResult = null }) }
}

// ----- Quick profile of one student -----
@Composable
fun VoterProfileDialog(vm: AppVM, p: VoterProfileDto) {
    var confirmReset by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.voterProfile = null },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.fullName, p.studentNumber.hashCode(), 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(ellipsize(p.fullName, 26), fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(p.studentNumber, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (p.online) GREEN else MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(6.dp))
                    Text(if (p.online) "Signed in now" else "Not signed in", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text("Program: ${p.program}", fontSize = 13.sp)
                Text("Respondent code: ${p.respondentCode ?: "none"}", fontSize = 13.sp)
                Text("Last sign-in: ${p.lastLogin?.let { timeAgo(it) } ?: "never"}", fontSize = 13.sp)
                Text("Last seen: ${p.lastSeen?.let { timeAgo(it) } ?: "never"}", fontSize = 13.sp)
                Text("Voted for ${p.positionsVoted} of ${p.positionsTotal} positions", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (p.positionsVoted > 0) GREEN else MaterialTheme.colorScheme.onSurfaceVariant)
                p.votes.forEach { v ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)) {
                        Text(v.position, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Text(ellipsize(v.candidateName, 30), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(v.transactionId.take(16) + "...", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (confirmReset) {
                    Text("Create a new password for this student? The old one stops working and the student is signed out.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmReset = false; vm.resetVoterPassword(p.studentNumber) }, enabled = !vm.locked) { Text("Yes, reset") }
                        OutlinedButton(onClick = { confirmReset = false }) { Text("Cancel") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmReset = true }, enabled = !vm.locked) { Text("Reset password", maxLines = 1) }
                        if (p.online) OutlinedButton(onClick = { vm.endSession(p.studentNumber) }, enabled = !vm.locked) { Text("End session", maxLines = 1) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.voterProfile = null }) { Text("Close") } }
    )
}

@Composable
fun NewPasswordDialog(np: NewPasswordDto, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${np.fullName} (${np.studentNumber})", fontSize = 13.sp)
                Text(np.password, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp))
                Text("This is shown only once. Share it privately with the student.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "VotEra password"); putExtra(Intent.EXTRA_TEXT, "VotEra login\nStudent number: ${np.studentNumber}\nNew password: ${np.password}") }
                ctx.startActivity(Intent.createChooser(send, "Share the new password"))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
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
        confirmButton = { Button(onClick = { picker.launch("*/*") }, enabled = !vm.locked) { Text("Choose CSV file") } },
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
                OutlinedTextField(name, { name = cleanText(it, 60) }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
            TextButton(enabled = snOk && name.isNotBlank() && !vm.locked, onClick = {
                vm.importVoters(SHEET_COLUMNS + "\n" + listOf(sn.trim(), name.trim(), program, pw.trim()).joinToString(",") { cell(it) })
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
