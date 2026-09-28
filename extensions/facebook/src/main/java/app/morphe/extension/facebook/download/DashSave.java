/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/DashSave.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Saves one DASH video track and one audio track as one MP4 file.
 *
 * <p>A DASH manifest keeps the picture and the sound in two files. One plain fetch gets each file.
 * Then {@code MediaMuxer} copies the samples of both into one file. It does not decode or encode
 * them again. So the file has the quality that the player streams, and the join takes less than a
 * second.
 *
 * <p>The two tracks and the result go into the cache of the app first, because the muxer must seek
 * in its files. Only the finished file goes into the gallery, through the same
 * {@link Downloader.Sink} as all other saves. So an error at any step leaves nothing in the gallery,
 * and the files in the cache are removed whatever happens.
 */
final class DashSave {

    private DashSave() {}

    /** The source every event of a DASH save carries in the diagnostic report. */
    private static final String SOURCE = "DashSave";

    private static final String CACHE_FOLDER = "hushfacebook-save";

    /** A file older than this is from a save that the system stopped. */
    private static final long STALE_MS = 60L * 60L * 1000L;

    private static final int DEFAULT_SAMPLE_BUFFER = 2 * 1024 * 1024;

    private static volatile Boolean canWriteAv1;

    /**
     * Whether this device can save an AV1 track. The muxer writes AV1 into an MP4 from Android 14.
     * The device must also have an AV1 decoder, or it cannot play the file.
     */
    static boolean canWriteAv1() {
        if (canWriteAv1 == null) canWriteAv1 = hasAv1Decoder();
        return canWriteAv1;
    }

    private static boolean hasAv1Decoder() {
        if (Build.VERSION.SDK_INT < 34) return false;

        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if ("video/av01".equalsIgnoreCase(type)) return true;
                }
            }
        } catch (Throwable ignored) {
            // No codec list, so no decoder.
        }

        return false;
    }

    /**
     * The folder every save works in, created and cleared of what a stopped save left, or
     * {@code null} when the cache can't hold one.
     */
    static File workFolder(Context application) {
        File folder = new File(application.getCacheDir(), CACHE_FOLDER);
        // mkdirs() answers false when another save made the folder a moment ago, so it only
        // failed if the folder still isn't there.
        if (!folder.mkdirs() && !folder.isDirectory()) return null;
        removeStale(folder);
        return folder;
    }

    /**
     * Download [video] and [audio], join them, and write the result to [sink]. This blocks and
     * never throws. [audio] is {@code null} for a video with no sound. Both tracks go through the
     * same checks as a single file, so nothing reaches the gallery unless both are Meta's media.
     */
    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        Downloader.Sink sink
    ) {
        return save(application, video, audio, sink, MediaUrlPolicy.META);
    }

    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        Downloader.Sink sink,
        MediaUrlPolicy policy
    ) {
        return save(application, video, audio, sink, policy, Downloader.MAX_BYTES);
    }

    /**
     * [maxBytes] holds the two tracks together, the way it holds a single file: the sound gets
     * what the picture left of it, and the joined file is held to it too. So what reaches the
     * gallery is never over the cap, whichever way it was saved.
     */
    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        Downloader.Sink sink,
        MediaUrlPolicy policy,
        long maxBytes
    ) {
        return save(application, video, audio, sink, policy, maxBytes, Downloader.SILENT);
    }

    /** As above, reporting each track's fetch to [progress] and stopping when it's cancelled. */
    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        Downloader.Sink sink,
        MediaUrlPolicy policy,
        long maxBytes,
        Downloader.Progress progress
    ) {
        File videoFile = null;
        File audioFile = null;
        File joined = null;

        try {
            File folder = workFolder(application);
            if (folder == null) return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "no cache folder");

            videoFile = File.createTempFile("video", ".mp4", folder);
            Downloader.Result result = Downloader.fetch(video.url, Downloader.Kind.VIDEO, videoFile, policy, maxBytes,
                progress);
            if (!result.ok()) return result;

            if (audio != null) {
                audioFile = File.createTempFile("audio", ".mp4", folder);
                // One count for the pair: the sound's bytes go on from the picture's.
                result = Downloader.fetch(audio.url, Downloader.Kind.AUDIO, audioFile, policy,
                    maxBytes - videoFile.length(), Downloader.after(videoFile.length(), progress));
                if (!result.ok()) return result;
            }

            if (progress.cancelled()) return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled before the join");
            joined = File.createTempFile("joined", ".mp4", folder);
            if (!join(videoFile, audioFile, joined, progress)) return cancelledJoining();
            // Joining writes boxes of its own, so the file itself is held to the cap as well.
            if (joined.length() > maxBytes) {
                return Downloader.Result.fail(Downloader.Status.TOO_LARGE,
                    "the joined file is " + joined.length() + " bytes, more than " + maxBytes);
            }

            return Downloader.publish(joined, "video/mp4", sink, progress);
        } catch (Throwable t) {
            // A cancel can end the join in a failure of its own, a muxer stopped with no sample
            // for one. It's still the person's cancel.
            if (progress.cancelled()) return cancelledJoining();
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the DASH save failed", t);
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "the tracks could not be joined");
        } finally {
            Downloader.delete(videoFile);
            Downloader.delete(audioFile);
            Downloader.delete(joined);
        }
    }

    // ---------------------------------------------------------------- internals

    private static Downloader.Result cancelledJoining() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled during the join");
    }

    /**
     * Copy the samples of both files into [out], in order of time. Answers false when [progress]
     * was cancelled first.
     *
     * <p>Each step writes the sample that comes first in time, from either track. A file with all
     * of the video before all of the sound also plays. But a player must then seek across the whole
     * file to start, and some players refuse that.
     *
     * <p>Cancel is read before every sample, between one native read and write and the next. A
     * native call that blocks isn't interrupted, so a cancel waits for that one call at most. Under
     * Robolectric (DashJoinTest) a minute of 720p, 4,384 samples, joined in 37 to 66 ms, and a
     * cancel half way ended the join within 1 ms. A phone's time per sample isn't measured yet.
     */
    private static boolean join(File video, File audio, File out, Downloader.Progress progress) throws IOException {
        MediaExtractor videoIn = null;
        MediaExtractor audioIn = null;
        MediaMuxer muxer = null;
        boolean started = false;
        Throwable failure = null;

        try {
            videoIn = new MediaExtractor();
            videoIn.setDataSource(video.getPath());
            if (audio != null) {
                audioIn = new MediaExtractor();
                audioIn.setDataSource(audio.getPath());
            }
            if (progress.cancelled()) return false;

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int bufferSize = DEFAULT_SAMPLE_BUFFER;

            MediaFormat videoFormat = selectTrack(videoIn, "video/");
            if (videoFormat == null) throw new IOException("the video file holds no video track");
            int videoTrack = muxer.addTrack(videoFormat);
            bufferSize = Math.max(bufferSize, maxInputSize(videoFormat));

            int audioTrack = -1;
            if (audioIn != null) {
                MediaFormat audioFormat = selectTrack(audioIn, "audio/");
                if (audioFormat == null) throw new IOException("the audio file holds no audio track");
                audioTrack = muxer.addTrack(audioFormat);
                bufferSize = Math.max(bufferSize, maxInputSize(audioFormat));
            }

            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // An AAC track with an edit list starts before zero: MediaExtractor reports its
            // priming frames at negative times, and MediaMuxer refuses a negative presentation
            // time. A loop that ended a track at its first negative time copied no sound at all.
            // So a track ends only on an empty read, and both tracks shift by the one lead-in, so
            // the gap between the picture and the sound survives the shift.
            long videoTime = videoIn.getSampleTime();
            long audioTime = audioIn == null ? Long.MAX_VALUE : audioIn.getSampleTime();
            long leadIn = Math.min(0L, Math.min(videoTime, audioIn == null ? 0L : audioTime));

            boolean videoDone = false;
            boolean audioDone = audioIn == null;

            while (!videoDone || !audioDone) {
                if (progress.cancelled()) return false;
                boolean takeVideo = !videoDone && (audioDone || videoTime <= audioTime);
                MediaExtractor from = takeVideo ? videoIn : audioIn;
                int track = takeVideo ? videoTrack : audioTrack;

                buffer.clear();
                int size = from.readSampleData(buffer, 0);
                if (size < 0) {
                    if (takeVideo) videoDone = true; else audioDone = true;
                    continue;
                }

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = (takeVideo ? videoTime : audioTime) - leadIn;
                info.flags = (from.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                    : 0;

                muxer.writeSampleData(track, buffer, info);

                boolean more = from.advance();
                long next = more ? from.getSampleTime() : Long.MAX_VALUE;
                if (takeVideo) {
                    videoTime = next;
                    if (!more) videoDone = true;
                } else {
                    audioTime = next;
                    if (!more) audioDone = true;
                }
            }
            return true;
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // Each one is released whatever the one before it did. A muxer that failed to stop used
            // to skip both extractors, and its failure took the place of the one that came first.
            Throwable closing = failure;
            if (muxer != null) {
                if (started) closing = attempt(closing, muxer::stop);
                closing = attempt(closing, muxer::release);
            }
            if (videoIn != null) closing = attempt(closing, videoIn::release);
            if (audioIn != null) closing = attempt(closing, audioIn::release);
            if (failure == null && closing != null) {
                throw new IOException("the joined file could not be finished: " + closing.getClass().getSimpleName(),
                    closing);
            }
        }
    }

    /**
     * Runs [step], and answers the failure to report: [failure] when there was one, with the step's
     * own added to it, else the step's.
     */
    private static Throwable attempt(Throwable failure, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            if (failure == null) return t;
            failure.addSuppressed(t);
        }
        return failure;
    }

    /** Select the first track of [kind] and return its format, or {@code null}. */
    private static MediaFormat selectTrack(MediaExtractor extractor, String kind) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(kind)) {
                extractor.selectTrack(i);
                return format;
            }
        }
        return null;
    }

    private static int maxInputSize(MediaFormat format) {
        try {
            return format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void removeStale(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;

        long now = System.currentTimeMillis();
        for (File file : files) {
            if (now - file.lastModified() > STALE_MS) Downloader.delete(file);
        }
    }
}
