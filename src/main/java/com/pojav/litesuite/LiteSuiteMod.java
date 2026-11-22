package com.pojav.litesuite;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.SpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class LiteSuiteMod implements ClientModInitializer {

    public enum OptLevel {
        NORMAL("Binh thuong"),
        HIGH("Cao"),
        ULTRA("Sieu cao");

        public final String label;
        OptLevel(String label) { this.label = label; }

        public OptLevel next() {
            return switch (this) {
                case NORMAL -> HIGH;
                case HIGH -> ULTRA;
                case ULTRA -> NORMAL;
            };
        }
    }

    public static boolean softAim = false;
    public static boolean espPlayer = false;
    public static boolean espBase = false;
    public static boolean showFps = true;
    public static OptLevel optLevel = OptLevel.NORMAL;

    public static double aimRange = 6.5;
    public static float aimSmoothFactor = 0.14f;
    public static float maxStepDeg = 4.5f;

    private static final List<BlockPos> baseTargets = new ArrayList<>();
    private static BlockPos nearestBasePos = null;
    private static int tickCounter = 0;

    public static KeyBinding keyOpenGui;
    public static KeyBinding keySoftAim;
    public static KeyBinding keyEspPlayer;
    public static KeyBinding keyEspBase;
    public static KeyBinding keyOptCycle;

    public static void toggleSoftAim() {
        softAim = !softAim;
        notifyHud("Aim nhe", softAim);
    }

    public static void toggleEspPlayer() {
        espPlayer = !espPlayer;
        notifyHud("ESP Player", espPlayer);
    }

    public static void toggleEspBase() {
        espBase = !espBase;
        if (!espBase) {
            baseTargets.clear();
            nearestBasePos = null;
        } else {
            scanBaseTargets(MinecraftClient.getInstance());
        }
        notifyHud("ESP Base + Day", espBase);
    }

    public static void cycleOptLevel() {
        optLevel = optLevel.next();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[LiteSuite] §fToi uu FPS: §b" + optLevel.label),
                    true
            );
        }
    }

    private static void notifyHud(String name, boolean enabled) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[LiteSuite] §f" + name + ": " + (enabled ? "§aBAT" : "§cTAT")),
                    true
            );
        }
    }

    @Override
    public void onInitializeClient() {
        String cat = "category.litesuite";
        keyOpenGui = registerKey("key.litesuite.gui", GLFW.GLFW_KEY_RIGHT_SHIFT, cat);
        keySoftAim = registerKey("key.litesuite.aim", GLFW.GLFW_KEY_R, cat);
        keyEspPlayer = registerKey("key.litesuite.esp_player", GLFW.GLFW_KEY_H, cat);
        keyEspBase = registerKey("key.litesuite.esp_base", GLFW.GLFW_KEY_B, cat);
        keyOptCycle = registerKey("key.litesuite.opt", GLFW.GLFW_KEY_O, cat);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyOpenGui.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new SpaceMenuScreen());
                }
            }
            while (keySoftAim.wasPressed()) toggleSoftAim();
            while (keyEspPlayer.wasPressed()) toggleEspPlayer();
            while (keyEspBase.wasPressed()) toggleEspBase();
            while (keyOptCycle.wasPressed()) cycleOptLevel();

            if (client.player == null || client.world == null) return;

            tickCounter++;
            int scanInterval = switch (optLevel) {
                case NORMAL -> 15;
                case HIGH -> 30;
                case ULTRA -> 50;
            };

            if (softAim) {
                applySoftAim(client);
            }

            if (espBase && tickCounter % scanInterval == 0) {
                scanBaseTargets(client);
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;

            MatrixStack matrices = context.matrixStack();
            Vec3d camPos = context.camera().getPos();
            VertexConsumer buffer = context.consumers().getBuffer(RenderLayer.getLines());

            matrices.push();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);

            if (espPlayer) {
                int maxPlayers = (optLevel == OptLevel.ULTRA) ? 8 : 24;
                int drawn = 0;
                for (PlayerEntity p : client.world.getPlayers()) {
                    if (p == client.player || !p.isAlive() || p.isSpectator()) continue;
                    if (drawn++ >= maxPlayers) break;
                    Box box = p.getBoundingBox().expand(0.08);
                    WorldRenderer.drawBox(matrices, buffer, box, 1.0f, 0.25f, 0.35f, 1.0f);
                }
            }

            if (espBase && !baseTargets.isEmpty()) {
                int maxBoxes = switch (optLevel) {
                    case NORMAL -> 80;
                    case HIGH -> 40;
                    case ULTRA -> 20;
                };

                Vec3d playerFeet = client.player.getPos().add(0, 0.15, 0);
                int count = 0;
                for (BlockPos pos : baseTargets) {
                    if (count++ >= maxBoxes) break;
                    Box box = new Box(pos);
                    WorldRenderer.drawBox(matrices, buffer, box, 1.0f, 0.72f, 0.0f, 1.0f);
                }

                if (nearestBasePos != null) {
                    Vec3d targetCenter = Vec3d.ofCenter(nearestBasePos);
                    drawLine(matrices, buffer, playerFeet, targetCenter, 0.2f, 0.95f, 1.0f, 1.0f);
                }
            }

            matrices.pop();
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounterObj) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            TextRenderer tr = client.textRenderer;

            int y = 8;
            if (showFps) {
                int fps = client.getCurrentFps();
                String fpsColor = fps >= 50 ? "§a" : (fps >= 30 ? "§e" : "§c");
                drawContext.drawTextWithShadow(tr, Text.literal("FPS: " + fpsColor + fps + " §7[" + optLevel.label + "]"), 8, y, 0xFFFFFF);
                y += 12;
            }

            if (espBase) {
                if (nearestBasePos != null) {
                    double dist = Math.sqrt(client.player.squaredDistanceTo(Vec3d.ofCenter(nearestBasePos)));
                    int dy = nearestBasePos.getY() - client.player.getBlockPos().getY();
                    String dirHint = dy < -2 ? "Dao xuong (" + dy + " block)" : (dy > 2 ? "Len tren (+" + dy + " block)" : "Cung cao do");
                    drawContext.drawTextWithShadow(
                            tr,
                            Text.literal("§6[Day Base] §f" + String.format("%.1fm", dist) + " §7- §b" + dirHint),
                            8,
                            y,
                            0xFFFFFF
                    );
                } else {
                    int maxRadius = getScanRadiusChunks();
                    drawContext.drawTextWithShadow(
                            tr,
                            Text.literal("§7[Day Base] Chua thay trong " + maxRadius + " chunk (thu di chuyen/dao sau them)"),
                            8,
                            y,
                            0xFFFFFF
                    );
                }
            }
        });
    }

    private static int getScanRadiusChunks() {
        return switch (optLevel) {
            case NORMAL -> 5;
            case HIGH -> 4;
            case ULTRA -> 3;
        };
    }

    private KeyBinding registerKey(String key, int glfwKey, String category) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(key, InputUtil.Type.KEYSYM, glfwKey, category));
    }

    private void applySoftAim(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        List<LivingEntity> candidates = client.world.getEntitiesByClass(
                LivingEntity.class,
                client.player.getBoundingBox().expand(aimRange),
                e -> e != client.player && e.isAlive() && !e.isSpectator()
        );

        LivingEntity target = candidates.stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(client.player)))
                .orElse(null);

        if (target == null) return;

        Vec3d eye = client.player.getEyePos();
        Vec3d aimPoint = target.getPos().add(0, target.getHeight() * 0.65, 0);

        double dx = aimPoint.x - eye.x;
        double dy = aimPoint.y - eye.y;
        double dz = aimPoint.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(MathHelper.atan2(dy, horiz) * (180.0 / Math.PI));

        float yawDiff = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());
        float pitchDiff = MathHelper.clamp(targetPitch - client.player.getPitch(), -maxStepDeg, maxStepDeg);

        float clampedYawStep = MathHelper.clamp(yawDiff * aimSmoothFactor, -maxStepDeg, maxStepDeg);
        float clampedPitchStep = pitchDiff * aimSmoothFactor;

        client.player.setYaw(client.player.getYaw() + clampedYawStep);
        client.player.setPitch(MathHelper.clamp(client.player.getPitch() + clampedPitchStep, -90.0f, 90.0f));
    }

    private static void scanBaseTargets(MinecraftClient client) {
        baseTargets.clear();
        nearestBasePos = null;
        if (client.world == null || client.player == null) return;

        int radius = getScanRadiusChunks();
        int pcx = client.player.getChunkPos().x;
        int pcz = client.player.getChunkPos().z;
        int py = client.player.getBlockPos().getY();

        double bestDistSq = Double.MAX_VALUE;

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    boolean interesting = be instanceof ChestBlockEntity
                            || be instanceof BarrelBlockEntity
                            || be instanceof EnderChestBlockEntity
                            || be instanceof ShulkerBoxBlockEntity
                            || be instanceof SpawnerBlockEntity;
                    if (!interesting) continue;

                    BlockPos pos = be.getPos();
                    if (pos.getY() <= py + 6) {
                        BlockPos imm = pos.toImmutable();
                        baseTargets.add(imm);
                        double dSq = client.player.squaredDistanceTo(Vec3d.ofCenter(imm));
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            nearestBasePos = imm;
                        }
                    }
                }
            }
        }
    }

    private static void drawLine(MatrixStack matrices, VertexConsumer buffer, Vec3d a, Vec3d b, float r, float g, float bl, float al) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        Vec3d dir = b.subtract(a).normalize();
        buffer.vertex(mat, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, al).normal((float) dir.x, (float) dir.y, (float) dir.z);
        buffer.vertex(mat, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, al).normal((float) dir.x, (float) dir.y, (float) dir.z);
    }

    public static class SpaceMenuScreen extends Screen {
        private static final int NODE_COUNT = 20;
        private final float[] px = new float[NODE_COUNT];
        private final float[] py = new float[NODE_COUNT];
        private final float[] vx = new float[NODE_COUNT];
        private final float[] vy = new float[NODE_COUNT];

        protected SpaceMenuScreen() {
            super(Text.literal("Lite Suite Space"));
            Random rnd = new Random();
            for (int i = 0; i < NODE_COUNT; i++) {
                px[i] = rnd.nextFloat();
                py[i] = rnd.nextFloat();
                vx[i] = (rnd.nextFloat() - 0.5f) * 0.0012f;
                vy[i] = (rnd.nextFloat() - 0.5f) * 0.0012f;
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0xE605060A);

            for (int i = 0; i < NODE_COUNT; i++) {
                px[i] += vx[i];
                py[i] += vy[i];
                if (px[i] < 0 || px[i] > 1) vx[i] = -vx[i];
                if (py[i] < 0 || py[i] > 1) vy[i] = -vy[i];
            }

            float connectDist = Math.min(this.width, this.height) * 0.22f;
            for (int i = 0; i < NODE_COUNT; i++) {
                int x1 = (int) (px[i] * this.width);
                int y1 = (int) (py[i] * this.height);

                for (int j = i + 1; j < NODE_COUNT; j++) {
                    int x2 = (int) (px[j] * this.width);
                    int y2 = (int) (py[j] * this.height);
                    float dist = (float) Math.hypot(x2 - x1, y2 - y1);
                    if (dist < connectDist) {
                        int alpha = (int) ((1.0f - dist / connectDist) * 75);
                        int lineColor = (alpha << 24) | 0xFFFFFF;
                        drawSegment(context, x1, y1, x2, y2, lineColor);
                    }
                }
                context.fill(x1 - 1, y1 - 1, x1 + 2, y1 + 2, 0xAAFFFFFF);
            }

            int boxW = Math.min(260, this.width - 24);
            int boxH = 176;
            int bx = (this.width - boxW) / 2;
            int by = (this.height - boxH) / 2;

            context.fill(bx, by, bx + boxW, by + boxH, 0xCC0C0F18);
            context.drawBorder(bx, by, boxW, boxH, 0x55FFFFFF);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§fLITE SUITE §8// §7SPACE"), bx + boxW / 2, by + 8, 0xFFFFFF);

            int btnW = (boxW - 24) / 2;
            int btnH = 26;
            int col1 = bx + 8;
            int col2 = bx + 16 + btnW;
            int row1 = by + 24;
            int row2 = by + 56;
            int row3 = by + 88;

            drawToggleCard(context, col1, row1, btnW, btnH, "Aim Nhe", softAim);
            drawToggleCard(context, col2, row1, btnW, btnH, "ESP Player", espPlayer);
            drawToggleCard(context, col1, row2, btnW, btnH, "Base + Day", espBase);
            drawToggleCard(context, col2, row2, btnW, btnH, "Hien FPS", showFps);

            context.fill(bx + 8, row3, bx + boxW - 8, row3 + btnH, 0x99181E2E);
            context.drawBorder(bx + 8, row3, boxW - 16, btnH, 0x667AD7FF);
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal("Toi uu: §b" + optLevel.label + " §7(Bam doi)"),
                    bx + boxW / 2,
                    row3 + 9,
                    0xFFFFFF
            );

            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal("§8Gan nut mo/bat trong Controls / Pojav"),
                    bx + boxW / 2,
                    by + boxH - 14,
                    0x888888
            );
        }

        private void drawToggleCard(DrawContext ctx, int x, int y, int w, int h, String label, boolean on) {
            int bg = on ? 0x99193B2B : 0x99141720;
            int border = on ? 0xAA55FF99 : 0x44FFFFFF;
            ctx.fill(x, y, x + w, y + h, bg);
            ctx.drawBorder(x, y, w, h, border);
            String stateText = on ? "§aON" : "§7OFF";
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§f" + label + " " + stateText), x + w / 2, y + 9, 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int boxW = Math.min(260, this.width - 24);
            int boxH = 176;
            int bx = (this.width - boxW) / 2;
            int by = (this.height - boxH) / 2;

            int btnW = (boxW - 24) / 2;
            int btnH = 26;
            int col1 = bx + 8;
            int col2 = bx + 16 + btnW;
            int row1 = by + 24;
            int row2 = by + 56;
            int row3 = by + 88;

            if (inside(mouseX, mouseY, col1, row1, btnW, btnH)) {
                toggleSoftAim();
                return true;
            }
            if (inside(mouseX, mouseY, col2, row1, btnW, btnH)) {
                toggleEspPlayer();
                return true;
            }
            if (inside(mouseX, mouseY, col1, row2, btnW, btnH)) {
                toggleEspBase();
                return true;
            }
            if (inside(mouseX, mouseY, col2, row2, btnW, btnH)) {
                showFps = !showFps;
                return true;
            }
            if (inside(mouseX, mouseY, bx + 8, row3, boxW - 16, btnH)) {
                cycleOptLevel();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private boolean inside(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        private void drawSegment(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
            int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
            if (steps <= 0) return;
            int step = Math.max(1, steps / 24);
            for (int i = 0; i <= steps; i += step) {
                float t = (float) i / steps;
                int px = Math.round(x1 + (x2 - x1) * t);
                int py = Math.round(y1 + (y2 - y1) * t);
                ctx.fill(px, py, px + 1, py + 1, color);
            }
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
  }
          
