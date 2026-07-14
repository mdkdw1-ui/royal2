package com.example.helper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

enum class BlockColor { RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN }

data class MatchMove(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
    val description: String
)

class SolverService : Service() {

    private val TAG = "GridHelper_Service"
    private val binder = SolverBinder()
    
    // [요구사항 3] 사용자 지정 행렬 (기본값 설정 및 UI에서 +/- 조절 가능)
    var rows = 11
    var cols = 9

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var windowManager: WindowManager
    
    // 윈도우 분리 구조 (터치 먹통 해결책)
    private var floatingControlView: LinearLayout? = null // 1. 드래그 가능한 플로팅 제어 패널
    private var visualOverlayView: VisualOverlayView? = null // 2. 전체화면 게임 오버레이 캔버스

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var isCapturing = false
    private var isManualAnalysisRequested = false 

    // [요구사항 1] 로직 온오프 스위치 플래그
    private var isLogicEnabled = true
    
    // [요구사항 5] 상태바 크기 모드 플래그
    private var isLargeMode = true

    // [요구사항 3] 좌상, 우상, 좌하, 우하 프리폼 격자 제어점
    private val ptTL = PointF()
    private val ptTR = PointF()
    private val ptBL = PointF()
    private val ptBR = PointF()
    private var isCalibrationMode = false // 격자 조정 모드 활성화 여부

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        backgroundThread = HandlerThread("SolverBackgroundWorker").apply { start() }
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        // 초기 디바이스 해상도 기반 격자 가이드라인 모서리 기본 위치 계산
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        
        ptTL.set(w * 0.05f, h * 0.25f)
        ptTR.set(w * 0.95f, h * 0.25f)
        ptBL.set(w * 0.05f, h * 0.75f)
        ptBR.set(w * 0.95f, h * 0.75f)
        
        // 윈도우 이원화 생성 (터치 차단 원천 봉쇄)
        createVisualOverlayWindow()
        createFloatingControlWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceInternal()
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("RESULT_DATA")
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                backgroundHandler?.post { setupScreenCapturePipeline(resultCode, resultData) }
            }
        }
        return START_STICKY
    }

    /**
     * 1️⃣ [요구사항 2, 5] 무조건 터치가 통과하는 전체화면 시각화 캔버스 윈도우 생성
     */
    private fun createVisualOverlayWindow() {
        mainHandler.post {
            visualOverlayView = VisualOverlayView(applicationContext)
            
            // 핵심: FLAG_NOT_TOUCHABLE을 기본 부여하여 게임 화면 터치를 0%도 방해하지 않음
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(visualOverlayView, params)
        }
    }

    /**
     * 2️⃣ [요구사항 1, 5] 화면 어디든 움직이고 크기 조절이 가능한 플로팅 제어 패널 생성
     */
    private fun createFloatingControlWindow() {
        mainHandler.post {
            val context = applicationContext
            floatingControlView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#EE111111"))
                setPadding(20, 15, 20, 15)
            }

            val floatParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 150
            }

            // 플로팅 무빙 터치 리스너 장착 (드래그 이동)
            floatingControlView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = floatParams.x
                            initialY = floatParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            floatParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            floatParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatingControlView, floatParams)
                            return true
                        }
                    }
                    return false
                }
            })

            refreshFloatingPanelUI()
            windowManager.addView(floatingControlView, floatParams)
        }
    }

    /**
     * 🔄 [요구사항 5] 대형 / 소형 모드 스위칭 갱신 렌더러
     */
    private fun refreshFloatingPanelUI() {
        val view = floatingControlView ?: return
        view.removeAllViews()
        val context = applicationContext

        // 모드 전환 통합 토글 버튼
        val btnSizeToggle = Button(context).apply {
            text = if (isLargeMode) "◀ 미니 모드" else "▶ 확장 패널"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isLargeMode = !isLargeMode
                refreshFloatingPanelUI()
            }
        }
        view.addView(btnSizeToggle)

        if (isLargeMode) {
            // [대형 모드 인터페이스]
            val tvStatus = TextView(context).apply {
                text = "엔진: ${if(isLogicEnabled) "ON (대기)" else "OFF"}\n격자: ${rows}x${cols}"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 10, 0, 10)
            }
            view.addView(tvStatus)

            // 수동 스캔 버튼
            val btnScan = Button(context).apply {
                text = "🎯 수동 격자 매칭 스캔"
                setBackgroundColor(Color.parseColor("#FF6200EE"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (!isLogicEnabled) {
                        showToastOnMainThread("로직이 OFF 상태입니다. ON으로 변경하세요."); return@setOnClickListener
                    }
                    isManualAnalysisRequested = true
                    showToastOnMainThread("화면 스캔 시작...")
                }
            }
            view.addView(btnScan)

            // 로직 온오프 스위치 버튼
            val btnLogicToggle = Button(context).apply {
                text = if (isLogicEnabled) "⚙️ 로직 ON 상태" else "❌ 로직 OFF 상태"
                setBackgroundColor(if (isLogicEnabled) Color.parseColor("#007F0E") else Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isLogicEnabled = !isLogicEnabled
                    text = if (isLogicEnabled) "⚙️ 로직 ON 상태" else "❌ 로직 OFF 상태"
                    setBackgroundColor(if (isLogicEnabled) Color.parseColor("#007F0E") else Color.DKGRAY)
                    if(!isLogicEnabled) visualOverlayView?.updateMoves(emptyList())
                }
            }
            view.addView(btnLogicToggle)

            // 격자 수정 모드 토글 버튼
            val btnCalibrate = Button(context).apply {
                text = if (isCalibrationMode) "💾 격자 설정 완료" else "📐 격자 위치 조절"
                setBackgroundColor(if (isCalibrationMode) Color.parseColor("#FF00DF") else Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    toggleCalibrationMode()
                }
            }
            view.addView(btnCalibrate)

            // 행열 수치 미세조정 패널
            val matrixBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val btnAddRow = Button(context).apply { text = "행+"; setOnClickListener { rows++; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            val btnSubRow = Button(context).apply { text = "행-"; setOnClickListener { if(rows>3) rows--; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            val btnAddCol = Button(context).apply { text = "열+"; setOnClickListener { cols++; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            val btnSubCol = Button(context).apply { text = "열-"; setOnClickListener { if(cols>3) cols--; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            matrixBox.addView(btnSubRow); matrixBox.addView(btnAddRow); matrixBox.addView(btnSubCol); matrixBox.addView(btnAddCol)
            view.addView(matrixBox)

            // 킬 스위치 (서비스 완전 종료 버튼)
            val btnKill = Button(context).apply {
                text = "☠️ 헬퍼 프로그램 종료 (킬스위치)"
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    stopSelf()
                }
            }
            view.addView(btnKill)
        } else {
            // [소형 모드 인터페이스] -> 오직 화면을 가리지 않게 최소화된 알약 버튼 형태
            val btnQuickScan = Button(context).apply {
                text = "⚡ 스캔"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#FF6200EE"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (isLogicEnabled) isManualAnalysisRequested = true
                }
            }
            view.addView(btnQuickScan)
        }
    }

    /**
     * 📐 [요구사항 3] 격자 조정 모드 진입 시 전체화면 오버레이에 터치 권한 일시 부여 함수
     */
    private fun toggleCalibrationMode() {
        isCalibrationMode = !isCalibrationMode
        val overlay = visualOverlayView ?: return
        val params = overlay.layoutParams as WindowManager.LayoutParams
        
        if (isCalibrationMode) {
            // 터치를 가로채서 4개의 모서리를 드래그할 수 있도록 차단 해제
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            showToastOnMainThread("📐 모서리를 끌어 퍼즐 영역에 맞추세요. (자석 효과 작동)")
        } else {
            // 조절 완료 시 다시 완전 터치 통과 모드로 복구
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            showToastOnMainThread("💾 격자 좌표가 메모리에 고정되었습니다.")
        }
        windowManager.updateViewLayout(overlay, params)
        refreshFloatingPanelUI()
        overlay.invalidate()
    }

    private fun setupScreenCapturePipeline(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            stopCapturePipeline()

            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { super.onStop(); stopCapturePipeline() }
            }, backgroundHandler)

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GridHelperCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
            )

            isCapturing = true

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturing || !isLogicEnabled) return@setOnImageAvailableListener
                val image = reader?.acquireLatestImage() ?: return@setOnImageAvailableListener
                
                try {
                    if (isManualAnalysisRequested) {
                        isManualAnalysisRequested = false
                        
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val w = metrics.widthPixels
                        val h = metrics.heightPixels
                        val rowPadding = rowStride - pixelStride * w

                        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        // 사용자가 임의 지정한 4점 기하 구조 격자를 바탕으로 스캔 연산 가동
                        val matchedMoves = analyzeAndSolvePerspective(bitmap)
                        
                        mainHandler.post {
                            visualOverlayView?.updateMoves(matchedMoves)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "분석 실패: ${e.message}")
                } finally {
                    image.close()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "파이프라인 구축 에러", e)
        }
    }

    /**
     * 🎯 [요구사항 4] 하이엔드 OOXOO 전용 정밀 판별기 (일반 3,4매칭 원천 배제 및 색상 스캔 고도화)
     */
    private fun findBestMoves(board: Array<Array<BlockColor>>): List<MatchMove> {
        val moves = mutableListOf<MatchMove>()
        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (board[r][c] == BlockColor.UNKNOWN) continue

                for (i in 0 until 4) {
                    val nr = r + dr[i]
                    val nc = c + dc[i]

                    if (nr in 0 until rows && nc in 0 until cols) {
                        if (board[nr][nc] == BlockColor.UNKNOWN) continue
                        
                        // 가상 스왑 시뮬레이션
                        val temp = board[r][c]
                        board[r][c] = board[nr][nc]
                        board[nr][nc] = temp

                        // [요구사항 4 적용] OOO, OOOO 매칭은 무시하고, 오직 완벽한 샌드위치 브릿지인 OOXOO 패턴만 필터링 검출
                        val hasOoxoo = isStrictOoxooPattern(board, r, c) || isStrictOoxooPattern(board, nr, nc)

                        if (hasOoxoo) {
                            val targetColor = board[r][c]
                            val korColor = getKoreanColorName(targetColor)
                            val desc = "⚡[$korColor 절대강자 OOXOO] 디스코볼 확정 배치! 💣"
                            moves.add(MatchMove(r, c, nr, nc, desc))
                        }

                        // 복구
                        board[nr][nc] = board[r][c]
                        board[r][c] = temp
                    }
                }
            }
        }
        return moves
    }

    private fun isStrictOoxooPattern(board: Array<Array<BlockColor>>, r: Int, c: Int): Boolean {
        val color = board[r][c]
        if (color == BlockColor.UNKNOWN) return false

        // 가로축 중심 OOXOO 검증 ([c-2]==C && [c-1]==C && [c]==중심 && [c+1]==C && [c+2]==C)
        if (c >= 2 && c < cols - 2) {
            if (board[r][c - 2] == color && board[r][c - 1] == color &&
                board[r][c + 1] == color && board[r][c + 2] == color) return true
        }
        // 세로축 중심 OOXOO 검증
        if (r >= 2 && r < rows - 2) {
            if (board[r - 2][c] == color && board[r - 1][c] == color &&
                board[r + 1][c] == color && board[r + 2][c] == color) return true
        }
        return false
    }

    /**
     * 📐 [요구사항 3] 양방향 선형 보간(Bilinear Interpolation) 적용 세포점 추출기
     * 유저가 네 모서리를 찌그러뜨리거나 경사지게 맞추어도, 수학적으로 완벽하게 각 블록의 중심을 추적해 냅니다.
     */
    private fun analyzeAndSolvePerspective(bitmap: Bitmap): List<MatchMove> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val board = Array(rows) { Array(cols) { BlockColor.UNKNOWN } }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // 비율 계산 (0.0 ~ 1.0)
                val u = (c + 0.5f) / cols
                val v = (r + 0.5f) / rows

                // 4점 공간 상의 위치 보간 연산
                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y

                val targetX = (1 - v) * topX + v * bottomX
                val targetY = (1 - v) * topY + v * bottomY

                board[r][c] = detectCellColorROI(pixels, width, height, targetX, targetY)
            }
        }
        return findBestMoves(board)
    }

    private fun detectCellColorROI(pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float): BlockColor {
        val radius = 10
        val cX = centerX.toInt()
        val cY = centerY.toInt()
        var rCnt = 0; var bCnt = 0; var yCnt = 0; var gCnt = 0; var pCnt = 0
        val hsv = FloatArray(3)

        for (y in (cY - radius)..(cY + radius)) {
            for (x in (cX - radius)..(cX + radius)) {
                if (x < 0 || x >= width || y < 0 || y >= height) continue
                val pixel = pixels[y * width + x]
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
                when {
                    (hsv[0] in 0f..22f) || (hsv[0] in 338f..360f) -> rCnt++
                    hsv[0] in 23f..72f -> yCnt++
                    hsv[0] in 73f..160f -> gCnt++
                    hsv[0] in 161f..250f -> bCnt++
                    hsv[0] in 251f..337f -> pCnt++
                }
            }
        }

        val threshold = 50
        val maxMap = mapOf(BlockColor.RED to rCnt, BlockColor.BLUE to bCnt, BlockColor.YELLOW to yCnt, BlockColor.GREEN to gCnt, BlockColor.PURPLE to pCnt)
        val best = maxMap.maxByOrNull { it.value } ?: return BlockColor.UNKNOWN
        return if (best.value > threshold) best.key else BlockColor.UNKNOWN
    }

    private fun getKoreanColorName(color: BlockColor): String {
        return when(color) {
            BlockColor.RED -> "빨강"; BlockColor.BLUE -> "파랑"; BlockColor.YELLOW -> "노랑"
            BlockColor.GREEN -> "초록"; BlockColor.PURPLE -> "보라"; else -> "미정"
        }
    }

    /**
     * 🎨 격자 가이드 가시화 및 4방향 자석 제어점 캔버스 클래스
     */
    inner class VisualOverlayView(context: Context) : View(context) {
        private val linePaint = Paint().apply { color = Color.parseColor("#8000FF00"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val textPaint = Paint().apply { color = Color.YELLOW; textSize = 42f; style = Paint.Style.FILL }
        private val handlePaint = Paint().apply { color = Color.parseColor("#FF00DF"); style = Paint.Style.FILL }
        private val arrowPaint = Paint().apply { color = Color.parseColor("#FF00DF"); strokeWidth = 15f; style = Paint.Style.FILL_AND_STROKE }

        private var currentMoves = listOf<MatchMove>()
        private var selectedCorner: PointF? = null
        private val touchRadius = 70f
        private val magnetThreshold = 35f // [요구사항 3] 자석 효과 임계 수치 (35픽셀 이내 근접 시 정렬 결합)

        fun updateMoves(moves: List<MatchMove>) {
            this.currentMoves = moves
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 1. 왜곡 대응형 사선 격자망 가이드라인 드로잉
            for (i in 0..cols) {
                val ratio = i.toFloat() / cols
                val topX = (1 - ratio) * ptTL.x + ratio * ptTR.x
                val topY = (1 - ratio) * ptTL.y + ratio * ptTR.y
                val botX = (1 - ratio) * ptBL.x + ratio * ptBR.x
                val botY = (1 - ratio) * ptBL.y + ratio * ptBR.y
                canvas.drawLine(topX, topY, botX, botY, linePaint)
            }
            for (j in 0..rows) {
                val ratio = j.toFloat() / rows
                val leftX = (1 - ratio) * ptTL.x + ratio * ptBL.x
                val leftY = (1 - ratio) * ptTL.y + ratio * ptBL.y
                val rightX = (1 - ratio) * ptTR.x + ratio * ptBR.x
                val rightY = (1 - ratio) * ptTR.y + ratio * ptBR.y
                canvas.drawLine(leftX, leftY, rightX, rightY, linePaint)
            }

            // 2. 격자 세팅 모드일 때만 화면 4귀퉁이에 앵커 노브(원형) 렌더링
            if (isCalibrationMode) {
                canvas.drawCircle(ptTL.x, ptTL.y, 30f, handlePaint)
                canvas.drawCircle(ptTR.x, ptTR.y, 30f, handlePaint)
                canvas.drawCircle(ptBL.x, ptBL.y, 30f, handlePaint)
                canvas.drawCircle(ptBR.x, ptBR.y, 30f, handlePaint)
            }

            // 3. [요구사항 5] 미니모드든 대형모드든 힌트 발견 시 화면 중앙 화살표 가이드는 상시 표출
            if (isLogicEnabled && currentMoves.isNotEmpty()) {
                val move = currentMoves.first()
                
                // 원본 좌표 복원을 위한 격자 셀 중심 역산 구동
                val uFrom = (move.fromCol + 0.5f) / cols
                val vFrom = (move.fromRow + 0.5f) / rows
                val startX = (1-vFrom)*((1-uFrom)*ptTL.x + uFrom*ptTR.x) + vFrom*((1-uFrom)*ptBL.x + uFrom*ptBR.x)
                val startY = (1-vFrom)*((1-uFrom)*ptTL.y + uFrom*ptTR.y) + vFrom*((1-uFrom)*ptBL.y + uFrom*ptBR.y)

                val uTo = (move.toCol + 0.5f) / cols
                val vTo = (move.toRow + 0.5f) / rows
                val endX = (1-vTo)*((1-uTo)*ptTL.x + uTo*ptTR.x) + vTo*((1-uTo)*ptBL.x + uTo*ptBR.x)
                val endY = (1-vTo)*((1-uTo)*ptTL.y + uTo*ptTR.y) + vTo*((1-uTo)*ptBR.y + uTo*ptBR.y)

                // 최강 OOXOO 화살표 선명하게 렌더링
                canvas.drawLine(startX, startY, endX, endY, arrowPaint)
                canvas.drawCircle(startX, startY, 20f, Paint().apply { color = Color.RED })
                canvas.drawCircle(endX, endY, 20f, Paint().apply { color = Color.GREEN })
                canvas.drawText(move.description, startX - 100f, startY - 40f, textPaint)
            }
        }

        /**
         * 📐 [요구사항 3] 마그네틱(Magnetic Snap) 기능 탑재 모서리 드래그 이벤트 핸들러
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isCalibrationMode) return false

            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectedCorner = when {
                        abs(x - ptTL.x) < touchRadius && abs(y - ptTL.y) < touchRadius -> ptTL
                        abs(x - ptTR.x) < touchRadius && abs(y - ptTR.y) < touchRadius -> ptTR
                        abs(x - ptBL.x) < touchRadius && abs(y - ptBL.y) < touchRadius -> ptBL
                        abs(x - ptBR.x) < touchRadius && abs(y - ptBR.y) < touchRadius -> ptBR
                        else -> null
                    }
                    return selectedCorner != null
                }
                MotionEvent.ACTION_MOVE -> {
                    val corner = selectedCorner ?: return false
                    corner.set(x, y)

                    // 🔥 [마그네틱 스냅 로직 구현] 근접한 수직/수평 좌표 축에 자석처럼 고정
                    when (corner) {
                        ptTL -> {
                            if (abs(ptTL.x - ptBL.x) < magnetThreshold) ptTL.x = ptBL.x  // 좌측 수직 자석
                            if (abs(ptTL.y - ptTR.y) < magnetThreshold) ptTL.y = ptTR.y  // 상단 수평 자석
                        }
                        ptTR -> {
                            if (abs(ptTR.x - ptBR.x) < magnetThreshold) ptTR.x = ptBR.x  // 우측 수직 자석
                            if (abs(ptTR.y - ptTL.y) < magnetThreshold) ptTR.y = ptTL.y  // 상단 수평 자석
                        }
                        ptBL -> {
                            if (abs(ptBL.x - ptTL.x) < magnetThreshold) ptBL.x = ptTL.x  // 좌측 수직 자석
                            if (abs(ptBL.y - ptBR.y) < magnetThreshold) ptBL.y = ptBR.y  // 하단 수평 자석
                        }
                        ptBR -> {
                            if (abs(ptBR.x - ptTR.x) < magnetThreshold) ptBR.x = ptTR.x  // 우측 수직 자석
                            if (abs(ptBR.y - ptBL.y) < magnetThreshold) ptBR.y = ptBL.y  // 하단 수평 자석
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> { selectedCorner = null }
            }
            return false
        }
    }

    private fun stopCapturePipeline() {
        isCapturing = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    override fun onDestroy() {
        stopCapturePipeline()
        floatingControlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        visualOverlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
