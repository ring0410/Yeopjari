package com.yeopjari.app.model

/** 기어 상태 */
enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

/** 방향지시등 상태 */
enum class Signal { OFF, LEFT, RIGHT }

/** 내비 다음 동작을 코칭용으로 단순화한 종류 */
enum class Maneuver {
    RIGHT, LEFT, UTURN, MERGE, ENTER_HIGHWAY_R, ENTER_HIGHWAY_L, EXIT_R, EXIT_L,
    KEEP_RIGHT, KEEP_LEFT, ROUNDABOUT, ROUNDABOUT_EXIT,
    REST_AREA, TOLL, TUNNEL, ARRIVE, NONE;

    /** 이 동작에서 켜야 하는 방향지시등. null = 확인하지 않음, OFF = 방향 무관(아무 쪽이나 켜면 됨) */
    val expectedSignal: Signal?
        get() = when (this) {
            RIGHT, EXIT_R, KEEP_RIGHT, ENTER_HIGHWAY_R, ROUNDABOUT_EXIT, REST_AREA -> Signal.RIGHT
            LEFT, UTURN, EXIT_L, KEEP_LEFT, ENTER_HIGHWAY_L -> Signal.LEFT
            MERGE -> Signal.OFF
            ROUNDABOUT, TOLL, TUNNEL, ARRIVE, NONE -> null
        }

    /** 고속도로 성격의 동작인지 (확인 거리를 더 길게 잡음) */
    val isHighway: Boolean
        get() = this == MERGE || this == ENTER_HIGHWAY_R || this == ENTER_HIGHWAY_L || this == EXIT_R || this == EXIT_L ||
            this == REST_AREA || this == TOLL
}

/**
 * 내비가 알려 준 다음 동작과 남은 거리.
 * [side]: 합류처럼 방향이 동작 이름에 없을 때 '들어갈 쪽' 힌트 (예: 오른쪽 진입로 다음 합류 → 본선은 왼쪽)
 */
data class Turn(val maneuver: Maneuver, val distanceM: Double, val side: Signal? = null)

/** 앞쪽 실시간 교통 상황 (NaviHelper RouteStateInfo.trafficStatus) */
enum class TrafficKind { ACCIDENT, CONSTRUCTION, ROAD_DAMAGE, DISASTER, EVENT, CONGESTION }

/**
 * @param lane 사고·공사가 난 차로 번호. SDK는 차로를 따로 주지 않아, 설명 문구에 "3차로" 같은 말이 있을 때만 채워집니다.
 * @param avgSpeedKmh 정체 구간 평균 속도
 */
data class TrafficAhead(
    val id: String,
    val kind: TrafficKind,
    val distanceM: Double,
    val lane: Int? = null,
    val avgSpeedKmh: Int? = null,
)

/** 주의 구간 */
enum class CautionKind {
    SCHOOL_ZONE, ACCIDENT_PRONE, SPEED_BUMP, RAILROAD, DOWNHILL, SLIPPERY, FOG, NARROW_ROAD,
    CONSTRUCTION, WRONG_WAY, FALLING_ROCK, WILDLIFE, BUS_LANE, OTHER
}

data class Caution(val id: String, val kind: CautionKind, val distanceM: Int)

/** 한 순간의 차량 + 내비 상태 */
data class DriveState(
    val timeMs: Long = 0L,
    val speedKmh: Float = 0f,
    val gear: Gear = Gear.PARK,
    val signal: Signal = Signal.OFF,
    val brake: Boolean = false,
    val turn: Turn? = null,
    val caution: Caution? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val fuelKmL: Float? = null,
    /** 그다음 동작 (연속 회전 안내용) */
    val nextTurn: Turn? = null,
    /** 전조등(하향등) 켜짐 여부, 모르면 null */
    val headlightOn: Boolean? = null,
    /** 현재 도로 제한속도(km/h), 모르면 null */
    val speedLimit: Int? = null,
    /** 연료 잔량(%), 모르면 null */
    val fuelPercent: Int? = null,
    /** 내비 경로 안내 중인지 (목적지 설정 여부), 모르면 null */
    val routeActive: Boolean? = null,
    /** 경로 재탐색 횟수 (늘어나면 재탐색이 일어난 것) */
    val rerouteCount: Int = 0,
    /** 앞쪽 사고·공사·정체 */
    val traffic: TrafficAhead? = null,
    /** 후측방(사각지대) 경고: 왼쪽·오른쪽 뒤에 차가 있음. 차량에 기능이 없거나 모르면 null */
    val blindLeft: Boolean? = null,
    val blindRight: Boolean? = null,
    /** 운전석 안전벨트를 맸는지, 모르면 null */
    val seatbelt: Boolean? = null,
    /** 앞유리 와이퍼가 움직이는지 (비 오는 날 판단), 모르면 null */
    val wiperOn: Boolean? = null,
    /** 해가 진 뒤(어두운 시간)인지, 모르면 null — 실제 차량에서 날짜·시각으로 계산 */
    val dark: Boolean? = null,
    /** 시연 전용: 이 순간 운전자가 말로 한 질문 (실제 차량에서는 음성 인식으로 들어옴) */
    val demoQuestion: String? = null,
)

/** 옆자리가 얼마나 말할지 */
enum class TalkLevel(val label: String, val desc: String) {
    QUIET("조용히", "꼭 필요한 안내만 해요"),
    NORMAL("보통", "필요한 안내 + 가끔 한마디"),
    INSTRUCTOR("강사 모드", "도로연수 강사처럼 틈날 때마다 요령을 알려 줘요"),
}

/**
 * 앱 설정.
 * [coaching] '옆자리 코칭' — 켜면 모든 안내를 하고, 끄면 안내 없이 주행 기록만 남깁니다. 처음 설치하면 켜짐.
 */
data class Settings(
    val coaching: Boolean = true,
    val demoMode: Boolean = true,
    /** 천천히 말하기 (어르신·초보자를 위해 기본 켜짐) */
    val slowSpeech: Boolean = true,
    /** 말하기 전 짧은 알림음 '띵' (갑자기 말해서 놀라지 않도록, 기본 켜짐) */
    val chime: Boolean = true,
    /** 말로 다시 듣기: 안내가 끝난 뒤 5초만 듣고 "다시·뭐라고" 등에 직전 안내를 다시 말함 */
    val voiceRepeat: Boolean = true,
    /** 차량에서 Pleos 음성 합성 사용 (기본 켜짐, 끄면 안드로이드 기본 음성 — 말 빠르기 조절 가능) */
    val pleosVoice: Boolean = true,
    /** 목소리: 남자(true) / 여자(false) */
    val maleVoice: Boolean = false,
    /** 말 많기: 조용히 / 보통 / 강사 모드 */
    val talkLevel: TalkLevel = TalkLevel.NORMAL,
)

/**
 * 운전자에 맞춘 안내 — 같은 동작을 제때 잘 해낸 횟수(실력)와 주행 횟수.
 * 처음엔 순서까지 자세히, 익숙해지면 짧게 말합니다. (모드를 나누지 않고 실력에 따라 저절로 바뀜)
 */
interface CoachProfile {
    /** 이 동작을 제때 깜빡이를 켜고 해낸 횟수 */
    fun skill(m: Maneuver): Int
    /** 지금까지 주행 횟수 */
    val trips: Int
    /** 일반 원칙 안내(1차로는 추월차로 등)를 지금까지 들은 횟수 — 여러 번 들었으면 생략 */
    fun heard(id: String): Int = 0

    companion object {
        const val BEGINNER_UNTIL = 3   // 이 횟수 전까지는 자세히
        const val SKILLED_FROM = 5     // 이 횟수부터는 짧게
        const val PRINCIPLE_TIMES = 3  // 일반 원칙은 이만큼 들었으면 다음부터 생략
        val NEW: CoachProfile = object : CoachProfile { override fun skill(m: Maneuver) = 0; override val trips = 0 }
    }
}

/**
 * 옆자리 미션 — 행동 하나하나가 미션이고, 성공하면 옆자리 포인트를 받습니다.
 * 위험한 '결과'(출구를 꼭 나가기 등)가 아니라 안전한 '행동'에만 포인트를 줍니다.
 */
enum class Mission(val label: String) {
    SIGNAL_ON_TIME("회전·합류 전 깜빡이 제때 켜기"),
    SIGNAL_OFF("회전 후 깜빡이 스스로 끄기"),
    SMOOTH_TRIP("급제동 없이 부드럽게 주행하기"),
}

/** 코칭 메시지 단계 */
enum class Stage { PREVIEW, CHECK, DONE, HABIT, CAUTION, MEMORY, INFO,
    /** 강사 한마디·질문 대답 — 가장 낮은 순위. 다른 안내가 오면 끊고, 끝나면 (대답은) 이어서 말함 */
    TALK }

data class CoachEvent(
    val stage: Stage,
    val headline: String,
    val body: String,
    val speech: String,
    val timeMs: Long,
    /** 아주 급한 안내(페달 오조작 의심): 말하던 문장을 끊고 바로 말함 */
    val urgent: Boolean = false,
    /** TALK 중 끊겼을 때 다른 안내가 끝난 뒤 이어서 말할지 (질문 대답은 이어서, 상황 한마디는 버림) */
    val resumable: Boolean = false,
)

/** 지도상의 한 점 (급제동 지점 기억용) */
data class Spot(val lat: Double, val lon: Double, val label: String)
