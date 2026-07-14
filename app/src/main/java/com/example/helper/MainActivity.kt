package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// 🎯 하위 폴더에 있는 SolverService 임포트
import com.example.helper.service.SolverService
import com.example.helper.R

class MainActivity : AppCompatActivity() {

    private val TAG = "GridHelper_Main"
    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY_PERMISSION = 1002 
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStart = findViewById<Button>(R.id.btnStartService)
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                startScreenCaptureRequest()
            }
        }
    }

    private fun startScreenCaptureRequest() {
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            val errorMsg = "화면 캡처 의도(Intent) 생성 실패: ${e.message}"
            Log.e(TAG, errorMsg, e)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한이 허용되었습니다!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "오버레이 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        } 
        else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            // 🎯 권한 승인 성공
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "화면 공유 권한 승인 성공. 서비스 시작 시도.")
                
                val serviceIntent = Intent(this, SolverService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Toast.makeText(this, "분석 엔진 기동 중...", Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                } catch (e: Exception) {
                    val errorMsg = "서비스 시작 실패 (Manifest 설정 확인 필요): ${e.message}"
                    Log.e(TAG, errorMsg, e)
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }

            } else {
                // 🎯 사용자가 거부했거나 에러가 났을 때 에러 코드를 상세히 표시
                val errorMsg = "화면 공유 권한이 거부되었습니다. (결과 코드: $resultCode)"
                Log.e(TAG, errorMsg)
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
