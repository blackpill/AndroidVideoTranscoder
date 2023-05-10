package com.exozet.transcoder.mcvideoeditor

/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context
import android.media.*
import android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC
import android.net.Uri
import android.util.Log
import com.exozet.transcoder.ffmpeg.Progress
import com.exozet.transcoder.ffmpeg.log
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


//20131122: minor tweaks to saveFrame() I/O
//20131205: add alpha to EGLConfig (huge glReadPixels speedup); pre-allocate pixel buffers;
//          log time to run saveFrame()
//20140123: correct error checks on glGet*Location() and program creation (they don't set error)
//20140212: eliminate byte swap

/**
 * To check how to convert time video frame times to frames number, go to getDesiredFrames() method
 */

/**
 * Extract frames from an MP4 using MediaExtractor, MediaCodec, and GLES.  Put a .mp4 file
 * in "/sdcard/source.mp4" and look for output files named "/sdcard/frame-XX.png".
 *
 *
 * This uses various features first available in Android "Jellybean" 4.1 (API 16).
 *
 *
 * (This was derived from bits and pieces of CTS tests, and is packaged as such, but is not
 * currently part of CTS.)
 */
class MediaCodecExtractImage {
    //private var decoder: MediaCodec? = null
    //private lateinit var outputSurface: CodecOutputSurface
    //private var extractor: MediaExtractor? = null
    private val pauseable = Pauseable()
    private val cancelable = Cancelable()
    private val currentDecodeFrame = AtomicInteger(0)
    private var releasedLatch = CountDownLatch(0)
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
//        XLog.e(getLog("onCoroutineException"), throwable)
        Log.e("MediaCodecExtractImages", throwable.toString())
    }
    private val eglDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineScope = CoroutineScope(SupervisorJob() + eglDispatcher + coroutineExceptionHandler)
    private var outputDone = false
    private var surfaceToFlowChannel = Channel<ByteArray>()
    private val released = AtomicBoolean(false)
    var currentTime:Double = 0.0
    fun pause(){
        pauseable.pause.set(true)
    }

    fun setReleasedLatch(){
        releasedLatch = CountDownLatch(1) //use this to make sure resources are fully released
        Log.d("worker", "releasedLatch set 1")
    }

    fun cancel(){
        //pause()
        cancelable.cancel.set(true)
        surfaceToFlowChannel = Channel()
//        completeLatch.await()
        //release(outputSurface, decoder, extractor)
    }

    fun getMetaInfo(
        inputVideo: Uri,
        context: Context? = null,
    ): JSONObject {
        val extractor = MediaExtractor()
        if (inputVideo.scheme == null || inputVideo.scheme == "file") {
            val inputFilePath = inputVideo.path
            val inputFile = File(inputFilePath!!)   // must be an absolute path
            // The MediaExtractor error messages aren't very useful.  Check to see if the input
            // file exists so we can throw a better one if it's not there.
            if (!inputFile.canRead()) {
                throw FileNotFoundException("Unable to read $inputFile")
            }
            extractor.setDataSource(inputFile.toString())
        } else {
            val headers = mapOf("User-Agent" to "media converter")
            extractor.setDataSource(context!!, inputVideo, headers)
        }

        val videoTrackIndex = getTrackId(extractor, "video")
        if (videoTrackIndex < 0) {
            throw RuntimeException("No video track found in $inputVideo")
        }
        extractor.selectTrack(videoTrackIndex)

        val format = extractor.getTrackFormat(videoTrackIndex)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger((MediaFormat.KEY_ROTATION))
                       else 0
        val videoWidth = if(rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_HEIGHT)
                         else format.getInteger(MediaFormat.KEY_WIDTH)
        val videoHeight = if(rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_WIDTH)
                          else format.getInteger(MediaFormat.KEY_HEIGHT)
        log(
            "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                    format.getInteger(MediaFormat.KEY_HEIGHT)
        )

        val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        val valuePacket = JSONObject()
        val MAX_FRAME_RATE = 24
        val adjustedFrameRate = if(frameRate > MAX_FRAME_RATE) MAX_FRAME_RATE
                                else frameRate

        valuePacket.put("duration", duration)
        valuePacket.put("frameRate", adjustedFrameRate)
        valuePacket.put("videoWidth", videoWidth)
        valuePacket.put("videoHeight", videoHeight)
        return valuePacket
    }
    /**
     * Tests extraction from an MP4 to a series of PNG files.
     *
     *
     * We scale the video to 640x480 for the PNG just to demonstrate that we can scale the
     * video with the GPU.  If the input video has a different aspect ratio, we could preserve
     * it by adjusting the GL viewport to get letterboxing or pillarboxing, but generally if
     * you're extracting frames you don't want black bars.
     */
    fun seekAndFetchOneFrame(
        inputVideo: Uri,
        photoQuality: Int,
        context: Context? = null,
        scalePercent: Int = 100,
        videoStartTime: Double = 0.0
    ): ByteArray {
        Log.d("worker", "releasedLatch.await() start")
        releasedLatch.await()
        Log.d("worker", "releasedLatch.await() passed")
        currentTime = videoStartTime
        var decoder: MediaCodec? = null
        var extractor: MediaExtractor? = null
        var outputSurface: CodecOutputSurface? = null
        var frameArray = ByteArray(0)
        released.getAndSet(false)
        runBlocking {
            extractor = MediaExtractor()
            if (inputVideo.scheme == null || inputVideo.scheme == "file") {
                val inputFilePath = inputVideo.path
                val inputFile = File(inputFilePath!!)   // must be an absolute path
                // The MediaExtractor error messages aren't very useful.  Check to see if the input
                // file exists so we can throw a better one if it's not there.
                if (!inputFile.canRead()) {
                    throw FileNotFoundException("Unable to read $inputFile")
                }
                extractor!!.setDataSource(inputFile.toString())
            } else {
                val headers = mapOf("User-Agent" to "media converter")
                extractor!!.setDataSource(context!!, inputVideo, headers)
            }

            val videoTrackIndex = getTrackId(extractor!!, "video")
            if (videoTrackIndex < 0) {
                throw RuntimeException("No video track found in $inputVideo")
            }
            extractor!!.selectTrack(videoTrackIndex)

            val format = extractor!!.getTrackFormat(videoTrackIndex)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger((MediaFormat.KEY_ROTATION))
            else 0
            val saveWidth = if(rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_HEIGHT)
            else format.getInteger(MediaFormat.KEY_WIDTH)
            val saveHeight = if(rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_WIDTH)
            else format.getInteger(MediaFormat.KEY_HEIGHT)
            log(
                "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                        format.getInteger(MediaFormat.KEY_HEIGHT)
            )

            val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)

            val duration = format.getLong(MediaFormat.KEY_DURATION)

            val secToMicroSec = 1000000
            val totalFrame = (duration * frameRate / secToMicroSec).toInt()
            val realStartTime: Long =
                if (videoStartTime > 0.01) (videoStartTime * secToMicroSec).toLong()
                else this@MediaCodecExtractImage.currentDecodeFrame.get() * secToMicroSec.toLong() / frameRate
            extractor!!.seekTo(realStartTime, SEEK_TO_PREVIOUS_SYNC)
            log(
                "Frame rate is = " + frameRate +
                        " Total duration is in microSec = " + duration +
                        " Total frame count = " + totalFrame
            )

            // Could use width/height from the MediaFormat to get full-size frames.
            outputSurface = CodecOutputSurface(saveWidth, saveHeight)
            val surface = outputSurface?.surface

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            //NOTE: outputSurface should not be in flow, when switch thread, the EGLContext will change.
            //So I use a channel in Flow to receive and emit data
            frameArray = extractOneFrame(
                extractor!!,
                videoTrackIndex,
                decoder!!,
                outputSurface!!,
                photoQuality,
                scalePercent,
                totalFrame,
                rotation,
                frameRate
            )
            release(outputSurface, decoder, extractor)
        }
        return frameArray
    }
    fun extractMpegFramesToFlow(
        inputVideo: Uri,
        photoQuality: Int,
        context: Context? = null,
        scalePercent: Int = 100,
        videoEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): Flow<ByteArray> {
        Log.d("worker", "releasedLatch.await() start")
        releasedLatch.await()
        Log.d("worker", "releasedLatch.await() passed")
        var decoder: MediaCodec? = null
        var extractor: MediaExtractor? = null
        var outputSurface: CodecOutputSurface? = null
        released.getAndSet(false)
        coroutineScope.launch {
            pauseable.pause.set(false)
            cancelable.cancel.set(false)
            extractor = MediaExtractor()
            if (inputVideo.scheme == null || inputVideo.scheme == "file") {
                val inputFilePath = inputVideo.path
                val inputFile = File(inputFilePath!!)   // must be an absolute path
                // The MediaExtractor error messages aren't very useful.  Check to see if the input
                // file exists so we can throw a better one if it's not there.
                if (!inputFile.canRead()) {
                    throw FileNotFoundException("Unable to read $inputFile")
                }
                extractor!!.setDataSource(inputFile.toString())
            } else {
                val headers = mapOf("User-Agent" to "media converter")
                extractor!!.setDataSource(context!!, inputVideo, headers)
            }

            val videoTrackIndex = getTrackId(extractor!!, "video")
            if (videoTrackIndex < 0) {
                throw RuntimeException("No video track found in $inputVideo")
            }
            extractor!!.selectTrack(videoTrackIndex)

            val format = extractor!!.getTrackFormat(videoTrackIndex)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger((MediaFormat.KEY_ROTATION))
            else 0
            val saveWidth = if(rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_HEIGHT)
                            else format.getInteger(MediaFormat.KEY_WIDTH)
            val saveHeight = if(rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_WIDTH)
                             else format.getInteger(MediaFormat.KEY_HEIGHT)
            log(
                "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                        format.getInteger(MediaFormat.KEY_HEIGHT)
            )

            val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)

            val duration = format.getLong(MediaFormat.KEY_DURATION)

            val secToMicroSec = 1000000
            val totalFrame = (duration * frameRate / secToMicroSec).toInt()
            val realStartTime: Long =
                if (currentTime > 0.01) (currentTime * secToMicroSec).toLong()
                else this@MediaCodecExtractImage.currentDecodeFrame.get() * secToMicroSec.toLong() / frameRate
            extractor!!.seekTo(realStartTime, SEEK_TO_PREVIOUS_SYNC)
            log(
                "Frame rate is = " + frameRate +
                        " Total duration is in microSec = " + duration +
                        " Total frame count = " + totalFrame
            )

            // Could use width/height from the MediaFormat to get full-size frames.
            outputSurface = CodecOutputSurface(saveWidth, saveHeight)
            val surface = outputSurface?.surface

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            //NOTE: outputSurface should not be in flow, when switch thread, the EGLContext will change.
            //So I use a channel in Flow to receive and emit data
            doExtractToFlow(
                extractor!!,
                videoTrackIndex,
                decoder!!,
                outputSurface!!,
                photoQuality,
                scalePercent,
                totalFrame,
                cancelable,
                pauseable,
                rotation,
                frameRate
            )
        }
        return flow {
            while(!(outputDone && surfaceToFlowChannel.isEmpty) && !cancelable.cancel.get()) {
                try {
                    if(!surfaceToFlowChannel.isEmpty) {
                        Log.d(
                            "flow",
                            "outputDone = $outputDone, isEmpty = {$surfaceToFlowChannel.isEmpty}, cancel = {$cancelable.cancel.get()}"
                        )
                        emit(surfaceToFlowChannel.receive())
                    }
                } catch (e: Exception) {
                    if(e !is java.util.concurrent.CancellationException) {
                        e.printStackTrace()
                    }
                }
            }
        }.onCompletion {
            release(outputSurface, decoder, extractor)
            reset()
        }
    }
    fun reset(){
        pauseable.pause.set(false)
        cancelable.cancel.set(false)
        currentDecodeFrame.set(0)
        releasedLatch = CountDownLatch(0)
        outputDone = false
        surfaceToFlowChannel = Channel<ByteArray>()
        released.set(false)
    }
    fun extractMpegFrames(
        inputVideo: Uri,
        timeInSec: List<Double>,
        outputDir: Uri,
        photoQuality: Int,
        context: Context? = null
    ): Observable<Progress> {

        val startTime = System.currentTimeMillis()

        val cancelable = Cancelable()

        var decoder: MediaCodec? = null
        var outputSurface: CodecOutputSurface? = null
        var extractor: MediaExtractor? = null


        return Observable.create { emitter ->

            val outputPath = outputDir.path

            val saveWidth: Int
            val saveHeight: Int

            if (emitter.isDisposed)
                return@create
            extractor = MediaExtractor()
            if (inputVideo.scheme == null || inputVideo.scheme == "file") {
                val inputFilePath = inputVideo.path
                val inputFile = File(inputFilePath!!)   // must be an absolute path
                // The MediaExtractor error messages aren't very useful.  Check to see if the input
                // file exists so we can throw a better one if it's not there.
                if (!inputFile.canRead()) {
                    emitter.onError(FileNotFoundException("Unable to read $inputFile"))
                }
                extractor!!.setDataSource(inputFile.toString())
            }else{
                val headers = mapOf("User-Agent" to "media converter")
                extractor!!.setDataSource(context!!, inputVideo, headers)
            }




            val videoTrackIndex = getTrackId(extractor!!, "video")
            if (videoTrackIndex < 0) {
                emitter.onError(RuntimeException("No video track found in $inputVideo"))
            }
            extractor!!.selectTrack(videoTrackIndex)

            val format = extractor!!.getTrackFormat(videoTrackIndex)
            val rotation = if(format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION)
                           else 0

            saveWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            saveHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            log(
                "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                        format.getInteger(MediaFormat.KEY_HEIGHT)
            )

            val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
            val duration = format.getLong(MediaFormat.KEY_DURATION)

            val secToMicroSec = 1000000
            val totalFrame = (duration * frameRate / secToMicroSec).toInt()

            log(
                "Frame rate is = " + frameRate +
                        " Total duration is in microSec = " + duration +
                        " Total frame count = " + totalFrame
            )

            //Can't use timeStamp directly, instead we need to get which frame we need to get
            val desiredFrames = getDesiredFrames(timeInSec, frameRate)

             log("Desired frames list is $desiredFrames")
            // Could use width/height from the MediaFormat to get full-size frames.
            outputSurface = CodecOutputSurface(saveWidth, saveHeight)

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            decoder?.configure(format, outputSurface!!.surface, null, 0)
            decoder?.start()
            doExtract(
                extractor!!,
                videoTrackIndex,
                decoder!!,
                outputSurface!!,
                desiredFrames,
                outputPath,
                photoQuality,
                emitter,
                totalFrame,
                startTime,
                cancelable,
                rotation
            )

        }.doOnDispose {
            cancelable.cancel.set(true)
        }.doOnComplete {
            release(outputSurface, decoder, extractor)
        }
    }

    /**
     * @param timeInSec = desired video frame times in sec
     * @param frameRate = video frame rate
     * @return list of frame numbers which points exact frame in given time
     *
     *
     * While using mediaCodec we can't seek to desired time, instead of that need to figure out which frame we needed
     * to calculate that, need to multiply desired frame time with frame rate
     *
     *
     * Example = Want to get the frame at 6.34 sec. We have a 30 frame rate video
     * 6.34*30 = 190,2 th frame -> we need int or long number so need to round it down
     */
    private fun getDesiredFrames(timeInSec: List<Double>, frameRate: Int): List<Int> {

        val desiredFrames = ArrayList<Int>()

        for (i in timeInSec.indices) {
            val desiredTimeFrames = (timeInSec[i] * frameRate).toInt()
            desiredFrames.add(desiredTimeFrames)
        }
        return desiredFrames
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private fun getTrackId(extractor: MediaExtractor, mimePrefix: String): Int {
        // Select the first video track we find, ignore the rest.
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("$mimePrefix/")) {
                log("Extractor selected track $i ($mime): $format")
                return i
            }
        }

        return -1
    }

    internal class Cancelable {
        val cancel = AtomicBoolean(false)
    }
    internal class Pauseable {
        val pause = AtomicBoolean(false)
    }

    companion object {
        private const val TAG = "ExtractMpegFrames"
    }

    // remove frames when the frameRate is higher than the maximum frameRate
    private fun getFrameIds(frameRate: Int): List<Int>{
        val frameIds = mutableListOf<Int>()
        for(i in 0 .. frameRate - 1){
            frameIds.add(i)
        }
        val MAX_FRAME_RATE = 24
        if(frameRate <= MAX_FRAME_RATE) {
            return frameIds
        }else{
            val framesToDrop = frameRate - MAX_FRAME_RATE
            return getDropFrameIdsOneRound(frameIds, framesToDrop)
        }
    }
    private fun getDropFrameIdsOneRound(frameIds: MutableList<Int>, dropFrameCount: Int):MutableList<Int>{
        var dropFrameLeft = dropFrameCount
        if(dropFrameCount == 0) {
            return frameIds
        }else{
            val dropStep = if(frameIds.size % dropFrameCount == 0){
                frameIds.size / dropFrameCount
            }else{
                frameIds.size / dropFrameCount + 1
            }
            var i = 0
            while(i<frameIds.size){
                frameIds.removeAt(i)
                dropFrameLeft -= 1
                i = i - 1 + dropStep
            }
            return getDropFrameIdsOneRound(frameIds, dropFrameLeft)
        }
    }
    @Throws(IOException::class)
    internal suspend fun extractOneFrame(
        extractor: MediaExtractor,
        trackIndex: Int,
        decoder: MediaCodec,
        outputSurface: CodecOutputSurface,
        photoQuality: Int,
        scalePercent: Int,
        totalFrame: Int,
        rotation: Int,
        frameRate: Int
    ):ByteArray {
        val TIMEOUT_USEC = 10000
        val decoderInputBuffers = decoder.inputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = this.currentDecodeFrame.get()
        var decodeCount = this.currentDecodeFrame.get()
        var inputDone = false
        var outputDone = false
        var frameArray = ByteArray(0)

        while (!outputDone) {
            // Feed more data to the decoder.
            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                if (inputBufIndex >= 0) {
                    log("inputBufIndex $inputBufIndex")

                    val inputBuf = decoderInputBuffers[inputBufIndex]
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    val chunkSize = extractor.readSampleData(inputBuf, 0)
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                        log("sent input EOS")
                    } else {
                        if (extractor.sampleTrackIndex != trackIndex) {
                            log(
                                "WEIRD: got sample from track " +
                                        extractor.sampleTrackIndex + ", expected " + trackIndex
                            )
                        }

                        log("decode count = $decodeCount")

                        val presentationTimeUs = extractor.sampleTime

                        decoder.queueInputBuffer(
                            inputBufIndex, 0, chunkSize,
                            presentationTimeUs, 0 /*flags*/
                        )

                        log(
                            "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize
                        )

                        inputChunk++
                        // extractor.advance()
                    }

                } else {
                    log("input buffer not available")
                }
            }

            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    log("no output from decoder available")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                    log("decoder output buffers changed")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    log("decoder output format changed: $newFormat")
                } else if (decoderStatus < 0) {
                    Log.e("Decoder",
                        "unexpected result from decoder.dequeueOutputBuffer: $decoderStatus"
                    )
                } else { // decoderStatus >= 0
                    log(
                        "surface decoder given buffer " + decoderStatus +
                                " (size=" + info.size + ")"
                    )
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        log("output EOS")
                    }

                    val doRender = info.size != 0

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                    if (doRender) {
                        log("awaiting decode of frame $decodeCount")
                        outputSurface.awaitNewImage()
                        log("awaitNewImage passed: $decodeCount")
                        outputSurface.drawImage(rotation)
                        log("drawImage passed: $decodeCount")
                        try {
                            frameArray = outputSurface.frameToArray(
                                    photoQuality,
                                    scalePercent
                                )
                            outputDone = true
                            log("surfaceToFlowChannel sent: $decodeCount")
                        } catch (e: java.lang.Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        return frameArray
    }
        @Throws(IOException::class)
        internal suspend fun doExtractToFlow(
            extractor: MediaExtractor,
            trackIndex: Int,
            decoder: MediaCodec,
            outputSurface: CodecOutputSurface,
            photoQuality: Int,
            scalePercent: Int,
            totalFrame: Int,
            cancel: Cancelable,
            pause: Pauseable,
            rotation: Int,
            frameRate: Int
        ) {
            val TIMEOUT_USEC = 10000
            val decoderInputBuffers = decoder.inputBuffers
            val info = MediaCodec.BufferInfo()
            var inputChunk = this.currentDecodeFrame.get()
            var decodeCount = this.currentDecodeFrame.get()
            var inputDone = false

            val frameIds = getFrameIds(frameRate)
            while (!outputDone && !pause.pause.get()) {

//                if (cancel.cancel.get()) {
//                    //outputPath?.let { MediaCodecTranscoder.deleteFolder(it) }
//                    //TODO: cancel mjpegSharedFlow
//                    release(outputSurface, decoder, extractor)
//                    completeLatch.countDown()
//                    return
//                }
//                log("loop")

                // Feed more data to the decoder.
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                    if (inputBufIndex >= 0) {
                        log("inputBufIndex $inputBufIndex")

                        val inputBuf = decoderInputBuffers[inputBufIndex]
                        // Read the sample data into the ByteBuffer.  This neither respects nor
                        // updates inputBuf's position, limit, etc.
                        val chunkSize = extractor.readSampleData(inputBuf, 0)
                        if (chunkSize < 0) {
                            // End of stream -- send empty frame with EOS flag set.
                            decoder.queueInputBuffer(
                                inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                            log("sent input EOS")
                        } else {
                            if (extractor.sampleTrackIndex != trackIndex) {
                                log(
                                    "WEIRD: got sample from track " +
                                            extractor.sampleTrackIndex + ", expected " + trackIndex
                                )
                            }

                            log("decode count = $decodeCount")

                            val presentationTimeUs = extractor.sampleTime

                            decoder.queueInputBuffer(
                                inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/
                            )

                            log(
                                "submitted frame " + inputChunk + " to dec, size=" +
                                        chunkSize
                            )

                            inputChunk++
                            extractor.advance()
                        }

                    } else {
                        log("input buffer not available")
                    }
                }

                if (!outputDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        log("no output from decoder available")
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // not important for us, since we're using Surface
                        log("decoder output buffers changed")
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        log("decoder output format changed: $newFormat")
                    } else if (decoderStatus < 0) {
                        Log.e("Decoder",
                            "unexpected result from decoder.dequeueOutputBuffer: $decoderStatus"
                        )
                    } else { // decoderStatus >= 0
                        log(
                            "surface decoder given buffer " + decoderStatus +
                                    " (size=" + info.size + ")"
                        )
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            log("output EOS")
                            outputDone = true
                        }

                        val doRender = info.size != 0

                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                        // that the texture will be available before the call returns, so we
                        // need to wait for the onFrameAvailable callback to fire.
                        decoder.releaseOutputBuffer(decoderStatus, doRender)
                        if (doRender) {
                            log("awaiting decode of frame $decodeCount")

                            outputSurface.awaitNewImage()
                            log("awaitNewImage passed: $decodeCount")
                            if(frameIds.size == frameRate || frameIds.contains(decodeCount % frameRate)) {
                                outputSurface.drawImage(rotation)
                                log("drawImage passed: $decodeCount")
                                try {
                                    surfaceToFlowChannel.send(
                                        outputSurface.frameToArray(
                                            photoQuality,
                                            scalePercent
                                        )
                                    )
                                    log("surfaceToFlowChannel sent: $decodeCount")
                                } catch (e: java.lang.Exception) {
                                    e.printStackTrace()
                                }
                            }

                            log("saving frames $decodeCount")

                            if (decodeCount < totalFrame) {
                                decodeCount++
                                this.currentDecodeFrame.incrementAndGet()
                            }
                        }
                    }
                }
            }
        }

        @Throws(IOException::class)
        internal fun doExtract(
            extractor: MediaExtractor,
            trackIndex: Int,
            decoder: MediaCodec,
            outputSurface: CodecOutputSurface,
            desiredFrames: List<Int>,
            outputPath: String?,
            photoQuality: Int,
            observer: ObservableEmitter<Progress>,
            totalFrame: Int,
            startTime: Long,
            cancel: Cancelable,
            rotation: Int
        ) {
            val TIMEOUT_USEC = 10000
            val decoderInputBuffers = decoder.inputBuffers
            val info = MediaCodec.BufferInfo()
            var inputChunk = 0
            var decodeCount = 0
            var frameSaveTime: Long = 0
            var frameCounter = 0

            var outputDone = false
            var inputDone = false
            while (!outputDone) {

                if (cancel.cancel.get()) {
                    outputPath?.let { MediaCodecTranscoder.deleteFolder(it) }
                    release(outputSurface, decoder, extractor)
                    return
                }
//                log("loop")

                // Feed more data to the decoder.
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                    if (inputBufIndex >= 0) {
//                        log("inputBufIndex $inputBufIndex")

                        val inputBuf = decoderInputBuffers[inputBufIndex]
                        // Read the sample data into the ByteBuffer.  This neither respects nor
                        // updates inputBuf's position, limit, etc.
                        val chunkSize = extractor.readSampleData(inputBuf, 0)
                        if (chunkSize < 0) {
                            // End of stream -- send empty frame with EOS flag set.
                            decoder.queueInputBuffer(
                                inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                            log("sent input EOS")
                        } else {
                            if (extractor.sampleTrackIndex != trackIndex) {
                                log(
                                    "WEIRD: got sample from track " +
                                            extractor.sampleTrackIndex + ", expected " + trackIndex
                                )
                            }

//                            log("decode count = $decodeCount")

                            val presentationTimeUs = extractor.sampleTime

                            decoder.queueInputBuffer(
                                inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/
                            )

//                            log(
//                                "submitted frame " + inputChunk + " to dec, size=" +
//                                        chunkSize
//                            )

                            inputChunk++
                            extractor.advance()
                        }

                    } else {
                        log("input buffer not available")
                    }
                }

                if (!outputDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        log("no output from decoder available")
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // not important for us, since we're using Surface
                        log("decoder output buffers changed")
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        log("decoder output format changed: $newFormat")
                    } else if (decoderStatus < 0) {
                        //fail("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                    } else { // decoderStatus >= 0
//                        log(
//                            "surface decoder given buffer " + decoderStatus +
//                                    " (size=" + info.size + ")"
//                        )
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            log("output EOS")
                            outputDone = true
                        }

                        val doRender = info.size != 0

                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                        // that the texture will be available before the call returns, so we
                        // need to wait for the onFrameAvailable callback to fire.
                        decoder.releaseOutputBuffer(decoderStatus, doRender)
                        if (doRender) {
//                            log("awaiting decode of frame $decodeCount")

                            if (desiredFrames.contains(decodeCount)) {
                                outputSurface.awaitNewImage()
                                outputSurface.drawImage(rotation)
                                val outputFile = File(
                                    outputPath,
                                    String.format("frame-%03d.jpg", frameCounter)
                                )
                                val startWhen = System.nanoTime()
                                outputSurface.saveFrame(outputFile.toString(), photoQuality)
                                frameSaveTime += System.nanoTime() - startWhen
                                frameCounter++

//                                log("saving frames $decodeCount")

                                observer.onNext(
                                    Progress(
                                        (decodeCount.toFloat() / totalFrame.toFloat() * 100).toInt(),
                                        null,
                                        Uri.parse(outputFile.absolutePath),
                                        System.currentTimeMillis() - startTime
                                    )
                                )

                            }
                            if (decodeCount < totalFrame) {
                                decodeCount++
                            }
                        }
                    }
                }
            }

            observer.onNext(
                Progress(
                    (decodeCount.toFloat() / totalFrame.toFloat() * 100).toInt(),
                    "total saved frame = $frameCounter",
                    Uri.parse(outputPath),
                    System.currentTimeMillis() - startTime
                )
            )

            observer.onComplete()
        }

    private fun release(
        outputSurface: CodecOutputSurface?,
        decoder: MediaCodec?,
        extractor: MediaExtractor?
    ) {
        if(!released.getAndSet(true)) {
            outputSurface?.release()
            decoder?.stop()
            decoder?.release()
            extractor?.release()
            cancelable.cancel.set(false)
            releasedLatch.countDown()
            Log.d("worker", "releasedLatch set 0")

        }
    }
}