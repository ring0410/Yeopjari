package com.yeopjari.app.source

import com.yeopjari.app.model.Caution
import com.yeopjari.app.model.CautionKind
import com.yeopjari.app.model.DriveState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Signal
import com.yeopjari.app.model.TrafficAhead
import com.yeopjari.app.model.TrafficKind
import com.yeopjari.app.model.Turn
import kotlin.math.max

/**
 * 시연용 주행 시나리오.
 * 에뮬레이터에서 실제 내비 경로를 재현하기 어려울 때도 같은 코칭 엔진을 그대로 보여 줄 수 있습니다.
 * 0.1초 간격의 차량·내비 상태 목록을 만듭니다.
 */
object Scenarios {

    enum class Id(val title: String, val desc: String) {
        MERGE("A. 첫 고속도로 합류", "왼쪽 합류 · 뒤차 경고 · 3차로 사고 · 터널 전조등"),
        RIGHT_TURN("B. 우회전과 급제동", "연료 부족 · 반대쪽 깜빡이 · 급제동 기록 · 깜빡이 끄기"),
        SAME_ROAD("C. 같은 길 다시", "어린이보호구역 · 지난 급제동 지점 · 연속 회전 · 도착"),
        EXIT("D. 고속도로 출구", "1차로·버스전용차로 · 속도 권유 · 3km/1km/500m 출구 안내 · 뒤차 경고"),
        TALK("E. 강사 모드 · 질문 · 길 놓침", "틈새 요령 · 말로 질문하면 대답 · 회전 안내가 오면 끊고 이어 말하기 · 길을 놓쳤을 때"),
    }

    private const val BASE_LAT = 37.5000
    private const val BASE_LON = 127.0300

    fun build(id: Id): List<DriveState> = when (id) {
        Id.MERGE -> merge()
        Id.RIGHT_TURN -> rightTurn()
        Id.SAME_ROAD -> sameRoad()
        Id.EXIT -> exit()
        Id.TALK -> talk()
    }

    /** [lonOffset]: 시연끼리 같은 길을 쓰지 않도록 경도를 다르게 줌 (B와 C는 '같은 길 다시'라 같은 길) */
    private class Sim(val lonOffset: Double = 0.0) {
        var t = 0L
        var v = 0f
        var gear = Gear.PARK
        var signal = Signal.OFF
        var brake = false
        var pos = 0.0
        var turnAt: Double? = null
        var turnM = Maneuver.NONE
        var nextAt: Double? = null
        var nextM = Maneuver.NONE
        var cautionAt: Double? = null
        var caution: CautionKind = CautionKind.OTHER
        var cautionId = ""
        var fuel = 14f
        var fuelPercent = 60
        var headlight = true
        var speedLimit: Int? = null
        var turnSide: Signal? = null
        var blindLeft: Boolean? = null
        var blindRight: Boolean? = null
        /** 앞쪽 교통 상황: (위치, 종류, 차로) */
        var trafficAt: Double? = null
        var trafficKind = TrafficKind.ACCIDENT
        var trafficLane: Int? = null
        var reroute = 0
        /** 이 순간 운전자가 말한 질문 (시연) */
        var question: String? = null
        val frames = mutableListOf<DriveState>()

        fun remain(): Double = (turnAt ?: pos) - pos

        fun step() {
            pos += v / 3.6 * 0.1
            t += 100
            frames += DriveState(
                timeMs = t,
                speedKmh = v,
                gear = gear,
                signal = signal,
                brake = brake,
                turn = turnAt?.let { at -> val d = at - pos; if (d >= -5 || turnM == Maneuver.ARRIVE) Turn(turnM, max(d, 0.0), turnSide) else null },
                caution = cautionAt?.let { at -> val d = at - pos; if (d >= 0) Caution(cautionId, caution, d.toInt()) else null },
                lat = BASE_LAT + pos / 111320.0,
                lon = BASE_LON + lonOffset,
                fuelKmL = fuel,
                nextTurn = nextAt?.let { at -> Turn(nextM, max(at - pos, 0.0)) },
                headlightOn = headlight,
                speedLimit = speedLimit,
                fuelPercent = fuelPercent,
                routeActive = true,
                traffic = trafficAt?.let { at -> val d = at - pos; if (d >= 0) TrafficAhead("tr-$at", trafficKind, d, trafficLane) else null },
                blindLeft = blindLeft,
                blindRight = blindRight,
                rerouteCount = reroute,
                demoQuestion = question,
            )
        }

        fun hold(sec: Double) = repeat((sec * 10).toInt()) { step() }

        fun rampTo(target: Float, sec: Double) {
            val n = (sec * 10).toInt().coerceAtLeast(1)
            val dv = (target - v) / n
            repeat(n) { v += dv; fuel = if (dv > 0) 9f else 18f; step() }
            fuel = 14f
        }

        /** 다음 동작까지 남은 거리가 [m] 이하가 될 때까지 지금 속도로 주행 */
        fun driveUntil(m: Double) { while (remain() > m) step() }
        fun driveUntilPos(p: Double) { while (pos < p) step() }

        fun setTurn(m: Maneuver, ahead: Double) { turnM = m; turnAt = pos + ahead }
        fun clearTurn() { turnAt = null; turnM = Maneuver.NONE; turnSide = null }

        /** 지금 동작을 마치고 '그다음 동작'을 현재 동작으로 올림 */
        fun advanceTurn() {
            turnAt = nextAt; turnM = nextM
            nextAt = null; nextM = Maneuver.NONE
        }
    }

    private fun Sim.startTrip() { hold(2.0); gear = Gear.DRIVE; hold(1.0) }
    private fun Sim.endTrip() { rampTo(0f, (v / 4f).toDouble().coerceAtLeast(2.0)); hold(1.0); gear = Gear.PARK; signal = Signal.OFF; hold(2.0) }

    /**
     * A. 첫 고속도로 합류
     * 왼쪽 합류 안내 → 방향지시등 늦음(왼쪽으로 켜 주세요) → 켜자마자 왼쪽 뒤에 차 → 지나간 뒤 합류 → 칭찬
     * → 2km 앞 3차로 사고 → 전조등이 꺼진 채 터널 진입
     */
    private fun merge(): List<DriveState> = Sim(lonOffset = 0.02).run {
        headlight = false
        blindLeft = false; blindRight = false   // 후측방 경고 기능이 있는 차량
        startTrip()
        rampTo(60f, 6.0)
        setTurn(Maneuver.MERGE, 560.0); turnSide = Signal.LEFT   // 오른쪽 진입로 → 본선은 왼쪽
        driveUntil(160.0)
        rampTo(70f, 2.0)
        driveUntil(120.0)
        signal = Signal.LEFT     // '왼쪽 방향지시등을 켜 주세요'를 듣고 켬
        rampTo(55f, 3.0)
        hold(1.0)
        blindLeft = true         // 왼쪽 뒤에서 차가 다가옴 → 속도를 줄이며 기다림
        rampTo(30f, 1.5)
        hold(3.5)
        blindLeft = false        // 뒤차가 지나감
        hold(4.0)
        rampTo(70f, 6.0)         // 천천히 속도를 맞춰 합류
        driveUntil(0.0)
        hold(1.0)
        signal = Signal.OFF
        clearTurn()
        rampTo(88f, 2.0)
        hold(3.0)
        trafficAt = pos + 1900.0; trafficKind = TrafficKind.ACCIDENT; trafficLane = 3
        hold(12.0)
        trafficAt = null
        setTurn(Maneuver.TUNNEL, 380.0)
        driveUntil(150.0)
        headlight = true   // 안내를 듣고 전조등을 켬
        driveUntil(0.0)
        hold(3.0)
        clearTurn()
        endTrip()
        frames
    }

    /** B. 연료 부족 안내 → 우회전 직전 급제동 → 반대쪽 깜빡이 → 회전 후 깜빡이를 안 끔 */
    private fun rightTurn(): List<DriveState> = Sim().run {
        fuelPercent = 12
        startTrip()
        rampTo(45f, 6.0)
        hold(8.0)          // 출발 전 점검 안내를 다 들을 시간 (B·C가 같은 길이 되도록 똑같이)
        setTurn(Maneuver.RIGHT, 320.0)
        driveUntil(125.0)
        signal = Signal.LEFT   // 실수로 반대쪽을 켬 → 확인 지점 전에 바로 '반대쪽' 안내
        driveUntil(90.0)
        brake = true
        rampTo(20f, 1.5)   // 초당 약 16km/h 감속 → 급제동
        brake = false
        driveUntil(62.0)
        signal = Signal.RIGHT  // 안내를 듣고 바로잡음
        rampTo(15f, 1.5)
        driveUntil(0.0)
        hold(1.0)
        clearTurn()
        rampTo(40f, 4.0)
        hold(10.0)             // 회전 후에도 깜빡이가 켜져 있음
        signal = Signal.OFF
        hold(3.0)
        endTrip()
        frames
    }

    /** C. 같은 길 다시 — 어린이보호구역, 지난 급제동 지점, 우회전 후 바로 좌회전, 도착 */
    private fun sameRoad(): List<DriveState> = Sim().run {
        cautionAt = 200.0; caution = CautionKind.SCHOOL_ZONE; cautionId = "school-1"
        startTrip()
        rampTo(45f, 6.0)
        hold(8.0)          // 출발 전 점검 안내를 다 들을 시간 (B·C가 같은 길이 되도록 똑같이)
        setTurn(Maneuver.RIGHT, 320.0)
        nextM = Maneuver.LEFT; nextAt = turnAt!! + 110.0
        rampTo(30f, 3.0)    // 어린이보호구역
        driveUntil(140.0)
        signal = Signal.RIGHT
        rampTo(15f, 5.0)    // 미리 천천히 감속
        driveUntil(0.0)
        hold(1.0)
        signal = Signal.OFF
        cautionAt = null
        advanceTurn()       // 바로 이어서 좌회전
        rampTo(25f, 2.0)
        driveUntil(75.0)
        signal = Signal.LEFT
        rampTo(15f, 2.0)
        driveUntil(0.0)
        hold(1.0)
        signal = Signal.OFF
        setTurn(Maneuver.ARRIVE, 260.0)
        rampTo(35f, 3.0)
        driveUntil(0.0)
        endTrip()            // 화면에는 '도착'으로 남음
        frames
    }

    /**
     * D. 고속도로 출구 — 1차로에서 출발해 3km 전부터 한 차로씩 오른쪽으로 옮김
     * 3km 안내 → 차로 변경 2번(한 번은 오른쪽 뒤차 경고) → 2km 칭찬 → 1km 확인 → 출구 진출
     */
    private fun exit(): List<DriveState> = Sim(lonOffset = 0.04).run {
        blindLeft = false; blindRight = false
        speedLimit = 100
        startTrip()
        rampTo(50f, 8.0)
        hold(25.0)               // 고속도로인데 시속 50km로 오래 달림 → 흐름보다 느리다고 안내
        rampTo(100f, 14.0)       // 시속 60km를 넘으면 '1차로는 추월할 때만' 안내
        cautionAt = pos + 330.0; caution = CautionKind.BUS_LANE; cautionId = "bus-1"
        driveUntilPos(pos + 600.0)
        cautionAt = null
        setTurn(Maneuver.EXIT_R, 3300.0)
        driveUntil(2700.0)
        signal = Signal.RIGHT; hold(3.0); signal = Signal.OFF      // 1 → 2차로
        driveUntil(2300.0)
        signal = Signal.RIGHT; hold(1.0)
        blindRight = true; hold(3.0); blindRight = false; hold(3.0) // 오른쪽 뒤차가 지나간 뒤
        signal = Signal.OFF                                         // 2 → 3차로
        driveUntil(1600.0)
        signal = Signal.RIGHT; hold(3.0); signal = Signal.OFF      // 3 → 4차로(끝 차로)
        driveUntil(900.0)
        rampTo(90f, 3.0)
        driveUntil(400.0)
        signal = Signal.RIGHT                                       // 출구 진입 전 미리 켬
        driveUntil(0.0)
        hold(1.0)
        signal = Signal.OFF
        clearTurn()
        rampTo(60f, 8.0)                                            // 출구 램프에서 천천히 감속
        hold(3.0)
        endTrip()
        frames
    }

    /**
     * E. 강사 모드 · 질문 · 길 놓침
     * 직진 중 강사 한마디 → "합류할 때는 언제 들어가요?" 대답 중 우회전 미리 알림이 오면 "잠깐만요!" 하고 끊음(대답은 보관)
     * → 깜빡이를 못 켜고 우회전을 지나침 → "괜찮아요, 길 다시 찾았어요" → 새 경로 우회전 200m 전에 또 질문
     * → "지금은 우회전 먼저 볼게요"로 미룸 → 우회전은 제때 → 여유 있는 직진에서 "아까 물어보신 거…" 두 대답을 한 번에 → 도착
     */
    private fun talk(): List<DriveState> = Sim(lonOffset = 0.06).run {
        startTrip()
        rampTo(45f, 6.0)
        hold(30.0)              // 출발 안내 뒤 조용한 직진 — 강사 한마디
        hold(14.0)              // 한마디 더
        question = "합류할 때는 언제 들어가요?"
        hold(1.0)
        question = null
        hold(2.0)
        setTurn(Maneuver.RIGHT, 345.0)   // 대답하는 중에 300m 미리 알림이 옴 → 끊고 알림, 대답은 여유 있을 때로
        rampTo(35f, 3.0)
        driveUntil(5.0)                   // 깜빡이를 못 켜고 그대로 지나침
        hold(1.5)
        clearTurn()
        hold(3.0)
        reroute = 1                       // 내비가 새 경로를 찾음
        hold(1.0)
        setTurn(Maneuver.RIGHT, 380.0)
        driveUntil(200.0)
        question = "비보호 좌회전은 언제 해요?"   // 회전이 가까워 대답을 미룸
        hold(1.0)
        question = null
        driveUntil(140.0)
        signal = Signal.RIGHT             // 이번엔 제때
        rampTo(15f, 5.0)
        driveUntil(0.0)
        hold(1.0)
        signal = Signal.OFF
        clearTurn()
        setTurn(Maneuver.ARRIVE, 950.0)   // 여유 있는 직진 — 미뤄 둔 대답을 한 번에
        rampTo(40f, 4.0)
        driveUntil(0.0)
        endTrip()
        frames
    }
}
