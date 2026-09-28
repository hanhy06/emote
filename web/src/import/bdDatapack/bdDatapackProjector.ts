import type { BakedRuntimeNodeTracks } from "../../domain/minecraftData";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { sanitizeResourcePath } from "../../format/resourceLocation";
import type { BdDatapackSource, BdSourceDisplay } from "./bdDatapackSource";

export function projectBdDatapack(source: BdDatapackSource, sourceName: string): ImportedProject {
  const name = prettify(source.namespace);
  const nodes = Object.fromEntries(source.displays.map((display) => [display.id, projectNode(display)]));
  return {
    source: "bd_datapack",
    sourceName,
    suggestedMetadata: { name, description: `${name} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: source.namespace,
    nodes,
    animations: source.animations.map((animation): ImportedAnimation => {
      const runtimeTracks: Record<string, BakedRuntimeNodeTracks> = Object.fromEntries(source.displays.map((display) => [display.id, {
        transforms: animation.transforms[display.id].map((frame) => ({ ...frame, interpolation: { ...frame.interpolation } })),
        visibility: [],
        nbt: animation.nbt[display.id].map((frame) => ({ ...frame, value: { ...frame.value } })),
      }]));
      const previewTracks = Object.fromEntries(source.displays.map((display) => [display.id, {
        transforms: animation.transforms[display.id].map((frame) => ({ ...frame, interpolation: { ...frame.interpolation } })),
        visibility: [],
      }]));
      return {
        id: sanitizeResourcePath(animation.name, "default"),
        name: prettify(animation.name),
        durationTicks: animation.durationTicks,
        playbackMode: "loop",
        loopDelayTicks: 0,
        events: { start: [], timeline: [], loop: [], stop: [] },
        preview: { durationTicks: animation.durationTicks, tracks: previewTracks, availability: { status: "full" } },
        exportAvailability: { exportable: true },
        runtime: { kind: "baked", tracks: runtimeTracks },
      };
    }),
    diagnostics: [...source.diagnostics, ...(source.droppedCamera ? [{
      severity: "warning",
      code: "bd_datapack_camera_ignored",
      message: "BD Engine camera movement is not part of the emote format and was ignored.",
      sourcePath: "data/*/function/k/*/keyframe_*.mcfunction",
    } as const] : [])],
    resources: new Map(),
  };
}

function projectNode(display: BdSourceDisplay): ImportedNode {
  const common = {
    binding: { sourceNodeId: display.id, skinGroupId: display.id },
    defaultMatrix: display.defaultMatrix,
    visible: true,
    ...(display.entityNbt ? { entityNbt: display.entityNbt } : {}),
  };
  if (display.type === "item_display") {
    return { ...common, type: "item_display", itemStack: display.itemStack, itemDisplay: display.itemDisplay };
  }
  if (display.type === "block_display") return { ...common, type: "block_display", blockState: display.blockState };
  return { ...common, type: "text_display", text: display.text };
}

function prettify(value: string): string {
  return value.replaceAll("_", " ").replace(/\b\w/g, (character) => character.toUpperCase());
}
