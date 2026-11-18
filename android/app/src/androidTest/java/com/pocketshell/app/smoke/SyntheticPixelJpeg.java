package com.pocketshell.app.smoke;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * A generated JPEG shaped like a Pixel camera photo (#3191), never a personal
 * image. The maintainer's failing photos were Pixel 7a JPEGs of ~630 KB and
 * ~670 KB (and a 1.67 MB one) with an EXIF block of 13 TIFF entries including
 * an orientation tag, Motion Photo XMP and an MPF APP2 segment, and nothing
 * after EOI. This builds the same layout around a rendered image, padded with
 * COM segments to an exact byte size, so an upload path that strips, rewrites
 * or truncates any of those markers fails the byte-exact host check.
 */
final class SyntheticPixelJpeg {
    static final int ORIENTATION_ROTATE_90 = 6;

    private SyntheticPixelJpeg() {}

    static byte[] create(int targetBytes, int widthPx, int heightPx, long seed) {
        byte[] encoded = render(widthPx, heightPx, seed);
        if (encoded[0] != (byte) 0xFF || encoded[1] != (byte) 0xD8) throw new IllegalStateException("encoder did not write SOI");
        // Drop the encoder's SOI and JFIF APP0: a camera JPEG starts with EXIF.
        int bodyStart = 2;
        if ((encoded[2] & 0xFF) == 0xFF && (encoded[3] & 0xFF) == 0xE0) {
            bodyStart = 4 + (((encoded[4] & 0xFF) << 8) | (encoded[5] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(targetBytes);
        out.write(0xFF);
        out.write(0xD8);
        segment(out, 0xE1, exif(widthPx, heightPx));
        segment(out, 0xE1, motionPhotoXmp(seed));
        segment(out, 0xE2, mpf(targetBytes));
        int remaining = targetBytes - out.size() - (encoded.length - bodyStart);
        if (remaining < 4) throw new IllegalStateException("rendered image is too large for " + targetBytes + " bytes");
        Random random = new Random(seed * 31 + 7);
        while (remaining > 0) {
            int total = Math.min(remaining, 65_537);
            // A COM segment is at least 4 bytes; never leave a 1-3 byte tail.
            if (remaining - total > 0 && remaining - total < 4) total -= 4;
            byte[] padding = new byte[total - 4];
            random.nextBytes(padding);
            segment(out, 0xFE, padding);
            remaining -= total;
        }
        out.write(encoded, bodyStart, encoded.length - bodyStart);
        byte[] jpeg = out.toByteArray();
        if (jpeg.length != targetBytes) throw new IllegalStateException("built " + jpeg.length + " bytes, wanted " + targetBytes);
        if ((jpeg[jpeg.length - 2] & 0xFF) != 0xFF || (jpeg[jpeg.length - 1] & 0xFF) != 0xD9) {
            throw new IllegalStateException("the JPEG must end at EOI");
        }
        return jpeg;
    }

    /** True when the bytes carry the camera markers this fixture promises. */
    static boolean hasCameraMarkers(byte[] jpeg) {
        return indexOf(jpeg, "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)) > 0
                && indexOf(jpeg, "GCamera:MotionPhoto=\"1\"".getBytes(StandardCharsets.ISO_8859_1)) > 0
                && indexOf(jpeg, "MPF\0".getBytes(StandardCharsets.ISO_8859_1)) > 0;
    }

    private static byte[] render(int width, int height, long seed) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Random random = new Random(seed);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.rgb(40 + random.nextInt(60), 90 + random.nextInt(60), 140 + random.nextInt(60)));
        for (int index = 0; index < 120; index++) {
            paint.setColor(Color.argb(160, random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            canvas.drawCircle(random.nextInt(width), random.nextInt(height), 20 + random.nextInt(width / 4), paint);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, jpeg)) throw new IllegalStateException("JPEG encode failed");
        bitmap.recycle();
        return jpeg.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int length = payload.length + 2;
        if (length > 0xFFFF) throw new IllegalStateException("segment too long");
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
    }

    /** "Exif\0\0" + a little-endian TIFF with IFD0 of 13 entries, an Exif IFD and a GPS IFD. */
    private static byte[] exif(int width, int height) {
        ByteBuffer tiff = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        final int entries = 13;
        int ifd0 = 8;
        int exifIfd = ifd0 + 2 + entries * 12 + 4;
        int gpsIfd = exifIfd + 2 + 12 + 4;
        int data = gpsIfd + 2 + 12 + 4;
        byte[] make = ascii("Google");
        byte[] model = ascii("Pixel 7a");
        byte[] software = ascii("HDR+ 1.0.640000000zd");
        byte[] dateTime = ascii("2026:11:18 10:15:00");
        int makeAt = data;
        int modelAt = makeAt + make.length;
        int softwareAt = modelAt + model.length;
        int dateAt = softwareAt + software.length;
        int xResAt = dateAt + dateTime.length;
        int yResAt = xResAt + 8;
        tiff.position(ifd0);
        tiff.putShort((short) entries);
        entry(tiff, 0x0100, 4, 1, width);
        entry(tiff, 0x0101, 4, 1, height);
        entry(tiff, 0x010F, 2, make.length, makeAt);
        entry(tiff, 0x0110, 2, model.length, modelAt);
        entry(tiff, 0x0112, 3, 1, ORIENTATION_ROTATE_90);
        entry(tiff, 0x011A, 5, 1, xResAt);
        entry(tiff, 0x011B, 5, 1, yResAt);
        entry(tiff, 0x0128, 3, 1, 2);
        entry(tiff, 0x0131, 2, software.length, softwareAt);
        entry(tiff, 0x0132, 2, dateTime.length, dateAt);
        entry(tiff, 0x0213, 3, 1, 1);
        entry(tiff, 0x8769, 4, 1, exifIfd);
        entry(tiff, 0x8825, 4, 1, gpsIfd);
        tiff.putInt(0);
        tiff.position(exifIfd);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x9000).putShort((short) 7).putInt(4).put(ascii4("0232"));
        tiff.putInt(0);
        tiff.position(gpsIfd);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0000).putShort((short) 1).putInt(4).put(new byte[] {2, 2, 0, 0});
        tiff.putInt(0);
        tiff.position(data);
        tiff.put(make).put(model).put(software).put(dateTime);
        tiff.putInt(72).putInt(1).putInt(72).putInt(1);
        int size = tiff.position();
        byte[] payload = new byte[6 + size];
        System.arraycopy("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, payload, 0, 6);
        System.arraycopy(tiff.array(), 0, payload, 6, size);
        return payload;
    }

    private static void entry(ByteBuffer tiff, int tag, int type, int count, int value) {
        tiff.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) tiff.putShort((short) value).putShort((short) 0);
        else tiff.putInt(value);
    }

    private static byte[] motionPhotoXmp(long seed) {
        String packet = "http://ns.adobe.com/xap/1.0/\0"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
                + " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
                + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\""
                + " GCamera:MotionPhotoPresentationTimestampUs=\"" + (500_000 + seed) + "\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        return packet.getBytes(StandardCharsets.UTF_8);
    }

    /** "MPF\0" + a little-endian MP Index IFD naming one primary image of the whole file's size. */
    private static byte[] mpf(int fileBytes) {
        ByteBuffer tiff = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        int entryAt = 8 + 2 + 3 * 12 + 4;
        tiff.putShort((short) 3);
        tiff.putShort((short) 0xB000).putShort((short) 7).putInt(4).put(ascii4("0100"));
        tiff.putShort((short) 0xB001).putShort((short) 4).putInt(1).putInt(1);
        tiff.putShort((short) 0xB002).putShort((short) 7).putInt(16).putInt(entryAt);
        tiff.putInt(0);
        tiff.putInt(0x030000).putInt(fileBytes).putInt(0).putShort((short) 0).putShort((short) 0);
        int size = tiff.position();
        byte[] payload = new byte[4 + size];
        System.arraycopy("MPF\0".getBytes(StandardCharsets.ISO_8859_1), 0, payload, 0, 4);
        System.arraycopy(tiff.array(), 0, payload, 4, size);
        return payload;
    }

    private static byte[] ascii(String value) {
        byte[] text = value.getBytes(StandardCharsets.US_ASCII);
        byte[] terminated = new byte[text.length + 1];
        System.arraycopy(text, 0, terminated, 0, text.length);
        return terminated;
    }

    private static byte[] ascii4(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) continue outer;
            }
            return index;
        }
        return -1;
    }
}
