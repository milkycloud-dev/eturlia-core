# ASIC smoke results (2026-08-07) — updated after main patch repair + 0084–0094

Jar: `eturlia-1.21.1-neoforge-21.1.248` with patches through **0094**.

**Legend:** boot = FML OK + `Done (...)!` + worlds tick without hard stop. Not full gameplay / region-safe proof.

## Confirmed PASS (core former-BLOCK stack, concurrent)

Mods together: Moonlight, Farmers Delight, CreativeCore, Farm & Charm, Almanac, letmedespawn, lodestone, horseman, Architectury, Cloth, Supplementaries, quality_food, **amendments**, Twilight Forest.

| Check | Result |
|-------|--------|
| Mixins apply | PASS |
| `Done (...)` | PASS (~1.9s) |
| Worlds incl. Twilight Forest | PASS |
| Alive after ~35–40s ticks | PASS |

SHA256 (this build): `9c8fab2156e5525e11b1ebf587eb578a159221437d6fc882e6a29c1e5b9d777a`

### Kernel bridges that unblocked this stack

| Patch | What |
|-------|------|
| **0084–0086** | FD/CreativeCore/Farm&Charm/Supplementaries/quality_food/TF/letmedespawn/lodestone/horseman |
| **0087** | amendments `doBrew` 3-arg; ItemStack Supplier components |
| **0088–0089** | Mojang `ServerLevelData` field; Entity multipart; PrimaryLevelData helper |
| **0090–0091** | DefaultAttributes NeoForge view; Mojang ServerLevel 12-arg body (Moonlight) |
| **0092** | `getFoodProperties`; timings ServerLevelData cast |
| **0093** | `HolderLookup.Provider.holder` (TF TravellersModifiers) |
| **0094** | `Main.main(String[])` + `LevelStorageSource.validateAndCreateAccess(String)` for **libjf/respackopts** and **WorldWeaver/BetterEnd** |

## Pack hygiene (auto, not “delete mods”)

| Item | Eturlia behavior |
|------|------------------|
| `spark-*-neoforge.jar` | Soft-skip → `*.jar.eturlia-skipped` (bundled Folia spark keeps `/spark`) |
| `arclight_sable_patch` (Arclight original) | Soft-skip; use `*-eturlia-shim.jar` if a placeholder modId is needed |
| `lithostitched-1.7.10+beta4` | Hard gate — **update jar to ≥1.7.13** (same mod; beta crashes TemplateLists) |
| `easy_npc` version `0.0NONE` | Broken jar on disk — re-download; not a kernel gap |
| `.jar1` / `.bak*` | Not loaded by NeoForge (wrong extension) |

## Known WARN / residual (non-fatal in this smoke)

| Issue | Notes |
|-------|--------|
| `handleServerStarted` WARN | Supplementaries faucet FakePlayer + horseman `ServerGamePacketListenerImpl` mixin miss on FakePlayer path — Folia-first catches; server continues |
| Bukkit `CraftEntityType` for some mod entities | IllegalArgument when wrapping unknown EntityType — noise on TF/nether entities |
| Datapack/recipe JSON noise | FD item_ability / neoforge:difference parse warnings |

## RISK (boot may pass after 0094; gameplay not certified)

| Mod | Notes |
|-----|-------|
| BetterEnd / BCLib / WorldWeaver / Wunderlib | NeoForge ports; Main mixin fixed by 0094; heavy worldgen RISK on Folia |
| Create / Alex / EasyNPC / Sable / TF regions | Boot OK\* possible; region physics RISK |

## Not claimed

Full concurrent ~60+ ASIC pack still **not** certified green until post-0094 full-pack smoke.
