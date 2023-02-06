package com.yoyo.multigltfpoc

import android.annotation.SuppressLint
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.*
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.utils.*
import java.nio.ByteBuffer
import com.google.android.filament.filamat.MaterialBuilder

import java.nio.FloatBuffer


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
    private var materialInstance: MaterialInstance?=null
    private val textures = arrayOfNulls<Texture>(2)



    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.gc()

        surfaceView = SurfaceView(this).apply { setContentView(this) }

        choreographer = Choreographer.getInstance()
        modelViewer = NewModelViewer(surfaceView)
        makeTransparentBackground()
        surfaceView.setOnTouchListener { _, event ->
            onTouchEvent(event)
            true
        }

        surfaceView.setOnTouchListener(modelViewer)
        setUpRenderSettings()
        loadGlb("mbap.glb","room.glb")
        createNeutralIndirectLight()
        test()
//        textures[0] = loadTexture(modelViewer.engine, R.drawable.dog)
//        textures[1] = loadTexture(modelViewer.engine, R.drawable.cat)
//        setTextures(textures[0]!!, textures[1]!!)
//        addTexture()
//        modelViewer.assetForRoom!!.entities.forEach {
//            val a=it
//            materialInstance?.let {
//            modelViewer.assetForModel!!.engine.renderableManager.setMaterialInstanceAt(a,0,
//                it
//            )
//        }
//        }

//
//        materialInstance?.let {
//            modelViewer.assetForModel!!.engine.renderableManager.setMaterialInstanceAt(204,0,
//                it
//            )
//        }
//        materialInstance?.let {
//            modelViewer.assetForModel!!.engine.renderableManager.setMaterialInstanceAt(203,0,
//                it
//            )
//        }

    }
    private fun readCompressedAsset(assetName: String): ByteBuffer {
        val input = assets?.open(assetName)
        val bytes = input?.let { ByteArray(it.available()) }
        input?.read(bytes)
        return ByteBuffer.wrap(bytes)
    }

    private fun addTexture(){
//        val numColumns: Int = mMeshResolution.get(0)
//        val numRows: Int = mMeshResolution.get(1)
        val numColumns = 20
        val numRows = 20
        val numCells = numColumns * numRows
        val numIndices = numCells * 6
        val numVertices = (numColumns + 1) * (numRows + 1)
        val uvs = FloatBuffer.allocate(numVertices * 2)
        var vertexBuffer = VertexBuffer.Builder()
            .bufferCount(3)
            .vertexCount(numVertices)
            .attribute(VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3)
            .attribute(VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2)
            .attribute(VertexAttribute.TANGENTS, 2, VertexBuffer.AttributeType.FLOAT4)
            .build(modelViewer.engine)
        var indexBuffer = IndexBuffer.Builder()
            .indexCount(numIndices)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(modelViewer.engine)
        for (row in 0..numRows) {
            for (col in 0..numColumns) {
                uvs.put(col.toFloat() / numColumns)
                uvs.put(row.toFloat() / numRows)
            }
        }

        uvs.flip()

        vertexBuffer.setBufferAt(modelViewer.engine, 1, uvs)
        val renderable = EntityManager.get().create()

        materialInstance?.let {
            RenderableManager.Builder(1)
                .culling(false)
                .material(0, it)
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer,
                    indexBuffer
                )
                .castShadows(false)
                .receiveShadows(false)
                .build(modelViewer.engine, renderable)
        }
        
    }
    
    private fun setTextures(textureA: Texture, textureB: Texture) {
        val sampler = TextureSampler()
        materialInstance?.setParameter("name1", textureA, sampler)
        materialInstance?.setParameter("name2", textureB, sampler)
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
//                modelViewer.transformToUnitCubeForRoom()
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


    }

    private fun test(){
        val asset=modelViewer.assetForRoom
        Log.i("SIZE","${asset?.entities?.size}")
        for (i in 0 until 4) {
            asset?.entities?.get(i)?.let {
                var entity = modelViewer.engine.renderableManager.getInstance(it)
                MaterialBuilder.init()
                val matPackage = MaterialBuilder().platform(MaterialBuilder.Platform.MOBILE)
                    // Set the name of the Material for debugging purposes.
                    .name("Clear coat")

                    // Defaults to LIT. We could change the shading model here if we desired.
                    .shading(MaterialBuilder.Shading.LIT)

                    // Add a parameter to the material that can be set via the setParameter method once
                    // we have a material instance.
                    .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "baseColor")

                    // Fragment block- see the material readme (docs/Materials.md.html) for the full
                    // specification.
                    .material(
                        "void material(inout MaterialInputs material) {\n" +
                                "    prepareMaterial(material);\n" +
                                "    material.baseColor.rgb = materialParams.baseColor;\n" +
                                "    material.roughness = 0.15;\n" +
                                "    material.metallic = 0.8;\n" +
                                "    material.clearCoat = 1.0;\n" +
                                "}\n"
                    )

                    // Turn off shader code optimization so this sample is compatible with the "lite"
                    // variant of the filamat library.
                    .optimization(MaterialBuilder.Optimization.NONE)

                    .build()

                if (matPackage.isValid) {
                    val buffer = matPackage.buffer
                    material = Material.Builder().payload(buffer, buffer.remaining()).build(modelViewer.engine)
                }

                // We're done building materials, so we call shutdown here to free resources. If we wanted
                // to build more materials, we could call MaterialBuilder.init() again (with a slight
                // performance hit).
                MaterialBuilder.shutdown()

                materialInstance = material.createInstance()
                // Specify that our color is in sRGB so the conversion to linear
                // is done automatically for us. If you already have a linear color
                // you can pass it directly, or use Colors.RgbType.LINEAR
                materialInstance!!.setParameter(
                    "baseColor",
                    Colors.RgbType.SRGB,
                    0.0f,
                    175.0f,
                    55.0f
                )


                RenderableManager.Builder(1)
                    .material(0, materialInstance!!)
//                    .geometry(0, PrimitiveType.TRIANGLES, vb, ib)
                    .build(modelViewer.engine, entity)
                modelViewer.engine.renderableManager.setMaterialInstanceAt(entity,0,materialInstance!!)
            }
        }
    }

    private fun loadTexture(engine: Engine, resourceId: Int): Texture {
        val resources = resources
        val options = BitmapFactory.Options()
        options.inPremultiplied = true
        var bitmap = BitmapFactory.decodeResource(resources, resourceId, options)
        bitmap = addWhiteBorder(bitmap, 20)
        val texture = Texture.Builder()
            .width(bitmap.width)
            .height(bitmap.height)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8) // It is crucial to use an SRGB format.
            .levels(0xff) // tells Filament to figure out the number of mip levels
            .build(engine)
        val handler = Handler()
        TextureHelper.setBitmap(
            engine, texture, 0, bitmap, handler
        ) { Log.i("page-curl", "Bitmap is released.") }
        texture.generateMipmaps(engine)
        return texture
    }

    private fun addWhiteBorder(bmp: Bitmap, borderSize: Int): Bitmap {
        val modified = Bitmap.createBitmap(
            bmp.width + borderSize * 2,
            bmp.height + borderSize * 2,
            bmp.config
        )
        val canvas = Canvas(modified)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bmp, borderSize.toFloat(), borderSize.toFloat(), null)
        return modified
    }

}