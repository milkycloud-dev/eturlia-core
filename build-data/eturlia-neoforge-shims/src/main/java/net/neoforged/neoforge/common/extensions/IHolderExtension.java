package net.neoforged.neoforge.common.extensions;

/**
 * Compile-time marker; runtime uses NeoForge's real IHolderExtension via the
 * neoforge universal jar. Holder must declare this superinterface explicitly
 * because ModLauncher interface injection is not applied to Folia-remapped
 * Holder (DeferredHolder.equals -> Holder.getKey()).
 */
public interface IHolderExtension<T> {}
