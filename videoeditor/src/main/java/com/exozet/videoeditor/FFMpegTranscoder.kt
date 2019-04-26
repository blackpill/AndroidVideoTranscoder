package com.exozet.videoeditor

import android.content.Context
import android.util.Log
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import nl.bravobit.ffmpeg.ExecuteBinaryResponseHandler
import nl.bravobit.ffmpeg.FFmpeg
import nl.bravobit.ffmpeg.FFtask
import java.io.File

class FFMpegTranscoder(context: Context) : IFFMpegTranscoder {

    private val internalStoragePath: String = context.filesDir.path

    private val TAG = FFMpegTranscoder::class.java.simpleName

    var ffmpeg = FFmpeg.getInstance(context)

    var extractFrameTaskList: ArrayList<FFtask> = arrayListOf()
    var createVideoTaskList: ArrayList<FFtask> = arrayListOf()


    data class MetaData (
        var progress : String? = null,
        var message : String? = null
    )

    //todo: add percentage calculation

    //extract frames from video
    /**
     * {@inheritDoc}
     */
    override fun createVideo(inputPath: String, fileName: String, outputPath: String, photoQuality: Int, videoQuality: Int, fps: Int, frameTimes: List<String>) = Observable.create<MetaData> { emitter ->



        if (emitter.isDisposed) {
            return@create
        }

        val savePath = "$internalStoragePath/postProcess/${System.currentTimeMillis()}/"
        val saveName = fileName.substring(0, fileName.lastIndexOf("."))

        //create new folder
        File(savePath).mkdirs()

        var selectedTimePoints = "select='"

        frameTimes.forEach {
            selectedTimePoints += "lt(prev_pts*TB\\,$it)*gte(pts*TB\\,$it)+"
        }

        selectedTimePoints = selectedTimePoints.substring(0, selectedTimePoints.length - 1)
        selectedTimePoints += "'"

        /**
         * -i : input
         * -vf : filter_graph set video filters
         * -filter:v : video filter for gived parameters - like requested frame times
         * -qscale:v :quality parameter
         * -vsync : drop : This allows to work around any non-monotonic time-stamp errors //not sure how it totally works
         */
        val cmd = arrayOf("-i", "$inputPath/$fileName", "-qscale:v", "$photoQuality", "-filter:v", selectedTimePoints, "-vsync", "drop", "$savePath${saveName}_%03d.jpg")

        val extractFrameTask = ffmpeg.execute(cmd, object : ExecuteBinaryResponseHandler() {

            override fun onFailure(result: String?) {
                loge("FAIL with output : $result")
                emitter.onError(Throwable(result))
            }

            override fun onSuccess(result: String?) {
                log("SUCCESS with output : $result")

                //create video from frames
                onExtractionSuccess(savePath, saveName, outputPath, videoQuality, fps, emitter, cmd)
            }

            override fun onProgress(progress: String?) {
                log("progress : $progress")
                progress?.let { emitter.onNext(MetaData(progress)) }
            }

            override fun onStart() {
                log("Started command : ffmpeg $cmd")
                emitter.onNext(MetaData(message = "Started Command $cmd"))
            }

            override fun onFinish() {
                log("Finished command : ffmpeg $cmd")
            }
        })
        //add it to list so can stop them later
        extractFrameTaskList.add(extractFrameTask)
    }

    private fun onExtractionSuccess(savePath: String, saveName: String, outputPath: String, videoQuality: Int, fps: Int, emitter: ObservableEmitter<MetaData>, cmd: Array<String>) {
        createVideoFromFrames(savePath, saveName, outputPath, videoQuality, fps, object : ExecuteBinaryResponseHandler() {

            override fun onFailure(result: String?) {
                loge("FAIL create video with output : $result")
                emitter.onError(Throwable(result))
            }

            override fun onSuccess(result: String?) {
                log("SUCCESS create video with output : $result")
                result?.let { emitter.onNext(MetaData(message = it)) }
            }

            override fun onProgress(progress: String?) {
                log("Started command create video : ffmpeg $cmd")
                log("progress create video : $progress")

                //todo: update progress
                progress?.let { emitter.onNext(MetaData(progress = it)) }
            }

            override fun onStart() {
                log("Started command create video : ffmpeg $cmd")
                emitter.onNext(MetaData(message = "Started command create video : ffmpeg $cmd"))
            }

            override fun onFinish() {
                log("Finished command create video: ffmpeg $cmd")
                //delete temp files
                val deleteStatus = deleteFolder(savePath)
                Log.d(TAG, "Delete temp frame save path status: $deleteStatus")
                emitter.onComplete()
            }
        })
    }

    override fun stopAllProcesses() {
        extractFrameTaskList.forEach {
            it.sendQuitSignal()
        }
        extractFrameTaskList.clear()

        createVideoTaskList.forEach {
            it.sendQuitSignal()
        }
        createVideoTaskList.clear()
    }


    /**
     * {@inheritDoc}
     */
    private fun createVideoFromFrames(savePath: String, saveName: String, outputPath: String, videoQuality: Int, fps: Int, handler: ExecuteBinaryResponseHandler) {

        /**
         * -i : input
         * -framerate : frame rate of the video
         * -crf quality of the output video
         * -pix_fmt pixel format
         */
        val cmd = arrayOf("-framerate", "$fps", "-i", "$savePath${saveName}_%03d.jpg", "-crf", "$videoQuality", "-pix_fmt", "yuv420p", outputPath)

        val createVideoTask = ffmpeg.execute(cmd, handler)
        createVideoTaskList.add(createVideoTask)
    }

    private fun deleteFolder(path: String): Boolean {
        val someDir = File(path)

        //todo:make async - check the right folder removed
        return someDir.deleteRecursively()
    }


}