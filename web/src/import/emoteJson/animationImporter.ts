import type { ImportedNodeHint, ImportedProject } from "../../domain/conversionSeed";
import type { AnimationIR } from "../../domain/animationIR";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { normalizeResourceLocation } from "../../format/resourceLocation";
import { readItemStack } from "../../format/minecraftData";

export function importAnimation(value: unknown, sourceName: string): ImportedProject {
  const ir = value as AnimationIR;
  const nodeHints: Record<string, ImportedNodeHint> = {};
  for (const [id, node] of Object.entries(ir.nodes)) {
    const attachment = Object.values(node.attachments ?? {}).find((value) => ["item_display", "block_display", "text_display"].includes(value.type));
    nodeHints[id] = { sourceNodeId: id };
    if (attachment?.type === "item_display" && typeof attachment.item_stack_snbt === "string" && attachment.item_stack_snbt.trim()) {
      try {
        if (normalizeResourceLocation(readItemStack(attachment.item_stack_snbt).id) === "minecraft:player_head") nodeHints[id].skinCandidate = { groupId: id };
      } catch {
        // Hints do not change the input passed to the mod.
      }
    }
  }
  return {
    source: "emote_json", sourceName, suggestedMetadata: ir.metadata, suggestedMinecraftVersion: ir.target_minecraft_version,
    suggestedNamespace: ir.id?.split(":")[0], suggestedPlayer: ir.settings?.player ?? createDefaultPlayerBehavior(),
    suggestedStandalone: ir.settings?.standalone ?? true, suggestedCooldown: ir.settings?.cooldown, suggestedRotationDeadzone: ir.settings?.rotation_deadzone ?? 50,
    suggestedDisplayInterpolation: `${ir.settings?.display_interpolation_ticks ?? 1}t`, nodeHints, resources: new Map(), diagnostics: [],
    animations: [{ ir, id: ir.id?.split(":")[1], sourceReferenceId: ir.id, name: ir.metadata.name,
    }],
  };
}
