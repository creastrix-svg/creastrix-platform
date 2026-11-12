package com.creastrix.platform.productmedia;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import com.creastrix.platform.storage.LocalImmutableObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProductPhotoProcessorTest {
    @TempDir Path scratch;
    private Path originals, derivatives;
    private ProductPhotoProcessor processor;
    private static final Color[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};

    @BeforeEach void open() throws IOException {
        var privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        originals = Files.createDirectory(scratch.toRealPath().resolve("originals"), privateDirectory);
        derivatives = Files.createDirectory(scratch.toRealPath().resolve("derivatives"), privateDirectory);
        processor = ProductPhotoProcessor.open(originals, derivatives);
    }

    private static BufferedImage quadrants(int w, int h) {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            for (int i=0; i<4; i++) {
                g.setColor(COLORS[i]);
                g.fillRect((i % 2) * (w / 2), (i / 2) * (h / 2), w / 2 + 1, h / 2 + 1);
            }
        } finally { g.dispose(); }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image,format,out)).isTrue();
        return out.toByteArray();
    }
    private byte[] readMaster(ProductPhotoProcessor.Published photo) throws IOException {
        return LocalImmutableObjectStore.open(originals).read(photo.original().ref());
    }
    private byte[] readPublic(ProductPhotoProcessor.Rendition photo) throws IOException {
        return LocalImmutableObjectStore.open(derivatives).read(photo.ref());
    }
    private static byte[] tiff(int orientation, boolean bigEndian) {
        // IFD0 with just Orientation; no external pointers.
        var b=ByteBuffer.allocate(26).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        b.putShort((short)(bigEndian ? 0x4d4d : 0x4949)).putShort((short)42).putInt(8).putShort((short)1)
                .putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)orientation).putShort((short)0).putInt(0);
        return b.array();
    }
    private static void segment(ByteArrayOutputStream buffer, int marker, byte[] payload) {
        int size=payload.length+2;
        buffer.write(255); buffer.write(marker); buffer.write(size >> 8); buffer.write(size); buffer.writeBytes(payload);
    }
    private static byte[] taggedJpeg(int orientation, boolean bigEndian) throws IOException {
        byte[] plain=encode(quadrants(180,120), "JPEG");
        var out=new ByteArrayOutputStream();
        // Keep the original JFIF APP0 first as required by the format.
        int afterJfif=4+Short.toUnsignedInt(ByteBuffer.wrap(plain).getShort(4));
        out.write(plain,0,afterJfif);
        var exif=new ByteArrayOutputStream(); exif.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII)); exif.writeBytes(tiff(orientation,bigEndian));
        segment(out,0xe1,exif.toByteArray()); segment(out,0xfe,"private-camera-location".getBytes(StandardCharsets.US_ASCII));
        out.write(plain,afterJfif,plain.length-afterJfif); return out.toByteArray();
    }
    private static byte[] chunk(String name,byte[] payload) {
        var b=ByteBuffer.allocate(payload.length+12);
        b.putInt(payload.length).put(name.getBytes(StandardCharsets.US_ASCII)).put(payload);
        var crc=new CRC32();crc.update(b.array(),4,4+payload.length);b.putInt((int)crc.getValue());return b.array();
    }
    private static byte[] afterPngHeader(byte[] png,String name,byte[] payload) {
        var b=new ByteArrayOutputStream();
        b.write(png,0,33); b.writeBytes(chunk(name,payload)); b.write(png,33,png.length-33); return b.toByteArray();
    }

    @Test void createsTwoDistinctJpegsAndPreservesTheExactPrivateOriginal() throws IOException {
        byte[] master=afterPngHeader(encode(quadrants(2400,1500),"PNG"),"tEXt","Location\0private-camera-location".getBytes(StandardCharsets.US_ASCII));
        var published=processor.publish(master);
        assertThat(readMaster(published)).containsExactly(master);
        assertThat(published.original().mediaType()).isEqualTo("image/png");
        assertThat(published.original().width()).isEqualTo(2400);
        assertThat(published.thumb().width()).isEqualTo(480);
        assertThat(published.thumb().height()).isEqualTo(300);
        assertThat(published.detail().width()).isEqualTo(1600);
        assertThat(published.detail().height()).isEqualTo(1000);
        for(var rendition:new ProductPhotoProcessor.Rendition[]{published.thumb(),published.detail()}) {
            var content=readPublic(rendition);
            assertThat(rendition.mediaType()).isEqualTo("image/jpeg");
            assertThat(new String(content,StandardCharsets.ISO_8859_1)).doesNotContain("private-camera-location");
            var image=ImageIO.read(new ByteArrayInputStream(content));
            assertThat(image.getWidth()).isEqualTo(rendition.width());
            assertThat(image.getHeight()).isEqualTo(rendition.height());
        }
        assertThat(published.thumb().ref().sha256()).isNotEqualTo(published.detail().ref().sha256());
        assertThat(derivatives.resolve(published.original().ref().objectKey().toString())).doesNotExist();
        assertThat(Files.getPosixFilePermissions(originals.resolve(published.original().ref().objectKey().toString())))
            .isEqualTo(PosixFilePermissions.fromString("r--------"));
    }

    @ParameterizedTest
    @CsvSource({"1,0123","2,1032","3,3210","4,2301","5,0213","6,2031","7,3120","8,1302"})
    void rendersAllEightExifOrientationsStripsMetadataAndNeverEnlarges(int orientation, String expected) throws IOException {
        byte[] master=taggedJpeg(orientation,orientation % 2 == 0); var published=processor.publish(master);
        assertThat(readMaster(published)).containsExactly(master);
        assertThat(published.original().width()).isEqualTo(180);
        assertThat(published.original().height()).isEqualTo(120);
        for(var rendition:new ProductPhotoProcessor.Rendition[]{published.thumb(),published.detail()}) {
            byte[] bytes=readPublic(rendition); var image=ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(image.getWidth()).isEqualTo(orientation >= 5 ? 120 : 180);
            assertThat(image.getHeight()).isEqualTo(orientation >= 5 ? 180 : 120);
            assertThat(new String(bytes,StandardCharsets.ISO_8859_1)).doesNotContain("Exif","private-camera-location");
            for(int i=0;i<4;i++) {
                var actual=new Color(image.getRGB(image.getWidth() * (i % 2 * 2 + 1) / 4,image.getHeight() * (i / 2 * 2 + 1) / 4));
                var reference=COLORS[expected.charAt(i)-'0'];
                assertThat(Math.abs(actual.getRed()-reference.getRed())+Math.abs(actual.getGreen()-reference.getGreen())
                        +Math.abs(actual.getBlue()-reference.getBlue())).isLessThan(15);
            }
        }
    }

    @Test void preservesPngOrientationAndFlattensTransparentPixelsOnWhite() throws IOException {
        var image=new BufferedImage(60,40,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setColor(Color.BLUE);g.fillRect(0,0,30,40);g.dispose();
        var photo=processor.publish(afterPngHeader(encode(image,"PNG"),"eXIf",tiff(6,true)));
        var rendered=ImageIO.read(new ByteArrayInputStream(readPublic(photo.detail())));
        assertThat(rendered.getWidth()).isEqualTo(40); assertThat(rendered.getHeight()).isEqualTo(60);
        assertThat(new Color(rendered.getRGB(20,15)).getBlue()).isGreaterThan(245);
        assertThat(new Color(rendered.getRGB(20,15)).getRed()).isLessThan(10);
        assertThat(new Color(rendered.getRGB(20,45))).isEqualTo(Color.WHITE);
    }

    private static byte[] integers(int... values) {
        var data = ByteBuffer.allocate(4*values.length);
        for(int value:values) data.putInt(value);
        return data.array();
    }
    private static byte[] grayPhoto(String format) throws IOException {
        var image=new BufferedImage(40,40,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(new Color(128,128,128));g.fillRect(0,0,40,40);g.dispose();
        return encode(image,format);
    }
    private int middleGray(ProductPhotoProcessor.Rendition rendition) throws IOException {
        var image=ImageIO.read(new ByteArrayInputStream(readPublic(rendition)));
        return new Color(image.getRGB(image.getWidth()/2,image.getHeight()/2)).getRed();
    }

    private static final int[] GRAY_STEPS={0,64,128,192,255}, ALPHA_STEPS={255,128,0};
    private static final int PATCH_EDGE=24;

    // Encode our own scanlines: using the same Java ColorModel for authoring and reading
    // the oracle would conceal accidental conversion of encoded grey to linear light.
    private static byte[] grayChart(int bits,int type,boolean transparentMid) throws IOException {
        int width=GRAY_STEPS.length*PATCH_EDGE, height=ALPHA_STEPS.length*PATCH_EDGE;
        var pixels=new ByteArrayOutputStream();
        try(var z=new DeflaterOutputStream(pixels)) {
            for(int y=0;y<height;y++) {
                z.write(0); // filter None
                for(int x=0;x<width;x++) {
                    int gray=GRAY_STEPS[x/PATCH_EDGE], alpha=ALPHA_STEPS[y/PATCH_EDGE];
                    int components=switch(type){ case 0 -> 1;case 2 -> 3;case 4 -> 2;case 6 -> 4;default -> throw new AssertionError(); };
                    for(int c=0;c<components;c++) {
                        int sample=(type==4 || type==6) && c==components-1 ? alpha : gray;
                        z.write(sample);
                        if(bits==16) z.write(sample); // 8-bit sample ×257 is identical in 16-bit.
                    }
                }
            }
        }
        var result=new ByteArrayOutputStream();
        result.writeBytes(new byte[]{(byte)137,80,78,71,13,10,26,10});
        var ihdr=ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte)bits).put((byte)type).put(new byte[3]).array();
        result.writeBytes(chunk("IHDR",ihdr)); result.writeBytes(chunk("sRGB",new byte[]{0}));
        if(transparentMid) {
            byte[] key=ByteBuffer.allocate(2).putShort((short)(bits==8 ? 128 : 128*257)).array();
            result.writeBytes(chunk("tRNS",type==0 ? key : ByteBuffer.allocate(6).put(key).put(key).put(key).array()));
        }
        result.writeBytes(chunk("IDAT",pixels.toByteArray()));result.writeBytes(chunk("IEND",new byte[0]));
        return result.toByteArray();
    }

    @ParameterizedTest
    @CsvSource({"8,4,false","16,4,false","8,0,false","16,0,false","8,0,true","16,0,true"})
    void encodedGrayMatchesRgbAtFiveLevelsWithAndWithoutTransparency(int bits,int type,boolean transparentMid) throws IOException {
        var gray=processor.publish(grayChart(bits,type,transparentMid));
        var rgb=processor.publish(grayChart(bits,type==4 ? 6 : 2,transparentMid));
        for(var pair:new ProductPhotoProcessor.Rendition[][]{{gray.thumb(),rgb.thumb()},{gray.detail(),rgb.detail()}}) {
            var actual=ImageIO.read(new ByteArrayInputStream(readPublic(pair[0])));
            var reference=ImageIO.read(new ByteArrayInputStream(readPublic(pair[1])));
            for(int row=0;row<ALPHA_STEPS.length;row++) for(int col=0;col<GRAY_STEPS.length;col++) {
                int g=GRAY_STEPS[col], a=type==4 ? ALPHA_STEPS[row] : transparentMid && g==128 ? 0 : 255;
                int expected=Math.round((g*a + 255*(255-a))/255f);
                int x=col*PATCH_EDGE+PATCH_EDGE/2, y=row*PATCH_EDGE+PATCH_EDGE/2;
                var rendered=new Color(actual.getRGB(x,y));
                assertThat(rendered).isEqualTo(new Color(reference.getRGB(x,y)));
                assertThat(rendered.getRed()).isBetween(Math.max(0,expected-2),Math.min(255,expected+2));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints={1,2,4})
    void packedGrayUsesItsNativeTransparencyKeyNotAnExpandedByte(int bits) throws IOException {
        int levels=1<<bits, width=levels*PATCH_EDGE, height=PATCH_EDGE, key=levels/2;
        var raw=new ByteArrayOutputStream();
        try(var z=new DeflaterOutputStream(raw)) {
            for(int y=0;y<height;y++) {
                z.write(0);
                for(int x=0;x<width;) {
                    int packed=0;
                    for(int n=0;n<8/bits;n++) packed=(packed<<bits)|(x++/PATCH_EDGE);
                    z.write(packed);
                }
            }
        }
        var body=new ByteArrayOutputStream();
        body.writeBytes(new byte[]{(byte)137,80,78,71,13,10,26,10});
        body.writeBytes(chunk("IHDR",ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte)bits).put(new byte[4]).array()));
        body.writeBytes(chunk("sRGB",new byte[]{0}));
        body.writeBytes(chunk("IDAT",raw.toByteArray())); body.writeBytes(chunk("IEND",new byte[0]));
        for(boolean keyed:new boolean[]{false,true}) {
            var source=keyed ? afterPngHeader(body.toByteArray(),"tRNS",new byte[]{0,(byte)key}) : body.toByteArray();
            var photo=processor.publish(source);
            for(var rendition:new ProductPhotoProcessor.Rendition[]{photo.thumb(),photo.detail()}) {
                var img=ImageIO.read(new ByteArrayInputStream(readPublic(rendition)));
                for(int i=0;i<levels;i++) {
                    int expected=keyed && i==key ? 255 : i*255/(levels-1);
                    var value=new Color(img.getRGB(i*PATCH_EDGE+PATCH_EDGE/2,height/2));
                    assertThat(value.getRed()).isBetween(Math.max(0,expected-2),Math.min(255,expected+2));
                }
            }
        }
    }

    @Test void admitsTrueSrgbAndRejectsLinearGammaOrDifferentPrimariesBeforeStoringAnything() throws IOException {
        byte[] base=grayPhoto("PNG");
        byte[] primaries=integers(31270,32900,64000,33000,30000,60000,15000,6000);
        byte[] tagged=afterPngHeader(base,"sRGB",new byte[]{0});
        byte[] srgb=afterPngHeader(afterPngHeader(tagged,"gAMA",integers(45455)),"cHRM",primaries);
        byte[] wrongPrimaries=primaries.clone(); wrongPrimaries[19]^=1;
        for(byte[] invalid:new byte[][]{afterPngHeader(base,"gAMA",integers(100000)),
                afterPngHeader(tagged,"gAMA",integers(100000)),afterPngHeader(base,"cHRM",wrongPrimaries),
                afterPngHeader(tagged,"cHRM",wrongPrimaries),afterPngHeader(srgb,"sRGB",new byte[]{1}),
                afterPngHeader(srgb,"gAMA",integers(45455)),afterPngHeader(srgb,"cHRM",primaries),
                afterPngHeader(base,"gAMA",new byte[1]),afterPngHeader(base,"cHRM",new byte[5]),
                afterPngHeader(base,"sRGB",new byte[]{4})}) {
            assertThatThrownBy(()->processor.prepare(invalid)).isInstanceOf(ProductPhotoProcessor.InvalidPhotoException.class);
        }
        var prepared=processor.prepare(srgb);
        try(var orig=Files.list(originals);var rendered=Files.list(derivatives)) {
            assertThat(orig.count()).isZero(); assertThat(rendered.count()).isZero();
        }
        byte[] original=srgb.clone(); srgb[0]=0;
        var generated=processor.publish(prepared,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        assertThat(readMaster(generated)).containsExactly(original);
        assertThat(middleGray(generated.detail())).isBetween(126,130);
        assertThat(middleGray(processor.publish(base).thumb())).isBetween(126,130);
    }

    private static byte[] addJpegSegment(byte[] jpeg,int marker,byte[] payload) {
        int afterJfif=4+Short.toUnsignedInt(ByteBuffer.wrap(jpeg).getShort(4));
        var out=new ByteArrayOutputStream(); out.write(jpeg,0,afterJfif);segment(out,marker,payload);
        out.write(jpeg,afterJfif,jpeg.length-afterJfif);return out.toByteArray();
    }
    @Test void jpegConvertsActualLinearRgbIccInsteadOfSimplyDiscardingItsProfile() throws IOException {
        byte[] gray=grayPhoto("JPEG");
        byte[] linear=ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
        var payload=new ByteArrayOutputStream();payload.writeBytes("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII));
        payload.write(1);payload.write(1);payload.writeBytes(linear);
        var tagged=processor.publish(addJpegSegment(gray,0xe2,payload.toByteArray()));
        assertThat(middleGray(tagged.thumb())).isBetween(184,192);
        String out=new String(readPublic(tagged.detail()),StandardCharsets.ISO_8859_1);
        assertThat(out).doesNotContain("ICC_PROFILE");
        assertThat(middleGray(processor.publish(gray).thumb())).isBetween(126,130);
        byte[] incomplete=payload.toByteArray();incomplete[13]=2;
        assertThatThrownBy(()->processor.prepare(addJpegSegment(gray,0xe2,incomplete))).isInstanceOf(ProductPhotoProcessor.InvalidPhotoException.class);
    }
    @Test void exifSubdirectoryCannotDeclareAnUnsupportedSourceColourSpace() throws IOException {
        byte[] gray=grayPhoto("JPEG");
        var tiff=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        tiff.putShort((short)0x4949).putShort((short)42).putInt(8)
                .putShort((short)1).putShort((short)0x8769).putShort((short)4).putInt(1).putInt(26).putInt(0)
                .putShort((short)1).putShort((short)0xa001).putShort((short)3).putInt(1).putShort((short)1).putShort((short)0).putInt(0);
        var good=new ByteArrayOutputStream(); good.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII)); good.writeBytes(tiff.array());
        assertThat(middleGray(processor.publish(addJpegSegment(gray,0xe1,good.toByteArray())).thumb())).isBetween(126,130);
        byte[] unsupported=good.toByteArray();unsupported[6+26+2+8]=2;
        assertThatThrownBy(()->processor.prepare(addJpegSegment(gray,0xe1,unsupported))).isInstanceOf(ProductPhotoProcessor.InvalidPhotoException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"empty","unsupported","oversize","dimensions","truncated","crc","orientation","animation","embedded-profile","multiple","invalid-pixels"})
    void unsupportedOrCorruptPhotosPublishNothing(String type) throws IOException {
        byte[] png=encode(quadrants(40,30),"PNG"), jpeg=taggedJpeg(1,true);
        byte[] hugeHeader=Arrays.copyOfRange(png,16,29);
        ByteBuffer.wrap(hugeHeader).putInt(6000).putInt(5000);
        byte[] badSize=png.clone(); System.arraycopy(chunk("IHDR",hugeHeader),0,badSize,8,25);
        byte[] badCrc=png.clone();badCrc[30]^=1;
        var invalidPixels=new ByteArrayOutputStream();invalidPixels.write(png,0,33);
        invalidPixels.writeBytes(chunk("IDAT",new byte[]{0,0,0,0}));invalidPixels.writeBytes(chunk("IEND",new byte[0]));
        var source=switch(type) {
            case "empty" -> new byte[0];
            case "unsupported" -> "not a raster".getBytes(StandardCharsets.US_ASCII);
            case "oversize" -> new byte[ProductPhotoProcessor.MAX_INPUT_BYTES+1];
            case "dimensions" -> badSize;
            case "truncated" -> Arrays.copyOf(jpeg,jpeg.length-1);
            case "crc" -> badCrc;
            case "orientation" -> taggedJpeg(9,false);
            case "animation" -> afterPngHeader(png,"acTL", new byte[8]);
            case "embedded-profile" -> afterPngHeader(png,"iCCP", new byte[16]);
            case "multiple" -> ByteBuffer.allocate(jpeg.length*2).put(jpeg).put(jpeg).array();
            case "invalid-pixels" -> invalidPixels.toByteArray();
            default -> throw new AssertionError();
        };
        assertThatThrownBy(()->processor.publish(source)).isInstanceOf(ProductPhotoProcessor.InvalidPhotoException.class);
        try (var orig=Files.list(originals); var previews=Files.list(derivatives)) {
            assertThat(orig.count()).isZero(); assertThat(previews.count()).isZero();
        }
    }

    @Test void aRetryUsesOnlyItsOriginalReservedKeysAndNeverOverwritesBytes() throws IOException {
        var master=encode(quadrants(200,90),"PNG");
        var first=processor.publish(master); var o=first.original().ref().objectKey();
        var t=first.thumb().ref().objectKey();var d=first.detail().ref().objectKey();
        assertThat(processor.publish(master,o,t,d)).isEqualTo(first);
        byte[] replacement=encode(quadrants(80,80),"PNG");
        assertThatThrownBy(()->processor.publish(replacement,o,t,d)).isInstanceOf(IOException.class);
        assertThat(readMaster(first)).containsExactly(master);
        try(var orig=Files.list(originals);var previews=Files.list(derivatives)) {
            assertThat(orig.count()).isOne();assertThat(previews.count()).isEqualTo(2);
        }
        assertThatThrownBy(()->ProductPhotoProcessor.open(originals,originals)).isInstanceOf(IOException.class);
    }
}
