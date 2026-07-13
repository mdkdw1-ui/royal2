package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.helper.service.SolverService

// 🎯 [핵심 수정] 깃허브 컴파일러가 레이아웃을 찾지 못하는 문제를 해결하기 위해 R 클래스를 강제 명시 임포트합니다.
import com.example.helper.R

class MainActivity : AppCompatActivity() {

    private val REQUEST_MEDIA_PROJECTION = 1001
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🎯 R.layout.activity_main 참조 확인
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 🎯 R.id.btnStartService 참조 확인
        val btnStart = findViewById<Button>(R.id.btnStartService)
        btnStart.setOnClickListener {
            // 화면 공유 권한 요청 팝업 띄우기
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                
                // 1. 서비스에 권한 토큰을 실어서 실행
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

                // 권한을 받자마자 이 앱을 백그라운드로 내려서 게임 화면이 보이도록 처리
                moveTaskToBack(true)

            } else {
                Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
