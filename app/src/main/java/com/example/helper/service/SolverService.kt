package com.example.helper.service

import android.app.*
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import com.example.helper.util.AppLogger
import com.example.helper.util.GridFeature
import com.example.helper.util.GridPredictor
import com.example.helper.util.GridSeedDB
import com.example.helper.util.LevelGridMemory
import com.example.helper.util.UpdateChecker
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class SolverService : Service() {
    private val TAG = "OOXOO_Auto"
    private val binder = SolverBinder()

    private var rows = 11
    private var cols = 9
    private val ptTL = PointF()
    private val ptTR = PointF()
    private val ptBL = PointF()
    private val ptBR = PointF()

    private var isAutoDetectEnabled = true
    private var currentFingerprint: String = ""
    private var lastLoggedRows = -1
    private var lastLoggedCols = -1
    private var lastLoggedSource = ""
    private var lastFingerprintForGameChange = ""
    private var lastSeedLogTime = 0L
    private var latestThumbnail: Bitmap? = null
    private var currentBoardCrop: Bitmap? = null
    private var latestFullBitmap: Bitmap? = null
    private var currentFeature: FloatArray? = null

    private var isCompactMode = true
    private var isGridVisible = true
    private var isCalibrationMode = false
    private var selectedCorner = 0

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false
    private var draggedCorner = -1

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager

    private var overlayView: OverlayView? = null
    private var controlView: LinearLayout? = null
    private var gimmickManagerView: View? = null
    private var logDialogView: View? = null
    private var labelDialogView: View? = null
    private var floatParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isCapturing = false
    private var isScanning = false
    private var isImageGrabberMode = false
    private var isGrabberProcessing = false

    private var isAutoScanEnabled = true
    private val AUTO_SCAN_INTERVAL = 1000L
    private var autoScanRunnable: Runnable? = null

    private val dynamicTemplates = mutableListOf<Mat>()
    private val dynamicTemplateFiles = mutableListOf<File>()
    private val templateSizes = mutableListOf<Pair<Int, Int>>()
    private var isOpenCVInitialized = false

    private var foundPositions = mutableListOf<Pair<Int, Int>>()

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        backgroundThread = HandlerThread("OOXOO_Worker").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        if (OpenCVLoader.initDebug()) {
            AppLogger.d("OpenCV 로드 성공")
            isOpenCVInitialized = true
            backgroundHandler?.post { loadTemplatesFromStorage() }
        } else {
            AppLogger.e("OpenCV 로드 실패")
        }

        loadPreferences()
        createOverlayWindow()
        createControlWindow()
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
                backgroundHandler?.post { setupScreenCapture(resultCode, resultData) }
            }
        }
        return START_STICKY
    }

    private fun startAutoScan() {
        if (!isAutoScanEnabled || isScanning) return
        autoScanRunnable = object : Runnable {
            override fun run() {
                if (!isAutoScanEnabled || !isCapturing) return
                performScan()
                backgroundHandler?.postDelayed(this, AUTO_SCAN_INTERVAL)
            }
        }
        backgroundHandler?.post(autoScanRunnable!!)
    }

    private fun stopAutoScan() {
        isAutoScanEnabled = false
        autoScanRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        autoScanRunnable = null
    }

    private fun loadTemplatesFromStorage() {
        if (!isOpenCVInitialized) return
        synchronized(dynamicTemplates) {
            dynamicTemplates.forEach { it.release() }
            dynamicTemplates.clear()
            dynamicTemplateFiles.clear()
            templateSizes.clear()
        }
        try {
            val dir = getExternalFilesDir("gimmicks")
            if (dir != null && dir.exists()) {
                dir.listFiles { _, name -> name.endsWith(".png") }?.forEach { file ->
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)
                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                        synchronized(dynamicTemplates) {
                            dynamicTemplates.add(mat)
                            dynamicTemplateFiles.add(file)
                            templateSizes.add(Pair(mat.width(), mat.height()))
                        }
                        bitmap.recycle()
                    }
                }
                mainHandler.post {
                    Toast.makeText(applicationContext, "📋 기믹 ${dynamicTemplates.size}개 로드됨", Toast.LENGTH_SHORT).show()
                    refreshControlUI()
                }
            }
        } catch (e: Exception) { AppLogger.e("템플릿 로드 실패", e) }
    }

    // 🔥 v42: 기믹 저장 (UI 스레드 안전)
    private fun saveGimmickBitmap(bitmap: Bitmap) {
        backgroundHandler?.post {
            try {
                val dir = getExternalFilesDir("gimmicks")
                if (dir != null && !dir.exists()) dir.mkdirs()
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
                val file = File(dir, "gimmick_" + System.currentTimeMillis() + ".png")
                FileOutputStream(file).use { out ->
                    resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val newMat = Mat()
                Utils.bitmapToMat(resizedBitmap, newMat)
                Imgproc.cvtColor(newMat, newMat, Imgproc.COLOR_RGBA2GRAY)
                synchronized(dynamicTemplates) {
                    dynamicTemplates.add(newMat)
                    dynamicTemplateFiles.add(file)
                    templateSizes.add(Pair(newMat.width(), newMat.height()))
                }
                resizedBitmap.recycle()
                mainHandler.post {
                    Toast.makeText(applicationContext, "OK gimmick " + dynamicTemplates.size, Toast.LENGTH_SHORT).show()
                    refreshControlUI()
                }
            } catch (e: Exception) {
                AppLogger.e("gimmick save fail", e)
            } finally {
                mainHandler.post {
                    isImageGrabberMode = false
                    isGrabberProcessing = false
                    overlayView?.let {
                        try {
                            val params = it.layoutParams as WindowManager.LayoutParams
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            windowManager.updateViewLayout(it, params)
                        } catch (ex: Exception) {}
                    }
                    refreshControlUI()
                }
            }
        }
    }

    private fun deleteGimmick(file: File) {
        backgroundHandler?.post {
            try {
                synchronized(dynamicTemplates) {
                    val idx = dynamicTemplateFiles.indexOf(file)
                    if (idx != -1) {
                        dynamicTemplates[idx].release()
                        dynamicTemplates.removeAt(idx)
                        dynamicTemplateFiles.removeAt(idx)
                        templateSizes.removeAt(idx)
                        file.delete()
                    }
                }
                mainHandler.post {
                    Toast.makeText(applicationContext, "🗑️ 삭제됨", Toast.LENGTH_SHORT).show()
                    refreshControlUI()
                    hideGimmickManager()
                    showGimmickManager()
                }
            } catch (e: Exception) { AppLogger.e("삭제 실패", e) }
        }
    }

    private fun clearAllGimmicks() {
        backgroundHandler?.post {
            try {
                synchronized(dynamicTemplates) {
                    dynamicTemplates.forEach { it.release() }
                    dynamicTemplates.clear()
                    dynamicTemplateFiles.clear()
                    templateSizes.clear()
                }
                getExternalFilesDir("gimmicks")?.listFiles()?.forEach { it.delete() }
                mainHandler.post {
                    Toast.makeText(applicationContext, "🧹 전체 삭제됨", Toast.LENGTH_SHORT).show()
                    refreshControlUI()
                    hideGimmickManager()
                }
                overlayView?.invalidate()
            } catch (e: Exception) { AppLogger.e("전체 삭제 실패", e) }
        }
    }

    private fun showGimmickManager() {
        mainHandler.post {
            if (gimmickManagerView != null) return@post
            val context = applicationContext
            val mainLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FA1E1E1E"))
                setPadding(30, 30, 30, 30)
            }
            val tvTitle = TextView(context).apply {
                text = "📋 등록된 기믹 (${dynamicTemplateFiles.size}개)"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 20)
            }
            mainLayout.addView(tvTitle)

            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(300), dpToPx(350))
            }
            val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val currentFiles = synchronized(dynamicTemplates) { ArrayList(dynamicTemplateFiles) }
            if (currentFiles.isEmpty()) {
                val tvEmpty = TextView(context).apply {
                    text = "저장된 기믹이 없습니다."
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, 50, 0, 50)
                }
                listContainer.addView(tvEmpty)
            } else {
                currentFiles.forEach { file ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 10, 0, 10)
                    }
                    val ivThumb = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(50))
                        setBackgroundColor(Color.DKGRAY)
                        setPadding(2, 2, 2, 2)
                        try { setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)) }
                        catch (e: Exception) { setImageResource(android.R.drawable.ic_menu_report_image) }
                    }
                    row.addView(ivThumb)
                    val tvInfo = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(20, 0, 20, 0)
                        }
                        text = file.name.substringBefore(".png").take(15)
                        setTextColor(Color.WHITE)
                        textSize = 11f
                    }
                    row.addView(tvInfo)
                    val btnDelete = Button(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(65), dpToPx(35))
                        text = "삭제"
                        textSize = 11f
                        setBackgroundColor(Color.parseColor("#D32F2F"))
                        setTextColor(Color.WHITE)
                        setOnClickListener { deleteGimmick(file) }
                    }
                    row.addView(btnDelete)
                    listContainer.addView(row)
                }
            }
            scrollView.addView(listContainer)
            mainLayout.addView(scrollView)

            val btnClearAll = Button(context).apply {
                text = "🧹 전체 삭제"
                setBackgroundColor(Color.parseColor("#FF8C00"))
                setTextColor(Color.WHITE)
                setOnClickListener { clearAllGimmicks() }
            }
            mainLayout.addView(btnClearAll)

            val btnClose = Button(context).apply {
                text = "닫기"
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener { hideGimmickManager() }
            }
            mainLayout.addView(btnClose)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            gimmickManagerView = mainLayout
            windowManager.addView(gimmickManagerView, params)
        }
    }

    private fun hideGimmickManager() {
        mainHandler.post {
            gimmickManagerView?.let {
                try { windowManager.removeView(it) } catch (e: Exception) {}
                gimmickManagerView = null
            }
        }
    }

    private fun startForegroundServiceInternal() {
        val channelId = "OOXOO_Channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "OOXOO Helper", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OOXOO 헬퍼")
            .setContentText("🔄 자동 스캔 중... (1초 간격)")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    // 🔥 오버레이 뷰
    inner class OverlayView(context: Context) : View(context) {
        private var positions = listOf<Pair<Int, Int>>()
        private val circlePaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL; alpha = 180 }
        private val borderPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val textPaint = Paint().apply { color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val gridPaint = Paint().apply { color = Color.parseColor("#80FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val cornerPaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL }
        private val cornerStrokePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val activeCornerPaint = Paint().apply { color = Color.parseColor("#FF00FF"); style = Paint.Style.FILL }
        private val cornerNamePaint = Paint().apply { color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER }

        fun updatePositions(newPositions: List<Pair<Int, Int>>) {
            this.positions = newPositions
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (ptTL.x < 0 || ptTR.x < 0 || ptBL.x < 0 || ptBR.x < 0) return

            if (isGridVisible) {
                for (i in 0..cols) {
                    val ratio = i.toFloat() / cols
                    val topX = (1 - ratio) * ptTL.x + ratio * ptTR.x
                    val topY = (1 - ratio) * ptTL.y + ratio * ptTR.y
                    val botX = (1 - ratio) * ptBL.x + ratio * ptBR.x
                    val botY = (1 - ratio) * ptBL.y + ratio * ptBR.y
                    canvas.drawLine(topX, topY, botX, botY, gridPaint)
                }
                for (j in 0..rows) {
                    val ratio = j.toFloat() / rows
                    val leftX = (1 - ratio) * ptTL.x + ratio * ptBL.x
                    val leftY = (1 - ratio) * ptTL.y + ratio * ptBL.y
                    val rightX = (1 - ratio) * ptTR.x + ratio * ptBR.x
                    val rightY = (1 - ratio) * ptTR.y + ratio * ptBR.y
                    canvas.drawLine(leftX, leftY, rightX, rightY, gridPaint)
                }
            }

            if (isCalibrationMode) {
                val corners = listOf(ptTL, ptTR, ptBL, ptBR)
                val names = listOf("좌상", "우상", "좌하", "우하")
                corners.forEachIndexed { index, corner ->
                    val paint = if (index == selectedCorner) activeCornerPaint else cornerPaint
                    canvas.drawCircle(corner.x, corner.y, 35f, paint)
                    canvas.drawCircle(corner.x, corner.y, 35f, cornerStrokePaint)
                    canvas.drawText(names[index], corner.x, corner.y - 45f, cornerNamePaint)
                }
            }

            for ((row, col) in positions) {
                val u = (col + 0.5f) / cols
                val v = (row + 0.5f) / rows
                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y
                val cx = (1 - v) * topX + v * bottomX
                val cy = (1 - v) * topY + v * bottomY
                canvas.drawCircle(cx, cy, 40f, circlePaint)
                canvas.drawCircle(cx, cy, 40f, borderPaint)
                canvas.drawText("X", cx, cy + 10f, textPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 🔥 기믹 따기 모드 우선 처리
            if (isImageGrabberMode && !isGrabberProcessing && event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x; val y = event.y
                var minDist = Float.MAX_VALUE; var targetRow = -1; var targetCol = -1
                for (r in 0 until rows) for (c in 0 until cols) {
                    val u = (c + 0.5f) / cols; val v = (r + 0.5f) / rows
                    val topX = (1 - u) * ptTL.x + u * ptTR.x
                    val topY = (1 - u) * ptTL.y + u * ptTR.y
                    val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                    val bottomY = (1 - u) * ptBL.y + u * ptBR.y
                    val cx = (1 - v) * topX + v * bottomX
                    val cy = (1 - v) * topY + v * bottomY
                    val dist = Math.hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                    if (dist < minDist) { minDist = dist; targetRow = r; targetCol = c }
                }
                if (minDist < 150f) {
                    AppLogger.d("📷 기믹 따기: row=$targetRow, col=$targetCol")
                    isGrabberProcessing = true
                    captureCellForGimmick(targetRow, targetCol)
                    return true
                }
                Toast.makeText(context, "⚠️ 셀을 정확히 터치하세요", Toast.LENGTH_SHORT).show()
                return true
            }

            // 보정 모드
            if (isCalibrationMode) {
                val x = event.x; val y = event.y
                val corners = listOf(ptTL, ptTR, ptBL, ptBR)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        var minDist = Float.MAX_VALUE; var bestIdx = -1
                        corners.forEachIndexed { idx, corner ->
                            val dist = Math.hypot((x - corner.x).toDouble(), (y - corner.y).toDouble()).toFloat()
                            if (dist < minDist && dist < 120f) { minDist = dist; bestIdx = idx }
                        }
                        if (bestIdx != -1) {
                            selectedCorner = bestIdx
                            draggedCorner = bestIdx
                            isDragging = true
                            dragStartX = x
                            dragStartY = y
                            refreshControlUI()
                            invalidate()
                            return true
                        }
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging && draggedCorner != -1) {
                            val corner = corners[draggedCorner]
                            val dx = x - dragStartX; val dy = y - dragStartY
                            corner.x += dx; corner.y += dy
                            val metrics = resources.displayMetrics
                            corner.x = corner.x.coerceIn(0f, metrics.widthPixels.toFloat())
                            corner.y = corner.y.coerceIn(0f, metrics.heightPixels.toFloat())
                            dragStartX = x; dragStartY = y
                            invalidate()
                            return true
                        }
                        return false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) { isDragging = false; draggedCorner = -1; return true }
                        return false
                    }
                }
                return false
            }

            return false
        }
    }

    private fun createOverlayWindow() {
        mainHandler.post {
            overlayView = OverlayView(applicationContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
            windowManager.addView(overlayView, params)
        }
    }

    // 🔥 꾹 누르면 연속 동작
    // 🔥 v28: 정답 확인 (긍정 피드백 → 최강 seed)
    private fun confirmCorrect() {
        val feat = currentFeature
        if (feat == null || feat.size != com.example.helper.util.GridFeature.DIM) {
            Toast.makeText(applicationContext, "⚠️ 화면 캡처 대기 중", Toast.LENGTH_SHORT).show()
            return
        }
        val posArr = floatArrayOf(ptTL.x, ptTL.y, ptTR.x, ptTR.y, ptBL.x, ptBL.y, ptBR.x, ptBR.y)
        val (removedCount, removedLabels) = com.example.helper.util.GridSeedDB.addManualCorrection(
            applicationContext, feat, rows, cols, currentBoardCrop, posArr
        )
        if (removedCount > 0) {
            AppLogger.d("✅ 정답 강화: ${rows}x${cols} | 오답 정리: ${removedLabels.joinToString(", ")}")
        } else {
            AppLogger.d("✅ 정답 확인: ${rows}x${cols} (총 ${com.example.helper.util.GridSeedDB.size(applicationContext)}개)")
        }
        Toast.makeText(applicationContext, "✅ 정답 저장: ${rows}x${cols}", Toast.LENGTH_SHORT).show()
    }

    private fun setAutoRepeatListener(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var runnable: Runnable? = null

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacksAndMessages(null)
                    runnable = object : Runnable {
                        override fun run() {
                            action()
                            handler.postDelayed(this, 80)
                        }
                    }
                    action()
                    handler.postDelayed(runnable!!, 200)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    runnable = null
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun createControlWindow() {
        mainHandler.post {
            val context = applicationContext
            floatParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 5; y = 5
            }

            controlView = object : LinearLayout(context) {
                private var initialX = 0; private var initialY = 0
                private var initialTouchX = 0f; private var initialTouchY = 0f
                private val touchSlop = 15f
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    super.onLayout(changed, l, t, r, b)
                }

                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = floatParams!!.x; initialY = floatParams!!.y
                            initialTouchX = ev.rawX; initialTouchY = ev.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(ev.rawX - initialTouchX); val dy = abs(ev.rawY - initialTouchY)
                            if (dx > touchSlop || dy > touchSlop) return true
                        }
                    }
                    return super.onInterceptTouchEvent(ev)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_MOVE -> {
                            floatParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                            floatParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(this, floatParams)
                            return true
                        }
                    }
                    return super.onTouchEvent(event)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#DD111111"))
                setPadding(0, 0, 0, 0)
            }

            refreshControlUI()
            windowManager.addView(controlView, floatParams)
        }
    }

    // 🔥 UI 새로고침 (전체 기능 포함)
    private fun refreshControlUI() {
        val view = controlView ?: return
        view.removeAllViews()
        val context = applicationContext

        // =========================================================
        // 보정 모드 전용 초미니 패널
        // =========================================================
        if (isCalibrationMode) {
            TextView(context).apply {
                text = "📐 코너 드래그 또는 방향키"
                setTextColor(Color.YELLOW)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, 2, 0, 2)
            }.also { view.addView(it) }

            val cornerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 2, 0, 2)
            }
            val cornerNames = listOf("좌상", "우상", "좌하", "우하")
            val radioGroup = RadioGroup(context).apply {
                orientation = RadioGroup.HORIZONTAL
                cornerNames.forEachIndexed { idx, name ->
                    val rb = RadioButton(context).apply {
                        text = name
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        id = idx
                        isChecked = (idx == selectedCorner)
                        setOnClickListener {
                            selectedCorner = idx
                            overlayView?.invalidate()
                            refreshControlUI()
                        }
                    }
                    addView(rb)
                }
            }
            cornerLayout.addView(radioGroup)
            view.addView(cornerLayout)

            val dpadLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 2, 0, 2)
            }
            val rowUp = LinearLayout(context).apply { gravity = Gravity.CENTER }
            Button(context).apply {
                text = "▲"; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(32))
                setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE)
                setAutoRepeatListener(this) { moveCorner(0f, -5f) }
            }.also { rowUp.addView(it) }
            dpadLayout.addView(rowUp)

            val rowMid = LinearLayout(context).apply { gravity = Gravity.CENTER }
            Button(context).apply {
                text = "◀"; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(32))
                setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE)
                setAutoRepeatListener(this) { moveCorner(-5f, 0f) }
            }.also { rowMid.addView(it) }
            View(context).apply { layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(32)) }.also { rowMid.addView(it) }
            Button(context).apply {
                text = "▶"; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(32))
                setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE)
                setAutoRepeatListener(this) { moveCorner(5f, 0f) }
            }.also { rowMid.addView(it) }
            dpadLayout.addView(rowMid)

            val rowDown = LinearLayout(context).apply { gravity = Gravity.CENTER }
            Button(context).apply {
                text = "▼"; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(32))
                setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE)
                setAutoRepeatListener(this) { moveCorner(0f, 5f) }
            }.also { rowDown.addView(it) }
            dpadLayout.addView(rowDown)
            view.addView(dpadLayout)

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 2, 0, 2)
            }

            Button(context).apply {
                text = "✅ 완료"
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply { setMargins(2, 0, 2, 0) }
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isCalibrationMode = false
                    overlayView?.let {
                        val p = it.layoutParams as WindowManager.LayoutParams
                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        windowManager.updateViewLayout(it, p)
                    }
                    // 🔥 v42: 보정 완료 시 무조건 수동 고정
                    isAutoDetectEnabled = false
                    isScanning = false
                    // v51: 사용자 명시적 액션 → seed 저장
                    saveSeedExplicitly()
                    savePreferences()
                    AppLogger.d("💾 보정 완료 → 수동 고정: ${rows}x${cols} (auto OFF)")
                    Toast.makeText(context, "💾 보정 완료 (수동 고정)", Toast.LENGTH_SHORT).show()
                    refreshControlUI()
                    overlayView?.invalidate()
                }
            }.also { btnRow.addView(it) }

            Button(context).apply {
                text = "🎯 자동"
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply { setMargins(2, 0, 2, 0) }
                setBackgroundColor(Color.parseColor("#FF6200EE"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    Toast.makeText(context, "🔍 재탐색 중...", Toast.LENGTH_SHORT).show()
                    performAutoDetectOnly()
                }
            }.also { btnRow.addView(it) }

            Button(context).apply {
                text = "🔄 초기화"
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply { setMargins(2, 0, 2, 0) }
                setBackgroundColor(Color.parseColor("#FF8C00"))
                setTextColor(Color.WHITE)
                setOnClickListener { resetCorners() }
            }.also { btnRow.addView(it) }

            view.addView(btnRow)

            Button(context).apply {
                text = "❌ 취소"
                textSize = 10f
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isCalibrationMode = false
                    overlayView?.let {
                        val p = it.layoutParams as WindowManager.LayoutParams
                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        windowManager.updateViewLayout(it, p)
                    }
                    loadPreferences()
                    refreshControlUI()
                    overlayView?.invalidate()
                    Toast.makeText(context, "보정 취소됨", Toast.LENGTH_SHORT).show()
                }
            }.also { view.addView(it) }

            floatParams?.let { params ->
                try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {}
            }
            return
        }

        // =========================================================
        // 일반 모드 (간이/확장)
        // =========================================================
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(18)  // v48: 높이 축소
            )
        }
        TextView(context).apply {
            text = if (isCompactMode) "" else "🎯 OOXOO 자동 감지기"
            setTextColor(Color.WHITE)
            textSize = if (isCompactMode) 8f else 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { topRow.addView(it) }

        Button(context).apply {
            text = if (isCompactMode) "▼" else "▲"
            textSize = 7f
            setPadding(2, 0, 2, 0)
            setBackgroundColor(Color.parseColor("#444444"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isCompactMode = !isCompactMode
                refreshControlUI()
            }
        }.also { topRow.addView(it) }
        view.addView(topRow)

        val statusText = if (isAutoScanEnabled) "🔄 자동 ON (1초)" else "⏸️ OFF"
        TextView(context).apply {
            run {
                val seedCount = GridSeedDB.size(context)
                val manualCount = GridSeedDB.loadAll(context).count { it.manual }
                "$statusText | ${rows}x${cols} | 🧠 ${seedCount}개(👤${manualCount})"
            }
            setTextColor(if (isAutoScanEnabled) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
            textSize = 11f
            setPadding(0, 5, 0, 5)
        }.also { view.addView(it) }

        // 간이 모드
        if (isCompactMode) {
            // 🔥 v40: 초콤팩트 - 2행 (크기조정 + 액션)
            // Row 1: [◀] [행] [▶] [◀] [열] [▶]
            // Row 2: [📸] [✅] [⚙️] [❌]
            view.setPadding(0, 0, 0, 0)

            // Row 1: 크기 조정 (한 줄)
            val sizeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(24)  // v48: 높이 축소
                )
            }

            fun tinyBtn(label: String, action: () -> Unit): Button {
                return Button(context).apply {
                    text = label
                    textSize = 10f
                    setPadding(2, 2, 2, 2)
                    setBackgroundColor(Color.parseColor("#333333"))
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(30), dpToPx(26))
                    setOnClickListener { action() }
                }
            }

            sizeRow.addView(tinyBtn("◀") { if (rows > 3) { rows--; isAutoDetectEnabled = false; savePreferences(); refreshControlUI() } })
            sizeRow.addView(TextView(context).apply {
                text = "$rows"
                setTextColor(Color.YELLOW); textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(30))
            })
            sizeRow.addView(tinyBtn("▶") { if (rows < 20) { rows++; isAutoDetectEnabled = false; savePreferences(); refreshControlUI() } })
            sizeRow.addView(tinyBtn("◀") { if (cols > 3) { cols--; isAutoDetectEnabled = false; savePreferences(); refreshControlUI() } })
            sizeRow.addView(TextView(context).apply {
                text = "$cols"
                setTextColor(Color.CYAN); textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(30))
            })
            sizeRow.addView(tinyBtn("▶") { if (cols < 20) { cols++; isAutoDetectEnabled = false; savePreferences(); refreshControlUI() } })
            view.addView(sizeRow)

            // Row 2: 액션 (입력/정답/종료)
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(24)  // v48: 높이 축소
                )
            }

            fun actionBtn(label: String, color: Int, action: () -> Unit): Button {
                return Button(context).apply {
                    text = label
                    textSize = 10f
                    setPadding(2, 2, 2, 2)
                    setBackgroundColor(color)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(26)).apply { setMargins(1, 0, 1, 0) }
                    setOnClickListener { action() }
                }
            }

            actionRow.addView(actionBtn("입력", Color.parseColor("#0288D1")) { showLabelingDialog() })
            actionRow.addView(actionBtn("확정", Color.parseColor("#4CAF50")) { confirmCorrect() })
            actionRow.addView(actionBtn(if (isAutoDetectEnabled) "자동" else "수동", Color.parseColor("#555555")) {
                isAutoDetectEnabled = !isAutoDetectEnabled
                savePreferences()
                refreshControlUI()
                Toast.makeText(context, if (isAutoDetectEnabled) "자동격자 ON" else "자동격자 OFF", Toast.LENGTH_SHORT).show()
            })
            actionRow.addView(actionBtn("새판", Color.parseColor("#FF6F00")) {
                // 새 판 시작: auto 재개
                isAutoDetectEnabled = true
                savePreferences()
                refreshControlUI()
                AppLogger.d("NEW_GAME: 새 판 시작 (auto 재개)")
                Toast.makeText(context, "새 판 시작 (자동 검출 재개)", Toast.LENGTH_SHORT).show()
            })
            actionRow.addView(actionBtn("종료", Color.RED) { stopSelf() })
            view.addView(actionRow)

            floatParams?.let { params -> try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {} }
            return
        }

        // --- 확장 모드 (전체 기능) ---
        // 1. 크기 조정
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 5, 0, 5)
        }
        TextView(context).apply {
            text = "${rows}x${cols}"
            setTextColor(Color.YELLOW)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { sizeRow.addView(it) }
        listOf("행-" to { if (rows > 5) rows-- },
            "행+" to { if (rows < 15) rows++ },
            "열-" to { if (cols > 5) cols-- },
            "열+" to { if (cols < 15) cols++ }
        ).forEach { (text, action) ->
            Button(context).apply {
                this.text = text
                textSize = 11f
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener { action(); savePreferences(); refreshControlUI() }
            }.also { sizeRow.addView(it) }
        }
        view.addView(sizeRow)

        // 2. 자동 스캔 토글
        Button(context).apply {
            text = if (isAutoScanEnabled) "⏸️ 자동 중지" else "▶️ 자동 시작"
            setBackgroundColor(if (isAutoScanEnabled) Color.parseColor("#D32F2F") else Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isAutoScanEnabled = !isAutoScanEnabled
                if (isAutoScanEnabled) { startAutoScan(); Toast.makeText(context, "🔄 재개", Toast.LENGTH_SHORT).show() }
                else { stopAutoScan(); Toast.makeText(context, "⏸️ 중지", Toast.LENGTH_SHORT).show() }
                refreshControlUI()
            }
        }.also { view.addView(it) }

        // 3. 자동 격자 감지 토글
        Button(context).apply {
            text = if (isAutoDetectEnabled) "📐 자동격자: ON" else "📐 자동격자: OFF"
            setBackgroundColor(if (isAutoDetectEnabled) Color.parseColor("#007F0E") else Color.parseColor("#444444"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isAutoDetectEnabled = !isAutoDetectEnabled
                Toast.makeText(context, if (isAutoDetectEnabled) "자동 격자 감지 ON" else "자동 격자 감지 OFF (수동 유지)", Toast.LENGTH_SHORT).show()
                refreshControlUI()
            }
        }.also { view.addView(it) }

        // 4. 격자 보기 토글
        Button(context).apply {
            text = if (isGridVisible) "🌐 격자: 보임" else "🌐 격자: 숨김"
            setBackgroundColor(if (isGridVisible) Color.parseColor("#007F0E") else Color.parseColor("#444444"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isGridVisible = !isGridVisible
                overlayView?.invalidate()
                refreshControlUI()
            }
        }.also { view.addView(it) }

        // 5. 격자 보정 (진입)
        Button(context).apply {
            text = "📐 격자 보정"
            setBackgroundColor(Color.parseColor("#444444"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isCalibrationMode = true
                    AppLogger.d("🎯 보정 버튼 클릭")
                overlayView?.let {
                    val p = it.layoutParams as WindowManager.LayoutParams
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager.updateViewLayout(it, p)
                }
                if (isImageGrabberMode) { isImageGrabberMode = false; isGrabberProcessing = false }
                Toast.makeText(context, "📐 보정 모드 진입", Toast.LENGTH_LONG).show()
                refreshControlUI()
                overlayView?.invalidate()
            }
        }.also { view.addView(it) }

        // 6. 기믹 따기
        Button(context).apply {
            text = if (isImageGrabberMode) "❌ 기믹 취소" else "📷 기믹 따기"
            setBackgroundColor(if (isImageGrabberMode) Color.parseColor("#D32F2F") else Color.parseColor("#5A0063"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isImageGrabberMode) {
                    isImageGrabberMode = false
                    overlayView?.let {
                        val p = it.layoutParams as WindowManager.LayoutParams
                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        windowManager.updateViewLayout(it, p)
                    }
                    Toast.makeText(context, "기믹 따기 종료", Toast.LENGTH_SHORT).show()
                } else {
                    isImageGrabberMode = true
                    overlayView?.let {
                        val p = it.layoutParams as WindowManager.LayoutParams
                        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        windowManager.updateViewLayout(it, p)
                    }
                    Toast.makeText(context, "📷 셀을 터치하세요", Toast.LENGTH_SHORT).show()
                }
                refreshControlUI()
            }
        }.also { view.addView(it) }

        // 7. 기믹 목록
        Button(context).apply {
            text = "📋 기믹 목록 (${dynamicTemplateFiles.size})"
            setBackgroundColor(Color.parseColor("#00574B"))
            setTextColor(Color.WHITE)
            setOnClickListener { showGimmickManager() }
        }.also { view.addView(it) }

        // 8. 종료
        Button(context).apply {
            text = "🔄 업데이트 확인"
            setBackgroundColor(Color.parseColor("#0288D1"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                Toast.makeText(context, "🔄 확인 중...", Toast.LENGTH_SHORT).show()
                UpdateChecker.checkAndUpdate(context) { msg ->
                    mainHandler.post {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.also { view.addView(it) }

        // 🔥 v31: 시드 초기화
        Button(context).apply {
            text = "🗑️ 학습 시드 초기화 (🧠${com.example.helper.util.GridSeedDB.size(context)})"
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                // v53: AlertDialog 제거 → 즉시 삭제 (Service context 크래시 회피)
                com.example.helper.util.GridSeedDB.clear(context)
                AppLogger.d("🗑️ 시드 초기화 완료")
                Toast.makeText(context, "🗑️ 시드 초기화 완료", Toast.LENGTH_SHORT).show()
                refreshControlUI()
            }
        }.also { view.addView(it) }

        // 🔥 v31: ML 데이터셋 내보내기
        Button(context).apply {
            text = "📤 ML 데이터셋 내보내기"
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val path = com.example.helper.util.GridSeedDB.exportDataset(context)
                if (path != null) {
                    Toast.makeText(context, "📤 저장: $path", Toast.LENGTH_LONG).show()
                    AppLogger.d("📤 ML 데이터셋: $path")
                } else {
                    Toast.makeText(context, "❌ 내보낼 데이터 없음", Toast.LENGTH_SHORT).show()
                }
            }
        }.also { view.addView(it) }

        Button(context).apply {
            text = "📋 로그 보기 (${AppLogger.getAll().size})"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener { showLogDialog() }
        }.also { view.addView(it) }

        Button(context).apply {
            text = "❌ 종료"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener { stopSelf() }
        }.also { view.addView(it) }

        floatParams?.let { params ->
            try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {}
        }
    }

    private fun moveCorner(dx: Float, dy: Float) {
        val corner = when (selectedCorner) {
            0 -> ptTL; 1 -> ptTR; 2 -> ptBL; 3 -> ptBR
            else -> return
        }
        corner.x += dx; corner.y += dy
        val metrics = resources.displayMetrics
        corner.x = corner.x.coerceIn(0f, metrics.widthPixels.toFloat())
        corner.y = corner.y.coerceIn(0f, metrics.heightPixels.toFloat())
        overlayView?.invalidate()
    }

    private fun resetCorners() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat(); val h = metrics.heightPixels.toFloat()
        ptTL.set(w * 0.15f, h * 0.18f)
        ptTR.set(w * 0.85f, h * 0.18f)
        ptBL.set(w * 0.15f, h * 0.82f)
        ptBR.set(w * 0.85f, h * 0.82f)
        overlayView?.invalidate()
        Toast.makeText(applicationContext, "초기화 완료", Toast.LENGTH_SHORT).show()
    }

    private fun performAutoDetectOnly() {
        if (!isCapturing) {
            Toast.makeText(applicationContext, "캡처가 활성화되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }
        backgroundHandler?.post {
            val reader = imageReader ?: return@post
            var image = reader.acquireLatestImage()
            if (image == null) {
                try { Thread.sleep(50) } catch (e: Exception) {}
                image = reader.acquireNextImage()
            }
            if (image == null) {
                mainHandler.post { Toast.makeText(applicationContext, "❌ 이미지 획득 실패", Toast.LENGTH_SHORT).show() }
                return@post
            }
            try {
                val metrics = resources.displayMetrics
                val planes = image.planes; val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
                val w = metrics.widthPixels; val h = metrics.heightPixels
                val rowPadding = rowStride - pixelStride * w
                val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                val detected = autoDetectBoard(bitmap)
                mainHandler.post {
                    if (detected) {
                        Toast.makeText(applicationContext, "✅ 자동 감지 성공: ${rows}x${cols}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "❌ 자동 감지 실패", Toast.LENGTH_SHORT).show()
                    }
                    overlayView?.invalidate()
                    refreshControlUI()
                }
                bitmap.recycle()
            } catch (e: Exception) { AppLogger.e("자동 감지 오류", e) }
            finally { image.close() }
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // 🔥 자동 보드 인식
    private fun autoDetectBoard(bitmap: Bitmap): Boolean {
        if (!isOpenCVInitialized) return false
        val width = bitmap.width; val height = bitmap.height
        val centerX = width / 2f; val centerY = height / 2f
        val searchRadius = minOf(width, height) * 0.48f

        val src = Mat(); Utils.bitmapToMat(bitmap, src)
        val gray = Mat(); Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat(); Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat(); Imgproc.Canny(blurred, edges, 25.0, 80.0)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var bestContour: MatOfPoint? = null
        var bestArea = 0.0
        for (contour in contours) {
            val moments = Imgproc.moments(contour)
            if (moments.m00 == 0.0) continue
            val cx = (moments.m10 / moments.m00).toFloat()
            val cy = (moments.m01 / moments.m00).toFloat()
            val dist = Math.hypot((cx - centerX).toDouble(), (cy - centerY).toDouble())
            if (dist > searchRadius) continue
            val area = Imgproc.contourArea(contour)
            if (area > bestArea && area > 25000) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, peri * 0.025, true)
                if (approx.toArray().size == 4) {
                    bestContour = contour; bestArea = area
                }
            }
        }

        if (bestContour == null) {
            src.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
            return false
        }

        val points = bestContour.toArray()
        val sortedPoints = sortCorners(points)
        ptTL.set(sortedPoints[0].x.toFloat(), sortedPoints[0].y.toFloat())
        ptTR.set(sortedPoints[1].x.toFloat(), sortedPoints[1].y.toFloat())
        ptBL.set(sortedPoints[2].x.toFloat(), sortedPoints[2].y.toFloat())
        ptBR.set(sortedPoints[3].x.toFloat(), sortedPoints[3].y.toFloat())

        // 🔥 격자선 프로젝션 방식으로 크기 검출
        val detected = detectGridDimensions(bitmap)
        if (detected != null) {
            rows = detected.first
            cols = detected.second
            savePreferences()
            AppLogger.d("✅ 자동 인식 성공: ${rows}x${cols}")
            mainHandler.post {
                Toast.makeText(applicationContext, "✅ 판 크기: ${rows}행 x ${cols}열", Toast.LENGTH_SHORT).show()
            }
            src.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
            return true
        } else {
            AppLogger.d("⚠️ 격자 크기 검출 실패, 기존 값 유지: ${rows}x${cols}")
            src.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
            return false
        }
    }


    // 🔥🔥🔥 [신규] 격자선 프로젝션으로 정확한 행/열 개수 검출
    // 🔥🔥🔥 v10: 파워업 바 제외 (maxY=0.68) + 넉넉한 gap + floor rows
    private fun detectGridDimensions(bitmap: Bitmap): Pair<Int, Int>? {
        var src: Mat? = null
        var rgb: Mat? = null
        var hsv: Mat? = null
        var tileMask: Mat? = null
        try {
            src = Mat(); Utils.bitmapToMat(bitmap, src)
            rgb = Mat(); Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
            hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)

            // 🔥 v11: 진한 빨강(자물쇠) + 갈색/탠색(나무상자) 추가
            val m1 = Mat(); Core.inRange(hsv, Scalar(0.0, 90.0, 90.0), Scalar(12.0, 255.0, 255.0), m1)      // 빨강
            val m2 = Mat(); Core.inRange(hsv, Scalar(13.0, 90.0, 120.0), Scalar(35.0, 255.0, 255.0), m2)    // 노랑
            val m3 = Mat(); Core.inRange(hsv, Scalar(36.0, 90.0, 90.0), Scalar(85.0, 255.0, 255.0), m3)     // 초록
            val m4 = Mat(); Core.inRange(hsv, Scalar(86.0, 90.0, 90.0), Scalar(135.0, 255.0, 255.0), m4)    // 파랑
            val m5 = Mat(); Core.inRange(hsv, Scalar(136.0, 90.0, 90.0), Scalar(180.0, 255.0, 255.0), m5)   // 보라
            // 🔥 추가 1: 진한 빨강/마룬 (자물쇠) - 낮은 밝기 허용
            val m6 = Mat(); Core.inRange(hsv, Scalar(0.0, 60.0, 40.0), Scalar(15.0, 255.0, 140.0), m6)
            // 🔥 추가 2: 갈색/탠색 (나무 상자) - hue 10~25
            val m7 = Mat(); Core.inRange(hsv, Scalar(10.0, 50.0, 100.0), Scalar(28.0, 255.0, 255.0), m7)
            // 🔥 추가 3: 밝은 살구색/베이지 (상자 하이라이트)
            val m8 = Mat(); Core.inRange(hsv, Scalar(15.0, 40.0, 180.0), Scalar(30.0, 180.0, 255.0), m8)

            tileMask = Mat()
            Core.bitwise_or(m1, m2, tileMask)
            Core.bitwise_or(tileMask, m3, tileMask)
            Core.bitwise_or(tileMask, m4, tileMask)
            Core.bitwise_or(tileMask, m5, tileMask)
            Core.bitwise_or(tileMask, m6, tileMask)
            Core.bitwise_or(tileMask, m7, tileMask)
            Core.bitwise_or(tileMask, m8, tileMask)
            m1.release(); m2.release(); m3.release(); m4.release(); m5.release()
            m6.release(); m7.release(); m8.release()

            val w = bitmap.width
            val h = bitmap.height

            // 🔥 Y 검색 범위: 상단 15% ~ 68% (파워업 바 제외)
            val minY = (h * 0.25).toInt()  // 🔥 v13: 상단 UI 제외
            val maxY = (h * 0.75).toInt()  // 🔥 v13: 하단 UI 제외

            // Y 프로젝션
            val rowProj = IntArray(h)
            for (y in 0 until h) {
                var count = 0
                for (x in 0 until w step 3) {
                    if (tileMask.get(y, x)[0] > 128.0) count++
                }
                rowProj[y] = count * 3
            }
            val rowSmooth = IntArray(h)
            for (y in 5 until h - 5) {
                var s = 0
                for (k in -5..5) s += rowProj[y+k]
                rowSmooth[y] = s / 11
            }

            // maxRow는 검색 범위 내에서만
            var maxRow = 0
            for (y in minY until maxY) if (rowSmooth[y] > maxRow) maxRow = rowSmooth[y]
            if (maxRow < 30) { AppLogger.d("rowProj 약함: $maxRow"); return null }

            val rowThr = maxRow * 0.50  // 🔥 v13: 엄격

            // first ~ last dense
            var firstDense = -1
            var lastDense = -1
            var gapCount = 0
            val maxGap = 100  // ← 넉넉하게

            for (y in minY until maxY) {
                if (rowSmooth[y] >= rowThr) {
                    if (firstDense == -1) firstDense = y
                    lastDense = y
                    gapCount = 0
                } else {
                    gapCount++
                    if (firstDense != -1 && gapCount > maxGap) break
                }
            }

            if (firstDense == -1 || lastDense - firstDense < 400) {
                AppLogger.d("Y범위 실패: first=$firstDense, last=$lastDense")
                return null
            }

            val boardTop = firstDense
            val boardBottom = lastDense
            val boardH = boardBottom - boardTop
            AppLogger.d("Y범위: ${boardTop}~${boardBottom} (H=$boardH)")

            // X 프로젝션
            val colProj = IntArray(w)
            for (x in 0 until w) {
                var count = 0
                for (y in boardTop until boardBottom step 3) {
                    if (tileMask.get(y, x)[0] > 128.0) count++
                }
                colProj[x] = count * 3
            }
            val colSmooth = IntArray(w)
            for (x in 5 until w - 5) {
                var s = 0
                for (k in -5..5) s += colProj[x+k]
                colSmooth[x] = s / 11
            }

            var maxCol = 0
            for (x in 0 until w) if (colSmooth[x] > maxCol) maxCol = colSmooth[x]
            if (maxCol < 30) { AppLogger.d("colProj 약함: $maxCol"); return null }

            val colThr = maxCol * 0.50  // 🔥 v13: 엄격
            var boardLeft = -1
            var boardRight = -1
            var gapCntX = 0
            for (x in 0 until w) {
                if (colSmooth[x] >= colThr) {
                    if (boardLeft == -1) boardLeft = x
                    boardRight = x
                    gapCntX = 0
                } else {
                    gapCntX++
                    if (boardLeft != -1 && gapCntX > 60) break
                }
            }

            if (boardLeft == -1 || boardRight - boardLeft < 400) {
                AppLogger.d("X범위 실패")
                return null
            }

            val boardW = boardRight - boardLeft
            AppLogger.d("보드(proj): ${boardW}x${boardH} @ (${boardLeft},${boardTop}) [확장+${(boardW*0.03).toInt()}px]")

            // 🔥 v45: 격자 좌우로 살짝 확장 (안쪽 잡힘 해결)
            val expandW = (boardW * 0.03f).toInt()  // 좌우 3%씩
            ptTL.set((boardLeft - expandW).toFloat().coerceAtLeast(0f), boardTop.toFloat())
            ptTR.set((boardRight + expandW).toFloat(), boardTop.toFloat())
            ptBL.set((boardLeft - expandW).toFloat().coerceAtLeast(0f), boardBottom.toFloat())
            ptBR.set((boardRight + expandW).toFloat(), boardBottom.toFloat())

            // 🔥🔥 v12: Royal Match 타일 특성 반영 (세로가 ~10% 김)
            // rowsRaw가 10.0~12.5면 11로 스냅 (Royal Match 11행 압도적)
            val COLS = 9
            val tileW = boardW.toFloat() / COLS
            val rowsRaw = boardH.toFloat() / tileW

            var rows = when {
                rowsRaw < 9.3f -> 9
                rowsRaw < 10.5f -> 10   // 🔥 v15: 9.3~10.5 → 10행 (이번 케이스)
                rowsRaw < 13.0f -> 11   // 10.5~13.0 → 11행
                rowsRaw < 14.5f -> 13
                else -> Math.round(rowsRaw).toInt()
            }

            AppLogger.d("계산: tileW=${tileW.toInt()}, rowsRaw=${"%.2f".format(rowsRaw)} → rows=$rows")

            if (rows < 9) rows = 9
            if (rows > 13) rows = 13

            val tileH = boardH.toFloat() / rows
            val err = Math.abs(tileW - tileH) / Math.max(tileW, tileH)
            AppLogger.d("최종: ${rows}행 x ${COLS}열, tile=${tileW.toInt()}x${tileH.toInt()}, 오차=${"%.3f".format(err)}")

            AppLogger.d("OK 격자: ${rows}행 x ${COLS}열")
            return Pair(rows, COLS)
        } catch (e: Exception) {
            AppLogger.e("격자 검출 오류", e)
            return null
        } finally {
            try { src?.release(); rgb?.release(); hsv?.release(); tileMask?.release() } catch (ex: Exception) {}
        }
    }

    private fun smoothArray(arr: DoubleArray, kernelSize: Int): DoubleArray {
        val result = DoubleArray(arr.size)
        val half = kernelSize / 2
        for (i in arr.indices) {
            var sum = 0.0; var count = 0
            for (j in -half..half) {
                val idx = i + j
                if (idx in arr.indices) { sum += arr[idx]; count++ }
            }
            result[i] = sum / count
        }
        return result
    }

    // 🔥 강한 피크만 검출 (프로미넌스 검사)
    private fun findPeaksV3(proj: DoubleArray): List<Int> {
        if (proj.isEmpty()) return emptyList()
        val maxVal = proj.maxOrNull() ?: 0.0
        if (maxVal < 5.0) return emptyList()

        val threshold = maxVal * 0.45   // 상위 55%만
        val peaks = mutableListOf<Int>()
        var i = 1
        while (i < proj.size - 1) {
            if (proj[i] > threshold && proj[i] >= proj[i-1] && proj[i] >= proj[i+1]) {
                peaks.add(i)
                i += 40   // 최소 간격 40
            } else { i++ }
        }

        // 🔥 프로미넌스 필터: 주변 최소값 대비 30% 이상 높아야 인정
        val filtered = peaks.filter { peak ->
            val window = 30
            val lo = (peak - window).coerceAtLeast(0)
            val hi = (peak + window).coerceAtMost(proj.size - 1)
            val localMin = (lo..hi).minOf { proj[it] }
            proj[peak] > localMin * 1.3
        }
        return filtered
    }

    // 🔥 피크 검출
    private fun findLinePeaks(proj: DoubleArray, len: Int): List<Int> {
        val smoothed = DoubleArray(len)
        for (i in 1 until len - 1) {
            smoothed[i] = (proj[i - 1] + proj[i] + proj[i + 1]) / 3.0
        }
        val maxVal = smoothed.maxOrNull() ?: 0.0
        if (maxVal < 1.0) return emptyList()
        val thr = maxVal * 0.4
        val peaks = mutableListOf<Int>()
        var i = 1
        while (i < len - 1) {
            if (smoothed[i] > thr && smoothed[i] >= smoothed[i - 1] && smoothed[i] >= smoothed[i + 1]) {
                peaks.add(i)
                i += 15
            } else {
                i++
            }
        }
        return peaks
    }

    private fun calculateStdDev(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return Math.sqrt(variance)
    }

    private fun sortCorners(points: Array<org.opencv.core.Point>): List<org.opencv.core.Point> {
        val sorted = points.sortedBy { it.y }
        val top = sorted.take(2).sortedBy { it.x }
        val bottom = sorted.drop(2).sortedBy { it.x }
        return listOf(top[0], top[1], bottom[0], bottom[1])
    }

    // 🔥 스캔 실행
    private fun performScan() {
        // 🔥 v38: 보정 모드/수동 모드면 아예 진입 X
        if (isCalibrationMode || !isAutoDetectEnabled) {
            mainHandler.post {
                val positions = foundPositions
                overlayView?.updatePositions(positions)
                refreshControlUI()
            }
            return
        }
        if (isScanning) return
        isScanning = true
        backgroundHandler?.post {
            // 🔥 v38: 스레드 내 재확인 (레이스 컨디션 방지)
            if (isCalibrationMode || !isAutoDetectEnabled) {
                mainHandler.post { isScanning = false }
                return@post
            }
            val reader = imageReader ?: return@post
            var image = reader.acquireLatestImage()
            if (image == null) {
                try { Thread.sleep(50) } catch (e: Exception) {}
                image = reader.acquireNextImage()
            }
            if (image == null) {
                mainHandler.post { isScanning = false }
                return@post
            }

            try {
                val metrics = resources.displayMetrics
                val planes = image.planes; val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
                val w = metrics.widthPixels; val h = metrics.heightPixels
                val rowPadding = rowStride - pixelStride * w
                val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                // 🔥 v26: full bitmap freeze (라벨링용)
                try { latestFullBitmap?.recycle(); latestFullBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { }

                // 🔥 v22: 프리뷰용 썸네일 저장
                try {
                    latestThumbnail?.recycle()
                    val tw = 500
                    val th = (500f * bitmap.height / bitmap.width).toInt()
                    latestThumbnail = Bitmap.createScaledBitmap(bitmap, tw, th, true)
                } catch (e: Exception) { }

                // 🔥 v23: ML 학습용 보드 크롭 (256x256)
                try {
                    currentBoardCrop?.recycle()
                    val bx0 = ptTL.x.toInt().coerceIn(0, bitmap.width - 10)
                    val by0 = ptTL.y.toInt().coerceIn(0, bitmap.height - 10)
                    val bx1 = ptBR.x.toInt().coerceIn(bx0 + 10, bitmap.width)
                    val by1 = ptBR.y.toInt().coerceIn(by0 + 10, bitmap.height)
                    val bw = bx1 - bx0
                    val bh = by1 - by0
                    if (bw > 50 && bh > 50) {
                        val cropRaw = Bitmap.createBitmap(bitmap, bx0, by0, bw, bh)
                        currentBoardCrop = Bitmap.createScaledBitmap(cropRaw, 256, 256, true)
                        cropRaw.recycle()
                    }
                } catch (e: Exception) { }

                // 🔥 v19: 자동검출 먼저 실행 → k-NN 예측이 있으면 우선 적용
                currentFingerprint = LevelGridMemory.computeFingerprint(bitmap)
                currentFeature = GridFeature.extract(bitmap, ptTL, ptTR, ptBL, ptBR)

                // 🔥 v55: 자동 판바뀜 감지 - manual seed가 있으면 스킵
                val hasManualSeed = GridSeedDB.loadAll(applicationContext).any { it.manual }
                if (!hasManualSeed && lastFingerprintForGameChange.isNotEmpty() && currentFingerprint.isNotEmpty()) {
                    var diff = 0
                    val len = minOf(lastFingerprintForGameChange.length, currentFingerprint.length)
                    for (i in 0 until len) {
                        if (lastFingerprintForGameChange[i] != currentFingerprint[i]) diff++
                    }
                    if (diff > len * 0.3 && !isAutoDetectEnabled && !isCalibrationMode) {
                        AppLogger.d("NEW_GAME_AUTO: 판 바뀜 (diff=$diff/$len) → auto 재개")
                        isAutoDetectEnabled = true
                        savePreferences()
                        mainHandler.post { refreshControlUI() }
                    }
                }
                lastFingerprintForGameChange = currentFingerprint

                // 🔥 v36: 보정 모드 or 수동 모드에서는 auto/k-NN 완전 스킵
                if (isCalibrationMode || !isAutoDetectEnabled) {
                    AppLogger.d("⏸️ 수동/보정 모드 - auto/k-NN 스킵 (${rows}x${cols})")
                    val positions = findOOXOO(bitmap)
                    mainHandler.post {
                        foundPositions.clear(); foundPositions.addAll(positions)
                        overlayView?.updatePositions(positions)
                        refreshControlUI()
                        isScanning = false
                    }
                    bitmap.recycle()
                    return@post
                }

                // 1) 자동검출 (항상 실행, 참고용)
                val beforeRows = rows
                val beforeCols = cols
                if (isAutoDetectEnabled) {
                    autoDetectBoard(bitmap)
                }
                val autoRows = rows
                val autoCols = cols
                val autoChanged = (beforeRows != autoRows || beforeCols != autoCols)

                // 2) k-NN 예측 (학습된 seed가 있으면 우선 적용)
                val feat = currentFeature
                var finalSource = if (isAutoDetectEnabled) "auto" else "manual-lock"
                if (feat != null) {
                    val prediction = GridPredictor.predict(applicationContext, feat)
                    if (prediction != null && prediction.confidence >= 0.75f) {  // 🔥 v36: 0.75 (안정성)
                        rows = prediction.rows
                        cols = prediction.cols
                        finalSource = "k-NN"

                        // 🔥 v69: 수동 모드(auto OFF)면 위치 복원 스킵 (사용자 저장 위치 유지)
                        val pos = prediction.position
                        if (isAutoDetectEnabled && pos != null && pos.size == 8) {
                            ptTL.set(pos[0], pos[1])
                            ptTR.set(pos[2], pos[3])
                            ptBL.set(pos[4], pos[5])
                            ptBR.set(pos[6], pos[7])
                            mainHandler.post {
                                try { overlayView?.invalidate() } catch (e: Exception) {}
                            }
                        }
                        // 🔥 v35: 로그 필터 (값 바뀔 때만)
                        if (rows != lastLoggedRows || cols != lastLoggedCols || finalSource != lastLoggedSource) {
                            val posStr = if (pos != null && pos.size == 8) " +📍" else ""
                            AppLogger.d("🧠 k-NN: ${rows}x${cols} (신뢰도=${"%.2f".format(prediction.confidence)}, 시드=${prediction.seedCount})$posStr | auto=${autoRows}x${autoCols}")
                            lastLoggedRows = rows
                            lastLoggedCols = cols
                            lastLoggedSource = "k-NN"
                        }
                    } else {
                        if (rows != lastLoggedRows || cols != lastLoggedCols || finalSource != "auto") {
                            AppLogger.d("📷 auto: ${rows}x${cols} (시드=${GridSeedDB.size(applicationContext)}개)")
                            lastLoggedRows = rows; lastLoggedCols = cols; lastLoggedSource = "auto"
                        }
                    }
                }

                // 3) 자동검출 결과가 새로 얻어졌고 k-NN 미적용이면 seed 추가 (auto)
                // 🔥 v38: auto seed는 보정/수동 모드 아닐 때만
                // 🔥 v41: auto seed 저장 안 함 (manual만 신뢰)
                // auto가 k-NN 오염시켜서 10x9↔11x9 뒤바뀌는 문제 해결

                val positions = findOOXOO(bitmap)

                mainHandler.post {
                    foundPositions.clear(); foundPositions.addAll(positions)
                    overlayView?.updatePositions(positions)
                    refreshControlUI()
                    isScanning = false
                }
                bitmap.recycle()
            } catch (e: Exception) { AppLogger.e("스캔 오류", e); mainHandler.post { isScanning = false } }
            finally { image.close() }
        }
    }

    // 🔥 OOXOO 탐색 (5칸 정확히)
    private fun findOOXOO(bitmap: Bitmap): List<Pair<Int, Int>> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // v54: rows/cols 유효성 검사
        val safeRows = rows.coerceIn(3, 20)
        val safeCols = cols.coerceIn(3, 20)
        if (safeRows != rows || safeCols != cols) {
            AppLogger.d("findOOXOO: rows/cols 조정 ${rows}x${cols} → ${safeRows}x${safeCols}")
            rows = safeRows
            cols = safeCols
        }
        val colorGrid = Array(rows) { IntArray(cols) }

        for (r in 0 until rows) for (c in 0 until cols) {
            val u = (c + 0.5f) / cols; val v = (r + 0.5f) / rows
            val topX = (1 - u) * ptTL.x + u * ptTR.x
            val topY = (1 - u) * ptTL.y + u * ptTR.y
            val bottomX = (1 - u) * ptBL.x + u * ptBR.x
            val bottomY = (1 - u) * ptBL.y + u * ptBR.y
            val cx = (1 - v) * topX + v * bottomX
            val cy = (1 - v) * topY + v * bottomY
            val ix = cx.toInt().coerceIn(0, width - 1); val iy = cy.toInt().coerceIn(0, height - 1)
            val pixel = pixels[iy * width + ix]

            // 기믹 체크
            if (isOpenCVInitialized && dynamicTemplates.isNotEmpty()) {
                val cellW = (abs(ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(20)
                val cellH = (abs(ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(20)

                val cropSize = minOf(cellW, cellH)
                val half = cropSize / 2
                var startX = (cx - half).toInt().coerceIn(0, width - cropSize)
                var startY = (cy - half).toInt().coerceIn(0, height - cropSize)
                val realSize = minOf(cropSize, width - startX, height - startY).coerceAtLeast(1)

                try {
                    val cellBitmap = Bitmap.createBitmap(bitmap, startX, startY, realSize, realSize)
                    val resizedCell = Bitmap.createScaledBitmap(cellBitmap, 64, 64, true)

                    if (isGimmick(resizedCell)) {
                        colorGrid[r][c] = -1
                        resizedCell.recycle()
                        cellBitmap.recycle()
                        continue
                    }
                    resizedCell.recycle()
                    cellBitmap.recycle()
                } catch (e: Exception) {}
            }

            val hsv = FloatArray(3); Color.colorToHSV(pixel, hsv)
            val hue = hsv[0]; val sat = hsv[1]
            colorGrid[r][c] = when {
                sat < 0.2f -> -1
                hue in 0f..30f -> 1
                hue in 31f..70f -> 2
                hue in 71f..160f -> 3
                hue in 161f..230f -> 4
                hue in 231f..360f -> 5
                else -> -1
            }
        }

        val result = mutableListOf<Pair<Int, Int>>()

        // 🔥 가로 OOXOO (5칸: O O X O O)
        if (cols >= 5) {
            for (r in 0 until rows) {
                for (c in 0 until cols - 4) {
                    val color = colorGrid[r][c]
                    if (color == -1) continue
                    if (colorGrid[r][c] == color &&
                        colorGrid[r][c + 1] == color &&
                        colorGrid[r][c + 2] != color &&
                        colorGrid[r][c + 3] == color &&
                        colorGrid[r][c + 4] == color) {
                        val xCol = c + 2
                        if ((r > 0 && colorGrid[r - 1][xCol] == color) ||
                            (r < rows - 1 && colorGrid[r + 1][xCol] == color)) {
                            result.add(Pair(r, xCol))
                        }
                    }
                }
            }
        }

        // 🔥 세로 OOXOO (5칸: O O X O O)
        if (rows >= 5) {
            for (c in 0 until cols) {
                for (r in 0 until rows - 4) {
                    val color = colorGrid[r][c]
                    if (color == -1) continue
                    if (colorGrid[r][c] == color &&
                        colorGrid[r + 1][c] == color &&
                        colorGrid[r + 2][c] != color &&
                        colorGrid[r + 3][c] == color &&
                        colorGrid[r + 4][c] == color) {
                        val xRow = r + 2
                        if ((c > 0 && colorGrid[xRow][c - 1] == color) ||
                            (c < cols - 1 && colorGrid[xRow][c + 1] == color)) {
                            result.add(Pair(xRow, c))
                        }
                    }
                }
            }
        }

        return result.distinct()
    }

    // 🔥 기믹 인식
    private fun isGimmick(cellBitmap: Bitmap): Boolean {
        if (!isOpenCVInitialized || dynamicTemplates.isEmpty()) return false

        val cellMat = Mat()
        Utils.bitmapToMat(cellBitmap, cellMat)
        Imgproc.cvtColor(cellMat, cellMat, Imgproc.COLOR_RGBA2GRAY)

        val resultMat = Mat()
        var matched = false

        synchronized(dynamicTemplates) {
            for (template in dynamicTemplates) {
                if (cellMat.cols() >= template.cols() && cellMat.rows() >= template.rows()) {
                    Imgproc.matchTemplate(cellMat, template, resultMat, Imgproc.TM_CCOEFF_NORMED)
                    if (Core.minMaxLoc(resultMat).maxVal >= 0.55) {
                        matched = true
                        break
                    }
                }
            }
        }

        cellMat.release()
        resultMat.release()
        return matched
    }

    // 🔥 기믹 캡처
    private fun captureCellForGimmick(row: Int, col: Int) {
        AppLogger.d("📷 기믹 캡처 시작: row=$row, col=$col")
        val reader = imageReader ?: run {
            AppLogger.e("❌ imageReader가 null입니다")
            isGrabberProcessing = false
            return
        }

        backgroundHandler?.post {
            var image = reader.acquireLatestImage()
            if (image == null) {
                try { Thread.sleep(50) } catch (e: Exception) {}
                image = reader.acquireNextImage()
            }
            if (image == null) {
                isGrabberProcessing = false
                mainHandler.post {
                    Toast.makeText(applicationContext, "❌ 이미지 캡처 실패", Toast.LENGTH_SHORT).show()
                }
                return@post
            }

            try {
                val metrics = resources.displayMetrics
                val planes = image.planes; val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
                val w = metrics.widthPixels; val h = metrics.heightPixels
                val rowPadding = rowStride - pixelStride * w
                val fullBitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)

                val u = (col + 0.5f) / cols; val v = (row + 0.5f) / rows
                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y
                val cx = (1 - v) * topX + v * bottomX
                val cy = (1 - v) * topY + v * bottomY

                val cellW = (abs(ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(30)
                val cellH = (abs(ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(30)

                val cropSize = (minOf(cellW, cellH) * 0.8f).toInt().coerceAtLeast(20)
                val half = cropSize / 2
                var startX = (cx - half).toInt().coerceIn(0, fullBitmap.width - cropSize)
                var startY = (cy - half).toInt().coerceIn(0, fullBitmap.height - cropSize)
                val realSize = minOf(cropSize, fullBitmap.width - startX, fullBitmap.height - startY).coerceAtLeast(1)

                val cellBitmap = Bitmap.createBitmap(fullBitmap, startX, startY, realSize, realSize)

                saveGimmickBitmap(cellBitmap.copy(Bitmap.Config.ARGB_8888, false))
                cellBitmap.recycle()
                fullBitmap.recycle()

                mainHandler.post {
                    Toast.makeText(applicationContext, "✅ 기믹 저장 완료!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e("셀 캡처 실패", e)
                mainHandler.post {
                    Toast.makeText(applicationContext, "❌ 캡처 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isGrabberProcessing = false
            } finally {
                image.close()
                isGrabberProcessing = false
            }
        }
    }

    private fun setupScreenCapture(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { super.onStop(); stopCapture() }
            }, backgroundHandler)

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OOXOO_Capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
            )
            isCapturing = true
            mainHandler.post {
                isAutoScanEnabled = true
                startAutoScan()
                Toast.makeText(applicationContext, "🔄 자동 스캔 시작 (1초 간격)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { AppLogger.e("캡처 설정 실패", e) }
    }

    private fun stopCapture() {
        isCapturing = false; stopAutoScan()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    // 🔥 v51: seed 학습은 별도 함수 (사용자 명시적 액션 시에만)
    private fun saveSeedExplicitly() {
        val feat = currentFeature
        if (feat != null && feat.size == GridFeature.DIM) {
            val posArr = floatArrayOf(ptTL.x, ptTL.y, ptTR.x, ptTR.y, ptBL.x, ptBL.y, ptBR.x, ptBR.y)
            val (removedCount, removedLabels) = GridSeedDB.addManualCorrection(
                applicationContext, feat, rows, cols, currentBoardCrop, posArr
            )
            if (removedCount > 0) {
                AppLogger.d("🎓 정정 학습: ${rows}x${cols} | 삭제된 오답: ${removedLabels.joinToString(", ")} | 남은 시드: ${GridSeedDB.size(applicationContext)}개")
            } else {
                AppLogger.d("🎓 정답 학습: ${rows}x${cols} (총 ${GridSeedDB.size(applicationContext)}개)")
            }
        }
    }

    private fun savePreferences() {
        // v51: seed 학습 제거, UI 상태만 저장
        val prefs = getSharedPreferences("OOXOO_Auto", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("rows", rows); putInt("cols", cols)
            putFloat("ptTL_x", ptTL.x); putFloat("ptTL_y", ptTL.y)
            putFloat("ptTR_x", ptTR.x); putFloat("ptTR_y", ptTR.y)
            putFloat("ptBL_x", ptBL.x); putFloat("ptBL_y", ptBL.y)
            putFloat("ptBR_x", ptBR.x); putFloat("ptBR_y", ptBR.y)
            putBoolean("autoDetect", isAutoDetectEnabled)
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("OOXOO_Auto", Context.MODE_PRIVATE)
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat(); val h = metrics.heightPixels.toFloat()

        rows = prefs.getInt("rows", 11)
        cols = prefs.getInt("cols", 9)
        isAutoDetectEnabled = prefs.getBoolean("autoDetect", true)

        ptTL.set(prefs.getFloat("ptTL_x", w * 0.15f), prefs.getFloat("ptTL_y", h * 0.18f))
        ptTR.set(prefs.getFloat("ptTR_x", w * 0.85f), prefs.getFloat("ptTR_y", h * 0.18f))
        ptBL.set(prefs.getFloat("ptBL_x", w * 0.15f), prefs.getFloat("ptBL_y", h * 0.82f))
        ptBR.set(prefs.getFloat("ptBR_x", w * 0.85f), prefs.getFloat("ptBR_y", h * 0.82f))
    }

    private fun showLogDialog() {
        mainHandler.post {
            if (logDialogView != null) return@post
            val context = applicationContext
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FA1E1E1E"))
                setPadding(20, 20, 20, 20)
            }

            val tvTitle = TextView(context).apply {
                text = "📋 최근 로그 (${AppLogger.getAll().size}/200)"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 10)
            }
            layout.addView(tvTitle)

            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(320), dpToPx(400))
                setBackgroundColor(Color.parseColor("#111111"))
            }
            val tvLog = TextView(context).apply {
                text = if (AppLogger.getAll().isEmpty()) "(로그 없음)" else AppLogger.getAsText()
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(10, 10, 10, 10)
                setTextIsSelectable(true)
            }
            scrollView.addView(tvLog)
            layout.addView(scrollView)

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
            }

            Button(context).apply {
                text = "📋 복사"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                setBackgroundColor(Color.parseColor("#1976D2"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val ok = AppLogger.copyToClipboard(context)
                    Toast.makeText(context, if (ok) "📋 클립보드에 복사됨" else "❌ 복사 실패", Toast.LENGTH_SHORT).show()
                }
            }.also { btnRow.addView(it) }

            Button(context).apply {
                text = "💾 저장"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                setBackgroundColor(Color.parseColor("#388E3C"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val path = AppLogger.saveToDownloads(context)
                    Toast.makeText(
                        context,
                        if (path != null) "💾 저장됨: $path" else "❌ 저장 실패",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.also { btnRow.addView(it) }

            Button(context).apply {
                text = "🗑️ 지우기"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AppLogger.clear()
                    Toast.makeText(context, "🗑️ 로그 초기화", Toast.LENGTH_SHORT).show()
                    hideLogDialog()
                }
            }.also { btnRow.addView(it) }
            layout.addView(btnRow)

            Button(context).apply {
                text = "닫기"
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener { hideLogDialog() }
            }.also { layout.addView(it) }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            logDialogView = layout
            windowManager.addView(logDialogView, params)
        }
    }

    private fun hideLogDialog() {
        mainHandler.post {
            logDialogView?.let {
                try { windowManager.removeView(it) } catch (e: Exception) {}
                logDialogView = null
            }
        }
    }

    // 🔥 v30: 라벨 다이얼로그 숨기기
    private fun hideLabelDialog() {
        // v47: 동기 실행
        try {
            labelDialogView?.let {
                try { windowManager.removeView(it) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        labelDialogView = null
    }

    // 🔥 v32: 정답 입력 다이얼로그 (완전 방어적)
    private fun showLabelingDialog() {
        mainHandler.post {
            try {
                // 이전 다이얼로그 정리 (leftover 방지)
                hideLabelDialog()

                val ctx = applicationContext
                // v72: 썸네일 대신 fullBmp 사용 (500px → 1088px)
                var fullBmp = latestFullBitmap
                var thumb = fullBmp  // workingBitmap과 fullBmp 동일

                // v50: 백그라운드에서 캡처 (main 블로킹 방지)
                if (thumb == null || fullBmp == null || thumb.isRecycled || fullBmp.isRecycled) {
                    AppLogger.d("라벨: 캡처 시도 (background)")
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var captured: Bitmap? = null
                    backgroundHandler?.post {
                        try {
                            val reader = imageReader
                            if (reader != null) {
                                var image = reader.acquireLatestImage()
                                if (image == null) {
                                    try { Thread.sleep(150) } catch (e: Exception) {}
                                    image = reader.acquireLatestImage()
                                }
                                if (image != null) {
                                    try {
                                        val metrics = resources.displayMetrics
                                        val planes = image.planes
                                        val buffer = planes[0].buffer
                                        val pixelStride = planes[0].pixelStride
                                        val rowStride = planes[0].rowStride
                                        val w = metrics.widthPixels
                                        val h = metrics.heightPixels
                                        val rowPadding = rowStride - pixelStride * w
                                        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                                        bmp.copyPixelsFromBuffer(buffer)
                                        captured = bmp
                                        AppLogger.d("라벨: 캡처 성공 (${bmp.width}x${bmp.height})")
                                    } catch (e: Exception) {
                                        AppLogger.e("라벨 캡처 오류", e)
                                    } finally {
                                        try { image.close() } catch (e: Exception) {}
                                    }
                                } else {
                                    AppLogger.d("라벨: acquireLatestImage null")
                                }
                            } else {
                                AppLogger.d("라벨: imageReader null")
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                    try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
                    val cap = captured
                    if (cap != null) {
                        fullBmp = cap
                        // v57: 썸네일 대신 원본 그대로 (좌표 정확도)
                        thumb = cap
                        latestThumbnail = cap
                        latestFullBitmap = cap
                    }
                }

                if (thumb == null || fullBmp == null || thumb.isRecycled || fullBmp.isRecycled) {
                    Toast.makeText(ctx, "⚠️ 캡처 실패. 다시 시도", Toast.LENGTH_SHORT).show()
                    AppLogger.d("라벨: 캡처 최종 실패")
                    return@post
                }

                // 3. 안전한 bitmap copy
                val workingBitmap: Bitmap
                try {
                    workingBitmap = thumb.copy(Bitmap.Config.ARGB_8888, true)
                        ?: run {
                            Toast.makeText(ctx, "⚠️ 이미지 복사 실패", Toast.LENGTH_SHORT).show()
                            return@post
                        }
                } catch (e: Exception) {
                    AppLogger.e("라벨: bitmap 복사 실패", e)
                    Toast.makeText(ctx, "⚠️ 이미지 준비 실패", Toast.LENGTH_SHORT).show()
                    return@post
                }

                // 4. 컨테이너
                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setBackgroundColor(Color.parseColor("#FA1E1E1E"))
                    setPadding(20, 20, 20, 20)
                    layoutParams = android.view.ViewGroup.LayoutParams(900, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    minimumWidth = 900
                    minimumHeight = 800
                }

                TextView(ctx).apply {
                    text = "📸 이미지 터치: 좌상 → 우하"
                    setTextColor(Color.YELLOW)
                    textSize = 13f
                    setPadding(0, 0, 0, 8)
                }.also { container.addView(it) }

                // 미리보기
                val iv = ImageView(ctx).apply {
                    // v61: adjustViewBounds로 이미지 비율 유지
                    adjustViewBounds = true
                    val dm = resources.displayMetrics
                    val halfH = (dm.heightPixels * 0.5f).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, halfH
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#111111"))
                }
                container.addView(iv)
                iv.setImageBitmap(workingBitmap)

                // 좌표 상태
                var tlFull = android.graphics.PointF(ptTL.x, ptTL.y)
                var brFull = android.graphics.PointF(ptBR.x, ptBR.y)
                // v64: 탭 카운트로 순서 확정
                var tapCount = 0
                // v66: coordText를 미리 선언 (onTouchEvent에서 접근)
                lateinit var coordText: android.widget.TextView

                // redraw 전용 참조
                var etRowsRef: android.widget.EditText? = null
                var etColsRef: android.widget.EditText? = null

                // 안전한 redraw
                val redraw: () -> Unit = redraw@{
                    try {
                        if (workingBitmap.isRecycled) return@redraw
                        val mutable = try {
                            workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        } catch (e: Exception) { null }
                        if (mutable == null) return@redraw

                        val canvas = Canvas(mutable)
                        val sx = mutable.width.toFloat() / fullBmp.width.toFloat()
                        val sy = mutable.height.toFloat() / fullBmp.height.toFloat()
                        val tlx = tlFull.x * sx
                        val tly = tlFull.y * sy
                        val brx = brFull.x * sx
                        val bry = brFull.y * sy

                        // v65: 좌상=빨강, 우하=초록 사각형
                        val rectPaint = Paint().apply {
                            color = Color.parseColor("#00FF88")
                            style = Paint.Style.STROKE
                            strokeWidth = 3f
                        }
                        canvas.drawRect(tlx, tly, brx, bry, rectPaint)

                        val rVal = etRowsRef?.text?.toString()?.toIntOrNull() ?: rows
                        val cVal = etColsRef?.text?.toString()?.toIntOrNull() ?: cols
                        if (rVal in 3..20 && cVal in 3..20) {
                            val gridPaint = Paint().apply {
                                color = Color.parseColor("#AAFFFF00")
                                style = Paint.Style.STROKE
                                strokeWidth = 1f
                            }
                            for (i in 0..cVal) {
                                val x = tlx + (brx - tlx) * i / cVal
                                canvas.drawLine(x, tly, x, bry, gridPaint)
                            }
                            for (j in 0..rVal) {
                                val y = tly + (bry - tly) * j / rVal
                                canvas.drawLine(tlx, y, brx, y, gridPaint)
                            }
                        }

                        // v65: 좌상=빨강 큰 점, 우하=초록 큰 점
                        val tlPaint = Paint().apply {
                            color = Color.RED
                            style = Paint.Style.FILL
                        }
                        val tlStroke = Paint().apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = 3f
                        }
                        canvas.drawCircle(tlx, tly, 22f, tlPaint)
                        canvas.drawCircle(tlx, tly, 22f, tlStroke)
                        // "좌" 라벨
                        val tlText = Paint().apply {
                            color = Color.WHITE
                            textSize = 24f
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        canvas.drawText("좌", tlx, tly + 8f, tlText)

                        val brPaint = Paint().apply {
                            color = Color.GREEN
                            style = Paint.Style.FILL
                        }
                        val brStroke = Paint().apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = 3f
                        }
                        canvas.drawCircle(brx, bry, 22f, brPaint)
                        canvas.drawCircle(brx, bry, 22f, brStroke)
                        // "우" 라벨
                        val brText = Paint().apply {
                            color = Color.WHITE
                            textSize = 24f
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        canvas.drawText("우", brx, bry + 8f, brText)

                        iv.setImageBitmap(mutable)
                    } catch (e: Exception) {
                        AppLogger.e("라벨 redraw 오류", e)
                    }
                }

                // 안전한 좌표 변환
                fun ivToOriginal(ivX: Float, ivY: Float): android.graphics.PointF? {
                    return try {
                        val bmpW = workingBitmap.width.toFloat()
                        val bmpH = workingBitmap.height.toFloat()
                        if (bmpW <= 0f || bmpH <= 0f) return null

                        val vW = iv.width.toFloat()
                        val vH = iv.height.toFloat()
                        if (vW <= 0f || vH <= 0f) return null

                        // FIT_CENTER 스케일
                        val scale = minOf(vW / bmpW, vH / bmpH)
                        val dispW = bmpW * scale
                        val dispH = bmpH * scale
                        val offsetX = (vW - dispW) / 2f
                        val offsetY = (vH - dispH) / 2f

                        // iv 좌표 → workingBitmap 좌표
                        val wx = (ivX - offsetX) / scale
                        val wy = (ivY - offsetY) / scale
                        if (wx < 0f || wy < 0f || wx > bmpW || wy > bmpH) return null

                        // v71: workingBitmap → fullBmp 좌표로 스케일
                        val fScaleX = fullBmp.width.toFloat() / bmpW
                        val fScaleY = fullBmp.height.toFloat() / bmpH
                        val fx = wx * fScaleX
                        val fy = wy * fScaleY
                        AppLogger.d("ivToOrig: iv=(${ivX.toInt()},${ivY.toInt()}) v=${vW.toInt()}x${vH.toInt()} wb=${bmpW.toInt()}x${bmpH.toInt()} → wb=(${wx.toInt()},${wy.toInt()}) → full=(${fx.toInt()},${fy.toInt()})")
                        android.graphics.PointF(fx, fy)
                    } catch (e: Exception) {
                        AppLogger.e("ivToOrig err", e)
                        null
                    }
                }

                // v49: 드래그 + 근접 감지
                var draggingCorner = 0  // 0=없음, 1=TL, 2=BR
                val hitRadius = 120f  // 확대 반경

                iv.setOnTouchListener { _, ev ->
                    val ivX = ev.x
                    val ivY = ev.y

                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val orig = ivToOriginal(ivX, ivY)
                            if (orig != null) {
                                // v64: tapCount 0 or 2 → 좌상 / 1 → 우하
                                if (tapCount % 2 == 0) {
                                    tlFull = orig
                                    AppLogger.d("좌상 지정: (${orig.x.toInt()},${orig.y.toInt()})")
                                } else {
                                    brFull = orig
                                    AppLogger.d("우하 지정: (${orig.x.toInt()},${orig.y.toInt()})")
                                }
                                tapCount++
                                redraw()
                                // v65: 좌표 텍스트 갱신
                                mainHandler.post {
                                    coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // 드래그는 손 안 뗀 상태 → 이전 탭한 곳 조정
                            val orig = ivToOriginal(ivX, ivY)
                            if (orig != null) {
                                if (tapCount % 2 == 1) {
                                    tlFull = orig
                                } else {
                                    brFull = orig
                                }
                                redraw()
                            }
                            true
                        }
                        else -> false
                    }
                }

                // 값 입력
                val inputRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 12, 0, 12)
                }
                TextView(ctx).apply {
                    text = "행:"
                    setTextColor(Color.WHITE); textSize = 15f; setPadding(0, 0, 10, 0)
                }.also { inputRow.addView(it) }

                val etRows = android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText(rows.toString()); setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#333333"))
                    textSize = 16f; setPadding(20, 15, 20, 15)
                    layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                etRowsRef = etRows
                inputRow.addView(etRows)

                TextView(ctx).apply {
                    text = "  ×  "; setTextColor(Color.WHITE); textSize = 15f
                }.also { inputRow.addView(it) }
                TextView(ctx).apply {
                    text = "열:"; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, 0, 10, 0)
                }.also { inputRow.addView(it) }

                val etCols = android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText(cols.toString()); setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#333333"))
                    textSize = 16f; setPadding(20, 15, 20, 15)
                    layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                etColsRef = etCols
                inputRow.addView(etCols)

                container.addView(inputRow)

                // TextWatcher로 redraw 연결
                val watcher = object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { redraw() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                }
                etRows.addTextChangedListener(watcher)
                etCols.addTextChangedListener(watcher)

                // v65: 좌상/우하 좌표 표시
                coordText = TextView(ctx).apply {
                    text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(0, 8, 0, 8)
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#222222"))
                }
                container.addView(coordText)

                // v67: 미세 조정 UI (좌상 방향키 + 우하 방향키)
                val adjustRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }

                // 좌상 방향키
                val tlBox = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                TextView(ctx).apply {
                    text = "🔴 좌상"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                }.also { tlBox.addView(it) }
                val tlUpRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
                Button(ctx).apply {
                    text = "▲"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        tlFull.y -= 5f
                        if (tlFull.y < 0) tlFull.y = 0f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { tlUpRow.addView(it) }
                tlBox.addView(tlUpRow)
                val tlMidRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
                Button(ctx).apply {
                    text = "◀"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        tlFull.x -= 5f
                        if (tlFull.x < 0) tlFull.x = 0f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { tlMidRow.addView(it) }
                Button(ctx).apply {
                    text = "▶"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        tlFull.x += 5f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { tlMidRow.addView(it) }
                tlBox.addView(tlMidRow)
                val tlDownRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
                Button(ctx).apply {
                    text = "▼"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        tlFull.y += 5f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { tlDownRow.addView(it) }
                tlBox.addView(tlDownRow)
                adjustRow.addView(tlBox)

                View(ctx).apply { layoutParams = LinearLayout.LayoutParams(40, 10) }.also { adjustRow.addView(it) }

                // 우하 방향키
                val brBox = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                TextView(ctx).apply {
                    text = "🟢 우하"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                }.also { brBox.addView(it) }
                val brUpRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
                Button(ctx).apply {
                    text = "▲"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        brFull.y -= 5f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { brUpRow.addView(it) }
                brBox.addView(brUpRow)
                val brMidRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
                Button(ctx).apply {
                    text = "◀"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        brFull.x -= 5f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { brMidRow.addView(it) }
                Button(ctx).apply {
                    text = "▶"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        brFull.x += 5f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { brMidRow.addView(it) }
                brBox.addView(brMidRow)
                val brDownRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
                Button(ctx).apply {
                    text = "▼"; textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setBackgroundColor(Color.parseColor("#555555"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        brFull.y += 5f
                        redraw()
                        coordText.text = "🔴 좌상: (${tlFull.x.toInt()},${tlFull.y.toInt()})  🟢 우하: (${brFull.x.toInt()},${brFull.y.toInt()})"
                    }
                }.also { brDownRow.addView(it) }
                brBox.addView(brDownRow)
                adjustRow.addView(brBox)

                container.addView(adjustRow)

                // 분포
                val dist = com.example.helper.util.GridSeedDB.getClassDistribution(ctx)
                TextView(ctx).apply {
                    text = if (dist.isEmpty()) "📊 데이터 없음"
                           else "📊 " + dist.entries.joinToString(", ") { "${it.key}(${it.value})" }
                    setTextColor(Color.parseColor("#81C784")); textSize = 11f
                    setPadding(0, 0, 0, 8)
                }.also { container.addView(it) }

                // 버튼
                val btnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }

                Button(ctx).apply {
                    text = "✅ 저장"
                    setBackgroundColor(Color.parseColor("#4CAF50")); setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                    setOnClickListener {
                        try {
                            val r = etRows.text.toString().toIntOrNull()
                            val c = etCols.text.toString().toIntOrNull()
                            if (r == null || c == null || r < 3 || r > 20 || c < 3 || c > 20) {
                                Toast.makeText(ctx, "❌ 3~20 사이", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            rows = r; cols = c
                            // v74: 좌우 2.5% + 상하 4% 확장 (실제 게임판 경계 포함)
                            val w0 = brFull.x - tlFull.x
                            val h0 = brFull.y - tlFull.y
                            val expandW = w0 * 0.025f   // 좌우 2.5%
                            val expandH = h0 * 0.04f    // 상하 4%
                            val realTLx = (tlFull.x - expandW).coerceAtLeast(0f)
                            val realTLy = (tlFull.y - expandH).coerceAtLeast(0f)
                            val realBRx = (brFull.x + expandW).coerceAtMost(fullBmp.width.toFloat())
                            val realBRy = (brFull.y + expandH).coerceAtMost(fullBmp.height.toFloat())
                            AppLogger.d("저장 전 확인: tlFull=(${realTLx.toInt()},${realTLy.toInt()}) brFull=(${realBRx.toInt()},${realBRy.toInt()})")
                            ptTL.set(realTLx, realTLy)
                            ptTR.set(realBRx, realTLy)
                            ptBL.set(realTLx, realBRy)
                            ptBR.set(realBRx, realBRy)
                            isAutoDetectEnabled = false
                            AppLogger.d("ptTL 저장: (${ptTL.x.toInt()},${ptTL.y.toInt()}) ptBR=(${ptBR.x.toInt()},${ptBR.y.toInt()})")

                            val feat = currentFeature
                            if (feat != null && feat.size == com.example.helper.util.GridFeature.DIM) {
                                val posArr = floatArrayOf(ptTL.x, ptTL.y, ptTR.x, ptTR.y, ptBL.x, ptBL.y, ptBR.x, ptBR.y)
                                val (removedCount, removedLabels) = com.example.helper.util.GridSeedDB.addManualCorrection(
                                    ctx, feat, rows, cols, currentBoardCrop, posArr
                                )
                                AppLogger.d("🎓 라벨: ${rows}x${cols} | TL=(${ptTL.x.toInt()},${ptTL.y.toInt()}) BR=(${ptBR.x.toInt()},${ptBR.y.toInt()}) | 삭제=${removedCount}")
                            }
                            // v52: 저장 시 자동 OFF (k-NN이 덮어쓰지 않도록)
                            isAutoDetectEnabled = false
                            isCalibrationMode = false
                            // v54: 진행 중 스캔 강제 종료 (인덱스 크래시 방지)
                            isScanning = false
                            savePreferences()
                            refreshControlUI()
                            overlayView?.invalidate()
                            Toast.makeText(ctx, "🎓 저장: ${rows}x${cols}", Toast.LENGTH_SHORT).show()
                            hideLabelDialog()
                        } catch (e: Exception) {
                            AppLogger.e("라벨 저장 오류", e)
                            Toast.makeText(ctx, "❌ 저장 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.also { btnRow.addView(it) }

                Button(ctx).apply {
                    text = "🔄 리셋"
                    setBackgroundColor(Color.parseColor("#FF8C00")); setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                    setOnClickListener {
                        tlFull = android.graphics.PointF(0f, 0f)
                        brFull = android.graphics.PointF(fullBmp.width.toFloat(), fullBmp.height.toFloat())
                        redraw()
                    }
                }.also { btnRow.addView(it) }

                Button(ctx).apply {
                    text = "❌ 취소"
                    setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                    setOnClickListener { hideLabelDialog() }
                }.also { btnRow.addView(it) }
                container.addView(btnRow)

                // WindowManager에 추가
                val wmParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 100
                }

                windowManager.addView(container, wmParams)
                labelDialogView = container
                AppLogger.d("📸 라벨 다이얼로그 열림")

                mainHandler.postDelayed({ redraw() }, 200)

            } catch (e: Exception) {
                AppLogger.e("라벨 다이얼로그 오류", e)
                Toast.makeText(applicationContext, "❌ 오류: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }





    // 🔥 v20: 꾹 누르면 연속
    override fun onDestroy() {
        stopCapture(); hideGimmickManager()
        synchronized(dynamicTemplates) {
            dynamicTemplates.forEach { it.release() }
            dynamicTemplates.clear(); dynamicTemplateFiles.clear(); templateSizes.clear()
        }
        controlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}