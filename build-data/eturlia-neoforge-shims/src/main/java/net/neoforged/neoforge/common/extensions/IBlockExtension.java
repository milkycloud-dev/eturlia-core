package net.neoforged.neoforge.common.extensions;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compile-time shim for NeoForge fire APIs on {@link Block}.
 * Runtime uses NeoForge's real {@code IBlockExtension} defaults.
 */
public interface IBlockExtension {

    default Block self() {
        return (Block) this;
    }

    default int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return ((FireBlock) Blocks.FIRE).getBurnOdds(state);
    }

    default boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getFlammability(level, pos, direction) > 0;
    }

    default void onCaughtFire(BlockState state, Level level, BlockPos pos, @Nullable Direction direction, @Nullable LivingEntity igniter) {
    }

    default int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return ((FireBlock) Blocks.FIRE).getIgniteOdds(state);
    }

    default boolean isFireSource(BlockState state, LevelReader level, BlockPos pos, Direction direction) {
        return state.is(level.dimensionType().infiniburn());
    }
}
