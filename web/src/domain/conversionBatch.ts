import { ConversionError } from "../foundation/diagnostics";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import type { GeneratedResource } from "./generatedResource";
import type { ConversionAnimation, ConversionDocument, SkinCandidate } from "./conversionDocument";
import type { NodeIR } from "./animationIR";
import { remapClip } from "./animationIRConversion";
import type { ImportedSequence } from "./emoteDefinition";

export function combineConversionDocuments(documents: readonly ConversionDocument[], importedSequences: readonly ImportedSequence[] = []): ConversionDocument {
  if (documents.length === 0) throw new ConversionError("empty_import", "No animation projects were imported.");
  if (importedSequences.length > 1) throw new ConversionError("multiple_sequences", "Open at most one sequence at a time.");
  if (documents.length === 1) return applyImportedSequence(documents[0], importedSequences[0]);

  const nodes: Record<string, NodeIR> = {};
  const skinCandidates: Record<string, SkinCandidate> = {};
  const animations: ConversionAnimation[] = [];
  const animationIds = new Set<string>();
  const resources = new Map(documents[0].resources);

  documents.forEach((document, index) => {
    const prefix = `input_${index + 1}__`;
    const nodeId = (id: string) => `${prefix}${id}`;
    const groupId = (id: string) => `${prefix}${id}`;

    for (const [id, node] of Object.entries(document.nodes)) {
      nodes[nodeId(id)] = {
        ...node,
        ...(node.parent ? { parent: nodeId(node.parent) } : {}),
      };
    }
    for (const [id, candidate] of Object.entries(document.skinCandidates)) {
      skinCandidates[nodeId(id)] = { ...candidate, groupId: groupId(candidate.groupId), sceneId: groupId(candidate.sceneId) };
    }
    animations.push(...document.animations.map((animation) => {
      const separator = animation.id.indexOf(":");
      const id = uniqueAnimationId(animation.id.slice(0, separator), animation.id.slice(separator + 1), animationIds);
      return {
        ...animation,
        id: `${animation.id.slice(0, separator)}:${id}`,
        nodeIds: animation.nodeIds.map(nodeId),
        clip: remapClip(animation.clip, nodeId),
      };
    }));
    if (index > 0) mergeResources(resources, document.resources);
  });

  const first = documents[0];
  return applyImportedSequence({
    ...first,
    origin: {
      ...first.origin,
      sourceName: documents.map((document) => document.origin.sourceName).join(", "),
      formatLabel: [...new Set(documents.map((document) => document.origin.formatLabel))].join(", "),
    },
    nodes,
    skinCandidates,
    animations,
    sequence: { ...first.sequence, namespace: "emote" },
    diagnostics: documents.flatMap((document) => document.diagnostics),
    resources,
  }, importedSequences[0]);
}

function applyImportedSequence(document: ConversionDocument, sequence: ImportedSequence | undefined): ConversionDocument {
  if (!sequence) return document;
  const separator = sequence.id.indexOf(":");
  return {
    ...document,
    targetMinecraftVersion: sequence.targetMinecraftVersion && Object.hasOwn(MINECRAFT_VERSION_PROFILES, sequence.targetMinecraftVersion)
      ? sequence.targetMinecraftVersion : document.targetMinecraftVersion,
    sequence: {
      namespace: sequence.id.slice(0, separator),
      idPath: sequence.id.slice(separator + 1),
      name: sequence.metadata.name,
      description: sequence.metadata.description,
      additionalMetadata: Object.fromEntries(Object.entries(sequence.metadata).filter(([key]) => key !== "name" && key !== "description")),
      cooldown: sequence.cooldown,
      player: sequence.player,
      steps: sequence.steps,
      callbacks: sequence.callbacks?.map((callback) => ({ ...callback })),
    },
  };
}

function uniqueAnimationId(namespace: string, sourceId: string, usedIds: Set<string>): string {
  let id = sourceId;
  let suffix = 2;
  while (usedIds.has(`${sanitizeNamespace(namespace)}:${sanitizeResourcePath(id)}`)) id = `${sourceId}_${suffix++}`;
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
