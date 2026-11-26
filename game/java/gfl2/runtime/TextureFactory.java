package gfl2.runtime;
import gfl2.motion.PmxShading;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;

/** Render-thread image reuse and bounded live bindings; no per-frame file reads. */
final class TextureFactory implements AutoCloseable {
    private final boolean viewpoint;
    TextureFactory(boolean viewpoint) { this.viewpoint = viewpoint; }
    record Pixels(int width,int height,byte[] rgba) {}
    record ImageSize(int width,int height) {}
    final LinkedHashMap<String,Texture> textures=new LinkedHashMap<>();
    private final LinkedHashMap<String,ImageSize> dimensions=new LinkedHashMap<>();
    private Texture placeholder;
    private boolean placeholderActive;
    private Texture[] bound;
    private long version=Long.MIN_VALUE,epoch=Long.MIN_VALUE;
    volatile int liveTextures,decodedImages;
    volatile long uploads;

    private static Pixels decode(String file)throws Exception {
        if(file.isBlank()||Files.isDirectory(Path.of(file)))return new Pixels(1,1,new byte[]{-1,-1,-1,-1});
        ByteBuffer encoded=null,pixels=null;
        String failure=null;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            byte[] bytes=Files.readAllBytes(Path.of(file));encoded=MemoryUtil.memAlloc(bytes.length);encoded.put(bytes).flip();
            IntBuffer w=stack.mallocInt(1),h=stack.mallocInt(1),c=stack.mallocInt(1);
            pixels=STBImage.stbi_load_from_memory(encoded,w,h,c,4);
            if(pixels!=null){
                // STB frees memAddress(buffer), so copying must preserve its base position.
                byte[] rgba=new byte[Math.multiplyExact(Math.multiplyExact(w.get(0),h.get(0)),4)];pixels.get(0,rgba);
                return new Pixels(w.get(0),h.get(0),rgba);
            }
            failure=STBImage.stbi_failure_reason();
        } finally {if(pixels!=null)STBImage.stbi_image_free(pixels);if(encoded!=null)MemoryUtil.memFree(encoded);}
        Pixels fallback=decodeFallback(file);
        if(fallback!=null)return fallback;
        throw new IllegalArgumentException("贴图无法读取："+file+" "+failure);
    }
    private static Pixels decodeFallback(String file) {
        try {
            java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(Path.of(file).toFile());
            if(image==null)return null;
            int width=image.getWidth(),height=image.getHeight();
            int[] argb=image.getRGB(0,0,width,height,null,0,width);
            byte[] rgba=new byte[Math.multiplyExact(Math.multiplyExact(width,height),4)];
            for(int i=0,p=0;i<argb.length;i++,p+=4) {
                int pixel=argb[i];
                rgba[p]=(byte)((pixel>>>16)&255);
                rgba[p+1]=(byte)((pixel>>>8)&255);
                rgba[p+2]=(byte)(pixel&255);
                rgba[p+3]=(byte)((pixel>>>24)&255);
            }
            return new Pixels(width,height,rgba);
        } catch(Exception ignored) {
            return null;
        }
    }
    private Texture bake(Pixels pixels,float[] material,int at,int marker,int[] ink)throws Exception {
        ByteBuffer output=MemoryUtil.memAlloc(pixels.rgba.length);
        TextureID id=null;
        try {
            int[] lookup=new int[4*256];
            boolean identity=true;
            for(int k=0;k<4;k++)for(int b=0;b<256;b++) {
                float sample=b/255f;
                float value=Math.clamp(sample*material[at+4+k]+material[at+8+k],0,1)*material[at+k];
                int result=Math.round(Math.clamp(value,0,1)*255);
                lookup[k*256+b]=result<<((3-k)*8);
                identity&=result==b;
            }
            int stride=pixels.width*4;
            byte[] raw=pixels.rgba;
            if(identity){
                for(int y=pixels.height-1;y>=0;y--)output.put(raw,y*stride,stride);
            }else{
                output.order(ByteOrder.BIG_ENDIAN);
                for(int y=pixels.height-1;y>=0;y--)for(int p=y*stride,tail=p+stride;p<tail;p+=4){
                    output.putInt(lookup[raw[p]&255] | lookup[256+(raw[p+1]&255)]
                        | lookup[512+(raw[p+2]&255)] | lookup[768+(raw[p+3]&255)]);
                }
            }
            output.flip();
            id=new TextureID(pixels.width,pixels.height,0,GL11.GL_RGBA,GL11.GL_RGBA8);id.bindalways();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL11.GL_REPEAT);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL11.GL_REPEAT);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,pixels.width,pixels.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,output);
            if(marker>=0){
                int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
                try(MemoryStack stack=MemoryStack.stackPush()){
                    ByteBuffer corner=stack.malloc(16);for(int k=0;k<4;k++)corner.put((byte)17).put((byte)93).put((byte)(197+marker)).put((byte)251);corner.flip();
                    for(int y:new int[]{0,height-2})for(int x:new int[]{0,width-2})GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,x,y,2,2,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,corner);
                }
            }
            if(ink!=null){
                try(MemoryStack stack=MemoryStack.stackPush()){
                    ByteBuffer patch=stack.malloc(64);
                    for(int k=0;k<16;k++)for(int channel:ink)patch.put((byte)channel);
                    patch.flip();GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,3,3,4,4,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,patch);
                }
            }
            Texture result=new Texture(id,"gfl2-pmx:"+uploads);
            if(!result.isReady())throw new IllegalStateException("游戏贴图尚未就绪");
            uploads++;return result;
        } catch(Throwable e) {if(id!=null)id.destroy();throw e;}
        finally {MemoryUtil.memFree(output);}
    }
    Texture[] preparePlaceholder(int count)throws Exception {
        if(placeholderActive&&bound!=null&&bound.length==count)return bound;
        if(placeholder==null){
            placeholder=bake(new Pixels(1,1,new byte[]{-1,-1,-1,-1}),new float[]{1,1,1,1,1,1,1,1,0,0,0,0},0,-1,null);
        }
        for(Texture tex:textures.values()){
            if(viewpoint)ViewpointModel.retireDrawing(tex::destroy);
            else tex.destroy();
        }
        textures.clear();dimensions.clear();
        Texture[] result=new Texture[count];
        Arrays.fill(result,placeholder);
        placeholderActive=true;version=Long.MIN_VALUE;bound=result;liveTextures=1;decodedImages=0;
        return result;
    }
    Texture[] prepare(String[] files,float[] material,long nextVersion,long nextEpoch)throws Exception {return prepare(files,material,nextVersion,nextEpoch,null);}
    Texture[] prepare(String[] files,float[] material,long nextVersion,long nextEpoch,PmxShading shading)throws Exception {
        if(!placeholderActive&&version==nextVersion&&epoch==nextEpoch)return bound;
        if(material.length!=files.length*12)throw new IllegalArgumentException("Material texture count differs");
        LinkedHashMap<String,Texture> next=new LinkedHashMap<>(),created=new LinkedHashMap<>();
        LinkedHashMap<String,Pixels> decoded=new LinkedHashMap<>();
        LinkedHashMap<String,ImageSize> nextDimensions=new LinkedHashMap<>();
        java.util.HashMap<String,Integer> lastUse=new java.util.HashMap<>();
        for(int i=0;i<files.length;i++)lastUse.put(files[i],i);
        Texture[] result=new Texture[files.length];
        try {
            for(int i=0;i<files.length;i++) {
                String file=files[i];Pixels pixels=decoded.get(file);
                ImageSize size=pixels!=null?new ImageSize(pixels.width,pixels.height):(epoch==nextEpoch?dimensions.get(file):null);
                if(size==null){pixels=decode(file);decoded.put(file,pixels);size=new ImageSize(pixels.width,pixels.height);}
                nextDimensions.put(file,size);
                int category=shading==null?-1:shading.kinds[i]==4?2:shading.kinds[i]==1||shading.kinds[i]==2?1:0;
                int marker=shading!=null&&shading.markerClearance[i]>3f/Math.min(size.width,size.height)?category:-1;
                int[] ink=shading!=null&&shading.outlineColors[i]!=null&&shading.markerClearance[i]>8f/Math.min(size.width,size.height)?shading.outlineColors[i]:null;
                String key=nextEpoch+"|"+file+"|"+Arrays.toString(Arrays.copyOfRange(material,i*12,i*12+12))+"|M"+marker+"|O"+Arrays.toString(ink);
                Texture texture=next.get(key);if(texture==null)texture=textures.get(key);
                if(texture==null){
                    if(pixels==null){pixels=decode(file);decoded.put(file,pixels);}
                    texture=bake(pixels,material,i*12,marker,ink);created.put(key,texture);
                }
                next.put(key,texture);result[i]=texture;
                if(lastUse.get(file)==i)decoded.remove(file);
            }
        } catch(Throwable e) {for(Texture texture:created.values())texture.destroy();throw e;}
        for(var entry:textures.entrySet())if(!next.containsKey(entry.getKey())) {
            if(viewpoint)ViewpointModel.retireDrawing(entry.getValue()::destroy);
            else entry.getValue().destroy();
        }
        if(placeholder!=null&&placeholderActive){
            Texture oldPlaceholder=placeholder;placeholder=null;
            if(viewpoint)ViewpointModel.retireDrawing(oldPlaceholder::destroy);
            else oldPlaceholder.destroy();
        }
        placeholderActive=false;
        textures.clear();textures.putAll(next);dimensions.clear();dimensions.putAll(nextDimensions);
        version=nextVersion;epoch=nextEpoch;bound=result;liveTextures=textures.size();decodedImages=dimensions.size();return result;
    }
    public void close() {
        java.util.ArrayList<AutoCloseable> ownedList=new java.util.ArrayList<>(textures.size()+1);
        for(Texture texture:textures.values())ownedList.add(texture::destroy);
        if(placeholder!=null){Texture p=placeholder;placeholder=null;ownedList.add(p::destroy);}
        placeholderActive=false;
        textures.clear();dimensions.clear();bound=null;liveTextures=decodedImages=0;
        gfl2.motion.ResourceCleanup.close(ownedList.toArray(AutoCloseable[]::new));
    }
}
