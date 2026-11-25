import type { ImportedNodeHint, ImportedProject } from "../../domain/conversionSeed";
import { attachmentTargetId, type AnimationIR } from "../../domain/animationIR";
import { normalizeResourceLocation } from "../../format/resourceLocation";
import { readItemStack } from "../../format/minecraftData";
import { parseAnimationDuration } from "../../format/time";
import { isRecord } from "../../format/runtimeValue";
import type { ConversionIssue } from "../../foundation/diagnostics";
import { migrateAnimation } from "./schema4Migration";

export function importAnimation(value: unknown, sourceName: string): ImportedProject {
  const nodeHints: Record<string, ImportedNodeHint> = {};
  const diagnostics: ConversionIssue[] = [];
  const ir = isRecord(value) && value.schema_version === 4
    ? migrateAnimation(value, nodeHints, diagnostics)
    : structuredClone(value) as AnimationIR;
  parseAnimationDuration(ir.animation.duration, "$.animation.duration");
  for (const [id, node] of Object.entries(ir.nodes)) {
    nodeHints[id] ??= { sourceNodeId: id };
    if (!node.attachments) continue;
    for (const [attachmentId, attachment] of Object.entries(node.attachments)) {
      if ("entity_nbt" in attachment && (attachment.type === "item_display" || attachment.type === "block_display" || attachment.type === "text_display")) {
        const { entity_nbt, ...display } = attachment as typeof attachment & { entity_nbt: string };
        if (!Object.hasOwn(display, "entity_snbt")) display.entity_snbt = entity_nbt;
        node.attachments[attachmentId] = display;
      }
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
    suggestedNamespace: ir.id?.split(":")[0],
    nodeHints, resources: new Map(), diagnostics,
    animations: [{ ir, id: ir.id?.split(":")[1], sourceReferenceId: ir.id, name: ir.metadata.name,
    }],
  };
}
