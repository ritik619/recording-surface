package com.yoyo.multigltfpoc

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.*
import com.google.android.filament.utils.*
import java.nio.ByteBuffer
import android.util.Log
import java.nio.channels.Channels


class MainActivity : AppCompatActivity() {

    companion object {
        init {
            Utils.init()

        }
    }


    private lateinit var surfaceView: SurfaceView
    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: NewModelViewer
    private val viewerContent = AutomationEngine.ViewerContent()
    private val automation = AutomationEngine()

    // Other Filament objects:
    private lateinit var material: Material
    private lateinit var materialInstance: MaterialInstance


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.gc()

        surfaceView = SurfaceView(this).apply { setContentView(this) }
        choreographer = Choreographer.getInstance()
        modelViewer = NewModelViewer(surfaceView)
        makeTransparentBackground()
        surfaceView.setOnTouchListener(modelViewer)
        setUpRenderSettings()
        loadGlb("mbap.glb","room.glb")
        createNeutralIndirectLight()

    }
    private fun readCompressedAsset(assetName: String): ByteBuffer {
        val input = assets?.open(assetName)
        val bytes = input?.let { ByteArray(it.available()) }
        input?.read(bytes)
        return ByteBuffer.wrap(bytes)
    }



    private fun makeTransparentBackground() {
        surfaceView.setBackgroundColor(Color.TRANSPARENT)
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        modelViewer.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT
        modelViewer.scene.skybox = null
        val options = modelViewer.renderer.clearOptions
        options.clear = true
        modelViewer.renderer.clearOptions = options
    }

    private fun createNeutralIndirectLight() {
        val engine = modelViewer.engine
        readCompressedAsset("test_ibl.ktx").let {
            modelViewer.scene.indirectLight = KTX1Loader.createIndirectLight(engine, it)
            modelViewer.scene.indirectLight!!.intensity = 40_000.0f
            viewerContent.indirectLight = modelViewer.scene.indirectLight
        }


    }

    private val frameCallback = object : Choreographer.FrameCallback {
        private val startTime = System.nanoTime()
        override fun doFrame(currentTime: Long) {
            val seconds = (currentTime - startTime).toDouble() / 1_000_000_000
            choreographer.postFrameCallback(this)
            modelViewer.animatorForModel?.apply {
                if (animationCount > 0) {
                    applyAnimation(0, seconds.toFloat())
                }
                updateBoneMatrices()

            }
            modelViewer.render(currentTime)
            modelViewer.assetForModel?.apply {
                modelViewer.transformToUnitCubeForModel()
            }
            modelViewer.assetForRoom?.apply {
                //modelViewer.transformToUnitCubeForRoom()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
        modelViewer.destroyObjects()
        System.gc()
    }

    private fun loadGlb(glbName1: String , glbName2: String) {

        val bufferForModel =  assets?.open(glbName1).use { input ->
            val bytes = input?.let { ByteArray(it.available()) }
            input?.read(bytes)
            ByteBuffer.wrap(bytes)
        }

        val bufferForRoom =  assets?.open(glbName2).use { input ->
            val bytes = input?.let { ByteArray(it.available()) }
            input?.read(bytes)
            ByteBuffer.wrap(bytes)
        }
        modelViewer.loadModelGltfAsyncForRoom(bufferForRoom) { uri -> readCompressedAsset("$uri") }
        modelViewer.loadModelGltfAsyncForModel(bufferForModel) { uri -> readCompressedAsset("$uri") }
        updateRootTransform()
    }

    private fun updateRootTransform() {
        if (automation.viewerOptions.autoScaleEnabled) {
            modelViewer.transformToUnitCubeForRoom()
            modelViewer.transformToUnitCubeForModel()
        } else {
            modelViewer.clearRootTransformForModel()
            modelViewer.clearRootTransformForRoom()
        }
    }


    private fun setUpRenderSettings() {
        val view = modelViewer.view
        // on mobile, better use lower quality color buffer
        view.renderQuality = view.renderQuality.apply {
            hdrColorBuffer = com.google.android.filament.View.QualityLevel.LOW
        }
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = true
            quality = com.google.android.filament.View.QualityLevel.LOW
        }

        /*      // dynamic resolution often helps a lot
               view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
                   enabled = true
                   quality = com.google.android.filament.View.QualityLevel.LOW
               }
              //
              view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
                  enabled = true
              }*/
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = true
        }
        // MSAA is needed with dynamic resolution MEDIUM
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = true
        }

        // FXAA is pretty cheap and helps a lot
        view.antiAliasing = com.google.android.filament.View.AntiAliasing.FXAA

        // ambient occlusion is the cheapest effect tht adds a lot of qualitya
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = true
        }
        // bloom is pretty expensive but adds a fair amount of realism
        view.bloomOptions = view.bloomOptions.apply {
            enabled = true
        }

    }

}