package com.example.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.helper.core.Match3Solver
import com.example.helper.GridDetector
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

/**
 * PC 버전의 main.py의 메인 루프 역할을 수행하는 안드로이드 포그라운드 서비스
 */
class SolverService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null // 지난 대화에서 정의한 OverlayView 인스턴스
    private var captureTimer: Timer? = null
    private val UPDATE_INTERVAL_MS = 1000L // config.py의 UPDATE_INTERVAL_MS 매핑

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        setupOverlayWindow()
        startAnalysisLoop()
    }

    /**
     * 1. 시스템 최상단 레이어에 투명한 OverlayView 배치 (Tkinter 대체)
     */
    private fun setupOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 오버레이 뷰 객체 생성 (사용자 커스텀 뷰)
        // 여기서는 예시를 위해 기본 View를 사용하며 실제 구현 시 OverlayView(this)를 대입합니다.
        overlayView = android.view.View(this).apply {
            // 이 레이아웃은 터치 이벤트를 관통시켜 아래의 게임 조작이 가능하게 만듭니다.
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // 터치 투과 (게임 터치 가능하게)
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }

        windowManager.addView(overlayView, params)
    }

    /**
     * 2. PC의 main.py 메인 루프 동작 이식 (실시간 캡처 -> 분석 -> 알고리즘 -> 드로잉)
     */
    private fun startAnalysisLoop() {
        captureTimer = fixedRateTimer(period = UPDATE_INTERVAL_MS) {
            // [Step A] 현재 화면 가상 캡처 비트맵 가져오기
            val currentScreenshot: Bitmap? = captureCurrentScreen()
            
            if (currentScreenshot != null) {
                // [Step B] GridDetector를 통해 7x9 행렬 추출
                val gridResult = GridDetector.getGridDataFromBitmap(currentScreenshot)
                
                if (gridResult.success && gridResult.categoryGrid != null && gridResult.bounds != null) {
                    
                    // [Step C] Match3Solver 알고리즘 가동하여 최선의 수 계산
                    val candidates = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
                    
                    // [Step D] 메인 UI 스레드에서 오버레이 뷰에 화살표 갱신 명령 보냄
                    overlayView?.post {
                        // overlayView.updateData(gridResult.bounds, candidates)
                        // 이 메서드 내부에서 invalidate()가 호출되어 화면에 화살표를 실시간으로 그림
                    }
                }
            }
        }
    }

    /**
     * MediaProjection 기법을 이용해 실시간 폰 화면을 비트맵으로 덤프하는 함수 (mss 대체)
     */
    private fun captureCurrentScreen(): Bitmap? {
        // 실제 구현 시에는 MediaProjection의 ImageReader 객체로부터 최신 가상 버퍼 스냅샷을 획득합니다.
        // 현재는 아키텍처 연동을 위한 가짜 객체 리턴 템플릿입니다.
        return null 
    }

    /**
     * 안드로이드 OS 정책상 백그라운드 장기 구동을 위한 알림창(Notification) 활성화
     */
    private fun startForegroundServiceNotification() {
        val channelId = "match3_solver_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Solver Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Match-3 Solver 가동 중")
            .setContentText("게임 화면 위에 최선의 수를 계산하여 화살표를 표시합니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        captureTimer?.cancel()
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }
}
