/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package me.instahelp.livekit_background_effects_native

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin
import com.cloudwebrtc.webrtc.utils.EglUtils
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer


/** LivekitBackgroundEffectsNativePlugin */
class LivekitBackgroundEffectsNativePlugin : FlutterPlugin, MethodCallHandler {
    private val TAG = "Blurring Plugin"

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    private lateinit var assetManager: AssetManager
    private lateinit var flutterAssets: FlutterPlugin.FlutterAssets
    private lateinit var applicationContext: Context

    /** The segmentation model, loaded once; each processor gets its own view of it. */
    private var modelBuffer: ByteBuffer? = null

    // Only accessed from the platform (main) thread.
    private var processors: MutableMap<Int, VirtualBackgroundVideoProcessor> = HashMap()
    private var tracks: MutableMap<Int, String> = HashMap()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.i(TAG, "attach to engine")
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "livekit_background_effects")
        channel.setMethodCallHandler(this)
        assetManager = flutterPluginBinding.getApplicationContext().getAssets()
        flutterAssets = flutterPluginBinding.getFlutterAssets()
        applicationContext = flutterPluginBinding.getApplicationContext()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.i(TAG, "call to ${call.method}")
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
        processors.values.forEach { it.dispose() }
        processors.clear()
        tracks.clear()
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

        val processor = VirtualBackgroundVideoProcessor(
            EglUtils.getRootEglBaseContext(),
            applicationContext,
            loadModel().duplicate(),
        )
        processor.enabled = true
        processors.put(processorId, processor)
        tracks.put(processorId, trackId)
        (track as LocalVideoTrack).addProcessor(processor)

        result.success(null)
    }

    private fun loadModel(): ByteBuffer {
        modelBuffer?.let { return it }
        val modelPath = flutterAssets.getAssetFilePathByName(
            "assets/models/selfie_segmenter.tflite", "livekit_background_effects_platform_interface"
        )
        val byteArray = assetManager.open(modelPath).use { inputStreamToByteArray(it) }
        val buffer = ByteBuffer.allocateDirect(byteArray.size)
        buffer.put(byteArray)
        buffer.rewind()
        modelBuffer = buffer
        return buffer
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
        processors.remove(processorId)
        tracks.remove(processorId)

        // Non-blocking: GL teardown happens on the processor's own thread.
        processor.dispose()

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
