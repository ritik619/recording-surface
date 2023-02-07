package com.yoyo.multigltfpoc


import android.media.MediaRecorder
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.Nullable
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.Buffer


private const val kNearPlane = 0.05     // 5 cm
private const val kFarPlane = 1000.0    // 1 km
private const val kAperture = 16f
private const val kShutterSpeed = 1f / 125f
private const val kSensitivity = 100f

/**
 * Helps render glTF models into a [SurfaceView] or [TextureView] with an orbit controller.
 *
 * `ModelViewer` owns a Filament engine, renderer, swapchain, view, and scene. It allows clients
 * to access these objects via read-only properties. The viewer can display only one glTF scene
 * at a time, which can be scaled and translated into the viewing frustum by calling
 * [transformToUnitCube]. All ECS entities can be accessed and modified via the [asset] property.
 *
 * For GLB files, clients can call [loadModelGlb] and pass in a [Buffer] with the contents of the
 * GLB file. For glTF files, clients can call [loadModelGltf] and pass in a [Buffer] with the JSON
 * contents, as well as a callback for loading external resources.
 *
 * `ModelViewer` reduces much of the boilerplate required for simple Filament applications, but
 * clients still have the responsibility of adding an [IndirectLight] and [Skybox] to the scene.
 * Additionally, clients should:
 *
 * 1. Pass the model viewer into [SurfaceView.setOnTouchListener] or call its [onTouchEvent]
 *    method from your touch handler.
 * 2. Call [render] and [Animator.applyAnimation] from a `Choreographer` frame callback.
 *
 * NOTE: if its associated SurfaceView or TextureView has become detached from its window, the
 * ModelViewer becomes invalid and must be recreated.
 *
 * See `sample-gltf-viewer` for a usage example.
 */
class NewModelViewer(
    val engine: Engine,
    private val uiHelper: UiHelper
) : android.view.View.OnTouchListener {
    var assetForModel: FilamentAsset? = null
        private set
    var isRecording=false
    var assetForRoom: FilamentAsset? = null
        private set

    var animatorForRoom: Animator? = null
        private set

    var animatorForModel: Animator? = null
        private set

    @Suppress("unused")
    val progressForRoom
        get() = resourceLoaderForRoom.asyncGetLoadProgress()

    @Suppress("unused")
    val progressForModel
        get() = resourceLoaderForModel.asyncGetLoadProgress()

    var normalizeSkinningWeights = true

    var cameraFocalLength = 28f
        set(value) {
            field = value
            updateCameraProjection()
        }

    val scene: Scene
    val view: View
    val camera: Camera
    val renderer: Renderer
    private lateinit var gestureDetector: ModelGestureDetector

    @Entity
    val light: Int

    private lateinit var displayHelper: DisplayHelper
    private lateinit var cameraManipulatorForModel: Manipulator
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null

    private var fetchResourcesJobForModel: Job? = null
    private var fetchResourcesJobForRoom: Job? = null

    private var swapChain: SwapChain? = null
    private var destSwapChain:SwapChain?=null

    private var assetLoaderForModel: AssetLoader
    private var assetLoaderForRoom: AssetLoader
    private var materialProvider: MaterialProvider
    private var resourceLoaderForModel: ResourceLoader
    private var resourceLoaderForRoom: ResourceLoader
    private val readyRenderables = IntArray(128) // add up to 128 entities at a time
    private var count=0
    private val eyePos = DoubleArray(3)
    private val target = DoubleArray(3)
    private val upward = DoubleArray(3)
    private val mirrors: MutableList<Mirror> = mutableListOf()
    private var mediaRecorder:MediaRecorder?=null
    private var videoDirectory: File? = null
    private var videoBaseName: String? = null
    private var videoPath: File? = null
    private var surfaceMirrorer:SurfaceMirrorer?=null

    init {
        renderer = engine.createRenderer()
        scene = engine.createScene()
        camera = engine.createCamera(engine.entityManager.create())
            .apply { setExposure(kAperture, kShutterSpeed, kSensitivity) }
        view = engine.createView()
        view.scene = scene
        view.camera = camera

        materialProvider = UbershaderProvider(engine)
        assetLoaderForModel = AssetLoader(engine, materialProvider, EntityManager.get())
        assetLoaderForRoom = AssetLoader(engine,materialProvider,EntityManager.get())
        surfaceMirrorer = SurfaceMirrorer(engine, view, renderer)

        resourceLoaderForModel = ResourceLoader(engine, normalizeSkinningWeights)
        resourceLoaderForRoom = ResourceLoader(engine, normalizeSkinningWeights)

        // Always add a direct light source since it is required for shadowing.
        // We highly recommend adding an indirect light as well.
        light = EntityManager.get().create()
        val (r, g, b) = Colors.cct(6_500.0f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(r, g, b)
            .intensity(400_000.0f)
            .direction(0.2f, -1.0f, 0.2f)
            .castShadows(true)
            .castLight(true)
            .build(engine, light)

        scene.addEntity(light)
    }

    constructor(
        surfaceView: SurfaceView,
        engine: Engine = Engine.create(),
        uiHelper: UiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
        manipulator: Manipulator? = null
    ) : this(engine, uiHelper) {

        cameraManipulatorForModel = manipulator ?: Manipulator.Builder()
            .targetPosition(
                kDefaultObjectPositionForModel.x,
                kDefaultObjectPositionForModel.y,
                kDefaultObjectPositionForModel.z
            )
            .viewport(surfaceView.width, surfaceView.height)
            .build(Manipulator.Mode.ORBIT)

        Log.d("Hello","Heloo")

        gestureDetector = ModelGestureDetector(surfaceView, cameraManipulatorForModel)
        this.surfaceView = surfaceView
        displayHelper = DisplayHelper(surfaceView.context)
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }



    /**
     * Loads a JSON-style glTF file and populates the Filament scene.
     *
     * The given callback is triggered from a worker thread for each requested resource.
     */
    fun loadModelGltfAsyncForModel(buffer: Buffer, callback: (String) -> Buffer ) {
        destroyModelForModel()
        assetForModel = assetLoaderForModel.createAsset(buffer)
        fetchResourcesJobForModel = CoroutineScope(Dispatchers.IO).launch {
            fetchResourcesForModel(assetForModel!!, callback)
        }
    }

    fun loadModelGltfAsyncForRoom(buffer: Buffer, callback: (String) -> Buffer ) {
        destroyModelForRoom()
        assetForRoom = assetLoaderForRoom.createAsset(buffer)
        fetchResourcesJobForRoom = CoroutineScope(Dispatchers.IO).launch {
            fetchResourcesForRoom(assetForRoom!!, callback)
        }
    }

    /**
     * Sets up a root transform on the current model to make it fit into a unit cube.
     *
     * @param centerPoint Coordinate of center point of unit cube, defaults to < 0, 0, -4 >
     */
    fun transformToUnitCubeForModel(centerPoint: Float3 = kDefaultObjectPositionForModel) {
        assetForModel?.let { asset ->
            val tm = engine.transformManager
            var center = asset.boundingBox.center.let { v -> Float3(v[0], v[1], v[2]) }
            val halfExtent = asset.boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
            val maxExtent = 2.0f * max(halfExtent)
            val scaleFactor = 4f / maxExtent
            center -= centerPoint / scaleFactor
            val transform = scale(Float3(scaleFactor)) * translation(-center)
            tm.setTransform(tm.getInstance(asset.root), transpose(transform).toFloatArray())
        }
    }


    fun transformToUnitCubeForRoom(centerPoint: Float3 = kDefaultObjectPositionForRoom) {
        assetForRoom?.let { asset ->
            val tm = engine.transformManager
            var center = asset.boundingBox.center.let { v -> Float3(v[0], v[1], v[2]) }
            val halfExtent = asset.boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
            val maxExtent = 0.5f * max(halfExtent)
            val scaleFactor = 5.0f / maxExtent
            center -= centerPoint / scaleFactor
            val transform = scale(Float3(scaleFactor)) * translation(-center)
            tm.setTransform(tm.getInstance(asset.root), transpose(transform).toFloatArray())
        }
    }
    /**
     * Removes the transformation that was set up via transformToUnitCube.
     */
    fun clearRootTransformForModel() {
        assetForModel?.let {
            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(it.root), Mat4().toFloatArray())
        }
    }

    fun clearRootTransformForRoom() {
        assetForRoom?.let {
            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(it.root), Mat4().toFloatArray())
        }
    }

    /**
     * Frees all entities associated with the most recently-loaded model.
     */
    fun destroyModelForRoom() {
        fetchResourcesJobForRoom?.cancel()
        resourceLoaderForRoom.asyncCancelLoad()
        resourceLoaderForRoom.evictResourceData()
        assetForRoom?.let { asset ->
            this.scene.removeEntities(asset.entities)
            assetLoaderForRoom.destroyAsset(asset)
            this.assetForRoom = null
            this.animatorForRoom = null
        }
        assetForRoom?.let { asset ->
            this.scene.removeEntities(asset.entities)
            assetLoaderForRoom.destroyAsset(asset)
            this.assetForRoom = null
        }
    }

    fun destroyModelForModel() {
        fetchResourcesJobForModel?.cancel()
        resourceLoaderForModel.asyncCancelLoad()
        resourceLoaderForModel.evictResourceData()
        assetForModel?.let { asset ->
            this.scene.removeEntities(asset.entities)
            assetLoaderForModel.destroyAsset(asset)
            this.assetForModel = null
            this.animatorForModel = null
        }
    }



    /**
     * Renders the model and updates the Filament camera.
     *
     * @param frameTimeNanos time in nanoseconds when the frame started being rendered,
     *                       typically comes from {@link android.view.Choreographer.FrameCallback}
     */
    fun render(frameTimeNanos: Long) {
        if (!uiHelper.isReadyToRender) {
            return
        }
        val finalEntity = assetForModel!!.getFirstEntityByName("Hips")
      /*  Log.d("World Transform", finalEntity.toString())*/
        // Allow the resource loader to finalize textures that have become ready.
        val hipsMovement: DoubleArray=  engine.transformManager.getWorldTransform( finalEntity, null as DoubleArray?)
        resourceLoaderForModel.asyncUpdateLoad()
        resourceLoaderForRoom.asyncUpdateLoad()

        // Add renderable entities to the scene as they become ready.
        assetForModel?.let { populateScene(it) }
        assetForRoom?.let { populateScene(it) }
        // Extract the camera basis from the helper and push it to the Filament camera.
        cameraManipulatorForModel.getLookAt(doubleArrayOf(0.0,6.0,-5.0), target, upward)
/*        camera.lookAt(
            eyePos[0],eyePos[1],hipsMovement[14] - deltaz,
            hipsMovement[12], hipsMovement[13] , hipsMovement[14] ,
            upward[0], upward[1], upward[2]
        )*/
        // Render the scene, unless the renderer wants to skip the frame.
        if (renderer.beginFrame(swapChain!!, frameTimeNanos)) {
            renderer.render(view)
            surfaceMirrorer!!.onFrame()
            renderer.endFrame()
        }

//        if(isRecording ==true) {
//            renderer.copyFrame(
//                destSwapChain,
//                view.viewport,
//                view.viewport,
//                Renderer.MIRROR_FRAME_FLAG_CLEAR
//            )
//        }
        count+=1
        Log.i("start","He,llo $count ${mirrors.size}")
        if(count==200){
            isRecording=true
            mediaRecorder= MediaRecorder()
            buildFilename()
            setUpMediaRecorder()
            surfaceMirrorer!!.startMirroring(mediaRecorder!!.surface,0,0,view.viewport.width,view.viewport.height)
        }
        if(count==2000){
            surfaceMirrorer!!.stopMirroring(mediaRecorder!!.surface)
            mediaRecorder!!.stop()
            isRecording=false

        }

    }

    fun startMirroringToSurface( sur: Surface) {
        if (this.renderer != null) {
//            this.renderer.startMirroring(var1, var2, var3, var4, var5);
        }
        val mirror = Mirror()
        mirror.surface = sur
        mirror.viewport = view.viewport
        mirror.swapChain = null
        synchronized(this.mirrors) { this.mirrors.add(mirror) }
        Log.i("start","${this.mirrors.size}")
        isRecording=true
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {

//    String fileName = "Flam_" + "${System.currentTimeMillis()}.mp4";
//    Path videoFile = Environment.getExternalStoragePublicDirectory(
//            Environment.DIRECTORY_DCIM
//    ).toString() + File.separator + fileName;
//    videoFile =
//            new File(
//                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//                            + "/Sceneform");
//    File file = File(videoFile)

        val fileMp = File(
            Environment.getExternalStorageDirectory()
                .toString() + "/Download/" + File.separator + "test3.mp4"
        )
        mediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder!!.setOutputFile(fileMp.absolutePath)
        mediaRecorder!!.setVideoEncodingBitRate(120000)
        mediaRecorder!!.setVideoFrameRate(30)
        try {
            mediaRecorder!!.prepare()
            mediaRecorder!!.start()
        } catch (e: IllegalStateException) {
            Log.e(
                "com.yoyo...TAG",
                "Exception starting capture: " + e.message,
                e
            )
        }
    }

    private fun buildFilename() {
        if (videoDirectory == null) {
            videoDirectory = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM
                ).toString()
            )
        }
        if (videoBaseName == null || videoBaseName!!.isEmpty()) {
            videoBaseName = "Sample"
        }
        videoPath = File(
            videoDirectory,
            videoBaseName + java.lang.Long.toHexString(System.currentTimeMillis()) + ".mp4"
        )
        val dir: File = videoPath!!.getParentFile()
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
    private fun populateScene(asset: FilamentAsset) {
        val rcm = engine.renderableManager
        var count = 0
        val popRenderables = { count = asset.popRenderables(readyRenderables); count != 0 }
        while (popRenderables()) {
            for (i in 0..count - 1) {
                val ri = rcm.getInstance(readyRenderables[i])
                rcm.setScreenSpaceContactShadows(ri, true)
            }
            scene.addEntities(readyRenderables.take(count).toIntArray())
        }
        scene.addEntities(asset.lightEntities)
    }

    fun destroyObjects() {
        uiHelper.detach()
        destroyModelForModel()
        destroyModelForRoom()
        assetLoaderForModel.destroy()
        assetLoaderForRoom.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        resourceLoaderForModel.destroy()
        resourceLoaderForRoom.destroy()

        engine.destroyEntity(light)
        engine.destroyRenderer(renderer)
        engine.destroyView(this@NewModelViewer.view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)

        EntityManager.get().destroy(light)
        engine.destroy()
    }


    @SuppressWarnings("ClickableViewAccessibility")
    override fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
//        Log.i("log","${event.}")
        gestureDetector.onTouchEvent(event)
        return true
    }

    private suspend fun fetchResourcesForRoom(asset: FilamentAsset, callback: (String) -> Buffer) {
        val items = HashMap<String, Buffer>()
        val resourceUris = asset.resourceUris
        for (resourceUri in resourceUris) {
            items[resourceUri] = callback(resourceUri)
        }

        withContext(Dispatchers.Main) {
            for ((uri, buffer) in items) {
                resourceLoaderForRoom.addResourceData(uri, buffer)
            }
            resourceLoaderForRoom.asyncBeginLoad(asset)
            animatorForRoom = asset.instance.animator
            asset.releaseSourceData()
        }
    }

    private suspend fun fetchResourcesForModel(asset: FilamentAsset, callback: (String) -> Buffer) {
        val items = HashMap<String, Buffer>()
        val resourceUris = asset.resourceUris
        for (resourceUri in resourceUris) {
            items[resourceUri] = callback(resourceUri)
        }

        withContext(Dispatchers.Main) {
            for ((uri, buffer) in items) {
                resourceLoaderForRoom.addResourceData(uri, buffer)
            }
            resourceLoaderForRoom.asyncBeginLoad(asset)
            animatorForModel = asset.instance.animator
            asset.releaseSourceData()
        }
    }

    private fun updateCameraProjection() {
        val width = view.viewport.width
        val height = view.viewport.height
        val aspect = width.toDouble() / height.toDouble()
        camera.setLensProjection(cameraFocalLength.toDouble(), aspect, kNearPlane, kFarPlane)
    }

    inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            destSwapChain = engine.createSwapChain(surface)
            surfaceView?.let { displayHelper.attach(renderer, it.display) }
            textureView?.let { displayHelper.attach(renderer, it.display) }
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
            destSwapChain.let {
                if (it != null) {
                    engine.destroySwapChain(it)
                }
                engine.flushAndWait()
                destSwapChain=null
            }
        }

        override fun onResized(width: Int, height: Int) {
            view.viewport = Viewport(0, 0, width, height)
            cameraManipulatorForModel.setViewport(width, height)
            updateCameraProjection()
        }
    }



    companion object {
        private val kDefaultObjectPositionForModel = Float3(0.0f, 1f, -6.0f)
        private val kDefaultObjectPositionForRoom = Float3(0.0f, 4.9f, -2f)
        private val kCameraDefaultPos = Float3(0.0F, 4F,1F)
        private val deltaz = kDefaultObjectPositionForModel[2] - kCameraDefaultPos[2]
    }

    private class Mirror public constructor() {
        @Nullable
        var swapChain: SwapChain? = null

        @Nullable
        var surface: Surface? = null
        var viewport: Viewport? = null
    }

}