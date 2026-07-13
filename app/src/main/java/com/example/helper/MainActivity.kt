package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.helper.service.SolverService

// 🎯 깃허브 컴파일러 레이아웃 인식용 R 클래스 임포트
import com.example.helper.R

class MainActivity : AppCompatActivity() {

    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY_PERMISSION = 1002 // 🎯 오버레이 권한 요청 코드 추가
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStart = findViewById<Button>(R.id.btnStartService)
        btnStart.setOnClickListener {
            // 🎯 [핵심] 다른 앱 위에 표시 권한이 있는지 선제적으로 체크합니다.
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
                
                // 유저를 해당 앱의 시스템 권한 설정 화면으로 강제 이동시킵니다.
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                // 이미 권한이 있다면 기존대로 화면 공유 요청 단계로 진입합니다.
                startScreenCaptureRequest()
            }
        }
    }

    /**
     * 화면 공유(미디어 프로젝션) 권한 팝업을 띄우는 함수
     */
    private fun startScreenCaptureRequest() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 🎯 케이스 1: 시스템 설정창에서 오버레이 권한을 주고 돌아왔을 때
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "권한이 허용되었습니다! 다시 버튼을 누르면 시작합니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "권한이 거부되어 서비스를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } 
        
        // 케이스 2: 화면 공유(지금 시작) 승인 팝업 결과 처리
        else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                
                val serviceIntent = Intent(this, SolverService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this, "분석기가 활성화되었습니다. 게임을 켜주세요.", Toast.LENGTH_SHORT).show()

                // 권한 수락 완료 즉시 앱을 백그라운드로 내려 게임 화면 노출
                moveTaskToBack(true)

            } else {
                Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
