import type { SkinIR } from "../../domain/animationIR";
import { createSkinOverride } from "../../domain/skin";
import { ConversionError } from "../../foundation/diagnostics";

export function readBlockbenchSkin(textures: readonly { name?: string; source?: string }[]): SkinIR | undefined {
  const named = (name: string) => textures.filter((texture) => texture.name?.trim().toLowerCase().replace(/\.png$/, "") === name);
  const overrides = named("override"), masks = named("override_mask");
  if (overrides.length > 1 || masks.length > 1) throw new ConversionError("duplicate_skin_texture", "Use at most one override texture and one override_mask texture.");
  if (!overrides.length) {
    if (masks.length) throw new ConversionError("missing_skin_override", "override_mask requires an override texture.");
    return undefined;
  }
  const embedded = (texture: { name?: string; source?: string }) => {
    if (!texture.source?.startsWith("data:image/png;base64,")) throw new ConversionError("external_skin_texture", `${texture.name}: embed the skin texture as PNG data.`);
    return texture.source.slice("data:image/png;base64,".length);
  };
  try {
    return { override: createSkinOverride(embedded(overrides[0]), masks[0] ? embedded(masks[0]) : undefined) };
  } catch (reason) {
    throw ConversionError.fromUnknown(reason, "invalid_skin_texture", "Could not read the skin override.", "textures");
  }
}
