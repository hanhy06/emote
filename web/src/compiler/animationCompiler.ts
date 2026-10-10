import type { AnimationIR, NbtPatchIR } from "../domain/animationIR";
import type { ConversionDocument } from "../domain/conversionDocument";
import { referencedItemModelResources } from "../domain/generatedResource";
import { minecraftVersionProfile } from "../format/minecraftVersionProfiles";
import { readBlockState, readDisplayNbt, writeBlockState, writeDisplayNbt } from "../format/minecraftData";
import { parseSnbtCompound } from "../format/snbt";
import type { AnimationJson } from "../format/animation";
import { EMOTE_SCHEMA_VERSION } from "../format/emote";
import { ConversionError } from "../foundation/diagnostics";
import { removeRedundantKeyframes } from "../domain/animationIRConversion";

export function compileAnimation(document: ConversionDocument, index: number, standalone?: boolean): { animation: AnimationJson; generatedResourceReferences: ReadonlySet<string> } {
  const entry = document.animations[index];
  if (!entry) throw new ConversionError("unknown_animation", `Animation ${index + 1} does not exist.`);
  const nodeIds = new Set(entry.nodeIds);
  for (const track of entry.clip.tracks) nodeIds.add(track.target.node);
  for (const phase of Object.values(entry.clip.events ?? {})) for (const event of phase ?? []) {
    if (event.source.type === "node") nodeIds.add(event.source.node);
    if (event.origin.type === "node") nodeIds.add(event.origin.node);
  }
  for (const id of nodeIds) {
    const node = document.nodes[id];
    if (node?.parent) nodeIds.add(node.parent);
  }
  const ir: AnimationIR = structuredClone({
    id: entry.id, metadata: entry.metadata, settings: entry.settings, callbacks: entry.callbacks,
    nodes: Object.fromEntries([...nodeIds].map((id) => [id, document.nodes[id]])), animation: entry.clip,
    resources: entry.resources, source: entry.source, target_minecraft_version: document.targetMinecraftVersion,
  });
  if (standalone !== undefined) ir.settings = { ...ir.settings, standalone };
  const generatedResourceReferences = new Set([...referencedItemModelResources(ir)].filter((path) => document.resources.has(path)));
  const profile = minecraftVersionProfile(document.targetMinecraftVersion);
  function formatNbt(value: string): string {
    try {
      const patch = readDisplayNbt(value);
      return patch.blockState ? writeDisplayNbt(patch, profile) : value;
    } catch {
      return value;
    }
  }
  for (const node of Object.values(ir.nodes)) for (const attachment of Object.values(node.attachments ?? {})) {
    if (attachment.type === "block_display" && typeof attachment.block_state_snbt === "string" && attachment.block_state_snbt.trim()) {
      try {
        if (!parseSnbtCompound(attachment.block_state_snbt).some((field) => field.name === profile.blockState.idKey)) attachment.block_state_snbt = writeBlockState(readBlockState(attachment.block_state_snbt), profile);
      } catch {
        // Leave unparseable input for the mod loader.
      }
    }
    if (attachment.entity_nbt) attachment.entity_nbt = formatNbt(attachment.entity_nbt);
  }
  for (const track of ir.animation.tracks) {
    if (track.channel !== "nbt" || track.driver.type !== "state") continue;
    for (const key of track.driver.keys) {
      const patch = key.value as NbtPatchIR;
      if (typeof patch.merge === "string") patch.merge = formatNbt(patch.merge);
    }
  }
  removeRedundantKeyframes(ir);
  return { animation: { ...ir, type: "animation", schema_version: EMOTE_SCHEMA_VERSION }, generatedResourceReferences };
}
