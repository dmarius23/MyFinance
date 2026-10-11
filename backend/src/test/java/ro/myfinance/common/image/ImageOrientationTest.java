package ro.myfinance.common.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageOrientationTest {

    @Test
    void applyOrientationSwapsDimensionsForNinetyDegreeRotations() {
        BufferedImage src = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);

        assertThat(ImageOrientation.applyOrientation(src, 6)) // 90° CW
                .extracting(BufferedImage::getWidth, BufferedImage::getHeight).containsExactly(10, 20);
        assertThat(ImageOrientation.applyOrientation(src, 8)) // 90° CCW
                .extracting(BufferedImage::getWidth, BufferedImage::getHeight).containsExactly(10, 20);
        assertThat(ImageOrientation.applyOrientation(src, 3)) // 180° — dims preserved
                .extracting(BufferedImage::getWidth, BufferedImage::getHeight).containsExactly(20, 10);
        assertThat(ImageOrientation.applyOrientation(src, 1)).isSameAs(src); // upright → untouched
    }

    @Test
    void readsExifOrientationTagAndIgnoresPlainJpeg() throws Exception {
        assertThat(ImageOrientation.readExifOrientation(jpegWithExifOrientation(6))).isEqualTo(6);
        assertThat(ImageOrientation.readExifOrientation(plainJpeg(20, 10))).isEqualTo(1); // no EXIF → 1
    }

    @Test
    void normalizeRotatesOnlyWhenExifSaysSo() throws Exception {
        // Upright JPEG (no EXIF) is returned byte-for-byte unchanged — no needless re-encode.
        byte[] upright = plainJpeg(20, 10);
        assertThat(ImageOrientation.normalize(upright)).isSameAs(upright);

        // A JPEG tagged orientation=6 (90° CW) comes back transposed (20x10 → 10x20).
        byte[] rotated = ImageOrientation.normalize(jpegWithExifOrientation(6));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(rotated));
        assertThat(out.getWidth()).isEqualTo(10);
        assertThat(out.getHeight()).isEqualTo(20);
    }

    @Test
    void nonImageBytesFailOpen() {
        byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8};
        assertThat(ImageOrientation.normalize(garbage)).isSameAs(garbage);
    }

    private static byte[] plainJpeg(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }

    /** A real 20x10 JPEG with an EXIF APP1 segment (big-endian TIFF) carrying Orientation=value spliced in. */
    private static byte[] jpegWithExifOrientation(int value) throws Exception {
        byte[] jpeg = plainJpeg(20, 10); // starts with FFD8 (SOI)
        byte[] tiff = {
            'M', 'M', 0x00, 0x2A,                   // big-endian, magic 42
            0x00, 0x00, 0x00, 0x08,                 // IFD0 offset
            0x00, 0x01,                             // 1 entry
            0x01, 0x12, 0x00, 0x03,                 // tag=Orientation, type=SHORT
            0x00, 0x00, 0x00, 0x01,                 // count=1
            0x00, (byte) value, 0x00, 0x00,         // value (inline)
            0x00, 0x00, 0x00, 0x00                  // next IFD = 0
        };
        byte[] payload = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, payload, 0, 6);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        int segLen = payload.length + 2; // APP1 length includes the 2 length bytes
        byte[] app1 = new byte[4 + payload.length];
        app1[0] = (byte) 0xFF;
        app1[1] = (byte) 0xE1;
        app1[2] = (byte) ((segLen >> 8) & 0xFF);
        app1[3] = (byte) (segLen & 0xFF);
        System.arraycopy(payload, 0, app1, 4, payload.length);

        // Splice APP1 right after the SOI (FFD8), before the rest of the JPEG.
        byte[] out = new byte[jpeg.length + app1.length];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }
}
