package com.yeopjari.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import com.yeopjari.app.ui.Actions
import com.yeopjari.app.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 차량(Android Automotive)이 아니면 휴대폰 시연용: 시연 모드로만 동작하고 화면을 비율대로 줄여 보여 줌
        val isCar = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        requestRuntimePermissions(isCar)
        controller = AppController(this, isCar)
        controller.start()

        val actions = Actions(
            play = controller::play,
            settings = { f -> controller.updateSettings { it.f() } },
            openSettings = controller::openSettings,
            closeReport = controller::closeReport,
            clearSpots = controller::clearSpots,
            repeat = { controller.repeatLast() },
            closeIntro = controller::closeIntro,
            showIntro = controller::showIntroAgain,
            toggleDetail = controller::toggleReportDetail,
            openParking = controller::openParking,
            share = controller::shareReport,
            ask = controller::askQuestion,
        )
        setContent {
            val ui by controller.ui.collectAsState()
            if (isCar) {
                App(ui, actions)
            } else {
                // 차량 화면(약 1280×720) 기준으로 만든 화면을 휴대폰 크기에 맞게 통째로 축소
                BoxWithConstraints {
                    val scale = minOf(1f, maxWidth.value / 1280f, maxHeight.value / 720f)
                    val d = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
                        App(ui, actions)
                    }
                }
            }
        }
    }

    /** 차량 속도·위치처럼 사용자 허락이 필요한 권한 요청 */
    private fun requestRuntimePermissions(isCar: Boolean) {
        // 마이크: '말로 다시 듣기'(안내 직후 5초만 듣고 저장하지 않음). 핸드폰 시연에서도 씀
        val wanted = (if (isCar) arrayOf(
            "android.car.permission.CAR_SPEED",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        ) else arrayOf(Manifest.permission.RECORD_AUDIO)).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
