package com.yeopjari.app.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yeopjari.app.model.CoachEvent
import com.yeopjari.app.model.Stage

/**
 * 음성 출력. 차량에서는 Pleos 음성 합성 SDK, 그 외(핸드폰 시연)에는 안드로이드 기본 음성을 씁니다.
 *
 * 말하는 규칙
 * - 한 번에 한 문장만 말하고, **말하던 문장은 끝까지 읽습니다** (중간에 끊지 않음).
 * - 방향지시등 확인(가장 중요)은 기다리던 다른 안내보다 먼저, 지금 문장이 끝나자마자 말합니다.
 * - 줄을 서서 너무 오래 기다린 안내는 이미 지난 이야기이므로 버립니다. 종류마다 기다릴 수 있는 시간이 다릅니다.
 * - 화면 카드는 실제로 읽기 시작하는 순간에 바꿔서, 들리는 말과 보이는 글이 항상 같게 합니다.
 * - 말하는 동안에는 다른 소리(내비 등)를 잠시 줄이도록 오디오 포커스를 요청합니다.
 * - 기본은 조금 천천히 말하고, 깜빡이 확인·뒤차 경고처럼 중요한 안내 앞에만 짧은 '띵' 소리를 냅니다.
 * - 페달 오조작 의심처럼 아주 급한 안내만 말하던 문장을 끊고 바로 말합니다.
 * - 실제로 읽는 데 걸린 시간을 재서, 안내를 언제 시작할지 계산하는 값(글자당 시간)을 맞춥니다.
 * - 강사 한마디·질문 대답(TALK)은 가장 낮은 순위입니다. 다른 안내가 오면 말하던 중이라도 끊고 "잠깐만요!" 하고 그 안내를 먼저 말합니다.
 *   끊긴 대답은 바로 이어 말하지 않고 보관해 두었다가, 앞으로 한동안 안내할 것이 없을 때(컨트롤러가 판단) 한 번에 이어서 말합니다.
 *   상황 한마디는 지난 이야기라 버립니다.
 */
class Speaker(context: Context, preferPleos: Boolean) {
    private class Item(val ev: CoachEvent, val queuedAt: Long)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var engine: VoiceEngine? = null
    private var ready = false
    private val pending = ArrayDeque<Item>()
    private var current: Item? = null
    private var startedAt = 0L

    /** 천천히 말하기 (설정, 기본 음성에서만 적용) */
    var slow: Boolean = true
    /** 중요한 안내 앞 알림음 (설정) */
    var chime: Boolean = true
    private val chimeFile = java.io.File(app.cacheDir, "yeopjari_chime.wav")
    /** 마지막으로 읽은 코칭 안내 (다시 듣기용) */
    var lastSaid: CoachEvent? = null; private set
    var lastSaidAt: Long = 0L; private set
    /** 지금 쓰는 음성 이름 (설정 화면 표시용) */
    val voiceName: String get() = engine?.name ?: "음성 준비 중"

    /** 문장을 읽기 시작할 때 (화면 카드 갱신용, 메인 스레드) */
    var onStart: ((CoachEvent) -> Unit)? = null
    /** 코칭 안내 하나를 다 읽었을 때 (말로 다시 듣기 대기 시작용, 메인 스레드). 대기열이 비었을 때만 불림 */
    var onIdleAfter: ((CoachEvent) -> Unit)? = null
    /** 읽는 데 걸린 실제 시간으로 잰 글자당 시간(ms) */
    var onMeasured: ((Long) -> Unit)? = null
    val isBusy: Boolean get() = current != null || pending.isNotEmpty()

    /**
     * 시연 모드에서는 음성을 '미디어 소리'로 내보냅니다(화면 녹화에 담기도록). 실제 차량에서는 길안내 음성으로 둡니다.
     */
    var forRecording: Boolean = false
        set(v) { field = v; attrs = buildAttrs(); engine?.setAttributes(attrs); focusRequest = buildFocus() }

    private fun buildAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (forRecording) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private fun buildFocus(): AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build() else null
    private var attrs: AudioAttributes = buildAttrs()
    private var focusRequest: AudioFocusRequest? = buildFocus()
    private var pendingIntro: String? = null
    /** 끊겼거나 미뤄 둔 질문 대답 (여유 있을 때 이어서 말하기용) */
    private val paused = mutableListOf<CoachEvent>()
    /** 다음 안내 앞에 '잠깐만요!'를 붙일지 */
    private var interruptedTalk = false
    /** 남자 목소리 */
    var male: Boolean = false
        set(v) { field = v; engine?.setMale(v) }

    init {
        runCatching { writeChime(chimeFile) }
        // 차량이면 Pleos 음성을 먼저 써 보고, 안 되면 기본 음성으로 바꿉니다.
        if (preferPleos) start(PleosVoice(app)) { start(AndroidVoice(app)) {} } else start(AndroidVoice(app)) {}
    }

    private fun start(e: VoiceEngine, onFail: () -> Unit) {
        e.init(
            onReady = { main.post { engine = e; e.setAttributes(attrs); e.setMale(male); ready = true; pendingIntro?.let { pendingIntro = null; intro(it) } } },
            onFail = { main.post { runCatching { e.release() }; onFail() } },
        )
    }

    /** 코칭 안내 한 개를 말하기 대기열에 넣습니다 */
    fun say(ev: CoachEvent) {
        if (ev.speech.isBlank()) return
        if (!ready) { if (ev.headline.isNotBlank()) onStart?.invoke(ev); return } // 음성 엔진이 없으면 화면으로만
        val item = Item(ev, SystemClock.elapsedRealtime())
        if (ev.stage == Stage.TALK) {
            pending.addLast(item)
            if (current == null) next()
            return
        }
        // 강사 한마디·질문 대답을 하던 중이면 끊고 이 안내를 먼저 (대답은 나중에 이어서)
        pauseTalk()
        if (ev.urgent) {
            // 아주 급한 안내: 기다리던 것을 모두 버리고, 말하던 문장도 끊고 바로 말합니다.
            pending.clear()
            pending.addFirst(item)
            if (current != null) { current = null; engine?.stop() }
            next(); return
        }
        if (ev.stage == Stage.CHECK) {
            // 방향지시등 확인이 생기면, 기다리던 덜 중요한 안내는 이미 늦었으므로 버리고 맨 앞에 둡니다.
            pending.removeAll { it.ev.stage != Stage.CHECK }
            pending.addFirst(item)
        } else {
            pending.addLast(item)
        }
        if (current == null) next()
    }

    /** 말하던(또는 기다리던) TALK를 멈추고, 이어서 말할 대답은 따로 보관합니다. */
    private fun pauseTalk() {
        val cur = current
        val waiting = pending.filter { it.ev.stage == Stage.TALK }
        if (cur?.ev?.stage != Stage.TALK && waiting.isEmpty()) return
        val keep = (listOfNotNull(cur?.takeIf { it.ev.stage == Stage.TALK }) + waiting).map { it.ev }.filter { it.resumable && !isIntro(it.speech) }
        // 끊긴 대답은 끊긴 문장부터 보관 (먼저 받은 질문이 앞에 오도록 뒤에 붙임)
        paused += keep
        pending.removeAll { it.ev.stage == Stage.TALK }
        if (cur?.ev?.stage == Stage.TALK) {
            current = null
            engine?.stop()
            interruptedTalk = true
        }
    }

    private fun isIntro(t: String) = t == Talk.RESUME || t == Talk.RESUME_AGAIN

    /** 보관 중인 대답이 있는지 */
    val hasPaused: Boolean get() = paused.isNotEmpty()

    /** 보관 중인 대답을 모두 말하는 데 걸릴 예상 시간(ms) — 이만큼 조용할 때만 이어서 말함 */
    fun pausedEstimateMs(): Long = paused.sumOf { CoachEngine.estimateSpeechMs(it.speech) } + CoachEngine.estimateSpeechMs(Talk.RESUME)

    /** 질문 대답을 지금 말하지 않고 여유 있을 때로 미룸 */
    fun deferTalk(events: List<CoachEvent>) { paused += events }

    /** 여유가 생겼을 때 보관한 대답을 한 번에 이어서 말함 */
    fun resumePaused(intro: String) {
        if (paused.isEmpty()) return
        val list = paused.toList(); paused.clear()
        val first = list.first()
        say(CoachEvent(Stage.TALK, first.headline, first.body, intro, 0L, resumable = true))
        list.forEach { say(it.copy(timeMs = 0L)) }
    }

    /** 보관한 대답을 버림 (주행이 끝나 리포트에 글로 남길 때) */
    fun clearPaused() = paused.clear()

    /** 리포트처럼 화면 카드와 상관없는 문장 */
    fun speak(text: String, stage: Stage) = say(CoachEvent(stage, "", "", text, 0L))

    private fun next() {
        if (current != null) return
        val now = SystemClock.elapsedRealtime()
        pending.removeAll { now - it.queuedAt > maxWaitMs(it.ev.stage) }
        val item = pending.removeFirstOrNull()
        if (item == null) { releaseFocus(); return }
        current = item
        val e = engine ?: return
        if (e.supportsRate) e.setRate(if (slow) SLOW_RATE else 1.0f)
        requestFocus()
        if (item.ev.headline.isNotBlank()) {
            onStart?.invoke(item.ev)
            // '네, 맞아요' 같은 짧은 한마디는 다시 듣기 대상에서 뺍니다.
            if (item.ev.headline != Phrases.fixed.headline) { lastSaid = item.ev; lastSaidAt = now }
        }
        // 중요한 안내(확인·뒤차 경고) 앞에만 '띵'. 급한 안내는 소리 없이 바로.
        val ding = chime && item.ev.stage == Stage.CHECK && !item.ev.urgent
        // 말하던 이야기를 끊고 들어온 안내면 '잠깐만요!'를 붙여 자연스럽게
        val text = if (interruptedTalk && item.ev.stage != Stage.TALK && !item.ev.urgent) Talk.INTERRUPT + item.ev.speech else item.ev.speech
        if (item.ev.stage != Stage.TALK) interruptedTalk = false
        val go = {
            if (current === item) e.speak(text,
                onStart = { startedAt = SystemClock.elapsedRealtime() },
                onDone = { main.post { done(item) } })
        }
        if (ding && playChime()) main.postDelayed(go, CHIME_MS) else go()
    }

    private fun done(item: Item) {
        if (current !== item) return
        current = null
        // 실제 읽은 시간 재기 (끊기지 않고 끝난 문장만)
        if (startedAt > 0) {
            val chars = item.ev.speech.count { !it.isWhitespace() }
            val ms = SystemClock.elapsedRealtime() - startedAt
            if (chars >= 8 && ms > 300) onMeasured?.invoke(ms / chars)
        }
        startedAt = 0L
        next()
        if (current == null && pending.isEmpty() && item.ev.headline.isNotBlank()) onIdleAfter?.invoke(item.ev)
    }

    private fun playChime(): Boolean = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(chimeFile.absolutePath)
            setOnCompletionListener { it.release() }
            prepare(); start()
        }
        true
    }.getOrDefault(false)

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.requestAudioFocus(it) }
    }

    private fun releaseFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
    }

    /** 처음 실행 때 소개처럼 화면 카드와 상관없는 긴 안내 */
    fun intro(text: String) { if (ready) say(CoachEvent(Stage.DONE, "", "", text, 0L)) else pendingIntro = text }

    /** 말하던 것과 기다리던 것을 모두 멈춤 (주행 끝 등) */
    fun stopAll() { pending.clear(); paused.clear(); interruptedTalk = false; if (current != null) { current = null; engine?.stop() }; releaseFocus() }

    fun shutdown() { main.removeCallbacksAndMessages(null); pending.clear(); engine?.release() }

    private companion object {
        const val SLOW_RATE = 0.85f
        const val CHIME_MS = 220L

        /** 안내 종류별로 줄 서서 기다릴 수 있는 최대 시간 */
        fun maxWaitMs(stage: Stage): Long = when (stage) {
            Stage.CHECK -> 6000L     // 방향지시등 확인·뒤차 경고
            Stage.PREVIEW -> 4000L   // 미리 알림은 늦으면 의미가 없음
            Stage.HABIT -> 4000L
            Stage.MEMORY -> 5000L
            Stage.CAUTION, Stage.INFO -> 6000L
            Stage.DONE -> 15000L     // 리포트 음성·완료 한마디
            Stage.TALK -> 20000L     // 강사 한마디·질문 대답 (여러 문장이 줄 서 있을 수 있음)
        }

        /** 짧고 부드러운 '띵' 소리(0.18초)를 파일로 만듭니다 — 따로 소리 파일을 넣지 않아도 되도록 */
        fun writeChime(f: java.io.File) {
            if (f.exists() && f.length() > 44) return
            val rate = 22050
            val n = (rate * 0.18).toInt()
            val data = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val env = minOf(1.0, i / (rate * 0.01)) * Math.exp(-t * 14)  // 부드럽게 시작해서 빠르게 사라짐
                val v = (Math.sin(2 * Math.PI * 880 * t) * 0.6 + Math.sin(2 * Math.PI * 1320 * t) * 0.4) * env * 0.35
                data.putShort((v * Short.MAX_VALUE).toInt().toShort())
            }
            val pcm = data.array()
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(pcm.size)
            f.outputStream().use { it.write(h.array()); it.write(pcm) }
        }
    }
}
