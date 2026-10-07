/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether a video a chat is about to send says where it was filmed. Send chat photos and videos at
 * original quality only lets a video skip Facebook's re-encode when this finds no location in it,
 * since the file then goes out byte for byte and the re-encode is what drops a camera's tags.
 *
 * <p>An MP4 or QuickTime file keeps its tags in the movie box ({@code moov}): a phone writes the
 * place as a {@code ©xyz} or 3GPP {@code loci} box in a {@code udta}, or as a
 * {@code com.apple.quicktime.location.ISO6709} key in a {@code meta}, on the movie or on a track.
 * A dashcam can write it elsewhere: a Novatek camera indexes its GPS log from a {@code gps } box in
 * the movie and keeps the log in {@code free} boxes, and a BlackVue keeps NMEA sentences in a
 * {@code gps } box inside a top-level {@code free} box. This reads the file's top-level box
 * headers, the movie box and the contents of every top-level {@code free}, {@code skip} and
 * {@code wide} box, never the media, and looks through every {@code udta}, {@code meta} and padding
 * box of the movie, its tracks and their media.
 *
 * <p>It answers "clear" only when it read the whole layout, knew every box in it, and found
 * nothing. Each container this walks has a list of the boxes it may hold, taken from ISO/IEC
 * 14496-12, Apple's QuickTime file format and what Android's and iOS's cameras write; a box off
 * that list (a dashcam's {@code gps } index, an XMP {@code uuid}, a fragment's {@code mvex}) means
 * the file isn't clear. So does a file that isn't local, can't be read, is cut short, has its movie
 * box twice, holds more than {@link #MAX_MOVIE_BYTES} in its movie and padding boxes together, has
 * a top-level box this doesn't know, or a track with no media or no handler, or with a handler that
 * isn't video or sound (a timed metadata track, such as GoPro's {@code gpmd} or Google's
 * {@code camm}, can carry a GPS trace). The date and the camera's make and model aren't looked at: a
 * video that passes keeps them.
 */
final class VideoLocation {
    /** The most of a movie box and the padding boxes beside it this reads. A phone video's is tens of kilobytes. */
    static final int MAX_MOVIE_BYTES = 4 << 20;

    /** How many top-level boxes a file may have before this stops reading it. */
    static final int MAX_TOP_BOXES = 64;

    /** How deep the movie box's containers may nest: track, media, media information. */
    private static final int MAX_DEPTH = 4;

    /**
     * The boxes each container this walks may hold, and nothing else. Padding ({@code free},
     * {@code skip}, {@code wide}) is read like a {@code udta}.
     */
    private static final Map<String, Set<String>> CHILDREN = new HashMap<>();

    static {
        // The movie's header, tracks, tags, MPEG-4's object descriptor and padding. No mvex: a
        // fragmented movie keeps its samples, and so any timed metadata, in fragments not read here.
        CHILDREN.put("moov", set("mvhd", "trak", "udta", "meta", "iods", "free", "skip", "wide"));
        // Header, references, edit list, QuickTime's aperture modes (an iPhone writes tapt), media.
        CHILDREN.put("trak", set("tkhd", "tref", "edts", "tapt", "mdia", "udta", "meta", "free", "skip", "wide"));
        CHILDREN.put("mdia", set("mdhd", "hdlr", "minf", "elng", "udta", "meta", "free", "skip", "wide"));
        // A video or a sound header, QuickTime's data handler, data references and sample tables.
        CHILDREN.put("minf", set("vmhd", "smhd", "hdlr", "dinf", "stbl", "udta", "meta", "free", "skip", "wide"));
    }

    /** Box types and key text that name a place, matched as bytes in a udta, a meta or padding. */
    private static final byte[][] PLACE_BOXES = {
            {(byte) 0xA9, 'x', 'y', 'z'},
            {'l', 'o', 'c', 'i'},
            {'X', 'M', 'P', '_'},
    };

    /**
     * Words a location key, an XMP packet or a GPS log holds, matched without case: a {@code gps }
     * box or a Novatek {@code freeGPS} block, a timed metadata format's name, and the NMEA sentences
     * a dashcam logs.
     */
    private static final byte[][] PLACE_WORDS = {
            ascii("location"),
            ascii("iso6709"),
            ascii("gps"),
            ascii("gpmd"),
            ascii("camm"),
            ascii("nmea"),
            ascii("$gprmc"),
            ascii("$gpgga"),
            ascii("$gnrmc"),
            ascii("$gngga"),
    };

    private VideoLocation() {
    }

    /**
     * True only when [source], a {@code file://} address as Facebook's transcoder takes it, is a
     * file whose box tree this read in full without finding a place in it.
     */
    static boolean clear(@Nullable String source) {
        File file = localFile(source);
        if (file == null) return false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] movie = movieBox(in);
            return movie != null && clearBoxes(movie, 0, movie.length, "moov", 0);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** The file a {@code file://} address names, or null for any other address. */
    @Nullable
    private static File localFile(@Nullable String source) {
        if (source == null || source.isEmpty()) return null;
        Uri uri = Uri.parse(source);
        if (!"file".equals(uri.getScheme())) return null;
        String path = uri.getPath();
        return path == null || path.isEmpty() ? null : new File(path);
    }

    /**
     * The movie box's payload, read after a walk over every top-level box header, or null when the
     * layout isn't one this reads in full or a padding box names a place.
     */
    @Nullable
    static byte[] movieBox(RandomAccessFile in) throws IOException {
        long length = in.length();
        byte[] header = new byte[16];
        byte[] movie = null;
        // What the movie box and the padding boxes may take together.
        long room = MAX_MOVIE_BYTES;
        long at = 0;
        for (int boxes = 0; at < length; boxes++) {
            if (boxes >= MAX_TOP_BOXES || length - at < 8) return null;
            in.seek(at);
            in.readFully(header, 0, 8);
            long size = u32(header, 0);
            int headerSize = 8;
            if (size == 1) {
                if (length - at < 16) return null;
                in.readFully(header, 8, 8);
                size = u64(header, 8);
                headerSize = 16;
            } else if (size == 0) {
                // The last box runs to the end of the file.
                size = length - at;
            }
            if (size < headerSize || size > length - at) return null;
            long payload = size - headerSize;
            switch (type(header, 4)) {
                case "moov":
                    if (movie != null || payload > room) return null;
                    movie = new byte[(int) payload];
                    in.readFully(movie);
                    room -= payload;
                    break;
                case "free":
                case "skip":
                case "wide":
                    // Padding, unless a camera wrote into it: a dashcam can keep its GPS log in one.
                    if (payload > room) return null;
                    byte[] padding = new byte[(int) payload];
                    in.readFully(padding);
                    room -= payload;
                    if (namesAPlace(padding, 0, padding.length)) return null;
                    break;
                case "ftyp":
                case "mdat":
                case "pdin":
                    break;
                default:
                    // Fragments (moof), an XMP uuid, a top-level meta or anything else.
                    return null;
            }
            at += size;
        }
        return movie;
    }

    /**
     * Whether the boxes in [from, to) of [data], the children of a [parent] box, hold no place:
     * each is one the parent may hold, every udta, meta and padding box is searched, tracks, their
     * media and its information are walked, a track has media, and the media's one handler is
     * video or sound.
     */
    static boolean clearBoxes(byte[] data, int from, int to, String parent, int depth) {
        if (depth > MAX_DEPTH) return false;
        Set<String> allowed = CHILDREN.get(parent);
        if (allowed == null) return false;
        boolean media = false;
        boolean handler = false;
        int at = from;
        while (at < to) {
            if (to - at < 8) return false;
            long size = u32(data, at);
            int headerSize = 8;
            if (size == 1) {
                if (to - at < 16) return false;
                size = u64(data, at + 8);
                headerSize = 16;
            } else if (size == 0) {
                size = to - at;
            }
            if (size < headerSize || size > to - at) return false;
            int start = at + headerSize;
            int end = (int) (at + size);
            String type = type(data, at + 4);
            if (!allowed.contains(type)) return false;
            switch (type) {
                case "udta":
                case "meta":
                case "free":
                case "skip":
                case "wide":
                    if (namesAPlace(data, start, end)) return false;
                    break;
                case "trak":
                case "minf":
                    if (!clearBoxes(data, start, end, type, depth + 1)) return false;
                    break;
                case "mdia":
                    if (media || !clearBoxes(data, start, end, type, depth + 1)) return false;
                    media = true;
                    break;
                case "hdlr":
                    // The media's handler, after its version, flags and QuickTime's component type.
                    // One inside minf is QuickTime's data handler, which says where the samples are.
                    if ("mdia".equals(parent)) {
                        if (handler || !videoOrSound(data, start, end)) return false;
                        handler = true;
                    }
                    break;
                default:
                    // A header, an edit list or the sample tables: no tags in them.
                    break;
            }
            at = end;
        }
        // A track without media, or media without a handler, can't be shown to be video or sound.
        if ("trak".equals(parent) && !media) return false;
        return !"mdia".equals(parent) || handler;
    }

    private static boolean videoOrSound(byte[] data, int start, int end) {
        if (end - start < 12) return false;
        String handler = type(data, start + 8);
        return "vide".equals(handler) || "soun".equals(handler);
    }

    /** Whether [from, to) of [data] holds a place's box type or a word a location key or a GPS log uses. */
    static boolean namesAPlace(byte[] data, int from, int to) {
        for (int i = from; i < to; i++) {
            for (byte[] box : PLACE_BOXES) {
                if (matches(data, i, to, box, false)) return true;
            }
            for (byte[] word : PLACE_WORDS) {
                if (matches(data, i, to, word, true)) return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] data, int at, int to, byte[] part, boolean anyCase) {
        if (to - at < part.length) return false;
        for (int j = 0; j < part.length; j++) {
            int b = data[at + j];
            if (anyCase && b >= 'A' && b <= 'Z') b += 'a' - 'A';
            if (b != part[j]) return false;
        }
        return true;
    }

    private static long u32(byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16) | ((data[at + 2] & 0xFFL) << 8)
                | (data[at + 3] & 0xFFL);
    }

    /** A 64-bit size, or -1 past what a long holds, which every size check then refuses. */
    private static long u64(byte[] data, int at) {
        long value = (u32(data, at) << 32) | u32(data, at + 4);
        return value < 0 ? -1 : value;
    }

    private static String type(byte[] data, int at) {
        return new String(data, at, 4, StandardCharsets.ISO_8859_1);
    }

    private static Set<String> set(String... types) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(types)));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
