package cn.huohuas001.huhobotPenguin.spigot.inventory;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Fixed-pose, headless Java2D software renderer for a textured Minecraft player model. */
public final class PlayerModelRenderer {
    public static final String CACHE_VERSION = "pv13";
    public static final int WIDTH = 128;
    public static final int HEIGHT = 256;
    static final int SUPERSAMPLE_SCALE = 4;
    private static final long MAX_RASTER_PIXELS = 4_000_000L;

    static final int HEAD_SIZE = 8;
    static final int BODY_WIDTH = 8;
    static final int BODY_HEIGHT = 12;
    static final int BODY_DEPTH = 4;
    static final int CLASSIC_ARM_WIDTH = 4;
    static final int SLIM_ARM_WIDTH = 3;
    static final int ARM_HEIGHT = 12;
    static final int LEG_WIDTH = 4;
    static final int LEG_HEIGHT = 12;
    static final int MODEL_HEIGHT = HEAD_SIZE + BODY_HEIGHT + LEG_HEIGHT;
    static final double HEAD_OUTER_EXPANSION = 0.5;
    static final double BODY_OUTER_EXPANSION = 0.25;
    static final double OUTER_ARMOR_EXPANSION = 0.5;
    static final double INNER_ARMOR_EXPANSION = 0.25;
    static final double ARMOR_GLINT_UV_SCALE = 0.16;
    static final double ARMOR_GLINT_ROTATION = Math.toRadians(10.0);
    static final double ARMOR_GLINT_STRENGTH = 0.75;
    static final int ARMOR_GLINT_MODULATOR = 0xffffff;
    static final double FACE_EDGE_BLEED = 0.6;

    private static final int BASE_SKIN_LAYER = 0;
    private static final int OUTER_SKIN_LAYER = 1;
    private static final int INNER_ARMOR_LAYER = 2;
    private static final int OUTER_ARMOR_LAYER = 3;

    private static final double YAW = Math.toRadians(-22.0);
    private static final double PITCH = Math.toRadians(7.0);
    private static final int HORIZONTAL_PADDING = 10;
    private static final int TOP_PADDING = 14;
    private static final int BOTTOM_PADDING = 22;

    private final int outputWidth;
    private final int outputHeight;

    public PlayerModelRenderer() {
        this(WIDTH, HEIGHT);
    }

    /**
     * Creates a renderer for the final preview rectangle. Rendering at the destination size avoids
     * the former 128x256 -> theme rectangle non-integer nearest-neighbor resample.
     */
    public PlayerModelRenderer(int outputWidth, int outputHeight) {
        if (outputWidth < 1 || outputHeight < 1 || (long) outputWidth * outputHeight > 4_000_000L) {
            throw new IllegalArgumentException("player preview dimensions are invalid or too large");
        }
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    public int getWidth() { return outputWidth; }
    public int getHeight() { return outputHeight; }

    public BufferedImage render(PlayerSkin playerSkin) {
        return render(playerSkin, ArmorEquipmentSet.empty(), null);
    }

    public BufferedImage render(
        PlayerSkin playerSkin,
        ArmorEquipmentSet equipment,
        EquipmentAssetResolver equipmentAssets
    ) {
        BufferedImage texture = standardCanvas(playerSkin.getImage());
        boolean legacy = playerSkin.getImage().getHeight() == 32;
        List<Face> faces = new ArrayList<Face>();
        boolean helmetEquipped = equipped(equipment, ArmorSlot.HEAD);
        boolean chestEquipped = equipped(equipment, ArmorSlot.CHEST);
        boolean leggingsEquipped = equipped(equipment, ArmorSlot.LEGS);
        boolean bootsEquipped = equipped(equipment, ArmorSlot.FEET);

        addCuboid(faces, texture, -4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8, 0.0, false);
        addCuboid(faces, texture, -4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, 0.0, false);
        addCuboid(faces, texture, -4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, 0.0, false);
        addCuboid(faces, texture, 0, 0, -2, 4, 12, 2,
            legacy ? 0 : 16, legacy ? 16 : 48, 4, 12, 4, 0.0, false);

        int armWidth = playerSkin.getSlim() ? SLIM_ARM_WIDTH : CLASSIC_ARM_WIDTH;
        addCuboid(faces, texture, -4 - armWidth, 12, -2, -4, 24, 2,
            40, 16, armWidth, 12, 4, 0.0, false);
        addCuboid(faces, texture, 4, 12, -2, 4 + armWidth, 24, 2,
            legacy ? 40 : 32, legacy ? 16 : 48, armWidth, 12, 4, 0.0, false);

        if (!helmetEquipped) {
            addCuboid(faces, texture, -4, 24, -4, 4, 32, 4, 32, 0, 8, 8, 8,
                HEAD_OUTER_EXPANSION, true);
        }
        if (!legacy) {
            if (!chestEquipped && !leggingsEquipped) {
                addCuboid(faces, texture, -4, 12, -2, 4, 24, 2, 16, 32, 8, 12, 4,
                    BODY_OUTER_EXPANSION, true);
            }
            if (!leggingsEquipped && !bootsEquipped) {
                addCuboid(faces, texture, -4, 0, -2, 0, 12, 2, 0, 32, 4, 12, 4,
                    BODY_OUTER_EXPANSION, true);
                addCuboid(faces, texture, 0, 0, -2, 4, 12, 2, 0, 48, 4, 12, 4,
                    BODY_OUTER_EXPANSION, true);
            }
            if (!chestEquipped) {
                addCuboid(faces, texture, -4 - armWidth, 12, -2, -4, 24, 2,
                    40, 32, armWidth, 12, 4, BODY_OUTER_EXPANSION, true);
                addCuboid(faces, texture, 4, 12, -2, 4 + armWidth, 24, 2,
                    48, 48, armWidth, 12, 4, BODY_OUTER_EXPANSION, true);
            }
        }

        if (equipment != null && equipmentAssets != null && !equipment.isEmpty()) {
            addArmor(faces, equipment, equipmentAssets, playerSkin.getSlim());
        }

        List<ProjectedFace> visible = new ArrayList<ProjectedFace>();
        for (Face face : faces) {
            if (!hasVisiblePixel(face.texture, face.uv)) continue;
            ProjectedFace projected = project(face);
            if (projected != null) visible.add(projected);
        }
        Collections.sort(visible, new Comparator<ProjectedFace>() {
            @Override public int compare(ProjectedFace first, ProjectedFace second) {
                return comparePaintOrder(
                    first.depth, first.face.renderLayer,
                    second.depth, second.face.renderLayer
                );
            }
        });
        int supersample = supersampleScale(outputWidth, outputHeight);
        BufferedImage rasterized = rasterize(
            visible,
            outputWidth * supersample,
            outputHeight * supersample,
            supersample
        );
        return supersample == 1
            ? rasterized
            : downsample(rasterized, outputWidth, outputHeight);
    }

    private static boolean equipped(ArmorEquipmentSet equipment, ArmorSlot slot) {
        return equipment != null && equipment.get(slot) != null;
    }

    static int comparePaintOrder(
        double firstDepth,
        int firstLayer,
        double secondDepth,
        int secondLayer
    ) {
        int depth = Double.compare(firstDepth, secondDepth);
        return depth != 0 ? depth : Integer.compare(firstLayer, secondLayer);
    }

    private static BufferedImage rasterize(
        List<ProjectedFace> source,
        int width,
        int height,
        int pixelScale
    ) {
        FitResult fit = fit(source, width, height);
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0, 0, 0, 82));
            double outputScale = (double) height / HEIGHT;
            int shadowWidth = Math.max(1, (int) Math.round(84 * outputScale));
            int shadowHeight = Math.max(1, (int) Math.round(11 * outputScale));
            int shadowX = (width - shadowWidth) / 2;
            int shadowY = Math.min(
                height - Math.max(1, (int) Math.round(12 * outputScale)),
                (int) Math.round(fit.bottom + 2 * outputScale)
            );
            graphics.fillOval(shadowX, shadowY, shadowWidth, shadowHeight);
            for (ProjectedFace face : fit.faces) {
                drawFace(graphics, face, FACE_EDGE_BLEED * pixelScale);
            }
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage downsample(BufferedImage source, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(
                RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY
            );
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    static int supersampleScale(int width, int height) {
        long pixels = (long) width * height;
        for (int scale = SUPERSAMPLE_SCALE; scale >= 2; scale--) {
            if (pixels * scale * scale <= MAX_RASTER_PIXELS) return scale;
        }
        return 1;
    }

    private static void addArmor(
        List<Face> faces,
        ArmorEquipmentSet equipment,
        EquipmentAssetResolver assets,
        boolean slim
    ) {
        ArmorVisualDescriptor head = equipment.get(ArmorSlot.HEAD);
        if (head != null) {
            addArmorPart(faces, head, assets, false,
                -4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8, OUTER_ARMOR_EXPANSION, false);
        }

        ArmorVisualDescriptor chest = equipment.get(ArmorSlot.CHEST);
        if (chest != null) {
            int armWidth = slim ? SLIM_ARM_WIDTH : CLASSIC_ARM_WIDTH;
            addArmorPart(faces, chest, assets, false,
                -4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, OUTER_ARMOR_EXPANSION, false);
            addArmorPart(faces, chest, assets, false,
                -4 - armWidth, 12, -2, -4, 24, 2,
                40, 16, 4, 12, 4, OUTER_ARMOR_EXPANSION, false);
            addArmorPart(faces, chest, assets, false,
                4, 12, -2, 4 + armWidth, 24, 2,
                40, 16, 4, 12, 4, OUTER_ARMOR_EXPANSION, true);
        }

        ArmorVisualDescriptor legs = equipment.get(ArmorSlot.LEGS);
        if (legs != null) {
            addArmorPart(faces, legs, assets, true,
                -4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, INNER_ARMOR_EXPANSION, false);
            addArmorPart(faces, legs, assets, true,
                -4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, INNER_ARMOR_EXPANSION, false);
            addArmorPart(faces, legs, assets, true,
                0, 0, -2, 4, 12, 2, 0, 16, 4, 12, 4, INNER_ARMOR_EXPANSION, true);
        }

        ArmorVisualDescriptor feet = equipment.get(ArmorSlot.FEET);
        if (feet != null) {
            addArmorPart(faces, feet, assets, false,
                -4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, OUTER_ARMOR_EXPANSION, false);
            addArmorPart(faces, feet, assets, false,
                0, 0, -2, 4, 12, 2, 0, 16, 4, 12, 4, OUTER_ARMOR_EXPANSION, true);
        }
    }

    private static void addArmorPart(
        List<Face> faces,
        ArmorVisualDescriptor descriptor,
        EquipmentAssetResolver assets,
        boolean leggings,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int u, int v, int width, int height, int depth,
        double expansion,
        boolean mirror
    ) {
        List<BufferedImage> layers = assets.resolveArmorLayers(descriptor, leggings);
        boolean first = true;
        for (BufferedImage texture : layers) {
            Glint glint = first && descriptor.hasGlint()
                ? new Glint(assets.resolveArmorGlintTexture()) : null;
            addArmorCuboid(faces, texture, minX, minY, minZ, maxX, maxY, maxZ,
                u, v, width, height, depth, expansion,
                leggings ? INNER_ARMOR_LAYER : OUTER_ARMOR_LAYER, mirror, glint);
            first = false;
        }
        BufferedImage trim = assets.resolveArmorTrimTexture(descriptor, leggings);
        if (trim != null) addArmorCuboid(faces, trim, minX, minY, minZ, maxX, maxY, maxZ,
            u, v, width, height, depth, expansion,
            leggings ? INNER_ARMOR_LAYER : OUTER_ARMOR_LAYER, mirror, null);
    }

    private static void addArmorCuboid(
        List<Face> faces,
        BufferedImage texture,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int u, int v, int width, int height, int depth,
        double expansion,
        int renderLayer,
        boolean mirror,
        Glint glint
    ) {
        int scaleX = Math.max(1, texture.getWidth() / 64);
        int scaleY = Math.max(1, texture.getHeight() / 32);
        addCuboidScaled(
            faces, texture, minX, minY, minZ, maxX, maxY, maxZ,
            u * scaleX, v * scaleY, width * scaleX, height * scaleY, depth * scaleX,
            expansion, renderLayer, mirror, glint
        );
    }

    private static void addCuboid(
        List<Face> faces,
        BufferedImage texture,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int u, int v, int width, int height, int depth,
        double expansion,
        boolean outer
    ) {
        addCuboidScaled(
            faces, texture, minX, minY, minZ, maxX, maxY, maxZ,
            u, v, width, height, depth, expansion,
            outer ? OUTER_SKIN_LAYER : BASE_SKIN_LAYER, false, null
        );
        if (outer && expansion > 0.0) {
            addOuterPixelWalls(
                faces, texture, minX, minY, minZ, maxX, maxY, maxZ,
                u, v, width, height, depth, expansion
            );
        }
    }

    private static void addCuboidScaled(
        List<Face> faces,
        BufferedImage texture,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int u, int v, int width, int height, int depth,
        double expansion,
        int renderLayer,
        boolean mirror,
        Glint glint
    ) {
        minX -= expansion; minY -= expansion; minZ -= expansion;
        maxX += expansion; maxY += expansion; maxZ += expansion;
        faces.add(new Face(texture, rect(u + depth, v + depth, width, height), renderLayer, mirror, 1.00, glint,
            vertices(minX,maxY,maxZ, maxX,maxY,maxZ, maxX,minY,maxZ, minX,minY,maxZ), 0,0,1));
        faces.add(new Face(texture, rect(u + depth + width + depth, v + depth, width, height), renderLayer, mirror, 0.72, glint,
            vertices(maxX,maxY,minZ, minX,maxY,minZ, minX,minY,minZ, maxX,minY,minZ), 0,0,-1));
        faces.add(new Face(texture, rect(u, v + depth, depth, height), renderLayer, mirror, 0.80, glint,
            vertices(minX,maxY,minZ, minX,maxY,maxZ, minX,minY,maxZ, minX,minY,minZ), -1,0,0));
        faces.add(new Face(texture, rect(u + depth + width, v + depth, depth, height), renderLayer, mirror, 0.86, glint,
            vertices(maxX,maxY,maxZ, maxX,maxY,minZ, maxX,minY,minZ, maxX,minY,maxZ), 1,0,0));
        faces.add(new Face(texture, rect(u + depth, v, width, depth), renderLayer, mirror, 1.00, glint,
            vertices(minX,maxY,minZ, maxX,maxY,minZ, maxX,maxY,maxZ, minX,maxY,maxZ), 0,1,0));
        faces.add(new Face(texture, rect(u + depth + width, v, width, depth), renderLayer, mirror, 0.74, glint,
            vertices(minX,minY,maxZ, maxX,minY,maxZ, maxX,minY,minZ, minX,minY,minZ), 0,-1,0));
    }

    /**
     * Adds the visible walls around opaque second-layer texels. The outer face remains a single
     * nearest-neighbour textured plane, while discontinuities gain real depth instead of looking
     * like a partially erased flat overlay. Only camera-facing wall normals are emitted because
     * the preview uses a fixed +X/+Y/+Z view.
     */
    private static void addOuterPixelWalls(
        List<Face> faces,
        BufferedImage texture,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int u, int v, int width, int height, int depth,
        double expansion
    ) {
        addFrontPixelWalls(faces, texture, minX, minY, maxZ, maxX, maxY,
            rect(u + depth, v + depth, width, height), expansion);
        addRightPixelWalls(faces, texture, maxX, minY, minZ, maxY, maxZ,
            rect(u + depth + width, v + depth, depth, height), expansion);
        addTopPixelWalls(faces, texture, minX, maxY, minZ, maxX, maxZ,
            rect(u + depth, v, width, depth), expansion);
    }

    private static void addFrontPixelWalls(
        List<Face> faces, BufferedImage texture,
        double minX, double minY, double maxZ, double maxX, double maxY,
        Rect uv, double expansion
    ) {
        double left = minX - expansion;
        double top = maxY + expansion;
        double cellWidth = (maxX - minX + expansion * 2.0) / uv.width;
        double cellHeight = (maxY - minY + expansion * 2.0) / uv.height;
        for (int py = 0; py < uv.height; py++) for (int px = 0; px < uv.width; px++) {
            if (!isPresent(texture, uv.x + px, uv.y + py)) continue;
            double x0 = left + px * cellWidth;
            double x1 = x0 + cellWidth;
            double y1 = top - py * cellHeight;
            double y0 = y1 - cellHeight;
            Rect pixel = rect(uv.x + px, uv.y + py, 1, 1);
            if (px + 1 == uv.width || !isPresent(texture, uv.x + px + 1, uv.y + py)) {
                faces.add(new Face(texture, pixel, OUTER_SKIN_LAYER, false, 0.86, null,
                    vertices(x1,y1,maxZ + expansion, x1,y1,maxZ, x1,y0,maxZ, x1,y0,maxZ + expansion),
                    1,0,0));
            }
            if (py == 0 || !isPresent(texture, uv.x + px, uv.y + py - 1)) {
                faces.add(new Face(texture, pixel, OUTER_SKIN_LAYER, false, 1.00, null,
                    vertices(x0,y1,maxZ, x1,y1,maxZ, x1,y1,maxZ + expansion, x0,y1,maxZ + expansion),
                    0,1,0));
            }
        }
    }

    private static void addRightPixelWalls(
        List<Face> faces, BufferedImage texture,
        double maxX, double minY, double minZ, double maxY, double maxZ,
        Rect uv, double expansion
    ) {
        double front = maxZ + expansion;
        double top = maxY + expansion;
        double cellDepth = (maxZ - minZ + expansion * 2.0) / uv.width;
        double cellHeight = (maxY - minY + expansion * 2.0) / uv.height;
        for (int py = 0; py < uv.height; py++) for (int px = 0; px < uv.width; px++) {
            if (!isPresent(texture, uv.x + px, uv.y + py)) continue;
            double z1 = front - px * cellDepth;
            double z0 = z1 - cellDepth;
            double y1 = top - py * cellHeight;
            double y0 = y1 - cellHeight;
            Rect pixel = rect(uv.x + px, uv.y + py, 1, 1);
            if (px == 0 || !isPresent(texture, uv.x + px - 1, uv.y + py)) {
                faces.add(new Face(texture, pixel, OUTER_SKIN_LAYER, false, 1.00, null,
                    vertices(maxX,y1,z1, maxX + expansion,y1,z1,
                        maxX + expansion,y0,z1, maxX,y0,z1), 0,0,1));
            }
            if (py == 0 || !isPresent(texture, uv.x + px, uv.y + py - 1)) {
                faces.add(new Face(texture, pixel, OUTER_SKIN_LAYER, false, 1.00, null,
                    vertices(maxX,y1,z0, maxX + expansion,y1,z0,
                        maxX + expansion,y1,z1, maxX,y1,z1), 0,1,0));
            }
        }
    }

    private static void addTopPixelWalls(
        List<Face> faces, BufferedImage texture,
        double minX, double maxY, double minZ, double maxX, double maxZ,
        Rect uv, double expansion
    ) {
        double left = minX - expansion;
        double back = minZ - expansion;
        double cellWidth = (maxX - minX + expansion * 2.0) / uv.width;
        double cellDepth = (maxZ - minZ + expansion * 2.0) / uv.height;
        for (int py = 0; py < uv.height; py++) for (int px = 0; px < uv.width; px++) {
            if (!isPresent(texture, uv.x + px, uv.y + py)) continue;
            double x0 = left + px * cellWidth;
            double x1 = x0 + cellWidth;
            double z0 = back + py * cellDepth;
            double z1 = z0 + cellDepth;
            Rect pixel = rect(uv.x + px, uv.y + py, 1, 1);
            if (px + 1 == uv.width || !isPresent(texture, uv.x + px + 1, uv.y + py)) {
                faces.add(new Face(texture, pixel, OUTER_SKIN_LAYER, false, 0.86, null,
                    vertices(x1,maxY + expansion,z1, x1,maxY + expansion,z0,
                        x1,maxY,z0, x1,maxY,z1), 1,0,0));
            }
            if (py + 1 == uv.height || !isPresent(texture, uv.x + px, uv.y + py + 1)) {
                faces.add(new Face(texture, pixel, OUTER_SKIN_LAYER, false, 1.00, null,
                    vertices(x0,maxY + expansion,z1, x1,maxY + expansion,z1,
                        x1,maxY,z1, x0,maxY,z1), 0,0,1));
            }
        }
    }

    private static boolean isPresent(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) != 0;
    }

    private static ProjectedFace project(Face face) {
        Vec3 normal = rotate(face.normal);
        if (normal.z <= 0.01) return null;
        Point2[] points = new Point2[4];
        double depth = 0.0;
        for (int index = 0; index < 4; index++) {
            Vec3 value = rotate(face.vertices[index]);
            points[index] = new Point2(value.x, -value.y);
            depth += value.z;
        }
        return new ProjectedFace(face, points, depth / 4.0);
    }

    private static FitResult fit(List<ProjectedFace> faces, int width, int height) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (ProjectedFace face : faces) {
            if (face.face.renderLayer != BASE_SKIN_LAYER) continue;
            for (Point2 point : face.points) {
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
            }
        }
        double outputScale = (double) height / HEIGHT;
        double horizontalPadding = HORIZONTAL_PADDING * outputScale;
        double topPadding = TOP_PADDING * outputScale;
        double bottomPadding = BOTTOM_PADDING * outputScale;
        if (faces.isEmpty() || maxX <= minX || maxY <= minY) {
            return new FitResult(faces, height - bottomPadding);
        }
        double scale = Math.min(
            (width - horizontalPadding * 2.0) / (maxX - minX),
            (height - topPadding - bottomPadding) / (maxY - minY)
        );
        double offsetX = (width - (maxX - minX) * scale) / 2.0 - minX * scale;
        double offsetY = topPadding - minY * scale;
        List<ProjectedFace> fitted = new ArrayList<ProjectedFace>();
        for (ProjectedFace face : faces) {
            Point2[] points = new Point2[face.points.length];
            for (int index = 0; index < points.length; index++) {
                points[index] = new Point2(
                    offsetX + face.points[index].x * scale,
                    offsetY + face.points[index].y * scale
                );
            }
            fitted.add(new ProjectedFace(face.face, points, face.depth));
        }
        return new FitResult(fitted, offsetY + maxY * scale);
    }

    private static Vec3 rotate(Vec3 value) {
        double yawX = value.x * Math.cos(YAW) + value.z * Math.sin(YAW);
        double yawZ = -value.x * Math.sin(YAW) + value.z * Math.cos(YAW);
        double pitchY = value.y * Math.cos(PITCH) - yawZ * Math.sin(PITCH);
        double pitchZ = value.y * Math.sin(PITCH) + yawZ * Math.cos(PITCH);
        return new Vec3(yawX, pitchY, pitchZ);
    }

    private static void drawFace(
        Graphics2D destination,
        ProjectedFace projected,
        double edgeBleed
    ) {
        Face face = projected.face;
        BufferedImage pixels = shadedRegion(face.texture, face.uv, face.shade, face.mirror, face.glint);
        if (!hasVisiblePixel(pixels)) return;
        Point2[] points = bleed(projected.points, edgeBleed);
        Graphics2D graphics = (Graphics2D) destination.create();
        try {
            int[] xs = new int[4];
            int[] ys = new int[4];
            for (int index = 0; index < 4; index++) {
                xs[index] = (int) Math.round(points[index].x);
                ys[index] = (int) Math.round(points[index].y);
            }
            graphics.setClip(new Polygon(xs, ys, 4));
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            Point2 topLeft = points[0];
            Point2 topRight = points[1];
            Point2 bottomLeft = points[3];
            AffineTransform transform = new AffineTransform(
                (topRight.x - topLeft.x) / pixels.getWidth(),
                (topRight.y - topLeft.y) / pixels.getWidth(),
                (bottomLeft.x - topLeft.x) / pixels.getHeight(),
                (bottomLeft.y - topLeft.y) / pixels.getHeight(),
                topLeft.x,
                topLeft.y
            );
            graphics.drawImage(pixels, transform, null);
        } finally {
            graphics.dispose();
        }
    }

    private static Point2[] bleed(Point2[] source, double amount) {
        double centerX = 0.0;
        double centerY = 0.0;
        for (Point2 point : source) {
            centerX += point.x;
            centerY += point.y;
        }
        centerX /= source.length;
        centerY /= source.length;
        Point2[] result = new Point2[source.length];
        for (int index = 0; index < source.length; index++) {
            double dx = source[index].x - centerX;
            double dy = source[index].y - centerY;
            double length = Math.sqrt(dx * dx + dy * dy);
            result[index] = length == 0.0 ? source[index] : new Point2(
                source[index].x + dx / length * amount,
                source[index].y + dy / length * amount
            );
        }
        return result;
    }

    private static BufferedImage shadedRegion(
        BufferedImage source, Rect uv, double shade, boolean mirror, Glint glint
    ) {
        BufferedImage result = new BufferedImage(uv.width, uv.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < uv.height; y++) {
            for (int x = 0; x < uv.width; x++) {
                int sourceX = mirror ? uv.x + uv.width - 1 - x : uv.x + x;
                int argb = source.getRGB(sourceX, uv.y + y);
                int alpha = argb >>> 24;
                int red = Math.min(255, (int) Math.round(((argb >>> 16) & 0xff) * shade));
                int green = Math.min(255, (int) Math.round(((argb >>> 8) & 0xff) * shade));
                int blue = Math.min(255, (int) Math.round((argb & 0xff) * shade));
                int shaded = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, glint == null || alpha == 0 ? shaded : glint.blend(
                    shaded,
                    (sourceX + 0.5) / source.getWidth(),
                    (uv.y + y + 0.5) / source.getHeight()
                ));
            }
        }
        return result;
    }

    private static boolean hasVisiblePixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        }
        return false;
    }

    private static boolean hasVisiblePixel(BufferedImage image, Rect area) {
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    private static BufferedImage standardCanvas(BufferedImage input) {
        if (input.getHeight() == 64) return input;
        BufferedImage expanded = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = expanded.createGraphics();
        try { graphics.drawImage(input, 0, 0, null); }
        finally { graphics.dispose(); }
        return expanded;
    }

    private static Rect rect(int x, int y, int width, int height) { return new Rect(x,y,width,height); }
    private static Vec3[] vertices(double... values) {
        Vec3[] result = new Vec3[4];
        for (int index = 0; index < 4; index++) {
            result[index] = new Vec3(values[index * 3], values[index * 3 + 1], values[index * 3 + 2]);
        }
        return result;
    }

    private static final class Face {
        private final BufferedImage texture; private final Rect uv; private final int renderLayer; private final boolean mirror;
        private final double shade; private final Glint glint; private final Vec3[] vertices; private final Vec3 normal;
        private Face(BufferedImage texture, Rect uv, int renderLayer, boolean mirror, double shade, Glint glint, Vec3[] vertices,
                     double nx, double ny, double nz) {
            this.texture=texture; this.uv=uv; this.renderLayer=renderLayer; this.mirror=mirror; this.shade=shade;
            this.glint=glint; this.vertices=vertices; this.normal=new Vec3(nx,ny,nz);
        }
    }
    private static final class Glint {
        private final BufferedImage texture;
        private final double phaseU;
        private final double phaseV;

        private Glint(BufferedImage texture) {
            this.texture = texture;
            // Fixed client-equivalent timestamp: 242 seconds on the 110s/30s armor glint cycles.
            // It crosses a readable highlight while keeping repeated QQ PNGs byte-stable.
            this.phaseU = 0.2;
            this.phaseV = 1.0 / 15.0;
        }

        private int blend(int destination, double u, double v) {
            double scaledU = u * ARMOR_GLINT_UV_SCALE;
            double scaledV = v * ARMOR_GLINT_UV_SCALE;
            double cos = Math.cos(ARMOR_GLINT_ROTATION);
            double sin = Math.sin(ARMOR_GLINT_ROTATION);
            double transformedU = scaledU * cos - scaledV * sin - phaseU;
            double transformedV = scaledU * sin + scaledV * cos + phaseV;
            int sample = bilinearWrapped(texture, transformedU, transformedV);
            if ((sample >>> 24) < 26) return destination;

            // The equipment dye color belongs to the leather base layer only. The client issues the
            // armor-glint render pass with a white vertex color, so dyed/undyed leather must not
            // attenuate or recolor the independent purple glint texture.
            int red = additive(destination >>> 16, sample >>> 16, ARMOR_GLINT_MODULATOR >>> 16);
            int green = additive(destination >>> 8, sample >>> 8, ARMOR_GLINT_MODULATOR >>> 8);
            int blue = additive(destination, sample, ARMOR_GLINT_MODULATOR);
            return (destination & 0xff000000) | (red << 16) | (green << 8) | blue;
        }

        private static int additive(int destination, int source, int color) {
            double glint = (source & 0xff) / 255.0 * ((color & 0xff) / 255.0) * ARMOR_GLINT_STRENGTH;
            return Math.min(255, (destination & 0xff) + (int) Math.round(glint * glint * 255.0));
        }
    }

    private static int bilinearWrapped(BufferedImage image, double u, double v) {
        double x = fractional(u) * image.getWidth() - 0.5;
        double y = fractional(v) * image.getHeight() - 0.5;
        int x0 = floorMod((int) Math.floor(x), image.getWidth());
        int y0 = floorMod((int) Math.floor(y), image.getHeight());
        int x1 = (x0 + 1) % image.getWidth();
        int y1 = (y0 + 1) % image.getHeight();
        double tx = x - Math.floor(x);
        double ty = y - Math.floor(y);
        int top = interpolate(image.getRGB(x0, y0), image.getRGB(x1, y0), tx);
        int bottom = interpolate(image.getRGB(x0, y1), image.getRGB(x1, y1), tx);
        return interpolate(top, bottom, ty);
    }

    private static int interpolate(int first, int second, double amount) {
        int alpha = channel(first >>> 24, second >>> 24, amount);
        int red = channel(first >>> 16, second >>> 16, amount);
        int green = channel(first >>> 8, second >>> 8, amount);
        int blue = channel(first, second, amount);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int channel(int first, int second, double amount) {
        return (int) Math.round((first & 0xff) * (1.0 - amount) + (second & 0xff) * amount);
    }

    private static double fractional(double value) { return value - Math.floor(value); }
    private static int floorMod(int value, int modulus) {
        int result = value % modulus;
        return result < 0 ? result + modulus : result;
    }
    private static final class ProjectedFace {
        private final Face face; private final Point2[] points; private final double depth;
        private ProjectedFace(Face face, Point2[] points, double depth) { this.face=face;this.points=points;this.depth=depth; }
    }
    private static final class FitResult {
        private final List<ProjectedFace> faces; private final double bottom;
        private FitResult(List<ProjectedFace> faces, double bottom) { this.faces=faces;this.bottom=bottom; }
    }
    private static final class Vec3 {
        private final double x,y,z; private Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    }
    private static final class Point2 {
        private final double x,y; private Point2(double x,double y){this.x=x;this.y=y;}
    }
    private static final class Rect {
        private final int x,y,width,height; private Rect(int x,int y,int width,int height){this.x=x;this.y=y;this.width=width;this.height=height;}
    }
}
