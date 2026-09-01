import { Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type EmoteEvent, type EmoteNode, type EmoteNodeTracks, type EmoteVectorKeyframe, type Matrix16, type MolangScalar } from "../../format/emoteAnimation";
import { matrixToLocalTransform } from "../../format/localTransform";
import { composeDegreesTransform, matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString } from "../../format/snbt";
import { formatMinecraftTime, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedTimelineEvent, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import {
  type BbAnimation,
  type BbAnimator,
  type BbCube,
  type BbGroup,
  type BbKeyframe,
  type BbLocator,
  type BbOutlinerEntry,
  type BbOutlinerGroup,
  type BbmodelProject,
} from "./cubeProjectSchema";
import { evaluateGeckoChannel } from "./cubeAnimationBaker";
import {
  cubePlayerHeadMatrix,
  isHiddenAccessoryBone,
  isHiddenAccessoryName,
  normalizeBlockbenchName,
  prepareCubeModels,
  uniqueCubeNodeId,
  writeCubeResources,
} from "./cubeModelResources";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../runtimeOutput";
import { affineMolang, isolateMolangAxis, molangScalar, negateMolang, type MolangVector } from "../molangVector";
import { GECKOLIB_BBMODEL_TRANSFORMS, type CubeProjectTransformConvention } from "./cubeProjectTransformConvention";

export const PLAYER_RENDER_SCALE = 0.9375;

interface BoneNodeEntry {
  id: string;
  localMatrix: Matrix4;
  ignoreInheritedScale?: boolean;
  locatorName?: string;
}

export interface BoneEntry {
  id: string;
  uuid: string;
  group: BbGroup;
  parent?: BoneEntry;
  cubes: BbCube[];
  locators: BbLocator[];
  nodes: BoneNodeEntry[];
}

export interface CubeProjectImportOptions {
  transforms?: CubeProjectTransformConvention;
}

export function importBlockbenchCubeProject(project: BbmodelProject, sourceName: string, options: CubeProjectImportOptions = {}): ImportedProject {
  if (project.meta.model_format !== "geckolib_model") throw new Error(`Unsupported Blockbench model format: ${project.meta.model_format}`);
  if (project.elements.some((element) => element.type && element.type !== "cube" && element.type !== "locator")) {
    throw new ConversionError("unsupported_geckolib_element", "GeckoLib meshes and non-cube elements are not supported.", "elements");
  }

  const sourceStem = sourceName.replace(/\.bbmodel$/i, "").trim() || project.name?.trim() || "GeckoLib Model";
  const namespace = validNamespace(project.geckolib_modid) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "geckolib_model");
  const resources = new Map<string, Uint8Array>();
  const transforms = options.transforms ?? GECKOLIB_BBMODEL_TRANSFORMS;
  const bones = buildBoneEntries(project);
  if (bones.length === 0) throw new Error("GeckoLib bbmodel does not contain bones.");
  const { playableCubesByBone, skinAssignments } = prepareCubeModels(project, bones, namespace, projectPath, resources, transforms);
  const diagnostics: ImportDiagnostic[] = [];
  const nodes: Record<string, ImportedNode> = {};
  const nodeIds = new Set(bones.map((bone) => bone.id));
  for (const bone of bones) {
    const boneMatrix = new Matrix4().set(...boneWorldMatrix(bone, new Map(), transforms));
    const playableCubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (playableCubes.length === 0) {
      nodes[bone.id] = {
        id: bone.id,
        type: "anchor",
        defaultMatrix: matrix4ToRowMajor(boneMatrix, `GeckoLib bone ${bone.id}`),
      };
      bone.nodes.push({ id: bone.id, localMatrix: new Matrix4() });
    } else for (const [cubeIndex, cube] of playableCubes.entries()) {
      const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, nodeIds);
      const hiddenAccessory = isHiddenAccessoryBone(bone);
      const conversionMatrix = hiddenAccessory ? undefined : cubePlayerHeadMatrix(cube, bone, transforms);
      if (!hiddenAccessory && !conversionMatrix) throw new ConversionError("invalid_geckolib_cube", `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const skin = hiddenAccessory ? undefined : skinAssignments.get(cube.uuid);
      const localMatrix = cubeLocalMatrix(cube, bone, transforms);
      bone.nodes.push({ id: nodeId, localMatrix });
      const modelPath = `${projectPath}/${nodeId}`;
      writeCubeResources(project, bone, cube, namespace, modelPath, resources, transforms);
      nodes[nodeId] = {
        id: nodeId,
        type: "item_display",
        defaultMatrix: matrix4ToRowMajor(boneMatrix.clone().multiply(localMatrix), `GeckoLib cube ${nodeId}`),
        visible: true,
        itemDisplay: "none",
        itemStackSnbt: serializeSnbtCompound([
          ["id", serializeSnbtString("minecraft:paper")],
          ["count", "1"],
          ["components", serializeSnbtCompound([
            ["minecraft:item_model", serializeSnbtString(`${namespace}:${modelPath}`)],
          ])],
        ]),
        ...(conversionMatrix ? { playerHeadConversion: { matrix: conversionMatrix } } : {}),
        ...(skin ? { suggestedSkin: skin, skinAssignmentGroup: `${skin.part}_${skin.order}` } : {}),
      };
    }
    for (const [locatorIndex, locator] of bone.locators.entries()) {
      const nodeId = uniqueLocatorNodeId(bone, locator, locatorIndex, nodeIds);
      const localMatrix = locatorLocalMatrix(locator, bone, transforms);
      const locatorBoneMatrix = locator.ignore_inherited_scale ? matrixWithoutScale(boneMatrix) : boneMatrix;
      bone.nodes.push({ id: nodeId, localMatrix, ignoreInheritedScale: locator.ignore_inherited_scale, locatorName: locator.name });
      nodes[nodeId] = {
        id: nodeId,
        type: "anchor",
        defaultMatrix: matrix4ToRowMajor(locatorBoneMatrix.clone().multiply(localMatrix), `GeckoLib locator ${nodeId}`),
      };
    }
  }

  if (project.animations.length === 0) throw new Error("GeckoLib bbmodel does not contain animations.");
  const animations = project.animations.map((animation, index) => {
    try {
      return importAnimation(animation, index, bones, diagnostics, transforms);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_geckolib_molang") throw reason;
      const message = `${animation.name}: preview uses the Create pose; runtime Molang is preserved.`;
      diagnostics.push({
        severity: "warning",
        code: "geckolib_animation_molang_unavailable",
        message,
        sourcePath: reason.sourcePath ?? `animations[${index}]`,
      });
      return createPreviewOnlyAnimation(animation, index, message, bones, nodes, transforms);
    }
  });
  return {
    source: "geckolib_bbmodel",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: namespace,
    nodes,
    animations,
    diagnostics,
    resources,
    ...(resources.size ? { resourceMinecraftVersion: "26.2" } : {}),
  };
}

function createPreviewOnlyAnimation(animation: BbAnimation, index: number, reason: string, bones: BoneEntry[], nodes: Record<string, ImportedNode>, transforms: CubeProjectTransformConvention): ImportedAnimation {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  const durationTicks = Number.isFinite(animation.length) && animation.length > 0
    ? Math.max(1, Math.round(animation.length * TICKS_PER_SECOND))
    : TICKS_PER_SECOND;
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks,
    loop: playbackMode === "loop" || playbackMode === "hold" ? playbackMode : "once",
    loopDelayTicks: 0,
    tracks: {},
    events: { start: [], timeline: [], loop: [], stop: [] },
    availability: { preview: "create_pose", exportable: true, reason },
    preview: { durationTicks: TICKS_PER_SECOND, tracks: {} },
    runtime: createGeckoRuntime(animation, index, durationTicks, bones, nodes, transforms),
  };
}

function createGeckoRuntime(
  animation: BbAnimation,
  animationIndex: number,
  durationTicks: number,
  bones: BoneEntry[],
  importedNodes: Record<string, ImportedNode>,
  transforms: CubeProjectTransformConvention,
): NonNullable<ImportedAnimation["runtime"]> {
  const sceneId = "geckolib_scene";
  const nodes: Record<string, EmoteNode> = {
    [sceneId]: { type: "anchor", space: "initiator", transform: { ...IDENTITY_TRANSFORM, scale: [PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE] } },
  };
  const tracks: Record<string, EmoteNodeTracks> = {};
  const animators = resolveBoneAnimators(animation, animationIndex, bones);
  for (const bone of bones) {
    const parent = bone.parent ? `${bone.parent.id}_x` : sceneId;
    const parentOrigin = bone.parent?.group.origin ?? ZERO_VECTOR;
    const basePosition = transforms.position(
      bone.group.origin.map((value, axis) => value - parentOrigin[axis]),
      (value) => -value,
    ).map((value) => value / 16) as [number, number, number];
    const baseRotation = transforms.rotation(bone.group.rotation, (value) => -value);
    nodes[`${bone.id}_z`] = { type: "anchor", parent, transform: { position: basePosition, rotation: [0, 0, baseRotation[2]], scale: ONE_VECTOR } };
    nodes[`${bone.id}_y`] = { type: "anchor", parent: `${bone.id}_z`, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } };
    nodes[`${bone.id}_x`] = { type: "anchor", parent: `${bone.id}_y`, transform: { position: ZERO_VECTOR, rotation: [baseRotation[0], 0, 0], scale: ONE_VECTOR } };
    for (const entry of bone.nodes) {
      const imported = importedNodes[entry.id];
      if (imported) nodes[entry.id] = importedNodeToRuntimeNode(imported, matrixToLocalTransform(matrix4ToRowMajor(entry.localMatrix, `GeckoLib runtime node ${entry.id}`), `GeckoLib runtime node ${entry.id}`), `${bone.id}_x`);
    }
    const animator = animators.get(bone.uuid);
    if (!animator) continue;
    const position = geckoChannelFrames(animator, "position", ZERO_VECTOR, (values) => transforms.position(values, negateMolang)
      .map((value, axis) => affineMolang(value, 1 / 16, basePosition[axis])) as MolangVector);
    const rotation = geckoChannelFrames(animator, "rotation", ZERO_VECTOR, (values) => transforms.rotation(values, negateMolang));
    const scale = geckoChannelFrames(animator, "scale", ONE_VECTOR, (values) => values);
    if (position) tracks[`${bone.id}_z`] = { position };
    if (rotation) {
      tracks[`${bone.id}_z`] = { ...tracks[`${bone.id}_z`], rotation: isolateMolangAxis(rotation, 2, (value) => affineMolang(value, 1, baseRotation[2])) };
      tracks[`${bone.id}_y`] = { rotation: isolateMolangAxis(rotation, 1, (value) => affineMolang(value, 1, baseRotation[1])) };
      tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], rotation: isolateMolangAxis(rotation, 0, (value) => affineMolang(value, 1, baseRotation[0])) };
    }
    if (scale) tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], scale };
  }
  return { nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
}

function geckoChannelFrames(
  animator: BbAnimator,
  channel: "position" | "rotation" | "scale",
  fallback: readonly [number, number, number],
  transform: (value: MolangVector) => MolangVector,
): EmoteVectorKeyframe[] | undefined {
  const source = (animator.keyframes ?? []).filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
  if (source.length === 0) return undefined;
  const result = source.map((frame): EmoteVectorKeyframe => {
    const points = frame.data_points;
    if (points.length < 1 || points.length > 2) throw new ConversionError("unsupported_geckolib_keyframe", "GeckoLib transform keyframes must contain one value or a pre/post pair.");
    const vectors = points.map((point) => transform(geckoPointVector(point)));
    const interpolation = frame.interpolation === "step" ? "step" : "linear";
    return vectors.length === 1
      ? { time: formatMinecraftTime(Math.round(frame.time * TICKS_PER_SECOND)), value: vectors[0], interpolation }
      : { time: formatMinecraftTime(Math.round(frame.time * TICKS_PER_SECOND)), pre: vectors[0], post: vectors[1], interpolation };
  });
  if (result[0].time !== "0t") result.unshift({ time: "0t", value: transform([...fallback] as MolangVector), interpolation: "step" });
  return result.map((frame, index) => index + 1 < result.length ? frame : (({ interpolation: _, ...last }) => last)(frame));
}

function geckoPointVector(point: BbKeyframe["data_points"][number]): MolangVector {
  if (point.x === undefined || point.y === undefined || point.z === undefined) throw new ConversionError("invalid_geckolib_keyframe", "GeckoLib transform keyframe is missing an axis value.");
  return [point.x, point.y, point.z].map(molangScalar) as MolangVector;
}

function buildBoneEntries(project: BbmodelProject): BoneEntry[] {
  const groups = new Map(project.groups.map((group) => [group.uuid, group]));
  const elements = new Map(project.elements.map((element) => [element.uuid, element]));
  const entries: BoneEntry[] = [];
  const ids = new Set<string>();
  const visit = (entry: BbOutlinerEntry, parent?: BoneEntry) => {
    if (typeof entry === "string") {
      if (!parent) throw new Error(`GeckoLib cube ${entry} is not parented to a bone.`);
      const element = elements.get(entry);
      if (!element) throw new Error(`GeckoLib outliner references unknown element ${entry}.`);
      if (isLocator(element)) parent.locators.push(element);
      else parent.cubes.push(element);
      return;
    }
    const saved = groups.get(entry.uuid);
    const group = mergeGroup(saved, entry);
    let id = sanitizeResourcePath(group.name, "bone").replaceAll("/", "_");
    const base = id;
    for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
    ids.add(id);
    const bone: BoneEntry = { id, uuid: group.uuid, group, parent, cubes: [], locators: [], nodes: [] };
    entries.push(bone);
    entry.children.forEach((child) => visit(child, bone));
  };
  project.outliner.forEach((entry) => visit(entry));
  return entries;
}

function isLocator(element: BbmodelProject["elements"][number]): element is BbLocator {
  return element.type === "locator";
}

function mergeGroup(saved: BbGroup | undefined, outliner: BbOutlinerGroup): BbGroup {
  const name = outliner.name ?? saved?.name;
  const origin = outliner.origin ?? saved?.origin;
  const rotation = outliner.rotation ?? saved?.rotation ?? [0, 0, 0];
  if (!name || !origin) throw new Error(`GeckoLib bone ${outliner.uuid} is missing its saved group data.`);
  return { uuid: outliner.uuid, name, origin, rotation };
}

function uniqueLocatorNodeId(bone: BoneEntry, locator: BbLocator, locatorIndex: number, ids: Set<string>): string {
  const name = sanitizeResourcePath(locator.name?.trim() || `locator_${locatorIndex + 1}`, `locator_${locatorIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${name}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

function boneWorldMatrix(bone: BoneEntry, cache: Map<string, Matrix16>, convention: CubeProjectTransformConvention): Matrix16 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const local = bindLocalMatrix(bone, convention);
  const world = bone.parent
    ? new Matrix4().set(...boneWorldMatrix(bone.parent, cache, convention)).multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  const result = matrix4ToRowMajor(world, `GeckoLib bone ${bone.id}`);
  cache.set(bone.uuid, result);
  return result;
}

function bindLocalMatrix(bone: BoneEntry, convention: CubeProjectTransformConvention): Matrix4 {
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  return composeDegreesTransform(
    convention.position(bone.group.origin.map((value, index) => value - parentOrigin[index]), (value) => -value).map((value) => value / 16),
    convention.rotation(bone.group.rotation, (value) => -value),
    [1, 1, 1],
  );
}

function importAnimation(animation: BbAnimation, index: number, bones: BoneEntry[], diagnostics: ImportDiagnostic[], convention: CubeProjectTransformConvention): ImportedAnimation {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  if (playbackMode !== "once" && playbackMode !== "hold" && playbackMode !== "loop") throw new Error(`GeckoLib animation ${animation.name} has unsupported loop mode ${loop}.`);
  if (!Number.isFinite(animation.length) || animation.length < 0) throw new Error(`GeckoLib animation ${animation.name} has an invalid length.`);
  const startDelaySeconds = optionalNumericValue(animation.start_delay, 0, `animations[${index}].start_delay`);
  const blendWeight = optionalNumericValue(animation.blend_weight, 1, `animations[${index}].blend_weight`);
  const effectEvents = importEffectEvents(animation, index, bones, diagnostics);
  const durationTicks = requireAnimationDurationTicks(
    Math.max(1, Math.round((animation.length + startDelaySeconds) * TICKS_PER_SECOND), ...effectEvents.map((event) => event.tick + 1)),
    `${animation.name}.length`,
  );
  const boneAnimators = resolveBoneAnimators(animation, index, bones);
  const samplePlan = planAnimationSamples(animation, index, bones, boneAnimators, durationTicks, convention);
  const tracks: ImportedAnimation["tracks"] = {};
  for (const bone of bones) {
    validateBoneAnimator(animation, index, bone, boneAnimators.get(bone.uuid));
    const transforms: ImportedTransformKeyframe[] = [];
    for (let tick = 0; tick <= durationTicks; tick++) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = startDelaySeconds > 0 ? tick / TICKS_PER_SECOND - startDelaySeconds : samplePlan.sourceTimes.get(tick) ?? tick / TICKS_PER_SECOND;
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, animation, boneAnimators, sourceTime, cache, index, blendWeight, convention), `${animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || samplePlan.stepTicks.has(tick) ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
    for (const node of bone.nodes) {
      tracks[node.id] = {
        transforms: transforms.map((transform) => ({
          ...transform,
          matrix: matrix4ToRowMajor(
            (node.ignoreInheritedScale ? matrixWithoutScale(new Matrix4().set(...transform.matrix)) : new Matrix4().set(...transform.matrix)).multiply(node.localMatrix),
            `${animation.name}/${node.id}/${transform.tick}`,
          ),
        })),
        visibility: [],
        nbt: [],
      };
    }
  }
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks,
    loop: playbackMode,
    loopDelayTicks: playbackMode === "loop"
      ? Math.round(numericValue(animation.loop_delay ?? 0, `animations[${index}].loop_delay`) * TICKS_PER_SECOND)
      : 0,
    tracks,
    events: { start: [], timeline: effectEvents, loop: [], stop: [] },
  };
}

interface AnimationAnchor {
  time: number;
  priority: number;
  step: boolean;
}

interface AnimationSamplePlan {
  sourceTimes: Map<number, number>;
  stepTicks: Set<number>;
}

interface PlannedAnchor {
  anchor: AnimationAnchor;
  tick: number;
}

interface SamplePlanState {
  lastTick: number;
  preservedPriority: number;
  preservedCount: number;
  error: number;
  previous?: SamplePlanState;
  assignment?: PlannedAnchor;
}

function planAnimationSamples(
  animation: BbAnimation,
  animationIndex: number,
  bones: BoneEntry[],
  boneAnimators: Map<string, BbAnimator>,
  durationTicks: number,
  convention: CubeProjectTransformConvention,
): AnimationSamplePlan {
  const anchors = collectAnimationAnchors(animation, animationIndex);
  if (anchors.length === 0) return { sourceTimes: new Map(), stepTicks: new Set() };

  const snapshots = new Map<string, Matrix4[]>();
  const snapshotAt = (time: number) => {
    const key = time.toFixed(9);
    const cached = snapshots.get(key);
    if (cached) return cached;
    const cache = new Map<string, Matrix4>();
    const result = bones.map((bone) => animatedWorldMatrix(bone, animation, boneAnimators, time, cache, animationIndex, 1, convention).clone());
    snapshots.set(key, result);
    return result;
  };

  let states = new Map<number, SamplePlanState>([[-1, {
    lastTick: -1,
    preservedPriority: 0,
    preservedCount: 0,
    error: 0,
  }]]);
  for (const anchor of anchors) {
    const scaledTime = anchor.time * TICKS_PER_SECOND;
    const candidateTicks = [...new Set([Math.floor(scaledTime), Math.ceil(scaledTime)])]
      .filter((tick) => tick >= 0 && tick <= durationTicks);
    const candidateCosts = new Map(candidateTicks.map((tick) => [
      tick,
      sampleCandidateError(anchor.time, anchor.step, tick, durationTicks, bones.length, snapshotAt)
        + Math.abs(tick / TICKS_PER_SECOND - anchor.time) * 1e-6,
    ]));
    const nextStates = new Map<number, SamplePlanState>();
    for (const state of states.values()) {
      retainBetterPlan(nextStates, state);
      for (const tick of candidateTicks) {
        if (tick <= state.lastTick) continue;
        retainBetterPlan(nextStates, {
          lastTick: tick,
          preservedPriority: state.preservedPriority + anchor.priority,
          preservedCount: state.preservedCount + 1,
          error: state.error + (candidateCosts.get(tick) ?? 0),
          previous: state,
          assignment: { anchor, tick },
        });
      }
    }
    states = nextStates;
  }

  const best = [...states.values()].reduce((current, candidate) => isBetterPlan(candidate, current) ? candidate : current);
  const sourceTimes = new Map<number, number>();
  const stepTicks = new Set<number>();
  for (let state: SamplePlanState | undefined = best; state?.assignment; state = state.previous) {
    const { anchor, tick } = state.assignment;
    sourceTimes.set(tick, anchor.time);
    if (anchor.step) stepTicks.add(tick);
  }
  return { sourceTimes, stepTicks };
}

function retainBetterPlan(states: Map<number, SamplePlanState>, candidate: SamplePlanState): void {
  const current = states.get(candidate.lastTick);
  if (!current || isBetterPlan(candidate, current)) states.set(candidate.lastTick, candidate);
}

function isBetterPlan(candidate: SamplePlanState, current: SamplePlanState): boolean {
  if (candidate.preservedPriority !== current.preservedPriority) return candidate.preservedPriority > current.preservedPriority;
  if (candidate.preservedCount !== current.preservedCount) return candidate.preservedCount > current.preservedCount;
  return candidate.error < current.error;
}

function sampleCandidateError(
  sourceTime: number,
  step: boolean,
  tick: number,
  durationTicks: number,
  boneCount: number,
  snapshotAt: (time: number) => Matrix4[],
): number {
  const anchorSnapshot = snapshotAt(sourceTime);
  let error = 0;
  for (const intervalTick of [tick - 1, tick]) {
    if (intervalTick < 0 || intervalTick >= durationTicks) continue;
    const first = intervalTick === tick ? anchorSnapshot : snapshotAt(intervalTick / TICKS_PER_SECOND);
    const second = intervalTick + 1 === tick ? anchorSnapshot : snapshotAt((intervalTick + 1) / TICKS_PER_SECOND);
    for (const alpha of [0.2, 0.4, 0.6, 0.8]) {
      const exact = snapshotAt((intervalTick + alpha) / TICKS_PER_SECOND);
      for (let boneIndex = 0; boneIndex < boneCount; boneIndex++) {
        const rendered = step && intervalTick + 1 === tick
          ? first[boneIndex]
          : interpolateTransformation(first[boneIndex], second[boneIndex], alpha);
        error += transformationError(rendered, exact[boneIndex]);
      }
    }
  }
  return error;
}

function interpolateTransformation(first: Matrix4, second: Matrix4, alpha: number): Matrix4 {
  const firstPosition = new Vector3();
  const firstRotation = new Quaternion();
  const firstScale = new Vector3();
  const secondPosition = new Vector3();
  const secondRotation = new Quaternion();
  const secondScale = new Vector3();
  first.decompose(firstPosition, firstRotation, firstScale);
  second.decompose(secondPosition, secondRotation, secondScale);
  return new Matrix4().compose(
    firstPosition.lerp(secondPosition, alpha),
    firstRotation.slerp(secondRotation, alpha),
    firstScale.lerp(secondScale, alpha),
  );
}

function transformationError(actual: Matrix4, expected: Matrix4): number {
  const actualPosition = new Vector3();
  const actualRotation = new Quaternion();
  const actualScale = new Vector3();
  const expectedPosition = new Vector3();
  const expectedRotation = new Quaternion();
  const expectedScale = new Vector3();
  actual.decompose(actualPosition, actualRotation, actualScale);
  expected.decompose(expectedPosition, expectedRotation, expectedScale);
  const rotationAngle = 2 * Math.acos(Math.min(1, Math.abs(actualRotation.dot(expectedRotation))));
  return actualPosition.distanceToSquared(expectedPosition) * 16
    + rotationAngle * rotationAngle
    + actualScale.distanceToSquared(expectedScale);
}

function collectAnimationAnchors(animation: BbAnimation, animationIndex: number): AnimationAnchor[] {
  const anchors = new Map<string, AnimationAnchor>();
  const addAnchor = (time: number, priority: number, step: boolean) => {
    if (!Number.isFinite(time) || time < 0 || time > animation.length) return;
    const key = time.toFixed(6);
    const current = anchors.get(key);
    if (!current) anchors.set(key, { time, priority, step });
    else {
      current.priority = Math.max(current.priority, priority);
      current.step ||= step;
    }
  };

  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    const keyframes = animator.keyframes ?? [];
    for (const channel of ["position", "rotation", "scale"]) {
      const frames = keyframes.filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
      for (const [frameIndex, frame] of frames.entries()) {
        const discontinuity = frame.data_points.length === 2 || (frameIndex > 0 && frames[frameIndex - 1].interpolation === "step");
        addAnchor(frame.time, discontinuity ? 200 : 100, discontinuity);
      }
      for (let frameIndex = 1; frameIndex < frames.length; frameIndex++) {
        const before = frames[frameIndex - 1];
        const after = frames[frameIndex];
        addEasingFeatureAnchors(before, after, frames, channel, `animations[${animationIndex}].animators.${animatorId}`, addAnchor);
      }
    }
  }
  return [...anchors.values()].sort((first, second) => first.time - second.time);
}

function addEasingFeatureAnchors(
  before: BbKeyframe,
  after: BbKeyframe,
  channelFrames: BbKeyframe[],
  channel: string,
  path: string,
  addAnchor: (time: number, priority: number, step: boolean) => void,
): void {
  const gap = after.time - before.time;
  if (gap <= 0) return;
  const easing = (after.easing ?? "linear").toLowerCase();
  if (easing === "step") {
    const steps = Math.max(2, Math.floor(after.easingArgs?.[0] ?? 5));
    for (let step = 1; step < steps; step++) addAnchor(before.time + gap * step / steps, 160, true);
    return;
  }
  const curvedInterpolation = before.interpolation === "catmullrom" || after.interpolation === "catmullrom"
    || before.interpolation === "bezier" || after.interpolation === "bezier";
  if (!curvedInterpolation && !/(back|elastic|bounce)/.test(easing)) return;

  const fallback = channel === "scale" ? [1, 1, 1] : [0, 0, 0];
  const subdivisions = Math.max(8, Math.min(64, Math.ceil(gap * 240)));
  const samples = Array.from({ length: subdivisions + 1 }, (_, sampleIndex) => {
    const time = before.time + gap * sampleIndex / subdivisions;
    return { time, value: evaluateGeckoChannel(channelFrames, channel, time, fallback, path) };
  });
  for (let sampleIndex = 1; sampleIndex < samples.length - 1; sampleIndex++) {
    const previous = samples[sampleIndex - 1].value;
    const current = samples[sampleIndex].value;
    const next = samples[sampleIndex + 1].value;
    const turns = current.some((value, axis) => {
      const incoming = value - previous[axis];
      const outgoing = next[axis] - value;
      return Math.abs(incoming) > 1e-6 && Math.abs(outgoing) > 1e-6 && incoming * outgoing < 0;
    });
    if (turns) addAnchor(samples[sampleIndex].time, 60, false);
  }
}

function resolveBoneAnimators(animation: BbAnimation, animationIndex: number, bones: BoneEntry[]): Map<string, BbAnimator> {
  const result = new Map<string, BbAnimator>();
  const boneByUuid = new Map(bones.map((bone) => [bone.uuid, bone]));
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (boneByUuid.has(animatorId)) result.set(animatorId, animator);
  }

  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (boneByUuid.has(animatorId) || isEffectAnimator(animatorId, animator) || isHiddenAccessoryName(animator.name ?? animatorId) || (animator.keyframes?.length ?? 0) === 0) continue;
    const normalizedName = normalizeBlockbenchName(animator.name);
    const matchingBones = normalizedName
      ? bones.filter((bone) => !result.has(bone.uuid) && normalizeBlockbenchName(bone.group.name) === normalizedName)
      : [];
    if (matchingBones.length === 1) {
      result.set(matchingBones[0].uuid, animator);
      continue;
    }
    throw new ConversionError(
      "unsupported_geckolib_animator",
      `GeckoLib animation ${animation.name} contains a non-bone animator (${animator.name ?? animatorId}).`,
      `animations[${animationIndex}].animators.${animatorId}`,
    );
  }
  return result;
}

function isEffectAnimator(animatorId: string, animator: BbAnimator): boolean {
  return animatorId === "effects" || animator.type === "effect" || (animator.keyframes ?? []).every((keyframe) => ["sound", "particle", "timeline"].includes(keyframe.channel));
}

function importEffectEvents(
  animation: BbAnimation,
  animationIndex: number,
  bones: BoneEntry[],
  diagnostics: ImportDiagnostic[],
): ImportedTimelineEvent[] {
  const events: ImportedTimelineEvent[] = [];
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (!isEffectAnimator(animatorId, animator)) continue;
    for (const [keyframeIndex, keyframe] of (animator.keyframes ?? []).entries()) {
      const tick = Math.round(keyframe.time * TICKS_PER_SECOND);
      const sourcePath = `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]`;
      if (tick < 0) throw new ConversionError("invalid_geckolib_event", "GeckoLib effect keyframe time must not be negative.", sourcePath);
      for (const point of keyframe.data_points) {
        const origin = effectOrigin(point.locator, bones);
        if (keyframe.channel === "sound" && point.effect?.trim()) {
          appendTimelineEvent(events, tick, { source: { type: "player" }, origin, commands: [`playsound ${point.effect.trim()} master @s ~ ~ ~`] });
        } else if (keyframe.channel === "particle" && point.effect?.trim()) {
          appendTimelineEvent(events, tick, { source: { type: "server" }, origin, commands: [`particle ${point.effect.trim()} ~ ~ ~`] });
          if (point.script?.trim()) diagnostics.push({
            severity: "warning",
            code: "geckolib_particle_script_ignored",
            message: `Particle pre-effect script was not converted: ${point.script.trim()}`,
            sourcePath,
          });
        } else if (keyframe.channel === "timeline") {
          const lines = (point.script ?? "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
          const commands = lines.filter((line) => line.startsWith("/")).map((line) => line.slice(1).trim()).filter(Boolean);
          if (commands.length) appendTimelineEvent(events, tick, { source: { type: "player" }, origin, commands });
          const ignored = lines.filter((line) => !line.startsWith("/"));
          if (ignored.length) diagnostics.push({
            severity: "warning",
            code: "geckolib_custom_instruction_ignored",
            message: `Custom instruction was not converted because it is not a slash command: ${ignored.join("; ")}`,
            sourcePath,
          });
        }
      }
    }
  }
  return events.sort((first, second) => first.tick - second.tick);
}

function effectOrigin(locator: string | undefined, bones: BoneEntry[]): EmoteEvent["origin"] {
  const name = normalizeBlockbenchName(locator);
  if (!name) return { type: "root" };
  for (const bone of bones) {
    const locatorNode = bone.nodes.find((node) => normalizeBlockbenchName(node.locatorName) === name);
    if (locatorNode) return { type: "node", node: locatorNode.id };
  }
  const bone = bones.find((candidate) => normalizeBlockbenchName(candidate.group.name) === name);
  return bone?.nodes[0] ? { type: "node", node: bone.nodes[0].id } : { type: "root" };
}

function appendTimelineEvent(events: ImportedTimelineEvent[], tick: number, event: EmoteEvent): void {
  const matching = events.find((candidate) => candidate.tick === tick
    && JSON.stringify(candidate.source) === JSON.stringify(event.source)
    && JSON.stringify(candidate.origin) === JSON.stringify(event.origin));
  if (matching) matching.commands.push(...event.commands);
  else events.push({ ...event, tick });
}

function validateBoneAnimator(animation: BbAnimation, animationIndex: number, bone: BoneEntry, animator: BbAnimator | undefined): void {
  for (const [keyframeIndex, keyframe] of (animator?.keyframes ?? []).entries()) {
    if (!["position", "rotation", "scale"].includes(keyframe.channel)) {
      throw new ConversionError(
        "unsupported_geckolib_channel",
        `GeckoLib bone ${bone.group.name} uses unsupported channel ${keyframe.channel}.`,
        `animations[${animationIndex}].animators.${bone.uuid}.keyframes[${keyframeIndex}].channel`,
      );
    }
  }
}

function animatedWorldMatrix(
  bone: BoneEntry,
  animation: BbAnimation,
  boneAnimators: Map<string, BbAnimator>,
  time: number,
  cache: Map<string, Matrix4>,
  animationIndex: number,
  blendWeight = 1,
  convention: CubeProjectTransformConvention = GECKOLIB_BBMODEL_TRANSFORMS,
): Matrix4 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const animator = boneAnimators.get(bone.uuid);
  const animationPath = `animations[${animationIndex}].animators.${bone.uuid}`;
  const position = convention.position(
    evaluateGeckoChannel(animator?.keyframes ?? [], "position", time, [0, 0, 0], animationPath),
    (value) => -value,
  ).map((value) => value * blendWeight / 16);
  const rotationDelta = evaluateGeckoChannel(animator?.keyframes ?? [], "rotation", time, [0, 0, 0], animationPath).map((value) => value * blendWeight);
  const scale = evaluateGeckoChannel(animator?.keyframes ?? [], "scale", time, [1, 1, 1], animationPath).map((value) => 1 + (value - 1) * blendWeight);
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  const basePosition = convention.position(bone.group.origin.map((value, index) => value - parentOrigin[index]), (value) => -value).map((value) => value / 16);
  const baseRotation = convention.rotation(
    bone.group.rotation.map((value, index) => value + rotationDelta[index]),
    (value) => -value,
  );
  const local = composeDegreesTransform(basePosition.map((value, index) => value + position[index]), baseRotation, scale);
  const world = bone.parent
    ? animatedWorldMatrix(bone.parent, animation, boneAnimators, time, cache, animationIndex, blendWeight, convention).clone().multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  cache.set(bone.uuid, world);
  return world;
}

function cubeLocalMatrix(cube: BbCube, bone: BoneEntry, convention: CubeProjectTransformConvention): Matrix4 {
  const rotation = convention.rotation(cube.rotation ?? [0, 0, 0], (value) => -value);
  if (rotation.every((value) => Math.abs(value) <= 1e-7)) return new Matrix4();
  const origin = convention.position(
    (cube.origin ?? bone.group.origin).map((value, axis) => value - bone.group.origin[axis]),
    (value) => -value,
  ).map((value) => value / 16);
  return composeDegreesTransform(origin, rotation, [1, 1, 1])
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
}

function locatorLocalMatrix(locator: BbLocator, bone: BoneEntry, convention: CubeProjectTransformConvention): Matrix4 {
  return composeDegreesTransform(
    convention.position(locator.position.map((value, axis) => value - bone.group.origin[axis]), (value) => -value).map((value) => value / 16),
    convention.rotation(locator.rotation, (value) => -value),
    [1, 1, 1],
  );
}

function matrixWithoutScale(matrix: Matrix4): Matrix4 {
  const position = new Vector3();
  const rotation = new Quaternion();
  matrix.decompose(position, rotation, new Vector3());
  return new Matrix4().compose(position, rotation, new Vector3(1, 1, 1));
}

function numericValue(value: string | number, path: string): number {
  const number = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(number)) throw new ConversionError("unsupported_geckolib_molang", `GeckoLib expression ${String(value)} is not a numeric constant.`, path);
  return number;
}

function optionalNumericValue(value: string | number | undefined, fallback: number, path: string): number {
  if (value === undefined || (typeof value === "string" && value.trim() === "")) return fallback;
  return numericValue(value, path);
}

function validNamespace(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return /^[a-z0-9_.-]+$/.test(value) ? value : undefined;
}
