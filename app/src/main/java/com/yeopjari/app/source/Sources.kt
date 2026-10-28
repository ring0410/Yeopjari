package com.yeopjari.app.source

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.constants.TrafficEventType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import com.yeopjari.app.model.Caution
import com.yeopjari.app.model.CautionKind
import com.yeopjari.app.model.DriveState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Signal
import com.yeopjari.app.model.TrafficAhead
import com.yeopjari.app.model.TrafficKind
import com.yeopjari.app.model.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 신호 출처: 실제 차량(Real) 또는 시연 시나리오(Replay). 같은 코칭 엔진에 연결됩니다. */
interface SignalSource {
    val state: StateFlow<DriveState>
    fun start()
    fun stop()
}

private const val TAG = "Yeopjari"

/**
 * 실제 차량 신호 — Pleos Vehicle SDK(방향지시등·브레이크·기어·연비),
 * NaviHelper SDK(다음 동작·주의 구간·위치), 안드로이드 차량 속도 정보를 모읍니다.
 */
class RealSource(private val context: Context) : SignalSource {
    private val _state = MutableStateFlow(DriveState())
    override val state: StateFlow<DriveState> = _state

    @Volatile private var speed = 0f
    @Volatile private var gear = Gear.UNKNOWN
    @Volatile private var signal = Signal.OFF
    @Volatile private var brake = false
    @Volatile private var fuel: Float? = null
    @Volatile private var turn: Turn? = null
    @Volatile private var caution: Caution? = null
    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    @Volatile private var nextTurn: Turn? = null
    @Volatile private var headlightOn: Boolean? = null
    @Volatile private var speedLimit: Int? = null
    @Volatile private var fuelPercent: Int? = null
    @Volatile private var routeActive: Boolean? = null
    @Volatile private var rerouteCount = 0
    @Volatile private var heading: Double? = null
    @Volatile private var traffic: TrafficAhead? = null
    @Volatile private var trafficStatus: ai.pleos.playground.navi.data.TrafficStatus? = null
    @Volatile private var blindLeft: Boolean? = null
    @Volatile private var blindRight: Boolean? = null
    @Volatile private var seatbelt: Boolean? = null
    @Volatile private var wiperOn: Boolean? = null
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    /** 합류 방향 힌트: 오른쪽 진입로를 지나면 본선은 왼쪽 (반대도 같음) */
    @Volatile private var mergeSide: Signal? = null

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            signal = when (value) {
                TurnSignalValue.LEFT -> Signal.LEFT
                TurnSignalValue.RIGHT -> Signal.RIGHT
                else -> Signal.OFF
            }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "turn signal", e) }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { brake = pressed == true }
        override fun onFailed(e: Exception) { Log.w(TAG, "brake", e) }
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) { gear = mapGear(g) }
        override fun onFailed(e: Exception) { Log.w(TAG, "gear", e) }
    }
    private val fuelListener = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(v: Float?) { fuel = v }
        override fun onFailed(e: Exception) { Log.w(TAG, "fuel", e) }
    }

    private val fuelPercentListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(v: Int?) { fuelPercent = v }
        override fun onFailed(e: Exception) { Log.w(TAG, "fuel percent", e) }
    }
    private val seatbeltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(v: Boolean?) { seatbelt = v }
        override fun onFailed(e: Exception) { Log.w(TAG, "seatbelt", e) }
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(v: WiperState?) { wiperOn = v?.let { it == WiperState.ON } }
        override fun onFailed(e: Exception) { Log.w(TAG, "wiper", e) }
    }

    private val headlightListener = object : LightStateListener {
        override fun onLightStateUpdated(v: LightState?) { headlightOn = v?.let { it == LightState.ON } }
        override fun onFailed(e: Exception) { Log.w(TAG, "headlight", e) }
    }

    /**
     * 후측방 경고: 계기판 표시등 LCA_LEFT/LCA_RIGHT(후측방 충돌방지 보조).
     * 깜빡일 때(BLINK)를 '뒤에 차가 있음'으로 봅니다.
     * ※ 표시등이 '기능 켜짐'만 뜻하는 차종도 있을 수 있어 실차 확인이 필요합니다. 표시등이 없으면 null(말하지 않음).
     */
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(t: Telltales?) {
            if (t == null) return
            fun warn(type: TelltaleType): Boolean? = t.telltales.firstOrNull { it.type == type }?.let { d ->
                d.state == TelltaleState.BLINK
            }
            blindLeft = warn(TelltaleType.LCA_LEFT)
            blindRight = warn(TelltaleType.LCA_RIGHT)
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "telltale", e) }
    }

    /** 앞쪽(진행 방향 ±60°) 3km 안에서 가장 가까운 사고·공사 등을 고릅니다. 없으면 정체 구간. */
    private fun updateTraffic() {
        val la = lat ?: return; val lo = lon ?: return
        val ts = trafficStatus ?: run { traffic = null; return }
        val hd = heading
        val event = ts.trafficEventList.mapNotNull { e ->
            val d = com.yeopjari.app.coach.distanceM(la, lo, e.latitude, e.longitude)
            val ahead = hd == null || angleDiff(bearing(la, lo, e.latitude, e.longitude), hd) < 60.0
            if (d > 3000.0 || !ahead) null else e to d
        }.minByOrNull { it.second }
        traffic = if (event != null) {
            val (e, d) = event
            val kind = when (e.eventType) {
                TrafficEventType.ACCIDENT -> TrafficKind.ACCIDENT
                TrafficEventType.REPAIRING -> TrafficKind.CONSTRUCTION
                TrafficEventType.ROAD_DAMAGE -> TrafficKind.ROAD_DAMAGE
                TrafficEventType.DISASTER -> TrafficKind.DISASTER
                else -> TrafficKind.EVENT
            }
            TrafficAhead("${e.latitude},${e.longitude},${e.eventType}", kind, d, parseLane(e.description))
        } else {
            // 정체: offset을 '현재 위치부터의 거리'로 해석 (실차 확인 필요)
            ts.congestionItemList.filter {
                it.trafficCongestionType == TrafficCongestionType.HEAVY || it.trafficCongestionType == TrafficCongestionType.SLOW
            }.minByOrNull { it.offset }?.let {
                TrafficAhead("jam-${it.roadName}-${it.startCrossName}", TrafficKind.CONGESTION, it.offset, null, it.avrSpeed.toInt())
            }
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(list: List<TBTInfo>) {
            list.forEach {
                when (mapTurn(it.type)) {
                    Maneuver.ENTER_HIGHWAY_R -> mergeSide = Signal.LEFT
                    Maneuver.ENTER_HIGHWAY_L -> mergeSide = Signal.RIGHT
                    else -> {}
                }
            }
            fun toTurn(t: TBTInfo): Turn { val m = mapTurn(t.type); return Turn(m, t.distance, if (m == Maneuver.MERGE) mergeSide else null) }
            turn = list.getOrNull(0)?.let { toTurn(it) }
            nextTurn = list.getOrNull(1)?.let { toTurn(it) }
        }
        override fun onCurrentRoadSpeedLimit(limit: Int) { speedLimit = limit.takeIf { it > 0 } }
        override fun onNavigationStatus(status: NavigationStatus) { routeActive = status == NavigationStatus.ROUTE_DRIVE }
        override fun onReRouteEnded() { rerouteCount++ }
        override fun onCautionPointInfo(list: List<CautionPoint>) {
            val c = list.minByOrNull { it.remainDistance }
            caution = c?.let { Caution(it.id, mapCaution(it.type), it.remainDistance) }
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            lat = info.latitude; lon = info.longitude; heading = info.heading
            updateTraffic()
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            trafficStatus = info.trafficStatus
            updateTraffic()
        }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>?) {
            when (value?.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { speed = it * 3.6f } // m/s → km/h
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { gear = mapAndroidGear(it) }
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {}
    }

    override fun start() {
        // 1) Pleos Vehicle SDK
        runCatching {
            val v = Vehicle(context).also { it.initialize() }
            vehicle = v
            v.getTurnSignal().registerTurnSignalState(turnSignalListener)
            v.getBrake().registerBrakePedalStatus(brakeListener)
            v.getGear().registerGearSelection(gearListener)
            v.getFuel().registerInstantaneousFuelEconomy(fuelListener)
            v.getFuel().registerFuelPercent(fuelPercentListener)
            v.getLight().registerLowBeamLightState(headlightListener)
            runCatching { v.getSafety().registerTelltales(telltaleListener) }
            runCatching { v.getSeat().registerSeatBeltBuckledState(driverSeat, seatbeltListener) }
            runCatching { v.getWiper().registerWiperState(windshield, wiperListener) }
            v.getGear().getCurrentGearSelectionState({ gear = mapGear(it) }, { Log.w(TAG, "gear get", it) })
        }.onFailure { Log.w(TAG, "Vehicle SDK unavailable", it) }

        // 2) Pleos NaviHelper SDK
        runCatching {
            val n = NaviHelper(context.applicationContext)
            navi = n
            n.initialize(
                onConnected = { n.addListener(naviListener); n.getTBTInfo(); n.getCurrentLocationInfo(); n.getRouteStateInfo() },
                onFailed = { Log.w(TAG, "NaviHelper connect failed", it) },
            )
        }.onFailure { Log.w(TAG, "NaviHelper unavailable", it) }

        // 3) 차량 속도 (안드로이드 자동차 기본 정보)
        runCatching {
            val c = Car.createCar(context)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            // 기어: Pleos 기어 API가 '안드로이드 기본 GEAR_SELECTION을 쓰라'고 안내하므로 함께 구독
            runCatching { pm.registerCallback(speedCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
        }.onFailure { Log.w(TAG, "Car speed unavailable", it) }

        // 0.1초마다 상태를 하나로 묶어 내보냄. 내비 정보는 1초마다 다시 요청.
        job = scope.launch {
            var tick = 0
            while (isActive) {
                _state.value = DriveState(
                    timeMs = System.currentTimeMillis(),
                    speedKmh = speed, gear = gear, signal = signal, brake = brake,
                    turn = turn, caution = caution, lat = lat, lon = lon, fuelKmL = fuel,
                    nextTurn = nextTurn, headlightOn = headlightOn, speedLimit = speedLimit,
                    fuelPercent = fuelPercent, routeActive = routeActive, rerouteCount = rerouteCount,
                    traffic = traffic, blindLeft = blindLeft, blindRight = blindRight,
                    seatbelt = seatbelt, wiperOn = wiperOn, dark = isDark(),
                )
                if (tick % 10 == 0) runCatching { navi?.getTBTInfo(); navi?.getCurrentLocationInfo() }
                if (tick % 100 == 0) runCatching { navi?.getRouteStateInfo() } // 교통 상황은 10초마다
                tick++
                delay(100)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        runCatching {
            vehicle?.getTurnSignal()?.unregisterTurnSignalState(turnSignalListener)
            vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeListener)
            vehicle?.getGear()?.unregisterGearSelection(gearListener)
            vehicle?.getFuel()?.unregisterInstantaneousFuelEconomy(fuelListener)
            vehicle?.getFuel()?.unregisterFuelPercent(fuelPercentListener)
            vehicle?.getLight()?.unregisterLowBeamLightState(headlightListener)
            vehicle?.getSafety()?.unregisterTelltales(telltaleListener)
            runCatching { vehicle?.getSeat()?.unregisterSeatBeltBuckledState(driverSeat, seatbeltListener) }
            runCatching { vehicle?.getWiper()?.unregisterWiperState(windshield, wiperListener) }
            vehicle?.release()
        }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { propertyManager?.unregisterCallback(speedCallback); car?.disconnect() }
    }

    companion object {
        /** 서울 기준 월별 해 뜨는·지는 시각(시, 소수) — 어두운 시간인지 대략 판단 (해 진 뒤 20분부터 어둡다고 봄) */
        private val SUNRISE = doubleArrayOf(7.7, 7.4, 6.9, 6.2, 5.5, 5.2, 5.3, 5.8, 6.2, 6.6, 7.1, 7.6)
        private val SUNSET = doubleArrayOf(17.6, 18.1, 18.6, 19.0, 19.4, 19.9, 19.9, 19.5, 18.9, 18.2, 17.6, 17.3)
        fun isDark(c: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
            val h = c.get(java.util.Calendar.HOUR_OF_DAY) + c.get(java.util.Calendar.MINUTE) / 60.0
            val mo = c.get(java.util.Calendar.MONTH)
            return h >= SUNSET[mo] + 0.33 || h < SUNRISE[mo] - 0.33
        }

        /** 설명 문구에서 "3차로", "2차선" 같은 차로 번호를 찾습니다. */
        fun parseLane(desc: String?): Int? =
            desc?.let { Regex("(\\d)\\s*차[로선]").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
            val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
            val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
            return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
        }

        fun angleDiff(a: Double, b: Double): Double { val d = kotlin.math.abs(a - b) % 360.0; return if (d > 180) 360 - d else d }

        /** 안드로이드 VehicleGear 값: 중립 1, 후진 2, 주차 4, 주행 8 이상 */
        fun mapAndroidGear(v: Int): Gear = when (v) {
            1 -> Gear.NEUTRAL
            2 -> Gear.REVERSE
            4 -> Gear.PARK
            0 -> Gear.UNKNOWN
            else -> Gear.DRIVE
        }

        fun mapGear(g: VehicleGear?): Gear = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> Gear.DRIVE // D 및 수동 단수
        }

        fun mapTurn(t: TurnType): Maneuver = when (t) {
            TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> Maneuver.RIGHT
            TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> Maneuver.LEFT
            TurnType.TURN_UTURN -> Maneuver.UTURN
            TurnType.TURN_DEVIDE_MERGE, TurnType.TURN_ENTER_IC_C -> Maneuver.MERGE
            TurnType.TURN_ENTER_IC_R -> Maneuver.ENTER_HIGHWAY_R
            TurnType.TURN_ENTER_IC_L -> Maneuver.ENTER_HIGHWAY_L
            TurnType.TURN_EXIT_IC_R -> Maneuver.EXIT_R
            TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA -> Maneuver.REST_AREA
            TurnType.TURN_TOLL -> Maneuver.TOLL
            TurnType.TURN_TUNNEL -> Maneuver.TUNNEL
            TurnType.TURN_ARRIVE -> Maneuver.ARRIVE
            TurnType.TURN_EXIT_IC_L -> Maneuver.EXIT_L
            TurnType.TURN_KEEP_R -> Maneuver.KEEP_RIGHT
            TurnType.TURN_KEEP_L -> Maneuver.KEEP_LEFT
            TurnType.TURN_EXIT_ROUNDABOUT -> Maneuver.ROUNDABOUT_EXIT
            TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
            TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
            TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12 -> Maneuver.ROUNDABOUT
            else -> Maneuver.NONE // 직진·경유지 등은 코칭하지 않음
        }

        fun mapCaution(t: CautionPointType): CautionKind = when (t) {
            CautionPointType.SCHOOL_ZONE -> CautionKind.SCHOOL_ZONE
            CautionPointType.ACCIDENT_PRONE_AREA -> CautionKind.ACCIDENT_PRONE
            CautionPointType.SPEED_BUMP -> CautionKind.SPEED_BUMP
            CautionPointType.RAILROAD_CROSSING -> CautionKind.RAILROAD
            CautionPointType.DOWNHILL_CAUTION_AREA -> CautionKind.DOWNHILL
            CautionPointType.SLIPPERY_ROAD_ALERT -> CautionKind.SLIPPERY
            CautionPointType.FOG_AREA -> CautionKind.FOG
            CautionPointType.NARROW_ROAD -> CautionKind.NARROW_ROAD
            CautionPointType.HIGHWAY_CONSTRUCTION -> CautionKind.CONSTRUCTION
            CautionPointType.WRONG_WAY_DRIVING_ALERT -> CautionKind.WRONG_WAY
            CautionPointType.FALLING_ROCK_AREA -> CautionKind.FALLING_ROCK
            CautionPointType.WILDLIFE_CROSSING -> CautionKind.WILDLIFE
            // 버스전용차로 운영 안내 → '파란 선 차로는 피해 주세요'로 행동을 덧붙임
            CautionPointType.BUS_LANE_AVAILABLE_NOTICE -> CautionKind.BUS_LANE
            // 단속 카메라·버스전용차로·끼어들기 단속 등은 내비가 이미 안내하므로 제외 (단속 카메라 알림은 제외)
            else -> CautionKind.OTHER
        }
    }
}

/** 시연 모드 — 미리 만든 시나리오를 실제 시간 속도로 재생 */
class ReplaySource : SignalSource {
    private val _state = MutableStateFlow(DriveState())
    override val state: StateFlow<DriveState> = _state
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private val _playing = MutableStateFlow<Scenarios.Id?>(null)
    val playing: StateFlow<Scenarios.Id?> = _playing

    fun play(id: Scenarios.Id) {
        job?.cancel()
        val frames = Scenarios.build(id)
        _playing.value = id
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            for (f in frames) {
                val target = t0 + f.timeMs
                val wait = target - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                _state.value = f.copy(timeMs = target)
            }
            _playing.value = null
        }
    }

    override fun start() {}
    override fun stop() { job?.cancel(); _playing.value = null }
}
