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

class HelperService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var gridOverlayView: GridOverlayView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. 레이아웃 인플레이트 (XML을 메모리에 로드)
        floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        // 2. 최상단 화면에 띄우기 위한 윈도우 매니저 옵션 설정
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // 다른 앱 위에 뜨는 플로팅 창 설정
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        // 3. 화면에 뷰 추가
        windowManager.addView(floatingView, layoutParams)

        // 4. 뷰 컴포넌트 연결
        gridOverlayView = floatingView.findViewById(R.id.gridOverlayView)
        val btnToggleGrid = floatingView.findViewById<Button>(R.id.btnToggleGrid)
        val btnToggleHints = floatingView.findViewById<Button>(R.id.btnToggleHints)

        // 5. [핵심] 격자 숨기기/보이기 버튼 이벤트 리스너
        btnToggleGrid.setOnClickListener {
            // 다른 기능은 두고 오직 격자 유무 플래그만 토글
            gridOverlayView.showGridLines = !gridOverlayView.showGridLines
            
            // 상태에 맞춰 버튼 글씨 변경
            btnToggleGrid.text = if (gridOverlayView.showGridLines) "격자 숨기기" else "격자 보이기"
        }

        // 6. [핵심] 힌트 숨기기/보이기 버튼 이벤트 리스너
        btnToggleHints.setOnClickListener {
            // 다른 기능은 두고 오직 힌트 유무 플래그만 토글
            gridOverlayView.showMatchHints = !gridOverlayView.showMatchHints
            
            // 상태에 맞춰 버튼 글씨 변경
            btnToggleHints.text = if (gridOverlayView.showMatchHints) "힌트 숨기기" else "힌트 보이기"
        }

        // 테스트용 임시 가상 데이터 구동 연동 예시
        runSolverSimulation()
    }

    /**
     * 분석 가상 데이터 연동 (실제 구현부에서는 스크린샷 캡처 루프에서 실행됨)
     */
    private fun runSolverSimulation() {
        // 예시용 임의의 8x8 게임판 보드 (0=공백, 1=빨강, 2=파랑, 3=노랑)
        // 아래 데이터는 가로 4번째 줄에 [1, 1, 0, 1, 1] 형태로 OO X OO 가 구성된 상태 예시입니다.
        val mockGrid = Array(8) { IntArray(8) { 0 } }
        mockGrid[3][0] = 1; mockGrid[3][1] = 1; mockGrid[3][2] = 0; mockGrid[3][3] = 1; mockGrid[3][4] = 1
        mockGrid[2][2] = 1 // 위 칸에 같은 색(1)을 배치해 아래로 내리면 5개 매칭이 되도록 유도

        // 1. 알고리즘 연동: 보드를 분석하여 5개 연속이 완성되는 좌표 추출
        val candidates = Match3Solver.getMatchCandidates(mockGrid)

        // 2. 뷰 데이터 갱신 연동: 뷰에 분석 결과를 전달하여 화면에 그리도록 명령
        gridOverlayView.updateData(rows = 8, cols = 8, candidates = candidates)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 서비스 종료 시 화면에서 오버레이 뷰 제거
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
