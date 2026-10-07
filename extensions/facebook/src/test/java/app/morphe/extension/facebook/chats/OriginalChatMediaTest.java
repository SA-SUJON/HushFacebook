/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.box;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.bytes;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.fileType;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.join;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.media;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.movie;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.plainVideo;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.track;
import static app.morphe.extension.facebook.chats.OriginalChatMediaForTests.video;

/**
 * Photos and videos from a chat inside Facebook, through the hooks: on sends the original and
 * counts it, off and paused leave Facebook's own transcode and size check alone. A video goes out
 * as it is only when its box tree was read in full and holds no location; built here as minimal
 * MP4 files with and without one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OriginalChatMediaTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void immediateCompletion() {
        OriginalPhoto.completion = Runnable::run;
        // Facebook's chats ask the size check on their media thread; Robolectric runs tests on the main one.
        OriginalChatMedia.onMainThread = () -> false;
    }

    @After
    public void restore() {
        OriginalChatMedia.onMainThread = OriginalChatMedia.MAIN_THREAD;
        OriginalPhoto.completion = Executors.newSingleThreadExecutor();
        PauseForTests.resume();
        Settings.ORIGINAL_CHAT_MEDIA.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.ORIGINAL_CHAT_MEDIA + ":")) return line;
        }
        return null;
    }

    private static Map<String, Object> extras() {
        return new HashMap<>();
    }

    @Test
    public void theSwitchStartsOffAndFacebookKeepsItsTranscode() throws IOException {
        assertFalse(Settings.ORIGINAL_CHAT_MEDIA.get());
        String path = OriginalChatMediaForTests.file(OriginalChatMediaForTests.jpeg());
        assertNull(OriginalChatMedia.photo(path, 0, 0, null, extras()));
        OriginalChatMediaForTests.Callback callback = new OriginalChatMediaForTests.Callback();
        assertFalse(OriginalChatMedia.photoAsync(path, 0, 0, null, extras(), callback));
        assertEquals(0, callback.successes);
        assertEquals(5, OriginalChatMedia.videoPassthrough(5, 1000, video(plainVideo())));
    }

    @Test
    public void aJpegGoesOutAsItsOwnImageDataAndIsCounted() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        byte[] original = OriginalChatMediaForTests.jpeg();
        byte[] sent = OriginalChatMedia.photo(OriginalChatMediaForTests.file(original), 0, 0, null, extras());
        assertNotNull(sent);
        assertEquals("starts as a JPEG", 0xFF, sent[0] & 0xFF);
        assertEquals(0xD8, sent[1] & 0xFF);
        assertEquals("ends as a JPEG", 0xD9, sent[sent.length - 1] & 0xFF);
        assertTrue("never bigger than the original", sent.length <= original.length);
        String line = statusLine();
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(OriginalChatMedia.PHOTO_SENT + " 1"));
    }

    @Test
    public void aStandardSendAndAnyTargetSizeGetTheOriginalToo() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        String path = OriginalChatMediaForTests.file(OriginalChatMediaForTests.jpeg());
        Map<String, Object> standard = extras();
        standard.put("IS_HD", Boolean.FALSE);
        assertNotNull(OriginalChatMedia.photo(path, 1280, 960, null, standard));
        assertNotNull("a tall target", OriginalChatMedia.photo(path, 4, 4000, null, extras()));
    }

    @Test
    public void aPreviewAndAThumbnailKeepFacebooksTranscode() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        String path = OriginalChatMediaForTests.file(OriginalChatMediaForTests.jpeg());
        Map<String, Object> preview = extras();
        preview.put("IS_PREVIEW", Boolean.TRUE);
        assertNull("a preview", OriginalChatMedia.photo(path, 0, 0, null, preview));
        assertNull("a thumbnail size", OriginalChatMedia.photo(path, 320, 320, null, extras()));
        assertNull("a missing file", OriginalChatMedia.photo(path + ".gone", 0, 0, null, extras()));
        assertNull("no path", OriginalChatMedia.photo(null, 0, 0, null, extras()));
    }

    @Test
    public void somethingThatIsNotAJpegKeepsFacebooksTranscode() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        assertNull(OriginalChatMedia.photo(OriginalChatMediaForTests.file(png), 0, 0, null, extras()));
        assertFalse(OriginalChatMedia.photoAsync(OriginalChatMediaForTests.file(png), 0, 0, null, extras(),
                new OriginalChatMediaForTests.Callback()));
        String line = statusLine();
        assertTrue("nothing counted", line == null || !line.contains(OriginalChatMedia.PHOTO_SENT));
    }

    @Test
    public void theAsyncSendAnswersTheCallbackWithTheCopy() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        OriginalChatMediaForTests.Callback callback = new OriginalChatMediaForTests.Callback();
        String path = OriginalChatMediaForTests.file(OriginalChatMediaForTests.jpeg());
        assertTrue(OriginalChatMedia.photoAsync(path, 0, 0, null, extras(), callback));
        assertEquals(1, callback.successes);
        assertEquals(0, callback.failures);
        assertTrue(callback.path, callback.path.startsWith("file:"));
        assertEquals(8.0, callback.width, 0);
        assertEquals(8.0, callback.height, 0);
        assertFalse("no callback, no hand-over",
                OriginalChatMedia.photoAsync(path, 0, 0, null, extras(), null));
    }

    @Test
    public void aSmallVideoSkipsTheReEncodeAndALargeOneDoesNot() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        String plain = video(plainVideo());
        assertEquals("a small video", -1, OriginalChatMedia.videoPassthrough(9, 4_000_000L, plain));
        assertEquals("a video Facebook already passes", -3, OriginalChatMedia.videoPassthrough(-3, 4_000_000L, plain));
        assertEquals("no size", 9, OriginalChatMedia.videoPassthrough(9, 0, plain));
        assertEquals("at the ceiling", -1, OriginalChatMedia.videoPassthrough(9, OriginalChatMedia.VIDEO_MAX_BYTES, plain));
        assertEquals("over the ceiling", 9,
                OriginalChatMedia.videoPassthrough(9, OriginalChatMedia.VIDEO_MAX_BYTES + 1, plain));
        String line = statusLine();
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(OriginalChatMedia.VIDEO_PASSED + " 2"));
    }

    /** The places a phone or a camera app writes where a video was filmed, each in an otherwise plain file. */
    private static Map<String, byte[]> locatedVideos() {
        byte[] place = bytes("+37.4220-122.0841/");
        Map<String, byte[]> videos = new LinkedHashMap<>();
        videos.put("Android's \u00A9xyz in the movie's udta",
                movie(track("vide"), track("soun"), box("udta", box("\u00A9xyz", new byte[4], place))));
        videos.put("\u00A9xyz in a track's udta",
                movie(track("vide", box("udta", box("\u00A9xyz", new byte[4], place))), track("soun")));
        videos.put("3GPP loci", movie(track("vide"), box("udta", box("loci", new byte[24]))));
        videos.put("QuickTime's location key in the movie's meta", movie(track("vide"), box("meta",
                box("hdlr", new byte[8], bytes("mdta"), new byte[13]),
                box("keys", new byte[8], box("mdta", bytes("com.apple.quicktime.location.ISO6709"))),
                box("ilst", box("\u0000\u0000\u0000\u0001", box("data", new byte[8], place))))));
        videos.put("a location key in a track's meta", movie(track("vide", box("meta",
                box("keys", new byte[8], box("mdta", bytes("com.android.location")))))));
        videos.put("\u00A9xyz in an iTunes-style list", movie(track("vide"),
                box("udta", box("meta", new byte[4], box("ilst", box("\u00A9xyz", box("data", new byte[8], place)))))));
        videos.put("an XMP packet with GPS tags", movie(track("vide"),
                box("udta", box("XMP_", bytes("<x:xmpmeta><exif:GPSLatitude>37,25.32N</exif:GPSLatitude></x:xmpmeta>")))));
        return videos;
    }

    @Test
    public void aVideoThatSaysWhereItWasFilmedKeepsTheReEncode() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        for (Map.Entry<String, byte[]> located : locatedVideos().entrySet()) {
            String address = video(join(fileType(), located.getValue(), media()));
            assertEquals(located.getKey(), 9, OriginalChatMedia.videoPassthrough(9, 4_000_000L, address));
        }
        String line = statusLine();
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(OriginalChatMedia.VIDEO_KEPT + " " + locatedVideos().size()));
        assertFalse(line, line.contains(OriginalChatMedia.VIDEO_PASSED));
    }

    @Test
    public void aVideoWithOtherTagsOrItsMovieLastGoesOutAsItIs() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        byte[] tagged = movie(track("vide"), track("soun"),
                box("udta", box("\u00A9mak", new byte[4], bytes("Google")), box("\u00A9mod", new byte[4], bytes("Pixel 9"))),
                box("meta", box("keys", new byte[8], box("mdta", bytes("com.android.version")),
                        box("mdta", bytes("com.android.capture.fps")))));
        assertEquals("a make, a model and Android's version keys", -1,
                OriginalChatMedia.videoPassthrough(9, 4_000_000L, video(join(fileType(), tagged, media()))));
        assertEquals("the movie box after the media, as a camera writes it", -1, OriginalChatMedia.videoPassthrough(9,
                4_000_000L, video(join(fileType(), media(), movie(track("vide"), track("soun"))))));
        byte[] toTheEnd = box("mdat", new byte[32]);
        toTheEnd[3] = 0;
        assertEquals("media that runs to the end of the file", -1, OriginalChatMedia.videoPassthrough(9, 4_000_000L,
                video(join(fileType(), movie(track("vide")), toTheEnd))));
    }

    @Test
    public void aVideoThatCantBeReadInFullKeepsTheReEncode() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        byte[] plain = plainVideo();
        Map<String, String> unread = new LinkedHashMap<>();
        unread.put("cut short in its media", video(Arrays.copyOf(plain, plain.length - 10)));
        byte[] moviePart = join(fileType(), movie(track("vide"), track("soun")));
        unread.put("cut short in its movie box", video(Arrays.copyOf(moviePart, moviePart.length - 20)));
        unread.put("no movie box", video(join(fileType(), media())));
        unread.put("two movie boxes", video(join(fileType(), movie(track("vide")), movie(track("vide")), media())));
        unread.put("fragmented", video(join(fileType(), movie(track("vide"), box("mvex", box("trex", new byte[24]))),
                box("moof", new byte[16]), media())));
        unread.put("a top-level uuid, as XMP can be", video(join(fileType(), box("uuid", new byte[32]),
                movie(track("vide")), media())));
        unread.put("a timed metadata track", video(join(fileType(), movie(track("vide"), track("meta")), media())));
        unread.put("a uuid in the movie", video(join(fileType(), movie(track("vide"), box("uuid", new byte[16])), media())));
        unread.put("a movie box over the limit", video(join(fileType(),
                movie(track("vide"), box("free", new byte[VideoLocation.MAX_MOVIE_BYTES])), media())));
        unread.put("a box smaller than its own header", video(join(fileType(), new byte[] {0, 0, 0, 4, 'f', 'r', 'e', 'e'},
                movie(track("vide")), media())));
        unread.put("a content address", "content://media/external/video/media/1");
        unread.put("a bare path", OriginalChatMediaForTests.file(plain));
        unread.put("a file that's gone", video(plain) + ".gone");
        unread.put("no address", null);
        for (Map.Entry<String, String> file : unread.entrySet()) {
            assertEquals(file.getKey(), 9, OriginalChatMedia.videoPassthrough(9, 4_000_000L, file.getValue()));
        }
        assertEquals("the same file whole", -1, OriginalChatMedia.videoPassthrough(9, 4_000_000L, video(plain)));
    }

    @Test
    public void onTheMainThreadAVideoKeepsTheReEncode() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        String plain = video(plainVideo());
        OriginalChatMedia.onMainThread = () -> true;
        assertEquals(9, OriginalChatMedia.videoPassthrough(9, 4_000_000L, plain));
        OriginalChatMedia.onMainThread = () -> false;
        assertEquals(-1, OriginalChatMedia.videoPassthrough(9, 4_000_000L, plain));
    }

    @Test
    public void pausedFacebookKeepsItsOwnTranscodeAndSizeCheck() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        String path = OriginalChatMediaForTests.file(OriginalChatMediaForTests.jpeg());
        assertNull(OriginalChatMedia.photo(path, 0, 0, null, extras()));
        assertFalse(OriginalChatMedia.photoAsync(path, 0, 0, null, extras(), new OriginalChatMediaForTests.Callback()));
        String plain = video(plainVideo());
        assertEquals(9, OriginalChatMedia.videoPassthrough(9, 4_000_000L, plain));
        PauseForTests.resume();
        assertEquals(-1, OriginalChatMedia.videoPassthrough(9, 4_000_000L, plain));
    }

    @Test
    public void theCopyKeepsTheRotationTagAndNothingElse() throws IOException {
        byte[] withExif = OriginalChatMediaForTests.jpeg();
        // An EXIF segment with GPS-sized padding goes in after SOI, as a camera writes it.
        byte[] exif = new byte[] {(byte) 0xFF, (byte) 0xE1, 0, 14, 'E', 'x', 'i', 'f', 0, 0, 'C', 'A', 'M', 'E', 'R', 'A'};
        byte[] merged = new byte[withExif.length + exif.length];
        System.arraycopy(withExif, 0, merged, 0, 2);
        System.arraycopy(exif, 0, merged, 2, exif.length);
        System.arraycopy(withExif, 2, merged, 2 + exif.length, withExif.length - 2);
        java.io.File source = new java.io.File(OriginalChatMediaForTests.file(merged));
        java.io.File target = java.io.File.createTempFile("copy", ".jpg");
        target.deleteOnExit();
        OriginalPhoto.copyImageData(source, target, 6);
        byte[] copy = java.nio.file.Files.readAllBytes(target.toPath());
        assertFalse("the camera's EXIF is gone", indexOf(copy, "CAMERA".getBytes()) >= 0);
        assertTrue("the rotation tag is in", indexOf(copy, OriginalPhoto.orientationExif(6)) >= 0);
        OriginalPhoto.copyImageData(source, target, 0);
        byte[] plain = java.nio.file.Files.readAllBytes(target.toPath());
        assertEquals("no tag, no EXIF segment", -1, indexOf(plain, new byte[] {'E', 'x', 'i', 'f'}));
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= bytes.length; i++) {
            for (int j = 0; j < part.length; j++) if (bytes[i + j] != part[j]) continue outer;
            return i;
        }
        return -1;
    }
}
