import type { ImportedNodeHint, ImportedProject } from "../../domain/conversionSeed";
import { requireAnimation } from "../../format/animation";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { normalizeResourceLocation } from "../../format/resourceLocation";
import { readItemStack } from "../../format/minecraftData";

export function importAnimation(value: unknown, sourceName: string): ImportedProject {
  const ir = requireAnimation(value);
  const nodeHints: Record<string, ImportedNodeHint> = {};
  for (const [id, node] of Object.entries(ir.nodes)) {
    const attachment = Object.values(node.attachments ?? {}).find((value) => ["item_display", "block_display", "text_display"].includes(value.type));
    nodeHints[id] = {
      sourceNodeId: id,
      ...(attachment?.type === "item_display" && normalizeResourceLocation(readItemStack(attachment.item_stack_snbt).id) === "minecraft:player_head"
        ? { skinCandidate: { groupId: id } } : {}),
    };
  }
  return {
    source: "emote_json", sourceName, suggestedMetadata: ir.metadata, suggestedMinecraftVersion: ir.target_minecraft_version,
    suggestedNamespace: ir.id.split(":")[0], suggestedPlayer: ir.settings?.player ?? createDefaultPlayerBehavior(),
    suggestedStandalone: ir.settings?.standalone ?? true, suggestedCooldown: `${ir.settings?.cooldown ?? 0}s`, suggestedRotationDeadzone: ir.settings?.rotation_deadzone ?? 50,
    suggestedDisplayInterpolation: `${ir.settings?.display_interpolation_ticks ?? 1}t`, nodeHints, resources: new Map(), diagnostics: [],
    animations: [{ ir, id: ir.id.split(":")[1], sourceReferenceId: ir.id, name: ir.metadata.name,
    }],
  };
}
