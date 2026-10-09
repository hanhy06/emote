import type { AnimationIR, NbtPatchIR } from "./animationIR";
import type { ItemStackData } from "./minecraftData";
import { readDisplayNbt, readItemStack } from "../format/minecraftData";
import { readSnbtString } from "../format/snbt";

export type GeneratedResource =
  | Uint8Array
  | { kind: "cuboid_model"; textures: Record<string, string>; elements: Record<string, unknown>[] }
  | { kind: "item_model"; model: string }
  | { kind: "json"; value: unknown };

export function itemModelResourcePath(namespace: string, modelPath: string): string {
  return `assets/${namespace}/items/${modelPath}.json`;
}

export function referencedItemModelResources(ir: AnimationIR): Set<string> {
  const references = new Set<string>();
  const collect = (item: Partial<ItemStackData>) => {
    for (const component of item.components ?? []) {
      if (component.name !== "minecraft:item_model") continue;
      const location = readSnbtString(component.value) ?? component.value;
      const separator = location.indexOf(":");
      references.add(itemModelResourcePath(separator < 0 ? "minecraft" : location.slice(0, separator), location.slice(separator + 1)));
    }
  };
  for (const node of Object.values(ir.nodes)) for (const attachment of Object.values(node.attachments ?? {})) {
    if (attachment.type === "item_display") collect(readItemStack(attachment.item_stack_snbt));
    if (attachment.entity_nbt) {
      const item = readDisplayNbt(attachment.entity_nbt).itemStack;
      if (item) collect(item);
    }
  }
  for (const track of ir.animation.tracks) {
    if (track.channel !== "nbt" || track.driver.type !== "state") continue;
    for (const key of track.driver.keys) {
      const patch = key.value as NbtPatchIR;
      if (typeof patch.merge !== "string") continue;
      const item = readDisplayNbt(patch.merge).itemStack;
      if (item) collect(item);
    }
  }
  return references;
}
