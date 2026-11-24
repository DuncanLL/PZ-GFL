package viewpoint.render;

import gfl2.runtime.ReadyRoomPreview;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;
import viewpoint.platform.Gfl2NativePackSelection;

/**
 * Self-contained GF2 studio preview renderer for Ready Room and UI3DModel (inventory/character screen).
 *
 * Resource & performance discipline:
 * - Zero shadow maps, zero live reflection probes, zero scene light gathering.
 * - Strict LRU cache of at most 2 preview models with reference-counted BC7 textures and material UBOs.
 * - Per-part VBOs are uploaded only when the CPU positions array reference changes (zero upload while orbiting).
 * - Automatic full transient reclamation (FBO + all cached preview textures/VAOs) after idle timeout or close.
 */
final class Gfl2StudioPreviewRenderer implements AutoCloseable {
    private static final int MAX_CACHED_MODELS = 2;
    private static final int MAX_FBO_DIM = 1280;
    private static final long IDLE_RECLAIM_NANOS = 3_000_000_000L;
    private static final String LUT_RESOURCE = "/gfl2/render/gf2-original-display.rgba16f";
    private static final String FACE_FORWARD = "a2f224c7144485694aa46c3c3b302b9e22e401b29eae813ee19e8ea14c740a8a";
    private static final List<String> STAGES = List.of(
            "GFCharForward", "GFHairShadow", "GFCharFaceShadow", "GFCharHairTransE",
            "GFOutline", "GFCharTransMrtWrtie", "GFCharTransBack", "GFCharTransFront", "GFCharTransOutline"
    );
    private static final Pattern QUEUE_PATTERN = Pattern.compile("([A-Za-z]+)([+-]\\d+)?");

    private static final String BLIT_VS = """
            #version 430
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String BLIT_FS = """
            #version 430
            in vec2 vUv;
            uniform sampler2D uStudioHdr;
            uniform sampler2D uStudioLut;
            uniform float uStudioExposure;
            out vec4 fragColor;
            vec3 evalLut(vec3 rgb) {
                vec3 p = clamp(log2(max(rgb, vec3(0.0)) * 5.555556 + 0.047996) * 0.0735 + 0.386036, 0.0, 1.0) * 31.0;
                float layer = floor(p.b);
                vec2 uv = (vec2(layer * 32.0 + p.r, p.g) + 0.5) / vec2(1024.0, 32.0);
                vec3 a = textureLod(uStudioLut, uv, 0.0).rgb;
                vec3 b = textureLod(uStudioLut, uv + vec2(1.0 / 32.0, 0.0), 0.0).rgb;
                return mix(a, b, p.b - layer);
            }
            vec3 linearToSrgb(vec3 c) {
                c = clamp(c, 0.0, 1.0);
                vec3 lo = c * 12.92;
                vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
                return mix(hi, lo, lessThanEqual(c, vec3(0.0031308)));
            }
            void main() {
                vec4 hdr = texture(uStudioHdr, vUv);
                float coverage = clamp(1.0 - hdr.a, 0.0, 1.0);
                if (coverage <= 0.002) discard;
                vec3 linearRgb = max(hdr.rgb / coverage, vec3(0.0)) * uStudioExposure;
                vec3 mapped = evalLut(linearRgb);
                fragColor = vec4(linearToSrgb(mapped), coverage);
            }
            """;

    private record ProgramKey(String vertex, String fragment) {}
    private record StateKey(int material, String pass) {}
    private record RegisterKey(String sha, int binding) {}

    private static final class RefTexture {
        final Gfl2OriginalTexture texture;
        int refs;
        RefTexture(Gfl2OriginalTexture texture) { this.texture = texture; }
    }

    private static final class RefBuffer {
        final int buffer;
        int refs;
        RefBuffer(int buffer) { this.buffer = buffer; }
    }

    private static final class PartMesh {
        final int pmxMaterial;
        final int sourceMaterial;
        final boolean keepWhenZeroAlpha;
        final int[] srcVerts;
        final float[] uv2Encoded;
        final int indexCount;
        final int vao, vbo, ibo;
        final ByteBuffer vertexScratch;

        PartMesh(int pmxMaterial, int sourceMaterial, boolean keepWhenZeroAlpha,
                 int[] srcVerts, float[] uv2Encoded, int[] indices) {
            this.pmxMaterial = pmxMaterial;
            this.sourceMaterial = sourceMaterial;
            this.keepWhenZeroAlpha = keepWhenZeroAlpha;
            this.srcVerts = srcVerts;
            this.uv2Encoded = uv2Encoded;
            this.indexCount = indices.length;
            this.vertexScratch = BufferUtils.createByteBuffer(srcVerts.length * 76).order(ByteOrder.nativeOrder());
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            this.ibo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) srcVerts.length * 76L, GL15.GL_DYNAMIC_DRAW);
            int[][] layout = {{0, 3, 0}, {1, 3, 12}, {2, 4, 24}, {3, 4, 40}, {4, 2, 56}, {5, 2, 64}};
            for (int[] a : layout) {
                GL20.glEnableVertexAttribArray(a[0]);
                GL20.glVertexAttribPointer(a[0], a[1], GL11.GL_FLOAT, false, 76, a[2]);
            }
            GL20.glEnableVertexAttribArray(6);
            GL30.glVertexAttribIPointer(6, 1, GL11.GL_INT, 76, 72);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
            ByteBuffer ib = BufferUtils.createByteBuffer(indices.length * 4).order(ByteOrder.nativeOrder());
            ib.asIntBuffer().put(indices);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
            GL30.glBindVertexArray(0);
        }

        void upload(float[] positions, float[] normals, float[] uv) {
            ByteBuffer buf = vertexScratch;
            buf.clear();
            int count = srcVerts.length;
            for (int i = 0; i < count; i++) {
                int sv = srcVerts[i];
                int p = sv * 3;
                int t = sv * 2;
                int u2 = i * 2;
                buf.putFloat(positions[p]).putFloat(positions[p + 1]).putFloat(positions[p + 2]);
                buf.putFloat(normals[p]).putFloat(normals[p + 1]).putFloat(normals[p + 2]);
                buf.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(-1e20f);
                buf.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
                buf.putFloat(uv[t]).putFloat(uv[t + 1]);
                buf.putFloat(uv2Encoded[u2]).putFloat(uv2Encoded[u2 + 1]);
                buf.putInt(0);
            }
            buf.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        }

        void close() {
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
            GL15.glDeleteBuffers(ibo);
        }
    }

    private final class ModelCache {
        final String sha;
        final JSONObject modelJson;
        final Gfl2FrameInputs frameBuilder;
        final int attributeTexture;
        final List<PartMesh> parts;
        final Set<Integer> materialIds = new HashSet<>();
        final Set<String> textureResources = new HashSet<>();
        float[] lastPositionsRef;
        float[] lastNormalsRef;

        ModelCache(String sha, JSONObject modelJson) throws IOException {
            this.sha = sha;
            this.modelJson = modelJson;
            this.frameBuilder = new Gfl2FrameInputs(modelJson, data.manifest.getJSONObject("original_camera_fill"));

            int aw = modelJson.getInt("texture_width"), ah = modelJson.getInt("texture_height");
            byte[] ab = data.bytes(modelJson.getJSONObject("attributes"));
            if ((long) aw * ah * 16 != ab.length) throw new IOException("Studio attribute texture size mismatch");
            int attTex = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(attTex, 1, GL30.GL_RGBA32F, aw, ah);
            try (Gfl2UnpackScope unpack = new Gfl2UnpackScope()) {
                GL45.glTextureSubImage2D(attTex, 0, 0, 0, aw, ah, GL11.GL_RGBA, GL11.GL_FLOAT, direct(ab));
            }
            GL45.glTextureParameteri(attTex, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(attTex, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            this.attributeTexture = attTex;

            byte[] geomBytes = data.bytes(modelJson.getJSONObject("geometry"));
            ByteBuffer geomBb = ByteBuffer.wrap(geomBytes).order(ByteOrder.LITTLE_ENDIAN);
            JSONArray partsArr = modelJson.getJSONArray("parts");
            List<PartMesh> built = new ArrayList<>(partsArr.length());
            try {
                for (int i = 0; i < partsArr.length(); i++) {
                    JSONObject part = partsArr.getJSONObject(i);
                    int pmxMat = part.getInt("pmx_material");
                    int srcMat = part.getInt("source_material");
                    boolean eye = part.optString("name", "").toLowerCase(Locale.ROOT).contains("eye");
                    int vOff = part.getInt("vertex_offset"), vCnt = part.getInt("vertices");
                    int iOff = part.getInt("index_offset"), iCnt = part.getInt("indices");
                    int[] srcVerts = new int[vCnt];
                    float[] uv2 = new float[vCnt * 2];
                    for (int vi = 0; vi < vCnt; vi++) {
                        int off = vOff + vi * 8;
                        srcVerts[vi] = geomBb.getInt(off);
                        long attIdx = Integer.toUnsignedLong(geomBb.getInt(off + 4));
                        uv2[vi * 2] = (float) (2048.0 + (64.0 + srcMat) * 32.0 + 16.0 + (attIdx % 512L) / 64.0 - 7.5);
                        uv2[vi * 2 + 1] = (float) (16.0 + Math.floor(attIdx / 512.0) / 64.0 - 2.0);
                    }
                    int[] indices = new int[iCnt];
                    for (int ii = 0; ii < iCnt; ii++) indices[ii] = geomBb.getInt(iOff + ii * 4);
                    built.add(new PartMesh(pmxMat, srcMat, eye, srcVerts, uv2, indices));
                    materialIds.add(srcMat);
                }
            } catch (RuntimeException | Error e) {
                GL11.glDeleteTextures(attTex);
                for (PartMesh pm : built) pm.close();
                throw e;
            }
            this.parts = built;
        }

        void release() {
            GL11.glDeleteTextures(attributeTexture);
            for (PartMesh pm : parts) pm.close();
            for (int matId : materialIds) releaseMaterialBuffer(matId);
            for (String res : textureResources) releaseTexture(res);
            materialIds.clear();
            textureResources.clear();
        }
    }

    private final Gfl2PackData data;
    private final Gfl2OriginalSamplers sourceSamplers;
    private final JSONObject[] materials;
    private final float[] surfaceWeights;
    private final Map<Integer, Map<String, JSONObject>> materialPasses = new HashMap<>();
    private final Map<String, JSONObject> programMetadata = new HashMap<>();
    private final Map<String, Map<String, String>> variants = new HashMap<>();
    private final Map<ProgramKey, Gfl2OriginalProgram> programs = new HashMap<>();
    private final Map<StateKey, JSONObject> states = new HashMap<>();
    private final Map<JSONObject, Integer> queues = new IdentityHashMap<>();
    private final Map<RegisterKey, Gfl2RegisterBank> registerBanks = new HashMap<>();
    private final Map<String, Integer> defaults = new HashMap<>();
    private final Map<String, RefTexture> textures = new HashMap<>();
    private final Map<Integer, RefBuffer> materialBuffers = new HashMap<>();
    private final LinkedHashMap<String, ModelCache> modelCache = new LinkedHashMap<>(4, 0.75f, true);

    private GlProgram blitProgram;
    private int blitVao;
    private int lutTexture;
    private int studioCube;
    private int studioShadow;
    private int studioShadowSampler;
    private int ssboPalettes, ssboDraws, ssboDummy;
    private int fbo, colourTex, normalTex, motionTex, depthStencilTex;
    private int fboWidth, fboHeight;
    private long bankRevision;
    private boolean initialized;
    private boolean closed;
    private volatile boolean trimRequested;
    private volatile long lastRenderNanos;
    private int vboUploads, evictions, idleCleanups;

    Gfl2StudioPreviewRenderer(Gfl2PackData data) {
        this.data = data;
        this.sourceSamplers = new Gfl2OriginalSamplers(data.manifest.getJSONObject("textures"));
        JSONArray src = data.manifest.getJSONArray("materials");
        this.materials = new JSONObject[src.length()];
        this.surfaceWeights = new float[src.length()];
        for (int i = 0; i < src.length(); i++) {
            JSONObject mat = src.getJSONObject(i);
            materials[i] = mat;
            for (Object f : mat.getJSONArray("bindings")) {
                JSONObject field = (JSONObject) f;
                if ("_UseGIFlatten".equals(field.getString("name"))) {
                    surfaceWeights[i] = field.getJSONArray("value").getFloat(0);
                }
            }
        }
        for (Object value : data.manifest.getJSONArray("programs")) {
            JSONObject row = (JSONObject) value;
            JSONArray choices = row.getJSONArray("programs");
            if (choices.length() != 1) continue;
            JSONObject prog = choices.getJSONObject(0);
            String sha = prog.getString("sha256");
            variants.computeIfAbsent(row.getString("material") + "\n" + row.getString("pass"), k -> new HashMap<>())
                    .put(row.getString("stage"), sha);
            programMetadata.put(sha, prog);
        }
    }

    ReadyRoomPreview.StudioRenderer callback() {
        return this::render;
    }

    Runnable releaseCallback() {
        return () -> trimRequested = true;
    }

    void tickMaintenance() {
        if (closed || !initialized) return;
        if (!ReadyRoomPreview.studioPreviewEnabled() && (fbo != 0 || !modelCache.isEmpty())) {
            releaseTransient();
            idleCleanups++;
            trimRequested = false;
            return;
        }
        long now = System.nanoTime();
        if (lastRenderNanos != 0 && (now - lastRenderNanos) > IDLE_RECLAIM_NANOS && (fbo != 0 || !modelCache.isEmpty())) {
            releaseTransient();
            idleCleanups++;
            trimRequested = false;
            return;
        }
        if (trimRequested) {
            trimRequested = false;
            evictToLimit(1);
        }
    }

    private void ensureSharedGl() throws IOException {
        if (initialized) return;
        blitProgram = GlProgram.fromSources("GF2 studio preview blit",
                new GlProgram.Sources(BLIT_VS, null, null, null, BLIT_FS), Map.of(), Map.of(), List.of());
        blitProgram.sampler("uStudioHdr", 0);
        blitProgram.sampler("uStudioLut", 1);
        blitVao = GL30.glGenVertexArrays();

        // Load checksum-verified original GF2 1024x32 RGBA16F display LUT (256 KB).
        byte[] rawLut;
        try (InputStream in = Gfl2StudioPreviewRenderer.class.getResourceAsStream(LUT_RESOURCE)) {
            if (in == null) throw new IOException("GF2 original display LUT missing");
            rawLut = in.readAllBytes();
        }
        if (rawLut.length != 262144) throw new IOException("GF2 display LUT size mismatch");
        if (data.manifest.has("original_display_profile")) {
            String expected = data.manifest.getJSONObject("original_display_profile").optString("sha256", "");
            if (!expected.isEmpty()) {
                try {
                    String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawLut));
                    if (!actual.equals(expected)) throw new IOException("GF2 display LUT checksum mismatch");
                } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
            }
        }
        lutTexture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(lutTexture, 1, GL30.GL_RGBA16F, 1024, 32);
        try (Gfl2UnpackScope unpack = new Gfl2UnpackScope()) {
            GL45.glTextureSubImage2D(lutTexture, 0, 0, 0, 1024, 32, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, direct(rawLut));
        }
        GL45.glTextureParameteri(lutTexture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL45.glTextureParameteri(lutTexture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL45.glTextureParameteri(lutTexture, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL45.glTextureParameteri(lutTexture, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Static 32x32 studio environment cubemap (24 KB VRAM, computed once).
        studioCube = GL45.glCreateTextures(GL13.GL_TEXTURE_CUBE_MAP);
        int cSize = 32;
        GL45.glTextureStorage2D(studioCube, 6, GL30.GL_RGBA16F, cSize, cSize);
        FloatBuffer faceBuf = BufferUtils.createFloatBuffer(cSize * cSize * 4);
        int oldTex = GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, studioCube);
        try (Gfl2UnpackScope unpack = new Gfl2UnpackScope()) {
            for (int face = 0; face < 6; face++) {
                faceBuf.clear();
                for (int y = 0; y < cSize; y++) {
                    float v = (y + 0.5f) / cSize * 2f - 1f;
                    for (int x = 0; x < cSize; x++) {
                        float u = (x + 0.5f) / cSize * 2f - 1f;
                        Vector3f dir = cubeDir(face, u, v).normalize();
                        float upMix = Math.clamp(dir.y * 0.5f + 0.5f, 0f, 1f);
                        float r = 0.15f + 0.18f * upMix;
                        float g = 0.17f + 0.19f * upMix;
                        float b = 0.20f + 0.21f * upMix;
                        // Subtle warm studio softbox reflection
                        float softbox = Math.max(0f, dir.dot(0.45f, 0.62f, 0.64f));
                        float boost = (float) Math.pow(softbox, 10.0) * 0.62f;
                        faceBuf.put(r + boost * 1.05f).put(g + boost * 1.00f).put(b + boost * 0.94f).put(1f);
                    }
                }
                faceBuf.flip();
                GL11.glTexSubImage2D(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, 0, 0, cSize, cSize,
                        GL11.GL_RGBA, GL11.GL_FLOAT, faceBuf);
            }
            GL30.glGenerateMipmap(GL13.GL_TEXTURE_CUBE_MAP);
        } finally {
            GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, oldTex);
        }
        GL45.glTextureParameteri(studioCube, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL45.glTextureParameteri(studioCube, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // 4x4 shadow depth = 1.0 (unoccluded studio light)
        studioShadow = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(studioShadow, 1, GL30.GL_DEPTH_COMPONENT32F, 4, 4);
        FloatBuffer ones = BufferUtils.createFloatBuffer(16);
        for (int i = 0; i < 16; i++) ones.put(1f);
        ones.flip();
        try (Gfl2UnpackScope unpack = new Gfl2UnpackScope()) {
            GL45.glTextureSubImage2D(studioShadow, 0, 0, 0, 4, 4, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, ones);
        }
        studioShadowSampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(studioShadowSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(studioShadowSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(studioShadowSampler, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL33.glSamplerParameteri(studioShadowSampler, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);

        ssboPalettes = GL45.glCreateBuffers();
        ssboDraws = GL45.glCreateBuffers();
        ssboDummy = GL45.glCreateBuffers();
        GL45.glNamedBufferData(ssboDummy, 64L, GL15.GL_STATIC_DRAW);
        initialized = true;
    }

    private static Vector3f cubeDir(int face, float u, float v) {
        return switch (face) {
            case 0 -> new Vector3f(1f, -v, -u);
            case 1 -> new Vector3f(-1f, -v, u);
            case 2 -> new Vector3f(u, 1f, v);
            case 3 -> new Vector3f(u, -1f, -v);
            case 4 -> new Vector3f(u, -v, 1f);
            default -> new Vector3f(-u, -v, -1f);
        };
    }

    private void ensureFbo(int w, int h) {
        int tw = Math.min(w, MAX_FBO_DIM);
        int th = Math.min(h, MAX_FBO_DIM);
        if (w > MAX_FBO_DIM || h > MAX_FBO_DIM) {
            float scale = Math.min((float) MAX_FBO_DIM / w, (float) MAX_FBO_DIM / h);
            tw = Math.max(16, Math.round(w * scale));
            th = Math.max(16, Math.round(h * scale));
        }
        if (fbo != 0 && fboWidth == tw && fboHeight == th) return;
        deleteFbo();
        fboWidth = tw;
        fboHeight = th;
        colourTex = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(colourTex, 1, GL30.GL_RGBA16F, tw, th);
        GL45.glTextureParameteri(colourTex, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL45.glTextureParameteri(colourTex, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        normalTex = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(normalTex, 1, GL11.GL_RGBA8, tw, th);
        GL45.glTextureParameteri(normalTex, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(normalTex, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        motionTex = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(motionTex, 1, GL11.GL_RGBA8, tw, th);
        GL45.glTextureParameteri(motionTex, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(motionTex, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        depthStencilTex = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(depthStencilTex, 1, GL30.GL_DEPTH24_STENCIL8, tw, th);
        GL45.glTextureParameteri(depthStencilTex, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(depthStencilTex, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colourTex, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, normalTex, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT2, GL11.GL_TEXTURE_2D, motionTex, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, depthStencilTex, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            deleteFbo();
            throw new IllegalStateException("GF2 studio FBO incomplete");
        }
    }

    private void deleteFbo() {
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (colourTex != 0) GL11.glDeleteTextures(colourTex);
        if (normalTex != 0) GL11.glDeleteTextures(normalTex);
        if (motionTex != 0) GL11.glDeleteTextures(motionTex);
        if (depthStencilTex != 0) GL11.glDeleteTextures(depthStencilTex);
        fbo = colourTex = normalTex = motionTex = depthStencilTex = 0;
        fboWidth = fboHeight = 0;
    }

    private void evictToLimit(int limit) {
        while (modelCache.size() > limit) {
            var it = modelCache.entrySet().iterator();
            ModelCache evicted = it.next().getValue();
            it.remove();
            evicted.release();
            evictions++;
        }
    }

    void releaseTransient() {
        evictToLimit(0);
        deleteFbo();
    }

    private ModelCache getOrLoadModel(String sha, JSONObject modelJson) throws IOException {
        ModelCache entry = modelCache.get(sha);
        if (entry != null) return entry;
        evictToLimit(MAX_CACHED_MODELS - 1);
        entry = new ModelCache(sha, modelJson);
        modelCache.put(sha, entry);
        return entry;
    }

    private int acquireMaterialBuffer(ModelCache owner, int matId) throws IOException {
        RefBuffer ref = materialBuffers.get(matId);
        if (ref == null) {
            JSONObject mat = materials[matId];
            byte[] raw = data.bytes(mat.getJSONObject("buffer"));
            int buf = GL45.glCreateBuffers();
            GL45.glNamedBufferData(buf, direct(raw), GL15.GL_STATIC_DRAW);
            ref = new RefBuffer(buf);
            materialBuffers.put(matId, ref);
        }
        if (owner.materialIds.add(matId)) ref.refs++;
        else if (ref.refs == 0) ref.refs = 1;
        return ref.buffer;
    }

    private void releaseMaterialBuffer(int matId) {
        RefBuffer ref = materialBuffers.get(matId);
        if (ref == null) return;
        ref.refs--;
        if (ref.refs <= 0) {
            materialBuffers.remove(matId);
            GL15.glDeleteBuffers(ref.buffer);
        }
    }

    private int acquireTexture(ModelCache owner, String resource) throws IOException {
        RefTexture ref = textures.get(resource);
        if (ref == null) {
            JSONObject entry = data.manifest.getJSONObject("textures").getJSONObject(resource);
            Gfl2OriginalTexture tex = new Gfl2OriginalTexture(resource, entry.getJSONObject("metadata"),
                    direct(data.bytes(entry.getJSONObject("payload"))));
            ref = new RefTexture(tex);
            textures.put(resource, ref);
        }
        if (owner.textureResources.add(resource)) ref.refs++;
        return ref.texture.texture;
    }

    private void releaseTexture(String resource) {
        RefTexture ref = textures.get(resource);
        if (ref == null) return;
        ref.refs--;
        if (ref.refs <= 0) {
            textures.remove(resource);
            ref.texture.close();
        }
    }

    private int defaultTexture(String name) {
        Integer existing = defaults.get(name);
        if (existing != null) return existing;
        float[] color = switch (name) {
            case "white" -> new float[]{1, 1, 1, 1};
            case "black" -> new float[]{0, 0, 0, 1};
            case "gray" -> new float[]{0.5f, 0.5f, 0.5f, 1};
            case "bump" -> new float[]{0.5f, 0.5f, 1, 1};
            case "red" -> new float[]{1, 0, 0, 1};
            default -> new float[]{1, 1, 1, 1};
        };
        int id = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        defaults.put(name, id);
        GL45.glTextureStorage2D(id, 1, GL30.GL_RGBA32F, 1, 1);
        try (Gfl2UnpackScope unpack = new Gfl2UnpackScope()) {
            GL45.glTextureSubImage2D(id, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, color);
        }
        GL45.glTextureParameteri(id, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL45.glTextureParameteri(id, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        return id;
    }

    private JSONObject optionalPass(int matId, String passName) {
        return materialPasses.computeIfAbsent(matId, key -> {
            Map<String, JSONObject> map = new HashMap<>();
            for (Object v : materials[key].getJSONArray("passes")) {
                JSONObject p = (JSONObject) v;
                if (!p.getBoolean("disabled_by_material")) map.putIfAbsent(p.getString("name"), p);
            }
            return map;
        }).get(passName);
    }

    private Gfl2OriginalProgram program(int matId, String passName) throws IOException {
        JSONObject mat = materials[matId];
        Map<String, String> pair = variants.get(mat.getString("name") + "\n" + passName);
        if (pair == null) throw new IllegalArgumentException("Studio source variant missing");
        String vsHash = pair.get("progVertex"), fsHash = pair.get("progFragment");
        ProgramKey key = new ProgramKey(vsHash, fsHash);
        Gfl2OriginalProgram existing = programs.get(key);
        if (existing != null) return existing;
        JSONObject glsl = data.manifest.getJSONObject("glsl");
        String vs = new String(data.bytes(glsl.getJSONObject(vsHash)), StandardCharsets.UTF_8);
        String fs = new String(data.bytes(glsl.getJSONObject(fsHash)), StandardCharsets.UTF_8);
        if ("GFCharForward".equals(passName)) {
            fs = Gfl2SurfaceControls.adapt(fs, programMetadata.get(fsHash));
            if (FACE_FORWARD.equals(fsHash)) fs = softenFaceMask(fs);
        }
        Gfl2OriginalProgram created = new Gfl2OriginalProgram(vs, fs, vsHash, fsHash);
        programs.put(key, created);
        return created;
    }

    private static String softenFaceMask(String source) {
        String width = "GF2Raw2[17].x";
        String begin = "    u_xlat8.x = (-GF2Raw2[17].x) * 0.5 + u_xlat6.x;";
        String end = "    u_xlat8.x = u_xlat42 * u_xlat6.x;";
        int start = source.indexOf(begin), stop = source.indexOf(end);
        if (start < 0 || stop <= start) return source;
        String transition = source.substring(start, stop);
        return source.substring(0, start) + transition.replace(width, "max(" + width + ", 0.30)")
                + "    u_xlat6.x = mix(1.0, u_xlat6.x, 0.60);\n" + source.substring(stop);
    }

    private JSONObject state(int matId, String passName) {
        return states.computeIfAbsent(new StateKey(matId, passName), k -> {
            JSONObject st = new JSONObject(optionalPass(matId, passName).getJSONObject("state").toString());
            if ("GFCharForward".equals(passName)) st.put("sourceSurfaceWeight", surfaceWeights[matId]);
            return st;
        });
    }

    private int queue(int matId, JSONObject pass) {
        return queues.computeIfAbsent(pass, p -> {
            int override = materials[matId].getInt("render_queue_override");
            if (override >= 0) return override;
            String text = "Geometry";
            for (Object v : p.getJSONObject("state").getJSONObject("m_Tags").getJSONArray("tags")) {
                JSONArray tag = (JSONArray) v;
                if ("QUEUE".equals(tag.getString(0))) text = tag.getString(1);
            }
            Matcher m = QUEUE_PATTERN.matcher(text);
            if (!m.matches()) return 2000;
            int base = switch (m.group(1)) {
                case "Background" -> 1000;
                case "Geometry" -> 2000;
                case "AlphaTest" -> 2450;
                case "Transparent" -> 3000;
                case "Overlay" -> 4000;
                default -> 2000;
            };
            return base + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
        });
    }

    private Map<Integer, int[]> textureBindings(ModelCache owner, Gfl2OriginalProgram program, int matId) throws IOException {
        Map<Integer, int[]> result = new LinkedHashMap<>();
        JSONObject mat = materials[matId];
        for (int registerKey : program.textureRegisters()) {
            boolean vertex = (registerKey & 65536) != 0;
            int register = (registerKey >> 8) & 255;
            String sha = vertex ? program.vertexHash : program.fragmentHash;
            JSONObject original = null;
            for (Object v : programMetadata.get(sha).getJSONArray("textures")) {
                JSONObject entry = (JSONObject) v;
                if (entry.getInt("m_Index") == register) { original = entry; break; }
            }
            if (original == null) throw new IOException("Studio texture register metadata missing");
            String name = original.getString("name");
            int target = GL11.GL_TEXTURE_2D, id, sampler = 0;
            if ("unity_SpecCube0".equals(name)) {
                target = GL13.GL_TEXTURE_CUBE_MAP;
                id = studioCube;
            } else if ("_MainLightShadowmapTexture".equals(name)) {
                id = studioShadow;
                sampler = studioShadowSampler;
            } else {
                JSONObject slot = mat.getJSONObject("texture_bindings").optJSONObject(name);
                if (slot == null) {
                    id = defaultTexture(data.manifest.getJSONObject("shader_property_defaults")
                            .getJSONObject(mat.getString("name")).getJSONObject(name)
                            .getJSONObject("texture").getString("m_DefaultName"));
                } else if (slot.has("resource")) {
                    id = acquireTexture(owner, slot.getString("resource"));
                } else {
                    id = defaultTexture(slot.getJSONObject("unassigned_shader_default").getString("m_DefaultName"));
                }
            }
            if (!"_MainLightShadowmapTexture".equals(name)) {
                sampler = sourceSamplers.sampler(programMetadata.get(sha), mat, registerKey);
            }
            result.put(registerKey, new int[]{target, id, sampler});
        }
        return result;
    }

    private Gfl2RegisterBank registerBank(String sha, int binding) {
        return registerBanks.computeIfAbsent(new RegisterKey(sha, binding), key -> {
            JSONObject layout = null;
            for (Object v : data.manifest.getJSONObject("consumed").getJSONObject(sha).getJSONArray("buffers")) {
                JSONObject candidate = (JSONObject) v;
                if (candidate.getInt("register") == binding) { layout = candidate; break; }
            }
            if (layout == null) throw new IllegalArgumentException("Studio register layout absent");
            return new Gfl2RegisterBank(layout);
        });
    }

    boolean render(
            String pmxSha256,
            float[] positions,
            float[] normals,
            float[] uv,
            float[] pmxMaterials,
            float[] shadingHead,
            float unitScale,
            float cameraX, float cameraY, float cameraZ,
            float dist, float yaw, float pitch, float fovRad, float nearPlane, float farPlane,
            int x, int y, int width, int height, int screenHeight
    ) {
        if (closed || width < 16 || height < 16 || unitScale <= 1e-4f) return false;
        JSONObject modelJson = data.modelBySha(pmxSha256);
        if (modelJson == null) return false;
        int hostDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int hostReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] hostViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, hostViewport);
        int[] hostScissor = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, hostScissor);
        boolean hostScissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try (Gfl2GlScope scope = new Gfl2GlScope(Gfl2GlScope.Phase.STUDIO)) {
            ensureSharedGl();
            ensureFbo(width, height);
            ModelCache model = getOrLoadModel(pmxSha256, modelJson);
            lastRenderNanos = System.nanoTime();

            // Upload per-part VBOs ONLY when vertex pose arrays changed (zero upload while orbiting static preview!).
            if (model.lastPositionsRef != positions || model.lastNormalsRef != normals) {
                for (PartMesh pm : model.parts) {
                    pm.upload(positions, normals, uv);
                }
                model.lastPositionsRef = positions;
                model.lastNormalsRef = normals;
                vboUploads++;
            }

            // Upload palette header SSBO (4-float aligned).
            int palLen = ((shadingHead.length + 3) / 4) * 4;
            FloatBuffer palBuf = BufferUtils.createFloatBuffer(palLen);
            palBuf.put(shadingHead);
            while (palBuf.hasRemaining()) palBuf.put(0f);
            palBuf.flip();
            GL45.glNamedBufferData(ssboPalettes, palBuf, GL15.GL_DYNAMIC_DRAW);

            float s = 1f / unitScale;
            float[] placement = {s, 0, 0, 0, 0, s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1};
            float[] drawRec = new float[68];
            System.arraycopy(placement, 0, drawRec, 0, 16);
            drawRec[16] = 1f; drawRec[17] = 1f; drawRec[18] = 1f; drawRec[19] = 0f;
            drawRec[20] = 1f; drawRec[21] = 1f; drawRec[22] = 1f; drawRec[23] = 1f;
            drawRec[28] = 0f; drawRec[29] = 0f; drawRec[30] = 1f; drawRec[31] = 1f;
            System.arraycopy(placement, 0, drawRec, 36, 16);
            drawRec[63] = 1f;
            GL45.glNamedBufferData(ssboDraws, drawRec, GL15.GL_DYNAMIC_DRAW);

            // Orbit studio camera around character target matching ReadyRoomPreview framing 1:1.
            // In Gfl2Rc2Vertex space Z is negated from PMX, so front is +Z.
            float targetX = cameraX * s;
            float targetY = cameraY * s;
            float targetZ = -cameraZ * s;
            float distM = dist * s;
            Vector3f offset = new Vector3f(0f, 0f, distM);
            // Match ReadyRoomPreview turn = rotateX(pitch) * rotateY(yaw) on model => inverse on camera: rotateY(-yaw) * rotateX(-pitch)
            offset.rotateX(-pitch).rotateY(-yaw);
            float eyeX = targetX + offset.x;
            float eyeY = targetY + offset.y;
            float eyeZ = targetZ + offset.z;
            float[] eyeArr = {eyeX, eyeY, eyeZ};

            Matrix4f viewM = new Matrix4f().lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0f, 1f, 0f);
            float aspect = (float) width / (float) height;
            Matrix4f projM = new Matrix4f().perspective(fovRad, aspect, Math.max(0.02f, nearPlane * s), Math.max(5f, farPlane * s));
            Matrix4f rotM = new Matrix4f(viewM).setTranslation(0f, 0f, 0f).scale(-1f, 1f, -1f);
            Matrix4f invRotM = new Matrix4f(rotM).invert();

            // Key light attached to camera front-right-upper so character stays beautifully lit at any orbit angle!
            Vector3f camRight = new Vector3f(viewM.m00(), viewM.m10(), viewM.m20()).normalize();
            Vector3f camUp = new Vector3f(viewM.m01(), viewM.m11(), viewM.m21()).normalize();
            Vector3f camBack = new Vector3f(viewM.m02(), viewM.m12(), viewM.m22()).normalize();
            Vector3f lightWorld = new Vector3f()
                    .fma(0.38f, camRight)
                    .fma(0.62f, camUp)
                    .fma(0.68f, camBack)
                    .normalize();
            // Native->Source lightDir flips X and Z (same as Gfl2NativeSourceSpace).
            float[] lightDir = {-lightWorld.x, lightWorld.y, -lightWorld.z};

            Map<String, float[]> nativeVals = new LinkedHashMap<>();
            nativeVals.put("gbufferModelView", rotM.get(new float[16]));
            nativeVals.put("gbufferModelViewInverse", invRotM.get(new float[16]));
            nativeVals.put("gbufferProjection", projM.get(new float[16]));
            nativeVals.put("gbufferPreviousModelView", rotM.get(new float[16]));
            nativeVals.put("gbufferPreviousProjection", projM.get(new float[16]));
            nativeVals.put("lightDir", lightDir);
            nativeVals.put("frameTimeCounter", new float[]{1f});

            var packet = model.frameBuilder.build(nativeVals, placement, eyeArr, placement, eyeArr,
                    fboWidth, fboHeight, fboWidth, fboHeight, 1.0, false, new float[0], new float[0], new float[0]);
            var displaySettings = Gfl2NativePackSelection.displaySettings();
            float sc = Math.clamp(displaySettings == null ? 1f : displaySettings.shadowContrast(), 0f, 2f);
            float ambScale = sc >= 1f ? 1f / (1f + 0.85f * (sc - 1f)) : 1f + 0.55f * (1f - sc);
            float mainScale = sc >= 1f ? 1f + 0.35f * (sc - 1f) : 1f - 0.35f * (1f - sc);
            Map<String, float[]> inputs = new LinkedHashMap<>(packet.values());
            inputs.put("_MainLightColor", new float[]{0.88f * mainScale, 0.86f * mainScale, 0.84f * mainScale, 1.0f});
            inputs.put("unity_SHAr", new float[]{0.0f, 0.04f * ambScale, 0.0f, 0.30f * ambScale});
            inputs.put("unity_SHAg", new float[]{0.0f, 0.04f * ambScale, 0.0f, 0.32f * ambScale});
            inputs.put("unity_SHAb", new float[]{0.0f, 0.05f * ambScale, 0.0f, 0.36f * ambScale});
            inputs.put("unity_SpecCube0_HDR", new float[]{1f, 1f, 0f, 0f});
            inputs.put("_MainLightWorldToShadow", new float[80]);
            inputs.put("_MainLightShadowParams", new float[]{0f, 1f, 0f, 0f});
            inputs.put("_MainLightShadowmapSize", new float[]{0.25f, 0.25f, 4f, 4f});
            inputs.put("_CascadeDepthBiasArray", new float[4]);
            inputs.put("_CascadeNormalBiasArray", new float[4]);

            float detail = displaySettings == null ? 0f : displaySettings.surfaceDetail();
            float gloss = displaySettings == null ? 0f : displaySettings.skinGloss();
            float baseOutline = displaySettings == null ? 1f : Math.max(0f, displaySettings.outlineWidth());
            boolean skipOutline = baseOutline <= 1e-4f;
            float effOutline = baseOutline * Gfl2NativeSourceRenderer.outlineDistanceScale(inputs);

            // Render all GF2 stages into our private RGBA16F + D32F/S8 studio FBO.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glViewport(0, 0, fboWidth, fboHeight);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glStencilMask(255);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT2});
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0f, 0f, 0f, 1f});
            GL30.glClearBufferfv(GL11.GL_COLOR, 1, new float[]{0f, 0f, 0f, 0f});
            GL30.glClearBufferfv(GL11.GL_COLOR, 2, new float[]{65504f, 65504f, 0f, 0f});
            GL30.glClearBufferfi(GL30.GL_DEPTH_STENCIL, 0, 1.0f, 0);
            GL11.glFrontFace(GL11.GL_CCW);

            JSONObject rendererFrames = modelJson.getJSONObject("renderer_frames").getJSONObject("materials");
            Map<String, LocalTransform> localCache = new HashMap<>();
            float[] baseInv = inputs.get("unity_WorldToObject");
            float[] baseInvNrm = new Matrix3f(new Matrix4f().set(inputs.get("unity_ObjectToWorld"))).transpose().get(new float[9]);

            for (String stage : STAGES) {
                if (skipOutline && ("GFOutline".equals(stage) || "GFCharTransOutline".equals(stage))) continue;
                List<PartJob> jobs = new ArrayList<>();
                for (int pi = 0; pi < model.parts.size(); pi++) {
                    PartMesh pm = model.parts.get(pi);
                    if (pm.pmxMaterial * 12 + 3 < pmxMaterials.length
                            && pmxMaterials[pm.pmxMaterial * 12 + 3] <= 0f && !pm.keepWhenZeroAlpha) continue;
                    JSONObject pass = optionalPass(pm.sourceMaterial, stage);
                    if (pass == null) continue;
                    jobs.add(new PartJob(pm, pi, queue(pm.sourceMaterial, pass)));
                }
                if (jobs.isEmpty()) continue;
                jobs.sort(Comparator.comparingInt(PartJob::queue).thenComparingInt(PartJob::ordinal));
                for (PartJob job : jobs) {
                    PartMesh pm = job.part;
                    int srcMat = pm.sourceMaterial;
                    String root = rendererFrames.optString(Integer.toString(srcMat), null);
                    LocalTransform loc = root == null ? null : localCache.computeIfAbsent(root, r -> {
                        double[] rot9 = model.frameBuilder.rootRotation(r, shadingHead, 0);
                        Matrix4d obj = new Matrix4d(new Matrix4f().set(inputs.get("unity_ObjectToWorld")))
                                .mul(new Matrix4d().set(new Matrix3d().set(rot9)));
                        Matrix4d inv = new Matrix4d(obj).invert();
                        float[] oArr = new Matrix4f(obj).get(new float[16]);
                        float[] iArr = new Matrix4f(inv).get(new float[16]);
                        float[] nArr = new Matrix3f(new Matrix4f(obj)).transpose().get(new float[9]);
                        Map<String, float[]> vals = new LinkedHashMap<>(inputs);
                        vals.put("unity_ObjectToWorld", oArr);
                        vals.put("unity_WorldToObject", iArr);
                        vals.put("unity_MatrixPreviousM", oArr);
                        return new LocalTransform(vals, iArr, nArr);
                    });
                    Map<String, float[]> regInputs = loc == null ? inputs : loc.values;
                    float[] invArr = loc == null ? baseInv : loc.inverse;
                    float[] invNrm = loc == null ? baseInvNrm : loc.inverseNormal;

                    Gfl2OriginalProgram prog = program(srcMat, stage);
                    GlProgram glp = prog.nativeProgram();
                    glp.use();
                    bindSsbos(glp.id());

                    Map<Integer, Integer> regs = new HashMap<>();
                    for (int slot = 0; slot < 6; slot++) {
                        if (prog.registerBytes(slot) <= 0) continue;
                        String sha = slot < 3 ? prog.fragmentHash : prog.vertexHash;
                        int binding = slot % 3;
                        JSONObject layout = null;
                        for (Object v : data.manifest.getJSONObject("consumed").getJSONObject(sha).getJSONArray("buffers")) {
                            JSONObject b = (JSONObject) v;
                            if (b.getInt("register") == binding) { layout = b; break; }
                        }
                        if (layout != null && "UnityPerMaterial".equals(layout.getString("name"))) {
                            regs.put(slot, acquireMaterialBuffer(model, srcMat));
                        } else {
                            Gfl2RegisterBank bank = registerBank(sha, binding);
                            bank.update(regInputs, ++bankRevision);
                            regs.put(slot, bank.buffer);
                        }
                    }
                    prog.registers(regs);
                    prog.textures(textureBindings(model, prog, srcMat));
                    prog.nativeInputs(invArr, invNrm, modelJson.getInt("attribute_records"));
                    prog.nativeFrame(placement, invArr, eyeArr, new float[2], false);
                    glp.setInt("uShadowPalette", 0);
                    glp.setInt("uIrisEntity", -1);
                    glp.setInt("uIrisUntinted", 0);
                    glp.set("uIrisEye", eyeX, eyeY, eyeZ);
                    if (glp.has("gf2CharacterEnhancement")) {
                        glp.set("gf2CharacterEnhancement", detail, gloss, surfaceWeights[srcMat]);
                    }
                    if (glp.has("gf2CharacterOutlineWidth")) {
                        glp.set("gf2CharacterOutlineWidth", effOutline);
                    }
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + 126);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, model.attributeTexture);
                    GL20.glDrawBuffers(prog.sourceOutputs());
                    Gfl2OriginalProgram.nativeColourState(state(srcMat, stage));
                    GL30.glBindVertexArray(pm.vao);
                    GL11.glDrawElements(GL11.GL_TRIANGLES, pm.indexCount, GL11.GL_UNSIGNED_INT, 0L);
                }
            }

            // Composite studio HDR through the official GF2 display LUT onto the UI framebuffer with exact alpha!
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, hostDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, hostReadFbo);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, screenHeight - y - height, width, height);
            GL11.glViewport(x, screenHeight - y - height, width, height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

            blitProgram.use();
            blitProgram.set("uStudioExposure", 1.0f);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colourTex);
            GL33.glBindSampler(0, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lutTexture);
            GL33.glBindSampler(1, 0);
            GL30.glBindVertexArray(blitVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            return true;
        } catch (Throwable failure) {
            System.err.println("[GFL2 StudioPreview] Fallback to plain preview: " + failure);
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, hostDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, hostReadFbo);
            GL11.glViewport(hostViewport[0], hostViewport[1], hostViewport[2], hostViewport[3]);
            GL11.glScissor(hostScissor[0], hostScissor[1], hostScissor[2], hostScissor[3]);
            if (hostScissorOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private void bindSsbos(int programId) {
        bindSsbo(programId, "ModelPalettes", ssboPalettes, 0);
        bindSsbo(programId, "ModelDraws", ssboDraws, 1);
        bindSsbo(programId, "ReliefWalls", ssboDummy, 2);
        bindSsbo(programId, "ReliefFlow", ssboDummy, 3);
    }

    private static void bindSsbo(int programId, String name, int buffer, int slot) {
        int idx = GL43.glGetProgramResourceIndex(programId, GL43.GL_SHADER_STORAGE_BLOCK, name);
        if (idx != GL31.GL_INVALID_INDEX) {
            GL43.glShaderStorageBlockBinding(programId, idx, slot);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, slot, buffer);
        }
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer bb = BufferUtils.createByteBuffer(bytes.length);
        bb.put(bytes).flip();
        return bb;
    }

    private record PartJob(PartMesh part, int ordinal, int queue) {}
    private record LocalTransform(Map<String, float[]> values, float[] inverse, float[] inverseNormal) {}

    JSONObject stats() {
        long fboBytes = fbo == 0 ? 0L : (long) fboWidth * fboHeight * (8L + 4L + 4L + 4L);
        return new JSONObject()
                .put("cached_models", modelCache.size())
                .put("live_textures", textures.size())
                .put("live_material_buffers", materialBuffers.size())
                .put("vbo_uploads", vboUploads)
                .put("evictions", evictions)
                .put("idle_cleanups", idleCleanups)
                .put("fbo_bytes", fboBytes);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        releaseTransient();
        for (Gfl2RegisterBank bank : registerBanks.values()) bank.close();
        registerBanks.clear();
        for (Gfl2OriginalProgram prog : programs.values()) prog.close();
        programs.clear();
        for (int tex : defaults.values()) GL11.glDeleteTextures(tex);
        defaults.clear();
        sourceSamplers.close();
        if (blitProgram != null) { blitProgram.delete(); blitProgram = null; }
        if (blitVao != 0) { GL30.glDeleteVertexArrays(blitVao); blitVao = 0; }
        if (lutTexture != 0) { GL11.glDeleteTextures(lutTexture); lutTexture = 0; }
        if (studioCube != 0) { GL11.glDeleteTextures(studioCube); studioCube = 0; }
        if (studioShadow != 0) { GL11.glDeleteTextures(studioShadow); studioShadow = 0; }
        if (studioShadowSampler != 0) { GL33.glDeleteSamplers(studioShadowSampler); studioShadowSampler = 0; }
        if (ssboPalettes != 0) { GL15.glDeleteBuffers(ssboPalettes); ssboPalettes = 0; }
        if (ssboDraws != 0) { GL15.glDeleteBuffers(ssboDraws); ssboDraws = 0; }
        if (ssboDummy != 0) { GL15.glDeleteBuffers(ssboDummy); ssboDummy = 0; }
        initialized = false;
    }
}
