package com.exozet.transcoder.mcvideoeditor

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.IntRange
import com.exozet.transcoder.ffmpeg.Progress
import com.exozet.transcoder.ffmpeg.log
import io.reactivex.Observable
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object MediaCodecTranscoder {
    private val mediaCodec = MediaCodecExtractImages()

    fun extractFramesFromVideo(
        context: Context,
        frameTimes: List<Double>,
        inputVideo: Uri,
        id: String,
        outputDir: Uri?,
        @IntRange(from = 1, to = 100) photoQuality: Int = 100
    ): Observable<Progress> {
        val mediaCodec = MediaCodecExtractImages()

        val internalStoragePath: String = context.filesDir.absolutePath
        val startTime = System.currentTimeMillis()

        val localSavePath = "${outputDir ?: "$internalStoragePath/postProcess/$id/$startTime/"}"

        //create new folder
        val file = File(localSavePath)
        if (!file.exists())
            file.mkdirs()

        return mediaCodec.extractMpegFrames(inputVideo, frameTimes, Uri.parse(localSavePath), photoQuality, context)
    }

    fun extractFramesFromVideoToFlow(
        context: Context,
        inputVideo: Uri,
        id: String,
        mjpegSharedFlow: MutableStateFlow<ByteArray>,
        @IntRange(from = 1, to = 100) photoQuality: Int = 100,
        videoStartTime: Double = 0.0,
        videoEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): Observable<Progress> {
        val internalStoragePath: String = context.filesDir.absolutePath
        val startTime = System.currentTimeMillis()


        return mediaCodec.extractMpegFramesToFlow(inputVideo, mjpegSharedFlow, photoQuality, context, videoStartTime, videoEndTime, loop)
    }
    fun pause() {
        return mediaCodec.pause()
    }
    fun cancel() {
        return mediaCodec.cancel()
    }

    fun createVideoFromFrames(
        frameFolder: Uri,
        outputUri: Uri,
        config: MediaConfig = MediaConfig(),
        deleteFramesOnComplete: Boolean = true
    ): Observable<Progress> {

        var mediaCodecCreateVideo : MediaCodecCreateVideo? = null
        val shouldCancel =  MediaCodecExtractImages.Cancelable()

        return Observable.create<Progress> { emitter ->

            if (emitter.isDisposed)
                return@create

            val items = File(frameFolder.path!!).listFiles()?.sorted() ?: return@create
            
            mediaCodecCreateVideo = MediaCodecCreateVideo(config)

            val firstFrame = BitmapFactory.decodeFile(items.firstOrNull()?.absolutePath ?: return@create)

            mediaCodecCreateVideo!!.startEncoding(items,firstFrame.width, firstFrame.height, outputUri, shouldCancel, emitter)

            if (!firstFrame.isRecycled) firstFrame.recycle()
            
        }.doOnDispose {
            shouldCancel.cancel.set(true)
            mediaCodecCreateVideo = null
        }

    }

    /**
     * Deletes directory path recursively.
     */
    internal fun deleteFolder(path: String): Boolean = File(path).deleteRecursively()
}