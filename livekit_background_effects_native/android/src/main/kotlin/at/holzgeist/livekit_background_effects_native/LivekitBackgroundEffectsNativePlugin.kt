package at.holzgeist.livekit_background_effects_native

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ProcessLifecycleOwner
import com.cloudwebrtc.webrtc.CameraCapturerUtils
import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin
import com.cloudwebrtc.webrtc.utils.EglUtils
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.webrtc.CameraXHelper
import org.webrtc.EglBase
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt


/** LivekitBackgroundEffectsNativePlugin */
@OptIn(ExperimentalCamera2Interop::class)
class LivekitBackgroundEffectsNativePlugin : FlutterPlugin, MethodCallHandler {
    private val TAG = "Blurring Plugin"

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    private lateinit var assetManager: AssetManager
    private lateinit var flutterAssets: FlutterPlugin.FlutterAssets
    private lateinit var imageAnalysis: ImageAnalysis
    private val imageAnalyzer: ImageAnalyser = ImageAnalyser()

    private lateinit var segmenter: ImageSegmenter
    private val lock: Lock = ReentrantLock()


    private var processors: MutableMap<Int, VirtualBackgroundVideoProcessor> = HashMap()
    private var tracks: MutableMap<Int, String> = HashMap()
    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null


    private inner class ImageAnalyser : ImageAnalysis.Analyzer {
        val latch = Semaphore(1, true)

        @OptIn(
            ExperimentalGetImage::class, ExperimentalStdlibApi::class
        )
        override fun analyze(imageProxy: ImageProxy) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                imageProxy.close()
                return;
            }

            val image = imageProxy.image

            lock.lock()
            var enabled: Boolean
            try {
                enabled = processors.values.any { it.enabled }
            } finally {
                lock.unlock()
            }

            if (enabled && image != null) {
                if (!latch.tryAcquire()) {
                    imageProxy.close()
                    return
                }

                val bitmapBuffer : Bitmap;

                imageProxy.use {
                    bitmapBuffer = imageProxy.toBitmap()
                }
                segmenter.segmentAsync(
                    BitmapImageBuilder(bitmapBuffer).build(),
                    SystemClock.uptimeMillis()
                )

                latch.acquire()
                latch.release()
            }

            imageProxy.close()
        }

        @RequiresApi(Build.VERSION_CODES.N)
        fun handleResult(result: ImageSegmenterResult, image: MPImage) {
            val mask = result.confidenceMasks().get()[0]
            val maskBuffer= ByteBufferExtractor.extract(mask)
            val converted = ByteBuffer.allocateDirect(mask.width * mask.height)
                    val originalData = FloatArray(mask.width * mask.height)
            maskBuffer.asFloatBuffer().get(originalData)
                    val convertedData =
                        originalData.asIterable().map { (it * 255).roundToInt().toByte() }
                            .toByteArray()
                    converted.put(convertedData)
                    converted.rewind()

                    val holder = VirtualBackgroundTransformer.MaskHolder(
                        mask.width,
                        mask.height,
                        converted,
                    )

                    lock.lock()
                    try {
                        processors.values.forEach { it.lastMask = holder }
                    } finally {
                        lock.unlock()
                    }
                    latch.release()
        }

        fun handleError(result: RuntimeException) {
            latch.release()
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.i(TAG, "attach to engine")
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "livekit_background_effects")
        channel.setMethodCallHandler(this)
        assetManager = flutterPluginBinding.getApplicationContext().getAssets()
        flutterAssets = flutterPluginBinding.getFlutterAssets()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val modelPath = flutterAssets.getAssetFilePathByName(
                "assets/models/selfie_segmenter.tflite",
                "livekit_background_effects_platform_interface"
            )
            val modelStream = assetManager.open(modelPath)
            val length = modelStream.available()
            val modelBuffer = ByteBuffer.allocateDirect(length)
            val readBuffer = ByteArray(1024 * 128)
            while (modelStream.available() > 0) {
                val read = modelStream.read(readBuffer)
                modelBuffer.put(readBuffer, 0, read)
            }
            modelBuffer.rewind()
            val options = ImageSegmenter.ImageSegmenterOptions.builder().setBaseOptions(
                BaseOptions.builder().setModelAssetBuffer(
                    modelBuffer
                ).setDelegate(Delegate.CPU).build()
            ).setRunningMode(RunningMode.LIVE_STREAM).setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .setResultListener { result, input ->
                    imageAnalyzer.handleResult(result, input)
                }
                .setErrorListener { e -> imageAnalyzer.handleError(e) }
                .build()
            segmenter =
                ImageSegmenter.createFromOptions(
                    flutterPluginBinding.getApplicationContext(),
                    options
                )
        }

        imageAnalysis = ImageAnalysis.Builder().build()
        imageAnalysis.setAnalyzer(Dispatchers.IO.asExecutor(), imageAnalyzer)
        cameraProvider =
            CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get(), arrayOf(imageAnalysis))
        if (cameraProvider?.isSupported(flutterPluginBinding.getApplicationContext()) == true) {
            CameraCapturerUtils.registerCameraProvider(cameraProvider!!)
        } else {
            cameraProvider = null
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.i(TAG, "call to ${call.method} with camera provider: ${cameraProvider != null}")
        if (call.method == "initVideoBlurring") {
            initVideoBlurring(call, result)
        } else if (call.method == "destroy") {
            destroy(call, result)
        } else if (call.method == "restart") {
            restart(call, result)
        } else if (call.method == "updateBackground") {
            updateBackground(call, result)
        } else if (call.method == "blurringSupported") {
            result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } else if (call.method == "onPublish") {
            result.success(null)
        } else if (call.method == "onUnpublish") {
            result.success(null)
        } else {
            Log.e(TAG, "call to " + call.method + " not supported")
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        if (cameraProvider != null) {
            CameraCapturerUtils.unregisterCameraProvider(cameraProvider!!)
        }
        processors.values.forEach { it.dispose() }
    }

    private fun initVideoBlurring(call: MethodCall, result: Result) {
        val flutterWebRTCPlugin = FlutterWebRTCPlugin.sharedSingleton
        val trackId = call.argument<String>("trackId")
        if (trackId == null) {
            result.error("INVALID_ARGUMENT", "trackId is required", null)
            return
        }
        val processorId = call.argument<Int>("processorId")
        if (processorId == null) {
            result.error("INVALID_ARGUMENT", "processorId is required", null)
            return
        }
        if (processors.containsKey(processorId)) {
            result.error("INVALID_STATE", "processorId is already used", null)
            return
        }
        val track = flutterWebRTCPlugin.getLocalTrack(trackId)
        if (track == null) {
            result.error("INVALID_STATE", "track not found", null)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            result.error("INVALID_OPERATION", "This Android version is not supported", null)
            return
        }

        val modelPath = flutterAssets.getAssetFilePathByName(
            "assets/models/selfie_segmenter.tflite", "livekit_background_effects_platform_interface"
        )
        val modelStream = assetManager.open(modelPath)

        val byteArray = inputStreamToByteArray(modelStream)
        val byteBuffer = ByteBuffer.allocateDirect(byteArray.size)
        byteBuffer.put(byteArray)
        val processor = VirtualBackgroundVideoProcessor(EglBase.create(EglUtils.getRootEglBaseContext()))
        processor.onCapturerStarted(true)
        processor.enabled = true
        lock.lock()
        try {
            processors.put(processorId, processor)
        } finally {
            lock.unlock()
        }
        tracks.put(processorId, trackId)
        (track as LocalVideoTrack).addProcessor(processor)

        result.success(null)
    }

    private fun inputStreamToByteArray(modelStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()

        var nRead: Int
        val data = ByteArray(4096)

        while ((modelStream.read(data, 0, data.size).also { nRead = it }) != -1) {
            buffer.write(data, 0, nRead)
        }

        buffer.flush()
        val byteArray = buffer.toByteArray()
        return byteArray
    }

    private fun destroy(call: MethodCall, result: Result) {
        val flutterWebRTCPlugin = FlutterWebRTCPlugin.sharedSingleton
        val processorId = call.argument<Int>("processorId")
        if (processorId == null) {
            result.error("INVALID_ARGUMENT", "processorId is required", null)
            return
        }
        if (!processors.containsKey(processorId)) {
            result.error("INVALID_STATE", "processorId not found", null)
            return
        }
        val trackId = tracks.get(processorId)
        if (trackId == null) {
            result.error("INVALID_STATE", "trackId not found", null)
            return
        }
        val track = flutterWebRTCPlugin.getLocalTrack(trackId)
        val processor = processors.get(processorId)
        if (processor == null) {
            result.error("INVALID_STATE", "processor not found", null)
            return

        }
        // the track might be destroyed before the processor
        if (track != null) {
            (track as LocalVideoTrack).removeProcessor(processor)
        }
        lock.lock()
        try {
            processors.remove(processorId)
        } finally {
            lock.unlock()
        }
        tracks.remove(processorId)

        processor.onCapturerStopped()

        result.success(null)

    }

    private fun restart(call: MethodCall, result: Result) {
        result.success(null)
    }

    private fun updateBackground(call: MethodCall, result: Result) {
        val processorId = call.argument<Int>("processorId")
        if (processorId == null) {
            result.error("INVALID_ARGUMENT", "processorId is required", null)
            return
        }
        if (!processors.containsKey(processorId)) {
            result.error("INVALID_STATE", "processorId not found", null)
            return
        }
        val processor = processors[processorId]
        if (processor == null) {
            result.error("INVALID_STATE", "processor not found", null)
            return

        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            result.error("INVALID_OPERATION", "This Android version is not supported", null)
            return
        }


        val virtualBackgroundAssetId = call.argument<String>("virtualBackgroundAssetId")
        val virtualBackgroundPackage = call.argument<String>("virtualBackgroundPackage")
        val blurRadius = call.argument<Int>("blurRadius")
        if (virtualBackgroundAssetId != null) {
            val backgroundPath =
                if (virtualBackgroundPackage == null) flutterAssets.getAssetFilePathByName(
                    virtualBackgroundAssetId
                ) else flutterAssets.getAssetFilePathByName(
                    virtualBackgroundAssetId, virtualBackgroundPackage
                )
            val imageSource = ImageDecoder.createSource(assetManager, backgroundPath)
            val backgroundBitmap =
                ImageDecoder.decodeBitmap(imageSource).copy(Bitmap.Config.ARGB_8888, false)
            processor.backgroundImage = backgroundBitmap
            processor.blurRadius = null
            processor.enabled = true
        } else if (blurRadius != null) {
            processor.backgroundImage = null
            processor.blurRadius = blurRadius
            processor.enabled = true
        } else {
            processor.backgroundImage = null
            processor.blurRadius = null
            processor.enabled = false
        }
        result.success(null)
    }
}
