package com.yeopjari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeopjari.app.UiState
import com.yeopjari.app.model.Gear
import com.yeopjari.app.model.Maneuver
import com.yeopjari.app.model.Settings
import com.yeopjari.app.model.Signal
import com.yeopjari.app.model.Stage
import com.yeopjari.app.source.Scenarios
import kotlin.math.roundToInt

// ---- 색 (화면 시안과 같은 값) ----
private val Ground = Color(0xFF0B0E13)
private val PanelBg = Color(0xFF11161D)
private val Card = Color(0xFF161C24)
private val Chip = Color(0xFF1F2731)
private val Text1 = Color(0xFFF2F4F7)
private val Text2 = Color(0xFFA9B4C2)
private val Text3 = Color(0xFF8693A3)
private val Teal = Color(0xFF2BC4A9)
private val Amber = Color(0xFFF5A524)
private val AmberBg = Color(0xFF2A2113)
private val Blue = Color(0xFF3D8BFF)

class Actions(
    val play: (Scenarios.Id) -> Unit,
    val settings: (Settings.() -> Settings) -> Unit,
    val openSettings: (Boolean) -> Unit,
    val closeReport: () -> Unit,
    val clearSpots: () -> Unit,
    val repeat: () -> Unit = {},
    val closeIntro: () -> Unit = {},
    val showIntro: () -> Unit = {},
    val toggleDetail: () -> Unit = {},
    val openParking: () -> Unit = {},
    val share: () -> Unit = {},
    val ask: () -> Unit = {},
)

@Composable
fun App(ui: UiState, a: Actions) {
    val big = false
    Box(Modifier.fillMaxSize().background(Ground)) {
        val g = ui.drive.gear
        when {
            // 차량이 '주행 중 화면 조작 제한' 상태이면 기어와 상관없이 주행 화면만 보여 줌
            g == Gear.DRIVE || g == Gear.REVERSE || ui.restricted -> DrivingScreen(ui, big, a)
            ui.showIntro -> IntroScreen(a)
            ui.showSettings -> SettingsScreen(ui, a)
            ui.showReport && ui.report != null -> ReportScreen(ui, a)
            else -> BriefScreen(ui, a)
        }
    }
}

// ---------------------------------------------------------------- 공통

@Composable
private fun Logo(size: Int = 36) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Teal),
            contentAlignment = Alignment.Center,
        ) { Text("옆", color = Ground, fontWeight = FontWeight.Black, fontSize = (size * 0.5).sp) }
        Spacer(Modifier.width(10.dp))
        Text("옆자리", color = Text1, fontWeight = FontWeight.Black, fontSize = 22.sp)
    }
}

@Composable
private fun Pill(text: String, fg: Color = Text2, bg: Color = Chip, bold: Boolean = false) {
    Text(
        text, color = fg, fontSize = 17.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun BigButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Teal else Chip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Ground else Text1, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, bg: Color = Card, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(bg).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

private fun maneuverIcon(m: Maneuver) = when (m) {
    Maneuver.RIGHT, Maneuver.EXIT_R, Maneuver.ENTER_HIGHWAY_R -> "↱"
    Maneuver.LEFT, Maneuver.EXIT_L, Maneuver.ENTER_HIGHWAY_L -> "↰"
    Maneuver.KEEP_RIGHT -> "↗"
    Maneuver.KEEP_LEFT -> "↖"
    Maneuver.UTURN -> "↶"
    Maneuver.MERGE -> "⤴"
    Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_EXIT -> "⟳"
    Maneuver.REST_AREA -> "↱"
    Maneuver.TOLL -> "⊓"
    Maneuver.TUNNEL -> "∩"
    Maneuver.ARRIVE -> "◎"
    Maneuver.NONE -> "↑"
}

private fun maneuverName(m: Maneuver) = when (m) {
    Maneuver.RIGHT -> "우회전"; Maneuver.LEFT -> "좌회전"; Maneuver.UTURN -> "유턴"
    Maneuver.MERGE -> "합류"; Maneuver.ENTER_HIGHWAY_R, Maneuver.ENTER_HIGHWAY_L -> "고속도로 진입"
    Maneuver.EXIT_R, Maneuver.EXIT_L -> "출구"; Maneuver.KEEP_RIGHT -> "오른쪽 길"; Maneuver.KEEP_LEFT -> "왼쪽 길"
    Maneuver.ROUNDABOUT -> "회전교차로"; Maneuver.ROUNDABOUT_EXIT -> "회전교차로 진출"; Maneuver.NONE -> "직진"
    Maneuver.REST_AREA -> "휴게소"; Maneuver.TOLL -> "톨게이트"; Maneuver.TUNNEL -> "터널"; Maneuver.ARRIVE -> "목적지"
}

// ---------------------------------------------------------------- 주행 중

@Composable
private fun DrivingScreen(ui: UiState, big: Boolean, a: Actions) {
    val d = ui.drive
    Row(Modifier.fillMaxSize()) {
        // 왼쪽: 내비 다음 동작(크게) + 차량 상태
        Column(
            Modifier.weight(0.85f).fillMaxHeight().background(PanelBg).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val t = d.turn
            val m = t?.maneuver ?: Maneuver.NONE
            Text("내비 안내", color = Text2, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF1A212B)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(maneuverIcon(m), color = Text1, fontSize = 120.sp, fontWeight = FontWeight.Bold, lineHeight = 130.sp)
                // 목적지에 닿으면 '도착', 경로 안내가 없으면 '경로 안내 없음'(예전엔 '직진'으로 보였음)
                val arrived = m == Maneuver.ARRIVE && (t?.distanceM ?: 99.0) <= 10.0
                val name = when {
                    arrived -> "도착"
                    m == Maneuver.NONE && d.routeActive == false -> "경로 안내 없음"
                    else -> maneuverName(m)
                }
                Text(if (t != null && !arrived) "${t.distanceM.roundToInt()}m" else "—", color = Text1, fontSize = 64.sp, fontWeight = FontWeight.Black)
                Text(name, color = Text2, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF1A212B)).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("${d.speedKmh.roundToInt()}", color = Text1, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("km/h", color = Text2, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                Pill(if (d.gear == Gear.REVERSE) "R" else "D", fg = Text1, bold = true)
                if (d.brake) Pill("브레이크", fg = Amber, bold = true)
            }
            // 깜빡이(방향지시등) 상태는 이 앱의 핵심이라 크게 보여 줌
            val sigOn = d.signal != Signal.OFF
            val sig = when (d.signal) { Signal.LEFT -> "◀  왼쪽 깜빡이 켜짐"; Signal.RIGHT -> "오른쪽 깜빡이 켜짐  ▶"; Signal.OFF -> "깜빡이 꺼짐" }
            Text(
                sig, color = if (sigOn) Ground else Text3, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (sigOn) Teal else Chip).padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        // 오른쪽: 옆자리 코칭 카드
        Column(
            Modifier.weight(1.15f).fillMaxHeight().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Pill(if (ui.settings.coaching) "옆자리 코칭 켜짐" else "옆자리 코칭 꺼짐 · 기록만")
            }
            val c = ui.card
            val isCheck = c?.stage == Stage.CHECK
            val accent = when (c?.stage) { Stage.CHECK, Stage.MEMORY, Stage.CAUTION, Stage.HABIT -> Amber; else -> Teal }
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(if (isCheck) AmberBg else Card)
                    .then(if (isCheck) Modifier.border(2.dp, Amber, RoundedCornerShape(26.dp)) else Modifier)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            ) {
                if (c == null) {
                    Text("옆자리에서 함께하고 있어요", color = Text1, fontSize = if (big) 40.sp else 34.sp, fontWeight = FontWeight.Black)
                    Text("필요한 순간에만 짧게 말씀드릴게요.", color = Text2, fontSize = if (big) 26.sp else 22.sp)
                } else {
                    val tag = when (c.stage) {
                        Stage.PREVIEW -> "미리 알림"; Stage.CHECK -> "확인"; Stage.DONE -> "완료"
                        Stage.HABIT -> "운전 습관"; Stage.CAUTION -> "주의 구간"; Stage.MEMORY -> "지난 주행 기억"; Stage.INFO -> "안내"
                        Stage.TALK -> if (c.headline.startsWith("질문")) "물어보신 것" else "강사 한마디"
                    }
                    Text(tag, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(c.headline, color = Text1, fontSize = if (big) 60.sp else 54.sp, fontWeight = FontWeight.Black, lineHeight = if (big) 70.sp else 64.sp)
                    if (c.stage == Stage.TALK) {
                        // 강사 한마디·대답: 제목 없이 내용을 크게 (지금 읽고 있는 문장)
                        Text(c.speech.removePrefix("잠깐만요! "), color = Color(0xFFD5DCE5), fontSize = if (big) 34.sp else 30.sp, lineHeight = if (big) 48.sp else 42.sp)
                    } else Text(c.body, color = Color(0xFFD5DCE5), fontSize = if (big) 36.sp else 31.sp, lineHeight = if (big) 50.sp else 44.sp)
                    if (c.stage != Stage.TALK) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val s = c.stage
                        Pill("① 미리 알림", fg = if (s == Stage.PREVIEW) Ground else Text3, bg = if (s == Stage.PREVIEW) Teal else Chip, bold = s == Stage.PREVIEW)
                        Pill("② 확인", fg = if (s == Stage.CHECK) Ground else Text3, bg = if (s == Stage.CHECK) Amber else Chip, bold = s == Stage.CHECK)
                        Pill("③ 완료", fg = if (s == Stage.DONE) Ground else Text3, bg = if (s == Stage.DONE) Teal else Chip, bold = s == Stage.DONE)
                    }
                }
            }
            // 못 들은 안내를 다시 듣는 큰 버튼 하나만 둠 (주행 중 화면 조작은 이것뿐)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("🔁  다시 듣기", false, Modifier.width(200.dp)) { a.repeat() }
                BigButton("🙋  질문하기", ui.askWaiting, Modifier.width(200.dp)) { a.ask() }
                // 말 상태: 받은 질문 → 알아들은 말 → 듣는 중 → 기본 안내
                when {
                    ui.question != null -> Text("🙋 \"${ui.question}\"", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    ui.heardText != null -> Text("🎤 '${ui.heardText}' 들었어요. 다시 말씀드릴게요", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    ui.askWaiting -> Text("🎤 질문을 듣고 있어요 · 예: \"합류는 언제 들어가요?\"", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    ui.listening -> Text("🎤 듣고 있어요 · \"다시\"라고 하거나 궁금한 걸 물어보세요", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    ui.settings.voiceRepeat -> Text("안내 직후 \"다시 말해 줘\"라고 하거나, 궁금한 건 물어보세요.", color = Text2, fontSize = 18.sp)
                    else -> Text("못 들으셨으면 눌러 주세요. 다른 조작은 필요 없어요.", color = Text2, fontSize = 18.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 출발 전

@Composable
private fun BriefScreen(ui: UiState, a: Actions) {
    val s = ui.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Logo(44)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("P · 정차 중")
                Text("설정", color = Text1, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Chip).clickable { a.openSettings(true) }.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("오늘도 옆자리에서 함께할게요", color = Text1, fontSize = 42.sp, fontWeight = FontWeight.Black)
            Text("기어를 D로 바꾸면 안내가 시작돼요.", color = Text2, fontSize = 21.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel(Modifier.weight(1f)) {
                Text("출발 전 한마디", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (ui.spotCount > 0) "지난번 급제동했던 곳이 오늘 길에 있으면 300m 전에 미리 알려 드릴게요."
                    else "회전·합류 전에 깜빡이를 확인해 드릴게요.",
                    color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("지난 주행 연비", ui.lastFuel?.let { String.format("%.1f km/L", it) } ?: "—", Modifier.weight(1f))
                    Stat("기억한 급제동 지점", "${ui.spotCount}곳", Modifier.weight(1f))
                }
                // 오늘의 미션: 지난 주행의 개선 포인트에서 하나만 골라 출발 전에 정해 줌
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AmberBg).border(2.dp, Amber, RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Text("오늘의 미션", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(ui.todayMission, color = Text1, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    ui.lastTip?.let { Text("지난번: $it", color = Text2, fontSize = 17.sp) }
                }
                if (ui.canOpenMap) ui.parking?.let { (_, _, at) ->
                    val time = java.text.SimpleDateFormat("M월 d일 a h:mm", java.util.Locale.KOREA).format(java.util.Date(at))
                    BigButton("🅿  주차 위치 보기 · $time", false, Modifier.fillMaxWidth()) { a.openParking() }
                }
            }
            Panel(Modifier.weight(1f)) {
                Toggle(
                    "옆자리 코칭",
                    if (s.coaching) "회전·합류·주의 구간을 옆에서 함께 안내해요" else "꺼져 있어요. 안내 없이 주행 기록만 남겨요",
                    s.coaching,
                ) { v -> a.settings { copy(coaching = v) } }
                Stat("옆자리 포인트", "${com.yeopjari.app.trip.Store.points(ui.pointsTotal)}P", Modifier.fillMaxWidth())
                Text("안전한 행동(미션) 하나에 1P예요. 하루 최대 100P까지 모여요. 운전 중엔 아무것도 누를 필요 없이 자동으로 쌓여요.", color = Text2, fontSize = 17.sp)
                Text("모은 포인트는 마이현대·블루멤버스 포인트로 받아 쓰는 것이 목표예요(현대차그룹 제휴 협의 후 확정). 차 화면에는 광고가 없어요.", color = Text3, fontSize = 16.sp)
                if (s.demoMode) {
                    Text("시연 주행", color = Blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    // 4개 시연을 2줄 2칸으로 보여 줘서 화면이 작아도 모두 보이게 함
                    Scenarios.Id.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { id ->
                                val playing = ui.playing == id
                                Row(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (playing) Blue else Chip)
                                        .clickable(enabled = ui.playing == null) { a.play(id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(id.title, color = Text1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(id.desc, color = if (playing) Text1 else Text2, fontSize = 16.sp, maxLines = 2)
                                    }
                                    Text(if (playing) "재생 중" else "▶", color = Text1, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("옆자리는 참고용 안내예요. 최종 판단과 책임은 운전자에게 있어요.", color = Text3, fontSize = 17.sp)
    }
}

// ---------------------------------------------------------------- 처음 실행 소개

@Composable
private fun IntroScreen(a: Actions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Logo(52)
        Text("안녕하세요, 저는 옆자리예요", color = Text1, fontSize = 46.sp, fontWeight = FontWeight.Black)
        listOf(
            "1" to "회전·합류하기 전에 미리 알려 드려요. 차로를 미리 옮길 수 있게요.",
            "2" to "깜빡이를 켰는지 확인해 드려요. 늦거나 반대쪽이면 바로 알려 드려요.",
            "3" to "운전이 끝나면 잘한 점 하나, 다음에 해 볼 것 하나만 알려 드려요.",
        ).forEach { (n, t) ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Teal), contentAlignment = Alignment.Center) {
                    Text(n, color = Ground, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Text(t, color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
            }
        }
        Text("주행 중에는 화면을 볼 필요 없이 목소리로만 안내해요. 못 들으셨으면 '다시 듣기'를 눌러 주세요.", color = Text2, fontSize = 21.sp)
        BigButton("시작할게요", true, Modifier.width(260.dp)) { a.closeIntro() }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Chip).padding(14.dp)) {
        Text(label, color = Text2, fontSize = 17.sp)
        Text(value, color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- 주행 후

@Composable
private fun ReportScreen(ui: UiState, a: Actions) {
    val r = ui.report ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("오늘의 주행 리포트", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("수고하셨어요", color = Text1, fontSize = 40.sp, fontWeight = FontWeight.Black)
            }
            Pill("P · 정차 중")
        }
        // 첫 화면은 두 가지만 크게: 잘한 점 하나, 다음에 해 볼 것 하나 (+ 왜 그런지)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1f)) {
                Text("오늘 잘한 점", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(r.goodPoint, color = Text1, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
                if (r.missions.isNotEmpty())
                    Text("미션 ${r.missions.size}개 성공 · +${com.yeopjari.app.trip.Store.points(r.points)}P", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Panel(Modifier.weight(1f).border(2.dp, Amber, RoundedCornerShape(22.dp)), bg = AmberBg) {
                Text("다음에 해 볼 것 하나", color = Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(r.improvement, color = Text1, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
                if (r.why.isNotBlank()) Text("왜요? ${r.why}", color = Color(0xFFE8DCC6), fontSize = 19.sp, lineHeight = 27.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton(if (ui.reportDetail) "자세히 접기" else "자세히 보기", false, Modifier.weight(1f)) { a.toggleDetail() }
            if (ui.canShare) BigButton("결과 공유하기", false, Modifier.weight(1f)) { a.share() }
            BigButton("확인", true, Modifier.weight(1f)) { a.closeReport() }
        }
        if (ui.askedQa.isNotEmpty()) Panel {
            Text("주행 중 물어보신 것", color = Blue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            ui.askedQa.forEach { (q, ans) ->
                Text("Q. $q?", color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(ans, color = Text2, fontSize = 19.sp, lineHeight = 27.sp)
            }
        }
        if (ui.reportDetail) ReportDetail(ui)
        Text("주행 기록은 차량 안에만 저장돼요. '결과 공유하기'는 직접 누를 때만, 위치 없이 요약만 보내요.", color = Text3, fontSize = 17.sp)
    }
}

@Composable
private fun ReportDetail(ui: UiState) {
    val r = ui.report ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ScoreTile("안전 점수", "${r.safetyScore}", Teal, Modifier.weight(1f))
        ScoreTile("에코 점수", "${r.ecoScore}", Blue, Modifier.weight(1f), r.avgFuelKmL?.let { String.format("평균 %.1f km/L", it) })
        ScoreTile("깜빡이 사용률", "${r.signalRate}%", Text1, Modifier.weight(1f), "제때 켬 ${r.signalOnTime} / ${r.maneuvers}회")
        ScoreTile("급가속 · 급제동", "${r.harshAccel} · ${r.harshBrakes.size}", Text1, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.weight(1f)) {
            Text("옆자리 미션", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!ui.settings.coaching && r.missions.isEmpty()) {
                Text("옆자리 코칭이 꺼져 있어 미션은 쉬었어요.", color = Text2, fontSize = 19.sp)
            } else if (r.missions.isEmpty()) {
                Text("이번엔 성공한 미션이 없어요. 다음에 도전해요!", color = Text2, fontSize = 19.sp)
            } else {
                r.missions.groupingBy { it }.eachCount().forEach { (m, n) ->
                    Text("✓ ${m.label}${if (n > 1) " ×$n" else ""}", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text("+${com.yeopjari.app.trip.Store.points(r.points)}P  ·  누적 ${com.yeopjari.app.trip.Store.points(ui.pointsTotal)}P", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Text("미션 하나에 1P, 하루 최대 100P예요.", color = Text2, fontSize = 17.sp)
            Text("급제동이 있었던 곳", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (r.harshBrakes.isEmpty()) Text("없었어요. 아주 부드러웠어요!", color = Text2, fontSize = 19.sp)
            r.harshBrakes.forEach {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Chip).padding(14.dp)) {
                    Text("● ${it.label}", color = Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("다음에 지날 때 미리 알려 드려요", color = Text2, fontSize = 17.sp)
                }
            }
        }
        Panel(Modifier.weight(1f)) {
            Text("실력 변화", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            ui.prevSignalRate?.let { prev ->
                if (r.maneuvers > 0) {
                    val now = r.onTimeRate
                    val diff = now - prev
                    val msg = when {
                        diff > 0 -> "지난번보다 깜빡이를 더 제때 켜셨어요! (제때 켬 ${prev}% → ${now}%)"
                        diff < 0 -> "지난번엔 ${prev}%를 제때 켰어요. 다음엔 조금 더 일찍 켜 봐요."
                        prev == 100 -> "지난번처럼 모두 제때 켰어요. 꾸준히 잘하고 계세요!"
                        else -> "지난번과 같은 ${prev}%예요. 다음엔 한 번 더 일찍 켜 봐요."
                    }
                    Text(msg, color = Text1, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
            // 최근 주행의 안전 점수를 막대로 (오래된 것 → 최근)
            val recent = ui.history.takeLast(7)
            if (recent.size >= 2) {
                Text("최근 ${recent.size}번 주행 안전 점수", color = Text2, fontSize = 17.sp)
                Row(Modifier.fillMaxWidth().heightIn(min = 110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    recent.forEachIndexed { i, h ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${h.safety}", color = if (i == recent.lastIndex) Teal else Text2, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Box(Modifier.fillMaxWidth().heightIn(min = (10 + h.safety * 0.8).dp).clip(RoundedCornerShape(6.dp)).background(if (i == recent.lastIndex) Teal else Chip))
                        }
                    }
                }
            }
            val streak = ui.history.reversed().takeWhile { it.brakes == 0 }.size
            if (streak >= 2) Text("급제동 없는 주행 ${streak}번 연속이에요!", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(String.format("주행 거리 %.1f km · %.0f분", r.distanceKm, r.durationMin.coerceAtLeast(1.0)), color = Text2, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ScoreTile(label: String, value: String, color: Color, modifier: Modifier, sub: String? = null) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Text2, fontSize = 18.sp)
        Text(value, color = color, fontSize = 44.sp, fontWeight = FontWeight.Black)
        sub?.let { Text(it, color = Text2, fontSize = 17.sp) }
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun SettingsScreen(ui: UiState, a: Actions) {
    val s = ui.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("설정", color = Text1, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("정차 중에만 바꿀 수 있어요")
                BigButton("완료", true, Modifier.width(120.dp)) { a.openSettings(false) }
            }
        }
        Panel {
            // 목소리와 말 많기 — 어르신은 낮고 또렷한 목소리를, 초보는 강사 모드를 고를 수 있게
            Text("목소리", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("여자 목소리", !s.maleVoice, Modifier.weight(1f)) { a.settings { copy(maleVoice = false) } }
                BigButton("남자 목소리", s.maleVoice, Modifier.weight(1f)) { a.settings { copy(maleVoice = true) } }
            }
            Text("말 많기", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.yeopjari.app.model.TalkLevel.entries.forEach { lv ->
                    BigButton(lv.label, s.talkLevel == lv, Modifier.weight(1f)) { a.settings { copy(talkLevel = lv) } }
                }
            }
            Text(s.talkLevel.desc + " · 회전·합류 직전, 주의 구간, 뒤차 경고 때는 말하지 않고, 중요한 안내가 오면 하던 이야기를 멈춰요.", color = Text2, fontSize = 17.sp)
            Toggle("옆자리 코칭", "켜면 회전·합류·주의 구간을 함께 안내해요. 끄면 안내 없이 주행 기록만 남겨요.", s.coaching) { v -> a.settings { copy(coaching = v) } }
            Toggle("천천히 말하기", "안내를 조금 천천히 말해요", s.slowSpeech) { v -> a.settings { copy(slowSpeech = v) } }
            Toggle("중요한 안내 알림음", "깜빡이 확인·뒤차 경고처럼 중요한 안내 앞에만 짧게 '띵' 소리를 내요", s.chime) { v -> a.settings { copy(chime = v) } }
            Toggle("말로 다시 듣기·질문하기", "안내가 끝난 뒤 5초만 듣고, \"다시 말해 줘·뭐라고?\"면 다시 말하고 운전 질문이면 대답해요. 소리는 저장하지 않아요. (${ui.listenerName.ifBlank { "준비 중" }})", s.voiceRepeat) { v -> a.settings { copy(voiceRepeat = v) } }
            Toggle("Pleos 차량 음성", "차량에서 Pleos 음성으로 말해요. 끄면 기본 음성(말 빠르기 조절 가능). 앱을 다시 켤 때 적용 · 지금: ${ui.voiceName.ifBlank { "준비 중" }}", s.pleosVoice) { v -> a.settings { copy(pleosVoice = v) } }
            Toggle("시연 모드", "실제 차량 대신 시연용 주행을 재생해요 (시연은 늘 처음 운전하는 분 기준으로 자세히 안내)", s.demoMode) { v -> a.settings { copy(demoMode = v) } }
            Text("기억한 급제동 지점 지우기 (${ui.spotCount}곳)", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Chip).clickable { a.clearSpots() }.padding(horizontal = 18.dp, vertical = 12.dp))
            Text("옆자리 소개 다시 듣기", color = Text1, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Chip).clickable { a.showIntro() }.padding(horizontal = 18.dp, vertical = 12.dp))
        }
        // 사고·고장 때 할 일 (한국도로공사 2차 사고 예방 행동요령 '비트박스')
        Panel(Modifier.border(2.dp, Amber, RoundedCornerShape(22.dp)), bg = AmberBg) {
            Text("사고·고장이 났을 때", color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Black)
            listOf(
                "비" to "비상등을 켜요",
                "트" to "트렁크를 열어 뒤차에 알려요",
                "밖" to "차 밖, 가드레일 바깥으로 대피해요",
                "스" to "스마트폰으로 신고해요 (112·119, 고속도로 무료 긴급견인 1588-2504)",
            ).forEach { (k, t) ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, color = Ground, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Amber).padding(horizontal = 12.dp, vertical = 4.dp))
                    Text(t, color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Text2, fontSize = 17.sp)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Ground),
        )
    }
}
