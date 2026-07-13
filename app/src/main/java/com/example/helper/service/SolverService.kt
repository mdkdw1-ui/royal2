package com.example.helper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.example.helper.R
import com.example.helper.core.Match3Solver
import com.example.helper.ui.GridOverlayView

class SolverService : Service() {

    companion object {
        // Logcat에서 에러만 필터링해서 보기 위한 태그 이름
        private const val TAG = "SolverService_Error"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var gridOverlayView: GridOverlayView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "서비스가 시작되었습니다 (onCreate)")

        // 1. 다른 앱 위에 그리기 권한 체크 (안 되어 있으면 튕김 방지용 사전 차단)
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "실패: '다른 앱 위에 표시' 권한이 허용되지 않았습니다.")
            Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 2. 레이아웃 인플레이트 에러 체크
            floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            val view = floatingView ?: throw NullPointerException("overlay_layout 인플레이트 실패")

            // 3. 오버레이 윈도우 파라미터 설정
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Android 8.0 이상 표준
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            // 4. 화면에 뷰 추가 (여기서 BadTokenException 자주 발생)
            windowManager.addView(view, layoutParams)
            Log.d(TAG, "오버레이 윈도우가 화면에 정상적으로 추가되었습니다.")

            // 5. 뷰 컴포넌트 연결 및 리스너 등록
            gridOverlayView = view.findViewById(R.id.gridOverlayView)
            val btnToggleGrid = view.findViewById<Button>(R.id.btnToggleGrid)
            val btnToggleHints = view.findViewById<Button>(R.id.btnToggleHints)

            btnToggleGrid.setOnClickListener {
                gridOverlayView.showGridLines = !gridOverlayView.showGridLines
                btnToggleGrid.text = if (gridOverlayView.showGridLines) "격자 숨기기" else "격자 보이기"
            }

            btnToggleHints.setOnClickListener {
                gridOverlayView.showMatchHints = !gridOverlayView.showMatchHints
                btnToggleHints.text = if (gridOverlayView.showMatchHints) "힌트 숨기기" else "힌트 보이기"
            }

        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "윈도우 토큰 에러: OverLay 뷰를 띄울 수 없습니다. 권한 혹은 OS 버전을 확인하세요.", e)
            Toast.makeText(this, "화면 오버레이 생성 실패 (BadToken)", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "초기화 중 알 수 없는 에러 발생: ${e.message}", e)
            stopSelf()
            return
        }

        // 6. 실시간 화면 캡처 분석 루프 실행 (에러 감싸기)
        try {
            startCaptureAndAnalysisLoop()
        } catch (e: SecurityException) {
            Log.e(TAG, "화면 공유(미디어 프로젝션) 권한 거부 또는 포그라운드 서비스 타입 누락 에러!", e)
            Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "화면 분석 루프 실행 중 에러 발생", e)
        }
    }

    /**
     * 실시간 화면 분석 루프 (에러 추적로그 장착)
     */
    private fun startCaptureAndAnalysisLoop() {
        Log.d(TAG, "화면 캡처 분석 루프 진입 성공")
        
        // [주의] 만약 기존에 여기에 스크린샷 코드(MediaProjection)가 들어가 있다면 
        // 미디어 프로젝션을 시작하는 순간 유저가 '지금 시작'을 안 누르면 SecurityException이 터집니다.
        // 위의 onCreate단에서 try-catch로 잡아서 로그를 뿌려주도록 설계했습니다.
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "서비스가 종료됩니다 (onDestroy)")
        try {
            floatingView?.let {
                windowManager.removeView(it)
                Log.d(TAG, "오버레이 뷰가 정상적으로 제거되었습니다.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "뷰 제거 중 에러 발생 (이미 제거되었거나 뷰가 없을 수 있음): ${e.message}")
        }
    }
}
