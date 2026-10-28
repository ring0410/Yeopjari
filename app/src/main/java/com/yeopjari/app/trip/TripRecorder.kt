package com.yeopjari.app.trip

import com.yeopjari.app.model.DriveState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Mission
import com.yeopjari.app.model.Spot

/** 주행 한 번의 요약 */
data class TripReport(
    val distanceKm: Double,
    val durationMin: Double,
    val safetyScore: Int,
    val ecoScore: Int,
    val maneuvers: Int,
    val signalOnTime: Int,
    /** 맞는 쪽 방향지시등을 (늦게라도) 켠 횟수 */
    val signalUsed: Int = signalOnTime,
    val harshAccel: Int,
    val harshBrakes: List<Spot>,
    val avgFuelKmL: Float?,
    val improvement: String,
    /** 개선 포인트를 왜 해야 하는지 (초보자는 이유를 알아야 따라 해요) */
    val why: String = "",
    /** 오늘 잘한 점 하나 (리포트 첫 화면에 크게) */
    val goodPoint: String = "",
    /** 이번 주행에서 성공한 미션 (옆자리 코칭이 꺼져 있었으면 비어 있음) */
    val missions: List<Mission> = emptyList(),
    /** 상한을 적용해 실제로 받은 옆자리 포인트 (컨트롤러가 채움) */
    val points: Int = 0,
) {
    /** 방향지시등 사용률: 늦게라도 켰으면 '사용'으로 셉니다. 제때 켰는지는 따로 보여 줍니다. */
    val signalRate: Int get() = if (maneuvers == 0) 100 else (signalUsed * 100 / maneuvers)
    /** 제때(확인 지점 전에) 켠 비율 — '지난번 대비' 비교에 씁니다 */
    val onTimeRate: Int get() = if (maneuvers == 0) 100 else (signalOnTime * 100 / maneuvers)
}

/**
 * 주행 기록기 — D단에서 출발해 P단으로 끝날 때까지를 한 번의 주행으로 봅니다.
 * 점수는 설명할 수 있는 규칙으로만 계산합니다.
 */
class TripRecorder {
    private var active = false
    private var startMs = 0L
    private var lastMs = 0L
    private var distanceM = 0.0
    private var maneuvers = 0
    private var signalOnTime = 0
    private var missedSignals = 0
    private var lateSignals = 0
    private var signalUsed = 0
    private var harshAccel = 0
    private val harshBrakes = mutableListOf<Spot>()
    private var brakesBeforeRight = 0
    private var fuelSum = 0.0
    private var fuelCount = 0
    private var lastGear = Gear.PARK
    private val missions = mutableListOf<Mission>()
    private var missionsOn = true

    /** 주행을 시작할 때 미션을 셀지 묻는 함수 (옆자리 코칭이 켜져 있을 때만 셈) */
    var missionsEnabled: () -> Boolean = { true }

    val isActive get() = active

    /** 매 순간 호출. 주행이 끝나면 리포트를 돌려줍니다. */
    fun update(s: DriveState): TripReport? {
        var finished: TripReport? = null
        if (!active && s.gear == Gear.DRIVE) start(s.timeMs)
        if (active) {
            if (lastMs > 0) distanceM += s.speedKmh / 3.6 * ((s.timeMs - lastMs) / 1000.0)
            lastMs = s.timeMs
            if (s.gear == Gear.DRIVE && s.speedKmh > 5f) s.fuelKmL?.let { if (it > 0f && it < 60f) { fuelSum += it; fuelCount++ } }
            if (s.gear == Gear.PARK && lastGear != Gear.PARK) {
                finished = buildReport(s.timeMs)
                active = false
            }
        }
        lastGear = s.gear
        return finished
    }

    private fun start(now: Long) {
        active = true
        startMs = now; lastMs = 0L; distanceM = 0.0
        maneuvers = 0; signalOnTime = 0; missedSignals = 0; lateSignals = 0; signalUsed = 0
        harshAccel = 0; harshBrakes.clear(); brakesBeforeRight = 0
        fuelSum = 0.0; fuelCount = 0
        missions.clear(); missionsOn = missionsEnabled()
    }

    fun onManeuver(m: Maneuver, onTime: Boolean, used: Boolean) {
        if (!active) return
        maneuvers++
        when {
            onTime -> { signalOnTime++; signalUsed++ }
            used -> { lateSignals++; signalUsed++ }   // 늦게라도 켬
            else -> missedSignals++                    // 끝까지 안 켬
        }
    }

    fun onHarshAccel() { if (active) harshAccel++ }

    fun onMission(m: Mission) { if (active && missionsOn) missions += m }

    fun onHarshBrake(s: DriveState, near: Maneuver?) {
        if (!active) return
        val label = when (near) {
            Maneuver.RIGHT -> "우회전 직전"
            Maneuver.LEFT -> "좌회전 직전"
            null -> "주행 중"
            else -> "회전·합류 직전"
        }
        if (near == Maneuver.RIGHT) brakesBeforeRight++
        harshBrakes += Spot(s.lat ?: 0.0, s.lon ?: 0.0, label)
    }

    private fun buildReport(now: Long): TripReport {
        val brakes = harshBrakes.size
        val safety = (100 - missedSignals * 10 - lateSignals * 5 - brakes * 8 - harshAccel * 4).coerceIn(0, 100)
        val eco = (100 - harshAccel * 8 - brakes * 6).coerceIn(0, 100)
        val improvement = when {
            missedSignals > 0 -> "회전·합류할 때는 깜빡이를 꼭 켜 주세요."
            lateSignals > 0 -> "회전·합류하기 전에 깜빡이를 조금 더 일찍 켜 보세요."
            brakesBeforeRight > 0 -> "우회전 전에 속도를 조금 더 일찍 줄여 보세요."
            brakes > 0 -> "앞차와 거리를 넉넉히 두고, 미리 천천히 줄여 보세요."
            harshAccel > 0 -> "출발할 때 가속 페달을 천천히 밟아 보세요."
            else -> "오늘처럼만 운전하세요. 아주 좋았어요!"
        }
        val why = when {
            missedSignals > 0 || lateSignals > 0 -> "깜빡이는 뒤차에게 내가 어디로 갈지 알려 주는 말이에요. 일찍 켤수록 뒤차가 미리 속도를 줄일 수 있어요."
            brakesBeforeRight > 0 -> "우회전 바로 앞에는 횡단보도가 있어요. 미리 줄여 두면 보행자를 보고도 여유 있게 멈출 수 있어요."
            brakes > 0 -> "급제동은 뒤차와 부딪힐 위험이 커요. 거리를 두면 천천히 멈출 시간이 생겨요."
            harshAccel > 0 -> "천천히 출발하면 연료도 아끼고, 앞이 갑자기 막혀도 바로 멈출 수 있어요."
            else -> "지금처럼 미리 준비하고 부드럽게 운전하면 사고 위험이 가장 낮아요."
        }
        val goodPoint = when {
            maneuvers == 1 && signalOnTime == 1 -> "회전·합류할 때 깜빡이를 제때 켜셨어요."
            maneuvers > 1 && signalOnTime == maneuvers -> "회전·합류 ${maneuvers}번 모두 깜빡이를 제때 켜셨어요."
            brakes == 0 && distanceM >= 500.0 -> "급제동 없이 부드럽게 운전하셨어요."
            signalUsed > 0 -> "회전·합류할 때 깜빡이를 잊지 않고 켜셨어요."
            harshAccel == 0 -> "출발과 가속이 부드러웠어요."
            else -> "끝까지 안전하게 도착하셨어요."
        }
        return TripReport(
            distanceKm = distanceM / 1000.0,
            durationMin = (now - startMs) / 60000.0,
            safetyScore = safety,
            ecoScore = eco,
            maneuvers = maneuvers,
            signalOnTime = signalOnTime,
            signalUsed = signalUsed,
            harshAccel = harshAccel,
            harshBrakes = harshBrakes.toList(),
            avgFuelKmL = if (fuelCount > 0) (fuelSum / fuelCount).toFloat() else null,
            improvement = improvement,
            why = why,
            goodPoint = goodPoint,
            missions = missions.toList() + if (missionsOn && brakes == 0 && distanceM >= 500.0) listOf(Mission.SMOOTH_TRIP) else emptyList(),
        )
    }
}
