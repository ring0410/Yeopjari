package com.yeopjari.app.trip

import android.content.Context
import com.yeopjari.app.model.Settings
import com.yeopjari.app.model.Spot
import org.json.JSONArray
import org.json.JSONObject

/** 설정과 급제동 지점, 지난 리포트를 차량 안(기기)에만 저장합니다. 외부로 보내지 않습니다. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("yeopjari", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        coaching = p.getBoolean("coaching", true),   // 처음 설치하면 켜짐
        demoMode = p.getBoolean("demoMode", true),
        slowSpeech = p.getBoolean("slowSpeech", true),
        chime = p.getBoolean("chime", true),
        voiceRepeat = p.getBoolean("voiceRepeat", true),
        pleosVoice = p.getBoolean("pleosVoice", true),
        maleVoice = p.getBoolean("maleVoice", false),
        talkLevel = runCatching { com.yeopjari.app.model.TalkLevel.valueOf(p.getString("talkLevel", "NORMAL")!!) }.getOrDefault(com.yeopjari.app.model.TalkLevel.NORMAL),
    )

    fun saveSettings(s: Settings) {
        p.edit()
            .putBoolean("coaching", s.coaching)
            .putBoolean("demoMode", s.demoMode)
            .putBoolean("slowSpeech", s.slowSpeech)
            .putBoolean("chime", s.chime)
            .putBoolean("voiceRepeat", s.voiceRepeat)
            .putBoolean("pleosVoice", s.pleosVoice)
            .putBoolean("maleVoice", s.maleVoice)
            .putString("talkLevel", s.talkLevel.name)
            .apply()
    }

    // ---------------- 옆자리 포인트 ----------------
    // 미션 1개 = 1P, 하루 최대 100P. 포인트는 운전 중에 아무것도 누르지 않아도 자동으로 쌓입니다.
    // 시연 모드에서는 상한을 적용하지 않습니다(발표 리허설을 여러 번 해도 포인트가 보이도록).
    // (공모전 시연용 — 마이현대·블루멤버스 포인트 연동은 현대차그룹 협업 제안)

    /** 누적 포인트. 예전(0.1P 단위) 기록이 있으면 1P 단위로 바꿔 읽습니다. */
    fun totalPoints(): Int = if (p.contains("points")) p.getInt("points", 0) else p.getInt("pointTenths", 0) / 10

    /** 이번 주행에서 받은 포인트를 하루 상한에 맞춰 더하고, 실제로 더해진 값을 돌려줍니다. */
    fun addPoints(raw: Int, capped: Boolean = true, now: java.util.Date = java.util.Date()): Int {
        if (raw <= 0) return 0
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(now)
        val dayUsed = if (p.getString("ptDay2", "") == day) p.getInt("ptDayAmt2", 0) else 0
        val add = if (!capped) raw else minOf(raw, DAILY_CAP - dayUsed).coerceAtLeast(0)
        p.edit()
            .putString("ptDay2", day).putInt("ptDayAmt2", dayUsed + add)
            .putInt("points", totalPoints() + add)
            .apply()
        return add
    }

    companion object {
        const val SPOT_MERGE_M = 150.0
        const val POINTS_PER_MISSION = 1   // 미션 1개 = 1P
        const val DAILY_CAP = 100          // 하루 최대 100P

        /** 포인트를 "1,234" 같은 글자로 */
        fun points(p: Int): String = "%,d".format(p)
    }

    fun loadSpots(): List<Spot> = runCatching {
        val arr = JSONArray(p.getString("spots", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Spot(o.getDouble("lat"), o.getDouble("lon"), o.optString("label"))
        }
    }.getOrDefault(emptyList())

    /** 새 급제동 지점을 추가 (가까운 기존 지점이 있으면 합침, 최대 50곳) */
    fun addSpots(newSpots: List<Spot>) {
        val all = loadSpots().toMutableList()
        for (s in newSpots) {
            if (s.lat == 0.0 && s.lon == 0.0) continue
            // 150m 안의 가까운 지점은 하나로 봅니다 (같은 곳을 두 번 알리지 않도록)
            if (all.none { com.yeopjari.app.coach.distanceM(it.lat, it.lon, s.lat, s.lon) < SPOT_MERGE_M }) all += s
        }
        val arr = JSONArray()
        all.takeLast(50).forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("label", it.label)) }
        p.edit().putString("spots", arr.toString()).apply()
    }

    fun clearSpots() = p.edit().remove("spots").apply()

    fun saveLastImprovement(text: String, fuel: Float?) {
        p.edit().putString("lastTip", text).putFloat("lastFuel", fuel ?: -1f).apply()
    }

    fun lastImprovement(): String? = p.getString("lastTip", null)
    fun saveLastSignalRate(rate: Int) = p.edit().putInt("lastSignalRate", rate).apply()
    fun lastSignalRate(): Int? = p.getInt("lastSignalRate", -1).takeIf { it >= 0 }
    fun lastFuel(): Float? = p.getFloat("lastFuel", -1f).takeIf { it > 0f }

    // ---------------- 운전자 실력 (안내를 점점 짧게) ----------------
    // 회전 종류별로 '제때 깜빡이를 켜고 해낸 횟수'를 셉니다. 우회전을 잘해도 출구는 처음일 수 있어서 따로 셉니다.

    private fun skillKey(m: com.yeopjari.app.model.Maneuver) = "skill_" + when (m) {
        com.yeopjari.app.model.Maneuver.EXIT_R, com.yeopjari.app.model.Maneuver.EXIT_L,
        com.yeopjari.app.model.Maneuver.REST_AREA, com.yeopjari.app.model.Maneuver.KEEP_RIGHT,
        com.yeopjari.app.model.Maneuver.KEEP_LEFT -> "EXIT"
        com.yeopjari.app.model.Maneuver.MERGE, com.yeopjari.app.model.Maneuver.ENTER_HIGHWAY_R,
        com.yeopjari.app.model.Maneuver.ENTER_HIGHWAY_L -> "MERGE"
        else -> m.name
    }
    fun skill(m: com.yeopjari.app.model.Maneuver): Int = p.getInt(skillKey(m), 0)
    fun addSkill(m: com.yeopjari.app.model.Maneuver) = p.edit().putInt(skillKey(m), skill(m) + 1).apply()
    fun trips(): Int = p.getInt("trips", 0)
    fun heard(id: String): Int = p.getInt("heard_$id", 0)
    fun addHeard(id: String) = p.edit().putInt("heard_$id", heard(id) + 1).apply()
    /** 실제로 잰 글자당 읽는 시간(ms), 없으면 null */
    fun msPerChar(): Long? = p.getLong("msPerChar", 0L).takeIf { it > 0 }
    fun saveMsPerChar(v: Long) = p.edit().putLong("msPerChar", v).apply()
    fun addTrip() = p.edit().putInt("trips", trips() + 1).apply()
    /** 시연을 처음 상태(초보자)부터 다시 보여 줄 때 */
    fun resetSkills() {
        val e = p.edit()
        p.all.keys.filter { it.startsWith("skill_") || it.startsWith("heard_") }.forEach { e.remove(it) }
        e.putInt("trips", 0).apply()
    }

    // ---------------- 최근 주행 기록 (실력 변화 보기) ----------------

    data class TripSummary(val timeMs: Long, val safety: Int, val onTimeRate: Int?, val brakes: Int)

    fun history(): List<TripSummary> = runCatching {
        val arr = JSONArray(p.getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TripSummary(o.getLong("t"), o.getInt("s"), if (o.has("r")) o.getInt("r") else null, o.getInt("b"))
        }
    }.getOrDefault(emptyList())

    fun addHistory(t: TripSummary) {
        val arr = JSONArray()
        (history() + t).takeLast(10).forEach {
            val o = JSONObject().put("t", it.timeMs).put("s", it.safety).put("b", it.brakes)
            it.onTimeRate?.let { r -> o.put("r", r) }
            arr.put(o)
        }
        p.edit().putString("history", arr.toString()).apply()
    }

    // ---------------- 주차 위치 ----------------

    fun saveParking(lat: Double, lon: Double, timeMs: Long) =
        p.edit().putString("parkLat", lat.toString()).putString("parkLon", lon.toString()).putLong("parkAt", timeMs).apply()
    fun parking(): Triple<Double, Double, Long>? {
        val lat = p.getString("parkLat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("parkLon", null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, p.getLong("parkAt", 0L))
    }

    // ---------------- 처음 실행 소개 ----------------
    fun introDone(): Boolean = p.getBoolean("introDone", false)
    fun setIntroDone() = p.edit().putBoolean("introDone", true).apply()
}
