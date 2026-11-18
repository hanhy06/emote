import { ConversionError } from "../foundation/diagnostics";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import type { GeneratedResource } from "./generatedResource";
import { animationEntries, DEFAULT_TARGET_MINECRAFT_VERSION, type ConversionAnimation, type ConversionDocument, type ImportedConversionDocument, type SkinCandidate } from "./conversionDocument";
import { attachmentTargetId, type NodeIR } from "./animationIR";
import { remapClip } from "./animationIRConversion";

export function combineConversionDocuments(documents: readonly ImportedConversionDocument[]): ConversionDocument {
  if (!documents.length) throw new ConversionError("empty_import", "No Emote files were imported.");
  const sequences = documents.flatMap((document) => document.entries.filter((entry) => entry.type === "sequence"));
  if (sequences.length > 1) throw new ConversionError("multiple_sequences", "Open at most one sequence at a time.");

  const animationDocuments = documents.filter((document) => document.origin.source !== "emote_sequence");
  const first = animationDocuments[0] ?? documents[0];
  const sequenceVersion = documents.find((document) => document.entries.some((entry) => entry.type === "sequence"))?.targetMinecraftVersion;
  const targetMinecraftVersion = sequenceVersion && Object.hasOwn(MINECRAFT_VERSION_PROFILES, sequenceVersion)
    ? sequenceVersion : animationDocuments[0]?.targetMinecraftVersion ?? DEFAULT_TARGET_MINECRAFT_VERSION;
  const origin = {
    ...first.origin,
    sourceName: documents.map((document) => document.origin.sourceName).join(", "),
    formatLabel: [...new Set(documents.map((document) => document.origin.formatLabel))].join(", "),
    namespace: animationDocuments.length > 1 ? "emote" : first.origin.namespace,
  };
  const diagnostics = documents.flatMap((document) => document.diagnostics);
  if (animationDocuments.length <= 1) {
    return { ...first, origin, diagnostics, targetMinecraftVersion, entries: [...animationEntries(first), ...sequences] };
  }

  const nodes: Record<string, NodeIR> = {};
  const skinCandidates: Record<string, SkinCandidate> = {};
  const animations: ConversionAnimation[] = [];
  const animationIds = new Set<string>();
  const reservedAnimationIds = new Set(animationDocuments.flatMap((document) => animationEntries(document).map((animation) => {
    const separator = animation.id.indexOf(":");
    return `${sanitizeNamespace(animation.id.slice(0, separator))}:${sanitizeResourcePath(animation.id.slice(separator + 1))}`;
  })));
  const resources = new Map(first.resources);

  animationDocuments.forEach((document, index) => {
    const prefix = `input_${index + 1}__`;
    const prefixId = (id: string) => `${prefix}${id}`;

    for (const [id, node] of Object.entries(document.nodes)) {
      nodes[prefixId(id)] = {
        ...node,
        ...(node.parent ? { parent: prefixId(node.parent) } : {}),
      };
    }
    for (const candidate of Object.values(document.skinCandidates)) {
      const nodeId = prefixId(candidate.nodeId);
      skinCandidates[attachmentTargetId(nodeId, candidate.attachmentId)] = { ...candidate, nodeId, groupId: prefixId(candidate.groupId), sceneId: prefixId(candidate.sceneId) };
    }
    animations.push(...animationEntries(document).map((animation) => {
      const separator = animation.id.indexOf(":");
      const namespace = animation.id.slice(0, separator);
      const path = uniqueAnimationId(namespace, animation.id.slice(separator + 1), animationIds, reservedAnimationIds);
      return {
        ...animation,
        id: `${namespace}:${path}`,
        nodeIds: animation.nodeIds.map(prefixId),
        clip: remapClip(animation.clip, prefixId),
      };
    }));
    if (index > 0) mergeResources(resources, document.resources);
  });

  return { origin, targetMinecraftVersion, nodes, skinCandidates, entries: [...animations, ...sequences], diagnostics, resources };
}

function uniqueAnimationId(namespace: string, sourceId: string, usedIds: Set<string>, reservedIds: ReadonlySet<string>): string {
  let id = sourceId;
  let suffix = 2;
  while (usedIds.has(`${sanitizeNamespace(namespace)}:${sanitizeResourcePath(id)}`)
    || id !== sourceId && reservedIds.has(`${sanitizeNamespace(namespace)}:${sanitizeResourcePath(id)}`)) id = `${sourceId}_${suffix++}`;
  usedIds.add(`${sanitizeNamespace(namespace)}:${sanitizeResourcePath(id)}`);
  return id;
}

function mergeResources(target: Map<string, GeneratedResource>, source: ReadonlyMap<string, GeneratedResource>): void {
  for (const [path, resource] of source) {
    if (target.has(path) && !sameResource(target.get(path)!, resource)) {
      throw new ConversionError("conflicting_import_resource", `Multiple inputs generate the same resource path: ${path}`, path);
    }
    target.set(path, resource);
  }
}

function sameResource(first: GeneratedResource, second: GeneratedResource): boolean {
  if (first instanceof Uint8Array || second instanceof Uint8Array) {
    return first instanceof Uint8Array && second instanceof Uint8Array
      && first.length === second.length && first.every((value, index) => value === second[index]);
  }
  return JSON.stringify(first) === JSON.stringify(second);
}
