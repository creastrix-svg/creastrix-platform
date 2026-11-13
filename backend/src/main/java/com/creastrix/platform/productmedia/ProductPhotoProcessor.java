package com.creastrix.platform.productmedia;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.creastrix.platform.storage.ImmutableObjectStore;
import com.creastrix.platform.storage.ImmutableObjectStore.Reference;
import com.creastrix.platform.storage.LocalImmutableObjectStore;

/** Pixel processing and durable storage only. Publication to a product is a separate
 * authorised metadata transaction. The original's dimensions are encoded dimensions;
 * both JPEG derivatives are orientation-corrected and contain no source metadata. */
public final class ProductPhotoProcessor {
    public static final int MAX_INPUT_BYTES = 12 * 1024 * 1024;
    public static final int THUMB_EDGE = 480;
    public static final int DETAIL_EDGE = 1600;
    private static final int MAX_EDGE = 12_000;
    private static final long MAX_PIXELS = 24_000_000L;

    public record Rendition(Reference ref, String mediaType, int width, int height) {}
    public record Published(Rendition original, Rendition thumb, Rendition detail) {}

    /** Opaque, validated request snapshot. Its byte arrays are owned exclusively here:
     * the caller can stage catalog metadata without being able to replace validated pixels. */
    public static final class Prepared {
        private final Encoded original, thumb, detail;
        private Prepared(Encoded original, Encoded thumb, Encoded detail) {
            this.original = original; this.thumb = thumb; this.detail = detail;
        }
    }
    private record Encoded(byte[] bytes, String mediaType, int width, int height) {
        Rendition persist(ImmutableObjectStore destination, UUID key) throws IOException {
            return new Rendition(store(destination,key,bytes), mediaType,width,height);
        }
    }

    public static final class InvalidPhotoException extends IllegalArgumentException {
        public InvalidPhotoException() { super("PRODUCT_PHOTO_INVALID"); }
    }

    private final ImmutableObjectStore originals;
    private final ImmutableObjectStore derivatives;

    private ProductPhotoProcessor(ImmutableObjectStore originals, ImmutableObjectStore derivatives) {
        this.originals = originals;
        this.derivatives = derivatives;
    }

    public static ProductPhotoProcessor open(Path originals, Path derivatives) throws IOException {
        if (Files.isSameFile(originals, derivatives))
            throw new IOException("Separate original and derivative stores are required");
        return new ProductPhotoProcessor(LocalImmutableObjectStore.open(originals), LocalImmutableObjectStore.open(derivatives));
    }

    public Published publish(byte[] original) throws IOException {
        return publish(original, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    public Published publish(byte[] original, UUID originalKey, UUID thumbKey, UUID detailKey) throws IOException {
        return publish(prepare(original),originalKey,thumbKey,detailKey);
    }

    /** Validate and encode before booking object keys in the database. No filesystem or
     * database writes; run once, outside any SQL lock/transaction. */
    public Prepared prepare(byte[] original) throws IOException {
        if (original == null || original.length == 0 || original.length > MAX_INPUT_BYTES) throw new InvalidPhotoException();
        byte[] bytes = original.clone();
        var source = PhotoContainer.inspect(bytes);
        if (source.width() <= 0 || source.height() <= 0 || Math.max(source.width(), source.height()) > MAX_EDGE
                || (long) source.width() * source.height() > MAX_PIXELS) throw new InvalidPhotoException();

        BufferedImage decoded = decode(source);
        int width = source.orientation() >= 5 ? source.height() : source.width();
        int height = source.orientation() >= 5 ? source.width() : source.height();
        var detail = resize(decoded, width, height, DETAIL_EDGE, source.orientation());
        var thumb = resize(detail, width, height, THUMB_EDGE, 1);
        return new Prepared(new Encoded(bytes,source.mediaType(),source.width(),source.height()),
                new Encoded(jpeg(thumb),"image/jpeg",thumb.getWidth(),thumb.getHeight()),
                new Encoded(jpeg(detail),"image/jpeg",detail.getWidth(),detail.getHeight()));
    }

    /** Keys must be allocated by the trusted metadata writer, never chosen by a buyer.
     * A retry with the same keys only succeeds if every previously written object matches
     * exactly. After an interruption objects stay private until the caller commits the links. */
    public Published publish(Prepared prepared, UUID originalKey, UUID thumbKey, UUID detailKey) throws IOException {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(originalKey);
        Objects.requireNonNull(thumbKey);
        Objects.requireNonNull(detailKey);
        if (originalKey.equals(thumbKey) || originalKey.equals(detailKey) || thumbKey.equals(detailKey))
            throw new IllegalArgumentException("Product photo keys must be distinct");
        return new Published(prepared.original.persist(originals,originalKey),
                prepared.thumb.persist(derivatives,thumbKey),prepared.detail.persist(derivatives,detailKey));
    }

    private static Reference store(ImmutableObjectStore store, UUID key, byte[] bytes) throws IOException {
        try {
            return store.writeNew(key, bytes);
        } catch (FileAlreadyExistsException occupied) {
            final String sha256;
            try { sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            var expected = new Reference(key, bytes.length, sha256);
            if (!Arrays.equals(store.read(expected), bytes)) throw new IOException("Product photo version conflict");
            return expected;
        }
    }

    private static BufferedImage decode(PhotoContainer source) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source.decodeBytes()))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName(source.mediaType().equals("image/jpeg") ? "JPEG" : "PNG").next();
            try {
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((ignored, message) -> warning[0] = true);
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != source.width() || reader.getHeight(0) != source.height()) throw new InvalidPhotoException();
                // For a large camera file keep enough samples for a high-quality final resize
                // without allocating a second full sensor-sized transformed raster.
                int sample = Math.max(1, Math.max(source.width(), source.height()) / (2 * DETAIL_EDGE));
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(sample, sample, 0, 0);
                var image = reader.read(0, params);
                if (warning[0] || image == null) throw new InvalidPhotoException();
                return source.hasPngGraySamples() ? pngGrayToSrgb(image,source) : image;
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException invalid) {
            throw new InvalidPhotoException();
        }
    }

    /** PNG greys represent encoded sRGB channel samples for our admitted sources. In particular
     * ImageIO may wrap (grey,alpha) in Java CS_GRAY, which Graphics2D treats as linear light and
     * brightens during colour conversion. Copy unassociated samples to an explicit sRGB raster
     * before compositing. Alpha is linear coverage, not a colour channel or a transfer function. */
    private static BufferedImage pngGrayToSrgb(BufferedImage image, PhotoContainer source) {
        var raster = image.getRaster();
        int bands = raster.getNumBands(), width = image.getWidth(), height = image.getHeight();
        if ((bands != 1 && bands != 2) || image.isAlphaPremultiplied()) throw new InvalidPhotoException();
        boolean keyed=source.grayTransparentSample() >= 0;
        var output = new BufferedImage(width,height,bands == 2 || keyed ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var pixels = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
        int grayMax = sampleMax(raster.getSampleModel().getSampleSize(0));
        int transparent = keyed ? (int)((long)source.grayTransparentSample()*grayMax /
                sampleMax(Byte.toUnsignedInt(source.decodeBytes()[24]))) : -1;
        int alphaMax = bands == 2 ? sampleMax(raster.getSampleModel().getSampleSize(1)) : 255;
        int[] grays = new int[width], alphas = bands == 2 ? new int[width] : null;
        for (int y=0;y<height;y++) {
            raster.getSamples(0,y,width,1,0,grays);
            if (alphas != null) raster.getSamples(0,y,width,1,1,alphas);
            for (int x=0;x<width;x++) {
                int gray = (grays[x]*255 + grayMax/2)/grayMax;
                int alpha = alphas == null ? 255 : (alphas[x]*255 + alphaMax/2)/alphaMax;
                if (grays[x] == transparent) alpha = 0;
                pixels[y*width+x] = (alpha << 24) | gray*0x010101;
            }
        }
        return output;
    }

    private static int sampleMax(int bits) {
        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) throw new InvalidPhotoException();
        return (1 << bits)-1;
    }

    private static BufferedImage resize(BufferedImage source, int displayedWidth, int displayedHeight, int edge, int orientation) {
        double factor = Math.min(1d, (double) edge / Math.max(displayedWidth, displayedHeight));
        int width = Math.max(1, (int) Math.round(displayedWidth * factor));
        int height = Math.max(1, (int) Math.round(displayedHeight * factor));
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int w = source.getWidth(), h = source.getHeight();
        var orient = switch (orientation) {
            case 1 -> new AffineTransform();
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> throw new InvalidPhotoException();
        };
        var transform = AffineTransform.getScaleInstance((double) width / (orientation >= 5 ? h : w),
                (double) height / (orientation >= 5 ? w : h));
        transform.concatenate(orient);
        var graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, transform, null);
        } finally { graphics.dispose(); }
        return result;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(.88f);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
}
