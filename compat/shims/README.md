# Eturlia shims

## `arclight_sable_patch-1.1.1-eturlia-shim.jar`

Drop-in **modId `arclight_sable_patch`** replacement for the Arclight-only patch jar.

- Does **not** contain Arclight mixins.
- CraftBukkit/Sable gaps are handled in Eturlia core (AABB guard, FluidType APIs, etc.).
- Keep `sable-neoforge`; remove the original Arclight patch.
