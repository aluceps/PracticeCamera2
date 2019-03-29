package me.aluceps.practicecamera2

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.support.v7.app.AppCompatActivity
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.util.*
import kotlin.collections.ArrayList

class CameraView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), CameraViewInterface {

    private var ratioWidth = 0
    private var ratioHeight = 0

    private fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be nagative.")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(w, h)
        } else {
            if (w < h * ratioWidth / ratioHeight) {
                setMeasuredDimension(w, w * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(h * ratioWidth / ratioHeight, h)
            }
        }
    }

    // 以下、カメラ機能

    private lateinit var activity: AppCompatActivity

    private val manager by lazy {
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun resume(activity: AppCompatActivity) {
        this.activity = activity
        startBackgroundThread()
        if (isAvailable) {
            openCamera()
        } else {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean = true
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                    openCamera()
                }
            }
        }
    }

    override fun pause() {
        closeCamera()
        stopBackgroundThread()
    }

    override fun requestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when {
            grantResults.isEmpty() ->
                debugLog("requestPermissionsResult: grantResults is empty")
            grantResults.any { it != PackageManager.PERMISSION_GRANTED } ->
                debugLog("requestPermissionsResult: ${grantResults.first()}")
            else ->
                if (requestCode == Permission.Camera.code) openCamera() else Unit
        }
    }

    override fun capture() {
    }

    private var cameraId: String = ""
    private var orientation: Int = 0
    private var flashSupport: Boolean = false

    private lateinit var previewSize: Size

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null

    private fun setupCamera() {
        manager.cameraIdList.forEach { cameraId ->
            val characteristics = manager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.LENS_FACING)?.let {
                if (it == CameraCharacteristics.LENS_FACING_FRONT) return@forEach
            }
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@forEach

            this.cameraId = cameraId
            this.orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            this.flashSupport = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            val largest = Collections.max(map.getOutputSizes(ImageFormat.JPEG).toList(), CompareSizesByArea())
            val swapped = areDimensionsSwapped(activity.windowManager.defaultDisplay.rotation)
            val displaySize = Point().apply { activity.windowManager.defaultDisplay.getSize(this) }

            imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)

            val rotateWidth = if (swapped) height else width
            val rotateHeight = if (swapped) width else height
            var maxWidth = if (swapped) displaySize.y else displaySize.x
            var maxHeight = if (swapped) displaySize.x else displaySize.y

            if (maxWidth > Resolution.FullHD.width) maxWidth = Resolution.FullHD.width
            if (maxHeight > Resolution.FullHD.height) maxHeight = Resolution.FullHD.height

            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                rotateWidth, rotateHeight, maxWidth, maxHeight, largest
            )

            if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                setAspectRatio(previewSize.width, previewSize.height)
            } else {
                setAspectRatio(previewSize.height, previewSize.width)
            }

            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        activity.requestPermission(Permission.Camera) {
            setupCamera()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    onDisconnected(camera)
                    activity.finish()
                }
            }, backgroundHandler)
        }
        activity.requestPermission(Permission.Audio)
        activity.requestPermission(Permission.WriteExternalStorage)
    }

    private fun createCameraPreviewSession() {
        try {
            cameraDevice?.let { device ->
                val texture = surfaceTexture.apply { setDefaultBufferSize(previewSize.width, previewSize.height) }
                val surface = Surface(texture)

                previewRequestBuilder =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }

                device.createCaptureSession(
                    listOf(surface, imageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                        }

                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                previewRequestBuilder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                previewRequest = previewRequestBuilder.build()
                                captureSession?.setRepeatingRequest(
                                    previewRequest,
                                    captureCallback,
                                    backgroundHandler
                                )
                            } catch (e: CameraAccessException) {
                                errorLog("onConfigured", e)
                            }
                        }
                    },
                    null
                )
            }
        } catch (e: CameraAccessException) {
            errorLog("createCameraPreviewSession", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        private fun process(result: CaptureResult) {
        }
    }

    private fun areDimensionsSwapped(displayRotation: Int): Boolean = when (displayRotation) {
        Surface.ROTATION_0, Surface.ROTATION_180 -> orientation == 90 || orientation == 270
        Surface.ROTATION_90, Surface.ROTATION_270 -> orientation == 0 || orientation == 180
        else -> {
            errorLog("Display rotation is invalid: $displayRotation")
            false
        }
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
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

        choices.forEach { option ->
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
            else -> choices.first()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            errorLog("stopBackgroundThread", e)
        }
    }
}