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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer


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
    private var seekTime = 0.0
    private var audioTrackIndex = -1

    fun seek(time:Double) {
//        seekTime = time
    }

    fun extractAudioToStream(inputVideo: Uri, context: Context?): InputStream {
        val inputStream = object : InputStream() {
            private val mediaExtractor = initMediaExtractor(inputVideo, context)
            private var samplePacketBuffer: ByteBuffer? = null
            private var sampleBufferSize: Int = 0
            private var mergedPacketArray: ByteArray? = null
            private var mergedPacketSize: Int = 0
            private var bytesRead = 0 //The number of bytes in packet already been read
//            private var offsetToSkip = 0 //To skip the specific bytes, after extractor.seekTo(), there may be offset to skip in the sample

            fun initMediaExtractor(inputVideo: Uri, context: Context?):MediaExtractor{
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
                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)
                    if (mimeType?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        sampleBufferSize = if(trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                                           else 64 * 1024
                        samplePacketBuffer = ByteBuffer.allocate(sampleBufferSize)
                        extractor.selectTrack(audioTrackIndex)
                        break
                    }
                }
                return extractor
            }

            fun resetToInit() {
                mediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                mergedPacketSize = 0
                bytesRead = 0
                seekTime = 0.0
            }

            override fun read(): Int {
                val buffer = ByteArray(1)
                return if (read(buffer) != -1) buffer[0].toInt() and 0xff else -1
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (audioTrackIndex == -1 || this.samplePacketBuffer == null) {
                    resetToInit()
                    return -1
                }

                if (mergedPacketSize == -1) {
                    resetToInit()
                    return -1
                }
                val bytesToRead = minOf(length, mergedPacketSize)
                if(bytesToRead > 0){ // add adts
                    mergedPacketArray?.copyInto(buffer, offset, bytesRead, bytesToRead)
                    bytesRead += bytesToRead
                }

                if (bytesRead >= mergedPacketSize) { // All bytes are read, then begin fetching the next sample
                    if(bytesRead == 0 && mergedPacketSize == 0) {
                        mediaExtractor.seekTo((seekTime * 1000000).toLong(),
                            MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    val sampleSize = mediaExtractor.readSampleData(this.samplePacketBuffer!!, 0)
                    if (sampleSize >= 0) {
                        val trackFormat = mediaExtractor.getTrackFormat(audioTrackIndex)

                        val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val aacProfile = if(trackFormat.containsKey(MediaFormat.KEY_AAC_PROFILE)) trackFormat.getInteger(MediaFormat.KEY_AAC_PROFILE)
                                         else 2
                        val adtsArray = ByteArray(7)
                        mergedPacketSize = sampleSize + 7
                        addADTStoPacket(adtsArray, mergedPacketSize, aacProfile, sampleRate, channelCount)
                        mergedPacketArray = adtsArray + this.samplePacketBuffer!!.array()
                        bytesRead = 0
                        mediaExtractor.advance()
                    } else {
                        mergedPacketSize = -1
                    }
                }

                return bytesToRead
            }

            override fun close() {
                mediaExtractor.release()
            }
        }

        return inputStream
    }


    /**
     * Add ADTS header at the beginning of each and every AAC packet.
     * This is needed as MediaCodec encoder generates a packet of raw
     * AAC data.
     *
     * Note the packetLen must count in the ADTS header itself.
     */
    private fun addADTStoPacket(packet: ByteArray, packetLen: Int, profile: Int, sampleRate: Int, channelCount: Int) {
        //39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
        val freqIdx = when(sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4
        } //44.1KHz
        val chanCfg = when (channelCount) {
            8 -> 7
            else -> channelCount
        }

        // fill in ADTS data
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = ((profile - 1 shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = ((chanCfg and 3 shl 6) + (packetLen shr 11)).toByte()
        packet[4] = (packetLen and 0x7FF shr 3).toByte()
        packet[5] = ((packetLen and 7 shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }


    companion object {
        private const val TAG = "MediaCodecExtractAudio"
    }


}