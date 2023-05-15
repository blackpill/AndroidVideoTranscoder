package com.exozet.transcoder.mcvideoeditor

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.IntRange
import com.exozet.transcoder.ffmpeg.Progress
import io.reactivex.Observable
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.File
import java.io.InputStream

object MediaCodecTranscoder {
    private val videoExtractor = MediaCodecExtractImage()
    private val audioExtractor = MediaCodecExtractAudio()

    fun getCurrentTime():Double{
        return videoExtractor.currentTime
    }
    fun seekAndFetchOneFrame(
        context: Context,
        inputVideo: Uri,
        @IntRange(from = 1, to = 100) photoQuality: Int = 100,
        @IntRange(from = 1, to = 100) scalePercent: Int = 100,
        seekTime: Double = (-1).toDouble(),
    ):ByteArray{
        audioExtractor.seek(seekTime)
        return videoExtractor.seekAndFetchOneFrame(inputVideo, photoQuality, context, scalePercent, seekTime)
    }
    fun extractFramesFromVideoToFlow(
        context: Context,
        inputVideo: Uri,
        @IntRange(from = 1, to = 100) photoQuality: Int = 100,
        @IntRange(from = 1, to = 100) scalePercent: Int = 100,
        videoEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): Flow<ByteArray> {
        return videoExtractor.extractMpegFramesToFlow(
            inputVideo,
            photoQuality,
            context,
            scalePercent
        )
    }
    fun getMetaInfo(
        context: Context,
        inputVideo: Uri
    ): JSONObject {
        return videoExtractor.getMetaInfo(inputVideo, context)
    }

    fun extractAudioFromVideoToStream(
        context: Context,
        inputVideo: Uri,
        audioEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): InputStream {
        return audioExtractor.extractAudioToStream(inputVideo, context)
    }
    fun pause(pauseTime: Double) {
        videoExtractor.pause(pauseTime)
    }
    fun cancel() {
        videoExtractor.cancel()
    }

    fun setReleasedLatch() {
        videoExtractor.setReleasedLatch()
    }

    fun createVideoFromFrames(
        frameFolder: Uri,
        outputUri: Uri,
        config: MediaConfig = MediaConfig(),
        deleteFramesOnComplete: Boolean = true
    ): Observable<Progress> {

        var mediaCodecCreateVideo : MediaCodecCreateVideo? = null
        val shouldCancel =  MediaCodecExtractImage.Cancelable()

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