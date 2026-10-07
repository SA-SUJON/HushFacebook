/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Sends a photo and a video through the hooks the way Facebook's chat transcoder does. */
public final class OriginalChatMediaForTests {
    private OriginalChatMediaForTests() {
    }

    /** An 8 by 8 JFIF JPEG with no EXIF. */
    private static final String JPEG_HEX =
            "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123" +
            "251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffdb0043011112" +
            "121815182f1a1a2f63423842636363636363636363636363636363636363636363636363636363636363636363636363" +
            "6363636363636363636363636363ffc00011080008000803012200021101031101ffc4001f0000010501010101010100" +
            "000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221" +
            "31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a" +
            "434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899" +
            "9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1" +
            "f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100" +
            "020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015" +
            "6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869" +
            "6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4" +
            "c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00c0" +
            "a28a2b80fad3ffd9";

    /** The standard JPEG as bytes. */
    public static byte[] jpeg() {
        byte[] bytes = new byte[JPEG_HEX.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(JPEG_HEX.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /** Writes [content] to a new file and returns its path. */
    public static String file(byte[] content) throws IOException {
        File file = File.createTempFile("chat-photo", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file.getPath();
    }

    /** Facebook's own completion callback, as far as the hook calls it. */
    public static final class Callback {
        public int successes;
        public int failures;
        public String path;
        public double width;
        public double height;

        public void success(String path, double a, double b, double c, double d, double e, double f, boolean rotated,
                int g, boolean h, double i, double j, double k) {
            successes++;
            this.path = path;
            width = a;
            height = b;
        }

        public void failure(double width, double height, Throwable error) {
            failures++;
        }
    }

    /** True when a standard JPEG comes back as its own bytes from transcodeImage's hook. */
    public static boolean sendsAPhotoAsIs() {
        try {
            return OriginalChatMedia.photo(file(jpeg()), 0, 0, null, new HashMap<String, Object>()) != null;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** True when transcodeImageAsync's hook takes a standard JPEG and answers the callback with it. */
    public static boolean sendsAPhotoAsyncAsIs() {
        OriginalPhoto.completion = Runnable::run;
        try {
            Callback callback = new Callback();
            Map<String, Object> extras = new HashMap<>();
            return OriginalChatMedia.photoAsync(file(jpeg()), 0, 0, null, extras, callback) && callback.successes == 1;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** True when a small video's size check says it can skip the re-encode, though Facebook's compare said it can't. */
    public static boolean passesAVideo() {
        return OriginalChatMedia.videoPassthrough(7, 4_000_000L) < 0;
    }
}
