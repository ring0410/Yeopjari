package com.yeopjari.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yeopjari.app.coach.Phrases
import com.yeopjari.app.coach.AndroidRepeatListener
import com.yeopjari.app.coach.PleosRepeatListener
import com.yeopjari.app.coach.RepeatListener
import android.content.pm.PackageManager
import android.os.SystemClock
import com.yeopjari.app.model.CoachProfile
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.coach.CoachEngine
import com.yeopjari.app.coach.Speaker
import com.yeopjari.app.model.CoachEvent
import com.yeopjari.app.model.DriveState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Settings
import com.yeopjari.app.source.RealSource
import com.yeopjari.app.source.ReplaySource
import com.yeopjari.app.source.Scenarios
import com.yeopjari.app.source.SignalSource
import com.yeopjari.app.trip.Store
import com.yeopjari.app.trip.TripRecorder
import com.yeopjari.app.trip.TripReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val drive: DriveState = DriveState(),
    val card: CoachEvent? = null,
    val report: TripReport? = null,
    val showReport: Boolean = false,
    val showSettings: Boolean = false,
    val settings: Settings = Settings(),
    val spotCount: Int = 0,
    val lastTip: String? = null,
    val lastFuel: Float? = null,
    val playing: Scenarios.Id? = null,
    /** 지난 주행의 방향지시등 사용률 (리포트에서 '지난번 대비' 비교용) */
    val prevSignalRate: Int? = null,
    /** 옆자리 포인트 누적 (1P 단위) */
    val pointsTotal: Int = 0,
    /** 최근 주행 기록 (실력 변화 보기) */
    val history: List<Store.TripSummary> = emptyList(),
    /** 마지막 주차 위치 (위도, 경도, 시각) */
    val parking: Triple<Double, Double, Long>? = null,
    /** 처음 실행 소개 화면 */
    val showIntro: Boolean = false,
    /** 리포트 '자세히 보기' 펼침 */
    val reportDetail: Boolean = false,
    /** 말로 다시 듣기: 지금 듣는 중 */
    val listening: Boolean = false,
    /** 말로 다시 듣기: 방금 알아들은 말 (화면에 잠깐 표시) */
    val heardText: String? = null,
    /** 이 기기에 지도 앱·공유 앱이 있는지 (없으면 버튼을 숨김) */
    val canOpenMap: Boolean = false,
    val canShare: Boolean = false,
    /** 차량이 '주행 중 화면 조작 제한' 상태인지 (그러면 주행 화면만 보여 줌) */
    val restricted: Boolean = false,
    /** 지금 쓰는 음성·음성 인식 이름 (설정 화면 표시) */
    val voiceName: String = "",
    val listenerName: String = "",
    /** 질문하기: 방금 받은 질문 (화면에 잠깐 표시) */
    val question: String? = null,
    /** 질문하기: 지금 질문을 기다리는 중 */
    val askWaiting: Boolean = false,
    /** 이번 주행에서 물어본 질문과 대답 (리포트에 남김) */
    val askedQa: List<Pair<String, String>> = emptyList(),
) {
    /** 오늘의 미션: 지난 주행의 개선 포인트에서 하나를 골라 출발 전에 보여 줍니다. */
    val todayMission: String
        get() = lastTip.orEmpty().let {
            when {
                "깜빡이" in it -> "회전·합류 전에 깜빡이 제때 켜기"
                "가속" in it -> "출발할 때 부드럽게 가속하기"
                "줄여" in it || "거리" in it -> "급제동 없이 부드럽게 도착하기"
                else -> "회전 후 깜빡이 스스로 끄기"
            }
        }
}

/** 신호 출처 → 코칭 엔진 → 음성·화면, 그리고 주행 기록을 연결하는 중심 */
class AppController(private val context: Context, private val isCar: Boolean = true) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = Store(context)
    private val speaker = Speaker(context, preferPleos = isCar && store.loadSettings().pleosVoice)
    /** 말로 다시 듣기 — 차량은 Pleos 음성 인식(안 되면 기본), 핸드폰은 기본 음성 인식 */
    private var listener: RepeatListener? = null
    /** 시연 모드에서 앱을 켠 뒤 몇 번째 시연인지 (출발 점검은 첫 시연에서만) */
    private var demoPlays = 0
    /** 마지막 안내를 말할 때의 회전 정보 (다시 듣기 때 이미 지난 안내인지 판단) */
    private var lastSaidTurn: com.yeopjari.app.model.Turn? = null
    private val recorder = TripRecorder()
    private val replay = ReplaySource()
    private var real: RealSource? = null
    private var source: SignalSource? = null
    private var collectJob: Job? = null
    private var lastGear = Gear.PARK

    private val _ui = MutableStateFlow(
        UiState(
            settings = store.loadSettings(),
            spotCount = store.loadSpots().size,
            lastTip = store.lastImprovement(),
            lastFuel = store.lastFuel(),
            pointsTotal = store.totalPoints(),
            history = store.history(),
            parking = store.parking(),
            showIntro = !store.introDone(),
        )
    )
    val ui: StateFlow<UiState> = _ui

    /** 실제 주행에서는 저장된 실력으로 안내 길이를 맞추고, 시연에서는 늘 처음 운전하는 분 기준(자세한 안내)으로 보여 줍니다. */
    private val profile = object : CoachProfile {
        override fun skill(m: Maneuver) = if (_ui.value.settings.demoMode) 0 else store.skill(m)
        // 시연은 첫 시연에서만 출발 점검을 말함 (여러 시연을 이어 볼 때 같은 말 반복 방지)
        override val trips: Int get() = if (_ui.value.settings.demoMode) (if (demoPlays <= 1) 0 else 99) else store.trips()
        override fun heard(id: String) = if (_ui.value.settings.demoMode) 0 else store.heard(id)
    }

    /** 시연 E(강사 모드·질문·잘못 진입)를 재생할 때는 강사 모드로 보여 줍니다 */
    private fun effectiveSettings(): Settings {
        val st = _ui.value.settings
        return if (st.demoMode && replay.playing.value == Scenarios.Id.TALK) st.copy(talkLevel = com.yeopjari.app.model.TalkLevel.INSTRUCTOR) else st
    }
    private var lastDemoQuestion: String? = null
    /** '질문하기'를 눌러 "네, 물어보세요"를 말한 뒤 질문을 기다리는 중 */
    private var questionPending = false
    /** 이번 주행에서 미뤄 둔 대답을 이어 말한 횟수 ('아까 물어보신 거…'는 처음 한 번만) */
    private var resumeCount = 0

    private val engine = CoachEngine({ effectiveSettings() }, { store.loadSpots() }, { profile }).apply {
        onManeuverFinished = { m, onTime, used ->
            recorder.onManeuver(m, onTime, used)
            if (onTime && !_ui.value.settings.demoMode) store.addSkill(m)
        }
        onHarshAccel = { recorder.onHarshAccel() }
        onHarshBrake = { s, near -> recorder.onHarshBrake(s, near) }
        onMission = { m -> recorder.onMission(m) }
        onPrinciple = { id -> if (!_ui.value.settings.demoMode) store.addHeard(id) }
    }

    init { recorder.missionsEnabled = { _ui.value.settings.coaching } }

    fun start() {
        speaker.forRecording = _ui.value.settings.demoMode
        speaker.slow = _ui.value.settings.slowSpeech
        speaker.chime = _ui.value.settings.chime
        speaker.male = _ui.value.settings.maleVoice
        // 안내 시작 시점 계산에 쓰는 '글자당 읽는 시간'을 이 기기에서 잰 값으로 (조금 넉넉하게)
        store.msPerChar()?.let { CoachEngine.msPerChar = (it * 11 / 10).coerceIn(150L, 320L) }
        speaker.onMeasured = { ms ->
            val prev = store.msPerChar() ?: ms
            val avg = (prev * 4 + ms) / 5
            store.saveMsPerChar(avg)
            CoachEngine.msPerChar = (avg * 11 / 10).coerceIn(150L, 320L)
        }
        // 말로 다시 듣기: 안내를 다 읽고 조용해지면 5초만 듣기
        speaker.onIdleAfter = { if (questionPending) { questionPending = false; startListening(question = true) } else startListening() }
        listener = if (isCar) PleosRepeatListener(context) { ok ->
            if (!ok) listener = AndroidRepeatListener(context).takeIf { it.available }
            _ui.update { it.copy(listenerName = listener?.name ?: "사용할 수 없음") }
        } else AndroidRepeatListener(context).takeIf { it.available }
        val pm = context.packageManager
        val canMap = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), 0).isNotEmpty()
        val canShare = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0).isNotEmpty()
        _ui.update { it.copy(canOpenMap = canMap, canShare = canShare, listenerName = listener?.name ?: "사용할 수 없음") }
        scope.launch { kotlinx.coroutines.delay(3000); _ui.update { it.copy(voiceName = speaker.voiceName) } }
        if (isCar) watchUxRestrictions()
        // 처음 설치했을 때 한 번: 옆자리가 무엇을 해 주는지 음성으로 소개
        if (_ui.value.showIntro) speaker.intro(Phrases.INTRO)
        // 화면 카드는 음성이 실제로 그 문장을 읽기 시작할 때 바꿉니다 (들리는 말 = 보이는 글)
        speaker.onStart = { ev ->
            listener?.cancel()   // 옆자리가 말하는 동안에는 듣지 않음
            lastSaidTurn = _ui.value.drive.turn
            _ui.update { it.copy(card = ev.copy(timeMs = it.drive.timeMs), listening = false) }
        }
        scope.launch { replay.playing.collect { p -> _ui.update { it.copy(playing = p) } } }
        switchSource()
    }

    private fun switchSource() {
        collectJob?.cancel()
        source?.stop()
        val src: SignalSource = if (_ui.value.settings.demoMode || !isCar) replay else (real ?: RealSource(context).also { real = it })
        source = src
        src.start()
        collectJob = scope.launch { src.state.collect { onState(it) } }
    }

    private fun onState(s: DriveState) {
        if (s.gear == Gear.DRIVE && lastGear != Gear.DRIVE) {
            engine.resetTrip()
            speaker.clearPaused()
            resumeCount = 0
            _ui.update { it.copy(showReport = false, showSettings = false, card = null, askedQa = emptyList()) }
        }
        lastGear = s.gear

        // 시연: 운전자가 말로 질문한 장면 (실제 차량에서는 음성 인식으로 들어옴)
        if (s.demoQuestion != null && s.demoQuestion != lastDemoQuestion) answer(s.demoQuestion)
        lastDemoQuestion = s.demoQuestion

        val st = _ui.value.settings
        var events = engine.update(s)
        if (speaker.hasPaused) {
            // 미뤄 둔 대답이 있으면 강사 한마디는 빼고, 앞으로 충분히 조용할 때 한 번에 이어서 말함
            events = events.filter { it.stage != com.yeopjari.app.model.Stage.TALK }
            if (events.isEmpty() && !speaker.isBusy && engine.isQuietFor(s, speaker.pausedEstimateMs())) {
                speaker.resumePaused(if (resumeCount++ == 0) com.yeopjari.app.coach.Talk.RESUME else com.yeopjari.app.coach.Talk.RESUME_AGAIN)
            }
        }
        events.forEach { speaker.say(it) }

        var prevRate: Int? = null
        val report: TripReport? = recorder.update(s)?.let { r ->
            prevRate = store.lastSignalRate()
            if (r.maneuvers > 0) store.saveLastSignalRate(r.onTimeRate)   // 지난번 대비는 '제때 켠 비율'로 비교
            store.addSpots(r.harshBrakes)
            store.saveLastImprovement(r.improvement, r.avgFuelKmL)
            // 옆자리 포인트: 미션 1개 1P, 하루 최대 100P(시연 모드 제외). 운전 중에는 말하지 않고 주차 후에만 알려 줌.
            val got = store.addPoints(r.missions.size * Store.POINTS_PER_MISSION, capped = !st.demoMode)
            if (!st.demoMode) store.addTrip()
            store.addHistory(Store.TripSummary(System.currentTimeMillis(), r.safetyScore, if (r.maneuvers > 0) r.onTimeRate else null, r.harshBrakes.size))
            // 주차 위치 기억 (큰 주차장에서 차를 찾기 쉽게)
            if (s.lat != null && s.lon != null) store.saveParking(s.lat, s.lon, System.currentTimeMillis())
            if (st.coaching) {
                // 잘한 점 하나 → 미션 → 다음에 해 볼 것 하나 (짧게)
                val missionText = if (r.missions.isNotEmpty()) " 미션 ${r.missions.size}개를 성공해서 ${Store.points(got)}포인트를 모았어요." else ""
                val next = if (r.improvement.startsWith("오늘처럼")) " ${r.improvement}" else " 다음엔 이것 하나만요. ${r.improvement}"
                // 주행 중에 여유가 없어 못 한 대답은 리포트에 글로 남김
                val qa = if (speaker.hasPaused) " 주행 중에 물어보신 것은 리포트에 적어 뒀어요." else ""
                speaker.clearPaused()
                speaker.speak("수고하셨어요. ${r.goodPoint}$missionText$next$qa", com.yeopjari.app.model.Stage.DONE)
            }
            r.copy(points = got)
        }

        _ui.update { u ->
            // 다가오는 회전·출구 안내 카드는 그 지점을 지날 때까지 화면에 남겨 둡니다(9초 뒤 빈 화면이 되던 문제).
            val turnAhead = s.turn?.let { it.maneuver != com.yeopjari.app.model.Maneuver.NONE && it.distanceM > 5.0 } ?: false
            val keepCard = u.card?.takeIf {
                s.timeMs - it.timeMs < 9000 ||
                    (turnAhead && (it.stage == com.yeopjari.app.model.Stage.PREVIEW || it.stage == com.yeopjari.app.model.Stage.CHECK))
            }
            u.copy(
                drive = s,
                card = keepCard,
                report = report ?: u.report,
                prevSignalRate = if (report != null) prevRate else u.prevSignalRate,
                showReport = if (report != null) true else u.showReport,
                spotCount = if (report != null) store.loadSpots().size else u.spotCount,
                lastTip = if (report != null) report.improvement else u.lastTip,
                lastFuel = if (report != null) report.avgFuelKmL ?: u.lastFuel else u.lastFuel,
                pointsTotal = if (report != null) store.totalPoints() else u.pointsTotal,
                history = if (report != null) store.history() else u.history,
                parking = if (report != null) store.parking() else u.parking,
                reportDetail = if (report != null) false else u.reportDetail,
            )
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val old = _ui.value.settings
        val new = transform(old)
        store.saveSettings(new)
        _ui.update { it.copy(settings = new) }
        speaker.slow = new.slowSpeech
        speaker.chime = new.chime
        if (old.maleVoice != new.maleVoice) {
            speaker.male = new.maleVoice
            speaker.speak(if (new.maleVoice) "남자 목소리로 안내할게요." else "여자 목소리로 안내할게요.", com.yeopjari.app.model.Stage.DONE)
        }
        if (old.demoMode != new.demoMode) { speaker.forRecording = new.demoMode; switchSource() }
    }

    /**
     * 안내가 끝난 뒤 5초만 듣기 (주행 중, 코칭·설정이 켜져 있고 마이크 권한이 있을 때).
     * "다시·뭐라고"면 다시 말하고, 운전 질문이면 대답합니다. [question]: '질문하기'를 눌렀을 때 — 8초 듣고 모르는 질문에도 대답.
     */
    private fun startListening(question: Boolean = false) {
        val u = _ui.value
        if (!u.settings.coaching || (!question && (!u.settings.voiceRepeat || u.drive.gear != Gear.DRIVE))) return
        val l = listener ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        _ui.update { it.copy(listening = true, askWaiting = question) }
        l.listen(if (question) QUESTION_MS else LISTEN_MS,
            onHeard = { text, final -> onHeard(text, final, question) },
            onEnd = { _ui.update { it.copy(listening = false, askWaiting = false) } })
    }

    /** 들은 말 처리. true = 처리했으니 그만 들어도 됨 */
    private fun onHeard(text: String, final: Boolean, question: Boolean): Boolean {
        if (com.yeopjari.app.coach.RepeatWords.isAsk(text) && com.yeopjari.app.coach.Faq.find(text) == null) { repeatLast(text); return true }
        if (!final) return false
        val hit = com.yeopjari.app.coach.Faq.find(text)
        if (hit != null || question) { answer(text); return true }
        return false
    }

    /** '질문하기' 버튼: "네, 물어보세요" 다음에 8초 동안 듣기 */
    fun askQuestion() {
        if (!_ui.value.settings.coaching) return
        questionPending = true
        speaker.say(com.yeopjari.app.model.CoachEvent(com.yeopjari.app.model.Stage.TALK, "궁금한 걸 물어보세요", "예: 합류는 언제 들어가요? · 비보호 좌회전은요?", "네, 물어보세요.", _ui.value.drive.timeMs))
    }

    /**
     * 질문에 대답 — 준비한 대답을 짧은 문장으로 나눠 말합니다.
     * 곧 회전·주의 구간이라 대답할 여유가 없으면 "지금은 ○○ 먼저 볼게요"만 하고 미뤘다가, 여유 있을 때 한 번에 말합니다.
     * 대답 중 급한 안내가 오면 끊기고, 역시 여유 있을 때 이어서 말합니다. 끝까지 여유가 없으면 리포트에 글로 남깁니다.
     */
    private fun answer(question: String) {
        val hit = com.yeopjari.app.coach.Faq.find(question)
        val text = hit?.answer ?: com.yeopjari.app.coach.Faq.UNKNOWN
        val q = question.trim().trimEnd('?')
        _ui.update { it.copy(question = question, askedQa = it.askedQa + (q to text)) }
        scope.launch { kotlinx.coroutines.delay(8000); _ui.update { if (it.question == question) it.copy(question = null) else it } }
        val sentences = text.split(Regex("(?<=[.?!])\\s+")).filter { it.isNotBlank() }
        val head = "질문: $q?"
        val now = _ui.value.drive.timeMs
        val events = sentences.map { line -> com.yeopjari.app.model.CoachEvent(com.yeopjari.app.model.Stage.TALK, head, text, line, now, resumable = true) }
        val s = _ui.value.drive
        val need = events.sumOf { CoachEngine.estimateSpeechMs(it.speech) }
        if (s.gear != Gear.DRIVE || (!speaker.isBusy && engine.isQuietFor(s, need))) {
            events.forEach { speaker.say(it) }
        } else {
            speaker.say(com.yeopjari.app.model.CoachEvent(com.yeopjari.app.model.Stage.TALK, head, text, com.yeopjari.app.coach.Talk.defer(engine.busyReason(s)), now))
            speaker.deferTalk(events)
        }
    }

    /**
     * 마지막 안내 다시 듣기 (버튼 또는 말).
     * 아직 유효하면 그대로, 이미 지난 회전 안내면 지금 기준 다음 안내를, 없으면 지금 상태를 말합니다.
     */
    fun repeatLast(heard: String? = null) {
        val u = _ui.value
        heard?.let { h ->
            _ui.update { it.copy(heardText = h) }
            scope.launch { kotlinx.coroutines.delay(3000); _ui.update { it.copy(heardText = null) } }
        }
        val last = speaker.lastSaid
        val fresh = last != null && SystemClock.elapsedRealtime() - speaker.lastSaidAt <= 20_000L
        val now = u.drive.turn
        val turnAhead = now != null && now.maneuver != Maneuver.NONE && now.distanceM > 5.0
        val stillValid = last != null && (last.stage !in listOf(com.yeopjari.app.model.Stage.PREVIEW, com.yeopjari.app.model.Stage.CHECK) ||
            (turnAhead && lastSaidTurn?.maneuver == now!!.maneuver && now.distanceM <= (lastSaidTurn?.distanceM ?: 0.0) + 1.0))
        val ev = when {
            fresh && stillValid -> last!!.copy(stage = com.yeopjari.app.model.Stage.INFO)
            turnAhead -> {
                val words = if (now!!.maneuver == Maneuver.ARRIVE) "목적지가 ${Phrases.distanceWords(now.distanceM)} 앞이에요"
                    else "다음은 ${Phrases.distanceWords(now.distanceM)} 앞 ${Phrases.ida(Phrases.maneuverWord(now.maneuver))}"
                com.yeopjari.app.model.CoachEvent(com.yeopjari.app.model.Stage.INFO, words, "", "$words.", u.drive.timeMs)
            }
            else -> com.yeopjari.app.model.CoachEvent(com.yeopjari.app.model.Stage.INFO, "지금은 직진이에요", "필요할 때 알려 드릴게요.", "지금은 직진이에요. 필요할 때 알려 드릴게요.", u.drive.timeMs)
        }
        speaker.say(ev)
    }

    /** 차량의 '주행 중 화면 조작 제한' 상태를 따라, 제한 중에는 주행 화면만 보여 줌 */
    private fun watchUxRestrictions() {
        runCatching {
            val car = android.car.Car.createCar(context)
            val mgr = car.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE) as android.car.drivingstate.CarUxRestrictionsManager
            fun apply(r: android.car.drivingstate.CarUxRestrictions?) {
                _ui.update { it.copy(restricted = r?.isRequiresDistractionOptimization == true) }
            }
            apply(mgr.currentCarUxRestrictions)
            mgr.registerListener { r -> apply(r) }
        }
    }

    fun closeIntro() { store.setIntroDone(); _ui.update { it.copy(showIntro = false) } }
    fun showIntroAgain() { _ui.update { it.copy(showIntro = true, showSettings = false) }; speaker.intro(Phrases.INTRO) }
    fun toggleReportDetail() = _ui.update { it.copy(reportDetail = !it.reportDetail) }

    /** 주차 위치를 지도(내비) 앱으로 보여 줌 */
    fun openParking() {
        val (lat, lon, _) = _ui.value.parking ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode("내 차 주차 위치")})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 주행 결과 공유하기(가족 등) — 본인이 직접 누를 때만, 위치는 빼고 요약만 */
    fun shareReport() {
        val r = _ui.value.report ?: return
        val text = buildString {
            append("[옆자리 주행 리포트]\n")
            append("안전 점수 ${r.safetyScore}점 · 급제동 ${r.harshBrakes.size}회 · 깜빡이 제때 ${r.signalOnTime}/${r.maneuvers}회\n")
            append("잘한 점: ${r.goodPoint}\n")
            append("다음에 해 볼 것: ${r.improvement}")
        }
        runCatching {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "결과 공유하기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun play(id: Scenarios.Id) {
        if (!_ui.value.settings.demoMode) updateSettings { it.copy(demoMode = true) }
        // 시연 B는 급제동이 '처음' 생기는 이야기라, 이전 시연이 남긴 급제동 기록을 비우고 시작합니다.
        // (시연 모드의 기록은 모두 시연으로 생긴 것이라 실제 주행 기록에는 영향이 없습니다)
        if (id == Scenarios.Id.RIGHT_TURN) clearSpots()
        demoPlays++
        replay.play(id)
    }

    fun openSettings(show: Boolean) = _ui.update { it.copy(showSettings = show) }
    fun closeReport() = _ui.update { it.copy(showReport = false) }
    fun clearSpots() { store.clearSpots(); _ui.update { it.copy(spotCount = 0) } }

    companion object { const val LISTEN_MS = 5000L; const val QUESTION_MS = 8000L }

    fun stop() {
        listener?.release()
        source?.stop()
        speaker.shutdown()
        scope.cancel()
    }
}
