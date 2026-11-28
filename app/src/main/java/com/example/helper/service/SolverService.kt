package com.example.helper.service

import android.app.Activity
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
import android.graphics.Path
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.helper.core.DetectedGem
import com.example.helper.core.EngineResult
import com.example.helper.core.FiveMatchMove
import com.example.helper.core.GemTemplateStore
import com.example.helper.core.RemoteSolverClient
import com.example.helper.core.ZeroMistakeEngine
import com.example.helper.util.AppLogger
import com.example.helper.util.UpdateChecker
import org.opencv.android.OpenCVLoader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SolverService : Service() {

    private val binder = SolverBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var overlayView: OverlayView? = null
    private var controlView: LinearLayout? = null
    private var logDialogView: View? = null
    private var serverDialogView: View? = null
    private var floatParams: WindowManager.LayoutParams? = null

    private var isOpenCVReady = false
    private var isCapturing = false
    private var isScanEnabled = true
    private var isScanning = false
    private var showDebugDots = true

    private var isGemRegisterMode = false
    private var isRegisterBusy = false
    private var selectedGemColor = 1

    private var useRemoteServer = false
    private var serverUrl = ""

    private var lastResult = EngineResult(false, 0, 0, 0f, 0f, emptyList(), emptyList())
    private val scanIntervalMs = 380L
    private var scanRunnable: Runnable? = null

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bgThread = HandlerThread("OOXOO_ZeroMistake").apply { start() }
        bgHandler = Handler(bgThread!!.looper)

        loadSettings()
        if (OpenCVLoader.initDebug()) {
            isOpenCVReady = true
            AppLogger.d("OpenCV 로드 성공")
            bgHandler?.post { GemTemplateStore.load(applicationContext) }
        } else {
            AppLogger.e("OpenCV 로드 실패")
        }

        createOverlayWindow()
        createControlWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("RESULT_DATA")
            }
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                bgHandler?.post { setupCapture(resultCode, resultData) }
            }
        }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        val channelId = "OOXOO_ZeroMistake_Ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "OOXOO 5매치 감지기", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OOXOO 5매치 감지기")
            .setContentText("실행 중")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notif)
        }
    }

    private fun setupCapture(resultCode: Int, resultData: Intent) {
        try {
            stopCapture()
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopCapture()
                }
            }, bgHandler)

            val (w, h) = getRealScreenMetrics()
            val dpi = resources.displayMetrics.densityDpi
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OOXOO_Capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, bgHandler
            )
            isCapturing = true
            mainHandler.post {
                startScanLoop()
                Toast.makeText(applicationContext, "✅ 화면 감시 시작", Toast.LENGTH_SHORT).show()
                refreshControlUI()
            }
        } catch (e: Exception) {
            AppLogger.e("캡처 초기화 실패", e)
        }
    }

    private fun stopCapture() {
        isCapturing = false
        stopScanLoop()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    private fun startScanLoop() {
        stopScanLoop()
        val r = object : Runnable {
            override fun run() {
                if (!isCapturing) return
                if (isScanEnabled && !isGemRegisterMode && !isScanning) {
                    performScanOnce()
                }
                bgHandler?.postDelayed(this, scanIntervalMs)
            }
        }
        scanRunnable = r
        bgHandler?.post(r)
    }

    private fun stopScanLoop() {
        scanRunnable?.let { bgHandler?.removeCallbacks(it) }
        scanRunnable = null
    }

    private fun acquireScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image = reader.acquireLatestImage()
        if (image == null) {
            try { Thread.sleep(30) } catch (_: Exception) {}
            image = reader.acquireLatestImage()
        }
        image ?: return null
        return try {
            val (w, h) = getRealScreenMetrics()
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * w
            val raw = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            if (raw.width != w) {
                Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() }
            } else raw
        } catch (e: Exception) {
            AppLogger.e("비트맵 변환 실패", e)
            null
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun performScanOnce() {
        if (!isOpenCVReady || isScanning) return
        if (!useRemoteServer && !GemTemplateStore.isReady()) return
        isScanning = true
        try {
            val bmp = acquireScreenBitmap() ?: return
            try {
                val res = if (useRemoteServer && serverUrl.isNotBlank()) {
                    val remote = RemoteSolverClient.analyze(serverUrl, bmp)
                    if (remote == null) {
                        ZeroMistakeEngine.resetTemporal()
                        ZeroMistakeEngine.analyze(bmp)
                    } else remote
                } else {
                    ZeroMistakeEngine.analyze(bmp)
                }
                lastResult = res
                mainHandler.post {
                    overlayView?.updateResult(res)
                    refreshControlUI()
                }
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            AppLogger.e("스캔 오류", e)
        } finally {
            isScanning = false
        }
    }

    private fun handleGemTap(tapX: Float, tapY: Float) {
        if (isRegisterBusy) return
        isRegisterBusy = true
        val color = selectedGemColor
        bgHandler?.post {
            val bmp = acquireScreenBitmap()
            if (bmp == null) {
                isRegisterBusy = false
                mainHandler.post { Toast.makeText(applicationContext, "❌ 캡처 실패", Toast.LENGTH_SHORT).show() }
                return@post
            }
            try {
                val tpl = GemTemplateStore.registerFromTap(applicationContext, bmp, color, tapX, tapY)
                if (serverUrl.isNotBlank()) {
                    RemoteSolverClient.uploadGem(serverUrl, color, GemTemplateStore.pitchRatio, tpl.pngBytes)
                }
                ZeroMistakeEngine.resetTemporal()
                val preview = ZeroMistakeEngine.analyze(bmp)
                lastResult = preview
                val names = mapOf(1 to "빨강", 2 to "노랑", 3 to "초록", 4 to "파랑", 5 to "보라")
                mainHandler.post {
                    overlayView?.updateResult(preview)
                    val next = GemTemplateStore.nextMissingColor()
                    if (color <= 4 && next != null) {
                        selectedGemColor = next
                        Toast.makeText(applicationContext, "✅ ${names[color]} 등록! 다음 [${names[next]}] 터치", Toast.LENGTH_SHORT).show()
                    } else if (GemTemplateStore.isReady() && color <= 4) {
                        Toast.makeText(applicationContext, "🎉 기본 4종 완료! (보라는 선택 후 터치, 완료 누르기)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(applicationContext, "✅ ${names[color]} 등록 완료!", Toast.LENGTH_SHORT).show()
                    }
                    refreshControlUI()
                }
            } catch (e: Exception) {
                AppLogger.e("젬 등록 실패", e)
            } finally {
                bmp.recycle()
                isRegisterBusy = false
            }
        }
    }

    private fun enterGemRegisterMode() {
        isGemRegisterMode = true
        isRegisterBusy = false
        selectedGemColor = GemTemplateStore.nextMissingColor() ?: 1
        setOverlayTouchable(true)
        Toast.makeText(applicationContext, "💎 보드의 일반 젬 중앙을 터치하세요 (빨→노→초→파)", Toast.LENGTH_LONG).show()
        refreshControlUI()
    }

    private fun exitGemRegisterMode() {
        isGemRegisterMode = false
        isRegisterBusy = false
        setOverlayTouchable(false)
        ZeroMistakeEngine.resetTemporal()
        refreshControlUI()
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        overlayView?.let { ov ->
            val p = ov.layoutParams as WindowManager.LayoutParams
            p.flags = if (touchable) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try { windowManager.updateViewLayout(ov, p) } catch (_: Exception) {}
        }
    }

    inner class OverlayView(context: Context) : View(context) {
        private var result = EngineResult(false, 0, 0, 0f, 0f, emptyList(), emptyList())
        private val gemColors = mapOf(
            1 to Color.parseColor("#FF3B30"),
            2 to Color.parseColor("#FFD60A"),
            3 to Color.parseColor("#34C759"),
            4 to Color.parseColor("#0A84FF"),
            5 to Color.parseColor("#BF5AF2")
        )
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
        }
        private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val lineBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55FFD700"); style = Paint.Style.FILL
        }
        private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC00E5FF"); style = Paint.Style.FILL
        }
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDFFE600"); style = Paint.Style.FILL
        }
        private val circleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE
            strokeWidth = 10f; strokeCap = Paint.Cap.ROUND
        }
        private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF88"); style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 32f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }

        fun updateResult(r: EngineResult) {
            this.result = r
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = result

            if (showDebugDots || isGemRegisterMode) {
                for (g in r.gems) {
                    if (g.movable) {
                        dotPaint.color = gemColors[g.color] ?: Color.WHITE
                        canvas.drawCircle(g.x, g.y, 11f, dotPaint)
                        canvas.drawCircle(g.x, g.y, 11f, dotStroke)
                    } else {
                        canvas.drawLine(g.x - 10f, g.y - 10f, g.x + 10f, g.y + 10f, lockPaint)
                        canvas.drawLine(g.x + 10f, g.y - 10f, g.x - 10f, g.y + 10f, lockPaint)
                    }
                }
            }

            val halfW = (r.cellW * 0.46f).coerceAtLeast(24f)
            val halfH = (r.cellH * 0.46f).coerceAtLeast(24f)
            for (m in r.moves) {
                for (pt in m.lineCoords) {
                    canvas.drawRect(pt.x - halfW, pt.y - halfH, pt.x + halfW, pt.y + halfH, lineBgPaint)
                }
                canvas.drawCircle(m.sourceX, m.sourceY, 34f, sourcePaint)
                canvas.drawCircle(m.sourceX, m.sourceY, 34f, circleBorder)
                canvas.drawText("O", m.sourceX, m.sourceY + 11f, labelPaint)

                canvas.drawLine(m.sourceX, m.sourceY, m.targetX, m.targetY, arrowPaint)
                val ang = atan2((m.targetY - m.sourceY).toDouble(), (m.targetX - m.sourceX).toDouble())
                val aLen = 28f
                val aAng = Math.PI / 6
                val head = Path().apply {
                    moveTo(m.targetX, m.targetY)
                    lineTo((m.targetX - aLen * cos(ang - aAng)).toFloat(), (m.targetY - aLen * sin(ang - aAng)).toFloat())
                    lineTo((m.targetX - aLen * cos(ang + aAng)).toFloat(), (m.targetY - aLen * sin(ang + aAng)).toFloat())
                    close()
                }
                canvas.drawPath(head, arrowHeadPaint)

                canvas.drawCircle(m.targetX, m.targetY, 38f, targetPaint)
                canvas.drawCircle(m.targetX, m.targetY, 38f, circleBorder)
                canvas.drawText("5", m.targetX, m.targetY + 11f, labelPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isGemRegisterMode && event.action == MotionEvent.ACTION_DOWN) {
                handleGemTap(event.rawX, event.rawY)
                return true
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            windowManager.addView(overlayView, params)
        }
    }

    private fun createControlWindow() {
        mainHandler.post {
            val ctx = applicationContext
            floatParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 12
                y = 40
            }

            controlView = object : LinearLayout(ctx) {
                private var ix = 0; private var iy = 0
                private var tx = 0f; private var ty = 0f
                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            ix = floatParams!!.x; iy = floatParams!!.y
                            tx = ev.rawX; ty = ev.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (abs(ev.rawX - tx) > 15f || abs(ev.rawY - ty) > 15f) return true
                        }
                    }
                    return super.onInterceptTouchEvent(ev)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.action == MotionEvent.ACTION_MOVE) {
                        floatParams!!.x = ix + (event.rawX - tx).toInt()
                        floatParams!!.y = iy + (event.rawY - ty).toInt()
                        windowManager.updateViewLayout(this, floatParams)
                        return true
                    }
                    return super.onTouchEvent(event)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#DC121212"))
                setPadding(8, 6, 8, 6)
            }

            refreshControlUI()
            windowManager.addView(controlView, floatParams)
        }
    }

    private fun refreshControlUI() {
        val view = controlView ?: return
        view.removeAllViews()
        val ctx = applicationContext

        if (isGemRegisterMode) {
            TextView(ctx).apply {
                text = "💎 보드의 해당 색 젬을 터치 (${GemTemplateStore.baseCount()}/4)"
                setTextColor(Color.YELLOW)
                textSize = 10f
                setPadding(4, 2, 4, 4)
            }.also { view.addView(it) }

            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val gemDefs = listOf(1 to "빨", 2 to "노", 3 to "초", 4 to "파", 5 to "보")
            val gemBg = mapOf(1 to "#C62828", 2 to "#F9A825", 3 to "#2E7D32", 4 to "#1565C0", 5 to "#6A1B9A")
            for ((code, name) in gemDefs) {
                val done = GemTemplateStore.hasColor(code)
                val sel = code == selectedGemColor
                Button(ctx).apply {
                    text = (if (sel) "▶" else "") + name + (if (done) "✓" else "")
                    textSize = 10f
                    setPadding(0, 0, 0, 0)
                    setBackgroundColor(Color.parseColor(if (sel) "#FF00AA" else gemBg[code]))
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(42), dpToPx(30)).apply { setMargins(2, 0, 2, 0) }
                    setOnClickListener {
                        selectedGemColor = code
                        refreshControlUI()
                    }
                }.also { row.addView(it) }
            }
            view.addView(row)

            val ctrlRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 0)
            }
            Button(ctx).apply {
                text = "초기화"
                textSize = 10f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(28), 1f).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener {
                    GemTemplateStore.clearAll(ctx)
                    if (serverUrl.isNotBlank()) bgHandler?.post { RemoteSolverClient.clearGems(serverUrl) }
                    selectedGemColor = 1
                    refreshControlUI()
                }
            }.also { ctrlRow.addView(it) }
            Button(ctx).apply {
                text = "완료"
                textSize = 10f
                setBackgroundColor(Color.parseColor("#2E7D32"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(28), 1f).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener { exitGemRegisterMode() }
            }.also { ctrlRow.addView(it) }
            view.addView(ctrlRow)

            floatParams?.let { try { windowManager.updateViewLayout(view, it) } catch (_: Exception) {} }
            return
        }

        val modeTxt = if (useRemoteServer) "OCI" else "로컬"
        val boardTxt = if (lastResult.rows > 0) "${lastResult.rows}x${lastResult.cols}" else "대기"
        val moveTxt = if (lastResult.moving) "이동중" else "5매치:${lastResult.moves.size}"
        TextView(ctx).apply {
            text = "$modeTxt | $boardTxt | 💎${GemTemplateStore.baseCount()}/4 | $moveTxt"
            setTextColor(if (lastResult.moves.isNotEmpty()) Color.YELLOW else Color.parseColor("#A5D6A7"))
            textSize = 10f
            setPadding(2, 0, 2, 4)
        }.also { view.addView(it) }

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun miniBtn(label: String, bg: String, onClick: () -> Unit): Button {
            return Button(ctx).apply {
                text = label
                textSize = 9.5f
                setPadding(2, 0, 2, 0)
                setBackgroundColor(Color.parseColor(bg))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(28)).apply { setMargins(1, 0, 1, 0) }
                setOnClickListener { onClick() }
            }
        }

        btnRow.addView(miniBtn("💎", if (GemTemplateStore.isReady()) "#2E7D32" else "#8E24AA") { enterGemRegisterMode() })
        btnRow.addView(miniBtn(if (isScanEnabled) "⏸" else "▶", if (isScanEnabled) "#455A64" else "#2E7D32") {
            isScanEnabled = !isScanEnabled
            ZeroMistakeEngine.resetTemporal()
            refreshControlUI()
        })
        btnRow.addView(miniBtn("👁", if (showDebugDots) "#0277BD" else "#424242") {
            showDebugDots = !showDebugDots
            overlayView?.invalidate()
            refreshControlUI()
        })
        btnRow.addView(miniBtn(if (useRemoteServer) "OCI" else "폰", if (useRemoteServer) "#EF6C00" else "#37474F") {
            if (!useRemoteServer && serverUrl.isBlank()) {
                showServerDialog()
            } else {
                useRemoteServer = !useRemoteServer
                saveSettings()
                ZeroMistakeEngine.resetTemporal()
                refreshControlUI()
            }
        })
        btnRow.addView(miniBtn("⚙", "#5D4037") { showServerDialog() })
        btnRow.addView(miniBtn("🔄", "#00695C") {
            Toast.makeText(ctx, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
            UpdateChecker.checkAndUpdate(ctx) { msg ->
                mainHandler.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            }
        })
        btnRow.addView(miniBtn("❌", "#C62828") { stopSelf() })
        view.addView(btnRow)

        floatParams?.let { try { windowManager.updateViewLayout(view, it) } catch (_: Exception) {} }
    }

    private fun showServerDialog() {
        mainHandler.post {
            if (serverDialogView != null) return@post
            val ctx = applicationContext
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F51E1E1E"))
                setPadding(28, 24, 28, 24)
            }
            TextView(ctx).apply {
                text = "🌐 오라클 AMD Micro 서버 주소"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, 10)
            }.also { box.addView(it) }

            val et = EditText(ctx).apply {
                hint = "http://서버IP:8000"
                setText(serverUrl)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.parseColor("#333333"))
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(dpToPx(260), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            box.addView(et)

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 14, 0, 0)
            }
            Button(ctx).apply {
                text = "저장 & OCI ON"
                setBackgroundColor(Color.parseColor("#2E7D32"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener {
                    serverUrl = et.text.toString().trim()
                    useRemoteServer = serverUrl.isNotBlank()
                    saveSettings()
                    if (useRemoteServer) {
                        bgHandler?.post {
                            for ((c, tpl) in GemTemplateStore.snapshot()) {
                                RemoteSolverClient.uploadGem(serverUrl, c, GemTemplateStore.pitchRatio, tpl.pngBytes)
                            }
                        }
                    }
                    hideServerDialog()
                    refreshControlUI()
                }
            }.also { row.addView(it) }

            Button(ctx).apply {
                text = "로그"
                setBackgroundColor(Color.parseColor("#37474F"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener {
                    hideServerDialog()
                    showLogDialog()
                }
            }.also { row.addView(it) }

            Button(ctx).apply {
                text = "닫기"
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener { hideServerDialog() }
            }.also { row.addView(it) }
            box.addView(row)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                0,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            serverDialogView = box
            windowManager.addView(box, params)
        }
    }

    private fun hideServerDialog() {
        serverDialogView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        serverDialogView = null
    }

    private fun showLogDialog() {
        mainHandler.post {
            if (logDialogView != null) return@post
            val ctx = applicationContext
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F51E1E1E"))
                setPadding(20, 20, 20, 20)
            }
            val sv = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(300), dpToPx(320))
                setBackgroundColor(Color.parseColor("#111111"))
            }
            TextView(ctx).apply {
                text = AppLogger.getAsText().ifEmpty { "(로그 없음)" }
                setTextColor(Color.LTGRAY)
                textSize = 10f
                setPadding(8, 8, 8, 8)
            }.also { sv.addView(it) }
            box.addView(sv)

            Button(ctx).apply {
                text = "닫기"
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    logDialogView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
                    logDialogView = null
                }
            }.also { box.addView(it) }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            logDialogView = box
            windowManager.addView(box, params)
        }
    }

    private fun saveSettings() {
        getSharedPreferences("OOXOO_V2", Context.MODE_PRIVATE).edit()
            .putBoolean("useRemote", useRemoteServer)
            .putString("serverUrl", serverUrl)
            .apply()
    }

    private fun loadSettings() {
        val p = getSharedPreferences("OOXOO_V2", Context.MODE_PRIVATE)
        useRemoteServer = p.getBoolean("useRemote", false)
        serverUrl = p.getString("serverUrl", "") ?: ""
    }

    private fun getRealScreenMetrics(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopCapture()
        hideServerDialog()
        logDialogView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        controlView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        GemTemplateStore.release()
        ZeroMistakeEngine.resetTemporal()
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
