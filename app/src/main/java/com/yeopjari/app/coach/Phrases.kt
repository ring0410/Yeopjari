package com.yeopjari.app.coach

import com.yeopjari.app.model.CautionKind
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Signal

/**
 * 코칭 문구 모음 (상황별 멘트 카탈로그와 번호를 맞춤).
 * 원칙: 한 번에 한 가지 할 일, 꾸짖지 않고 권하는 말투, 단속·위반 같은 표현 쓰지 않기.
 * 어르신·초보자가 바로 알아듣도록 '방향지시등' 대신 '깜빡이'라고 말하고, "~가 아니라면" 같은 조건 문장은 쓰지 않습니다.
 */
object Phrases {

    data class Line(val id: String, val headline: String, val body: String)

    // ---------------- 회전·경로 동작 (T) ----------------

    /** 방향 말하기: 오른쪽/왼쪽 */
    fun sideWord(sig: Signal?): String = when (sig) { Signal.RIGHT -> "오른쪽"; Signal.LEFT -> "왼쪽"; else -> "들어갈 쪽" }

    /**
     * 미리 알림. 깜빡이가 필요한 동작은 '깜빡이 → 뒤 확인 → 차로 이동' 순서를 한 문장으로 알려 줍니다.
     * [side]: 합류할 때 들어갈 방향 (모르면 null)
     */
    fun preview(m: Maneuver, headlightOn: Boolean? = null, side: Signal? = null): Line = when (m) {
        Maneuver.RIGHT -> Line("T-01", "우회전이 다가와요", "오른쪽 깜빡이를 켜고, 뒤를 본 뒤 오른쪽 끝 차로로 가세요.")
        Maneuver.LEFT -> Line("T-03", "좌회전이 다가와요", "왼쪽 깜빡이를 켜고, 뒤를 본 뒤 좌회전 차로로 가세요.")
        Maneuver.UTURN -> Line("T-05", "유턴 구간이에요", "왼쪽 깜빡이를 켜고, 유턴 신호와 맞은편 차를 확인하세요.")
        Maneuver.MERGE -> if (side != null)
            Line("T-06", "합류 구간이에요", "본선은 ${sideWord(side)}이에요. ${sideWord(side)} 깜빡이를 켜고, 뒤차를 본 뒤 속도를 맞춰 들어가세요.")
        else Line("T-06", "합류 구간이에요", "들어갈 쪽 깜빡이를 켜고, 뒤차를 본 뒤 속도를 맞춰 들어가세요.")
        Maneuver.ENTER_HIGHWAY_R -> Line("T-07", "고속도로 진입로예요", "오른쪽 깜빡이를 켜고, 오른쪽 진입로로 미리 옮겨 주세요.")
        Maneuver.ENTER_HIGHWAY_L -> Line("T-07", "고속도로 진입로예요", "왼쪽 깜빡이를 켜고, 왼쪽 진입로로 미리 옮겨 주세요.")
        Maneuver.EXIT_R -> Line("T-08", "출구가 다가와요", "오른쪽 깜빡이를 켜고, 뒤를 본 뒤 오른쪽 끝 차로로 가세요.")
        Maneuver.EXIT_L -> Line("T-08", "출구가 다가와요", "왼쪽 깜빡이를 켜고, 뒤를 본 뒤 왼쪽 끝 차로로 가세요.")
        Maneuver.KEEP_RIGHT -> Line("T-09", "오른쪽 길로 가요", "오른쪽 깜빡이를 켜고 오른쪽 차로를 따라가세요.")
        Maneuver.KEEP_LEFT -> Line("T-09", "왼쪽 길로 가요", "왼쪽 깜빡이를 켜고 왼쪽 차로를 따라가세요.")
        Maneuver.ROUNDABOUT -> Line("T-10", "회전교차로예요", "안에서 도는 차가 먼저예요. 천천히 들어가고, 나갈 땐 오른쪽 깜빡이를 켜요.")
        Maneuver.ROUNDABOUT_EXIT -> Line("T-11", "회전교차로를 나가요", "오른쪽 깜빡이를 켜고, 오른쪽을 확인하며 천천히 나가세요.")
        Maneuver.REST_AREA -> Line("T-12", "휴게소 진입로예요", "오른쪽 깜빡이를 켜고 오른쪽 끝 차로로 옮긴 뒤, 진입로에서 속도를 충분히 줄이세요.")
        Maneuver.TOLL -> Line("T-13", "톨게이트가 다가와요", "하이패스 차로를 확인하고, 미리 속도를 줄이세요.")
        Maneuver.TUNNEL -> if (headlightOn == false)
            Line("T-14", "터널이에요, 전조등을 켜 주세요", "터널 안에서는 전조등을 켜고 앞차와 거리를 넉넉히 두세요.")
        else Line("T-15", "터널이에요", "앞차와 거리를 넉넉히 두고, 차로를 유지하세요.")
        Maneuver.ARRIVE -> Line("T-16", "목적지 근처예요", "주변 보행자를 살피며 천천히 주차할 곳을 찾아보세요.")
        Maneuver.NONE -> Line("", "", "")
    }

    // ---------------- 3단계 사전 안내 (직진 외 모든 동작) ----------------

    /** 받침이 있으면 true (조사 고르기용) */
    private fun hasBatchim(w: String): Boolean {
        val c = w.trim().lastOrNull() ?: return false
        return c in '가'..'힣' && (c - '가') % 28 != 0
    }
    fun ida(w: String) = if (hasBatchim(w)) "${w}이에요" else "${w}예요"
    fun subj(w: String) = if (hasBatchim(w)) "${w}이" else "${w}가"

    /** 다시 듣기에서 '다음 안내'를 말할 때 쓰는 동작 이름 */
    fun maneuverWord(m: Maneuver): String = when (m) {
        Maneuver.MERGE -> "합류"; Maneuver.ROUNDABOUT -> "회전교차로"; Maneuver.TOLL -> "톨게이트"
        Maneuver.TUNNEL -> "터널"; Maneuver.ARRIVE -> "목적지"; Maneuver.NONE -> "직진"
        else -> placeName(m)
    }

    fun placeName(m: Maneuver): String = when (m) {
        Maneuver.RIGHT -> "우회전"; Maneuver.LEFT -> "좌회전"; Maneuver.UTURN -> "유턴"
        Maneuver.ENTER_HIGHWAY_R, Maneuver.ENTER_HIGHWAY_L -> "고속도로 진입로"
        Maneuver.EXIT_R, Maneuver.EXIT_L -> "출구"; Maneuver.REST_AREA -> "휴게소"
        Maneuver.KEEP_RIGHT, Maneuver.KEEP_LEFT -> "갈림길"; Maneuver.ROUNDABOUT_EXIT -> "회전교차로 출구"
        else -> "다음 길"
    }

    /**
     * 일반도로 1단계(약 300m 전): 차로를 미리 옮겨 두기.
     * [skill] 이 동작을 제때 해낸 횟수 — 처음엔 차로 옮기는 순서까지, 익숙해지면 짧게.
     */
    fun cityFar(m: Maneuver, side: Signal, skill: Int = 0): Line {
        val w = sideWord(side)
        val lane = when (m) {
            Maneuver.LEFT -> "좌회전 차로로"; Maneuver.UTURN -> "유턴할 수 있는 1차로로"
            else -> "$w 끝 차로로"
        }
        val head = "${subj(placeName(m))} 다가와요"
        return when {
            skill >= com.yeopjari.app.model.CoachProfile.SKILLED_FROM -> Line("T-21", head, "")
            skill < com.yeopjari.app.model.CoachProfile.BEGINNER_UNTIL ->
                Line("T-21", head, "깜빡이, 거울, 고개 순서로 확인하고 ${lane} 옮겨요.")
            else -> Line("T-21", head, "${lane} 미리 옮겨 두세요.")
        }
    }

    /** 일반도로 2단계(약 100m 전): 깜빡이와 뒤 확인. 이미 켰으면 칭찬하고 뒤 확인만 */
    fun cityMid(m: Maneuver, side: Signal, signalOn: Boolean, skill: Int = 0): Line {
        val w = sideWord(side)
        if (signalOn) return Line("T-22", "깜빡이 좋아요", "$w 뒤를 한 번 더 확인하세요.")
        val body = if (m == Maneuver.UTURN) "왼쪽 깜빡이를 켜고, 유턴 표지의 조건을 확인하세요."
        else "$w 깜빡이를 켜고, $w 뒤를 확인하세요."
        // (놓쳐도 괜찮다는 안심은 실제로 놓쳤을 때 '새 길로 안내할게요. 괜찮아요'로 말합니다 — 미리 말하면 문장이 길어져 확인이 늦어짐)
        return Line("T-22", "곧 ${ida(placeName(m))}", body)
    }

    /**
     * 고속도로 출구·휴게소·갈림길 3단계(3km → 1km → 500m).
     * 지금 몇 차로인지 알 수 없으므로 판단하지 않고, 운전자가 스스로 확인하게 말합니다.
     * 마지막 단계에서는 못 들어가도 괜찮다고 안심시켜 급한 끼어들기를 막습니다.
     */
    fun highwayStage(stageM: Int, m: Maneuver, side: Signal, signalOn: Boolean): Line {
        val w = sideWord(side)
        val place = placeName(m)
        val keep = m == Maneuver.KEEP_RIGHT || m == Maneuver.KEEP_LEFT
        val relax = if (keep) "놓쳐도 내비가 새 길을 찾아 줘요. 무리하지 마세요."
        else "들어가기 어려우면 무리하지 말고 다음 출구로 가도 괜찮아요."
        return when (stageM) {
            3000 -> if (keep) Line("T-18", "3킬로미터 뒤 $w 길로 갈라져요", "$w 차로로 지금부터 여유 있게 옮겨 두세요.")
            else Line("T-18", "3킬로미터 뒤 ${ida(place)}", "${subj(place)} ${w}에 있어요. 지금부터 여유 있게 $w 끝 차로로 옮겨 두세요.")
            1000 -> Line("T-19", "${place}까지 1킬로미터예요", "$w ${if (keep) "" else "끝 "}차로로 천천히 옮겨 주세요.")
            else -> if (signalOn) Line("T-20", "500미터 앞 ${ida(place)}", "깜빡이 좋아요. $relax")
            else Line("T-20", "500미터 앞 ${ida(place)}", "$w 깜빡이를 켜 주세요. $relax")
        }
    }

    /** T-02 우회전 직전 (보행자·일시정지) — 약 40m 전 */
    val rightTurnPedestrian = Line("T-02", "횡단보도를 확인하세요", "사람이 있거나 빨간불이면 일단 멈췄다 가세요.")

    /** T-04 좌회전 직전 (맞은편 차) */
    val leftTurnOncoming = Line("T-04", "맞은편 차를 확인하세요", "맞은편 차가 다 지나간 뒤 천천히 돌아 주세요.")

    /** T-23 고속도로 합류인데 너무 느릴 때 (초보자가 자주 하는 실수) */
    val mergeSpeed = Line("T-23", "속도를 조금 올려 보세요", "본선 차들과 속도를 맞춰야 들어가기 쉬워요.")

    /** T-17 연속 동작 */
    fun chained(next: Maneuver): String = when (next) {
        Maneuver.RIGHT -> "바로 이어서 우회전이 있어요."
        Maneuver.LEFT -> "바로 이어서 좌회전이 있어요."
        Maneuver.UTURN -> "바로 이어서 유턴이 있어요."
        else -> "바로 이어서 다음 안내가 있어요."
    }

    // ---------------- 방향지시등 확인 (S) ----------------

    fun check(expected: Signal): Line = when (expected) {
        Signal.RIGHT -> Line("S-01", "오른쪽 깜빡이를 켜 주세요", "켜면 뒤차가 미리 알고 속도를 줄여요.")
        Signal.LEFT -> Line("S-01", "왼쪽 깜빡이를 켜 주세요", "켜면 뒤차가 미리 알고 속도를 줄여요.")
        Signal.OFF -> Line("S-02", "깜빡이를 켜 주세요", "들어갈 쪽 깜빡이를 켜 주세요.")
    }

    /** S-08 실수 안내 뒤 바로잡았을 때: 짧게 안심시켜 줌 */
    val fixed = Line("S-08", "네, 맞아요", "")

    /** S-06 제때 켰을 때: 다음 할 일(뒤 확인)로 이어 줌 */
    fun signalAck(side: Signal) = Line("S-06", "좋아요, 이제 ${sideWord(side)} 뒤를 보세요", "${sideWord(side)} 사이드미러로 뒤에서 오는 차를 한 번 더 확인하세요.")

    // ---------------- 후측방(사각지대) 경고 (B) ----------------

    /** B-01 차로를 옮기려는 쪽 뒤에 차가 있음 */
    fun blindSpot(side: Signal) = Line("B-01", "${sideWord(side)} 뒤에 차가 와요", "지나간 뒤에 들어가세요.")

    /** B-02 경고가 풀림 (최종 판단은 운전자: '들어가도 돼요'라고 말하지 않음) */
    fun blindClear(side: Signal) = Line("B-02", "${sideWord(side)} 뒤 차가 지나갔어요", "한 번 더 보고 들어가세요.")

    // ---------------- 실시간 교통 (TR) ----------------

    fun traffic(t: com.yeopjari.app.model.TrafficAhead): Line {
        val d = distanceWords(t.distanceM)
        val laneText = t.lane?.let { "${it}차로" }
        return when (t.kind) {
            com.yeopjari.app.model.TrafficKind.ACCIDENT ->
                if (laneText != null) Line("TR-01", "앞에 사고가 났어요", "$d 앞 ${laneText}에서 사고가 났어요. 옆 차로로 미리 옮기고 속도를 줄이세요.")
                else Line("TR-01", "앞에 사고가 났어요", "$d 앞에서 사고가 났어요. 속도를 줄이고 비상등 켠 차를 조심하세요.")
            com.yeopjari.app.model.TrafficKind.CONSTRUCTION ->
                if (laneText != null) Line("TR-02", "앞에 공사 중이에요", "$d 앞 ${laneText}에서 공사 중이에요. 옆 차로로 미리 옮겨 주세요.")
                else Line("TR-02", "앞에 공사 중이에요", "$d 앞에서 공사 중이에요. 안내 표지를 따라 천천히 가세요.")
            com.yeopjari.app.model.TrafficKind.ROAD_DAMAGE ->
                Line("TR-03", "도로가 파손된 구간이에요", "$d 앞 도로가 파손됐어요. 속도를 줄이고 노면을 살피세요.")
            com.yeopjari.app.model.TrafficKind.DISASTER ->
                Line("TR-04", "앞쪽이 통제 중이에요", "$d 앞이 재난으로 통제 중이에요. 안내에 따라 천천히 가세요.")
            com.yeopjari.app.model.TrafficKind.EVENT ->
                Line("TR-05", "앞쪽에 행사 통제가 있어요", "$d 앞에 행사로 통제되는 구간이 있어요. 천천히 가세요.")
            com.yeopjari.app.model.TrafficKind.CONGESTION ->
                Line("TR-06", "앞에서 길이 막혀요", "$d 앞부터 정체예요${t.avgSpeedKmh?.let { ", 평균 시속 ${it}킬로미터" } ?: ""}. 미리 천천히 줄이고 앞차와 거리를 두세요.")
        }
    }

    /** S-03 반대 방향 점등 */
    fun wrongSide(expected: Signal): Line = when (expected) {
        Signal.RIGHT -> Line("S-03", "반대쪽 깜빡이가 켜져 있어요", "오른쪽 깜빡이로 바꿔 주세요.")
        else -> Line("S-03", "반대쪽 깜빡이가 켜져 있어요", "왼쪽 깜빡이로 바꿔 주세요.")
    }

    /** S-04 동작 후에도 계속 켜져 있음 */
    val signalLeftOn = Line("S-04", "깜빡이가 아직 켜져 있어요", "회전이 끝났으면 깜빡이를 꺼 주세요.")

    /** S-07 늦게라도 켜고 통과: 칭찬 대신 다음 연습 포인트 */
    fun doneLate(m: Maneuver): Line = when (m) {
        Maneuver.MERGE, Maneuver.ENTER_HIGHWAY_L, Maneuver.ENTER_HIGHWAY_R -> Line("S-07", "합류 완료", "다음엔 깜빡이를 조금 더 일찍 켜 봐요.")
        Maneuver.EXIT_R, Maneuver.EXIT_L, Maneuver.REST_AREA -> Line("S-07", "진출 완료", "다음엔 깜빡이를 조금 더 일찍 켜 봐요.")
        else -> Line("S-07", "통과했어요", "다음엔 깜빡이를 조금 더 일찍 켜 봐요.")
    }

    fun done(m: Maneuver): Line = when (m) {
        Maneuver.MERGE, Maneuver.ENTER_HIGHWAY_L, Maneuver.ENTER_HIGHWAY_R -> Line("S-05", "합류 완료", "잘하셨어요.")
        Maneuver.EXIT_R, Maneuver.EXIT_L, Maneuver.REST_AREA -> Line("S-05", "진출 완료", "좋아요.")
        else -> Line("S-05", "좋아요", "잘하셨어요.")
    }

    // ---------------- 주의 구간 (C) ----------------

    fun caution(k: CautionKind): Line? = when (k) {
        CautionKind.SCHOOL_ZONE -> Line("C-01", "어린이보호구역이에요", "시속 30km 이하로 천천히, 아이들이 갑자기 나올 수 있어요.")
        CautionKind.ACCIDENT_PRONE -> Line("C-02", "사고가 잦은 구간이에요", "앞차와 거리를 넉넉히 두세요.")
        CautionKind.SPEED_BUMP -> Line("C-03", "과속방지턱이 있어요", "미리 속도를 줄여 부드럽게 넘어가세요.")
        CautionKind.RAILROAD -> Line("C-04", "철길 건널목이에요", "건너기 전에 일시정지하고 좌우를 확인하세요.")
        CautionKind.DOWNHILL -> Line("C-05", "급경사 내리막이에요", "브레이크를 오래 밟기보다 미리 속도를 줄이세요.")
        CautionKind.SLIPPERY -> Line("C-06", "미끄러운 도로예요", "급하게 핸들을 돌리거나 브레이크를 밟지 마세요.")
        CautionKind.FOG -> Line("C-07", "안개가 잦은 구간이에요", "속도를 줄이고 앞차와 거리를 충분히 두세요.")
        CautionKind.NARROW_ROAD -> Line("C-08", "길이 좁아져요", "맞은편 차와 보행자를 살피며 천천히 가세요.")
        CautionKind.CONSTRUCTION -> Line("C-09", "공사 구간이에요", "차로가 바뀔 수 있어요. 안내 표지를 따라 천천히 가세요.")
        CautionKind.WRONG_WAY -> Line("C-10", "역주행 주의 구간이에요", "진행 방향 표지를 꼭 확인하세요.")
        CautionKind.FALLING_ROCK -> Line("C-11", "낙석 주의 구간이에요", "도로 위 낙하물을 살피며 가세요.")
        CautionKind.BUS_LANE -> Line("C-13", "버스전용차로 구간이에요", "파란 선 차로는 버스 전용이에요. 운영 시간에는 피해 주세요.")
        CautionKind.WILDLIFE -> Line("C-12", "야생동물 출몰 구간이에요", "특히 밤에는 속도를 줄이고 앞을 잘 살피세요.")
        CautionKind.OTHER -> null
    }

    // ---------------- 운전 습관 (H) ----------------

    val harshAccel = Line("H-01", "조금 부드럽게", "가속 페달을 천천히 밟아 보세요.")
    val brakeMemory = Line("H-03", "지난번 급제동 지점이에요", "이 앞에서 지난번 급제동이 있었어요. 미리 천천히 가 보세요.")
    fun overSpeed(limit: Int) = Line("H-04", "제한속도보다 빨라요", "이 도로는 시속 ${limit}km예요. 조금만 줄여 볼까요?")
    /** H-06 고속도로에서 흐름보다 많이 느릴 때: 속도를 강요하지 않고 오른쪽 차로를 함께 권함 */
    val tooSlow = Line("H-06", "흐름보다 많이 느려요", "편하신 만큼 천천히 속도를 올려 보세요. 천천히 가실 땐 오른쪽 차로가 편해요.")
    /** H-07 고속도로 진입 시 1회: 1차로는 추월차로 */
    val passingLane = Line("H-07", "1차로는 추월할 때만 써요", "추월한 뒤엔 오른쪽 차로로 돌아와 주세요.")
    fun longDrive(hours: Int) = Line("H-05", "쉬어 갈 시간이에요", "${hours}시간째 운전 중이에요. 다음 휴게소나 안전한 곳에서 잠시 쉬어 가세요.")
    /** H-08 멈춰 있다가 갑자기 빨라짐: 페달을 잘못 밟았을 수 있음 (가장 급한 안내) */
    val pedal = Line("H-08", "브레이크를 밟으세요", "갑자기 빨라졌어요. 발이 브레이크 위에 있는지 확인하세요.")
    /** H-09 급제동 뒤, 놀란 순간이 지나고 한 번만 */
    val keepDistance = Line("H-09", "앞차와 거리를 조금 더 두세요", "거리를 넉넉히 두면 브레이크를 급하게 밟지 않아도 돼요.")

    // ---------------- 출발·경로 상태 (P) ----------------

    fun lowFuel(pct: Int) = Line("P-04", "연료가 ${pct}% 남았어요", "가까운 주유소에 들러 주세요.")
    val noRoute = Line("P-05", "목적지를 설정해 보세요", "목적지를 정하면 회전·합류 전에 미리 알려 드릴게요.")
    val reroute = Line("R-01", "괜찮아요, 길 다시 찾았어요", "급하게 서거나 후진하지 말고, 그대로 가면서 새 경로를 따라가요.")
    val seatbelt = Line("P-06", "안전벨트를 매 주세요", "출발 전에 안전벨트를 꼭 매 주세요.")
    /** P-07 처음 몇 번의 주행: 출발 전 기본 점검 */
    val startCheck = Line("P-07", "출발 전 확인", "거울과 의자, 안전벨트를 확인하고 천천히 출발해요.")
    val night = Line("P-08", "어두워졌어요, 전조등을 켜 주세요", "전조등을 켜야 다른 차와 사람이 나를 잘 볼 수 있어요.")
    /** P-09 와이퍼가 움직이면 (비 오는 날은 법으로도 20% 감속) */
    fun rain(headlightOff: Boolean) = Line("P-09", "비가 와요", "평소보다 속도를 20% 줄이고, 앞차와 거리를 넉넉히 두세요.${if (headlightOff) " 전조등도 켜 주세요." else ""}")

    /** I-01 처음 설치했을 때 한 번 */
    const val INTRO = "안녕하세요, 저는 옆자리예요. 회전하거나 합류하기 전에 미리 알려 드리고, 깜빡이를 켰는지 확인해 드릴게요. 운전이 끝나면 잘한 점 하나와 다음에 해 볼 것 하나를 알려 드려요."

    /** 1km보다 가까우면 거리 대신 '잠시 뒤'라고 말합니다 (거리 감각이 어려운 분을 위해). 화면에는 거리가 그대로 보여요. */
    fun whenWords(m: Double): String = if (m >= 1000.0) "${distanceWords(m)} 앞" else "잠시 뒤"

    /** 거리를 50m 단위로 말하기 좋게 바꿉니다. */
    fun distanceWords(m: Double): String {
        val r = (Math.round(m / 50.0) * 50).toInt().coerceAtLeast(50)
        return if (r >= 1000) String.format("%.1f킬로미터", r / 1000.0).replace(".0킬로", "킬로") else "${r}미터"
    }
}
