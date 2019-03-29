package me.aluceps.practicecamera2

import android.Manifest
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.PermissionChecker
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import java.lang.Long.signum

internal class CompareSizesByArea : Comparator<Size> {
    override fun compare(o1: Size, o2: Size): Int =
        signum(o1.width.toLong() * o1.height - o2.width.toLong() * o2.height)
}

interface CameraViewInterface {
    fun resume(activity: AppCompatActivity)
    fun pause()
    fun requestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    fun capture()
}

enum class Permission(val manifest: String, val code: Int) {
    Camera(Manifest.permission.CAMERA, 0x001),
    Audio(Manifest.permission.RECORD_AUDIO, 0x002),
    WriteExternalStorage(Manifest.permission.WRITE_EXTERNAL_STORAGE, 0x003)
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

enum class CameraState {
    Preview,
    WaitingLock,
    WaitingPrecapture,
    WaitingNonPrecapture,
    PictureTaken,
}

enum class Resolution(val width: Int, val height: Int) {
    FullHD(1920, 1080)
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

