package com.yeopjari.app.coach

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * '말로 다시 듣기'와 '질문하기' — 옆자리가 안내를 마친 뒤 **5초 동안만**(또는 '질문하기'를 누른 뒤 8초) 듣고,
 * "다시 말해 줘 / 뭐라고 / 못 들었어 …" 같은 말이면 직전 안내를 다시 들려주고, 운전 질문이면 대답합니다.
 *
 * - 차량: Pleos 음성 인식 SDK(기기 안 방식, 인증 키 없음)
 * - 핸드폰 시연: 안드로이드 기본 음성 인식(가능하면 기기 안에서 처리)
 * - 소리는 저장하지 않고, 정해진 말이 있는지만 보고 버립니다.
 * - 옆자리나 내비가 말하는 동안에는 듣지 않습니다(자기 목소리를 듣지 않도록).
 */
interface RepeatListener {
    val name: String
    /**
     * [windowMs] 안에 말이 시작되지 않으면 그만 들음.
     * 들은 글자를 onHeard(글자, 말이 끝났는지)로 넘기고, true를 돌려받으면(처리함) 바로 그만 들음.
     */
    fun listen(windowMs: Long, onHeard: (String, Boolean) -> Boolean, onEnd: () -> Unit)
    fun cancel()
    fun release()
}

/** 다시 말해 달라는 말인지 판단 (띄어쓰기·말끝이 달라도 알아듣게) */
object RepeatWords {
    private val keys = listOf(
        "다시", "뭐라고", "뭐라구", "뭐라는", "못들었", "잘못들었", "안들렸", "안들려", "못들", "한번더", "한번만", "한번만더", "뭐라했",
    )

    fun isAsk(text: String): Boolean {
        val t = text.replace(Regex("[\\s.,?!~]"), "")
        if (t.isEmpty()) return false
        return keys.any { t.contains(it) }
    }
}

/** 안드로이드 기본 음성 인식 (핸드폰 시연용) */
class AndroidRepeatListener(context: Context) : RepeatListener {
    override val name = "기본 음성 인식"
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var active = false
    private var heardSpeech = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    override fun listen(windowMs: Long, onHeard: (String, Boolean) -> Boolean, onEnd: () -> Unit) {
        if (active || !available) { onEnd(); return }
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(app).also { rec = it }
        active = true; heardSpeech = false
        var fired = false
        fun finish() { if (active) { active = false; runCatching { r.cancel() }; onEnd() } }
        fun check(results: Bundle?, final: Boolean) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (fired || list.isEmpty()) return
            // 다시 듣기 말이 후보 중에 있으면 그것을 먼저, 아니면 가장 그럴듯한 첫 번째 결과
            val text = list.firstOrNull { RepeatWords.isAsk(it) } ?: list.first()
            if (onHeard(text, final)) { fired = true; finish() }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { heardSpeech = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { finish() }
            override fun onResults(results: Bundle?) { check(results, true); finish() }
            override fun onPartialResults(partialResults: Bundle?) { check(partialResults, false) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        runCatching { r.startListening(intent) }.onFailure { Log.w("Repeat", "start", it); finish(); return }
        // 5초 안에 말이 시작되지 않으면 그만 들음. 말하는 중이면 끝날 때까지(최대 4초 더) 기다림.
        main.postDelayed({ if (active && !heardSpeech) finish() }, windowMs)
        main.postDelayed({ finish() }, windowMs + 4000L)
    }

    override fun cancel() { if (active) { active = false; runCatching { rec?.cancel() } }; main.removeCallbacksAndMessages(null) }
    override fun release() { cancel(); runCatching { rec?.destroy() }; rec = null }
}

/** Pleos 음성 인식 (차량용, 기기 안 방식) */
class PleosRepeatListener(context: Context, onReady: (Boolean) -> Unit) : RepeatListener {
    override val name = "Pleos 음성 인식"
    private val main = Handler(Looper.getMainLooper())
    private var stt: ai.pleos.playground.stt.SpeechToText? = null
    private var active = false
    private var heardSpeech = false
    private var onHeard: ((String, Boolean) -> Boolean)? = null
    private var onEnd: (() -> Unit)? = null

    private val results = object : ai.pleos.playground.stt.listener.ResultListener {
        override fun onUpdated(text: String, completed: Boolean) {
            main.post {
                if (!active) return@post
                val handled = onHeard?.invoke(text, completed) == true
                if (handled || completed) finish()
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onStartedRecognition() { heardSpeech = true }
        override fun onEndedRecognition() {}
        override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) { main.post { finish() } }
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        runCatching {
            val s = ai.pleos.playground.stt.SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            stt = s
            s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() {
                    runCatching { s.setLocale(java.util.Locale.KOREAN); s.addListener(results) }
                        .onSuccess { main.post { onReady(true) } }.onFailure { main.post { onReady(false) } }
                }
                override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) { main.post { onReady(false) } }
            })
        }.onFailure { Log.w("Repeat", "Pleos STT unavailable", it); main.post { onReady(false) } }
    }

    private fun finish() {
        if (!active) return
        active = false
        runCatching { stt?.stop() }
        main.removeCallbacksAndMessages(null)
        onEnd?.invoke()
    }

    override fun listen(windowMs: Long, onHeard: (String, Boolean) -> Boolean, onEnd: () -> Unit) {
        val s = stt
        if (active || s == null) { onEnd(); return }
        this.onHeard = onHeard; this.onEnd = onEnd
        active = true; heardSpeech = false
        runCatching { s.request() }.onFailure { finish(); return }
        main.postDelayed({ if (active && !heardSpeech) finish() }, windowMs)
        main.postDelayed({ finish() }, windowMs + 4000L)
    }

    override fun cancel() { if (active) { active = false; runCatching { stt?.stop() } }; main.removeCallbacksAndMessages(null) }
    override fun release() { cancel(); runCatching { stt?.removeListener(results); stt?.release() }; stt = null }
}
