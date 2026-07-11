package com.example.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.helper.core.GridDetector
import com.example.helper.core.Match3Solver

class SolverService : Service() {
    private val TAG = "SolverService"
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var windowManager: WindowManager
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // 🎯 [예전 코드 참조 핵심 핵심] 메모리 튕김을 막는 재사용 비트맵 선언
    private var reusableBitmap: Bitmap? = null
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            // 포그라운드 서비스 안정적 승격
            try {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(8888, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(8888, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Foreground 서비스 승격 실패", e)
            }

            // 미디어 프로젝션 및 백그라운드 앵커 스레드 초기화
            try {
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels

                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                
                // 예전 코드와 동일한 정석적인 백그라운드 워커 스레드 가동
                backgroundThread = HandlerThread("Grid_Scanner").apply { start() }
                backgroundHandler = Handler(backgroundThread!!.looper)

                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "SolverCapture", screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
                )
                
                // 1초 간격 실시간 스캔 루프 시작
                backgroundHandler?.post(analyzeRunnable)
            } catch (e: Exception) {
                Log.e(TAG, "프로젝션 및 캡처 레이어 초기화 실패", e)
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try {
                processCurrentFrameFast()
            } catch (e: Exception) {
                Log.e(TAG, "스캔 루프 스킵", e)
            }
            backgroundHandler?.postDelayed(this, 1000) // 1초 간격 처리
        }
    }

    private fun processCurrentFrameFast() {
        val reader = imageReader ?: return
        // 이미지 획득 실패 시 안전하게 널 처리하여 크래시 방지
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            
            // 🎯 [예전 코드 참조 핵심] 기기별 특성(여백 픽셀)을 고려한 Stride 정밀 계산
            val rowPadding = rowStride - pixelStride * screenWidth
            val adjustedWidth = screenWidth + rowPadding / pixelStride

            // 🎯 [예전 코드 참조 핵심] 매번 힙 메모리에 비트맵을 생성하지 않고 딱 한 번만 생성하여 메모리 재사용
            if (reusableBitmap == null || reusableBitmap!!.width != adjustedWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(adjustedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }

            val bitmap = reusableBitmap!!
            buffer.rewind() // 포인터를 처음으로 돌려 안전하게 픽셀 복사 준비
            bitmap.copyPixelsFromBuffer(buffer)

            // 복사된 초경량/안정적 비트맵을 기반으로 기존 격자 탐색 알고리즘 수행
            val gridResult = GridDetector.getGridDataFromBitmap(bitmap)
            
            if (gridResult.success && gridResult.categoryGrid != null) {
                val bestMoves = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
                if (bestMoves.isNotEmpty()) {
                    Log.d(TAG, "🎯 최적의 퍼즐 매칭 이동 경로 발견: ${bestMoves[0]}")
                    // 추후 이 좌표 데이터를 활용해 예전 코드처럼 화면에 선이나 화살표 오버레이 렌더링 가능
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "프레임 비트맵 변환 및 분석 중 예외 발생 예방 차단", t)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "solver_service_channel",
                "Match-3 Solver Running",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "solver_service_channel")
            .setContentTitle("Match-3 Solver가 가동 중입니다")
            .setContentText("실시간 게임 화면 캡처 및 분석 레이어 동작 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            backgroundHandler?.removeCallbacks(analyzeRunnable)
            backgroundThread?.quitSafely()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            // 서비스 종료 시점 비트맵 메모리 완전 해제
            reusableBitmap?.recycle()
            reusableBitmap = null
            Log.d(TAG, "SolverService 모든 자원 정상 해제 완료")
        } catch (e: Exception) {
            Log.e(TAG, "자원 해제 중 예외", e)
        }
    }
}
