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
import android.net.Uri
import android.util.Log
import com.exozet.transcoder.ffmpeg.Progress
import com.exozet.transcoder.ffmpeg.log
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


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
class MediaCodecExtractAudio {
    private var extractor: MediaExtractor? = null
    private val pauseable = Pauseable()
    private val cancelable = Cancelable()
    private var completeLatch = CountDownLatch(0)
    private var audioCompleteLatch = CountDownLatch(0)
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
//        XLog.e(getLog("onCoroutineException"), throwable)
        Log.e("MediaCodecExtractImages", throwable.toString())
    }
    private val eglDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineScope = CoroutineScope(SupervisorJob() + eglDispatcher + coroutineExceptionHandler)
    private var currentSampleTime: Long = 0
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
        context: Context? = null,
        audioStartTime: Double = 0.0,
        audioEndTime: Double = (-1).toDouble(),
        loop: Boolean = true
    ): Flow<ByteArray> {
        return flow {
            extractor = MediaExtractor()
            doExtractAudioToFlow(extractor, context, inputVideo, this, audioStartTime)
        }.onCompletion {
            //release(outputSurface, decoder, extractor)
        }
    }

    /**
     * @param audioStartTime starting time in milliseconds for trimming. Set to
     * negative if starting from beginning.
     * @param endMs end time for trimming in milliseconds. Set to negative if
     * no trimming at the end.
     * @throws IOException
     */
    @SuppressLint("NewApi", "WrongConstant")
    @Throws(IOException::class)
    suspend fun doExtractAudioToFlow(
        extractor: MediaExtractor?,
        context: Context?,
        inputVideo: Uri,
        flowCollector: FlowCollector<ByteArray>,
        audioStartTime: Double = -1.0,
        endMs: Int = -1
    ) {
        val headers = mapOf<String, String>("User-Agent" to "media converter")
        if (inputVideo.scheme == null || inputVideo.scheme == "file") {
            val inputFilePath = inputVideo.path
            val inputFile = File(inputFilePath!!)   // must be an absolute path
            // The MediaExtractor error messages aren't very useful.  Check to see if the input
            // file exists so we can throw a better one if it's not there.
            if (!inputFile.canRead()) {
                throw FileNotFoundException("Unable to read $inputFile")
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
        var audioTrackId = -1
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("audio/")) {
                audioTrackId = i
                break
            }
        }
        if (audioTrackId == -1) {
            throw FileNotFoundException("Unable to get audio track")
        }
        extractor.selectTrack(audioTrackId)
        val format = extractor.getTrackFormat(audioTrackId)
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            val newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            bufferSize = if (newSize > bufferSize) newSize else bufferSize
        }
        if (bufferSize < 0) {
            bufferSize = DEFAULT_BUFFER_SIZE
        }
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        // Set up the orientation and starting time for extractor.
        val retrieverSrc = MediaMetadataRetriever()
        retrieverSrc.setDataSource(context, inputVideo)
        val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
        val secToMicroSec = 1000000
        val realStartTime: Long =
            if (audioStartTime > 0.01) (audioStartTime * secToMicroSec).toLong()
            else this@MediaCodecExtractAudio.currentSampleTime
        if (realStartTime > 0.01) {
            extractor.seekTo(realStartTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        // Copy the samples from MediaExtractor to MediaMuxer. We will loop
        // for copying each sample and stop when we get to the end of the source
        // file or exceed the end time of the trimming.
        val offset = 0
        var trackIndex = -1
        val dstBufLength = bufferSize + 7
        val dstBuf: ByteBuffer = ByteBuffer.allocate(bufferSize)
        val adtsArray = ByteArray(7)
        var dstBufArray: ByteArray
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
                currentSampleTime = extractor.sampleTime
                if (endMs > 0 && bufferInfo.presentationTimeUs > endMs.toLong() * 1000) {
                    Log.d(TAG, "The current sample is over the trim end time.")
                    break
                } else {
                    bufferInfo.flags = extractor.sampleFlags
                    addADTStoPacket(adtsArray, dstBufLength)
                    dstBufArray = adtsArray + dstBuf.array()
                    flowCollector.emit(dstBufArray)
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