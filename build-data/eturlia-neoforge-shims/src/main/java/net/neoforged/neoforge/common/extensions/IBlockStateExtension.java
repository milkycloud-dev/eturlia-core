package net.neoforged.neoforge.common.extensions;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compile-time shim for NeoForge fire APIs on {@link BlockState}.
 * Runtime uses NeoForge's real {@code IBlockStateExtension} defaults.
 */
public interface IBlockStateExtension {

    default BlockState self() {
        return (BlockState) this;
    }

    default int getFlammability(BlockGetter level, BlockPos pos, Direction direction) {
        return self().getBlock().getFlammability(self(), level, pos, direction);
    }

    default boolean isFlammable(BlockGetter level, BlockPos pos, Direction direction) {
        return self().getBlock().isFlammable(self(), level, pos, direction);
    }

    default void onCaughtFire(Level level, BlockPos pos, @Nullable Direction direction, @Nullable LivingEntity igniter) {
        self().getBlock().onCaughtFire(self(), level, pos, direction, igniter);
    }

    default int getFireSpreadSpeed(BlockGetter level, BlockPos pos, Direction direction) {
        return self().getBlock().getFireSpreadSpeed(self(), level, pos, direction);
    }

    default boolean isFireSource(LevelReader level, BlockPos pos, Direction direction) {
        return self().getBlock().isFireSource(self(), level, pos, direction);
    }
}
