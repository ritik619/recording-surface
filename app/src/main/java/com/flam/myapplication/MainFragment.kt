package com.flam.myapplication

import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gorisse.thomas.lifecycle.lifecycle
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.loadHdrIndirectLight
import io.github.sceneview.loaders.loadHdrSkybox
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.nodes.ModelNode
import java.io.File

class MainFragment : Fragment(R.layout.fragment_main) {

    lateinit var sceneView: SceneView
    lateinit var loadingView: View
    var mediaRecorder:MediaRecorder?=null
    var isRecording=false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sceneView = view.findViewById<SceneView>(R.id.sceneView).apply {
            setLifecycle(lifecycle)
        }
        loadingView = view.findViewById(R.id.loadingView)

        lifecycleScope.launchWhenCreated {
            val hdrFile = "environments/studio_small_09_2k.hdr"
            sceneView.loadHdrIndirectLight(hdrFile, specularFilter = true) {
                intensity(30_000f)
            }
            sceneView.loadHdrSkybox(hdrFile) {
                intensity(50_000f)
            }

            val model = sceneView.modelLoader.loadModel("models/MaterialSuite.glb")!!
            val modelNode = ModelNode(sceneView, model).apply {
                transform(
                    position = Position(z = -4.0f),
                    rotation = Rotation(x = 15.0f)
                )
                scaleToUnitsCube(2.0f)
                // TODO: Fix centerOrigin
//                centerOrigin(Position(x=-1.0f, y=-1.0f))
                playAnimation()
            }
            sceneView.addChildNode(modelNode)

//    String fileName = "Flam_" + "${System.currentTimeMillis()}.mp4";
//    Path videoFile = Environment.getExternalStoragePublicDirectory(
//            Environment.DIRECTORY_DCIM
//    ).toString() + File.separator + fileName;
//    videoFile =
//            new File(
//                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//                            + "/Sceneform");
//    File file = File(videoFile)

            loadingView.isGone = true
        }
//        val mediaRecorder=MediaRecorder()
//        val fileMp = File(
//            Environment.getExternalStorageDirectory()
//                .toString() + "/Download/" + File.separator + "test3.mp4"
//        )
//        mediaRecorder.setOutputFile(fileMp.absolutePath)
//        mediaRecorder.setVideoEncodingBitRate(120000)
//        mediaRecorder.setVideoFrameRate(30)
//        mediaRecorder.setVideoSize(view.width,view.height)
//        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

    }

    override fun onPause() {
        Log.i("on pause","phhhh")
        super.onPause()

            if(isRecording){
                sceneView.stopMirroring(mediaRecorder!!)
            }
            isRecording=true


    }
    override fun onResume() {
        super.onResume()
        Log.i("on resume","start")
        if(isRecording){
            mediaRecorder=MediaRecorder()
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
            mediaRecorder!!.prepare()
            mediaRecorder!!.start()
            isRecording=true
        if(isRecording){
        sceneView.startMirroring(mediaRecorder!!)
    }
}
//override fun
}}