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

import android.annotation.SuppressLint
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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
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
class MediaCodecExtractImages {
    private var decoder: MediaCodec? = null
    private lateinit var outputSurface: CodecOutputSurface
    private var extractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null
    private val pauseable = Pauseable()
    private val cancelable = Cancelable()
    private val currentDecodeFrame = AtomicInteger(0)
    private var completeLatch = CountDownLatch(0)
    private var audioCompleteLatch = CountDownLatch(0)
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
//        XLog.e(getLog("onCoroutineException"), throwable)
        Log.e("MediaCodecExtractImages", throwable.toString())
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)
    fun pause(){
        pauseable.pause.set(true)
    }

    fun cancel(){
        //pause()
        cancelable.cancel.set(true)
        completeLatch.await()
        //release(outputSurface, decoder, extractor)
    }

    fun extractAudioToFlow(
        inputVideo: Uri,
        audioSharedFlow: MutableStateFlow<ByteArray>,
        context: Context? = null,
        audioStartTime: Double = 0.0,
        audioEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): Observable<Progress> {
        pauseable.pause.set(false)
        cancelable.cancel.set(false)
        val startTime = System.currentTimeMillis()
        var extractor = this.audioExtractor
        var decoder = this.decoder
        val pauseable = this.pauseable
        val cancelable = this.cancelable
        this.audioCompleteLatch = CountDownLatch(1)
        var compLatch = this.audioCompleteLatch

        return Observable.create<Progress>{ emitter ->
            if (emitter.isDisposed)
                return@create
            extractor = MediaExtractor()
            //val dstPath = context!!.cacheDir.absolutePath + File.separator + "temp.mp4"
            //genVideoUsingMuxer(extractor, emitter, context, inputVideo, dstPath)
            doExtractAudioToFlow(extractor, emitter, context, inputVideo, audioSharedFlow)

        }.doOnDispose {
            cancelable.cancel.set(true)
        }.doOnComplete {
            //release(outputSurface, decoder, extractor)
        }
    }

    /**
     * @param srcPath the path of source video file.
     * @param dstPath the path of destination video file.
     * @param startMs starting time in milliseconds for trimming. Set to
     * negative if starting from beginning.
     * @param endMs end time for trimming in milliseconds. Set to negative if
     * no trimming at the end.
     * @param useAudio true if keep the audio track from the source.
     * @param useVideo true if keep the video track from the source.
     * @throws IOException
     */
    @SuppressLint("NewApi", "WrongConstant")
    @Throws(IOException::class)
    fun doExtractAudioToFlow(
        extractor: MediaExtractor?,
        emitter: ObservableEmitter<Progress>,
        context: Context?,
        inputVideo: Uri,
        audioSharedFlow: MutableStateFlow<ByteArray>,
        startMs: Int = -1,
        endMs: Int = -1,
        useAudio: Boolean = true,
        useVideo: Boolean = false
    ) {
        val headers = mapOf<String, String>("User-Agent" to "media converter")
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
            extractor!!.setDataSource(context!!, inputVideo, headers)
        }
        val trackCount = extractor.trackCount

        // Set up the tracks and retrieve the max buffer size for selected
        // tracks.
        val indexMap = HashMap<Int, Int>(trackCount)
        var bufferSize = -1
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            var selectCurrentTrack = false
            if (mime!!.startsWith("audio/") && useAudio) {
                selectCurrentTrack = true
            } else if (mime.startsWith("video/") && useVideo) {
                selectCurrentTrack = true
            }
            if (selectCurrentTrack) {
                extractor.selectTrack(i)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    bufferSize = if (newSize > bufferSize) newSize else bufferSize
                }
            }
        }
        if (bufferSize < 0) {
            bufferSize = DEFAULT_BUFFER_SIZE
        }
        // Set up the orientation and starting time for extractor.
        val retrieverSrc = MediaMetadataRetriever()
        retrieverSrc.setDataSource(context, inputVideo)
        if (startMs > 0) {
            extractor.seekTo((startMs * 1000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        // Copy the samples from MediaExtractor to MediaMuxer. We will loop
        // for copying each sample and stop when we get to the end of the source
        // file or exceed the end time of the trimming.
        val offset = 0
        var trackIndex = -1
        val dstBufLength = bufferSize + 7
        val dstBuf: ByteBuffer = ByteBuffer.allocate(bufferSize)
        val adtsArray = ByteArray(7)
        var dstBufArray = ByteArray(dstBufLength)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            bufferInfo.offset = offset
            bufferInfo.size = extractor.readSampleData(dstBuf, offset)
            if (bufferInfo.size < 0) {
                Log.d(TAG, "Saw audio input EOS.")
                bufferInfo.size = 0
                break
            } else {
                bufferInfo.presentationTimeUs = extractor.sampleTime
                if (endMs > 0 && bufferInfo.presentationTimeUs > endMs.toLong() * 1000) {
                    Log.d(TAG, "The current sample is over the trim end time.")
                    break
                } else {
                    bufferInfo.flags = extractor.sampleFlags
                    addADTStoPacket(adtsArray, dstBufLength)
                    dstBufArray = adtsArray + dstBuf.array()
                    audioSharedFlow.tryEmit(dstBufArray)
                    extractor.advance()
                }
            }
        }
        return
    }

    /**
     * Add ADTS header at the beginning of each and every AAC packet.
     * This is needed as MediaCodec encoder generates a packet of raw
     * AAC data.
     *
     * Note the packetLen must count in the ADTS header itself.
     */
    private fun addADTStoPacket(packet: ByteArray, packetLen: Int) {
        val profile = 2 //AAC LC
        //39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
        val freqIdx = 4 //44.1KHz
        val chanCfg = 2 //CPE

        // fill in ADTS data
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = ((profile - 1 shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = ((chanCfg and 3 shl 6) + (packetLen shr 11)).toByte()
        packet[4] = (packetLen and 0x7FF shr 3).toByte()
        packet[5] = ((packetLen and 7 shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }
    /**
     * @param srcPath the path of source video file.
     * @param dstPath the path of destination video file.
     * @param startMs starting time in milliseconds for trimming. Set to
     * negative if starting from beginning.
     * @param endMs end time for trimming in milliseconds. Set to negative if
     * no trimming at the end.
     * @param useAudio true if keep the audio track from the source.
     * @param useVideo true if keep the video track from the source.
     * @throws IOException
     */
    @SuppressLint("NewApi", "WrongConstant")
    @Throws(IOException::class)
    fun genVideoUsingMuxer(
        extractor: MediaExtractor?,
        emitter: ObservableEmitter<Progress>,
        context: Context?,
        inputVideo: Uri,
        dstPath: String,
        startMs: Int = -1,
        endMs: Int = -1,
        useAudio: Boolean = true,
        useVideo: Boolean = false
    ) {
        val headers = mapOf<String, String>("User-Agent" to "media converter")
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
            extractor!!.setDataSource(context!!, inputVideo, headers)
        }
        val trackCount = extractor.trackCount
        // Set up MediaMuxer for the destination.
        val muxer: MediaMuxer
        muxer = MediaMuxer(dstPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // Set up the tracks and retrieve the max buffer size for selected
        // tracks.
        val indexMap = HashMap<Int, Int>(trackCount)
        var bufferSize = -1
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            var selectCurrentTrack = false
            if (mime!!.startsWith("audio/") && useAudio) {
                selectCurrentTrack = true
            } else if (mime.startsWith("video/") && useVideo) {
                selectCurrentTrack = true
            }
            if (selectCurrentTrack) {
                extractor.selectTrack(i)
                val dstIndex = muxer.addTrack(format)
                indexMap[i] = dstIndex
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    bufferSize = if (newSize > bufferSize) newSize else bufferSize
                }
            }
        }
        if (bufferSize < 0) {
            bufferSize = DEFAULT_BUFFER_SIZE
        }
        // Set up the orientation and starting time for extractor.
        val retrieverSrc = MediaMetadataRetriever()
        retrieverSrc.setDataSource(context, inputVideo)
        val degreesString =
            retrieverSrc.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        if (degreesString != null) {
            val degrees = degreesString.toInt()
            if (degrees >= 0) {
                muxer.setOrientationHint(degrees)
            }
        }
        if (startMs > 0) {
            extractor.seekTo((startMs * 1000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        // Copy the samples from MediaExtractor to MediaMuxer. We will loop
        // for copying each sample and stop when we get to the end of the source
        // file or exceed the end time of the trimming.
        val offset = 0
        var trackIndex = -1
        val dstBuf: ByteBuffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        muxer.start()
        while (true) {
            bufferInfo.offset = offset
            bufferInfo.size = extractor.readSampleData(dstBuf, offset)
            if (bufferInfo.size < 0) {
                Log.d(TAG, "Saw input EOS.")
                bufferInfo.size = 0
                break
            } else {
                bufferInfo.presentationTimeUs = extractor.sampleTime
                if (endMs > 0 && bufferInfo.presentationTimeUs > endMs.toLong() * 1000) {
                    Log.d(TAG, "The current sample is over the trim end time.")
                    break
                } else {
                    bufferInfo.flags = extractor.sampleFlags
                    trackIndex = extractor.sampleTrackIndex
                    muxer.writeSampleData(indexMap[trackIndex]!!, dstBuf, bufferInfo)
                    extractor.advance()
                }
            }
        }
        muxer.stop()
        muxer.release()
        return
    }

    private var outputDone = false
    private val channel = Channel<ByteArray>()
    /**
     * Tests extraction from an MP4 to a series of PNG files.
     *
     *
     * We scale the video to 640x480 for the PNG just to demonstrate that we can scale the
     * video with the GPU.  If the input video has a different aspect ratio, we could preserve
     * it by adjusting the GL viewport to get letterboxing or pillarboxing, but generally if
     * you're extracting frames you don't want black bars.
     */
    fun extractMpegFramesToFlow(
        inputVideo: Uri,
        photoQuality: Int,
        context: Context? = null,
        videoStartTime: Double = 0.0,
        videoEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): Flow<ByteArray> {

        coroutineScope.launch {
            pauseable.pause.set(false)
            cancelable.cancel.set(false)
            val startTime = System.currentTimeMillis()
            //var outputSurface = this.outputSurface
            this@MediaCodecExtractImages.completeLatch = CountDownLatch(1)
            var compLatch = this@MediaCodecExtractImages.completeLatch

            val saveWidth: Int
            val saveHeight: Int
//            if (emitter.isDisposed)
//                return@create
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
                val headers = mapOf<String, String>("User-Agent" to "media converter")
                extractor!!.setDataSource(context!!, inputVideo, headers)
            }

            val videoTrackIndex = getTrackId(extractor!!, "video")
            if (videoTrackIndex < 0) {
                throw RuntimeException("No video track found in ${inputVideo.toString()}")
            }
            extractor!!.selectTrack(videoTrackIndex)

            val format = extractor!!.getTrackFormat(videoTrackIndex)

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
            val realStartTime: Long =
                if (videoStartTime > 0.01) (videoStartTime * secToMicroSec).toLong()
                else this@MediaCodecExtractImages.currentDecodeFrame.get() * secToMicroSec.toLong() / frameRate

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
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            //NOTE: outputSurface should not be in flow, when switch thread, the EGLContext will change.
            //So I use a channel in Flow to receive and emit data
            doExtractToFlow(
                extractor!!,
                videoTrackIndex,
                decoder!!,
                outputSurface,
                photoQuality,
                totalFrame,
                realStartTime,
                cancelable,
                pauseable,
                compLatch
            )
        }
        return flow<ByteArray>{
            while(!(outputDone && channel.isEmpty)) {
                try {
                    emit(channel.receive())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.onCompletion {
            release(outputSurface, decoder, extractor)
        }
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


        return Observable.create<Progress>{ emitter ->

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
                val headers = mapOf<String, String>("User-Agent" to "media converter")
                extractor!!.setDataSource(context!!, inputVideo, headers)
            }




            val videoTrackIndex = getTrackId(extractor!!, "video")
            if (videoTrackIndex < 0) {
                emitter.onError(RuntimeException("No video track found in ${inputVideo.toString()}"))
            }
            extractor!!.selectTrack(videoTrackIndex)

            val format = extractor!!.getTrackFormat(videoTrackIndex)

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
                cancelable
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

        private val TAG = "ExtractMpegFrames"
    }

        /**
         * Work loop.
         */

        @Throws(IOException::class)
        internal suspend fun doExtractToFlow(
            extractor: MediaExtractor,
            trackIndex: Int,
            decoder: MediaCodec,
            outputSurface: CodecOutputSurface,
            photoQuality: Int,
            totalFrame: Int,
            startTime: Long,
            cancel: Cancelable,
            pause: Pauseable,
            completeLatch: CountDownLatch
        ) {
            val TIMEOUT_USEC = 10000
            val decoderInputBuffers = decoder.inputBuffers
            val info = MediaCodec.BufferInfo()
            var inputChunk = this.currentDecodeFrame.get()
            var decodeCount = this.currentDecodeFrame.get()
            var frameSaveTime: Long = 0
            var frameCounter = 0

            var inputDone = false
            extractor.seekTo(startTime, SEEK_TO_PREVIOUS_SYNC)
            while (!outputDone && !pause.pause.get()) {

                if (cancel.cancel.get()) {
                    //outputPath?.let { MediaCodecTranscoder.deleteFolder(it) }
                    //TODO: cancel mjpegSharedFlow
                    release(outputSurface, decoder, extractor)
                    completeLatch.countDown()
                    return
                }
                log("loop")

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
                        //fail("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
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
                            outputSurface.drawImage(true)
                            val startWhen = System.nanoTime()
                            try {
                                channel.send(outputSurface.frameToArray(photoQuality))
                            } catch (e:java.lang.Exception){
                                e.printStackTrace()
                            }


                            frameSaveTime += System.nanoTime() - startWhen
                            frameCounter++

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
            cancel: Cancelable
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
                                outputSurface.drawImage(true)
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
            outputSurface?.release()
            decoder?.stop()
            decoder?.release()
            extractor?.release()
            cancelable.cancel.set(false)
        }

}