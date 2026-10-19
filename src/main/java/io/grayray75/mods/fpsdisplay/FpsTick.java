package io.grayray75.mods.fpsdisplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Right-click a block with a sword in your main hand -> rail, TNT minecart, flint & steel,
 * (optional slab), then a crossbow shot. Press J for the settings screen.
 *
 * Rules:
 *  - Does NOTHING (click passes through) if the mod is off, you're on cooldown, your offhand holds something
 *    usable (gapple, potion, shield...), or any of rail / TNT minecart / flint & steel / LOADED crossbow is missing.
 *    The slab is optional.
 *  - From inventory ON: missing hotbar items are swapped in from the inventory and swapped back at the very end.
 *  - Failure cleanup: placements are verified; a missing rail or cart retries on the next spot over (up to 2 times),
 *    anything else aborts: slot, swaps, sneak and keys are restored and the cooldown starts.
 *  - Aim smoothing: the view turns over a few ticks; sneak starts exactly 1 tick before the shot and is released
 *    only when everything (all setups, reload, swap-back) is done.
 *  - Multiple setups (1-3): after the shot + reload, the next one is placed 2 blocks to the side after a delay.
 *    Auto refill ON re-fetches used-up items from the inventory between setups.
 *
 * Yarn names for 1.21.1. Written from the compiled original; NOT compiled or tested.
 */
public class FpsTick implements ClientModInitializer {
    // ---- settings (J screen) ----
    static boolean modEnabled = true, fromInventory = true, autoRefill = true, autoReload = true, autoShift = true, smoothAim = true;
    static int dirMode = 0;            // 0 = toward you, 1 = clicked face, 2 = look direction
    static int setupCount = 1;         // 1..3
    static int setupDelay = 10;        // ticks between setups
    static double distCenter = 4.15;   // window = distCenter +/- 0.5  (original: 3.65 - 4.65)

    static final String[] DIR_NAMES = {"Toward you", "Clicked face", "Look direction"};
    private static final int COOLDOWN_TICKS = 20;
    private static final int CONFIRM_TRIES = 8;    // ticks to wait for the server to confirm a placement
    private static final int RELOAD_TIMEOUT = 50;  // ticks
    private static final int AIM_TICKS = 3;        // smooth-turn duration
    private static final int MAX_RETRIES = 2;
    private static final int SETUP_SPACING = 2;    // blocks between setups in a multi-run

    private static final Predicate<ItemStack> IS_RAIL  = s -> s.isIn(ItemTags.RAILS);
    private static final Predicate<ItemStack> IS_CART  = s -> s.isOf(Items.TNT_MINECART);
    private static final Predicate<ItemStack> IS_FLINT = s -> s.isOf(Items.FLINT_AND_STEEL);
    private static final Predicate<ItemStack> IS_SLAB  = s -> s.isIn(ItemTags.SLABS); // inferred tag
    private static final Predicate<ItemStack> IS_BOW   = s -> s.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(s);

    private KeyBinding menuKey;

    private int step = -1, wait = 0, cooldown = 0, tries = 0, reloadTicks = 0;
    private int setupIdx = 0, retryCount = 0, aimIndex = 0;
    private BlockPos baseGround, groundPos, fireGround, slabGround;
    private Direction chainDir, sideDir;
    private int prevSlot;
    private int slotRail, slotCart, slotFlint, slotSlab, slotBow;
    /** {inventorySlot, hotbarSlot} pairs, undone in reverse order at the very end. */
    private final List<int[]> swaps = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        loadConfig();
        menuKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.fpsdisplay.cart_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.misc"));

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient || hand != Hand.MAIN_HAND || step != -1) return ActionResult.PASS;
            if (!modEnabled || cooldown > 0) return ActionResult.PASS;
            if (!player.getMainHandStack().isIn(ItemTags.SWORDS)) return ActionResult.PASS;
            if (offhandWantsUse(player.getOffHandStack())) return ActionResult.PASS; // gapple/shield/etc. keep working
            return start(player, hit.getBlockPos(), hit.getSide()) ? ActionResult.SUCCESS : ActionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static boolean offhandWantsUse(ItemStack off) {
        if (off.isEmpty()) return false;
        return off.contains(DataComponentTypes.FOOD) || off.getUseAction() != UseAction.NONE;
    }

    // ---- item lookup (hotbar, then inventory with swap) --------------------------------------

    private int pickHotbarSlot(PlayerEntity p, Set<Integer> reserved) {
        for (int i = 0; i < 9; i++)
            if (!reserved.contains(i) && p.getInventory().getStack(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++)
            if (!reserved.contains(i)) return i;
        return -1;
    }

    /** Hotbar slot holding the wanted item; swaps it in from the inventory if "From inventory" is on. -1 if none. */
    private int acquire(MinecraftClient mc, PlayerEntity p, Predicate<ItemStack> want, Set<Integer> reserved) {
        for (int i = 0; i < 9; i++) {
            if (!reserved.contains(i) && want.test(p.getInventory().getStack(i))) {
                reserved.add(i);
                return i;
            }
        }
        if (!fromInventory) return -1;
        for (int i = 9; i < 36; i++) {
            if (!want.test(p.getInventory().getStack(i))) continue;
            int hot = pickHotbarSlot(p, reserved);
            if (hot < 0) return -1;
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, hot, SlotActionType.SWAP, p);
            swaps.add(new int[]{i, hot});
            reserved.add(hot);
            return hot;
        }
        return -1;
    }

    /** Between setups: keep the slot if still valid, otherwise refetch (Auto refill + From inventory). */
    private int refresh(MinecraftClient mc, PlayerEntity p, int slot, Predicate<ItemStack> want, Set<Integer> reserved) {
        if (slot >= 0 && want.test(p.getInventory().getStack(slot))) { reserved.add(slot); return slot; }
        if (autoRefill && fromInventory) return acquire(mc, p, want, reserved);
        return -1;
    }

    private void undoSwaps(MinecraftClient mc, PlayerEntity p) {
        if (mc.interactionManager != null) {
            for (int k = swaps.size() - 1; k >= 0; k--) {
                int[] s = swaps.get(k);
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, s[0], s[1], SlotActionType.SWAP, p);
            }
        }
        swaps.clear();
    }

    // ---- spot selection ---------------------------------------------------------------------------

    private boolean spotOk(MinecraftClient mc, BlockPos g) {
        return mc.world.getBlockState(g).isSideSolidFullSquare(mc.world, g, Direction.UP)
                && mc.world.getBlockState(g.up()).isReplaceable();
    }

    /** Sets groundPos/fireGround/slabGround for a sideways offset from the base spot; false if the spot is unusable. */
    private boolean applySpot(MinecraftClient mc, int sideOffset) {
        BlockPos g = baseGround.offset(sideDir, sideOffset);
        if (!spotOk(mc, g)) return false;
        groundPos = g;
        fireGround = g.offset(chainDir);
        slabGround = fireGround.offset(chainDir);
        return true;
    }

    /** Retry: try the next spot over (+1, -1, +2, ...). */
    private boolean nextRetrySpot(MinecraftClient mc) {
        for (int r = retryCount + 1; r <= MAX_RETRIES; r++) {
            int off = setupIdx * SETUP_SPACING + ((r + 1) / 2) * (r % 2 == 1 ? 1 : -1);
            if (applySpot(mc, off)) { retryCount = r; return true; }
        }
        return false;
    }

    // ---- start -----------------------------------------------------------------------------------

    private boolean start(PlayerEntity player, BlockPos clicked, Direction clickedSide) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.interactionManager == null) return false;

        double lo = distCenter - 0.5, hi = distCenter + 0.5;
        double dx = player.getX() - (clicked.getX() + 0.5);
        double dz = player.getZ() - (clicked.getZ() + 0.5);
        Direction toward = Direction.getFacing(dx, 0, dz);   // block -> you
        BlockPos p = clicked;
        for (int i = 0; i < 8; i++) {
            double d = Math.hypot(player.getX() - (p.getX() + 0.5), player.getZ() - (p.getZ() + 0.5));
            if (d < lo) p = p.offset(toward.getOpposite());
            else if (d > hi) p = p.offset(toward);
            else break;
        }
        double dist = Math.hypot(player.getX() - (p.getX() + 0.5), player.getZ() - (p.getZ() + 0.5));
        if (dist < lo || dist > hi) return false;

        chainDir = switch (dirMode) {
            case 1 -> clickedSide.getAxis().isHorizontal() ? clickedSide : toward;
            case 2 -> player.getHorizontalFacing();
            default -> toward;
        };
        sideDir = chainDir.rotateYClockwise();
        baseGround = p;
        if (!applySpot(mc, 0)) return false;

        Set<Integer> reserved = new HashSet<>();
        reserved.add(player.getInventory().selectedSlot); // the sword's slot is never touched
        swaps.clear();
        slotRail  = acquire(mc, player, IS_RAIL, reserved);
        slotCart  = acquire(mc, player, IS_CART, reserved);
        slotFlint = acquire(mc, player, IS_FLINT, reserved);
        slotBow   = acquire(mc, player, IS_BOW, reserved);   // must be loaded
        slotSlab  = acquire(mc, player, IS_SLAB, reserved);  // optional
        if (slotRail < 0 || slotCart < 0 || slotFlint < 0 || slotBow < 0) {
            undoSwaps(mc, player);
            return false; // anything missing / crossbow unloaded -> do nothing, click passes through
        }

        prevSlot = player.getInventory().selectedSlot;
        setupIdx = 0;
        retryCount = 0;
        aimIndex = 0;
        reloadTicks = 0;
        tries = 0;
        step = 0;
        wait = 0;
        return true;
    }

    // ---- the sequence --------------------------------------------------------------------------

    private void tick(MinecraftClient mc) {
        if (cooldown > 0) cooldown--;

        while (menuKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new ControlScreen());
        }

        if (step < 0) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || mc.world == null) {
            abort(mc, null);
            return;
        }
        if (wait > 0) { wait--; return; }

        switch (step) {
            case 0 -> { select(p, slotRail);  clickTop(mc, p, groundPos); }
            case 1 -> {
                if (!ready(mc, p, true, () -> mc.world.getBlockState(groundPos.up()).isIn(BlockTags.RAILS))) return;
                select(p, slotCart); clickTop(mc, p, groundPos.up());
            }
            case 2 -> {
                if (!ready(mc, p, true, () -> !mc.world.getEntitiesByClass(TntMinecartEntity.class,
                        new Box(groundPos.up()).expand(1.0), e -> true).isEmpty())) return;
                select(p, slotFlint); clickTop(mc, p, fireGround);
            }
            case 3 -> {
                if (!ready(mc, p, false, () -> mc.world.getBlockState(fireGround.up()).isIn(BlockTags.FIRE))) return;
                if (slotSlab >= 0) { select(p, slotSlab); clickTop(mc, p, slabGround); }
            }
            case 4 -> { select(p, slotBow); aimIndex = 0; }
            case 5 -> {   // smooth turn over a few ticks (stand still, not sneaking yet)
                int total = smoothAim ? AIM_TICKS : 0;
                if (aimIndex < total) {
                    float[] t = target(p);
                    int remaining = total - aimIndex;
                    float yaw = p.getYaw() + MathHelper.wrapDegrees(t[0] - p.getYaw()) / remaining;
                    float pitch = p.getPitch() + (t[1] - p.getPitch()) / remaining;
                    setRotation(p, yaw, pitch);
                    aimIndex++;
                    return;
                }
            }
            case 6 -> { if (autoShift) holdKey(mc.options.sneakKey, true); } // sneak starts exactly 1 tick before the shot
            case 7 -> {
                if (!CrossbowItem.isCharged(p.getInventory().getStack(slotBow))) { abort(mc, p); return; }
                float[] t = target(p);               // final exact aim, now with sneaking eye height
                setRotation(p, t[0], t[1]);
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                wait = 2;
            }
            case 8 -> {
                // optional reload; sneak stays held until everything is finished
                if (autoReload && hasAmmo(p) && reloadTicks < RELOAD_TIMEOUT
                        && !CrossbowItem.isCharged(p.getInventory().getStack(slotBow))) {
                    select(p, slotBow);
                    holdKey(mc.options.useKey, true);
                    reloadTicks++;
                    return; // stay on this step until charged (or timeout)
                }
                holdKey(mc.options.useKey, false);
                if (!nextSetup(mc, p)) finish(mc, p);
                return;
            }
        }
        step++;
        tries = 0;
    }

    /** True when cond holds. Otherwise waits a tick; after CONFIRM_TRIES it retries the next spot (if allowed) or aborts. */
    private boolean ready(MinecraftClient mc, ClientPlayerEntity p, boolean retryable, BooleanSupplier cond) {
        if (cond.getAsBoolean()) return true;
        if (++tries > CONFIRM_TRIES) {
            if (retryable && nextRetrySpot(mc)) { step = 0; tries = 0; wait = 0; }
            else abort(mc, p);
        }
        return false;
    }

    /** Multi-setup: prepare the next one, or return false to finish. */
    private boolean nextSetup(MinecraftClient mc, ClientPlayerEntity p) {
        if (setupIdx + 1 >= setupCount) return false;
        Set<Integer> reserved = new HashSet<>();
        reserved.add(prevSlot);
        slotRail  = refresh(mc, p, slotRail, IS_RAIL, reserved);
        slotCart  = refresh(mc, p, slotCart, IS_CART, reserved);
        slotFlint = refresh(mc, p, slotFlint, IS_FLINT, reserved);
        slotBow   = refresh(mc, p, slotBow, IS_BOW, reserved);   // must have been reloaded
        slotSlab  = refresh(mc, p, slotSlab, IS_SLAB, reserved);
        if (slotRail < 0 || slotCart < 0 || slotFlint < 0 || slotBow < 0) return false;
        int next = setupIdx + 1;
        if (!applySpot(mc, next * SETUP_SPACING)) {
            if (!applySpot(mc, -next * SETUP_SPACING)) return false;
        }
        setupIdx = next;
        retryCount = 0;
        reloadTicks = 0;
        aimIndex = 0;
        tries = 0;
        step = 0;
        wait = setupDelay;
        return true;
    }

    private void finish(MinecraftClient mc, ClientPlayerEntity p) {
        holdKey(mc.options.useKey, false);
        select(p, prevSlot);
        undoSwaps(mc, p);                          // everything back where it was (incl. leftover carts)
        holdKey(mc.options.sneakKey, false);       // unsneak only now, when everything is done
        step = -1;
        cooldown = COOLDOWN_TICKS;
    }

    /** Failure cleanup. p may be null if the player is gone. */
    private void abort(MinecraftClient mc, ClientPlayerEntity p) {
        holdKey(mc.options.useKey, false);
        if (p != null) {
            select(p, prevSlot);
            undoSwaps(mc, p);
        } else {
            swaps.clear();
        }
        holdKey(mc.options.sneakKey, false);
        step = -1;
        cooldown = COOLDOWN_TICKS;
    }

    private boolean hasAmmo(PlayerEntity p) {
        if (isAmmo(p.getOffHandStack())) return true;
        for (int i = 0; i < 36; i++) if (isAmmo(p.getInventory().getStack(i))) return true;
        return false;
    }

    private static boolean isAmmo(ItemStack s) {
        return s.isOf(Items.ARROW) || s.isOf(Items.SPECTRAL_ARROW) || s.isOf(Items.TIPPED_ARROW) || s.isOf(Items.FIREWORK_ROCKET);
    }

    /** Presses a key, or releases it back to whether you are physically holding it. */
    private void holdKey(KeyBinding key, boolean on) {
        if (on) { key.setPressed(true); return; }
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean physical = false;
        InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        if (bound.getCategory() == InputUtil.Type.KEYSYM)
            physical = InputUtil.isKeyPressed(mc.getWindow().getHandle(), bound.getCode());
        key.setPressed(physical);
    }

    private void select(PlayerEntity p, int slot) {
        p.getInventory().selectedSlot = slot;
    }

    private void clickTop(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
        p.swingHand(Hand.MAIN_HAND);
    }

    /** {yaw, pitch} to shoot the setup: yaw at the cart, pitch at the fire block (same maths as the original). */
    private float[] target(ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        BlockPos a = groundPos.up();
        BlockPos b = fireGround.up();

        double ax = a.getX() + 0.5 - eye.x, az = a.getZ() + 0.5 - eye.z;
        float yaw = (float) (MathHelper.atan2(az, ax) * 180.0 / Math.PI) - 90.0f;

        double bx = b.getX() + 0.5 - eye.x, bz = b.getZ() + 0.5 - eye.z, by = b.getY() + 0.4 - eye.y;
        float pitch = (float) -(MathHelper.atan2(by, Math.sqrt(bx * bx + bz * bz)) * 180.0 / Math.PI);
        return new float[]{yaw, pitch};
    }

    private void setRotation(ClientPlayerEntity p, float yaw, float pitch) {
        p.setYaw(yaw);
        p.setPitch(pitch);
        p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround()));
    }

    // ---- config persistence ---------------------------------------------------------------------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("fpsdisplay-cart.properties");
    }

    static void loadConfig() {
        try (InputStream in = Files.newInputStream(configPath())) {
            Properties pr = new Properties();
            pr.load(in);
            modEnabled    = Boolean.parseBoolean(pr.getProperty("modEnabled", "true"));
            fromInventory = Boolean.parseBoolean(pr.getProperty("fromInventory", "true"));
            autoRefill    = Boolean.parseBoolean(pr.getProperty("autoRefill", "true"));
            autoReload    = Boolean.parseBoolean(pr.getProperty("autoReload", "true"));
            autoShift     = Boolean.parseBoolean(pr.getProperty("autoShift", "true"));
            smoothAim     = Boolean.parseBoolean(pr.getProperty("smoothAim", "true"));
            dirMode       = MathHelper.clamp(Integer.parseInt(pr.getProperty("dirMode", "0")), 0, 2);
            setupCount    = MathHelper.clamp(Integer.parseInt(pr.getProperty("setupCount", "1")), 1, 3);
            setupDelay    = MathHelper.clamp(Integer.parseInt(pr.getProperty("setupDelay", "10")), 0, 100);
            distCenter    = MathHelper.clamp(Double.parseDouble(pr.getProperty("distCenter", "4.15")), 2.5, 6.0);
        } catch (IOException | NumberFormatException ignored) { /* first run / bad file: defaults */ }
    }

    static void saveConfig() {
        Properties pr = new Properties();
        pr.setProperty("modEnabled", String.valueOf(modEnabled));
        pr.setProperty("fromInventory", String.valueOf(fromInventory));
        pr.setProperty("autoRefill", String.valueOf(autoRefill));
        pr.setProperty("autoReload", String.valueOf(autoReload));
        pr.setProperty("autoShift", String.valueOf(autoShift));
        pr.setProperty("smoothAim", String.valueOf(smoothAim));
        pr.setProperty("dirMode", String.valueOf(dirMode));
        pr.setProperty("setupCount", String.valueOf(setupCount));
        pr.setProperty("setupDelay", String.valueOf(setupDelay));
        pr.setProperty("distCenter", String.valueOf(distCenter));
        try (OutputStream out = Files.newOutputStream(configPath())) {
            pr.store(out, "Crossbow cart settings");
        } catch (IOException ignored) { }
    }

    // ---- the J screen -----------------------------------------------------------------------------

    static class ControlScreen extends Screen {
        private static final int[] DELAYS = {5, 10, 20, 40};
        private ButtonWidget distButton;

        ControlScreen() { super(Text.literal("Crossbow Cart")); }

        private static Text onOff(String name, boolean on) {
            return Text.literal(name + ": " + (on ? "ON" : "OFF"));
        }

        private void button(int x, int y, int w, Supplier<Text> label, Runnable action) {
            addDrawableChild(ButtonWidget.builder(label.get(), b -> {
                action.run();
                b.setMessage(label.get());
                saveConfig();
            }).dimensions(x, y, w, 20).build());
        }

        @Override
        protected void init() {
            int lx = width / 2 - 155, rx = width / 2 + 5, w = 150;
            int y0 = height / 2 - 55, dy = 24;

            // left column
            button(lx, y0,          w, () -> onOff("Toggle mod",     modEnabled),    () -> modEnabled = !modEnabled);
            button(lx, y0 + dy,     w, () -> onOff("From inventory", fromInventory), () -> fromInventory = !fromInventory);
            button(lx, y0 + dy * 2, w, () -> onOff("Auto refill",    autoRefill),    () -> autoRefill = !autoRefill);
            button(lx, y0 + dy * 3, w, () -> onOff("Auto reload",    autoReload),    () -> autoReload = !autoReload);
            button(lx, y0 + dy * 4, w, () -> onOff("Auto shift",     autoShift),     () -> autoShift = !autoShift);

            // right column
            button(rx, y0,          w, () -> onOff("Aim smoothing", smoothAim), () -> smoothAim = !smoothAim);
            button(rx, y0 + dy,     w, () -> Text.literal("Direction: " + DIR_NAMES[dirMode]), () -> dirMode = (dirMode + 1) % 3);
            button(rx, y0 + dy * 2, w, () -> Text.literal("Setups: " + setupCount), () -> setupCount = setupCount % 3 + 1);
            button(rx, y0 + dy * 3, w, () -> Text.literal("Delay: " + setupDelay + " ticks"), () -> {
                int idx = 0;
                for (int i = 0; i < DELAYS.length; i++) if (DELAYS[i] == setupDelay) idx = i;
                setupDelay = DELAYS[(idx + 1) % DELAYS.length];
            });
            // distance:  [-] label [+]
            int dy4 = y0 + dy * 4;
            distButton = addDrawableChild(ButtonWidget.builder(Text.literal(distLabel()), b -> { })
                    .dimensions(rx + 22, dy4, w - 44, 20).build());
            button(rx, dy4, 20, () -> Text.literal("-"), () -> {
                distCenter = Math.max(2.5, Math.round((distCenter - 0.25) * 100) / 100.0);
                distButton.setMessage(Text.literal(distLabel()));
            });
            button(rx + w - 20, dy4, 20, () -> Text.literal("+"), () -> {
                distCenter = Math.min(6.0, Math.round((distCenter + 0.25) * 100) / 100.0);
                distButton.setMessage(Text.literal(distLabel()));
            });
        }

        private static String distLabel() {
            return String.format("Dist: %.2f-%.2f", distCenter - 0.5, distCenter + 0.5);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 75, 0xFFFFFF);
        }

        @Override
        public boolean shouldPause() { return false; }

        @Override
        public void close() {
            saveConfig();
            super.close();
        }
    }
}
