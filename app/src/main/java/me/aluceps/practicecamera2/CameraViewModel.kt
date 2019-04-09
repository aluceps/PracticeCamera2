package me.aluceps.practicecamera2

import android.Manifest
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.PermissionChecker
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import java.io.File
import java.lang.Long.signum
import java.util.*

internal class CompareSizesByArea : Comparator<Size> {
    override fun compare(o1: Size, o2: Size): Int =
        signum(o1.width.toLong() * o1.height - o2.width.toLong() * o2.height)
}

interface CameraViewInterface {
    fun resume(activity: AppCompatActivity? = null)
    fun pause()
    fun requestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    fun captureImage(file: File)
    fun unlock(): Boolean
    fun captureVideo(file: File)
    fun stopCaptureVideo()
    var state: State.Camera
}

sealed class State {
    sealed class Camera {
        object Preview : Camera()
        object WaitingLock : Camera()
        object WaitingPrecapture : Camera()
        object WaitingNonPrecapture : Camera()
        object PictureTaken : Camera()
    }
}

sealed class Permission {
    object Camera : Permission() {
        override val manifest: String = Manifest.permission.CAMERA
        override val code: Int = 0x001
    }

    object Audio : Permission() {
        override val manifest: String = Manifest.permission.RECORD_AUDIO
        override val code: Int = 0x002
    }

    object WriteExternalStorage : Permission() {
        override val manifest: String = Manifest.permission.WRITE_EXTERNAL_STORAGE
        override val code: Int = 0x003
    }

    abstract val manifest: String
    abstract val code: Int
}

fun AppCompatActivity.requestPermission(permission: Permission, granted: (() -> Unit)? = null) {
    if (PermissionChecker.checkSelfPermission(this, permission.manifest) != PackageManager.PERMISSION_GRANTED) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission.manifest)) {
            debugLog("requestPermission: ${permission.manifest}")
        } else {
            ActivityCompat.requestPermissions(this, listOf(permission.manifest).toTypedArray(), permission.code)
        }
    } else {
        granted?.invoke()
    }
}

enum class Resolution(val width: Int, val height: Int) {
    FullHD(1920, 1080)
}

fun Array<Size>.chooseOptimalSize(
    viewWidth: Int,
    viewHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    aspectRatio: Size
): Size {
    val bigEnough = ArrayList<Size>()
    val notBigEnough = ArrayList<Size>()
    val w = aspectRatio.width
    val h = aspectRatio.height

    forEach { option ->
        if (option.width <= maxWidth &&
            option.height <= maxHeight &&
            option.height <= option.width * h / w
        ) {
            if (option.width >= viewWidth && option.height >= viewHeight) {
                bigEnough.add(option)
            } else {
                notBigEnough.add(option)
            }
        }
    }

    return when {
        bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
        notBigEnough.size > 0 -> Collections.max(notBigEnough, CompareSizesByArea())
        else -> first()
    }
}

fun debugLog(message: String) {
    if (BuildConfig.DEBUG) Log.d("CameraView", message)
}

fun errorLog(message: String) {
    if (BuildConfig.DEBUG) Log.e("CameraView", message)
}

fun errorLog(message: String, throwable: Throwable) {
    if (BuildConfig.DEBUG) Log.e("CameraView", message, throwable)
}
