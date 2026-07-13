package com.example.helper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.example.helper.R
import com.example.helper.core.Match3Solver
import com.example.helper.ui.GridOverlayView

class SolverService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var gridOverlayView: GridOverlayView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. 새 레이아웃(overlay_layout.xml) 인플레이트
        floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        // 2. 다른 앱(게임) 위에 화면을 띄우기 위한 최상단 오버레이 윈도우 설정
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        // 3. 윈도우 매니저에 최상단 뷰 등록
        windowManager.addView(floatingView, layoutParams)

        // 4. 통합된 레이아웃 안의 컴포넌트들 연결
        gridOverlayView = floatingView.findViewById(R.id.gridOverlayView)
        val btnToggleGrid = floatingView.findViewById<Button>(R.id.btnToggleGrid)
        val btnToggleHints = floatingView.findViewById<Button>(R.id.btnToggleHints)

        // 5. [격자 제어] 버튼 클릭 리스너 - 오직 격자선 유무만 따로 토글
        btnToggleGrid.setOnClickListener {
            gridOverlayView.showGridLines = !gridOverlayView.showGridLines
            btnToggleGrid.text = if (gridOverlayView.showGridLines) "격자 숨기기" else "격자 보이기"
        }

        // 6. [힌트 제어] 버튼 클릭 리스너 - 오직 5개 매칭 화살표 유무만 따로 토글
        btnToggleHints.setOnClickListener {
            gridOverlayView.showMatchHints = !gridOverlayView.showMatchHints
            btnToggleHints.text = if (gridOverlayView.showMatchHints) "힌트 숨기기" else "힌트 보이기"
        }

        // 7. 기존에 사용하시던 미디어 프로젝션이나 이미지 캡처/분석 루프를 여기서 시작합니다.
        startCaptureAndAnalysisLoop()
    }

    /**
     * 실시간 화면 분석 및 매칭 힌트 연동 루프
     */
    private fun startCaptureAndAnalysisLoop() {
        // 기존 SolverService에 구현되어 있던 미디어 프로젝션(MediaProjection) 캡처 루프 코드가 있다면 여기에 위치시킵니다.
        // 예시 흐름: 
        // 1. 실시간 스크린샷 비트맵 가져오기
        // 2. GridDetector.detectGrid(bitmap)를 호출하여 게임판 이차원 배열(grid) 획득
        // 3. 아래처럼 Match3Solver와 GridOverlayView를 연결해 줍니다.
        
        /* [실제 화면 분석 데이터 연동 구현 예시]
        val currentGrid: Array<IntArray> = GridDetector.analyze(screenshotBitmap) // 기존 분석 함수 예시
        
        // 오직 5개 매칭(OOXOO)만 정밀 타격하여 후보군 추출
        val candidates = Match3Solver.getMatchCandidates(currentGrid)
        
        // UI 스레드에서 격자 뷰에 데이터 전달 및 갱신
        gridOverlayView.post {
            gridOverlayView.updateData(
                rows = currentGrid.size, 
                cols = currentGrid[0].size, 
                candidates = candidates
            )
        }
        */
    }

    override fun onDestroy() {
        super.onDestroy()
        // 서비스 종료 시 화면을 방해하지 않도록 오버레이 뷰 제거
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
