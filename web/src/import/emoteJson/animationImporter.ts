import type { ImportedNodeHint, ImportedProject } from "../../domain/conversionSeed";
import { attachmentTargetId, type AnimationIR } from "../../domain/animationIR";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { normalizeResourceLocation } from "../../format/resourceLocation";
import { readItemStack } from "../../format/minecraftData";
import { isRecord } from "../../format/runtimeValue";
import type { ConversionIssue } from "../../foundation/diagnostics";
import { migrateAnimation } from "./schema4Migration";

export function importAnimation(value: unknown, sourceName: string): ImportedProject {
  const nodeHints: Record<string, ImportedNodeHint> = {};
  const diagnostics: ConversionIssue[] = [];
  const ir = isRecord(value) && value.schema_version === 4 ? migrateAnimation(value, nodeHints, diagnostics) : value as AnimationIR;
  for (const [id, node] of Object.entries(ir.nodes)) {
    nodeHints[id] ??= { sourceNodeId: id };
    for (const [attachmentId, attachment] of Object.entries(node.attachments ?? {})) {
      if (attachment.type === "player_skin") {
        (nodeHints[id].skinCandidates ??= {})[attachmentId] = { groupId: attachmentTargetId(id, attachmentId) };
        continue;
      }
      if (attachment.type !== "item_display" || !attachment.item_stack_snbt?.trim()) continue;
      try {
        if (normalizeResourceLocation(readItemStack(attachment.item_stack_snbt).id) === "minecraft:player_head") {
          (nodeHints[id].skinCandidates ??= {})[attachmentId] ??= { groupId: attachmentTargetId(id, attachmentId) };
        }
      } catch {
        // Hints do not change the input passed to the mod.
      }
    }
  }
  return {
    source: "emote_json", sourceName, suggestedMetadata: ir.metadata, suggestedMinecraftVersion: ir.target_minecraft_version,
    suggestedNamespace: ir.id?.split(":")[0], suggestedPlayer: ir.settings?.player ?? createDefaultPlayerBehavior(),
    suggestedStandalone: ir.settings?.standalone ?? true, suggestedCooldown: ir.settings?.cooldown, suggestedRotationDeadzone: ir.settings?.rotation_deadzone ?? 50,
    nodeHints, resources: new Map(), diagnostics,
    animations: [{ ir, id: ir.id?.split(":")[1], sourceReferenceId: ir.id, name: ir.metadata.name,
    }],
  };
}
