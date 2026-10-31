package org.ekatra.alfred.lessons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding

private val Blue = Color(0xFF2563EB)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)

@Composable
fun LessonsApp(onExit: () -> Unit, vm: LessonsViewModel = hiltViewModel()) {
    val ui by vm.uiState.collectAsState()
    BackHandler { if (!vm.back()) onExit() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LessonsTopBar(ui, onBack = { if (!vm.back()) onExit() })
        Box(Modifier.weight(1f)) {
            when (val screen = ui.screen) {
                is LessonsScreen.Home -> HomeScreen(ui, vm)
                is LessonsScreen.Lesson -> {
                    val lesson = vm.findLesson(screen.courseId, screen.lessonId)
                    if (lesson == null) HomeFallback(vm) else when (screen.stage) {
                        Stage.READ -> ReadScreen(lesson, screen.courseId, ui.imageRevision, vm, onStartQuiz = vm::startQuiz)
                        Stage.QUIZ -> QuizScreen(ui, lesson, vm)
                        Stage.DONE -> DoneScreen(ui, lesson, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFallback(vm: LessonsViewModel) {
    LaunchedEffect(Unit) { vm.back() }
}

@Composable
private fun LessonsTopBar(ui: LessonsUiState, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Blue, Color(0xFF1D4ED8))))
            .statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Text("←", color = Color.White, fontSize = 22.sp) }
        Column(Modifier.weight(1f)) {
            Text("Lessons", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                if (ui.online) "Online" else "Offline: lessons you downloaded still work",
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp
            )
        }
        SyncChip(ui, onDark = true)
    }
}

// ---------------- sync chip ----------------

internal fun syncChipText(ui: LessonsUiState): String = when {
    !ui.configured -> "Not configured"
    ui.syncStatus == SyncStatus.UNAUTHORIZED -> "Sign-in expired"
    ui.syncStatus == SyncStatus.RUNNING -> "Syncing…"
    ui.pendingCount > 0 -> if (ui.pendingCount == 1) "1 update waiting" else "${ui.pendingCount} updates waiting"
    else -> "Synced ✓"
}

@Composable
private fun SyncChip(ui: LessonsUiState, onDark: Boolean = false) {
    val synced = ui.configured && ui.pendingCount == 0 && ui.syncStatus != SyncStatus.UNAUTHORIZED && ui.syncStatus != SyncStatus.RUNNING
    val bg = when {
        synced -> Green
        ui.syncStatus == SyncStatus.UNAUTHORIZED || !ui.configured -> Red
        else -> Color(0xFFF59E0B)
    }
    Text(
        syncChipText(ui), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

// ---------------- home ----------------

internal data class PendingMessage(val text: String, val showProgress: Boolean)

/**
 * What to tell the learner about lesson requests still in the queue. "Back online" only makes sense
 * while the device is offline; online, a pending request is being created (or retried after a 409
 * in_progress, 5xx, ...) and the server can take a minute or two.
 */
internal fun pendingRequestMessage(ui: LessonsUiState): PendingMessage? = when {
    ui.pendingTopics.isEmpty() -> null
    !ui.online -> PendingMessage("Will be created when you're back online", showProgress = false)
    ui.syncStatus == SyncStatus.UNAUTHORIZED -> PendingMessage("Waiting: your lessons sign-in expired", showProgress = false)
    else -> PendingMessage("Creating your lesson with Muse Spark… this can take a minute", showProgress = true)
}

internal fun failedRequestText(f: FailedLessonRequest) = "Couldn't create lesson: ${f.reason}"

@Composable
private fun HomeScreen(ui: LessonsUiState, vm: LessonsViewModel) {
    var topic by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask for a lesson", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    OutlinedTextField(
                        value = topic, onValueChange = { topic = it }, singleLine = true,
                        enabled = ui.configured && !ui.requesting,
                        label = { Text("Topic, e.g. fractions") }, modifier = Modifier.fillMaxWidth()
                    )
                    // While a request is in flight the card below shows the progress; this button just waits.
                    Button(
                        onClick = { if (vm.askForLesson(topic)) topic = "" },
                        enabled = ui.configured && topic.isNotBlank() && !ui.requesting
                    ) { Text(if (ui.requesting) "Creating…" else if (ui.online) "Create lesson" else "Queue lesson") }
                    if (!ui.configured) {
                        Text("Lessons aren't set up on this build.", color = Red, fontSize = 13.sp)
                    }
                    ui.notice?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it, Modifier.weight(1f), fontSize = 13.sp, color = if (it.startsWith("Couldn't")) Red else Color(0xFF334155))
                            TextButton(onClick = vm::dismissNotice) { Text("OK") }
                        }
                    }
                }
            }
        }
        items(ui.failedRequests, key = { "failed-" + it.clientRequestId }) { f ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(failedRequestText(f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Red)
                    Text("Topic: ${f.topic}", fontSize = 13.sp, color = Color(0xFF475569))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.retryFailedRequest(f.clientRequestId) }, enabled = !ui.requesting) { Text("Try again") }
                        TextButton(onClick = { vm.dismissFailedRequest(f.clientRequestId) }) { Text("Dismiss") }
                    }
                }
            }
        }
        pendingRequestMessage(ui)?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (msg.showProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(msg.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        ui.pendingTopics.forEach { Text("• $it", fontSize = 14.sp) }
                    }
                }
            }
        }
        if (ui.bundles.isEmpty() && ui.configured && ui.pendingTopics.isEmpty() && ui.failedRequests.isEmpty()) {
            item { Text("No lessons yet. Ask for one above.", color = Color(0xFF64748B), modifier = Modifier.padding(8.dp)) }
        }
        items(ui.bundles, key = { it.course.id }) { bundle -> CourseCard(bundle, ui, vm) }
        item {
            Text(
                "Built with Llama",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 12.sp, color = Color(0xFF64748B)
            )
        }
    }
}

@Composable
private fun CourseCard(bundle: LessonBundle, ui: LessonsUiState, vm: LessonsViewModel) {
    val lessons = bundle.lessons.sortedBy { it.order }
    val done = lessons.count { it.id in ui.completed }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LessonImage(
                file = vm.coverFile(bundle.course.id),
                expected = !bundle.course.coverImageUrl.isNullOrBlank(),
                contentDescription = "Cover illustration for ${bundle.course.title}",
                revision = ui.imageRevision,
                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp))
            )
            Text(bundle.course.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            bundle.course.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = Color(0xFF475569)) }
            bundle.course.generator?.let { g ->
                val who = listOfNotNull(g.provider, g.model).joinToString(" ")
                if (who.isNotBlank()) Text("Written by $who", fontSize = 11.sp, color = Color(0xFF94A3B8))
            }
            Text("$done of ${lessons.size} done", fontSize = 12.sp, color = Color(0xFF64748B))
            lessons.forEach { l ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openLesson(bundle.course.id, l.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (l.id in ui.completed) "✓" else "○", color = if (l.id in ui.completed) Green else Color(0xFF94A3B8), fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(l.title, Modifier.weight(1f), fontSize = 15.sp)
                    Text("${l.questions.size} Q", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
                HorizontalDivider(color = Color(0xFFE2E8F0))
            }
        }
    }
}

// ---------------- reading ----------------

/** Lesson text uses *bold* and blank-line paragraphs; render just that, nothing fancier. */
internal fun lessonText(raw: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '*') {
            // Treat ** and * alike as a bold toggle.
            while (i + 1 < raw.length && raw[i + 1] == '*') i++
            if (bold) pop() else pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            bold = !bold
        } else {
            append(c)
        }
        i++
    }
    if (bold) pop() // an unclosed * bolds to the end rather than leaking a style
}

@Composable
private fun ReadScreen(lesson: BundleLesson, courseId: String, imageRevision: Int, vm: LessonsViewModel, onStartQuiz: () -> Unit) {
    val course = vm.findCourse(courseId)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (course != null) {
            LessonImage(
                file = vm.coverFile(courseId),
                expected = !course.coverImageUrl.isNullOrBlank(),
                contentDescription = "Cover illustration for ${course.title}",
                revision = imageRevision,
                modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Text(lesson.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        LessonImage(
            file = vm.lessonImageFile(courseId, lesson.id),
            expected = !lesson.imageUrl.isNullOrBlank(),
            contentDescription = "Illustration for ${lesson.title}",
            revision = imageRevision,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
        )
        Text(lessonText(lesson.content.orEmpty()), fontSize = 17.sp, lineHeight = 26.sp)
        if (lesson.questions.isNotEmpty()) {
            Button(onClick = onStartQuiz, modifier = Modifier.fillMaxWidth()) {
                Text("Start quiz (${lesson.questions.size} question${if (lesson.questions.size == 1) "" else "s"})")
            }
        } else {
            Button(onClick = onStartQuiz, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
        }
        Text("Built with Llama", fontSize = 12.sp, color = Color(0xFF64748B), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

// ---------------- quiz ----------------

@Composable
private fun QuizScreen(ui: LessonsUiState, lesson: BundleLesson, vm: LessonsViewModel) {
    val quiz = ui.quiz
    val q = lesson.questions.getOrNull(quiz.index) ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Question ${quiz.index + 1} of ${lesson.questions.size}", fontSize = 13.sp, color = Color(0xFF64748B))
        Text(q.question, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        q.options.forEachIndexed { i, text ->
            val letter = LessonGrading.letterFor(i)
            val answered = quiz.selected != null
            val isChosen = quiz.selected == letter
            val isRight = LessonGrading.isCorrect(q, letter)
            val border = when {
                answered && isRight -> Green
                answered && isChosen -> Red
                else -> Color(0xFFCBD5E1)
            }
            val fill = when {
                answered && isRight -> Color(0xFFDCFCE7)
                answered && isChosen -> Color(0xFFFEE2E2)
                else -> Color.White
            }
            OutlinedCard(
                onClick = { vm.answer(letter) }, enabled = !answered,
                colors = CardDefaults.outlinedCardColors(containerColor = fill, disabledContainerColor = fill),
                border = BorderStroke(if (answered && (isRight || isChosen)) 2.dp else 1.dp, border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(letter, fontWeight = FontWeight.Bold, color = Blue, modifier = Modifier.width(28.dp))
                    Text(text, fontSize = 16.sp, color = Color(0xFF0F172A))
                }
            }
        }
        if (quiz.selected != null) {
            Text(
                if (quiz.correct == true) "Correct! 🎉" else "Not quite. The answer is ${q.correctAnswer}.",
                fontWeight = FontWeight.Bold, color = if (quiz.correct == true) Green else Red, fontSize = 16.sp
            )
            if (quiz.correct == true) {
                q.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp, color = Color(0xFF334155)) }
            } else {
                MayaExplains(ui.explain)
            }
            val last = quiz.index + 1 >= lesson.questions.size
            Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) { Text(if (last) "Finish lesson" else "Next question") }
        }
        Text("Built with Llama", fontSize = 12.sp, color = Color(0xFF64748B), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** The line under "Maya explains" that names what actually wrote the text. */
internal fun explainCredit(state: ExplainState): String? = when (state) {
    is ExplainState.Loading -> "${state.modelName} is thinking on this phone…"
    is ExplainState.Streaming -> "Explained by ${state.modelName} on this phone"
    is ExplainState.Done -> "Explained by ${state.modelName} on this phone"
    is ExplainState.Fallback -> "From the lesson, not written by the model (${state.reason})"
    ExplainState.Idle -> null
}

@Composable
private fun MayaExplains(state: ExplainState) {
    if (state is ExplainState.Idle) return
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Maya explains", fontWeight = FontWeight.Bold, color = Blue, fontSize = 14.sp)
            when (state) {
                is ExplainState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Thinking…", fontSize = 14.sp)
                }
                is ExplainState.Streaming -> Text(state.text, fontSize = 15.sp)
                is ExplainState.Done -> Text(state.text, fontSize = 15.sp)
                is ExplainState.Fallback -> Text(state.text, fontSize = 15.sp)
                ExplainState.Idle -> Unit
            }
            explainCredit(state)?.let { Text(it, fontSize = 11.sp, color = Color(0xFF64748B)) }
        }
    }
}

// ---------------- completion ----------------

/**
 * True only when the server profile has mastery for a lesson this device never saw an answer or a
 * completion for, i.e. the learner worked on it somewhere else (WhatsApp, another phone).
 */
internal fun profileHasRemoteActivity(profile: ServerProfile, answeredLessonIds: Set<String>, completed: Set<String>): Boolean =
    profile.mastery.keys.any { it !in answeredLessonIds && it !in completed }

internal fun profileSourceNote(ui: LessonsUiState): String {
    val profile = ui.profile ?: return ""
    val base = "From the server, as of the last sync"
    val waiting = if (ui.pendingCount > 0) {
        " · doesn't include your ${ui.pendingCount} waiting update${if (ui.pendingCount == 1) "" else "s"} yet"
    } else ""
    val elsewhere = if (profileHasRemoteActivity(profile, ui.answeredLessonIds, ui.completed)) {
        " · includes lessons you took elsewhere, such as on WhatsApp"
    } else ""
    return base + waiting + elsewhere
}

@Composable
private fun DoneScreen(ui: LessonsUiState, lesson: BundleLesson, vm: LessonsViewModel) {
    val total = lesson.questions.size
    val correct = ui.quiz.correctCount
    val titles = ui.lessonTitles + ui.bundles.flatMap { it.lessons }.associate { it.id to it.title }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Lesson complete 🎉", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(lesson.title, fontSize = 16.sp, color = Color(0xFF475569))
        if (total > 0) Text("You got $correct of $total right.", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Last synced profile", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    SyncChip(ui)
                }
                val profile = ui.profile
                if (profile == null) {
                    Text(
                        if (ui.configured) "Your progress is saved on this phone. Your profile appears here once it has synced."
                        else "Sync isn't set up on this build.",
                        fontSize = 14.sp, color = Color(0xFF475569)
                    )
                } else {
                    Text(profileSourceNote(ui), fontSize = 11.sp, color = Color(0xFF94A3B8))
                    Text(
                        "${profile.totals.quizAnswered} answered · ${profile.totals.correct} right · ${profile.totals.wrong} wrong · ${profile.totals.lessonsCompleted} lessons done",
                        fontSize = 14.sp
                    )
                    if (profile.mastery.isEmpty()) Text("No mastery data yet.", fontSize = 14.sp, color = Color(0xFF64748B))
                    profile.mastery.entries.sortedByDescending { it.value }.forEach { (id, m) ->
                        val struggling = id in profile.struggles
                        Column {
                            Row {
                                Text(titles[id] ?: "Lesson", Modifier.weight(1f), fontSize = 14.sp)
                                Text("${(m * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (struggling) Red else Green)
                            }
                            LinearProgressIndicator(
                                progress = { m.toFloat().coerceIn(0f, 1f) },
                                color = if (struggling) Red else Green,
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                            )
                        }
                    }
                    if (profile.struggles.isNotEmpty()) {
                        Text("Worth another look: " + profile.struggles.joinToString { titles[it] ?: "a lesson" }, fontSize = 13.sp, color = Red)
                    }
                }
            }
        }
        Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text("Back to lessons") }
        Text("Built with Llama", fontSize = 12.sp, color = Color(0xFF64748B), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
