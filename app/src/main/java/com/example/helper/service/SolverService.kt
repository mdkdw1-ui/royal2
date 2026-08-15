package com.example.helper.service

import android.app.*
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
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
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
            Log.d(TAG, "OpenCV 로드 성공")
            isOpenCVInitialized = true
            backgroundHandler?.post { loadTemplatesFromStorage() }
        } else {
            Log.e(TAG, "OpenCV 로드 실패")
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
        } catch (e: Exception) { Log.e(TAG, "템플릿 로드 실패", e) }
    }

    private fun saveGimmickBitmap(bitmap: Bitmap) {
        try {
            val dir = getExternalFilesDir("gimmicks")
            if (dir != null && !dir.exists()) dir.mkdirs()

            val TEMPLATE_SIZE = 64
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, TEMPLATE_SIZE, TEMPLATE_SIZE, true)

            val file = File(dir, "gimmick_${System.currentTimeMillis()}.png")
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
                Toast.makeText(applicationContext, "✅ 기믹 저장 완료! (${dynamicTemplates.size}개)", Toast.LENGTH_SHORT).show()
                refreshControlUI()
            }
        } catch (e: Exception) {
            Log.e(TAG, "기믹 저장 실패", e)
            mainHandler.post {
                Toast.makeText(applicationContext, "❌ 기믹 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            isImageGrabberMode = false
            isGrabberProcessing = false
            overlayView?.let {
                val params = it.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(it, params)
            }
            refreshControlUI()
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
            } catch (e: Exception) { Log.e(TAG, "삭제 실패", e) }
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
            } catch (e: Exception) { Log.e(TAG, "전체 삭제 실패", e) }
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
                    Log.d(TAG, "📷 기믹 따기: row=$targetRow, col=$targetCol")
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
                x = 30; y = 100
            }

            controlView = object : LinearLayout(context) {
                private var initialX = 0; private var initialY = 0
                private var initialTouchX = 0f; private var initialTouchY = 0f
                private val touchSlop = 15f

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
                setPadding(15, 12, 15, 12)
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
                    savePreferences()
                    Toast.makeText(context, "💾 격자 위치 저장 완료!", Toast.LENGTH_SHORT).show()
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
        }
        TextView(context).apply {
            text = if (isCompactMode) "🔍 OOXOO" else "🎯 OOXOO 자동 감지기"
            setTextColor(Color.WHITE)
            textSize = if (isCompactMode) 13f else 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { topRow.addView(it) }

        Button(context).apply {
            text = if (isCompactMode) "▼ 확장" else "▲ 접기"
            textSize = 11f
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
            text = "$statusText | 발견: ${foundPositions.size}개 | 자동격자: ${if (isAutoDetectEnabled) "ON" else "OFF"}"
            setTextColor(if (isAutoScanEnabled) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
            textSize = 11f
            setPadding(0, 5, 0, 5)
        }.also { view.addView(it) }

        // 간이 모드
        if (isCompactMode) {
            Button(context).apply {
                text = "❌ 종료"
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
                setOnClickListener { stopSelf() }
            }.also { view.addView(it) }
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
                overlayView?.let {
                    val p = it.layoutParams as WindowManager.LayoutParams
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager.updateViewLayout(it, p)
                }
                if (isImageGrabberMode) { isImageGrabberMode = false; isGrabberProcessing = false }
                Toast.makeText(context, "📐 보정 모드 (코너 드래그 가능)", Toast.LENGTH_LONG).show()
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
            } catch (e: Exception) { Log.e(TAG, "자동 감지 오류", e) }
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

        val candidates = listOf(
            8 to 8, 8 to 9, 8 to 10,
            9 to 8, 9 to 9, 9 to 10, 9 to 11,
            10 to 8, 10 to 9, 10 to 10, 10 to 11,
            11 to 8, 11 to 9, 11 to 10, 11 to 11,
            12 to 8, 12 to 9, 12 to 10, 12 to 11
        )
        var bestRows = rows; var bestCols = cols; var bestScore = -1.0
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for ((r, c) in candidates) {
            var valid = 0; var total = 0
            for (rr in 0 until r) for (cc in 0 until c) {
                val u = (cc + 0.5f) / c; val v = (rr + 0.5f) / r
                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y
                val cx = (1 - v) * topX + v * bottomX
                val cy = (1 - v) * topY + v * bottomY
                val ix = cx.toInt().coerceIn(0, width - 1)
                val iy = cy.toInt().coerceIn(0, height - 1)
                val pixel = pixels[iy * width + ix]
                val hsv = FloatArray(3); Color.colorToHSV(pixel, hsv)
                total++
                if (hsv[1] > 0.15f && hsv[2] > 0.15f) valid++
            }
            val score = valid.toDouble() / total
            if (score > bestScore) { bestScore = score; bestRows = r; bestCols = c }
        }

        if (bestScore > 0.15) {
            rows = bestRows; cols = bestCols
            savePreferences()
            Log.d(TAG, "자동 인식 성공: ${rows}x${cols}, 신뢰도 ${"%.0f".format(bestScore * 100)}%")
            src.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
            return true
        }

        src.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
        return false
    }

    private fun sortCorners(points: Array<org.opencv.core.Point>): List<org.opencv.core.Point> {
        val sorted = points.sortedBy { it.y }
        val top = sorted.take(2).sortedBy { it.x }
        val bottom = sorted.drop(2).sortedBy { it.x }
        return listOf(top[0], top[1], bottom[0], bottom[1])
    }

    // 🔥 스캔 실행
    private fun performScan() {
        if (isScanning) return
        isScanning = true
        backgroundHandler?.post {
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

                if (isAutoDetectEnabled) {
                    autoDetectBoard(bitmap)
                }

                val positions = findOOXOO(bitmap)

                mainHandler.post {
                    foundPositions.clear(); foundPositions.addAll(positions)
                    overlayView?.updatePositions(positions)
                    refreshControlUI()
                    isScanning = false
                }
                bitmap.recycle()
            } catch (e: Exception) { Log.e(TAG, "스캔 오류", e); mainHandler.post { isScanning = false } }
            finally { image.close() }
        }
    }

    // 🔥 OOXOO 탐색 (5칸 정확히)
    private fun findOOXOO(bitmap: Bitmap): List<Pair<Int, Int>> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
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
        Log.d(TAG, "📷 기믹 캡처 시작: row=$row, col=$col")
        val reader = imageReader ?: run {
            Log.e(TAG, "❌ imageReader가 null입니다")
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

                saveGimmickBitmap(cellBitmap)
                cellBitmap.recycle()
                fullBitmap.recycle()

                mainHandler.post {
                    Toast.makeText(applicationContext, "✅ 기믹 저장 완료!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "셀 캡처 실패", e)
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
        } catch (e: Exception) { Log.e(TAG, "캡처 설정 실패", e) }
    }

    private fun stopCapture() {
        isCapturing = false; stopAutoScan()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    private fun savePreferences() {
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