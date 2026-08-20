import type { EmoteNode, LocalTransform } from "../format/emoteAnimation";
import type { ImportedNode } from "../domain/conversionSeed";

export const ZERO_VECTOR = [0, 0, 0] as const;
export const ONE_VECTOR = [1, 1, 1] as const;
export const IDENTITY_TRANSFORM: LocalTransform = { position: ZERO_VECTOR, rotation: ZERO_VECTOR, scale: ONE_VECTOR };

export function importedNodeToRuntimeNode(node: ImportedNode, transform: LocalTransform, parent?: string): EmoteNode {
  const common = {
    ...(parent ? { parent } : { space: node.space ?? "initiator" as const }),
    ...("visible" in node && !node.visible ? { visible: false } : {}),
    ...("entityNbt" in node && node.entityNbt ? { entity_nbt: node.entityNbt } : {}),
    transform,
  };
  if (node.type === "anchor") return { type: "anchor", ...common };
  if (node.type === "item_display") return { type: "item_display", ...common, item_stack_snbt: node.itemStackSnbt, item_display: node.itemDisplay };
  if (node.type === "block_display") return { type: "block_display", ...common, block_state_snbt: node.blockStateSnbt };
  return { type: "text_display", ...common, text: node.text };
}
