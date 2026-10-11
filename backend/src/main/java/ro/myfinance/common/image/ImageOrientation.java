package ro.myfinance.common.image;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/**
 * Normalises a photo to upright using its EXIF orientation — but ONLY when the image actually carries a
 * non-default orientation. Phone cameras usually store the sensor pixels sideways plus an EXIF orientation
 * tag telling viewers how to rotate; vision OCR reads such images literally (sideways), which hurts
 * accuracy. This rotates the pixels so the OCR sees the receipt upright.
 *
 * <p>Deliberately conservative: an image with no EXIF orientation, an already-upright one (orientation 1),
 * a non-JPEG (e.g. a PDF-rendered PNG, which has no EXIF), or anything unreadable is returned
 * <em>byte-for-byte unchanged</em> — no needless re-encode. Any failure fails open to the original bytes,
 * so it can never break an upload or extraction.
 */
public final class ImageOrientation {

    private ImageOrientation() {
    }

    /**
     * @return upright JPEG bytes when {@code image} has a rotating EXIF orientation (3/6/8) or a simple
     *         mirror (2/4); otherwise the original bytes unchanged.
     */
    public static byte[] normalize(byte[] image) {
        if (image == null || image.length < 4) {
            return image;
        }
        try {
            int orientation = readExifOrientation(image);
            // 1 = upright/absent; 5/7 are rare mirrored-transposes — leave those untouched rather than risk
            // an incorrect transform (doing nothing is never worse than today's behaviour).
            if (orientation <= 1 || orientation == 5 || orientation == 7) {
                return image;
            }
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(image));
            if (src == null) {
                return image;
            }
            BufferedImage dst = applyOrientation(src, orientation);
            if (dst == src) {
                return image;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(dst, "jpeg", out) || out.size() == 0) {
                return image;
            }
            return out.toByteArray();
        } catch (RuntimeException | java.io.IOException e) {
            return image; // fail open — never break the upload/extraction over an image quirk
        }
    }

    /** EXIF orientation (1..8) from a JPEG's APP1/Exif segment; 1 when absent, unreadable, or not a JPEG. */
    static int readExifOrientation(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return 1; // not a JPEG (no SOI) → no EXIF orientation
        }
        int p = 2;
        while (p + 4 <= b.length) {
            if ((b[p] & 0xFF) != 0xFF) {
                return 1; // not aligned on a marker → give up
            }
            int marker = b[p + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // start-of-scan / end-of-image → no metadata beyond here
            }
            int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            if (len < 2) {
                return 1;
            }
            int seg = p + 4;
            if (marker == 0xE1 && seg + 6 <= b.length
                    && b[seg] == 'E' && b[seg + 1] == 'x' && b[seg + 2] == 'i' && b[seg + 3] == 'f'
                    && b[seg + 4] == 0 && b[seg + 5] == 0) {
                return readOrientationFromTiff(b, seg + 6);
            }
            p += 2 + len;
        }
        return 1;
    }

    private static int readOrientationFromTiff(byte[] b, int tiff) {
        if (tiff + 8 > b.length) {
            return 1;
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        int ifd = tiff + readU32(b, tiff + 4, little);
        if (ifd < 0 || ifd + 2 > b.length) {
            return 1;
        }
        int count = readU16(b, ifd, little);
        int e = ifd + 2;
        for (int i = 0; i < count && e + 12 <= b.length; i++, e += 12) {
            if (readU16(b, e, little) == 0x0112) { // Orientation tag; value is inline (SHORT, count 1)
                int v = readU16(b, e + 8, little);
                return (v >= 1 && v <= 8) ? v : 1;
            }
        }
        return 1;
    }

    private static int readU16(byte[] b, int o, boolean little) {
        int b0 = b[o] & 0xFF;
        int b1 = b[o + 1] & 0xFF;
        return little ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static int readU32(byte[] b, int o, boolean little) {
        int b0 = b[o] & 0xFF;
        int b1 = b[o + 1] & 0xFF;
        int b2 = b[o + 2] & 0xFF;
        int b3 = b[o + 3] & 0xFF;
        return little ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0 : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /** Produce an upright image for orientations 2/3/4/6/8; returns {@code src} unchanged for anything else. */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform t = new AffineTransform();
        boolean swapDims = false;
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }                       // mirror horizontal
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }                    // 180°
            case 4 -> { t.translate(0, h); t.scale(1, -1); }                       // mirror vertical
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); swapDims = true; }       // 90° CW
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); swapDims = true; }   // 90° CCW
            default -> { return src; }
        }
        BufferedImage dst = new BufferedImage(swapDims ? h : w, swapDims ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return dst;
    }
}
