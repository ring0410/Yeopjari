package com.yeopjari.app

import com.yeopjari.app.coach.CoachEngine
import com.yeopjari.app.model.Caution
import com.yeopjari.app.model.CautionKind
import com.yeopjari.app.model.CoachEvent
import com.yeopjari.app.model.DriveState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Settings
import com.yeopjari.app.model.Signal
import com.yeopjari.app.model.Spot
import com.yeopjari.app.model.Stage
import com.yeopjari.app.model.Turn
import com.yeopjari.app.model.Mission
import com.yeopjari.app.model.CoachProfile
import com.yeopjari.app.source.Scenarios
import com.yeopjari.app.trip.TripRecorder
import com.yeopjari.app.trip.TripReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEngineTest {

    private class Run(val events: List<CoachEvent>, val report: TripReport?) {
        fun has(text: String) = events.any { it.headline.contains(text) || it.body.contains(text) }
    }

    private fun run(id: Scenarios.Id, spots: List<Spot> = emptyList(), settings: Settings = Settings()): Run =
        runFrames(id.name, Scenarios.build(id), spots, settings, CoachProfile.NEW)

    private fun profileOf(skill: Int, trips: Int, heard: Int = 0) = object : CoachProfile {
        override fun skill(m: Maneuver) = skill
        override val trips = trips
        override fun heard(id: String) = heard
    }

    private fun runFrames(name: String, frames: List<DriveState>, spots: List<Spot> = emptyList(), settings: Settings = Settings(), profile: CoachProfile = profileOf(0, 99)): Run {
        val rec = TripRecorder().apply { missionsEnabled = { settings.coaching } }
        val engine = CoachEngine({ settings }, { spots }, { profile }).apply {
            onManeuverFinished = { m, ok, used -> rec.onManeuver(m, ok, used) }
            onHarshAccel = { rec.onHarshAccel() }
            onHarshBrake = { s, near -> rec.onHarshBrake(s, near) }
            onMission = { m -> rec.onMission(m) }
        }
        val events = mutableListOf<CoachEvent>()
        var report: TripReport? = null
        for (f in frames) {
            events += engine.update(f)
            rec.update(f)?.let { report = it }
        }
        events.forEach { println("[$name] ${it.timeMs / 1000.0}s ${it.stage}: ${it.speech}") }
        report?.let { println("[$name] report: $it") }
        return Run(events, report)
    }

    // ---------------- 시연 시나리오 ----------------

    @Test fun scenarioA_merge_check_praise_tunnelHeadlight() {
        val r = run(Scenarios.Id.MERGE)
        val stages = r.events.map { it.stage }
        assertEquals("출발 점검 다음 첫 안내는 미리 알림", Stage.PREVIEW, stages.first { it != Stage.INFO })
        assertTrue("합류 방향까지 안내", r.has("본선은 왼쪽"))
        assertTrue("방향을 짚어서 확인", r.has("왼쪽 깜빡이를 켜 주세요"))
        assertTrue("왼쪽 뒤 차 경고", r.has("왼쪽 뒤에 차가 와요"))
        assertTrue("뒤차 지나감", r.has("뒤 차가 지나갔어요"))
        assertTrue("합류 완료 + 다음 연습 포인트(늦게 켰으므로 칭찬 대신)", r.has("합류 완료") && r.has("조금 더 일찍 켜 봐요"))
        assertFalse("늦게 켰는데 '잘하셨어요'라고 하지 않음", r.has("잘하셨어요"))
        assertTrue("3차로 사고 안내", r.has("3차로에서 사고"))
        assertTrue("터널 전조등 안내", r.has("전조등을 켜 주세요"))
        val rep = r.report!!
        assertEquals(1, rep.maneuvers)
        assertEquals(0, rep.signalOnTime)
        assertEquals("늦게라도 켰으면 사용률은 100%", 100, rep.signalRate)
        assertTrue("늦게 켠 것은 감점이 작음", rep.safetyScore in 90..99)
        assertEquals("뒤차를 기다리며 줄인 속도는 급제동이 아님", 0, rep.harshBrakes.size)
        assertEquals("급가속 없음", 0, rep.harshAccel)
    }

    @Test fun scenarioB_lowFuel_wrongSide_harshBrake_signalLeftOn() {
        val r = run(Scenarios.Id.RIGHT_TURN)
        assertTrue("연료 부족", r.has("연료가 12%"))
        assertTrue("반대쪽 깜빡이", r.has("반대쪽 깜빡이"))
        assertTrue("바로잡으면 '네, 맞아요'로 안심", r.has("네, 맞아요"))
        assertTrue("회전 후 깜빡이 끄기", r.has("아직 켜져 있어요"))
        assertFalse("반대쪽을 켰다 바꾼 뒤 '잘하셨어요' 없음", r.has("잘하셨어요"))
        val rep = r.report!!
        assertEquals(1, rep.harshBrakes.size)
        assertEquals("우회전 직전", rep.harshBrakes[0].label)
        assertEquals("반대쪽을 켰다가 바로잡음 → 사용은 했지만 제때는 아님", 100, rep.signalRate)
        assertEquals(0, rep.signalOnTime)
    }

    @Test fun wrongSide_isSaidRightAway_notAtCheckPoint() {
        val frames = Scenarios.build(Scenarios.Id.RIGHT_TURN)
        val r = runFrames("wrongEarly", frames)
        val wrong = r.events.filter { it.headline.contains("반대쪽 깜빡이") }
        assertEquals("반대쪽 안내는 한 번만", 1, wrong.size)
        val at = frames.first { it.timeMs >= wrong[0].timeMs }
        val d = at.turn!!.distanceM
        assertTrue("확인 지점(50m)보다 앞에서 바로 알려 줌: ${d}m", d > CoachEngine.CHECK_CITY_M + 20)

        // 잠깐 건드렸다 끈 것(1.5초 미만)은 말하지 않음
        val blip = drive(40) { i -> DriveState(timeMs = i * 250L, speedKmh = 30f, gear = Gear.DRIVE,
            turn = Turn(Maneuver.RIGHT, 140.0 - i * 2.0), signal = if (i in 4..7) Signal.LEFT else Signal.OFF) }
        assertFalse(runFrames("blip", blip).has("반대쪽"))
    }

    @Test fun signalNeverUsed_countsAsMissed() {
        val frames = (0 until 120).map { i -> DriveState(timeMs = i * 250L, speedKmh = 30f, gear = if (i < 116) Gear.DRIVE else Gear.PARK,
            turn = if (i < 110) Turn(Maneuver.RIGHT, 200.0 - i * 2.0) else null) }
        val rep = runFrames("noSignal", frames).report!!
        assertEquals(1, rep.maneuvers)
        assertEquals(0, rep.signalRate)
        assertEquals("회전·합류할 때는 깜빡이를 꼭 켜 주세요.", rep.improvement)
    }

    @Test fun scenarioC_memory_school_chain_arrive() {
        val spot = run(Scenarios.Id.RIGHT_TURN).report!!.harshBrakes.first()
        val r = run(Scenarios.Id.SAME_ROAD, spots = listOf(spot))
        assertTrue("지난 급제동 지점", r.events.any { it.stage == Stage.MEMORY })
        assertTrue("어린이보호구역", r.has("어린이보호구역"))
        assertTrue("연속 회전", r.has("바로 이어서 좌회전"))
        assertTrue("도착", r.has("목적지 근처"))
        assertTrue("일찍 켰으니 확인 안내 없음", r.events.none { it.stage == Stage.CHECK })
        assertTrue("제때 켜면 칭찬하고 뒤 확인으로 이어 줌", r.has("깜빡이 좋아요"))
        assertTrue("1단계: 처음 운전하는 분께는 차로 옮기는 순서까지", r.has("깜빡이, 거울, 고개 순서로"))
        assertTrue("가까운 거리는 '잠시 뒤'로 말함", r.events.any { it.speech.startsWith("잠시 뒤") })
        assertTrue("우회전 직전: 빨간불이면 일시정지", r.has("빨간불이면 일단 멈췄다 가세요"))
        val rep = r.report!!
        assertEquals(0, rep.harshBrakes.size)
        assertEquals(2, rep.maneuvers)
        assertEquals(100, rep.signalRate)
    }

    @Test fun scenarios_speechNeverCutAndNotTooLate() {
        // 음성은 한 문장씩 끝까지 읽고(끊지 않음), 겹치면 줄을 서서 기다림.
        // 줄 서서 기다리는 시간이 3초를 넘으면 안내가 늦어지는 것이므로 실패로 봄.
        val spot = run(Scenarios.Id.RIGHT_TURN).report!!.harshBrakes.first()
        for (r in listOf(run(Scenarios.Id.MERGE), run(Scenarios.Id.RIGHT_TURN), run(Scenarios.Id.SAME_ROAD, spots = listOf(spot)), run(Scenarios.Id.EXIT))) {
            var freeAt = Long.MIN_VALUE
            r.events.forEach { e ->
                val start = maxOf(e.timeMs, freeAt)
                // 동작 전 안내는 3초, 동작이 끝난 뒤 한마디(완료)는 8초까지 기다려도 됨
                val limit = if (e.stage == Stage.DONE) 8000 else 3000
                assertTrue("늦음 ${(start - e.timeMs) / 1000.0}초: '${e.speech}'", start - e.timeMs <= limit)
                freeAt = start + CoachEngine.estimateSpeechMs(e.speech)
            }
        }
    }

    @Test fun scenarioD_exit_stagedLaneChange() {
        val r = run(Scenarios.Id.EXIT)
        assertTrue("3km 안내", r.has("3킬로미터 뒤 출구예요"))
        assertTrue("500m 마지막 안내 + 안심", r.has("500미터 앞 출구예요") && r.has("다음 출구로 가도 괜찮아요"))
        assertTrue("1차로는 추월차로", r.has("1차로는 추월할 때만"))
        assertTrue("버스전용차로", r.has("버스전용차로 구간이에요"))
        assertTrue("느린 주행 권유", r.has("흐름보다 많이 느려요"))
        assertTrue("1km: 한 가지 할 일만", r.has("오른쪽 끝 차로로 천천히 옮겨 주세요"))
        assertFalse("'~가 아니라면' 같은 조건 문장 없음", r.events.any { it.speech.contains("아니라면") })
        assertFalse("옮긴 횟수는 말하지 않음", r.has("번 옮기셨어요"))
        assertTrue("1km 확인", r.has("출구까지 1킬로미터예요"))
        assertTrue("차로 변경 중 뒤차 경고", r.has("오른쪽 뒤에 차가 와요"))
        assertTrue("미리 켰으니 확인 안내 없음", r.events.none { it.stage == Stage.CHECK && it.headline.contains("켜 주세요") })
        assertEquals(100, r.report!!.signalRate)
    }

    @Test fun memory_notNearTurn_onceForCloseSpots_notAfterPassing() {
        // 회전 120m 앞에 급제동 지점이 둘(서로 40m) 있음 → 회전에 가까워 말하지 않아야 함
        val base = Scenarios.build(Scenarios.Id.RIGHT_TURN)
        val near = base.first { it.turn != null && it.turn!!.distanceM in 100.0..130.0 }
        val spots = listOf(Spot(near.lat!!, near.lon!!, "a"), Spot(near.lat!! + 0.00036, near.lon!!, "b"))
        val r = runFrames("memory-near-turn", base, spots)
        val mem = r.events.filter { it.stage == Stage.MEMORY }
        assertEquals("가까운 두 지점은 한 번만 안내", 1, mem.size)
        val at = base.first { it.timeMs == mem[0].timeMs }
        assertTrue("회전 150m 안이 아니라 그 전에 미리 안내", (at.turn?.distanceM ?: 999.0) > 150.0)
        // 회전이 없는 직선 도로에서는 가까운 두 지점을 한 번만 말함
        val straight = drive(400) { i -> DriveState(speedKmh = 40f, lat = 37.5 + i * 0.1 * 40 / 3.6 / 111320.0, lon = 127.0) }
        val sp = listOf(Spot(37.5 + 250 / 111320.0, 127.0, "x"), Spot(37.5 + 290 / 111320.0, 127.0, "y"))
        assertEquals("가까운 지점은 한 번만", 1, runFrames("memory-cluster", straight, sp).events.count { it.stage == Stage.MEMORY })
    }

    // ---------------- 개별 규칙 ----------------

    private fun drive(n: Int, start: Long = 0, f: (Int) -> DriveState): List<DriveState> =
        (0 until n).map { i -> f(i).copy(timeMs = start + i * 100L, gear = Gear.DRIVE) }

    @Test fun reroute_isAnnouncedCalmly() {
        val frames = drive(30) { i -> DriveState(speedKmh = 40f, rerouteCount = if (i < 10) 0 else 1) }
        val r = runFrames("reroute", frames)
        assertTrue(r.has("길 다시 찾았어요") && r.has("후진하지 말고"))
    }

    @Test fun overSpeed_onlyAfterHoldingAndNotForNormalUsers() {
        val frames = drive(80) { DriveState(speedKmh = 75f, speedLimit = 60) }
        assertTrue(runFrames("overspeed", frames).has("제한속도보다 빨라요"))
        val short = drive(30) { DriveState(speedKmh = 75f, speedLimit = 60) }
        assertFalse("3초만 넘으면 말하지 않음", runFrames("overspeed-short", short).has("제한속도"))
    }

    @Test fun longDrive_everyHour() {
        val frames = listOf(0L, 30 * 60 * 1000L, 60 * 60 * 1000L + 1000).map { DriveState(timeMs = it, gear = Gear.DRIVE, speedKmh = 80f) }
        assertTrue("1시간마다 휴식 권유", runFrames("long", frames).has("1시간째"))
    }

    // ---------------- 어르신·초보자를 위한 안내 ----------------

    @Test fun pedal_suddenSurgeFromStop_afterStart_isUrgent() {
        val park = (0 until 10).map { DriveState(timeMs = it * 100L, gear = Gear.PARK) }
        val stop = (10 until 30).map { DriveState(timeMs = it * 100L, gear = Gear.DRIVE, speedKmh = 0f) }
        val surge = (30 until 45).map { DriveState(timeMs = it * 100L, gear = Gear.DRIVE, speedKmh = (it - 30) * 2.5f) }  // 1.4초에 시속 35km
        val r = runFrames("pedal", park + stop + surge)
        val ev = r.events.firstOrNull { it.headline.contains("브레이크를 밟으세요") }
        assertNotNull("멈춰 있다가 갑자기 빨라지면 바로 경고", ev)
        assertTrue("말하던 것을 끊고 바로", ev!!.urgent)
        // 보통의 부드러운 출발은 경고하지 않음
        val gentle = (10 until 120).map { DriveState(timeMs = it * 100L, gear = Gear.DRIVE, speedKmh = (it - 10) * 0.6f) }
        assertFalse(runFrames("gentle", park + gentle).has("브레이크를 밟으세요"))
    }

    @Test fun night_andRain_once() {
        val night = drive(60) { DriveState(speedKmh = 40f, dark = true, headlightOn = false) }
        assertEquals("밤인데 전조등이 꺼져 있으면 한 번", 1, runFrames("night", night).events.count { it.headline.contains("전조등을 켜 주세요") })
        val rain = drive(80) { i -> DriveState(speedKmh = 50f, wiperOn = i >= 10, headlightOn = true) }
        val r = runFrames("rain", rain)
        assertTrue("와이퍼가 움직이면 비 오는 날 안내", r.has("비가 와요") && r.has("20%"))
        assertEquals(1, r.events.count { it.headline == "비가 와요" })
    }

    @Test fun merge_tooSlow_onHighway() {
        val frames = drive(80) { i -> DriveState(speedKmh = 40f, speedLimit = 100, turn = Turn(Maneuver.MERGE, 300.0 - i * 1.5, Signal.LEFT)) }
        assertTrue(runFrames("mergeSlow", frames).has("속도를 조금 올려 보세요"))
    }

    @Test fun guidance_getsShorter_asDriverGetsBetter() {
        val frames = Scenarios.build(Scenarios.Id.RIGHT_TURN)
        val beginner = runFrames("beginner", frames, profile = profileOf(0, 0))
        val skilled = runFrames("skilled", frames, profile = profileOf(CoachProfile.SKILLED_FROM, 20))
        assertTrue("처음엔 순서까지", beginner.has("거울, 고개 순서로"))
        assertFalse("익숙해지면 짧게", skilled.has("거울, 고개 순서로"))
        val fb = beginner.events.first { it.speech.contains("우회전이 다가와요") }.speech
        val fs = skilled.events.first { it.speech.contains("우회전이 다가와요") }.speech
        assertTrue("익숙한 분께는 더 짧은 문장", fs.length < fb.length)
        assertTrue("처음 몇 번은 출발 전 점검", beginner.has("거울과 의자"))
        assertFalse("주행이 쌓이면 출발 점검은 생략", skilled.has("거울과 의자"))
    }

    @Test fun seatbelt_atStartOnly() {
        val frames = drive(50) { DriveState(speedKmh = 20f, seatbelt = false) }
        assertEquals(1, runFrames("belt", frames).events.count { it.headline.contains("안전벨트") })
    }

    @Test fun repeatWords_understandsManyWaysOfAsking() {
        listOf("다시 말해줘", "뭐라고?", "뭐라구요", "잘 못 들었어", "못 들었어요", "다시 한번만", "한 번 더", "안 들렸어").forEach {
            assertTrue("'$it' → 다시 말하기", com.yeopjari.app.coach.RepeatWords.isAsk(it))
        }
        listOf("오늘 날씨 좋다", "우회전", "배고파", "").forEach {
            assertFalse("'$it' 는 무시", com.yeopjari.app.coach.RepeatWords.isAsk(it))
        }
    }

    @Test fun principles_skippedAfterHeardSeveralTimes() {
        val frames = Scenarios.build(Scenarios.Id.EXIT)
        val first = runFrames("principle-new", frames, profile = profileOf(0, 0, heard = 0))
        val later = runFrames("principle-heard", frames, profile = profileOf(0, 99, heard = CoachProfile.PRINCIPLE_TIMES))
        assertTrue(first.has("1차로는 추월할 때만") && first.has("버스전용차로"))
        assertFalse("여러 번 들었으면 1차로 원칙 생략", later.has("1차로는 추월할 때만"))
        assertFalse("버스전용차로 규칙도 생략", later.has("버스전용차로"))
        assertTrue("출구 안내 같은 핵심은 그대로", later.has("3킬로미터 뒤 출구예요"))
    }

    @Test fun goodPoint_naturalForOneManeuver() {
        assertEquals("회전·합류할 때 깜빡이를 제때 켜셨어요.", run(Scenarios.Id.EXIT).report!!.goodPoint)
    }

    @Test fun report_explainsWhy_andGoodPoint() {
        val rep = run(Scenarios.Id.RIGHT_TURN).report!!
        assertTrue(rep.why.isNotBlank())
        assertTrue(rep.goodPoint.isNotBlank())
    }

    @Test fun railroad_isAnnounced() {
        val frames = drive(20) { DriveState(speedKmh = 40f, caution = Caution("rail", CautionKind.RAILROAD, 200)) }
        assertTrue(runFrames("rail", frames).has("철길 건널목"))
    }

    @Test fun toll_previewOnly_noSignalCheck() {
        val frames = drive(200) { i -> DriveState(speedKmh = 60f, turn = Turn(Maneuver.TOLL, 300.0 - i * 1.7)) }
        val r = runFrames("toll", frames)
        assertTrue(r.has("톨게이트"))
        assertTrue(r.events.none { it.stage == Stage.CHECK })
    }

    @Test fun coachingOff_saysNothing_recordsTrip_noMissions() {
        val r = run(Scenarios.Id.SAME_ROAD, settings = Settings(coaching = false))
        assertTrue(r.events.isEmpty())
        assertNotNull("말하지 않아도 기록은 남음", r.report)
        assertTrue("코칭을 끄면 미션도 쉬어요", r.report!!.missions.isEmpty())
    }

    @Test fun missions_onlyForSafeBehaviour() {
        val c = run(Scenarios.Id.SAME_ROAD).report!!.missions
        assertEquals("깜빡이 제때 2번", 2, c.count { it == Mission.SIGNAL_ON_TIME })
        assertTrue("회전 후 스스로 끔", Mission.SIGNAL_OFF in c)
        assertTrue("급제동 없이 주행", Mission.SMOOTH_TRIP in c)
        val b = run(Scenarios.Id.RIGHT_TURN).report!!.missions
        assertFalse("늦게 켜면 미션 실패", Mission.SIGNAL_ON_TIME in b)
        assertFalse("경고를 듣고서야 끄면 실패", Mission.SIGNAL_OFF in b)
        assertFalse("급제동이 있으면 실패", Mission.SMOOTH_TRIP in b)
    }

    // ---------------- 강사 모드 · 질문하기 · 길 놓침 ----------------

    private fun ins() = Settings(talkLevel = com.yeopjari.app.model.TalkLevel.INSTRUCTOR)
    private fun quiet() = Settings(talkLevel = com.yeopjari.app.model.TalkLevel.QUIET)

    @Test fun talk_instructorFillsQuietTime_quietStaysSilent() {
        val frames = drive(900) { DriveState(speedKmh = 45f) }   // 90초 직진
        val r = runFrames("talk-ins", frames, settings = ins())
        val talks = r.events.filter { it.stage == Stage.TALK }
        assertTrue("강사 모드 첫마디는 질문 안내", talks.first().speech.contains("궁금한 게 있으면"))
        assertTrue("조용한 시간에 여러 번 이야기", talks.size >= 3)
        assertEquals("같은 말 반복 없음", talks.size, talks.map { it.speech }.toSet().size)
        assertTrue("조용히 모드는 한마디 없음", runFrames("talk-quiet", frames, settings = quiet()).events.none { it.stage == Stage.TALK })
    }

    @Test fun talk_neverNearTurn_orWithSignalOn() {
        var d = 600.0
        val frames = drive(450) { i -> d -= 1.25; DriveState(speedKmh = 45f, turn = Turn(Maneuver.RIGHT, maxOf(d, 0.0)), signal = if (d < 100) Signal.RIGHT else Signal.OFF) }
        val r = runFrames("talk-turn", frames, settings = ins())
        val talkDist = r.events.filter { it.stage == Stage.TALK }.map { ev -> 600.0 - (ev.timeMs / 100) * 1.25 }
        assertTrue("회전 350m 안에서는 한마디 없음: $talkDist", talkDist.all { it > CoachEngine.TALK_GUARD_CITY_M - 2 })
    }

    @Test fun scenarioE_talk_question_reroute() {
        val r = run(Scenarios.Id.TALK, settings = ins())
        assertTrue("강사 한마디", r.events.any { it.stage == Stage.TALK })
        assertTrue("우회전 미리 알림", r.events.any { it.stage == Stage.PREVIEW })
        assertTrue("길 놓쳤을 때 침착하게", r.has("길 다시 찾았어요"))
        assertTrue("강사 모드 미리 알림은 강사 말투", r.events.filter { it.stage == Stage.PREVIEW }.any { ev -> com.yeopjari.app.coach.Talk.leadIns.any { ev.speech.startsWith(it) } })
        assertTrue("시연 질문이 들어 있음", Scenarios.build(Scenarios.Id.TALK).any { it.demoQuestion != null })
    }

    @Test fun faq_findsCommonQuestions_andNotNonsense() {
        val f = com.yeopjari.app.coach.Faq
        assertEquals("merge", f.find("합류할 때는 언제 들어가요?")?.id)
        assertEquals("unprotected", f.find("비보호 좌회전 어떻게 해요")?.id)
        assertEquals("rightturn", f.find("빨간불에 우회전 해도 돼요?")?.id)
        assertEquals("hipass", f.find("하이패스 차로 어디로 가요")?.id)
        assertEquals("missedexit", f.find("출구 놓쳤어요 어떡해")?.id)
        assertEquals("sleepy", f.find("너무 졸려")?.id)
        assertEquals(null, f.find("오늘 저녁 뭐 먹지"))
        // '다시'가 들어간 질문은 다시 듣기가 아니라 질문으로
        assertNotNull(f.find("길 잘못 들어왔는데 다시 어떻게 가요"))
        // 모든 대답은 짧게 (운전 중에 듣기 좋게 세 문장 안쪽)
        f.entries.forEach { e -> assertTrue("${e.id} 대답이 길어요", e.answer.split(Regex("(?<=[.?!])\\s+")).size <= 3) }
    }

    @Test fun quietWindow_onlyWhenNothingToSayAhead() {
        val e = CoachEngine({ ins() })
        val base = DriveState(timeMs = 60_000, gear = Gear.DRIVE, speedKmh = 36f)   // 초속 10m
        assertTrue("앞에 아무것도 없으면 여유 있음", e.isQuietFor(base, 20_000))
        assertFalse("회전 400m: 20초 대답(200m 주행) + 수다 금지 350m보다 가까움", e.isQuietFor(base.copy(turn = Turn(Maneuver.RIGHT, 400.0)), 20_000))
        assertTrue("회전 600m면 20초 대답 가능", e.isQuietFor(base.copy(turn = Turn(Maneuver.RIGHT, 600.0)), 20_000))
        assertFalse("깜빡이 켠 동안은 아님", e.isQuietFor(base.copy(signal = Signal.LEFT), 5_000))
        assertFalse("어린이보호구역 앞은 아님", e.isQuietFor(base.copy(caution = Caution("c", CautionKind.SCHOOL_ZONE, 200)), 5_000))
        assertFalse("주차 중엔 판단 안 함", e.isQuietFor(base.copy(gear = Gear.PARK), 5_000))
        assertEquals("우회전", e.busyReason(base.copy(turn = Turn(Maneuver.RIGHT, 200.0))))
    }

    @Test fun scenarioE_hasQuietStretchForDeferredAnswers() {
        // 마지막 우회전 뒤, 도착 안내 전까지 충분히 긴 직진(대답 30초 + 수다 금지 거리)이 있어야 미뤄 둔 대답을 한 번에 할 수 있음
        val frames = Scenarios.build(Scenarios.Id.TALK)
        val arrive = frames.first { it.turn?.maneuver == Maneuver.ARRIVE }
        assertTrue(arrive.turn!!.distanceM > CoachEngine.TALK_GUARD_CITY_M + 40 / 3.6 * 30)
        assertEquals("질문 두 번", 2, frames.mapNotNull { it.demoQuestion }.toSet().size)
    }
}
