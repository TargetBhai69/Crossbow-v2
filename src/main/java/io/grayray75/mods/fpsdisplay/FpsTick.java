package io.grayray75.mods.fpsdisplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

public class FpsTick implements ClientModInitializer {

    private int step = -1;   // -1 = idle
    private int wait = 0;

    private BlockPos groundPos;   // block that was clicked, rail goes on top
    private BlockPos fireGround;  // ground block under the fire
    private BlockPos slabGround;  // ground block under the slab

    private int prevSlot;
    private int slotA, slotB, slotC, slotD, slotE;

    @Override
    public void onInitializeClient() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient || hand != Hand.MAIN_HAND || step != -1) return ActionResult.PASS;
            if (!player.getMainHandStack().isIn(ItemTags.SWORDS)) return ActionResult.PASS;
            start(player, hit.getBlockPos());
            return ActionResult.FAIL; // cancel normal sword right-click
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void start(PlayerEntity player, BlockPos clicked) {
        slotA = find(player, s -> s.isIn(ItemTags.RAILS));
        slotB = find(player, s -> s.isOf(Items.TNT_MINECART));
        slotC = find(player, s -> s.isOf(Items.FLINT_AND_STEEL));
        slotD = find(player, s -> s.isIn(ItemTags.SLABS));
        slotE = find(player, s -> s.isOf(Items.CROSSBOW));

        if (slotA < 0 || slotB < 0 || slotC < 0 || slotE < 0) {
            player.sendMessage(Text.literal("§c[FPS Display] Setup incomplete"), true);
            return;
        }

        double dx = player.getX() - (clicked.getX() + 0.5);
        double dz = player.getZ() - (clicked.getZ() + 0.5);
        if (Math.sqrt(dx * dx + dz * dz) < 3.0) {
            player.sendMessage(Text.literal("§c[FPS Display] Too close"), true);
            return;
        }

        Direction toward = Direction.getFacing(dx, 0, dz); // toward player
        groundPos = clicked;
        fireGround = clicked.offset(toward);
        slabGround = fireGround.offset(toward);
        prevSlot = player.getInventory().selectedSlot;
        step = 0;
        wait = 0;
    }

    private void tick(MinecraftClient mc) {
        if (step < 0) return;
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) {
            step = -1;
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }

        switch (step) {
            case 0 -> { select(player, slotA); clickTop(mc, player, groundPos); }
            case 1 -> { select(player, slotB); clickTop(mc, player, groundPos.up()); }
            case 2 -> { select(player, slotC); clickTop(mc, player, fireGround); }
            case 3 -> {
                if (slotD >= 0) {
                    select(player, slotD);
                    clickTop(mc, player, slabGround);
                }
            }
            case 4 -> { select(player, slotE); aim(player); wait = 1; }
            case 5 -> { mc.interactionManager.interactItem(player, Hand.MAIN_HAND); wait = 2; }
            case 6 -> {
                select(player, prevSlot);
                step = -1;
                return;
            }
        }
        step++;
    }

    private void clickTop(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
        player.swingHand(Hand.MAIN_HAND);
    }

    private void aim(ClientPlayerEntity player) {
        BlockPos rail = groundPos.up();
        Vec3d eye = player.getEyePos();
        double dx = rail.getX() + 0.5 - eye.x;
        double dy = rail.getY() + 0.4 - eye.y;
        double dz = rail.getZ() + 0.5 - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        player.setPitch((float) -(MathHelper.atan2(dy, flat) * 180.0 / Math.PI));
    }

    private void select(PlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;
    }

    private int find(PlayerEntity player, Predicate<ItemStack> filter) {
        for (int i = 0; i < 9; i++) {
            if (filter.test(player.getInventory().getStack(i))) return i;
        }
        return -1;
    }
}
