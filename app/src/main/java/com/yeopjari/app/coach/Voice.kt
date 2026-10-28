package com.yeopjari.app.coach

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 음성 합성 엔진 — 차량에서는 Pleos TextToSpeech SDK, 핸드폰(시연)에서는 안드로이드 기본 음성을 씁니다.
 * 옆자리는 한 번에 한 문장만 넘기므로(대기열은 Speaker가 관리) 엔진은 '한 문장 읽기'만 하면 됩니다.
 */
interface VoiceEngine {
    val name: String
    /** 준비되면 onReady, 못 쓰면 onFail */
    fun init(onReady: () -> Unit, onFail: () -> Unit)
    fun setAttributes(attrs: AudioAttributes)
    /** 말하기 빠르기를 바꿀 수 있는지 (천천히 말하기) */
    val supportsRate: Boolean
    fun setRate(rate: Float)
    /** 남자 목소리(true) / 여자 목소리(false) */
    fun setMale(male: Boolean)
    /** 한 문장 읽기. 끝나거나 멈추면 onDone (다른 스레드일 수 있음) */
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit)
    fun stop()
    fun release()
}

/** 안드로이드 기본 음성 */
class AndroidVoice(context: Context) : VoiceEngine, TextToSpeech.OnInitListener {
    override val name = "기본 음성"
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready: (() -> Unit)? = null
    private var fail: (() -> Unit)? = null
    private var counter = 0
    private var currentId: String? = null
    private var currentStart: (() -> Unit)? = null
    private var currentDone: (() -> Unit)? = null
    override val supportsRate = true

    override fun init(onReady: () -> Unit, onFail: () -> Unit) {
        ready = onReady; fail = onFail
        tts = TextToSpeech(app, this)
    }

    override fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) { fail?.invoke(); return }
        t.language = Locale.KOREAN
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (id == currentId) currentStart?.invoke() }
            override fun onDone(id: String?) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(id)
            override fun onStop(id: String?, interrupted: Boolean) = finished(id)
        })
        ready?.invoke()
    }

    private fun finished(id: String?) {
        if (id != null && id == currentId) { currentId = null; currentDone?.invoke() }
    }

    override fun setAttributes(attrs: AudioAttributes) { tts?.setAudioAttributes(attrs) }
    override fun setRate(rate: Float) { tts?.setSpeechRate(rate) }

    /**
     * 남·여 목소리: 기기에 깔린 한국어 목소리 중 이름으로 남·여를 고르고(구글·삼성 음성 이름 규칙),
     * 맞는 목소리가 없으면 음 높이로 구분합니다(남자는 낮게).
     */
    override fun setMale(male: Boolean) {
        val t = tts ?: return
        val voices = runCatching { t.voices?.filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired } }.getOrNull().orEmpty()
        val maleRe = Regex("(male|smtm|-koc-|-kod-|man)", RegexOption.IGNORE_CASE)
        val femaleRe = Regex("(female|smtf|smtl|-ism-|-kob-|woman)", RegexOption.IGNORE_CASE)
        val pick = voices.firstOrNull { v -> if (male) maleRe.containsMatchIn(v.name) && !femaleRe.containsMatchIn(v.name) else femaleRe.containsMatchIn(v.name) }
        if (pick != null) { runCatching { t.voice = pick }; t.setPitch(1.0f) }
        else t.setPitch(if (male) 0.78f else 1.05f)
        Log.i("AndroidVoice", "voice male=$male pick=${pick?.name} of ${voices.map { it.name }}")
    }

    override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
        val id = "y${counter++}"
        currentId = id; currentStart = onStart; currentDone = onDone
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() { tts?.stop() }
    override fun release() { tts?.stop(); tts?.shutdown(); tts = null }
}

/**
 * Pleos 음성 합성 (차량 안 Pleos 음성 서비스가 있어야 동작). 기기 안(ON_DEVICE) 방식이라 인증 키가 필요 없습니다.
 * ※ SDK에 말하기 빠르기 설정이 없어 '천천히 말하기'는 기본 음성에서만 적용됩니다(설명회에서 확인 예정).
 */
class PleosVoice(context: Context) : VoiceEngine {
    override val name = "Pleos 차량 음성"
    private val app = context.applicationContext
    private var tts: ai.pleos.playground.tts.TextToSpeech? = null
    @Volatile private var speaking = false
    private var currentStart: (() -> Unit)? = null
    private var currentDone: (() -> Unit)? = null
    override val supportsRate = false

    private val events = object : ai.pleos.playground.tts.listener.EventListener {
        override fun onStart() { currentStart?.invoke() }
        override fun onReady() {}
        override fun onDone() = finished()
        override fun onError(e: ai.pleos.playground.tts.constant.ErrorType) { Log.w(TAG, "tts error $e"); finished() }
        override fun onStop() = finished()
        override fun onUpdatedRms(rms: Double) {}
    }

    private fun finished() {
        if (!speaking) return
        speaking = false
        currentDone?.invoke()
    }

    override fun init(onReady: () -> Unit, onFail: () -> Unit) {
        runCatching {
            val t = ai.pleos.playground.tts.TextToSpeech(app, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            tts = t
            t.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() {
                    runCatching { t.setLocale(Locale.KOREAN); t.addEventListener(events) }
                        .onSuccess { onReady() }.onFailure { onFail() }
                }
                override fun onFailure(e: ai.pleos.playground.tts.constant.ErrorType) { Log.w(TAG, "tts init $e"); onFail() }
            })
        }.onFailure { Log.w(TAG, "Pleos TTS unavailable", it); onFail() }
    }

    override fun setAttributes(attrs: AudioAttributes) { runCatching { tts?.setAudioAttributes(attrs) } }
    override fun setRate(rate: Float) {}

    /** Pleos 음성의 화자 종류 바꾸기(setSpeakerType). 쓸 수 있는 값은 설명회에서 확인 예정 — 안 되면 기본 목소리 그대로 */
    override fun setMale(male: Boolean) {
        runCatching { tts?.setSpeakerType(if (male) "male" else "female") }.onFailure { Log.w(TAG, "speaker type", it) }
    }

    override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
        currentStart = onStart; currentDone = onDone; speaking = true
        runCatching { tts?.speak(text, true) }.onFailure { Log.w(TAG, "speak", it); finished() }
    }

    override fun stop() { runCatching { tts?.stop() } }
    override fun release() { runCatching { tts?.removeEventListener(events); tts?.release() }; tts = null }

    private companion object { const val TAG = "PleosVoice" }
}
