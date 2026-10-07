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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Photos and videos from a chat inside Facebook, through the hooks: on sends the original and
 * counts it, off and paused leave Facebook's own transcode and size check alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OriginalChatMediaTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void immediateCompletion() {
        OriginalPhoto.completion = Runnable::run;
    }

    @After
    public void restore() {
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
        assertEquals(5, OriginalChatMedia.videoPassthrough(5, 1000));
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
    public void aSmallVideoSkipsTheReEncodeAndALargeOneDoesNot() {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        assertEquals("a small video", -1, OriginalChatMedia.videoPassthrough(9, 4_000_000L));
        assertEquals("a video Facebook already passes", -3, OriginalChatMedia.videoPassthrough(-3, 4_000_000L));
        assertEquals("no size", 9, OriginalChatMedia.videoPassthrough(9, 0));
        assertEquals("at the ceiling", -1, OriginalChatMedia.videoPassthrough(9, OriginalChatMedia.VIDEO_MAX_BYTES));
        assertEquals("over the ceiling", 9,
                OriginalChatMedia.videoPassthrough(9, OriginalChatMedia.VIDEO_MAX_BYTES + 1));
        String line = statusLine();
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(OriginalChatMedia.VIDEO_PASSED + " 2"));
    }

    @Test
    public void pausedFacebookKeepsItsOwnTranscodeAndSizeCheck() throws IOException {
        Settings.ORIGINAL_CHAT_MEDIA.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        String path = OriginalChatMediaForTests.file(OriginalChatMediaForTests.jpeg());
        assertNull(OriginalChatMedia.photo(path, 0, 0, null, extras()));
        assertFalse(OriginalChatMedia.photoAsync(path, 0, 0, null, extras(), new OriginalChatMediaForTests.Callback()));
        assertEquals(9, OriginalChatMedia.videoPassthrough(9, 4_000_000L));
        PauseForTests.resume();
        assertEquals(-1, OriginalChatMedia.videoPassthrough(9, 4_000_000L));
    }

    @Test
    public void theCopyKeepsTheRotationTagAndNothingElse() throws IOException {
        byte[] withExif = OriginalChatMediaForTests.jpeg();
        // An EXIF segment with GPS-sized padding goes in after SOI, as a camera writes it.
        byte[] exif = new byte[] {(byte) 0xFF, (byte) 0xE1, 0, 10, 'E', 'x', 'i', 'f', 0, 0, 1, 2};
        byte[] merged = new byte[withExif.length + exif.length];
        System.arraycopy(withExif, 0, merged, 0, 2);
        System.arraycopy(exif, 0, merged, 2, exif.length);
        System.arraycopy(withExif, 2, merged, 2 + exif.length, withExif.length - 2);
        java.io.File source = new java.io.File(OriginalChatMediaForTests.file(merged));
        java.io.File target = java.io.File.createTempFile("copy", ".jpg");
        target.deleteOnExit();
        OriginalPhoto.copyImageData(source, target, 6);
        byte[] copy = java.nio.file.Files.readAllBytes(target.toPath());
        assertFalse("the camera's EXIF is gone", indexOf(copy, new byte[] {1, 2}) >= 0);
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
