package me.aluceps.practicecamera2

import android.annotation.SuppressLint
import android.databinding.DataBindingUtil
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import me.aluceps.practicecamera2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.action.setOnClickListener {
            Toast.makeText(it.context, "写真", Toast.LENGTH_SHORT).show()
            binding.cameraView.captureImage(createTempFile("temp_image", ".jpg"))
        }
        binding.action.setOnLongClickListener {
            Toast.makeText(it.context, "動画", Toast.LENGTH_SHORT).show()
            binding.cameraView.captureVideo(createTempFile("temp_video", ".mp4"))
            true
        }
        binding.action.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                binding.cameraView.unlock()
            } else {
                false
            }
        }
        binding.facing.setOnClickListener {
            binding.cameraView.facing()
        }
        binding.flash.setOnClickListener {
            binding.cameraView.toggleFlash()
        }
        binding.close.setOnClickListener {
//            binding.cameraView.unlock()
        }
    }

    override fun onStart() {
        super.onStart()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    override fun onResume() {
        super.onResume()
        binding.cameraView.resume(this)
    }

    override fun onPause() {
        binding.cameraView.pause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        binding.cameraView.requestPermissionsResult(requestCode, permissions, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
