/*
 * Crelia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Crelia contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.coremods;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Crelia Folia-aware coremod for NeoForge 21.1.x (ICoreMod / ModLauncher API).
 *
 * <p>Registers transformers that:</p>
 * <ul>
 *   <li>Redirect {@code MinecraftServer.main} to {@code crelia.CreliaServer.main}
 *       when the class is loaded under FML (standalone launcher already does this,
 *       but this covers direct FML launches).</li>
 *   <li>Insert region-thread validation before {@code ServerLevel.tick} call sites.</li>
 * </ul>
 */
public class CreliaFMLBootstrapCoremod implements ICoreMod {
    @Override
    public Iterable<? extends ITransformer<?>> getTransformers() {
        List<ITransformer<?>> transformers = new ArrayList<>();
        transformers.add(new CreliaServerMainRedirector());
        transformers.add(new RegionThreadValidator());
        return transformers;
    }

    static final class CreliaServerMainRedirector implements ITransformer<ClassNode> {
        private static final String MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer";
        private static final String CRELIA_SERVER = "crelia/CreliaServer";
        private static final String MAIN_DESC = "([Ljava/lang/String;)V";

        @Override
        public TargetType<ClassNode> getTargetType() {
            return TargetType.CLASS;
        }

        @Override
        public Set<Target<ClassNode>> targets() {
            return Set.of(Target.targetClass(MINECRAFT_SERVER));
        }

        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            for (MethodNode method : input.methods) {
                if ("main".equals(method.name) && MAIN_DESC.equals(method.desc)) {
                    method.instructions.clear();
                    method.tryCatchBlocks.clear();
                    InsnList insn = new InsnList();
                    insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insn.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            CRELIA_SERVER,
                            "main",
                            MAIN_DESC,
                            false));
                    insn.add(new InsnNode(Opcodes.RETURN));
                    method.instructions = insn;
                }
            }
            return input;
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }
    }

    static final class RegionThreadValidator implements ITransformer<ClassNode> {
        private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
        private static final String VALIDATOR_HOOKS = "crelia/core/mixin/server/RegionThreadValidatorHooks";
        private static final String TICK_DESC = "(Ljava/util/function/BooleanSupplier;)V";
        private static final String CHECK_DESC = "(Lnet/minecraft/server/level/ServerLevel;)V";

        @Override
        public TargetType<ClassNode> getTargetType() {
            return TargetType.CLASS;
        }

        @Override
        public Set<Target<ClassNode>> targets() {
            // Broad scan — ModLauncher will only transform loaded classes.
            // Prefer high-value call sites; wildcard class targeting is not supported
            // on all ModLauncher versions, so target MinecraftServer + ServerLevel.
            return Set.of(
                    Target.targetClass("net.minecraft.server.MinecraftServer"),
                    Target.targetClass("net.minecraft.server.level.ServerLevel"),
                    Target.targetClass("net.minecraft.server.level.ServerChunkCache"));
        }

        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            for (MethodNode method : input.methods) {
                AbstractInsnNode node = method.instructions.getFirst();
                while (node != null) {
                    AbstractInsnNode next = node.getNext();
                    if (node instanceof MethodInsnNode methodInsn
                            && (methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                                    || methodInsn.getOpcode() == Opcodes.INVOKEINTERFACE)
                            && SERVER_LEVEL.equals(methodInsn.owner)
                            && "tick".equals(methodInsn.name)
                            && TICK_DESC.equals(methodInsn.desc)) {
                        // Stack before INVOKEVIRTUAL: ..., ServerLevel, BooleanSupplier
                        // Duplicate ServerLevel (under BooleanSupplier) for the check.
                        InsnList validation = new InsnList();
                        validation.add(new InsnNode(Opcodes.SWAP));
                        validation.add(new InsnNode(Opcodes.DUP));
                        validation.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                VALIDATOR_HOOKS,
                                "checkRegionThread",
                                CHECK_DESC,
                                false));
                        validation.add(new InsnNode(Opcodes.SWAP));
                        method.instructions.insertBefore(methodInsn, validation);
                    }
                    node = next;
                }
            }
            return input;
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }
    }
}
