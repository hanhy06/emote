import type { ParsedFrame } from "gifuct-js";
import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type Matrix16 } from "../../format/emoteAnimation";
import { IDENTITY_MATRIX, matrix4ToRowMajor } from "../../format/matrix";
import { parseResourceLocation, sanitizeResourcePath, type ResourceLocation } from "../../format/resourceLocation";
import { isRecord } from "../../format/runtimeValue";
import { serializeSnbtCompound, serializeSnbtString } from "../../format/snbt";
import { formatMinecraftTime, requireAnimationDurationTicks, secondsToTicks, TICKS_PER_SECOND } from "../../format/time";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { parseInputJson } from "../inputCache";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart, ImportedTimelineEvent, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import { ConversionError } from "../../foundation/diagnostics";
import { bakeAjNodeChannels, evaluateAjMolang, requiresAjBaking, type AjTransformValues } from "./animatedJavaAnimationBaker";
import { requireAjBlueprint, type AjAnimation, type AjBlueprint, type AjElement, type AjKeyframe, type AjNode, type AjNodeChannels, type AjTextureAnimation } from "./animatedJavaSchema";
import { blockArgumentToSnbt, itemArgumentToSnbt } from "./animatedJavaDisplayArguments";
import { createAjBlueprintRuntime, type AjPoseTarget } from "./animatedJavaAnimationOutput";
import { humanoidSkinPartHeight, humanoidSkinSlices, inferHumanoidPart, isStandardHumanoidPartSize, sliceVerticalUv } from "../humanoid/humanoidPlayerRig";

const encoder = new TextEncoder();
const MINECRAFT_TICK_MILLISECONDS = 50;
const MAX_GIF_FRAME_COUNT = 1_024;
const MAX_GIF_ATLAS_DIMENSION = 16_384;
const MAX_GIF_ATLAS_AREA = 67_108_864;

interface ImportedAjNodes {
  nodes: Record<string, ImportedNode>;
  targetsBySource: Map<string, AjPoseTarget[]>;
  itemModelsByNodeAndPaletteState: Map<string, AjItemModelVariants>;
}

interface AjItemModelVariants {
  models: Map<string, string>;
  enchanted: boolean;
}

interface PreparedCustomTexture {
  bytes: Uint8Array;
  animation?: AjTextureAnimation;
}

type SupportedImageType = "png" | "jpeg" | "gif";

interface AjPaletteConfiguration {
  key: string;
  states: Record<string, string>;
}

interface AjSkinCandidate {
  nodeId: string;
  part: ImportedSkinPart["part"];
  centerY: number;
  sourceOrder: number;
}

interface IndexedAjKeyframes {
  time: number;
  position?: AjKeyframe;
  rotation?: AjKeyframe;
  scale?: AjKeyframe;
}

export const animatedJavaJsonAdapter: ImportAdapter<ImportedProject> = {
  id: "animated_java_json",
  label: "Animated Java Plugin Blueprint JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    try {
      const parsed = parseInputJson(input);
      const value = parsed as Partial<AjBlueprint>;
      return value.format_version === 1 && typeof value.settings?.id === "string" && isRecord(value.nodes) && isRecord(value.animations)
        ? { confidence: 100, reason: "matches Animated Java plugin blueprint format 1" }
        : { confidence: 0, reason: "does not match Animated Java plugin blueprint format 1" };
    } catch {
      return { confidence: 0, reason: "not JSON" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const parsed = parseInputJson(input);
    const blueprint = requireAjBlueprint(parsed);
    validateRoot(blueprint);
    const resource = parseResourceLocation(blueprint.settings.id, "Animated Java settings.id");
    const resources = new Map<string, Uint8Array>();
    const preparedTextures = await prepareCustomTextures(blueprint);
    const paletteConfigurations = collectPaletteConfigurations(blueprint);
    const { nodes, targetsBySource, itemModelsByNodeAndPaletteState } = importNodes(
      blueprint,
      resource,
      resources,
      paletteConfigurations,
      preparedTextures,
    );
    const diagnostics: ImportDiagnostic[] = [];
    const animations = Object.entries(blueprint.animations ?? {}).map(([id, animation]) => {
      try {
        return importAnimation(id, animation, nodes, targetsBySource, blueprint, itemModelsByNodeAndPaletteState);
      } catch (reason) {
        if (!(reason instanceof ConversionError) || reason.code !== "unsupported_animated_java_molang") throw reason;
        const message = `${id}: preview uses the Create pose; runtime Molang is preserved.`;
        diagnostics.push({
          severity: "warning",
          code: "animated_java_animation_molang_unavailable",
          message,
          sourcePath: reason.sourcePath ?? `animations.${id}`,
        });
        return createPreviewOnlyAnimation(
          id,
          animation,
          message,
          nodes,
          targetsBySource,
          blueprint,
          itemModelsByNodeAndPaletteState,
        );
      }
    });
    if (Object.keys(nodes).length === 0) throw new Error("Animated Java blueprint does not contain nodes.");
    if (animations.length === 0) throw new Error("Animated Java blueprint does not contain animations.");
    const name = prettify(resource.path.split("/").at(-1) ?? resource.path);
    return {
      source: "animated_java_json",
      sourceName: input.name,
      suggestedMetadata: { name, description: `${name} emote.` },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes,
      animations,
      diagnostics,
      resources,
      ...(resources.size ? { resourceMinecraftVersion: "26.2" } : {}),
    };
  },
};

function createPreviewOnlyAnimation(
  id: string,
  animation: AjAnimation,
  reason: string,
  nodes: Record<string, ImportedNode>,
  targetsBySource: ReadonlyMap<string, readonly AjPoseTarget[]>,
  blueprint: AjBlueprint,
  itemModelsByNodeAndPaletteState: ReadonlyMap<string, AjItemModelVariants>,
): ImportedAnimation {
  const animationDurationTicks = Number.isFinite(animation.length) && animation.length > 0
    ? Math.max(1, Math.round(animation.length * TICKS_PER_SECOND))
    : TICKS_PER_SECOND;
  const startDelayTicks = secondsToTicks(numericExpression(animation.start_delay ?? "0", `${id}.start_delay`), `${id}.start_delay`);
  const durationTicks = requireAnimationDurationTicks(animationDurationTicks + startDelayTicks, `${id} duration`);
  const runtime = createAjBlueprintRuntime(animation, durationTicks, targetsBySource, nodes);
  const callbacks = compileAjCallbackEvents(id, animation, startDelayTicks, durationTicks, blueprint);
  if (callbacks.length > 0) {
    runtime.timeline.events = { timeline: callbacks.map(({ tick, ...event }) => ({ ...event, time: formatMinecraftTime(tick) })) };
  }
  const textureTracks: ImportedAnimation["tracks"] = {};
  addTextureNbtTracks(
    id,
    animation,
    startDelayTicks,
    animationDurationTicks,
    blueprint,
    itemModelsByNodeAndPaletteState,
    textureTracks,
  );
  for (const [nodeId, track] of Object.entries(textureTracks)) {
    runtime.timeline.tracks[nodeId] = {
      ...runtime.timeline.tracks[nodeId],
      nbt: track.nbt.map((frame) => ({ time: formatMinecraftTime(frame.tick), value: frame.value })),
    };
  }
  return {
    id: sanitizeResourcePath(id, "default"),
    name: prettify(id),
    durationTicks,
    loop: animation.loop_mode.type,
    loopDelayTicks: 0,
    tracks: {},
    events: { start: [], timeline: callbacks, loop: [], stop: [] },
    availability: { preview: "create_pose", exportable: true, reason },
    preview: { durationTicks: TICKS_PER_SECOND, tracks: {} },
    runtime,
  };
}

function validateRoot(blueprint: AjBlueprint): void {
  if (blueprint.format_version !== 1) throw new Error(`Unsupported Animated Java plugin blueprint version: ${blueprint.format_version}`);
  parseResourceLocation(blueprint.settings?.id, "Animated Java settings.id");
}

function collectPaletteConfigurations(blueprint: AjBlueprint): AjPaletteConfiguration[] {
  const defaults = Object.fromEntries(Object.entries(blueprint.texture_palettes ?? {})
    .sort(([first], [second]) => first.localeCompare(second))
    .map(([paletteId, palette]) => {
      if (!palette.states[palette.active_state]) {
        throw new Error(`Animated Java texture palette ${paletteId} references unknown active state ${palette.active_state}.`);
      }
      return [paletteId, palette.active_state];
    }));
  const configurations = new Map<string, Record<string, string>>();
  const add = (states: Record<string, string>) => configurations.set(paletteStateKey(states), { ...states });
  add(defaults);
  for (const animation of Object.values(blueprint.animations ?? {})) {
    const states = { ...defaults };
    for (const [, frame] of sortedTextureKeyframes(animation)) {
      for (const [paletteId, stateId] of Object.entries(frame)) {
        const palette = blueprint.texture_palettes?.[paletteId];
        if (!palette) throw new Error(`Animated Java texture keyframe references unknown palette ${paletteId}.`);
        if (!palette.states[stateId]) throw new Error(`Animated Java texture palette ${paletteId} has no state ${stateId}.`);
        states[paletteId] = stateId;
      }
      add(states);
    }
  }
  return [...configurations].map(([key, states]) => ({ key, states }));
}

function sortedTextureKeyframes(animation: AjAnimation): [number, Record<string, string>][] {
  return Object.entries(animation.global_keyframes?.texture ?? {})
    .map(([time, frame]) => [Number(time), frame] as [number, Record<string, string>])
    .sort(([first], [second]) => first - second);
}

function paletteStateKey(states: Record<string, string>): string {
  return JSON.stringify(Object.entries(states).sort(([first], [second]) => first.localeCompare(second)));
}

function boneItemStackSnbt(namespace: string, modelPath: string, enchanted = false): string {
  const components: [string, string][] = [
    ["minecraft:item_model", serializeSnbtString(`${namespace}:${modelPath}`)],
  ];
  if (enchanted) components.push(["minecraft:enchantment_glint_override", "1b"]);
  return serializeSnbtCompound([
    ["id", serializeSnbtString("minecraft:paper")],
    ["count", "1"],
    ["components", serializeSnbtCompound(components)],
  ]);
}

function importNodes(
  blueprint: AjBlueprint,
  resource: ResourceLocation,
  resources: Map<string, Uint8Array>,
  paletteConfigurations: AjPaletteConfiguration[],
  preparedTextures: ReadonlyMap<string, PreparedCustomTexture>,
): ImportedAjNodes {
  const nodes: Record<string, ImportedNode> = {};
  const generatedIdsBySource = new Map<string, string[]>();
  const skinCandidates: AjSkinCandidate[] = [];
  const usedIds = new Set(Object.keys(blueprint.nodes ?? {}));
  const itemModelsByNodeAndPaletteState = new Map<string, AjItemModelVariants>();
  const lowerJointByUpper = findAjLowerJoints(blueprint);
  const jointTargets: { upperId: string; lowerId: string; upperTargets: string[]; lowerTargets: string[] }[] = [];
  let sourceOrder = 0;
  for (const [id, node] of Object.entries(blueprint.nodes ?? {})) {
    if (node.type !== "bone") {
      nodes[id] = importDisplayNode(id, node);
      generatedIdsBySource.set(id, [id]);
      continue;
    }

    const defaultMatrix = readDefaultMatrix(node, id);
    const entityNbt = displayPropertiesToNbt(node.display_properties, node.type);
    const enchanted = node.display_properties?.is_enchanted === true;
    const part = inferAjSkinPart(id);
    const lowerId = lowerJointByUpper.get(id);
    const elements = (node.elements ?? []).flatMap((element) => splitTallAjSkinElement(element, part, lowerId !== undefined));
    if (elements.length === 0) {
      nodes[id] = { id, type: "anchor", defaultMatrix };
      generatedIdsBySource.set(id, [id]);
      continue;
    }

    const generatedIds: string[] = [];
    const upperTargets: string[] = [];
    const lowerTargets: string[] = [];
    for (const [elementIndex, prepared] of elements.entries()) {
      const element = prepared.element;
      const nodeId = elementIndex === 0 ? id : uniqueAjElementNodeId(id, elementIndex, usedIds);
      const modelPath = [resource.path, nodeId].filter(Boolean).join("/");
      const itemModels = new Map<string, string>();
      for (const [configurationIndex, configuration] of paletteConfigurations.entries()) {
        const variantModelPath = configurationIndex === 0 ? modelPath : `${modelPath}_palette_${configurationIndex}`;
        writeBoneResources(variantModelPath, id, [element], resource, blueprint, resources, configuration.states, preparedTextures);
        itemModels.set(configuration.key, variantModelPath);
      }
      itemModelsByNodeAndPaletteState.set(nodeId, { models: itemModels, enchanted });
      const conversionMatrix = ajElementPlayerHeadMatrix(element, `${id}.elements[${elementIndex}]`);
      nodes[nodeId] = {
        id: nodeId,
        type: "item_display",
        defaultMatrix,
        visible: true,
        ...(entityNbt ? { entityNbt } : {}),
        itemDisplay: "none",
        itemStackSnbt: boneItemStackSnbt(resource.namespace, modelPath, enchanted),
        ...(conversionMatrix ? { playerHeadConversion: { matrix: conversionMatrix } } : {}),
      };
      generatedIds.push(nodeId);
      (prepared.motion === "lower" ? lowerTargets : upperTargets).push(nodeId);
      if (conversionMatrix && part && isAjSkinSegment(element, part)) {
        skinCandidates.push({
          nodeId,
          part,
          centerY: (element.from[1] + element.to[1]) / 2,
          sourceOrder: sourceOrder++,
        });
      }
    }
    generatedIdsBySource.set(id, generatedIds);
    if (lowerId && lowerTargets.length > 0) jointTargets.push({ upperId: id, lowerId, upperTargets, lowerTargets });
  }
  assignSuggestedAjSkinParts(nodes, skinCandidates);
  const targetsBySource = new Map([...generatedIdsBySource].map(([id, ids]) => [id, ids.map((targetId) => ({ id: targetId }))]));
  for (const binding of jointTargets) {
    targetsBySource.set(binding.upperId, binding.upperTargets.map((id) => ({ id })));
    const lowerTargets = targetsBySource.get(binding.lowerId) ?? [];
    const upperMatrix = readDefaultMatrix(blueprint.nodes![binding.upperId], binding.upperId);
    const lowerMatrix = readDefaultMatrix(blueprint.nodes![binding.lowerId], binding.lowerId);
    const localMatrix = matrix4ToRowMajor(
      new Matrix4().set(...lowerMatrix).invert().multiply(new Matrix4().set(...upperMatrix)),
      `Animated Java ${binding.lowerId} to ${binding.upperId} joint offset`,
    );
    targetsBySource.set(binding.lowerId, [
      ...lowerTargets,
      ...binding.lowerTargets.map((id) => ({ id, localMatrix })),
    ]);
  }
  return { nodes, targetsBySource, itemModelsByNodeAndPaletteState };
}

function importDisplayNode(id: string, node: AjNode): ImportedNode {
  const defaultMatrix = readDefaultMatrix(node, id);
  if (node.type === "locator" || node.type === "structure" || node.type === "camera") {
    return { id, type: "anchor", defaultMatrix };
  }
  const entityNbt = displayPropertiesToNbt(node.display_properties, node.type);
  const properties = node.display_properties ?? {};
  if (node.type === "item_display") {
    return {
      id,
      type: "item_display",
      defaultMatrix,
      visible: true,
      ...(entityNbt ? { entityNbt } : {}),
      itemDisplay: stringProperty(properties, "item_display", "none"),
      itemStackSnbt: itemArgumentToSnbt(stringProperty(properties, "item", "minecraft:air")),
    };
  }
  if (node.type === "block_display") {
    return {
      id,
      type: "block_display",
      defaultMatrix,
      visible: true,
      ...(entityNbt ? { entityNbt } : {}),
      blockStateSnbt: blockArgumentToSnbt(stringProperty(properties, "block_state", "minecraft:air")),
    };
  }
  return {
    id,
    type: "text_display",
    defaultMatrix,
    visible: true,
    ...(entityNbt ? { entityNbt } : {}),
    text: parseText(stringProperty(properties, "text", "")),
  };
}

function uniqueAjElementNodeId(sourceId: string, elementIndex: number, usedIds: Set<string>): string {
  const base = `${sourceId}_${elementIndex + 1}`;
  let id = base;
  for (let suffix = 2; usedIds.has(id); suffix++) id = `${base}_${suffix}`;
  usedIds.add(id);
  return id;
}

function inferAjSkinPart(nodeId: string): ImportedSkinPart["part"] | undefined {
  return inferHumanoidPart(nodeId);
}

interface PreparedAjElement {
  element: AjElement;
  motion: "upper" | "lower";
}

function splitTallAjSkinElement(
  element: AjElement,
  part: ImportedSkinPart["part"] | undefined,
  jointed: boolean,
): PreparedAjElement[] {
  if (!part || !isStandardAjSkinElement(element, part)) return [{ element, motion: "upper" }];
  const height = element.to[1] - element.from[1];
  const skinHeight = humanoidSkinPartHeight(part);
  return humanoidSkinSlices(part, jointed).map((slice) => ({
    element: {
      ...element,
      from: [element.from[0], element.to[1] - height * slice.endY / skinHeight, element.from[2]],
      to: [element.to[0], element.to[1] - height * slice.startY / skinHeight, element.to[2]],
      faces: sliceAjVerticalFaceUvs(element.faces, slice.startY / skinHeight, slice.endY / skinHeight),
    },
    motion: slice.motion,
  }));
}

function sliceAjVerticalFaceUvs(faces: AjElement["faces"], startRatio: number, endRatio: number): AjElement["faces"] {
  const slicedFaces: AjElement["faces"] = {};
  for (const [direction, face] of Object.entries(faces)) {
    if (!["north", "south", "east", "west"].includes(direction) || face.uv.length !== 4) {
      slicedFaces[direction] = face;
      continue;
    }
    slicedFaces[direction] = { ...face, uv: sliceVerticalUv(face.uv, face.rotation ?? 0, startRatio, endRatio, "high") };
  }
  return slicedFaces;
}

function findAjLowerJoints(blueprint: AjBlueprint): Map<string, string> {
  const result = new Map<string, string>();
  const animatedIds = new Set(Object.values(blueprint.animations ?? {}).flatMap((animation) => Object.keys(animation.node_keyframes ?? {})));
  const bones = Object.entries(blueprint.nodes ?? {}).filter((entry): entry is [string, AjNode] => entry[1].type === "bone");
  for (const [upperId, upper] of bones) {
    const part = inferAjSkinPart(upperId);
    if (!part || part === "head" || !(upper.elements ?? []).some((element) => isStandardAjSkinElement(element, part))) continue;
    const names = part === "body"
      ? ["lowerbody", "abdomen"]
      : part.endsWith("arm") ? ["forearm", "lowerarm", "elbow"] : ["lowerleg", "shin", "knee"];
    const upperPosition = ajNodePosition(upper, upperId);
    const candidates = bones.filter(([candidateId, candidate]) => candidateId !== upperId
      && inferAjSkinPart(candidateId) === part
      && names.some((name) => candidateId.toLowerCase().replaceAll(/[^a-z0-9]/g, "").includes(name))
      && animatedIds.has(candidateId)
      && jointDistance(upperPosition, ajNodePosition(candidate, candidateId)) >= 0.2
      && jointDistance(upperPosition, ajNodePosition(candidate, candidateId)) <= 0.55);
    if (candidates.length === 1) result.set(upperId, candidates[0][0]);
  }
  return result;
}

function ajNodePosition(node: AjNode, id: string): number[] {
  const position = new Vector3();
  new Matrix4().set(...readDefaultMatrix(node, id)).decompose(position, new Quaternion(), new Vector3());
  return position.toArray();
}

function jointDistance(first: number[], second: number[]): number {
  return Math.hypot(...first.map((value, axis) => value - second[axis]));
}

function isStandardAjSkinElement(element: AjElement, part: ImportedSkinPart["part"]): boolean {
  const size = element.to.map((value, axis) => Math.abs(value - element.from[axis]));
  return isStandardHumanoidPartSize(part, size);
}

function isAjSkinSegment(element: AjElement, part: ImportedSkinPart["part"]): boolean {
  if (part === "head") return isStandardAjSkinElement(element, part);
  const size = element.to.map((value, axis) => Math.abs(value - element.from[axis]));
  const closeTo = (value: number, expected: number) => Math.abs(value - expected) <= 1e-3;
  const expectedWidth = part === "body" ? [8] : [3, 4];
  return expectedWidth.some((width) => closeTo(size[0], width))
    && (closeTo(size[1], 2) || closeTo(size[1], 4) || closeTo(size[1], 8))
    && closeTo(size[2], 4);
}

function assignSuggestedAjSkinParts(nodes: Record<string, ImportedNode>, candidates: AjSkinCandidate[]): void {
  for (const part of ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const) {
    candidates
      .filter((candidate) => candidate.part === part)
      .sort((first, second) => second.centerY - first.centerY || first.sourceOrder - second.sourceOrder)
      .forEach((candidate, order) => {
        const node = nodes[candidate.nodeId];
        if (node?.type === "item_display") node.suggestedSkin = { part, order };
      });
  }
}

function ajElementPlayerHeadMatrix(element: AjElement, path: string): Matrix16 | undefined {
  if (!Array.isArray(element.rotation) && !("axis" in element.rotation) && element.rotation.rescale) return undefined;
  const from = element.from.map((value) => (value - 8) / 16);
  const to = element.to.map((value) => (value - 8) / 16);
  const size = to.map((value, axis) => value - from[axis]);
  if (size.some((value) => !Number.isFinite(value) || value <= 0)) return undefined;
  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  const rotation = ajElementRotationMatrix(element);
  return matrix4ToRowMajor(rotation ? rotation.multiply(fit) : fit, `Animated Java ${path} player head conversion`);
}

function ajElementRotationMatrix(element: AjElement): Matrix4 | undefined {
  const rotation = element.rotation;
  if (Array.isArray(rotation)) {
    if (rotation.every((value) => Math.abs(value) <= 1e-7)) return undefined;
    const origin = element.from.map((value, axis) => ((value + element.to[axis]) / 2 - 8) / 16);
    return rotateAround(origin, rotation);
  }
  const origin = rotation.origin.map((value) => (value - 8) / 16);
  if ("axis" in rotation) {
    if (Math.abs(rotation.angle) <= 1e-7) return undefined;
    const axis = rotation.axis === "x" ? new Vector3(1, 0, 0) : rotation.axis === "y" ? new Vector3(0, 1, 0) : new Vector3(0, 0, 1);
    return new Matrix4().makeTranslation(origin[0], origin[1], origin[2])
      .multiply(new Matrix4().makeRotationAxis(axis, MathUtils.degToRad(rotation.angle)))
      .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
  }
  return rotateAround(origin, [rotation.x, rotation.y, rotation.z]);
}

function rotateAround(origin: number[], rotation: number[]): Matrix4 {
  const quaternion = new Quaternion().setFromEuler(new Euler(
    MathUtils.degToRad(rotation[0]),
    MathUtils.degToRad(rotation[1]),
    MathUtils.degToRad(rotation[2]),
    "ZYX",
  ));
  return new Matrix4().makeTranslation(origin[0], origin[1], origin[2])
    .multiply(new Matrix4().makeRotationFromQuaternion(quaternion))
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
}

function importAnimation(
  id: string,
  animation: AjAnimation,
  nodes: Record<string, ImportedNode>,
  targetsBySource: ReadonlyMap<string, readonly AjPoseTarget[]>,
  blueprint: AjBlueprint,
  itemModelsByNodeAndPaletteState: ReadonlyMap<string, AjItemModelVariants>,
): ImportedAnimation {
  const blendWeight = animation.blend_weight ?? "1";
  const startDelayTicks = secondsToTicks(numericExpression(animation.start_delay ?? "0", `${id}.start_delay`), `${id}.start_delay`);
  const animationDurationTicks = secondsToTicks(animation.length, `${id}.length`);
  const durationTicks = requireAnimationDurationTicks(animationDurationTicks + startDelayTicks, `${id} duration`);
  const tracks: ImportedAnimation["tracks"] = {};
  for (const [sourceNodeId, channels] of Object.entries(animation.node_keyframes ?? {})) {
    const targets = targetsBySource.get(sourceNodeId);
    const node = targets?.length ? nodes[targets[0].id] : undefined;
    if (!node || !targets) throw new Error(`Animated Java animation ${id} references unknown node ${sourceNodeId}.`);
    const transforms = compileNodeChannels(id, sourceNodeId, channels, node.defaultMatrix, startDelayTicks, animationDurationTicks, blendWeight);
    for (const target of targets) tracks[target.id] = {
      transforms: target.localMatrix ? transforms.map((frame) => ({
        ...frame,
        matrix: matrix4ToRowMajor(
          new Matrix4().set(...frame.matrix).multiply(new Matrix4().set(...target.localMatrix!)),
          `Animated Java ${id}/${target.id}/${frame.tick}`,
        ),
      })) : transforms,
      visibility: [],
      nbt: [],
    };
  }
  addTextureNbtTracks(
    id,
    animation,
    startDelayTicks,
    animationDurationTicks,
    blueprint,
    itemModelsByNodeAndPaletteState,
    tracks,
  );
  return {
    id: sanitizeResourcePath(id, "default"),
    name: prettify(id),
    durationTicks,
    loop: animation.loop_mode.type,
    loopDelayTicks: animation.loop_mode.type === "loop"
      ? secondsToTicks(numericExpression(animation.loop_mode.loop_delay ?? "0", `${id}.loop_delay`), `${id}.loop_delay`)
      : 0,
    tracks,
    events: {
      start: [],
      timeline: compileAjCallbackEvents(id, animation, startDelayTicks, durationTicks, blueprint),
      loop: [],
      stop: [],
    },
  };
}

function compileAjCallbackEvents(
  animationId: string,
  animation: AjAnimation,
  startDelayTicks: number,
  durationTicks: number,
  blueprint: AjBlueprint,
): ImportedTimelineEvent[] {
  const namespace = parseResourceLocation(blueprint.settings.id, "Animated Java settings.id").namespace;
  return Object.entries(animation.global_keyframes?.event ?? {})
    .map(([time, frame]) => ({ time: Number(time), frame }))
    .sort((first, second) => first.time - second.time)
    .filter(({ frame }) => frame.events.length > 0)
    .map(({ time, frame }) => {
      if (time > animation.length) throw new Error(`${animationId}.global_keyframes.event.${time} exceeds the animation length.`);
      const sourceTick = secondsToTicks(time, `${animationId}.global_keyframes.event.${time}`) + startDelayTicks;
      return {
        tick: Math.min(sourceTick, durationTicks - 1),
        source: { type: "player" as const },
        origin: { type: "root" as const },
        commands: [],
        callbacks: frame.events.map((name) => ({ name: `${namespace}:${name}` })),
      };
    });
}

function addTextureNbtTracks(
  animationId: string,
  animation: AjAnimation,
  startDelayTicks: number,
  animationDurationTicks: number,
  blueprint: AjBlueprint,
  itemModelsByNodeAndPaletteState: ReadonlyMap<string, AjItemModelVariants>,
  tracks: ImportedAnimation["tracks"],
): void {
  const sourceFrames = sortedTextureKeyframes(animation);
  if (sourceFrames.length === 0) return;
  const states = Object.fromEntries(Object.entries(blueprint.texture_palettes ?? {})
    .map(([paletteId, palette]) => [paletteId, palette.active_state]));
  const frames: { tick: number; key: string }[] = [{ tick: 0, key: paletteStateKey(states) }];
  for (const [time, frame] of sourceFrames) {
    if (time > animation.length) throw new Error(`${animationId}.global_keyframes.texture.${time} exceeds the animation length.`);
    Object.assign(states, frame);
    const tick = secondsToTicks(time, `${animationId}.global_keyframes.texture.${time}`) + startDelayTicks;
    const value = { tick, key: paletteStateKey(states) };
    if (frames.at(-1)!.tick === tick) frames[frames.length - 1] = value;
    else frames.push(value);
  }
  for (const [nodeId, variants] of itemModelsByNodeAndPaletteState) {
    const { models, enchanted } = variants;
    const node = models.size ? tracks[nodeId] ?? { transforms: [], visibility: [], nbt: [] } : undefined;
    if (!node) continue;
    node.nbt = frames.map(({ tick, key }) => {
      const modelPath = models.get(key);
      if (!modelPath) throw new Error(`Animated Java animation ${animationId} produced an unknown texture palette state.`);
      return {
        tick,
        value: serializeSnbtCompound([["item", boneItemStackSnbt(
          parseResourceLocation(blueprint.settings.id, "Animated Java settings.id").namespace,
          modelPath,
          enchanted,
        )]]),
      };
    });
    tracks[nodeId] = node;
  }
  if (frames.at(-1)!.tick > animationDurationTicks + startDelayTicks) {
    throw new Error(`${animationId} texture keyframe exceeds the animation duration.`);
  }
}

function compileNodeChannels(
  animationId: string,
  nodeId: string,
  channels: AjNodeChannels,
  defaultMatrix: Matrix16,
  startDelayTicks: number,
  durationTicks: number,
  blendWeight: string,
): ImportedTransformKeyframe[] {
  const matrix = new Matrix4().set(...defaultMatrix);
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  matrix.decompose(position, rotation, scale);
  const defaultTransform: AjTransformValues = {
    position: position.toArray() as [number, number, number],
    rotation: quaternionToAjRotation(rotation),
    scale: scale.toArray() as [number, number, number],
  };
  const current = {
    position: [...defaultTransform.position] as [number, number, number],
    rotation: [...defaultTransform.rotation] as [number, number, number],
    scale: [...defaultTransform.scale] as [number, number, number],
  };
  if (requiresAjBaking(channels, blendWeight)) {
    return bakeAjNodeChannels(channels, defaultTransform, durationTicks, `${animationId}/${nodeId}`).map((frame) => ({
      tick: frame.tick + startDelayTicks,
      matrix: composeBlendedAjMatrix(
        defaultTransform,
        frame,
        evaluateAjMolang(blendWeight, { animationTime: frame.time, keyframeLerpTime: 0 }, `${animationId}.blend_weight`),
      ),
      interpolation: frame.tick === 0 ? { type: "step" } : { type: "linear" },
    }));
  }
  const numericBlendWeight = numericExpression(blendWeight, `${animationId}.blend_weight`);
  const transforms = indexAjKeyframes(animationId, nodeId, channels).map(({ time, position, rotation, scale }): ImportedTransformKeyframe => {
    const entries = [position, rotation, scale];
    const [positionKeyframe, rotationKeyframe, scaleKeyframe] = entries;
    if (positionKeyframe) current.position = readBakedVector(positionKeyframe, `${animationId}/${nodeId}/position/${time}`);
    if (rotationKeyframe) current.rotation = readBakedVector(rotationKeyframe, `${animationId}/${nodeId}/rotation/${time}`);
    if (scaleKeyframe) current.scale = readBakedVector(scaleKeyframe, `${animationId}/${nodeId}/scale/${time}`);
    const step = entries.filter(Boolean).some((entry) => interpolation(entry!, `${animationId}/${nodeId}/${time}`) === "step");
    return {
      tick: secondsToTicks(time, `${animationId}/${nodeId} keyframe`) + startDelayTicks,
      matrix: composeBlendedAjMatrix(defaultTransform, current, numericBlendWeight),
      interpolation: step ? { type: "step" } : { type: "linear" },
    };
  });
  if (startDelayTicks > 0 && transforms.length > 0) {
    if (transforms[0].tick === startDelayTicks) {
      transforms[0] = { ...transforms[0], interpolation: { type: "step" } };
    } else {
      transforms.unshift({ tick: startDelayTicks, matrix: defaultMatrix, interpolation: { type: "step" } });
    }
  }
  return transforms;
}

function indexAjKeyframes(animationId: string, nodeId: string, channels: AjNodeChannels): IndexedAjKeyframes[] {
  const indexed = new Map<number, IndexedAjKeyframes>();
  const channelEntries = [
    ["position", channels.position],
    ["rotation", channels.rotation],
    ["scale", channels.scale],
  ] as const;
  for (const [channelName, channel] of channelEntries) {
    for (const [key, keyframe] of Object.entries(channel ?? {})) {
      const time = Number(key);
      if (!Number.isFinite(time) || time < 0) throw new Error(`${animationId}/${nodeId} has invalid keyframe time ${key}.`);
      secondsToTicks(time, `${animationId}/${nodeId} keyframe`);
      const entry = indexed.get(time) ?? { time };
      if (entry[channelName]) {
        throw new Error(`${animationId}/${nodeId}/${channelName} contains duplicate keyframe time ${time}.`);
      }
      entry[channelName] = keyframe;
      indexed.set(time, entry);
    }
  }
  return [...indexed.values()].sort((first, second) => first.time - second.time);
}

function composeBlendedAjMatrix(
  base: { position: number[]; rotation: number[]; scale: number[] },
  target: { position: number[]; rotation: number[]; scale: number[] },
  weight: number,
): Matrix16 {
  const position = base.position.map((value, index) => value + (target.position[index] - value) * weight);
  const scale = base.scale.map((value, index) => value + (target.scale[index] - value) * weight);
  const rotation = ajQuaternion(base.rotation).slerp(ajQuaternion(target.rotation), weight);
  return matrix4ToRowMajor(new Matrix4().compose(new Vector3(...position), rotation, new Vector3(...scale)), "Animated Java blended matrix");
}

function readBakedVector(keyframe: AjKeyframe, path: string): [number, number, number] {
  if (keyframe.post) throw new Error(`${path} contains a pre/post keyframe; enable baked animations in Animated Java.`);
  interpolation(keyframe, path);
  if (!Array.isArray(keyframe.value) || keyframe.value.length !== 3) throw new Error(`${path}.value must contain three values.`);
  return keyframe.value.map((value, index) => numericExpression(value, `${path}.value[${index}]`)) as [number, number, number];
}

function interpolation(keyframe: AjKeyframe, path: string): "linear" | "step" {
  if (keyframe.interpolation.type === "step") return "step";
  if (keyframe.interpolation.type === "linear" && keyframe.interpolation.easing === "step") return "step";
  if (keyframe.interpolation.type === "linear" && keyframe.interpolation.easing === "linear") return "linear";
  throw new Error(`${path} uses non-baked interpolation; enable baked animations in Animated Java.`);
}

function ajQuaternion(rotation: number[]): Quaternion {
  const euler = new Euler(
    MathUtils.degToRad(-rotation[0]),
    MathUtils.degToRad(180 - rotation[1]),
    MathUtils.degToRad(rotation[2]),
    "YXZ",
  );
  return new Quaternion().setFromEuler(euler);
}

function quaternionToAjRotation(quaternion: Quaternion): [number, number, number] {
  const euler = new Euler().setFromQuaternion(quaternion, "YXZ");
  return [-MathUtils.radToDeg(euler.x), 180 - MathUtils.radToDeg(euler.y), MathUtils.radToDeg(euler.z)];
}

function readDefaultMatrix(node: AjNode, id: string): Matrix16 {
  const values = node.default_transformation?.matrix ?? IDENTITY_MATRIX;
  if (values.length !== 16 || values.some((value) => !Number.isFinite(value))) throw new Error(`Animated Java node ${id} has an invalid default matrix.`);
  return matrix4ToRowMajor(new Matrix4().fromArray(values), `Animated Java node ${id} default matrix`);
}

function writeBoneResources(
  modelPath: string,
  nodeId: string,
  elements: AjElement[],
  resource: ResourceLocation,
  blueprint: AjBlueprint,
  resources: Map<string, Uint8Array>,
  paletteStates: Readonly<Record<string, string>>,
  preparedTextures: ReadonlyMap<string, PreparedCustomTexture>,
): void {
  const usedTextures = new Set<string>();
  const modelElements = elements.map((element) => ({
    from: element.from,
    to: element.to,
    rotation: element.rotation,
    ...(element.shade === undefined ? {} : { shade: element.shade }),
    ...(element.light_emission === undefined ? {} : { light_emission: element.light_emission }),
    faces: Object.fromEntries(Object.entries(element.faces).map(([direction, face]) => {
      const texture = resolveTexture(face.texture_provider, blueprint, paletteStates);
      usedTextures.add(texture);
      return [direction, {
        uv: face.uv,
        texture: `#${texture}`,
        ...(face.tintindex === undefined ? {} : { tintindex: face.tintindex }),
        ...(face.rotation === undefined ? {} : { rotation: face.rotation }),
      }];
    })),
  }));
  const textures = Object.fromEntries([...usedTextures].map((texture) => {
    const source = blueprint.textures?.[texture];
    if (!source) throw new Error(`Animated Java bone ${nodeId} references unknown texture ${texture}.`);
    if (source.type === "reference") return [texture, source.resource_location];
    const texturePath = [resource.path, texture].filter(Boolean).join("/");
    const outputPath = `assets/${resource.namespace}/textures/item/${texturePath}.png`;
    const prepared = preparedTextures.get(texture);
    if (!prepared) throw new Error(`Animated Java custom texture ${texture} was not prepared.`);
    addResource(resources, outputPath, prepared.bytes);
    if (prepared.animation) addResource(resources, `${outputPath}.mcmeta`, jsonBytes({ animation: prepared.animation }));
    return [texture, `${resource.namespace}:item/${texturePath}`];
  }));
  addResource(resources, `assets/${resource.namespace}/models/item/${modelPath}.json`, jsonBytes({ textures, elements: modelElements }));
  addResource(
    resources,
    `assets/${resource.namespace}/items/${modelPath}.json`,
    jsonBytes({ model: { type: "minecraft:model", model: `${resource.namespace}:item/${modelPath}` } }),
  );
}

function resolveTexture(
  provider: AjElement["faces"][string]["texture_provider"],
  blueprint: AjBlueprint,
  paletteStates: Readonly<Record<string, string>>,
): string {
  if (provider.type === "texture") return provider.texture;
  const palette = blueprint.texture_palettes?.[provider.texture_palette];
  const texture = palette?.states?.[paletteStates[provider.texture_palette]]?.texture;
  if (!texture) throw new Error(`Animated Java texture palette ${provider.texture_palette} has no active texture.`);
  return texture;
}

function displayPropertiesToNbt(properties: Record<string, unknown> | undefined, nodeType: AjNode["type"]): string | undefined {
  if (!properties) return undefined;
  const fields: [string, string][] = [];
  for (const key of ["billboard", "shadow_radius", "shadow_strength", "glow_color_override"] as const) {
    const value = properties[key];
    if (typeof value === "string") fields.push([key, serializeSnbtString(value)]);
    else if (typeof value === "number" && Number.isFinite(value)) fields.push([key, String(value)]);
  }
  if (properties.is_glowing === true) fields.push(["Glowing", "1b"]);
  if (typeof properties.custom_brightness === "number" && Number.isInteger(properties.custom_brightness)) {
    fields.push(["brightness", serializeSnbtCompound([
      ["sky", String(properties.custom_brightness)],
      ["block", String(properties.custom_brightness)],
    ])]);
  } else if (properties.is_custom_brightness_enabled === true && isRecord(properties.custom_brightness)) {
    const sky = numberProperty(properties.custom_brightness, "sky", 0);
    const block = numberProperty(properties.custom_brightness, "block", 0);
    fields.push(["brightness", serializeSnbtCompound([["sky", String(sky)], ["block", String(block)]])]);
  }
  if (nodeType === "text_display") {
    const alignment = properties.alignment;
    if (typeof alignment === "string") fields.push(["alignment", serializeSnbtString(alignment)]);
    for (const [source, target] of [["background_color", "background"], ["line_width", "line_width"]] as const) {
      const value = properties[source];
      if (typeof value === "number" && Number.isInteger(value)) fields.push([target, String(value)]);
    }
    if (typeof properties.text_opacity === "number" && Number.isInteger(properties.text_opacity)) {
      fields.push(["text_opacity", `${properties.text_opacity}b`]);
    }
    for (const [source, target] of [
      ["is_default_background", "default_background"],
      ["is_see_through", "see_through"],
      ["is_shadowed", "shadow"],
    ] as const) {
      if (typeof properties[source] === "boolean") fields.push([target, properties[source] ? "1b" : "0b"]);
    }
  }
  return fields.length ? serializeSnbtCompound(fields) : undefined;
}

function parseText(value: string): unknown {
  try { return JSON.parse(value); } catch { return { text: value }; }
}

function numericExpression(value: string, path: string): number {
  if (typeof value !== "string" || !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim())) {
    throw new Error(`${path} contains a dynamic Molang expression; enable baked animations in Animated Java.`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${path} is not finite.`);
  return parsed;
}

function decodeBase64(value: string, texture: string): Uint8Array {
  try {
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    throw new Error(`Animated Java texture ${texture} is not valid base64.`);
  }
}

async function prepareCustomTextures(blueprint: AjBlueprint): Promise<Map<string, PreparedCustomTexture>> {
  const preparedTextures = new Map<string, PreparedCustomTexture>();
  for (const [textureId, texture] of Object.entries(blueprint.textures ?? {})) {
    if (texture.type !== "custom") continue;

    const bytes = decodeBase64(texture.base64_string, textureId);
    const detectedType = detectImageType(bytes);
    const declaredType = normalizeImageMimeType(texture.mime_type);
    const path = `textures.${textureId}`;
    if (texture.mime_type !== undefined && !declaredType) {
      throw new ConversionError(
        "unsupported_animated_java_texture_format",
        `Animated Java texture ${textureId} uses unsupported MIME type ${texture.mime_type}. Only PNG, JPEG, and GIF are supported.`,
        `${path}.mime_type`,
      );
    }
    if (!detectedType) {
      throw new ConversionError(
        "unsupported_animated_java_texture_format",
        `Animated Java texture ${textureId} is not a PNG, JPEG, or GIF image.`,
        `${path}.base64_string`,
      );
    }
    if (declaredType && declaredType !== detectedType) {
      throw new ConversionError(
        "animated_java_texture_mime_mismatch",
        `Animated Java texture ${textureId} declares ${texture.mime_type} but contains ${detectedType.toUpperCase()} data.`,
        `${path}.mime_type`,
      );
    }

    if (detectedType === "png") preparedTextures.set(textureId, { bytes, animation: texture.animation });
    else if (detectedType === "jpeg") preparedTextures.set(textureId, { bytes: await convertJpegToPng(bytes, textureId, path), animation: texture.animation });
    else preparedTextures.set(textureId, await convertGifToAnimatedPng(bytes, textureId, path));
  }
  return preparedTextures;
}

function normalizeImageMimeType(mimeType: string | undefined): SupportedImageType | undefined {
  const normalized = mimeType?.split(";", 1)[0].trim().toLowerCase();
  if (normalized === "image/png") return "png";
  if (normalized === "image/jpeg" || normalized === "image/jpg" || normalized === "image/pjpeg") return "jpeg";
  if (normalized === "image/gif") return "gif";
  return undefined;
}

function detectImageType(bytes: Uint8Array): SupportedImageType | undefined {
  if (
    bytes.length >= 8
    && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
    && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a
  ) return "png";
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpeg";
  if (
    bytes.length >= 6
    && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x38
    && (bytes[4] === 0x37 || bytes[4] === 0x39) && bytes[5] === 0x61
  ) return "gif";
  return undefined;
}

async function convertJpegToPng(bytes: Uint8Array, textureId: string, path: string): Promise<Uint8Array> {
  let bitmap: ImageBitmap | undefined;
  try {
    bitmap = await createImageBitmap(new Blob([bytes.slice().buffer], { type: "image/jpeg" }));
    const canvas = createCanvas(bitmap.width, bitmap.height);
    const context = requireCanvasContext(canvas);
    context.drawImage(bitmap, 0, 0);
    return await canvasToPngBytes(canvas);
  } catch (reason) {
    const detail = reason instanceof Error ? reason.message : String(reason);
    throw new ConversionError(
      "animated_java_jpeg_conversion_failed",
      `Animated Java texture ${textureId} could not be converted from JPEG to PNG: ${detail}`,
      `${path}.base64_string`,
    );
  } finally {
    bitmap?.close();
  }
}

async function convertGifToAnimatedPng(bytes: Uint8Array, textureId: string, path: string): Promise<PreparedCustomTexture> {
  try {
    const { decompressFrames, parseGIF } = await import("gifuct-js");
    const gif = parseGIF(bytes.slice().buffer);
    const decodedFrames = decompressFrames(gif, true);
    const frameCount = decodedFrames.length;
    if (frameCount < 1) throw new Error("GIF does not contain any frames");
    if (frameCount > MAX_GIF_FRAME_COUNT) throw new Error(`GIF contains ${frameCount} frames; the limit is ${MAX_GIF_FRAME_COUNT}`);
    const frameWidth = gif.lsd.width;
    const frameHeight = gif.lsd.height;
    if (frameWidth < 1 || frameHeight < 1) throw new Error("GIF has invalid frame dimensions");
    const { columns, rows } = layoutGifAtlas(frameWidth, frameHeight, frameCount);
    const frameCanvas = createCanvas(frameWidth, frameHeight);
    const patchCanvas = createCanvas(1, 1);
    const atlasCanvas = createCanvas(frameWidth * columns, frameHeight * rows);
    const frameContext = requireCanvasContext(frameCanvas);
    const patchContext = requireCanvasContext(patchCanvas);
    const atlasContext = requireCanvasContext(atlasCanvas);
    const frames: NonNullable<AjTextureAnimation["frames"]> = [];
    let previousFrame: ParsedFrame | undefined;
    let restoreBeforePreviousFrame: ImageData | undefined;
    for (const [frameIndex, frame] of decodedFrames.entries()) {
      applyGifDisposal(frameContext, previousFrame, restoreBeforePreviousFrame);
      const restoreBeforeCurrentFrame = frame.disposalType === 3
        ? frameContext.getImageData(0, 0, frameWidth, frameHeight)
        : undefined;
      validateGifPatch(frame, frameWidth, frameHeight, frameIndex);
      patchCanvas.width = frame.dims.width;
      patchCanvas.height = frame.dims.height;
      const patch = new Uint8ClampedArray(frame.patch.length);
      patch.set(frame.patch);
      patchContext.putImageData(new ImageData(patch, frame.dims.width, frame.dims.height), 0, 0);
      frameContext.drawImage(patchCanvas, frame.dims.left, frame.dims.top);
      const x = frameIndex % columns * frameWidth;
      const y = Math.floor(frameIndex / columns) * frameHeight;
      atlasContext.drawImage(frameCanvas, x, y);
      frames.push({ index: frameIndex, time: gifFrameTicks(frame.delay) });
      previousFrame = frame;
      restoreBeforePreviousFrame = restoreBeforeCurrentFrame;
    }

    return {
      bytes: await canvasToPngBytes(atlasCanvas),
      animation: { width: frameWidth, height: frameHeight, frames },
    };
  } catch (reason) {
    if (reason instanceof ConversionError) throw reason;
    const detail = reason instanceof Error ? reason.message : String(reason);
    throw new ConversionError(
      "animated_java_gif_conversion_failed",
      `Animated Java texture ${textureId} could not be converted from GIF to an animated PNG texture: ${detail}`,
      `${path}.base64_string`,
    );
  }
}

function applyGifDisposal(
  context: CanvasRenderingContext2D,
  previousFrame: ParsedFrame | undefined,
  restoreBeforePreviousFrame: ImageData | undefined,
): void {
  if (previousFrame?.disposalType === 2) {
    const { left, top, width, height } = previousFrame.dims;
    context.clearRect(left, top, width, height);
  } else if (previousFrame?.disposalType === 3 && restoreBeforePreviousFrame) {
    context.putImageData(restoreBeforePreviousFrame, 0, 0);
  }
}

function validateGifPatch(frame: ParsedFrame, frameWidth: number, frameHeight: number, frameIndex: number): void {
  const { left, top, width, height } = frame.dims;
  if (width < 1 || height < 1 || left < 0 || top < 0 || left + width > frameWidth || top + height > frameHeight) {
    throw new Error(`GIF frame ${frameIndex} lies outside the animation bounds`);
  }
}

function layoutGifAtlas(frameWidth: number, frameHeight: number, frameCount: number): { columns: number; rows: number } {
  const maxColumns = Math.floor(MAX_GIF_ATLAS_DIMENSION / frameWidth);
  const maxRows = Math.floor(MAX_GIF_ATLAS_DIMENSION / frameHeight);
  if (maxColumns < 1 || maxRows < 1 || maxColumns * maxRows < frameCount) {
    throw new Error(`GIF animation does not fit within a ${MAX_GIF_ATLAS_DIMENSION}px texture atlas`);
  }
  let columns = Math.min(maxColumns, Math.max(1, Math.ceil(Math.sqrt(frameCount * frameHeight / frameWidth))));
  while (Math.ceil(frameCount / columns) > maxRows) columns++;
  const rows = Math.ceil(frameCount / columns);
  if (frameWidth * columns * frameHeight * rows > MAX_GIF_ATLAS_AREA) {
    throw new Error(`GIF animation exceeds the ${MAX_GIF_ATLAS_AREA}-pixel atlas limit`);
  }
  return { columns, rows };
}

function createCanvas(width: number, height: number): HTMLCanvasElement {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

function requireCanvasContext(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
  const context = canvas.getContext("2d");
  if (!context) throw new Error("2D canvas is unavailable");
  return context;
}

function gifFrameTicks(durationMilliseconds: number): number {
  return Math.max(1, Math.round(durationMilliseconds / MINECRAFT_TICK_MILLISECONDS));
}

async function canvasToPngBytes(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((result) => result ? resolve(result) : reject(new Error("PNG encoding failed")), "image/png");
  });
  return new Uint8Array(await blob.arrayBuffer());
}

function jsonBytes(value: unknown): Uint8Array {
  return encoder.encode(`${JSON.stringify(value, null, 2)}\n`);
}

function addResource(resources: Map<string, Uint8Array>, path: string, data: Uint8Array): void {
  const existing = resources.get(path);
  if (existing && (existing.length !== data.length || existing.some((value, index) => value !== data[index]))) {
    throw new ConversionError("resource_path_collision", `Generated resources contain different files at the same path: ${path}`, path);
  }
  resources.set(path, data);
}

function stringProperty(value: Record<string, unknown>, key: string, fallback: string): string {
  return typeof value[key] === "string" ? value[key] : fallback;
}

function numberProperty(value: Record<string, unknown>, key: string, fallback: number): number {
  return typeof value[key] === "number" && Number.isFinite(value[key]) ? value[key] : fallback;
}

function prettify(value: string): string {
  const result = value.replaceAll("_", " ").replaceAll("-", " ").trim();
  return result ? result[0].toUpperCase() + result.slice(1) : "Emote";
}
