import type { ImportedNode } from "../../domain/conversionSeed";
import type { DisplayNbtPatch } from "../../domain/minecraftData";
import { readDisplayNbt } from "../../format/minecraftData";

export const ZERO_VECTOR = [0, 0, 0] as const;

const DISPLAY_NBT_DEFAULTS: Readonly<Record<string, string>> = {
  billboard: '"fixed"', view_range: "1f", shadow_radius: "0f", shadow_strength: "1f", width: "0f", height: "0f", glow_color_override: "-1",
  Glowing: "0b", Silent: "0b", NoGravity: "0b", Invulnerable: "0b",
};
const TEXT_DISPLAY_NBT_DEFAULTS: Readonly<Record<string, string>> = {
  line_width: "200", background: "1073741824", text_opacity: "-1b", shadow: "0b", see_through: "0b", default_background: "0b", alignment: '"center"',
};

export function initialDisplayNbt(node: ImportedNode, patches: readonly DisplayNbtPatch[], atZero?: DisplayNbtPatch): DisplayNbtPatch {
  if (node.type === "anchor") throw new Error("Anchor nodes do not support NBT tracks.");
  const source = readDisplayNbt(node.entityNbt ?? "{}");
  const sourceFields = new Map([...source.rawFields, ...(atZero?.rawFields ?? [])].map((field) => [field.name, field.value]));
  const names = new Set(patches.flatMap((patch) => patch.rawFields.map((field) => field.name)));
  const rawFields = [...names].map((name) => {
    const value = sourceFields.get(name) ?? DISPLAY_NBT_DEFAULTS[name] ?? (node.type === "text_display" ? TEXT_DISPLAY_NBT_DEFAULTS[name] : undefined)
      ?? (name === "item_display" && node.type === "item_display" ? JSON.stringify(node.itemDisplay) : undefined)
      ?? (name === "text" && node.type === "text_display" ? JSON.stringify(node.text) : undefined);
    if (value === undefined) throw new Error(`NBT track field ${name} requires an explicit initial value in the source node.`);
    return { name, value };
  });
  return {
    rawFields,
    ...(patches.some((patch) => patch.itemStack !== undefined) && node.type === "item_display" ? { itemStack: { ...node.itemStack, ...source.itemStack, ...atZero?.itemStack } } : {}),
    ...(patches.some((patch) => patch.blockState !== undefined) && node.type === "block_display" ? { blockState: { ...node.blockState, ...source.blockState, ...atZero?.blockState } } : {}),
  };
}
