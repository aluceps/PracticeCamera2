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
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v7.app.AppCompatActivity
import android.util.AttributeSet
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    private lateinit var activity: AppCompatActivity

    private val manager by lazy {
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override var state: State.Camera = State.Camera.Preview

    override val tempPath by lazy {
        File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/PracticeCamera2/").also { dir ->
            if (!dir.exists()) dir.mkdir()
        }.absolutePath
    }

    override fun resume(activity: AppCompatActivity?) {
        if (activity != null) {
            this.activity = activity
        }
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
        lockFocus()
    }

    override fun unlock() {
        if (isRecordingVideo) {
            stopCaptureVideo()
        } else {
            unLockFocus()
        }
    }

    override fun captureVideo() {
        recordingVideo()
    }

    override fun stopCaptureVideo() {
        isRecordingVideo = false
        debugLog("stopCaptureVideo")
        mediaRecorder?.apply {
            stop()
            reset()
        }
        closePreviewSession()
        createCameraPreviewSession()
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
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        backgroundHandler?.post {
            it.acquireNextImage().use { image ->
                val buffer = image.planes.first().buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream("$tempPath/temp_image.jpg").use { stream ->
                    stream.write(bytes)
                }
            }
        }
    }

    private var mediaRecorder: MediaRecorder? = null

    private val defaultOrientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }

    private val inverseOrientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }

    private var isRecordingVideo = false

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

            imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            val rotateWidth = if (swapped) height else width
            val rotateHeight = if (swapped) width else height
            var maxWidth = if (swapped) displaySize.y else displaySize.x
            var maxHeight = if (swapped) displaySize.x else displaySize.y

            if (maxWidth > Resolution.FullHD.width) maxWidth = Resolution.FullHD.width
            if (maxHeight > Resolution.FullHD.height) maxHeight = Resolution.FullHD.height

            previewSize = map.getOutputSizes(SurfaceTexture::class.java).chooseOptimalSize(
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
                    closeCameraDevice()
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
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                previewRequestBuilder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                previewRequest = previewRequestBuilder.build()
                                preview()
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
        ) = process(result)

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) = process(partialResult)

        private fun process(result: CaptureResult) {
            when (state) {
                is State.Camera.WaitingLock -> capturePicture(result)
                is State.Camera.WaitingPrecapture -> precapture(result)
                is State.Camera.WaitingNonPrecapture -> nonPrecapture(result)
                else -> Unit
            }
        }

        private fun precapture(result: CaptureResult) {
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            if (ae == null ||
                ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            ) {
                state = State.Camera.WaitingNonPrecapture
                debugLog("precapture: state=$state")
            }
        }

        private fun nonPrecapture(result: CaptureResult) {
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            if (ae == null ||
                ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE
            ) {
                state = State.Camera.PictureTaken
                debugLog("nonPrecapture: state=$state")
                captureStillPicture()
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (af == null) {
                state = State.Camera.PictureTaken
                debugLog("capturePicture: 0 state=$state")
                captureStillPicture()
            } else {
                if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (ae == null ||
                        ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    ) {
                        state = State.Camera.PictureTaken
                        debugLog("capturePicture: 1 state=$state")
                        captureStillPicture()
                    } else {
                        runPrecaptureSequence()
                    }
                }
            }
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

    private fun closeCamera() {
        closePreviewSession()
        closeCameraDevice()
        closeImageReader()
        closeMediaRecorder()
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun closeCameraDevice() {
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun closeImageReader() {
        imageReader?.close()
        imageReader = null
    }

    private fun closeMediaRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
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

    private fun runPrecaptureSequence() {
        try {
            startPrecapture()
            state = State.Camera.WaitingPrecapture
            debugLog("runPrecaptureSequence: state=$state")
            captureSession?.capture(
                previewRequestBuilder.build(),
                captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            errorLog("runPrecaptureSequence", e)
        }
    }

    private fun captureStillPicture() {
        try {
            if (cameraDevice == null) return
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(imageReader!!.surface)
                set(
                    CaptureRequest.JPEG_ORIENTATION,
                    defaultOrientations.get(activity.windowManager.defaultDisplay.rotation)
                )
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) = Unit
            }
            captureBuilder?.build()?.let { request ->
                captureSession?.apply {
                    stopRepeating()
                    abortCaptures()
                    capture(request, captureCallback, null)
                }
            }
        } catch (e: CameraAccessException) {
            errorLog("captureStillPicture", e)
        }
    }

    private fun lockFocus() {
        try {
            startAutoFocus()
            state = State.Camera.WaitingLock
            debugLog("lockFocus: state=$state")
            requestCapture()
        } catch (e: CameraAccessException) {
            errorLog("lockFocus", e)
        }
    }

    private fun unLockFocus() {
        try {
            cancelAutoFocus()
            requestCapture()
            state = State.Camera.Preview
            debugLog("unLockFocus: state=$state")
            preview()
        } catch (e: CameraAccessException) {
            errorLog("lockFocus", e)
        }
    }

    private fun recordingVideo() {
        cameraDevice?.let { device ->
            try {
                closePreviewSession()
                setupMediaRecorder()

                val texture = surfaceTexture.apply { setDefaultBufferSize(previewSize.width, previewSize.height) }
                val previewSurface = Surface(texture)
                val recorderSurface = mediaRecorder!!.surface
                val surfaces = ArrayList<Surface>().apply {
                    add(previewSurface)
                    add(recorderSurface)
                }

                previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(recorderSurface)
                }

                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                        activity.runOnUiThread {
                            debugLog("recordingVideo")
                            isRecordingVideo = true
                            mediaRecorder?.start()
                        }
                    }
                }, backgroundHandler)
            } catch (e: CameraAccessException) {
                errorLog("recordingVideo", e)
            } catch (e: IOException) {
                errorLog("recordingVideo", e)
            }
        }
    }

    private fun setupMediaRecorder() {
        mediaRecorder = MediaRecorder()

        val rotation = activity.windowManager.defaultDisplay.rotation
        when (orientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(defaultOrientations.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(inverseOrientations.get(rotation))
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile("$tempPath/temp_video.mp4")
            setVideoEncodingBitRate(RECORD_ENCODING_BIT_RATE)
            setVideoFrameRate(RECORD_FRAME_RATE)
            setVideoSize(previewSize.width, previewSize.height)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            prepare()
        }
    }

    private fun preview() {
        captureSession?.setRepeatingRequest(
            previewRequest,
            captureCallback,
            backgroundHandler
        )
    }

    private fun updatePreview() {
        try {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO
            )
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            errorLog("updatePreview", e)
        }
    }

    private fun requestCapture() {
        captureSession?.capture(
            previewRequestBuilder.build(),
            captureCallback,
            backgroundHandler
        )
    }

    private fun startPrecapture() {
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
        )
    }

    private fun startAutoFocus() {
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CameraMetadata.CONTROL_AF_TRIGGER_START
        )
    }

    private fun cancelAutoFocus() {
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
        )
    }

    companion object {
        private const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
        private const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
        private const val RECORD_ENCODING_BIT_RATE = 12000000
        private const val RECORD_FRAME_RATE = 30
    }
}