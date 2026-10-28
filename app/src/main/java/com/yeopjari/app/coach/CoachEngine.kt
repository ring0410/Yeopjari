package com.yeopjari.app.coach

import com.yeopjari.app.model.CautionKind
import com.yeopjari.app.model.CoachProfile
import com.yeopjari.app.model.CoachEvent
import com.yeopjari.app.model.DriveState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Mission
import com.yeopjari.app.model.Settings
import com.yeopjari.app.model.Signal
import com.yeopjari.app.model.Spot
import com.yeopjari.app.model.Stage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 코칭 규칙 엔진 — 안내(Guide) → 확인(Check) → 코칭(Coach)
 *
 * 차량·내비 상태를 매 순간 받아서, 지금 말해야 할 코칭이 있으면 돌려줍니다.
 * 안드로이드 기능을 쓰지 않는 순수 코드라서 컴퓨터에서 바로 테스트할 수 있습니다.
 *
 * 현대차 개발팀 관점 원칙
 *  - 차량·내비가 이미 하는 경고(안전벨트, 문 열림, 단속카메라, 경고등)는 반복하지 않는다.
 *  - 한 번에 한 문장, 같은 지적은 한 번만, 낮은 중요도 안내는 4초 간격을 둔다.
 *  - 방향지시등 확인·반대 방향·역주행 주의처럼 안전에 직결되는 안내는 앞 문장이 끝나는 대로 먼저 말한다.
 *  - 페달 오조작 의심처럼 아주 급한 순간만 말하던 문장을 끊고 바로 말한다.
 */
class CoachEngine(
    private val settingsProvider: () -> Settings,
    private val brakeSpotsProvider: () -> List<Spot> = { emptyList() },
    /** 운전자 실력(동작별 성공 횟수)·주행 횟수 — 처음엔 자세히, 익숙해지면 짧게 */
    private val profileProvider: () -> CoachProfile = { CoachProfile.NEW },
) {
    companion object {
        const val MIN_PREVIEW_M = 50.0
        const val CHECK_CITY_M = 50.0       // 일반 도로: 법정 30m보다 조금 앞에서 확인
        const val CHECK_HIGHWAY_M = 150.0   // 고속도로: 법정 100m보다 조금 앞에서 확인
        const val PEDESTRIAN_M = 100.0
        const val CHAIN_GAP_M = 150.0       // 두 동작 사이가 이보다 가까우면 '바로 이어서' 안내
        const val HARSH_ACCEL_KMH_PER_S = 11f   // 초기값 — 시험 주행으로 조정
        const val HARSH_BRAKE_KMH_PER_S = 7.5f  // 초기값 — 시험 주행으로 조정
        const val MEMORY_RADIUS_M = 300.0
        const val MEMORY_TURN_GAP_M = 150.0   // 회전·출구 150m 안에서는 급제동 기억 안내를 하지 않음
        const val MEMORY_CLUSTER_M = 150.0
        const val CAUTION_M = 300
        const val LOW_PRIORITY_GAP_MS = 4000L
        const val BREATH_MS = 1500L          // 문장과 문장 사이 쉬는 틈

        /** 한국어 음성 길이 추정: 글자당 약 0.22초 + 문장 쉼 0.7초 (실제 휴대폰 음성이 예상보다 느려 넉넉하게) */
        /** 글자당 읽는 시간(ms). 실제 차량·기기에서 잰 값으로 앱이 맞춰 줍니다 (테스트 기본값 220) */
        @Volatile var msPerChar: Long = 220L
        fun estimateSpeechMs(text: String): Long = text.count { !it.isWhitespace() } * msPerChar + 700L
        const val HABIT_COOLDOWN_MS = 15000L
        const val SIGNAL_LEFT_ON_MS = 8000L
        const val OVERSPEED_MARGIN = 10
        const val OVERSPEED_HOLD_MS = 5000L
        const val OVERSPEED_COOLDOWN_MS = 180_000L
        const val LONG_DRIVE_MS = 60 * 60 * 1000L   // 어르신·초보자 기준 1시간마다 휴식 권유
        /** 고속도로 출구·휴게소·갈림길 단계 안내 거리 (미리 → 중간 → 마지막) */
        val HIGHWAY_STAGES_M = listOf(3000, 1000, 500)
        const val CITY_FAR_M = 300.0   // 일반도로 1단계: 차로 미리
        const val CITY_MID_M = 100.0   // 일반도로 2단계: 깜빡이·뒤 확인 (최소 거리)
        const val NEAR_M = 40.0        // 3단계: 보행자·맞은편 차
        const val BLIND_BRAKE_GRACE_MS = 8000L   // 뒤차 경고 뒤 기다리느라 줄인 속도는 급제동으로 치지 않음
        const val SLOW_HOLD_MS = 20_000L
        const val SLOW_COOLDOWN_MS = 300_000L
        const val WRONG_EARLY_CITY_M = 150.0      // 일반도로: 회전 150m 안에서 반대쪽을 켜면 확인 지점 전에 바로 알려 줌
        const val WRONG_EARLY_HIGHWAY_M = 500.0   // 고속도로 출구·갈림길: 500m 안
        const val WRONG_HOLD_MS = 1500L           // 잠깐 잘못 건드린 것은 넘어가고, 1.5초 이상 켜져 있을 때만
        // 페달 오조작 의심: 멈춰 있던 차가 1.5초 안에 시속 20km를 넘음 (출발 직후 1분 또는 목적지 150m 안에서만)
        const val PEDAL_WINDOW_MS = 1500L
        const val PEDAL_SPEED_KMH = 20f
        const val PEDAL_AFTER_DRIVE_MS = 60_000L
        const val KEEP_DISTANCE_DELAY_MS = 8000L  // 급제동 직후 놀란 순간에는 말하지 않고, 조금 뒤 한 번만
        const val RAIN_COOLDOWN_MS = 30 * 60 * 1000L
        const val START_CHECK_TRIPS = 5           // 처음 5번의 주행은 출발 전 점검을 말해 줌
        const val MERGE_SLOW_KMH = 50f
        // 강사 한마디: 마지막 안내 뒤 이만큼 조용하면 말함 (강사 모드 / 보통)
        const val TALK_GAP_INSTRUCTOR_MS = 10_000L
        const val TALK_GAP_NORMAL_MS = 45_000L
        const val TALK_EVERY_INSTRUCTOR_MS = 20_000L
        const val TALK_EVERY_NORMAL_MS = 120_000L
        const val TALK_QUIET_AFTER_MS = 3_000L
        const val TALK_AFTER_START_MS = 20_000L
        const val TALK_GUARD_CITY_M = 350.0       // 회전 350m 안에서는 수다 금지
        const val TALK_GUARD_HIGHWAY_M = 1200.0   // 고속도로 출구·합류 1.2km 안에서는 수다 금지
    }

    /** 지금 다가오는 동작 하나에 대한 진행 상태 */
    private data class ManeuverProgress(
        val maneuver: Maneuver,
        var startDistance: Double,
        var previewed: Boolean = false,
        var nearSaid: Boolean = false,
        var checked: Boolean = false,
        var signalOkAtCheck: Boolean = false,
        var signalSeen: Boolean = false,
        /** 이번 동작에 맞는 쪽 방향지시등을 (늦게라도) 켠 적이 있음 */
        var rightSignalSeen: Boolean = false,
        /** 반대쪽 방향지시등이 켜진 시각 / 반대쪽 안내를 이미 했는지 */
        var wrongSince: Long? = null,
        var wrongSaid: Boolean = false,
        var lastDistance: Double = startDistance,
        var finished: Boolean = false,
        var finishedAt: Long = 0L,
        var leftOnSaid: Boolean = false,
        var ackSaid: Boolean = false,
        var farSaid: Boolean = false,
        /** 실수 안내 뒤 바로잡았을 때 '네, 맞아요'를 했는지 */
        var fixAckSaid: Boolean = false,
        var mergeSpeedSaid: Boolean = false,
        /** 고속도로 출구 단계 안내(3km·2km·1km) 중 이미 말한 단계 */
        val stagesSaid: MutableSet<Int> = mutableSetOf(),
    )

    private var current: ManeuverProgress? = null
    private var lastFinished: ManeuverProgress? = null
    private val speedHistory = ArrayDeque<Pair<Long, Float>>()
    private var lastSpokenAt = Long.MIN_VALUE / 2
    /** 지금 말하고 있는(또는 대기 중인) 문장이 끝나는 예상 시각 */
    private var busyUntil = Long.MIN_VALUE / 2
    private var lastHabitAt = Long.MIN_VALUE / 2
    private var lastHarshAccelAt = Long.MIN_VALUE / 2
    private var lastHarshBrakeAt = Long.MIN_VALUE / 2
    private var overSpeedSince: Long? = null
    private var lastOverSpeedAt = Long.MIN_VALUE / 2
    private var tripStartAt: Long? = null
    private var longDriveSaidAt: Long? = null
    private var startChecksDone = false
    private var lastRerouteCount = -1
    private val announcedCautions = mutableSetOf<String>()
    private val announcedSpots = mutableSetOf<Spot>()
    private val spotDist = mutableMapOf<Spot, Double>()
    private val announcedTraffic = mutableSetOf<String>()
    /** 후측방 경고를 말한 쪽 (풀리면 '지나갔어요'를 말하기 위해 기억) */
    private var blindWarnedSide: Signal? = null
    private var lastBlindWarnAt = Long.MIN_VALUE / 2
    private var slowSince: Long? = null
    private var lastSlowAt = Long.MIN_VALUE / 2
    private var passingLaneSaid = false
    private var lastGearSeen = Gear.PARK
    private var driveEngagedAt: Long? = null
    private val pedalHistory = ArrayDeque<Pair<Long, Float>>()
    private var lastPedalAt = Long.MIN_VALUE / 2
    private var keepDistanceDueAt: Long? = null
    private var keepDistanceSaid = false
    private var nightSaid = false
    private var wiperWasOn = false
    private var rainPending = false
    private var lastRainAt = Long.MIN_VALUE / 2
    // 강사 한마디
    private var lastTalkAt = Long.MIN_VALUE / 2
    private val talkUsed = mutableSetOf<String>()
    private var stoppedSince: Long? = null
    private var slowTalkSince: Long? = null
    private var leadInIdx = 0

    /** 주행 기록기에 넘길 사건들 */
    /** signalUsed = 맞는 쪽 방향지시등을 늦게라도 켰는지 */
    var onManeuverFinished: ((Maneuver, signalOnTime: Boolean, signalUsed: Boolean) -> Unit)? = null
    var onHarshAccel: ((DriveState) -> Unit)? = null
    var onHarshBrake: ((DriveState, nearManeuver: Maneuver?) -> Unit)? = null
    /** 미션 성공 (옆자리 포인트) */
    var onMission: ((Mission) -> Unit)? = null
    /** 일반 원칙 안내를 말했을 때 (몇 번 들었는지 기억해 다음부터 생략) */
    var onPrinciple: ((String) -> Unit)? = null

    fun resetTrip() {
        current = null
        lastFinished = null
        speedHistory.clear()
        announcedCautions.clear()
        announcedSpots.clear()
        spotDist.clear()
        announcedTraffic.clear()
        blindWarnedSide = null
        slowSince = null
        passingLaneSaid = false
        overSpeedSince = null
        tripStartAt = null
        longDriveSaidAt = null
        startChecksDone = false
        lastRerouteCount = -1
        pedalHistory.clear()
        keepDistanceDueAt = null
        keepDistanceSaid = false
        nightSaid = false
        wiperWasOn = false
        rainPending = false
        lastTalkAt = Long.MIN_VALUE / 2
        talkUsed.clear()
        stoppedSince = null
        slowTalkSince = null
    }

    fun update(s: DriveState): List<CoachEvent> {
        val out = mutableListOf<CoachEvent>()
        if (s.gear != Gear.DRIVE) {
            speedHistory.clear()
            pedalHistory.clear()
            lastGearSeen = s.gear
            return out
        }
        if (lastGearSeen != Gear.DRIVE) driveEngagedAt = s.timeMs
        lastGearSeen = s.gear
        val st = settingsProvider()
        if (tripStartAt == null) tripStartAt = s.timeMs
        handleStart(s, st, out)
        handleReroute(s, st, out)
        detectPedal(s, st, out)
        detectHabits(s, st, out)
        handleBlindSpot(s, st, out)
        handleTurn(s, st, out)
        handleSignalLeftOn(s, st, out)
        handleTraffic(s, st, out)
        handleCaution(s, st, out)
        handleMemory(s, st, out)
        handleOverSpeed(s, st, out)
        handleHighwayFlow(s, st, out)
        handleLongDrive(s, st, out)
        handleWeather(s, st, out)
        handleTalk(s, st, out)
        return out
    }

    /**
     * 앞으로 [durationMs] 동안 다른 안내를 할 일이 없을지 (질문 대답을 미루거나 이어 말할 때 판단).
     * 다음 회전·출구까지 수다 금지 거리 + 그동안 달릴 거리보다 멀고, 주의 구간·사고 정보·뒤차 경고·깜빡이가 없고,
     * 방금 한 안내가 끝나고 2초 이상 지났을 때만 '여유 있음'.
     */
    fun isQuietFor(s: DriveState, durationMs: Long): Boolean {
        if (s.gear != Gear.DRIVE) return false
        if (s.timeMs < busyUntil + 2000L) return false
        if (s.signal != Signal.OFF) return false
        if (s.blindLeft == true || s.blindRight == true || blindWarnedSide != null) return false
        s.caution?.let { if (it.distanceM < CAUTION_M + 100) return false }
        s.traffic?.let { if (it.distanceM < 2500.0) return false }
        val t = s.turn
        if (t != null && t.maneuver != Maneuver.NONE) {
            val highway = s.speedKmh >= 70f || t.maneuver.isHighway
            val guard = if (highway) TALK_GUARD_HIGHWAY_M else TALK_GUARD_CITY_M
            val travel = s.speedKmh / 3.6 * durationMs / 1000.0
            if (t.distanceM < guard + travel) return false
        }
        return true
    }

    /** 대답을 미룰 때 '지금은 ○○ 먼저 볼게요'의 ○○ (가까운 회전·출구 이름) */
    fun busyReason(s: DriveState): String? = s.turn?.takeIf { it.maneuver != Maneuver.NONE }?.let { Phrases.maneuverWord(it.maneuver) }

    // ---------------- 강사 한마디 (T-xx) ----------------
    // 알림이 없는 조용한 시간에만, 동승한 강사처럼 짧은 요령을 들려줍니다.
    // 회전·합류가 가깝거나, 주의 구간·사고 정보·뒤차 경고가 있거나, 깜빡이를 켠 동안, 급제동 직후에는 말하지 않습니다.

    private fun handleTalk(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        if (!st.coaching || st.talkLevel == com.yeopjari.app.model.TalkLevel.QUIET) return
        val now = s.timeMs
        val instructor = st.talkLevel == com.yeopjari.app.model.TalkLevel.INSTRUCTOR
        // 멈춰 있음 / 정체 지속 시간
        if (s.speedKmh < 1f) { if (stoppedSince == null) stoppedSince = now } else stoppedSince = null
        if (s.speedKmh in 1f..20f) { if (slowTalkSince == null) slowTalkSince = now } else slowTalkSince = null

        val sinceSpoken = if (instructor) TALK_GAP_INSTRUCTOR_MS else TALK_GAP_NORMAL_MS
        val sinceTalk = if (instructor) TALK_EVERY_INSTRUCTOR_MS else TALK_EVERY_NORMAL_MS
        if (now - lastSpokenAt < sinceSpoken || now - lastTalkAt < sinceTalk || now < busyUntil + TALK_QUIET_AFTER_MS) return
        val engaged = driveEngagedAt ?: return
        if (now - engaged < TALK_AFTER_START_MS) return
        if (s.signal != Signal.OFF) return
        if (s.blindLeft == true || s.blindRight == true || blindWarnedSide != null) return
        if (now - lastHarshBrakeAt < 10_000L || now - lastHarshAccelAt < 10_000L) return
        if (overSpeedSince != null) return
        val highway = s.speedKmh >= 70f || s.turn?.maneuver?.isHighway == true
        s.turn?.let { t ->
            val guard = if (highway) TALK_GUARD_HIGHWAY_M else TALK_GUARD_CITY_M
            if (t.maneuver != Maneuver.NONE && t.distanceM < guard) return
        }
        s.caution?.let { if (it.distanceM < CAUTION_M + 100) return }
        s.traffic?.let { if (it.distanceM < 2500.0) return }
        if (lastFinished != null && !lastFinished!!.leftOnSaid && now - lastFinished!!.finishedAt < 6000L) return

        val ctxs = buildList {
            if (s.wiperOn == true) add(Talk.Ctx.RAIN)
            if (s.dark == true) add(Talk.Ctx.NIGHT)
            stoppedSince?.let { if (now - it > 4000L) add(Talk.Ctx.STOPPED) }
            slowTalkSince?.let { if (now - it > 10_000L) add(Talk.Ctx.SLOW) }
            if (s.speedKmh >= 70f) add(Talk.Ctx.HIGHWAY) else if (s.speedKmh >= 15f) add(Talk.Ctx.CITY)
            // 3분 동안 급가속·급제동이 없었으면 칭찬도 섞어 줌
            if (now - maxOf(lastHarshBrakeAt, lastHarshAccelAt) > 180_000L && now - engaged > 120_000L) add(Talk.Ctx.PRAISE)
        }
        // 강사 모드의 첫마디: 오늘 함께한다는 인사 + 궁금한 건 물어봐도 된다는 안내
        val tip = (if (instructor && talkUsed.isEmpty()) Talk.invite else null)
            ?: ctxs.asSequence().flatMap { Talk.pool(it).asSequence() }.firstOrNull { it.id !in talkUsed } ?: return
        talkUsed += tip.id
        lastTalkAt = now
        lastSpokenAt = now
        // 강사 한마디는 다른 안내가 오면 바로 끊기므로, 다른 안내의 시작 시각(busyUntil)을 미루지 않습니다.
        out += CoachEvent(Stage.TALK, "옆자리 한마디", tip.text, tip.text, now)
    }

    // ---------------- 출발 직후 (P-04, P-05) ----------------

    private fun handleStart(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        if (startChecksDone) return
        startChecksDone = true
        // 차량이 이미 하는 안전벨트 경고음은 반복하지 않고, 출발할 때 한 번만 함께 말합니다.
        val belt = if (s.seatbelt == false) Phrases.seatbelt else null
        // 처음 몇 번의 주행에는 거울·의자·안전벨트 점검을 짧게 (초보·오랜만에 운전하는 분)
        val check = if (belt == null && profileProvider().trips < START_CHECK_TRIPS) Phrases.startCheck else null
        val fuel = s.fuelPercent?.takeIf { it in 0..15 }?.let { Phrases.lowFuel(it) }
        val route = if (s.routeActive == false) Phrases.noRoute else null
        val lines = listOfNotNull(belt, check, fuel, route)
        if (lines.isEmpty()) return
        // 여러 개를 따로 말하면 뒤의 것이 밀려 버려지므로, 한 번에 이어서 말합니다. 화면 제목은 가장 중요한 것.
        val main = belt ?: fuel ?: route ?: check!!
        // 화면 제목과 처음 들리는 말이 같도록, 가장 중요한 것을 먼저 말합니다.
        val ordered = listOf(main) + (lines - main)
        val speech = ordered.joinToString(" ") { "${it.headline}. ${it.body}" }
        emit(out, Stage.INFO, main.headline, ordered.joinToString(" ") { it.body }, speech, s.timeMs, st, force = belt != null || fuel != null)
    }

    // ---------------- 경로 재탐색 (R-01) ----------------

    private fun handleReroute(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        if (lastRerouteCount < 0) { lastRerouteCount = s.rerouteCount; return }
        if (s.rerouteCount > lastRerouteCount) {
            lastRerouteCount = s.rerouteCount
            current = null // 이전 경로의 동작은 버림
            val line = Phrases.reroute
            emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = true)
        }
    }

    // ---------------- 회전·합류 코칭 ----------------

    private fun handleTurn(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val turn = s.turn
        val m = turn?.maneuver ?: Maneuver.NONE
        val dist = turn?.distanceM ?: Double.MAX_VALUE

        // 새 동작이 시작됐는지 판단: 종류가 바뀌었거나 남은 거리가 크게 늘어났을 때
        val cur = current
        val isNew = cur == null || cur.maneuver != m || dist > cur.lastDistance + 100.0
        if (isNew) {
            cur?.let { finish(it, s, st, out) }
            current = if (m == Maneuver.NONE) null else ManeuverProgress(m, dist)
        }
        val p = current ?: return
        p.lastDistance = dist
        if (p.finished) return // 지나간 동작: 다음 동작이 올 때까지 대기
        if (s.signal != Signal.OFF) p.signalSeen = true

        val mps = s.speedKmh / 3.6
        val fast = s.speedKmh >= 100f
        val leadSec = if (!fast) 10.0 else 6.0
        // 저속에서도 너무 늦지 않게 최소 120m 전에는 알려 줌
        val previewDist = maxOf(mps * leadSec + MIN_PREVIEW_M, 120.0)
        val checkDist = if (m.isHighway || s.speedKmh >= 70f) CHECK_HIGHWAY_M else CHECK_CITY_M

        // 이번 동작에서 켜야 하는 방향지시등 (합류는 내비가 알려 준 '들어갈 쪽'이 있으면 그쪽)
        val expectedSig: Signal? = if (m == Maneuver.MERGE && turn?.side != null) turn.side else m.expectedSignal

        val sideSig = expectedSig?.takeIf { it != Signal.OFF }
        val chain = s.nextTurn?.let { n ->
            val gap = if (n.distanceM >= dist) n.distanceM - dist else n.distanceM
            if (n.maneuver != Maneuver.NONE && n.maneuver != Maneuver.ARRIVE && gap <= CHAIN_GAP_M) Phrases.chained(n.maneuver) else null
        }
        // 초보·고령 운전자를 위해 직진 외 동작은 '미리 → 중간 → 마지막' 여러 번 나눠 안내합니다.
        val highwayStaged = sideSig != null && s.speedKmh >= 60f &&
            (m == Maneuver.EXIT_R || m == Maneuver.EXIT_L || m == Maneuver.REST_AREA || m == Maneuver.KEEP_RIGHT || m == Maneuver.KEEP_LEFT)
        val cityStaged = !highwayStaged && sideSig != null && m != Maneuver.MERGE

        if (highwayStaged) {
            // ⓐ 고속도로 출구·휴게소·갈림길: 3km → 1km → 500m (T-18~T-20)
            //    SDK가 '지금 몇 차로'를 주지 않으므로 차로를 추측하지 않고 운전자가 스스로 확인하게 말합니다.
            for (stage in HIGHWAY_STAGES_M) {
                val window = if (stage == 500) dist <= 500.0 && dist > checkDist else dist <= stage && dist > stage - 400
                if (stage in p.stagesSaid || !window) continue
                p.stagesSaid += stage
                if (stage == 500) p.previewed = true
                val line = Phrases.highwayStage(stage, m, sideSig!!, s.signal == sideSig)
                emit(out, Stage.PREVIEW, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = true)
            }
            // 단계 사이에 스스로 켜면 다음 할 일(뒤 확인)로 이어 줌 (S-06)
            if (!p.ackSaid && !p.checked && s.signal == sideSig && dist > checkDist + 30.0 && 500 !in p.stagesSaid) {
                val line = Phrases.signalAck(sideSig!!)
                if (emit(out, Stage.PREVIEW, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) p.ackSaid = true
            }
        } else if (cityStaged) {
            // ⓑ 일반도로 회전: 약 300m 전 '차로 미리' → 확인 지점 전 '깜빡이·뒤 확인' → 40m 전 보행자·맞은편 차
            val skill = profileProvider().skill(m)
            val farLine = Phrases.cityFar(m, sideSig!!, skill)
            if (!p.farSaid && dist <= CITY_FAR_M && dist > CITY_FAR_M - 150.0) {
                p.farSaid = true
                val body = listOfNotNull(farLine.body.ifBlank { null }, chain).joinToString(" ")
                emit(out, Stage.PREVIEW, farLine.headline, body, "${Phrases.whenWords(dist)} ${farLine.headline}. $body".trim(), s.timeMs, st, force = true)
            }
            val midLine = Phrases.cityMid(m, sideSig, s.signal == sideSig, skill)
            val midSpeech = listOfNotNull("${midLine.headline}.", midLine.body, if (!p.farSaid) chain else null).joinToString(" ")
            val midDist = maxOf(CITY_MID_M, checkDist + mps * (estimateSpeechMs(midSpeech) / 1000.0 + 1.5))
            if (!p.previewed && dist <= midDist && dist > checkDist) {
                p.previewed = true
                emit(out, Stage.PREVIEW, midLine.headline, midLine.body, midSpeech, s.timeMs, st, force = true)
            }
        } else {
            // ⓒ 합류·톨게이트·터널·회전교차로·목적지: 한 번 미리 알림 (T-06, T-10, T-13~T-16)
            val previewFloor = if (expectedSig != null) checkDist else 20.0
            if (!p.previewed && dist <= previewDist + 400.0 && dist > previewFloor) {
                val line = Phrases.preview(m, s.headlightOn, turn?.side)
                val words = Phrases.whenWords(dist)
                val body = listOfNotNull(line.body, chain).joinToString(" ")
                val speech = if (fast) listOfNotNull("$words, ${line.headline}.", chain).joinToString(" ")
                else "$words, ${line.headline}. $body"
                // 확인 지점에 닿기 전에 다 읽고 1.5초 여유가 남도록 일찍 시작합니다.
                val needDist = if (expectedSig != null) checkDist + mps * (estimateSpeechMs(speech) / 1000.0 + 1.5) else 0.0
                if (dist <= maxOf(previewDist, needDist, checkDist + 30.0)) {
                    p.previewed = true
                    emit(out, Stage.PREVIEW, line.headline, body, speech, s.timeMs, st, force = true)
                }
            }
        }

        // 동작 직전 한마디(약 40m 전): 우회전 보행자(T-02), 좌회전·유턴 맞은편 차(T-04)
        val nearLine = when (m) {
            Maneuver.RIGHT -> Phrases.rightTurnPedestrian
            Maneuver.LEFT, Maneuver.UTURN -> Phrases.leftTurnOncoming
            else -> null
        }
        if (nearLine != null && p.previewed && !p.nearSaid && dist <= NEAR_M && dist > 8.0) {
            p.nearSaid = true
            emit(out, Stage.PREVIEW, nearLine.headline, nearLine.body, nearLine.body, s.timeMs, st, force = false)
        }

        if (expectedSig != null && (if (expectedSig == Signal.OFF) s.signal != Signal.OFF else s.signal == expectedSig)) p.rightSignalSeen = true

        // ①-2 반대쪽 방향지시등은 확인 지점을 기다리지 않고 바로 알려 줍니다 (S-03).
        //      초보·어르신은 잘못 켠 줄 모르고 그대로 가는 경우가 많아서, 일찍 알려 드려야 고칠 여유가 있어요.
        val wrongOn = sideSig != null && (cityStaged || highwayStaged) && s.signal != Signal.OFF && s.signal != sideSig
        if (wrongOn) {
            val since = p.wrongSince ?: s.timeMs.also { p.wrongSince = it }
            val early = if (highwayStaged) WRONG_EARLY_HIGHWAY_M else WRONG_EARLY_CITY_M
            if (!p.wrongSaid && !p.checked && dist <= early && dist > checkDist && s.timeMs - since >= WRONG_HOLD_MS) {
                p.wrongSaid = true
                val line = Phrases.wrongSide(sideSig!!)
                emit(out, Stage.CHECK, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = true)
            }
        } else {
            p.wrongSince = null
        }

        // 고속도로 합류인데 너무 느리면 속도를 맞추도록 (T-23). 뒤차를 기다리느라 줄인 속도는 제외.
        val joining = m == Maneuver.MERGE || m == Maneuver.ENTER_HIGHWAY_L || m == Maneuver.ENTER_HIGHWAY_R
        if (joining && !p.mergeSpeedSaid && (s.speedLimit ?: 0) >= 80 && dist <= 300.0 && dist > checkDist &&
            s.speedKmh in 5f..MERGE_SLOW_KMH && blindWarnedSide == null && s.timeMs - lastBlindWarnAt > BLIND_BRAKE_GRACE_MS
        ) {
            val line = Phrases.mergeSpeed
            if (emit(out, Stage.HABIT, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) p.mergeSpeedSaid = true
        }

        // ② 확인: 방향지시등 (S-01 미점등, S-02 합류, S-03 반대 방향)
        val expected = expectedSig
        if (!p.checked && dist <= checkDist && expected != null) {
            p.checked = true
            val ok = if (expected == Signal.OFF) s.signal != Signal.OFF else s.signal == expected
            // 반대쪽을 켰다가 안내를 듣고 바로잡은 경우는 '제때'로 치지 않습니다 (칭찬 대신 다음 연습 포인트).
            p.signalOkAtCheck = ok && !p.wrongSaid
            if (!ok) {
                val line = if (expected != Signal.OFF && s.signal != Signal.OFF) Phrases.wrongSide(expected) else Phrases.check(expected)
                emit(out, Stage.CHECK, line.headline, line.body, "${line.headline}. ${if (line.id == "S-03") line.body else ""}".trim(), s.timeMs, st, force = true)
            }
        }

        // 실수 안내(켜 주세요·반대쪽) 뒤 바로잡으면 "네, 맞아요" 한마디로 안심시킴 (S-08)
        val corrected = expected != null && (if (expected == Signal.OFF) s.signal != Signal.OFF else s.signal == expected)
        // 앞의 확인 문장이 거의 끝났을 때 말해서, 한참 뒤에 엉뚱하게 들리지 않게 합니다.
        if (!p.fixAckSaid && corrected && (p.wrongSaid || (p.checked && !p.signalOkAtCheck)) && dist > 5.0 && busyUntil - s.timeMs <= 2500L) {
            p.fixAckSaid = true
            val line = Phrases.fixed
            emit(out, Stage.INFO, line.headline, line.body, "${line.headline}.", s.timeMs, st, force = true)
        }

        // ③ 완료: 지점을 지나면
        if (dist <= 5.0) finish(p, s, st, out)
    }

    private fun finish(p: ManeuverProgress, s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        if (p.finished) return
        p.finished = true
        p.finishedAt = s.timeMs
        p.maneuver.expectedSignal ?: return
        if (!p.checked) return // 확인 지점까지 오지 않은 동작은 기록하지 않음 (경로 변경 등)
        lastFinished = p
        onManeuverFinished?.invoke(p.maneuver, p.signalOkAtCheck, p.rightSignalSeen)
        if (p.signalOkAtCheck) onMission?.invoke(Mission.SIGNAL_ON_TIME)
        // 바로 이어지는 회전(150m 이내)이 있으면 칭찬보다 다음 안내가 먼저예요.
        val upcoming = if (s.turn?.maneuver == p.maneuver && (s.turn?.distanceM ?: 0.0) <= 5.0) s.nextTurn else s.turn
        val chainedNext = upcoming != null && upcoming.maneuver != Maneuver.NONE &&
            upcoming.maneuver != Maneuver.ARRIVE && upcoming.distanceM <= CHAIN_GAP_M
        if (p.signalSeen && !chainedNext) {
            // 제때 켰으면 칭찬, 늦게 켰으면 다음 연습 포인트 (실수 뒤 '잘하셨어요'는 어색함)
            val line = if (p.signalOkAtCheck) Phrases.done(p.maneuver) else Phrases.doneLate(p.maneuver)
            // 칭찬은 짧고 동기부여에 중요하므로 앞 문장이 끝난 뒤 이어서 말합니다 (끊지 않고 대기)
            emit(out, Stage.DONE, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = true)
        }
    }

    // ---------------- 회전 후에도 방향지시등이 켜져 있음 (S-04) ----------------

    private fun handleSignalLeftOn(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val f = lastFinished ?: return
        if (f.leftOnSaid) return
        if (s.signal == Signal.OFF) {
            // 미션: 회전이 끝나고 경고를 듣기 전에 스스로 방향지시등을 끔
            if (f.signalSeen) onMission?.invoke(Mission.SIGNAL_OFF)
            lastFinished = null; return
        }
        val nextNear = current?.let { !it.finished && it.lastDistance <= 200.0 } ?: false
        if (!nextNear && s.timeMs - f.finishedAt >= SIGNAL_LEFT_ON_MS && s.speedKmh >= 10f) {
            f.leftOnSaid = true
            val line = Phrases.signalLeftOn
            emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)
        }
    }

    // ---------------- 급가속·급제동 (H-01, H-02) ----------------

    private fun detectHabits(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        speedHistory.addLast(s.timeMs to s.speedKmh)
        while (speedHistory.isNotEmpty() && s.timeMs - speedHistory.first().first > 1000L) {
            speedHistory.removeFirst()
        }
        val first = speedHistory.firstOrNull() ?: return
        val dt = (s.timeMs - first.first) / 1000f
        if (dt < 0.8f) return
        val rate = (s.speedKmh - first.second) / dt // km/h per second

        if (rate >= HARSH_ACCEL_KMH_PER_S && s.speedKmh >= 6f && s.timeMs - lastHarshAccelAt > 5000L) {
            lastHarshAccelAt = s.timeMs
            onHarshAccel?.invoke(s)
            if (s.timeMs - lastHabitAt > HABIT_COOLDOWN_MS) {
                lastHabitAt = s.timeMs
                val line = Phrases.harshAccel
                emit(out, Stage.HABIT, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)
            }
        }
        if (-rate >= HARSH_BRAKE_KMH_PER_S && s.timeMs - lastHarshBrakeAt > 5000L) {
            lastHarshBrakeAt = s.timeMs
            // H-02: 놀란 순간에는 말하지 않고 위치만 기록합니다.
            // 단, 뒤차 경고를 듣고 기다리느라 속도를 줄인 것은 안내를 잘 따른 행동이므로 급제동으로 치지 않습니다.
            if (blindWarnedSide != null || s.timeMs - lastBlindWarnAt < BLIND_BRAKE_GRACE_MS) return
            val near = current?.takeIf { it.lastDistance <= 150.0 }?.maneuver
            onHarshBrake?.invoke(s, near)
            if (!keepDistanceSaid && keepDistanceDueAt == null) keepDistanceDueAt = s.timeMs + KEEP_DISTANCE_DELAY_MS
        }
        // H-09 급제동 뒤 조금 지나 한 번만, 회전 직전이 아닐 때 '앞차와 거리'를 부드럽게
        val due = keepDistanceDueAt
        val turnClose = current?.let { !it.finished && it.lastDistance <= 150.0 } ?: false
        // 회전 뒤 '깜빡이 끄기' 확인이 남아 있으면 그것이 먼저예요.
        if (due != null && s.timeMs >= due && !turnClose && lastFinished == null) {
            val line = Phrases.keepDistance
            if (emit(out, Stage.HABIT, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) {
                keepDistanceSaid = true; keepDistanceDueAt = null
            }
        }
    }

    // ---------------- 페달 오조작 의심 (H-08) ----------------
    // 어르신 사고에서 많은 '브레이크 대신 가속 페달'. 멈춰 있던 차가 순간적으로 빨라지면 바로 알려 줍니다.
    // 출발 직후(주차장)나 목적지 근처처럼 오조작이 잦은 때만 봐서, 평소 빠른 출발과 헷갈리지 않게 합니다.

    private fun detectPedal(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        pedalHistory.addLast(s.timeMs to s.speedKmh)
        while (pedalHistory.isNotEmpty() && s.timeMs - pedalHistory.first().first > PEDAL_WINDOW_MS) pedalHistory.removeFirst()
        if (s.speedKmh < PEDAL_SPEED_KMH || s.timeMs - lastPedalAt < 30_000L) return
        val wasStopped = pedalHistory.any { it.second < 3f }
        if (!wasStopped) return
        val afterStart = driveEngagedAt?.let { s.timeMs - it <= PEDAL_AFTER_DRIVE_MS } ?: false
        val nearArrive = s.turn?.let { it.maneuver == Maneuver.ARRIVE && it.distanceM <= 150.0 } ?: false
        if (!afterStart && !nearArrive) return
        lastPedalAt = s.timeMs
        val line = Phrases.pedal
        if (!st.coaching) return
        lastSpokenAt = s.timeMs
        busyUntil = s.timeMs + estimateSpeechMs(line.headline)
        out += CoachEvent(Stage.CHECK, line.headline, line.body, "${line.headline}. ${line.headline}.", s.timeMs, urgent = true)
    }

    // ---------------- 밤·비 (P-08, P-09) ----------------

    private fun handleWeather(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        if (!nightSaid && s.dark == true && s.headlightOn == false && s.speedKmh >= 10f) {
            val line = Phrases.night
            if (emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) nightSaid = true
        }
        val wiper = s.wiperOn == true
        if (wiper && !wiperWasOn && s.timeMs - lastRainAt >= RAIN_COOLDOWN_MS) rainPending = true
        wiperWasOn = wiper
        if (rainPending && wiper && s.speedKmh >= 20f) {
            val line = Phrases.rain(s.headlightOn == false)
            if (emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) {
                rainPending = false; lastRainAt = s.timeMs
            }
        }
        if (!wiper) rainPending = false
    }

    // ---------------- 주의 구간 (C-01~C-12) ----------------

    private fun handleCaution(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val c = s.caution ?: return
        if (c.distanceM > CAUTION_M || c.id in announcedCautions) return
        val critical = c.kind == CautionKind.WRONG_WAY || c.kind == CautionKind.RAILROAD
        // 역주행·철길처럼 위험이 큰 곳은 앞의 말이 끝나는 대로 꼭 알림 (다른 주의 구간은 말할 틈이 있을 때)
        val line = Phrases.caution(c.kind) ?: return
        // 버스전용차로처럼 '규칙을 알려 주는' 안내는 여러 번 들었으면 생략
        if (c.kind == CautionKind.BUS_LANE && profileProvider().heard(line.id) >= CoachProfile.PRINCIPLE_TIMES) { announcedCautions += c.id; return }
        if (emit(out, Stage.CAUTION, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = critical)) {
            announcedCautions += c.id
            if (c.kind == CautionKind.BUS_LANE) onPrinciple?.invoke(line.id)
        }
    }

    // ---------------- 지난번 급제동 지점 (H-03) ----------------

    private fun handleMemory(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val lat = s.lat ?: return
        val lon = s.lon ?: return
        // 회전·출구까지 150m 안에서는 말하지 않음 (깜빡이 확인처럼 더 중요한 안내와 겹치지 않도록)
        val turnClose = current?.let { !it.finished && it.lastDistance <= MEMORY_TURN_GAP_M } ?: false
        val spots = brakeSpotsProvider()
        for (spot in spots) {
            if (spot in announcedSpots) continue
            val d = distanceM(lat, lon, spot.lat, spot.lon)
            val prev = spotDist[spot]
            spotDist[spot] = d
            val approaching = prev != null && d < prev   // 지나간 뒤(멀어지는 중)에는 말하지 않음
            if (d in 30.0..MEMORY_RADIUS_M && approaching && !turnClose) {
                val line = Phrases.brakeMemory
                if (emit(out, Stage.MEMORY, line.headline, line.body, line.body, s.timeMs, st, force = false)) {
                    // 서로 가까운 지점(150m 안)은 한 번만 말함
                    spots.filter { distanceM(it.lat, it.lon, spot.lat, spot.lon) < MEMORY_CLUSTER_M }.forEach { announcedSpots += it }
                }
                break
            }
        }
    }

    // ---------------- 제한속도 (H-04) ----------------
    // 내비가 단속카메라 경고를 하므로, 옆자리는 '오래 계속 빠를 때'만 부드럽게 한 번 말함

    private fun handleOverSpeed(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val limit = s.speedLimit ?: run { overSpeedSince = null; return }
        if (limit <= 0) return
        if (s.speedKmh >= limit + OVERSPEED_MARGIN) {
            val since = overSpeedSince ?: s.timeMs.also { overSpeedSince = it }
            if (s.timeMs - since >= OVERSPEED_HOLD_MS && s.timeMs - lastOverSpeedAt >= OVERSPEED_COOLDOWN_MS) {
                val line = Phrases.overSpeed(limit)
                if (emit(out, Stage.HABIT, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) {
                    lastOverSpeedAt = s.timeMs
                }
            }
        } else overSpeedSince = null
    }

    // ---------------- 장시간 운전 (H-05) ----------------

    private fun handleLongDrive(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val start = tripStartAt ?: return
        val elapsed = s.timeMs - start
        val last = longDriveSaidAt
        val due = if (last == null) elapsed >= LONG_DRIVE_MS else s.timeMs - last >= 60 * 60 * 1000L
        if (due) {
            val line = Phrases.longDrive(maxOf(1, (elapsed / LONG_DRIVE_MS).toInt()))
            if (emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) {
                longDriveSaidAt = s.timeMs
            }
        }
    }

    // ---------------- 고속도로 흐름: 1차로 추월차로(H-07), 너무 느린 주행(H-06) ----------------
    // SDK에 '고속도로 여부'가 없어 제한속도 80km 이상 도로를 고속도로로 봅니다.
    // 현재 차로는 알 수 없으므로 '지금 1차로에 있다'고 판단하지 않고 일반 원칙만 한 번 알려 줍니다.

    private fun handleHighwayFlow(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val limit = s.speedLimit ?: run { slowSince = null; return }
        if (limit < 80) { slowSince = null; return }
        if (!passingLaneSaid && s.speedKmh >= 60f) {
            val line = Phrases.passingLane
            // 여러 번 들은 일반 원칙은 생략 (잔소리가 되지 않도록)
            if (profileProvider().heard(line.id) >= CoachProfile.PRINCIPLE_TIMES) passingLaneSaid = true
            else if (emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) {
                passingLaneSaid = true; onPrinciple?.invoke(line.id)
            }
        }
        val jam = s.traffic?.let { it.kind == com.yeopjari.app.model.TrafficKind.CONGESTION && it.distanceM < 2000.0 } ?: false
        val turnNear = current?.let { !it.finished && it.lastDistance < 1200.0 } ?: false
        if (s.speedKmh in 20f..(limit * 0.6f) && !jam && !turnNear) {
            val since = slowSince ?: s.timeMs.also { slowSince = it }
            if (s.timeMs - since >= SLOW_HOLD_MS && s.timeMs - lastSlowAt >= SLOW_COOLDOWN_MS) {
                val line = Phrases.tooSlow
                if (emit(out, Stage.HABIT, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = false)) {
                    lastSlowAt = s.timeMs; slowSince = null
                }
            }
        } else slowSince = null
    }

    // ---------------- 후측방(사각지대) 경고 (B-01, B-02) ----------------
    // 차로를 옮기려고 방향지시등을 켠 쪽 뒤에 차가 있으면, 지나갈 때까지 기다리라고 알려 줍니다.
    // 신호: Vehicle SDK 계기판 표시등 LCA_LEFT/LCA_RIGHT (후측방 충돌방지 보조). 기능이 없는 차량은 null → 말하지 않음.

    private fun handleBlindSpot(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val warned = blindWarnedSide
        if (warned != null) {
            val still = if (warned == Signal.LEFT) s.blindLeft else s.blindRight
            if (still == true) lastBlindWarnAt = s.timeMs
            if (still != true) {
                blindWarnedSide = null
                if (s.signal == warned) {
                    val line = Phrases.blindClear(warned)
                    emit(out, Stage.INFO, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = true)
                }
            }
            return
        }
        val side = s.signal.takeIf { it != Signal.OFF } ?: return
        if (s.speedKmh < 5f) return
        val car = if (side == Signal.LEFT) s.blindLeft else s.blindRight
        if (car == true) {
            blindWarnedSide = side
            lastBlindWarnAt = s.timeMs
            val line = Phrases.blindSpot(side)
            emit(out, Stage.CHECK, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = true)
        }
    }

    // ---------------- 실시간 교통: 사고·공사·정체 (TR-01~06) ----------------

    private fun handleTraffic(s: DriveState, st: Settings, out: MutableList<CoachEvent>) {
        val t = s.traffic ?: return
        if (t.id in announcedTraffic) return
        val range = if (s.speedKmh >= 70f) 2000.0 else 800.0
        if (t.distanceM > range || t.distanceM < 100.0) return
        val line = Phrases.traffic(t)
        val critical = t.kind == com.yeopjari.app.model.TrafficKind.ACCIDENT
        if (emit(out, Stage.CAUTION, line.headline, line.body, "${line.headline}. ${line.body}", s.timeMs, st, force = critical)) {
            announcedTraffic += t.id
        }
    }

    private fun emit(
        out: MutableList<CoachEvent>, stage: Stage, headline: String, body: String, speech: String,
        now: Long, st: Settings, force: Boolean,
    ): Boolean {
        if (!st.coaching) return true // 옆자리 코칭 끔: 말하지 않고 기록만 합니다
        // 앞 문장이 끝나고 잠시 숨 돌릴 틈이 생기기 전에는 낮은 우선순위 안내를 꺼내지 않습니다.
        if (!force && (now - lastSpokenAt < LOW_PRIORITY_GAP_MS || now < busyUntil + BREATH_MS)) return false
        lastSpokenAt = now
        // 강사 모드: 미리 알림 앞에 '자, 여기 중요해요' 같은 짧은 말을 붙여 강사처럼 (시간이 넉넉한 미리 알림에만)
        val said = if (st.talkLevel == com.yeopjari.app.model.TalkLevel.INSTRUCTOR && stage == Stage.PREVIEW) {
            Talk.leadIns[leadInIdx++ % Talk.leadIns.size] + speech
        } else speech
        busyUntil = maxOf(now, busyUntil) + estimateSpeechMs(said)
        out += CoachEvent(stage, headline, body, said, now)
        return true
    }
}

/** 두 위경도 사이 거리(m) */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}
