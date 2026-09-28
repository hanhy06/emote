import type { EmoteEvent } from "../format/emoteAnimation";
import type { RuntimeNode, RuntimeNodeTracks } from "./minecraftData";
import type { ImportedAnimation, ImportedTimelineEvent } from "./conversionSeed";
import { remapNativeRuntimeBindings } from "./nodeBindings";
import type { PreviewProjection } from "./previewProjection";
import type { AnimationRuntimeData } from "./runtimeProjection";

export interface ImportedAnimationIdRemapper {
  editorNodeId(id: string): string;
  runtimeNodeId(id: string): string;
  editorGroupId?(id: string): string;
}

export function remapPreviewProjection(
  preview: PreviewProjection,
  ids: ImportedAnimationIdRemapper,
): PreviewProjection {
  return { ...preview, tracks: remapTracks(preview.tracks, ids.editorNodeId) };
}

export function remapAnimationRuntimeData(runtime: AnimationRuntimeData, ids: ImportedAnimationIdRemapper): AnimationRuntimeData {
  if (runtime.kind === "baked") return { kind: "baked", tracks: remapTracks(runtime.tracks, ids.editorNodeId) };
  return {
    ...runtime,
    nodes: remapRuntimeNodes(runtime.nodes, ids.runtimeNodeId),
    tracks: remapRuntimeTracks(runtime.tracks, ids.runtimeNodeId),
    bindings: remapNativeRuntimeBindings(runtime.bindings, {
      editorNodeId: ids.editorNodeId,
      editorGroupId: ids.editorGroupId ?? ids.editorNodeId,
      runtimeNodeId: ids.runtimeNodeId,
    }),
  };
}

export function remapImportedAnimationEvents(
  events: ImportedAnimation["events"],
  nodeId: (id: string) => string,
): ImportedAnimation["events"] {
  return {
    start: events.start.map((event) => remapEvent(event, nodeId)),
    timeline: events.timeline.map((event) => remapTimelineEvent(event, nodeId)),
    loop: events.loop.map((event) => remapEvent(event, nodeId)),
    stop: events.stop.map((event) => remapEvent(event, nodeId)),
  };
}

function remapTracks<T>(
  tracks: Record<string, T>,
  nodeId: (id: string) => string,
): Record<string, T> {
  return Object.fromEntries(Object.entries(tracks).map(([id, track]) => [nodeId(id), track]));
}

function remapRuntimeNodes(
  nodes: Record<string, RuntimeNode>,
  nodeId: (id: string) => string,
): Record<string, RuntimeNode> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => [
    nodeId(id),
    node.parent ? { ...node, parent: nodeId(node.parent) } : node,
  ]));
}

function remapRuntimeTracks(
  tracks: Record<string, RuntimeNodeTracks>,
  nodeId: (id: string) => string,
): Record<string, RuntimeNodeTracks> {
  return Object.fromEntries(Object.entries(tracks).map(([id, track]) => [nodeId(id), track]));
}

function remapTimelineEvent(event: ImportedTimelineEvent, nodeId: (id: string) => string): ImportedTimelineEvent {
  return { ...remapEvent(event, nodeId), tick: event.tick };
}

function remapEvent<T extends EmoteEvent>(event: T, nodeId: (id: string) => string): T {
  return {
    ...event,
    source: event.source.type === "node" ? { ...event.source, node: nodeId(event.source.node) } : event.source,
    origin: event.origin.type === "node" ? { ...event.origin, node: nodeId(event.origin.node) } : event.origin,
  };
}
