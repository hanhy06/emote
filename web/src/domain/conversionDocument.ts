import type { ConversionIssue } from "../foundation/diagnostics";
import type { PlayerSkinPart } from "./player";
import { orderedNodeIds, type AnimationEntryIR, type AnimationIR, type AttachmentIR, type AnimationSetIR, type NodeIR, type TimelineEventIR, type TransformOperationIR } from "./animationIR";
import { normalizeAnimationTimes, normalizeSequenceTimes, remapClip, removeTinyStaticNodes } from "./animationIRConversion";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import { parseAnimationSeconds, parseMinecraftTime } from "../format/time";
import type { GeneratedResource } from "./generatedResource";
import type { EmoteCallback, EmotePlayerBehavior, SequenceStep } from "./emoteDefinition";
import type { ImportedProject, ImportedSkinPart, InputFormat } from "./conversionSeed";

export const DEFAULT_TARGET_MINECRAFT_VERSION = "26.3";

export interface SkinCandidate {
  groupId: string;
  sceneId: string;
  attachmentId: string;
  originalAttachment: AttachmentIR;
  fittingOperation?: TransformOperationIR;
}

export interface ConversionAnimation extends AnimationEntryIR {
  sourceName: string;
  sourceReferenceId?: string;
}

export type ConversionAnimationEvents = Required<NonNullable<AnimationIR["animation"]["events"]>>;

export interface SequenceOutputSettings {
  callbacks?: EmoteCallback[];
  namespace: string;
  idPath?: string;
  name: string;
  description: string;
  additionalMetadata: Record<string, unknown>;
  cooldown: string;
  player: EmotePlayerBehavior;
  sourceReferenceId?: string;
  steps?: SequenceStep[];
}

export interface ConversionDocument extends AnimationSetIR {
  origin: { source: InputFormat; sourceName: string; formatLabel: string; minecraftVersion?: string };
  animations: ConversionAnimation[];
  skinCandidates: Record<string, SkinCandidate>;
  sequence: SequenceOutputSettings;
  diagnostics: ConversionIssue[];
  resources: Map<string, GeneratedResource>;
}

export function createConversionDocument(project: ImportedProject, formatLabel: string): ConversionDocument {
  const nodes: Record<string, NodeIR> = {};
  const skinCandidates: Record<string, SkinCandidate> = {};
  const suggestions: Record<string, ImportedSkinPart> = {};
  const variants = new Map<string, string[]>();
  const reservedIds = new Set(project.animations.flatMap((animation) => Object.keys(animation.ir.nodes)));
  const additionalMetadata = Object.fromEntries(Object.entries(project.suggestedMetadata).filter(([key]) => key !== "name" && key !== "description"));
  const namespace = project.suggestedNamespace ?? "emote";
  const animations = project.animations.map((animation): ConversionAnimation => {
    const ir = structuredClone(animation.ir);
    if (project.source !== "emote_json") ir.id = `${sanitizeNamespace(namespace)}:${sanitizeResourcePath(animation.id)}`;
    if (project.source !== "emote_json") ir.metadata = animation.suggestedMetadata ?? { ...additionalMetadata, name: animation.name, description: `${animation.name} emote.` };
    ir.settings = {
      standalone: project.suggestedStandalone ?? true,
      cooldown: project.suggestedCooldown ?? "0t",
      rotation_deadzone: project.suggestedRotationDeadzone ?? 50,
      display_interpolation_ticks: parseMinecraftTime(project.suggestedDisplayInterpolation ?? "1t"),
      player: structuredClone(project.suggestedPlayer), ...ir.settings,
    };
    ir.animation.events = {
      start: ir.animation.events?.start ?? [],
      timeline: ir.animation.events?.timeline ?? [],
      loop: ir.animation.events?.loop ?? [],
      stop: ir.animation.events?.stop ?? [],
    };
    normalizeAnimationTimes(ir);
    removeTinyStaticNodes(ir, ir.animation.events);
    const ids = new Map<string, string>();
    for (const sourceId of orderedNodeIds(ir.nodes)) {
      const sourceNode = ir.nodes[sourceId];
      const importedId = typeof sourceNode.source?.editor_node_id === "string" ? sourceNode.source.editor_node_id : sourceId;
      const node = structuredClone(sourceNode);
      if (node.parent) node.parent = ids.get(node.parent)!;
      if (node.source && Object.hasOwn(node.source, "editor_node_id")) {
        delete node.source.editor_node_id;
        node.source.node_id ??= project.nodeHints[importedId]?.sourceNodeId ?? importedId;
      }
      let id = variants.get(sourceId)?.find((candidate) => JSON.stringify(nodes[candidate]) === JSON.stringify(node));
      if (!id) {
        id = sourceId;
        let suffix = 2;
        while (nodes[id] || (id !== sourceId && reservedIds.has(id))) id = `${sourceId}_${suffix++}`;
        nodes[id] = node;
        variants.set(sourceId, [...(variants.get(sourceId) ?? []), id]);
        const candidate = project.nodeHints[importedId]?.skinCandidate;
        const attachmentId = Object.keys(node.attachments ?? {}).find((key) => node.attachments![key].type === "item_display");
        if (candidate && attachmentId) {
          let operationId = "skin_conversion";
          let operationSuffix = 2;
          while (node.transform?.some((operation) => operation.id === operationId)) operationId = `skin_conversion_${operationSuffix++}`;
          skinCandidates[id] = {
            groupId: candidate.groupId, sceneId: "input", attachmentId,
            originalAttachment: structuredClone(node.attachments![attachmentId]),
            ...(candidate.fittingMatrix ? { fittingOperation: { id: operationId, op: "matrix", value: candidate.fittingMatrix } } : {}),
          };
          const suggested = candidate.suggestion;
          if (suggested) suggestions[id] = suggested;
        }
      }
      ids.set(sourceId, id);
    }
    const { nodes: _nodes, animation: sourceClip, target_minecraft_version: _version, ...fields } = ir;
    const clip = remapClip(sourceClip, (id) => ids.get(id) ?? id);
    return { ...fields, clip, nodeIds: [...ids.values()], sourceName: animation.name,
      sourceReferenceId: animation.sourceReferenceId };
  });
  let document: ConversionDocument = {
    origin: { source: project.source, sourceName: project.sourceName, formatLabel, ...(project.suggestedMinecraftVersion ? { minecraftVersion: project.suggestedMinecraftVersion } : {}) },
    targetMinecraftVersion: project.suggestedMinecraftVersion && Object.hasOwn(MINECRAFT_VERSION_PROFILES, project.suggestedMinecraftVersion)
      ? project.suggestedMinecraftVersion : DEFAULT_TARGET_MINECRAFT_VERSION,
    nodes, skinCandidates, animations,
    sequence: { namespace, name: project.suggestedMetadata.name, description: project.suggestedMetadata.description,
      additionalMetadata, cooldown: project.suggestedCooldown ?? "0t", player: project.suggestedPlayer },
    diagnostics: project.diagnostics, resources: project.resources,
  };
  normalizeSequenceTimes(document.sequence);
  for (const part of [...new Set(Object.values(suggestions).map((suggestion) => suggestion.part))]) {
    const groupIds = [...new Set(Object.entries(suggestions).filter(([, suggestion]) => suggestion.part === part)
      .sort(([, first], [, second]) => first.order - second.order).map(([id]) => skinCandidates[id].groupId))];
    document = applySkinGroups(document, part, groupIds, "input");
  }
  return document;
}

export function skinPartAssignments(document: ConversionDocument): Record<string, PlayerSkinPart | null> {
  return Object.fromEntries(Object.entries(document.skinCandidates).map(([id, candidate]) => {
    const attachment = document.nodes[id].attachments?.[candidate.attachmentId];
    return [id, attachment?.type === "player_skin" ? attachment.part : null];
  }));
}

export function skinPartOrders(document: ConversionDocument): Record<string, number | null> {
  return Object.fromEntries(Object.entries(document.skinCandidates).map(([id, candidate]) => {
    const attachment = document.nodes[id].attachments?.[candidate.attachmentId];
    if (attachment?.type !== "player_skin") return [id, null];
    return [id, orderedSkinGroups(document, attachment.part, candidate.sceneId).indexOf(candidate.groupId)];
  }));
}

export function assignSkinPart(document: ConversionDocument, selectedNodeIds: ReadonlySet<string>, part: PlayerSkinPart | null): ConversionDocument {
  const groups = new Set([...selectedNodeIds].flatMap((id) => document.skinCandidates[id] ? [document.skinCandidates[id].groupId] : []));
  if (!groups.size) return document;
  const scenes = new Set([...selectedNodeIds].flatMap((id) => document.skinCandidates[id] ? [document.skinCandidates[id].sceneId] : []));
  const targetOrders = new Map([...scenes].map((scene) => [scene, part ? orderedSkinGroups(document, part, scene) : []]));
  const previousParts = new Set<PlayerSkinPart>();
  const nodes = { ...document.nodes };
  for (const [id, candidate] of Object.entries(document.skinCandidates)) {
    if (!groups.has(candidate.groupId)) continue;
    const node = nodes[id];
    const attachment = node.attachments?.[candidate.attachmentId];
    if (attachment?.type !== "player_skin") continue;
    previousParts.add(attachment.part);
    const restored = structuredClone(candidate.originalAttachment);
    if (attachment.visible === undefined) delete restored.visible;
    else restored.visible = attachment.visible;
    if (attachment.entity_nbt === undefined) delete restored.entity_nbt;
    else restored.entity_nbt = attachment.entity_nbt;
    nodes[id] = { ...node, attachments: { ...node.attachments, [candidate.attachmentId]: restored },
      ...(part === null && candidate.fittingOperation ? { transform: node.transform?.filter((operation) => operation.id !== candidate.fittingOperation!.id) } : {}) };
  }
  let result = { ...document, nodes };
  for (const scene of scenes) {
    for (const previous of previousParts) result = applySkinGroups(result, previous, orderedSkinGroups(result, previous, scene), scene);
    const sceneGroups = [...groups].filter((group) => Object.values(document.skinCandidates).some((candidate) => candidate.sceneId === scene && candidate.groupId === group));
    if (part) {
      const order = targetOrders.get(scene)!;
      result = applySkinGroups(result, part, [...order, ...sceneGroups.filter((group) => !order.includes(group))], scene);
    }
  }
  return result;
}

export function assignSkinOrder(document: ConversionDocument, selectedNodeIds: ReadonlySet<string>, order: number): ConversionDocument {
  const selected = new Set([...selectedNodeIds].flatMap((id) => document.skinCandidates[id] ? [document.skinCandidates[id].groupId] : []));
  const scenes = new Set([...selectedNodeIds].flatMap((id) => document.skinCandidates[id] ? [document.skinCandidates[id].sceneId] : []));
  let result = document;
  for (const scene of scenes) for (const part of [...new Set(Object.values(skinPartAssignments(document)).filter((part): part is PlayerSkinPart => part !== null))]) {
    const groups = orderedSkinGroups(document, part, scene);
    const moved = groups.filter((id) => selected.has(id));
    if (!moved.length) continue;
    const others = groups.filter((id) => !selected.has(id));
    others.splice(Math.max(0, Math.min(Math.trunc(order), others.length)), 0, ...moved);
    result = applySkinGroups(result, part, others, scene);
  }
  return result;
}

function orderedSkinGroups(document: ConversionDocument, part: PlayerSkinPart, scene: string): string[] {
  return [...new Set(Object.entries(document.skinCandidates).flatMap(([id, candidate]) => {
    if (candidate.sceneId !== scene) return [];
    const attachment = document.nodes[id].attachments?.[candidate.attachmentId];
    return attachment?.type === "player_skin" && attachment.part === part ? [{ id: candidate.groupId, from: attachment.region.from }] : [];
  }).sort((first, second) => first.from - second.from).map((group) => group.id))];
}

function applySkinGroups(document: ConversionDocument, part: PlayerSkinPart, groups: string[], scene: string): ConversionDocument {
  const nodes = { ...document.nodes };
  for (const [id, candidate] of Object.entries(document.skinCandidates)) {
    if (candidate.sceneId !== scene) continue;
    const index = groups.indexOf(candidate.groupId);
    if (index < 0) continue;
    const node = nodes[id];
    const attachment = node.attachments?.[candidate.attachmentId] ?? candidate.originalAttachment;
    const fitting = candidate.fittingOperation;
    const transform = fitting ? (node.transform?.some((operation) => operation.id === fitting.id)
      ? node.transform.map((operation) => operation.id === fitting.id ? structuredClone(fitting) : operation)
      : [...(node.transform ?? []), structuredClone(fitting)]) : node.transform;
    nodes[id] = { ...node,
      attachments: { ...node.attachments, [candidate.attachmentId]: { type: "player_skin", part, region: { from: index / groups.length, to: (index + 1) / groups.length },
        ...(attachment.visible === undefined ? {} : { visible: attachment.visible }), ...(attachment.entity_nbt === undefined ? {} : { entity_nbt: attachment.entity_nbt }) } },
      ...(fitting ? { transform } : {}),
    };
  }
  return { ...document, nodes };
}

export function updateLifecycleEvents(document: ConversionDocument, animationIndex: number,
  events: Pick<ConversionAnimationEvents, "start" | "loop" | "stop"> & { callbacks: EmoteCallback[] }): ConversionDocument {
  const animation = document.animations[animationIndex];
  if (!animation) return document;
  const { callbacks, ...commandEvents } = events;
  return updateAnimation(document, animationIndex, { ...animation, callbacks: structuredClone(callbacks),
    clip: { ...animation.clip, events: { ...structuredClone(commandEvents), timeline: structuredClone(animation.clip.events?.timeline ?? []) } } });
}

export function replaceTimelineEvents(document: ConversionDocument, animationIndex: number, events: TimelineEventIR[]): ConversionDocument {
  const animation = document.animations[animationIndex];
  if (!animation) return document;
  return updateAnimation(document, animationIndex, { ...animation,
    clip: { ...animation.clip, events: { ...animation.clip.events, timeline: structuredClone(events).sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time)) } } });
}

export function updateAnimation(document: ConversionDocument, animationIndex: number, animation: AnimationEntryIR): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  const normalized = structuredClone(animation);
  normalizeAnimationTimes(normalized);
  return { ...document, animations: document.animations.map((entry, index) => index === animationIndex ? { ...entry, ...normalized } : entry) };
}
