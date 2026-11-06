# Resource packs in Engine (Bedrock)

Engine supports Bedrock resource packs in `resource_packs/`, including manifests
with `format_version: 3`, PBR capabilities, and declared `subpacks/` variants.

## Install a distributable pack

1. Ensure the pack's license allows you to offer its contents for download from
   your server. **Do not redistribute Minecraft Marketplace purchases, including
   Actions & Stuff, merely because you own a local copy.**
2. Put the authorized `.mcpack` or `.zip` archive in `resource_packs/`,
   with `manifest.json` at the archive root.
3. Edit `server-settings.yml`, then restart Engine:

```yaml
resource-pack-settings:
  auto-encrypt-packs: false
  force-resource-packs: true
  allow-client-resource-packs: false
  max-chunk-size: 100
  disable-vibrant-visuals: false
```

Subpacks are selected **automatically** without a `selected-subpacks`
setting, custom UUID configuration, or modifying the pack itself. Engine
inspects the manifest: if every declared subpack uses the universal baseline
memory tier (0 or 1), it advertises the **last declared** subpack in both
the pack info and stack packets, following Bedrock's tie-breaking rules.
For example, `SP0`, `SP1` and `SP2` all at tier 0 automatically
select `SP2`.

For a pack with **different** tiers or a higher common tier, Engine
advertises an empty override instead of forcing high-resolution assets
onto low-memory devices. The Minecraft client must resolve the appropriate
variant. Because this can depend on the client's platform and cache state,
confirm with a real Bedrock client during deployment.

- `force-resource-packs: true` prompts the player to accept server packs
  before entering. If they decline, they cannot join while the pack is required.
- `auto-encrypt-packs: false` is recommended for PBR / Vibrant Visuals packs;
  automatic encryption can prevent those visuals from working.
- When authorized packs are served, Bedrock downloads them as part of login,
  normally reusing a cached pack on later joins.
- A pack declaring `header.min_engine_version` will be omitted from the
  advertised packs and stack for older Minecraft clients. This preserves
  multiversion connections for resource-only packs, but an old client won't
  see those newer assets. Gameplay mechanics must not rely on an omitted pack.
- The `settings` array of a v3 manifest is parsed and retained. These are
  client-side settings; Engine does not force the options selected in a
  Marketplace pack's personal UI.

## Marketplace content

For purchased Marketplace packs, players should activate their licensed copy
in Minecraft's resource settings. To allow players to use their own packs
without distributing assets, use `allow-client-resource-packs: true` and,
when no authorized server packs are installed, `force-resource-packs: false`.
Engine's generic resource-pack downloader must not be used to distribute that
paid content without permission. Client-side global resources may still be
affected by the player's own pack order and Minecraft platform restrictions.

## Testing

`./gradlew :server:test --tests org.allaymc.server.pack.ResourcePackV3Test`

The tests use a synthetic pack (no Marketplace assets) and cover v3 manifest
parsing, PBR, automatic tier-0/1 subpack selection, per-version filtering, and the critical ZIP
case where `subpacks/SP*/` directories have no explicit directory entries.

Before deploying, test with a real Bedrock client of a supported version
and, separately, an older client to confirm multiversion behavior.
