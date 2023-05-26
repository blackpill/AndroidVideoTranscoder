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
import com.exozet.transcoder.ffmpeg.log
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
import java.util.concurrent.locks.ReentrantLock


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
    private val DEFAULT_PHOTO_QUALITY = 90
    private val DEFAULT_SCALE_PERCENT = 60
    //private var decoder: MediaCodec? = null
    //private lateinit var outputSurface: CodecOutputSurface
    private var extractor = MediaExtractor()
    private val extractorLock = ReentrantLock()
    private var videoTrackIndex = -1
    private val pauseable = Pauseable()
    private val cancelable = Cancelable()
    private val currentDecodeFrame = AtomicInteger(0)
    private var releasedLatch = CountDownLatch(0)
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
//        XLog.e(getLog("onCoroutineException"), throwable)
        throwable.printStackTrace()
        Log.e("MediaCodecExtractImages", throwable.toString())
    }
    private val eglDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineScope = CoroutineScope(SupervisorJob() + eglDispatcher + coroutineExceptionHandler)
    private var outputDone = false
    private var surfaceToFlowChannel = Channel<ByteArray>()
    private val released = AtomicBoolean(false)
    var currentTime:Double = 0.0

    //MediaExtractor must be initialed and released manually
    private fun initExtractor(inputVideo: Uri, context: Context?){
        if(extractorLock.isLocked) extractorLock.unlock()
        extractorLock.lock()
        extractor = MediaExtractor()
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
        videoTrackIndex = getTrackId(extractor, "video")
        if (videoTrackIndex < 0) {
            throw RuntimeException("No video track found in $inputVideo")
        }
        extractor.selectTrack(videoTrackIndex) // only take effect once
    }
    fun pause(pauseTime: Double){
        pauseable.pause.set(true)
        currentTime = pauseTime
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
        return runBlocking(eglDispatcher) {
            Log.d("HK_EXT", "getMetaInfo extractor inited")
            initExtractor(inputVideo, context)

            val format = extractor.getTrackFormat(videoTrackIndex)
            val rotation =
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger((MediaFormat.KEY_ROTATION))
                else 0
            val videoWidth =
                if (rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_HEIGHT)
                else format.getInteger(MediaFormat.KEY_WIDTH)
            val videoHeight =
                if (rotation == 90 || rotation == 270) format.getInteger(MediaFormat.KEY_WIDTH)
                else format.getInteger(MediaFormat.KEY_HEIGHT)
            log(
                "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                        format.getInteger(MediaFormat.KEY_HEIGHT)
            )
            val MAX_FRAME_RATE = 24
            val DEFAULT_FRAME_RATE = 30
            val frameRate =
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE)
                else DEFAULT_FRAME_RATE
            val duration =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
                else 0L
            val valuePacket = JSONObject()

            val adjustedFrameRate = if (frameRate > MAX_FRAME_RATE) MAX_FRAME_RATE
            else frameRate

            valuePacket.put("duration", duration)
            valuePacket.put("frameRate", adjustedFrameRate)
            valuePacket.put("uri", inputVideo.toString())
            valuePacket.put("videoWidth", videoWidth)
            valuePacket.put("videoHeight", videoHeight)
            extractor.release()
            if (extractorLock.isLocked) extractorLock.unlock()
            Log.d("HK_EXT", "getMetaInfo extractor released")
            valuePacket
        }
    }
    private fun calcScaledSize(size: Int): Int{
        val newSize:Int = size * initScalePercent.get() / 100
        if(newSize % 2 == 0){
            return newSize
        }else{
            return newSize + 1
        }
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
        context: Context? = null,
        videoStartTime: Double = 0.0
    ): ByteArray {
        Log.d("worker", "releasedLatch.await() start")
        releasedLatch.await()
        return runBlocking(eglDispatcher) {
            Log.d("HK_EXT", "seekone extractor inited")
            initExtractor(inputVideo, context)
            Log.d("worker", "releasedLatch.await() passed")
            currentTime = videoStartTime
            val decoder: MediaCodec?
    //        var extractor: MediaExtractor? = null
            val outputSurface: CodecOutputSurface?
            val frameArray: ByteArray
            released.getAndSet(false)

            val format = extractor.getTrackFormat(videoTrackIndex)
//            if (format.containsKey("display-width")){
//                format.setInteger("display-width", format.getInteger("display-width") * scalePercent.get() / 100)
//            }
//            if (format.containsKey("display-height")){
//                format.setInteger("display-height", format.getInteger("display-height") * scalePercent.get() / 100)
//            }
            format.setInteger(MediaFormat.KEY_WIDTH, calcScaledSize(format.getInteger(MediaFormat.KEY_WIDTH)))
            format.setInteger(MediaFormat.KEY_HEIGHT, calcScaledSize(format.getInteger(MediaFormat.KEY_HEIGHT)))
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
            val DEFAULT_FRAME_RATE = 30
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE)
                            else DEFAULT_FRAME_RATE
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
                            else 0L

            val secToMicroSec = 1000000
            val totalFrame = if(duration == 0L) 0
                             else (duration * frameRate / secToMicroSec).toInt()
            val realStartTime: Long =
                if (videoStartTime > 0.01) (videoStartTime * secToMicroSec).toLong()
                else this@MediaCodecExtractImage.currentDecodeFrame.get() * secToMicroSec.toLong() / frameRate
            extractor.seekTo(realStartTime, SEEK_TO_PREVIOUS_SYNC)
            log(
                "Frame rate is = " + frameRate +
                        " Total duration is in microSec = " + duration +
                        " Total frame count = " + totalFrame
            )

            // Could use width/height from the MediaFormat to get full-size frames.
            outputSurface = CodecOutputSurface(saveWidth, saveHeight)
            val surface = outputSurface.surface

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            //NOTE: outputSurface should not be in flow, when switch thread, the EGLContext will change.
            //So I use a channel in Flow to receive and emit data
            frameArray = extractOneFrame(
                extractor,
                videoTrackIndex,
                decoder,
                outputSurface,
                rotation
            )
            release(outputSurface, decoder, extractor)
            Log.d("HK_EXT", "seekone extractor released")
            frameArray
        }
    }
    private var photoQuality = AtomicInteger(DEFAULT_PHOTO_QUALITY)
    private var scalePercent = AtomicInteger(DEFAULT_SCALE_PERCENT)
    private var initScalePercent = AtomicInteger(DEFAULT_SCALE_PERCENT)
    fun setScalePercent(scale: Int){
        val tempScale = if(scale > 100) 100
                        else if(scale < 1) 1
                        else scale
        scalePercent.set(tempScale)
    }
    fun setInitScalePercent(scale: Int){
        val tempScale = if(scale > 100) 100
        else if(scale < 1) 1
        else scale
        initScalePercent.set(tempScale)
    }
    fun qualityChange(delta: Int){
        if(delta != 0){
            val tempQuality = photoQuality.get() + delta
            val finalQuality = when{
                tempQuality > 100 -> 100
                tempQuality < 10 -> 10
                else -> tempQuality
            }
            photoQuality.set(finalQuality)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun extractMpegFramesToFlow(
        inputVideo: Uri,
        context: Context? = null
    ): Flow<ByteArray> {
        Log.d("worker", "releasedLatch.await() start")
        releasedLatch.await()
        Log.d("worker", "releasedLatch.await() passed")
        Log.d("HK_EXT", "while extractor inited")
        var decoder: MediaCodec? = null
//        var extractor: MediaExtractor? = null
        var outputSurface: CodecOutputSurface? = null
        released.getAndSet(false)
        pauseable.pause.set(false)
        cancelable.cancel.set(false)
        coroutineScope.launch {
            Log.d("HK_EXT", "While init Thread is = " + Thread.currentThread())
            initExtractor(inputVideo, context)
            val format = extractor.getTrackFormat(videoTrackIndex)
//            if (format.containsKey("display-width")){
//                format.setInteger("display-width", format.getInteger("display-width") * scalePercent.get() / 100)
//            }
//            if (format.containsKey("display-height")){
//                format.setInteger("display-height", format.getInteger("display-height") * scalePercent.get() / 100)
//            }
            format.setInteger(MediaFormat.KEY_WIDTH, calcScaledSize(format.getInteger(MediaFormat.KEY_WIDTH)))
            format.setInteger(MediaFormat.KEY_HEIGHT, calcScaledSize(format.getInteger(MediaFormat.KEY_HEIGHT)))
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

            val DEFAULT_FRAME_RATE = 30
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE)
                            else DEFAULT_FRAME_RATE
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
                            else 0L

            val secToMicroSec = 1000000
            val totalFrame = if(duration == 0L) 0
                            else (duration * frameRate / secToMicroSec).toInt()
            val realStartTime: Long =
                if (currentTime > 0.01) (currentTime * secToMicroSec).toLong()
                else this@MediaCodecExtractImage.currentDecodeFrame.get() * secToMicroSec.toLong() / frameRate
            extractor.seekTo(realStartTime, SEEK_TO_PREVIOUS_SYNC)
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
                extractor,
                videoTrackIndex,
                decoder!!,
                outputSurface!!,
                totalFrame,
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
            release(outputSurface, decoder, extractor, eglDispatcher)
            Log.d("HK_EXT", "while extractor released")
            reset()
        }
    }
    private fun reset(){
        pauseable.pause.set(false)
        cancelable.cancel.set(false)
        currentDecodeFrame.set(0)
        releasedLatch = CountDownLatch(0)
        outputDone = false
        surfaceToFlowChannel = Channel()
        released.set(false)
        photoQuality.set(DEFAULT_PHOTO_QUALITY)
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
        private const val TAG = "MediaCodecExtractImage"
    }

    // remove frames when the frameRate is higher than the maximum frameRate
    private fun getFrameIds(frameRate: Int): List<Int>{
        val frameIds = mutableListOf<Int>()
        for(i in 0 .. frameRate - 1){
            frameIds.add(i)
        }
        val MAX_FRAME_RATE = 24
        return if(frameRate <= MAX_FRAME_RATE) {
            frameIds
        }else{
            val framesToDrop = frameRate - MAX_FRAME_RATE
            getDropFrameIdsOneRound(frameIds, framesToDrop)
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
    internal fun extractOneFrame(
        extractor: MediaExtractor,
        trackIndex: Int,
        decoder: MediaCodec,
        outputSurface: CodecOutputSurface,
        rotation: Int
    ):ByteArray {
        val TIMEOUT_USEC = 10000
        val decoderInputBuffers = decoder.inputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = this.currentDecodeFrame.get()
        val decodeCount = this.currentDecodeFrame.get()
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
                                    photoQuality.get(),
                                    100
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
    private lateinit var outputDoneCallback: ()->Unit
    fun setOutputDoneCallback(callback: () -> Unit) {
        outputDoneCallback = callback
    }
        @Throws(IOException::class)
        internal suspend fun doExtractToFlow(
            extractor: MediaExtractor,
            trackIndex: Int,
            decoder: MediaCodec,
            outputSurface: CodecOutputSurface,
            totalFrame: Int,
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
                                Log.d("TIME", "surfaceToFlowChannel.send $decodeCount at " + System.currentTimeMillis())
                                try {
                                    surfaceToFlowChannel.send(
                                        outputSurface.frameToArray(
                                            photoQuality.get(),
                                            scalePercent.get() * 100 / initScalePercent.get()
                                        )
                                    )
                                    log("surfaceToFlowChannel sent: $decodeCount")
                                } catch (e: java.lang.Exception) {
                                    e.printStackTrace()
                                }
                            }

                            log("saving frames $decodeCount")

                            if (decodeCount < totalFrame || totalFrame == 0) {
                                decodeCount++
                                this.currentDecodeFrame.incrementAndGet()
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            log("output EOS")
                            outputDone = true
                            outputDoneCallback.invoke()
                        }
                    }
                }
            }
            Log.d("HK_EXT", "while exited, pause = " + pause.pause.get())
        }

    private fun doRelease(
        outputSurface: CodecOutputSurface?,
        decoder: MediaCodec?,
        extractor: MediaExtractor?,
        dispatcher: ExecutorCoroutineDispatcher? = null
    ) {
        outputSurface?.release()
        decoder?.stop()
        decoder?.release()
        extractor?.release()
        Log.d("HK_EXT", "extractor really released in Thread " + Thread.currentThread())
        Log.d("HK_EXT", "While release Thread is = " + Thread.currentThread())
        if (extractorLock.isLocked) extractorLock.unlock()
    }
    private fun release(
        outputSurface: CodecOutputSurface?,
        decoder: MediaCodec?,
        extractor: MediaExtractor?,
        dispatcher: ExecutorCoroutineDispatcher? = null
    ) {
        if(!released.get()) {
            if(dispatcher != null) {
                runBlocking(eglDispatcher) {
                    doRelease(outputSurface, decoder, extractor, dispatcher)
                }
            }else{
                doRelease(outputSurface, decoder, extractor, dispatcher)
            }
            cancelable.cancel.set(false)
            releasedLatch.countDown()
            released.set(true)
            Log.d("worker", "releasedLatch set 0")
        }else{
            Log.d("worker", "decoder already released")
        }
    }
}