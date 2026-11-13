package com.creastrix.platform.accountcart;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.Kind;
import com.creastrix.platform.accountcart.LocalCartCommands.InvalidBatch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local Loop print preview only. v1 is an ordered, typed layout, not a manufacturing instruction.
 * No browser handles, labels, file bytes or digests occur in the stored layout. Each external
 * resource occurrence consumes one ordered slot, resolved separately to exact verified bytes. */
public final class StudioDesign {
    public static final int MAX_WIRE=24*1024*1024;
    private static final int MAGIC=0x4c535401; // LST, version 1; pins the Studio font/geometry vocabulary.
    private static final long UNITS=1L<<48;
    static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Pattern GRAPHEME=Pattern.compile("\\X");
    // Immutable Loop@2 font definitions. Matching the short editor ID alone is insufficient:
    // encode its exact version in canonical identity and refuse unsupported historical mappings.
    static final Map<String,String> FONT_PINS=Map.of(
            "rounded","bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207",
            "editorial","b20b7d9626dd956b2c5e558692ad328b1f19e3275e2782db4fa07670d83f35e0",
            "handwritten","0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988");
    /** Shared by the public capability projection and the private admission check. */
    public static final String TEMPLATE_V2="""
        {"format":"loop-studio-v1","variants":["cobalt","coral","chalk","cobalt-chalk"],
         "zones":[{"id":"surface","operation":"print","tools":["text","image"]}]}
        """;
    private static final Set<String> INKS=Set.of("#ffffff","#20251f","#b68a45");
    final String sku;
    final long template;
    final byte[] layout;
    final List<Resource> resources;
    record Resource(Kind kind,byte[] bytes,String digest) {}
    private StudioDesign(String sku,long template,byte[] layout,List<Resource> resources) {
        this.sku=sku; this.template=template; this.layout=layout; this.resources=List.copyOf(resources);
    }
    private static void require(boolean condition) { if(!condition) throw new InvalidBatch(); }
    private static String string(JsonNode node,String key) {
        var value=node.get(key); require(value!=null && value.isString()); return value.asString();
    }
    private static long geometry(JsonNode node,String key,int min,int max) {
        var value=node.get(key); require(value!=null && value.isNumber());
        double number=value.asDouble(); require(number>=min && number<=max);
        // JSON numbers are admitted via the editor's round-trippable decimal spelling. Do
        // not silently collapse a higher-precision wire value onto a neighbouring double.
        require(value.decimalValue().compareTo(java.math.BigDecimal.valueOf(number))==0);
        // All accepted editor doubles (18..332) fit exactly in this fixed-point grid and int64.
        long units=(long)(number*UNITS); require((double)units/UNITS==number);
        return units;
    }
    private static String text(String raw) {
        require(raw.length()<=1024);
        String value=raw.replace("\r\n","\n").replace('\r','\n');
        int lines=1;
        for(int cp:value.codePoints().toArray()) {
            if(cp=='\n') { require(++lines<=2); continue; }
            require(!Character.isISOControl(cp) && !(cp>=0xd800&&cp<=0xdfff) &&
                    !(cp>=0x202a&&cp<=0x202e) && !(cp>=0x2066&&cp<=0x2069));
        }
        require(GRAPHEME.matcher(value).results().limit(33).count()<=32);
        return value;
    }
    private static Resource resource(Kind kind,String data) {
        int limit=kind==Kind.IMAGE?10*1024*1024:2*1024*1024;
        require(!data.isEmpty() && data.length()<=4*((limit+2)/3) && data.length()%4==0);
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(data); }
        catch(IllegalArgumentException invalid) { throw new InvalidBatch(); }
        require(bytes.length>0 && bytes.length<=limit);
        if(kind==Kind.IMAGE) png(bytes); else font(bytes);
        try { return new Resource(kind,bytes,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void png(byte[] bytes) {
        // Normalized canvas PNGs only: never hand unbounded profiles, text metadata or an
        // animated/multipage stream to a generic image decoder.
        var b=ByteBuffer.wrap(bytes);
        require(bytes.length>=45 && b.getLong()==0x89504e470d0a1a0aL);
        boolean first=true,idat=false,end=false;
        int width=0,height=0;
        while(b.hasRemaining()) {
            require(!end && b.remaining()>=12);
            int length=b.getInt(),at=b.position();
            require(length>=0 && length<=b.remaining()-8);
            String name=new String(bytes,at,4,StandardCharsets.US_ASCII);
            b.position(at+4);
            if(first) {
                require(name.equals("IHDR") && length==13); first=false;
                width=b.getInt(); height=b.getInt(); require(width>0 && height>0 && width<=1536 && height<=1536);
            } else switch(name) {
                case "IDAT" -> idat=true;
                case "IEND" -> { require(length==0 && idat); end=true; }
                case "PLTE","tRNS","sRGB","pHYs","gAMA","cHRM","sBIT","bKGD" -> require(length<=768);
                default -> throw new InvalidBatch();
            }
            b.position(at+4+length);
            var crc=new CRC32(); crc.update(bytes,at,4+length); require((int)crc.getValue()==b.getInt());
        }
        require(end);
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader=ImageIO.getImageReadersByFormatName("png").next();
            try {
                reader.setInput(input,true,true);
                require(reader.getWidth(0)==width && reader.getHeight(0)==height && reader.read(0)!=null);
            } finally { reader.dispose(); }
        } catch(IOException | IllegalArgumentException invalid) { throw new InvalidBatch(); }
    }
    private static void font(byte[] bytes) {
        require(bytes.length>=12);
        var b=ByteBuffer.wrap(bytes); int format=b.getInt();
        require(format==0x00010000 || format==0x4f54544f); // TrueType or OpenType/CFF, not collections or WOFF2.
        int count=Short.toUnsignedInt(b.getShort()); require(count>0 && count<=256 && 12+16*count<=bytes.length);
        Set<Integer> tags=new HashSet<>();
        for(int i=0;i<count;i++) {
            int entry=12+i*16, offset=b.getInt(entry+8), length=b.getInt(entry+12);
            require(tags.add(b.getInt(entry)) && offset>=12+16*count && length>=0 && (long)offset+length<=bytes.length);
            require(b.getInt(entry)!=0x53564720); // No embedded SVG documents.
        }
        try { require(Font.createFont(Font.TRUETYPE_FONT,new ByteArrayInputStream(bytes)).getNumGlyphs()>0); }
        catch(IOException | FontFormatException invalid) { throw new InvalidBatch(); }
    }
    public static StudioDesign parse(JsonNode body) {
        require(body!=null && body.isObject() && body.size()==4);
        String sku=string(body,"sku"), rev=string(body,"templateRevision");
        require(sku.length()<=200 && sku.matches("[a-z0-9]+(-[a-z0-9]+)*") && rev.equals("2"));
        require(string(body,"zone").equals("surface"));
        var objects=body.get("objects"); require(objects!=null && objects.isArray() && objects.size()>=1 && objects.size()<=8);
        var resources=new ArrayList<Resource>(); var buffer=new ByteArrayOutputStream();
        boolean visible=false; var uniqueFonts=new ArrayList<byte[]>();
        try(var out=new DataOutputStream(buffer)) {
            out.writeInt(MAGIC); out.writeByte(objects.size());
            for(var row:objects) {
                require(row.isObject());
                boolean image=false;
                double imageHalfWidth=0,imageHalfHeight=0;
                switch(string(row,"kind")) {
                    case "text" -> {
                        String font=string(row,"font"), ink=string(row,"color"), value=text(string(row,"text"));
                        boolean external=font.equals("resource");
                        require((external || FONT_PINS.containsKey(font)) && INKS.contains(ink) && row.size()==(external?8:7));
                        out.writeByte(1); field(out,value); field(out,ink); field(out,external?font:font+"@"+FONT_PINS.get(font));
                        if(external) {
                            var resource=resource(Kind.FONT,string(row,"data")); resources.add(resource);
                            if(uniqueFonts.stream().noneMatch(bytes->Arrays.equals(bytes,resource.bytes()))) uniqueFonts.add(resource.bytes());
                            require(uniqueFonts.size()<=3);
                        }
                        out.writeLong(geometry(row,"size",18,32)); visible|=value.codePoints()
                                .anyMatch(c->!Character.isSpaceChar(c) && !Character.isWhitespace(c) && c!=0xfeff);
                    }
                    case "image" -> {
                        String fit=string(row,"fit");
                        require(row.size()==6 && Set.of("free","contain","cover").contains(fit));
                        var resource=resource(Kind.IMAGE,string(row,"data")); resources.add(resource);
                        out.writeByte(2); field(out,fit);
                        long size=geometry(row,"size",20,150);
                        var header=ByteBuffer.wrap(resource.bytes());
                        double aspect=(double)header.getInt(16)/header.getInt(20);
                        double width=Math.min((double)size/UNITS,109*aspect);
                        imageHalfWidth=width/2; imageHalfHeight=width/aspect/2;
                        out.writeLong(size); visible=true; image=true;
                    }
                    default -> throw new InvalidBatch();
                }
                long x=geometry(row,"x",image?157:161,image?323:319), y=geometry(row,"y",image?223:227,image?332:328);
                if(image) {
                    double px=(double)x/UNITS,py=(double)y/UNITS;
                    require(px>=157+imageHalfWidth && px<=323-imageHalfWidth &&
                            py>=223+imageHalfHeight && py<=332-imageHalfHeight);
                }
                out.writeLong(x); out.writeLong(y);
            }
        } catch(IOException impossible) { throw new AssertionError(impossible); }
        require(visible);
        return new StudioDesign(sku,Long.parseLong(rev),buffer.toByteArray(),resources);
    }
    private static void field(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String field(DataInputStream in) throws IOException {
        int n=in.readInt(); if(n<0 || n>4096 || n>in.available()) throw new IOException();
        return new String(in.readNBytes(n),StandardCharsets.UTF_8);
    }
    static ObjectNode restore(String sku,long revision,byte[] layout,List<Resource> resources) throws IOException {
        return decode(sku,revision,layout,resources.iterator(),null);
    }
    /** Bounded private display metadata, never a filename or source image/font. */
    static String caption(byte[] layout) {
        try {
            for(var row:decode("",2,layout,null,null).get("objects")) {
                if(row.has("text")) {
                    String text=row.get("text").asString().replaceAll("(?U)[\\s\\p{Cc}]+"," ").strip();
                    if(!text.isEmpty())return text.substring(0,text.offsetByCodePoints(0,Math.min(32,text.codePointCount(0,text.length()))));
                }
            }
            return null;
        } catch(IOException invalid) {throw new InvalidBatch();}
    }
    // Prepare cannot bypass current support by directly naming a legacy saved design UUID.
    // Resource verification is a separate, mandatory step of prepare/apply, outside the DB locks.
    static boolean supportedLayout(byte[] layout) {
        if(layout==null || layout.length>65536) return false;
        try { decode("",2,layout,null,null); return true; }
        catch(IOException unavailable) { return false; }
    }
    /** Assignment to the cart compares the applied surface. Keep the saved source unchanged:
     * in contain/cover, the template and PNG aspect determine the entire fitted rectangle;
     * the free-mode transform is retained for editing, but cannot distinguish two cart lines. */
    static byte[] comparisonLayout(byte[] layout) {
        if(layout==null || layout.length>65536) return null;
        var effective=layout.clone();
        try { decode("",2,layout,null,effective); return effective; }
        catch(IOException unsupported) { return null; }
    }
    private static ObjectNode decode(String sku,long revision,byte[] layout,Iterator<Resource> slots,byte[] effective) throws IOException {
        if(revision!=2) throw new IOException();
        var result=JSON.createObjectNode().put("sku",sku).put("templateRevision",Long.toString(revision)).put("zone","surface");
        var rows=result.putArray("objects");
        try(var in=new DataInputStream(new ByteArrayInputStream(layout))) {
            if(in.readInt()!=MAGIC) throw new IOException();
            int count=in.readUnsignedByte(); if(count<1 || count>8) throw new IOException();
            for(int i=0;i<count;i++) {
                var row=rows.addObject(); int type=in.readUnsignedByte();
                Kind kind=null; boolean fitted=false;
                if(type==1) {
                    row.put("kind","text").put("text",field(in)).put("color",field(in));
                    String font=field(in);
                    if(font.equals("resource")) { row.put("font",font); kind=Kind.FONT; }
                    else {
                        int delimiter=font.indexOf('@');
                        if(delimiter<1) throw new IOException();
                        String label=font.substring(0,delimiter);
                        if(!font.substring(delimiter+1).equals(FONT_PINS.get(label))) throw new IOException();
                        row.put("font",label);
                    }
                } else if(type==2) {
                    String fit=field(in);
                    if(!Set.of("free","contain","cover").contains(fit)) throw new IOException();
                    row.put("kind","image").put("fit",fit); kind=Kind.IMAGE; fitted=!fit.equals("free");
                }
                else throw new IOException();
                if(kind!=null && slots!=null) {
                    if(!slots.hasNext()) throw new IOException();
                    var slot=slots.next(); if(slot.kind()!=kind) throw new IOException();
                    row.put("data",Base64.getEncoder().encodeToString(slot.bytes()));
                }
                int at=layout.length-in.available();
                row.put("size",(double)in.readLong()/UNITS).put("x",(double)in.readLong()/UNITS).put("y",(double)in.readLong()/UNITS);
                if(fitted && effective!=null) Arrays.fill(effective,at,at+3*Long.BYTES,(byte)0);
            }
            if(slots!=null && slots.hasNext() || in.available()!=0) throw new IOException();
        }
        return result;
    }
}
