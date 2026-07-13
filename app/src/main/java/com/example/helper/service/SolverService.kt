package com.example.helper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.helper.R
import com.example.helper.ui.GridOverlayView

class SolverService : Service() {

    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var gridOverlayView: GridOverlayView? = null
    private lateinit var tvGridInfo: TextView
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var gridParams: WindowManager.LayoutParams

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isLogicEnabled = true
    private var isEditMode = false 

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)

            gridParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            gridOverlayView = inflater.inflate(R.layout.grid_overlay_layout, null) as GridOverlayView
            windowManager.addView(gridOverlayView, gridParams)

            panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0; y = 100
            }

            panelView = inflater.inflate(R.layout.overlay_layout, null)
            windowManager.addView(panelView, panelParams)
            
            val pView = panelView ?: return
            tvGridInfo = pView.findViewById(R.id.tvGridInfo)

            initControlPanelListeners(pView)
            setupAdvancedIndirectTouchListener() // ✨ 비가림 원격 드래그 매핑
            updateInfoText()

        } catch (e: Exception) { Log.e("SolverService", "초기화 에러", e) }
    }

    private fun initControlPanelListeners(view: View) {
        val layoutHeader = view.findViewById<LinearLayout>(R.id.layoutHeader)
        val layoutExpandedBody = view.findViewById<LinearLayout>(R.id.layoutExpandedBody)
        val btnMinimize = view.findViewById<Button>(R.id.btnMinimize)
        val btnKillService = view.findViewById<Button>(R.id.btnKillService)
        val btnToggleLogic = view.findViewById<Button>(R.id.btnToggleLogic)
        val btnEditMode = view.findViewById<Button>(R.id.btnEditMode)
        val btnInstantGridToggle = view.findViewById<Button>(R.id.btnInstantGridToggle)

        // 제어판 헤더 드래그 무빙
        var pInitialX = 0; var pInitialY = 0; var pTouchX = 0f; var pTouchY = 0f
        layoutHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pInitialX = panelParams.x; pInitialY = panelParams.y
                    pTouchX = event.rawX; pTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = pInitialX + (event.rawX - pTouchX).toInt()
                    panelParams.y = pInitialY + (event.rawY - pTouchY).toInt()
                    panelView?.let { windowManager.updateViewLayout(it, panelParams) }
                    true
                }
                else -> false
            }
        }

        // ✨ 즉시 격자 숨김/보임 토글 단추 로직
        btnInstantGridToggle.setOnClickListener {
            gridOverlayView?.let { gov ->
                gov.showGridLines = !gov.showGridLines
                if (gov.showGridLines) {
                    btnInstantGridToggle.text = "격자 ON"
                    btnInstantGridToggle.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                } else {
                    btnInstantGridToggle.text = "격자 OFF"
                    btnInstantGridToggle.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                }
                gov.invalidate()
            }
        }

        btnMinimize.setOnClickListener {
            if (layoutExpandedBody.visibility == View.VISIBLE) {
                layoutExpandedBody.visibility = View.GONE
                btnMinimize.text = "펼치기 ▼"
            } else {
                layoutExpandedBody.visibility = View.VISIBLE
                btnMinimize.text = "접기 ▲"
            }
            panelView?.let { windowManager.updateViewLayout(it, panelParams) }
        }

        btnEditMode.setOnClickListener {
            isEditMode = !isEditMode
            if (isEditMode) {
                btnEditMode.text = "조절 중 (화면 드래그)"
                btnEditMode.setBackgroundColor(android.graphics.Color.parseColor("#E91E63"))
                gridParams.flags = gridParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                btnEditMode.text = "격자 고정 상태"
                btnEditMode.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                gridParams.flags = gridParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            gridOverlayView?.let { windowManager.updateViewLayout(it, gridParams) }
        }

        btnToggleLogic.setOnClickListener {
            isLogicEnabled = !isLogicEnabled
            btnToggleLogic.text = if (isLogicEnabled) "로직: ON" else "로직: OFF"
            btnToggleLogic.setBackgroundColor(android.graphics.Color.parseColor(if (isLogicEnabled) "#4CAF50" else "#757575"))
        }

        btnKillService.setOnClickListener { stopSelf() }

        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView?.let { it.rows++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { gridOverlayView?.let { if(it.rows > 1) it.rows--; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView?.let { it.cols++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { gridOverlayView?.let { if(it.cols > 1) it.cols--; updateInfoText(); it.invalidate() } }
    }

    // ✨ [시야 무방해 원격 드래그 엔진]
    // 모서리를 손으로 직접 안 만져도 됩니다. 화면을 터치하면 가장 가까운 모서리가 타겟팅되어 움직입니다.
    private fun setupAdvancedIndirectTouchListener() {
        var lockedCorner = -1 // 0:TL, 1:TR, 2:BL, 3:BR
        var startStartX = 0f; var startStartY = 0f
        var origX = 0f; var origY = 0f

        gridOverlayView?.setOnTouchListener { _, event ->
            if (!isEditMode) return@setOnTouchListener false
            val gov = gridOverlayView ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startStartX = event.x; startStartY = event.y
                    // 누른 위치에서 제일 가까운 꼭짓점 매칭 알고리즘
                    val dTL = Math.hypot((startStartX - gov.tlX).toDouble(), (startStartY - gov.tlY).toDouble())
                    val dTR = Math.hypot((startStartX - gov.trX).toDouble(), (startStartY - gov.trY).toDouble())
                    val dBL = Math.hypot((startStartX - gov.blX).toDouble(), (startStartY - gov.blY).toDouble())
                    val dBR = Math.hypot((startStartX - gov.brX).toDouble(), (startStartY - gov.brY).toDouble())

                    val minVal = listOf(dTL, dTR, dBL, dBR).minOrNull() ?: return@setOnTouchListener false
                    lockedCorner = listOf(dTL, dTR, dBL, dBR).indexOf(minVal)

                    // 잠금된 모서리의 원래 위치 확보
                    when(lockedCorner) {
                        0 -> { origX = gov.tlX; origY = gov.tlY }
                        1 -> { origX = gov.trX; origY = gov.trY }
                        2 -> { origX = gov.blX; origY = gov.blY }
                        3 -> { origX = gov.brX; origY = gov.brY }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lockedCorner == -1) return@setOnTouchListener false
                    val dx = event.x - startStartX
                    val dy = event.y - startStartY

                    // 손가락 이동량 변위만큼 원격으로 오프셋 연산 적용
                    when(lockedCorner) {
                        0 -> { gov.tlX = origX + dx; gov.tlY = origY + dy }
                        1 -> { gov.trX = origX + dx; gov.trY = origY + dy }
                        2 -> { gov.blX = origX + dx; gov.blY = origY + dy }
                        3 -> { gov.brX = origX + dx; gov.brY = origY + dy }
                    }
                    gov.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lockedCorner = -1
                    true
                }
                else -> false
            }
        }
    }

    private fun updateInfoText() {
        gridOverlayView?.let { tvGridInfo.text = "크기: ${it.rows}행 x ${it.cols}열" }
    }

    // ... (이하 온스타트커맨드 및 이미지캡처 루프 생략 - 이전과 동일) ...
}
