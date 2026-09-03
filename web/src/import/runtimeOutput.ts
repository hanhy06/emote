import type { EmoteNode, EmoteTimeline, LocalTransform } from "../format/emoteAnimation";
import type { ImportedNode } from "../domain/conversionSeed";
import type { RuntimeNode, RuntimeTimeline } from "../domain/minecraftData";
import { readBlockState, readDisplayNbtValue, readItemStack } from "../format/minecraftData";

export const ZERO_VECTOR = [0, 0, 0] as const;
export const ONE_VECTOR = [1, 1, 1] as const;
export const IDENTITY_TRANSFORM: LocalTransform = { position: ZERO_VECTOR, rotation: ZERO_VECTOR, scale: ONE_VECTOR };

export function importedNodeToRuntimeNode(node: ImportedNode, transform: LocalTransform, parent?: string): RuntimeNode {
  const common = {
    ...(parent ? { parent } : { space: node.space ?? "initiator" as const }),
    ...("visible" in node && !node.visible ? { visible: false } : {}),
    ...("entityNbt" in node && node.entityNbt ? { entity_nbt: node.entityNbt } : {}),
    transform,
  };
  if (node.type === "anchor") return { type: "anchor", ...common };
  if (node.type === "item_display") return { type: "item_display", ...common, itemStack: node.itemStack, item_display: node.itemDisplay };
  if (node.type === "block_display") return { type: "block_display", ...common, blockState: node.blockState };
  return { type: "text_display", ...common, text: node.text };
}

export function readRuntimeNodes(nodes: Record<string, EmoteNode>): Record<string, RuntimeNode> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]): [string, RuntimeNode] => {
    if (node.type === "block_display") {
      const { block_state_snbt, ...common } = node;
      return [id, { ...common, blockState: readBlockState(block_state_snbt) }];
    }
    if (node.type === "item_display") {
      const { item_stack_snbt, ...common } = node;
      return [id, { ...common, ...(item_stack_snbt === undefined ? {} : { itemStack: readItemStack(item_stack_snbt) }) }];
    }
    return [id, node];
  }));
}

export function readRuntimeTimeline(timeline: EmoteTimeline): RuntimeTimeline {
  return {
    ...timeline,
    tracks: Object.fromEntries(Object.entries(timeline.tracks).map(([id, track]) => {
      const { nbt, ...channels } = track;
      return [id, { ...channels, ...(nbt ? { nbt: nbt.map((frame) => ({ ...frame, value: readDisplayNbtValue(frame.value) })) } : {}) }];
    })),
  };
}
