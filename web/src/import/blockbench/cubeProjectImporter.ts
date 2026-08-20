import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type EmoteEvent, type Matrix16 } from "../../format/emoteAnimation";
import { matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString } from "../../format/snbt";
import { requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart, ImportedTimelineEvent, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import {
  type BbAnimation,
  type BbAnimator,
  type BbCube,
  type BbGroup,
  type BbKeyframe,
  type BbLocator,
  type BbOutlinerEntry,
  type BbOutlinerGroup,
  type BbTexture,
  type BbmodelProject,
} from "./cubeProjectSchema";
import { evaluateGeckoChannel } from "./cubeAnimationBaker";

const encoder = new TextEncoder();
const SUPPORTED_FACES = new Set(["north", "south", "east", "west", "up", "down"]);
const PLAYER_RENDER_SCALE = 0.9375;
const HIDDEN_ACCESSORY_BONES = new Set(["leftitem", "rightitem", "cape"]);

interface BoneNodeEntry {
  id: string;
  localMatrix: Matrix4;
  ignoreInheritedScale?: boolean;
  locatorName?: string;
}

interface BoneEntry {
  id: string;
  uuid: string;
  group: BbGroup;
  parent?: BoneEntry;
  cubes: BbCube[];
  locators: BbLocator[];
  nodes: BoneNodeEntry[];
}

export function importBlockbenchCubeProject(project: BbmodelProject, sourceName: string): ImportedProject {
  if (project.meta.model_format !== "geckolib_model") throw new Error(`Unsupported Blockbench model format: ${project.meta.model_format}`);
  if (project.elements.some((element) => element.type && element.type !== "cube" && element.type !== "locator")) {
    throw new ConversionError("unsupported_geckolib_element", "GeckoLib meshes and non-cube elements are not supported.", "elements");
  }

  const sourceStem = sourceName.replace(/\.bbmodel$/i, "").trim() || project.name?.trim() || "GeckoLib Model";
  const namespace = validNamespace(project.geckolib_modid) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "geckolib_model");
  const resources = new Map<string, Uint8Array>();
  const bones = buildBoneEntries(project);
  if (bones.length === 0) throw new Error("GeckoLib bbmodel does not contain bones.");
  if (bones.some((bone) => bone.cubes.length > 0)) {
    const texture = requireEmbeddedTexture(project.textures);
    const texturePath = `assets/${namespace}/textures/item/${projectPath}/texture.png`;
    const textureBytes = decodeTexture(texture);
    resources.set(texturePath, textureBytes);
    const textureMetadata = animatedTextureMetadata(texture, textureBytes);
    if (textureMetadata) resources.set(`${texturePath}.mcmeta`, jsonBytes(textureMetadata));
    const resourceNodeIds = new Set(bones.map((bone) => bone.id));
    for (const bone of bones) {
      for (const [cubeIndex, cube] of bone.cubes.entries()) {
        const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, resourceNodeIds);
        writeCubeResources(project, bone, cube, namespace, `${projectPath}/${nodeId}`, resources);
      }
    }
  }
  const playableCubesByBone = new Map(bones.map((bone) => [
    bone.uuid,
    splitTallSkinCubes(bone, removeDuplicateSkinLayers(bone.cubes)),
  ]));
  const skinAssignments = inferSkinAssignments(bones, playableCubesByBone);
  const nodes: Record<string, ImportedNode> = {};
  const nodeIds = new Set(bones.map((bone) => bone.id));
  for (const bone of bones) {
    const boneMatrix = new Matrix4().set(...boneWorldMatrix(bone, new Map()));
    const playableCubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (playableCubes.length === 0) {
      nodes[bone.id] = { id: bone.id, type: "anchor", defaultMatrix: matrix4ToRowMajor(boneMatrix, `GeckoLib bone ${bone.id}`) };
      bone.nodes.push({ id: bone.id, localMatrix: new Matrix4() });
    } else for (const [cubeIndex, cube] of playableCubes.entries()) {
      const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, nodeIds);
      const hiddenAccessory = isHiddenAccessoryBone(bone);
      const conversionMatrix = hiddenAccessory ? undefined : cubePlayerHeadMatrix(cube, bone);
      if (!hiddenAccessory && !conversionMatrix) throw new ConversionError("invalid_geckolib_cube", `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const skin = hiddenAccessory ? undefined : skinAssignments.get(cube.uuid);
      const localMatrix = cubeLocalMatrix(cube, bone);
      bone.nodes.push({ id: nodeId, localMatrix });
      const modelPath = `${projectPath}/${nodeId}`;
      writeCubeResources(project, bone, cube, namespace, modelPath, resources);
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
        ...(skin ? { suggestedSkin: skin } : {}),
      };
    }
    for (const [locatorIndex, locator] of bone.locators.entries()) {
      const nodeId = uniqueLocatorNodeId(bone, locator, locatorIndex, nodeIds);
      const localMatrix = locatorLocalMatrix(locator, bone);
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
  const diagnostics: ImportDiagnostic[] = [];
  const animations = project.animations.map((animation, index) => {
    try {
      return importAnimation(animation, index, bones, diagnostics);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_geckolib_molang") throw reason;
      const message = `${animation.name} contains Molang that the converter cannot evaluate. Only the Create pose is available; animation export is disabled. To fix it, replace the expression at ${reason.sourcePath ?? `animations[${index}]`} with constants or q.anim_time-based Molang.`;
      diagnostics.push({
        severity: "warning",
        code: "geckolib_animation_molang_unavailable",
        message,
        sourcePath: reason.sourcePath ?? `animations[${index}]`,
      });
      return createPreviewOnlyAnimation(animation, index, message);
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

function createPreviewOnlyAnimation(animation: BbAnimation, index: number, reason: string): ImportedAnimation {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks: Number.isFinite(animation.length) && animation.length > 0
      ? Math.max(1, Math.round(animation.length * TICKS_PER_SECOND))
      : TICKS_PER_SECOND,
    loop: playbackMode === "loop" || playbackMode === "hold" ? playbackMode : "once",
    loopDelayTicks: 0,
    tracks: {},
    events: { start: [], timeline: [], loop: [], stop: [] },
    availability: { preview: "create_pose", exportable: false, reason },
  };
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

function uniqueCubeNodeId(bone: BoneEntry, cube: BbCube, cubeIndex: number, ids: Set<string>): string {
  const cubeName = sanitizeResourcePath(cube.name?.trim() || `cube_${cubeIndex + 1}`, `cube_${cubeIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${cubeName}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

function uniqueLocatorNodeId(bone: BoneEntry, locator: BbLocator, locatorIndex: number, ids: Set<string>): string {
  const name = sanitizeResourcePath(locator.name?.trim() || `locator_${locatorIndex + 1}`, `locator_${locatorIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${name}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

function boneWorldMatrix(bone: BoneEntry, cache: Map<string, Matrix16>): Matrix16 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const local = bindLocalMatrix(bone);
  const world = bone.parent
    ? new Matrix4().set(...boneWorldMatrix(bone.parent, cache)).multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  const result = matrix4ToRowMajor(world, `GeckoLib bone ${bone.id}`);
  cache.set(bone.uuid, result);
  return result;
}

function bindLocalMatrix(bone: BoneEntry): Matrix4 {
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  return composeTransform(
    bone.group.origin.map((value, index) => (value - parentOrigin[index]) / 16),
    bone.group.rotation,
    [1, 1, 1],
  );
}

function importAnimation(animation: BbAnimation, index: number, bones: BoneEntry[], diagnostics: ImportDiagnostic[]): ImportedAnimation {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  if (playbackMode !== "once" && playbackMode !== "hold" && playbackMode !== "loop") throw new Error(`GeckoLib animation ${animation.name} has unsupported loop mode ${loop}.`);
  if (!Number.isFinite(animation.length) || animation.length < 0) throw new Error(`GeckoLib animation ${animation.name} has an invalid length.`);
  const effectEvents = importEffectEvents(animation, index, bones, diagnostics);
  const durationTicks = requireAnimationDurationTicks(
    Math.max(1, Math.round(animation.length * TICKS_PER_SECOND), ...effectEvents.map((event) => event.tick + 1)),
    `${animation.name}.length`,
  );
  const boneAnimators = resolveBoneAnimators(animation, index, bones);
  const samplePlan = planAnimationSamples(animation, index, bones, boneAnimators, durationTicks);
  const tracks: ImportedAnimation["tracks"] = {};
  for (const bone of bones) {
    validateBoneAnimator(animation, index, bone, boneAnimators.get(bone.uuid));
    const transforms: ImportedTransformKeyframe[] = [];
    for (let tick = 0; tick <= durationTicks; tick++) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = samplePlan.sourceTimes.get(tick) ?? tick / TICKS_PER_SECOND;
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, animation, boneAnimators, sourceTime, cache, index), `${animation.name}/${bone.id}/${tick}`),
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
): AnimationSamplePlan {
  const anchors = collectAnimationAnchors(animation, animationIndex);
  if (anchors.length === 0) return { sourceTimes: new Map(), stepTicks: new Set() };

  const snapshots = new Map<string, Matrix4[]>();
  const snapshotAt = (time: number) => {
    const key = time.toFixed(9);
    const cached = snapshots.get(key);
    if (cached) return cached;
    const cache = new Map<string, Matrix4>();
    const result = bones.map((bone) => animatedWorldMatrix(bone, animation, boneAnimators, time, cache, animationIndex).clone());
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
    const normalizedName = normalizeBoneName(animator.name);
    const matchingBones = normalizedName
      ? bones.filter((bone) => !result.has(bone.uuid) && normalizeBoneName(bone.group.name) === normalizedName)
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
  const name = normalizeBoneName(locator);
  if (!name) return { type: "root" };
  for (const bone of bones) {
    const locatorNode = bone.nodes.find((node) => normalizeBoneName(node.locatorName) === name);
    if (locatorNode) return { type: "node", node: locatorNode.id };
  }
  const bone = bones.find((candidate) => normalizeBoneName(candidate.group.name) === name);
  return bone?.nodes[0] ? { type: "node", node: bone.nodes[0].id } : { type: "root" };
}

function appendTimelineEvent(events: ImportedTimelineEvent[], tick: number, event: EmoteEvent): void {
  const matching = events.find((candidate) => candidate.tick === tick
    && JSON.stringify(candidate.source) === JSON.stringify(event.source)
    && JSON.stringify(candidate.origin) === JSON.stringify(event.origin));
  if (matching) matching.commands.push(...event.commands);
  else events.push({ ...event, tick });
}

function normalizeBoneName(name: string | undefined): string | undefined {
  const normalized = name?.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  return normalized || undefined;
}

function isHiddenAccessoryName(name: string | undefined): boolean {
  const normalized = normalizeBoneName(name);
  return normalized !== undefined && HIDDEN_ACCESSORY_BONES.has(normalized);
}

function isHiddenAccessoryBone(bone: BoneEntry): boolean {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    if (isHiddenAccessoryName(current.group.name)) return true;
  }
  return false;
}

function removeDuplicateSkinLayers(cubes: BbCube[]): BbCube[] {
  return cubes.filter((cube, cubeIndex) => !cubes.some((other, otherIndex) => {
    if (cubeIndex === otherIndex || !sameCubeBounds(cube, other)) return false;
    const nameMarksLayer = normalizeBoneName(cube.name)?.includes("layer") ?? false;
    return nameMarksLayer || (cube.inflate ?? 0) > (other.inflate ?? 0);
  }));
}

function splitTallSkinCubes(bone: BoneEntry, cubes: BbCube[]): BbCube[] {
  const part = inferSkinPart(bone);
  return cubes.flatMap((cube) => {
    if (!part || part === "head" || !isStandardPlayerSkinCube(cube, part)) return [cube];
    const height = cube.to[1] - cube.from[1];
    const upperHeight = height / 3;
    const splitY = cube.to[1] - upperHeight;
    const [upperFaces, lowerFaces] = splitVerticalFaceUvs(cube.faces, upperHeight / height);
    return [
      {
        ...cube,
        uuid: `${cube.uuid}_skin_upper`,
        name: `${cube.name ?? "Cube"} Upper`,
        from: [cube.from[0], splitY, cube.from[2]],
        faces: upperFaces,
      },
      {
        ...cube,
        uuid: `${cube.uuid}_skin_lower`,
        name: `${cube.name ?? "Cube"} Lower`,
        to: [cube.to[0], splitY, cube.to[2]],
        faces: lowerFaces,
      },
    ];
  });
}

function splitVerticalFaceUvs(faces: BbCube["faces"], upperRatio: number): [BbCube["faces"], BbCube["faces"]] {
  const upperFaces: BbCube["faces"] = {};
  const lowerFaces: BbCube["faces"] = {};
  for (const [direction, face] of Object.entries(faces)) {
    if (!["north", "south", "east", "west"].includes(direction) || face.uv?.length !== 4) {
      upperFaces[direction] = face;
      lowerFaces[direction] = face;
      continue;
    }

    const [minU, minV, maxU, maxV] = face.uv;
    const rotation = ((face.rotation ?? 0) % 360 + 360) % 360;
    if (rotation === 90) {
      const splitU = minU + (maxU - minU) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, minV, splitU, maxV] };
      lowerFaces[direction] = { ...face, uv: [splitU, minV, maxU, maxV] };
    } else if (rotation === 180) {
      const splitV = maxV + (minV - maxV) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, splitV, maxU, maxV] };
      lowerFaces[direction] = { ...face, uv: [minU, minV, maxU, splitV] };
    } else if (rotation === 270) {
      const splitU = maxU + (minU - maxU) * upperRatio;
      upperFaces[direction] = { ...face, uv: [splitU, minV, maxU, maxV] };
      lowerFaces[direction] = { ...face, uv: [minU, minV, splitU, maxV] };
    } else {
      const splitV = minV + (maxV - minV) * upperRatio;
      upperFaces[direction] = { ...face, uv: [minU, minV, maxU, splitV] };
      lowerFaces[direction] = { ...face, uv: [minU, splitV, maxU, maxV] };
    }
  }
  return [upperFaces, lowerFaces];
}

function sameCubeBounds(first: BbCube, second: BbCube): boolean {
  return first.from.every((value, axis) => Math.abs(value - second.from[axis]) <= 1e-7)
    && first.to.every((value, axis) => Math.abs(value - second.to[axis]) <= 1e-7);
}

function inferSkinAssignments(
  bones: BoneEntry[],
  playableCubesByBone: ReadonlyMap<string, BbCube[]>,
): Map<string, ImportedSkinPart> {
  const candidates: { cube: BbCube; part: ImportedSkinPart["part"]; centerY: number; sourceOrder: number }[] = [];
  let sourceOrder = 0;
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    for (const cube of playableCubesByBone.get(bone.uuid) ?? []) {
      const isSplitSkinCube = cube.uuid.endsWith("_skin_upper") || cube.uuid.endsWith("_skin_lower");
      if (!part || (!isSplitSkinCube && !isStandardPlayerSkinCube(cube, part))) continue;
      candidates.push({ cube, part, centerY: (cube.from[1] + cube.to[1]) / 2, sourceOrder: sourceOrder++ });
    }
  }

  const result = new Map<string, ImportedSkinPart>();
  for (const part of ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const) {
    candidates
      .filter((candidate) => candidate.part === part)
      .sort((first, second) => second.centerY - first.centerY || first.sourceOrder - second.sourceOrder)
      .forEach((candidate, order) => result.set(candidate.cube.uuid, { part, order }));
  }
  return result;
}

function isStandardPlayerSkinCube(cube: BbCube, part: ImportedSkinPart["part"]): boolean {
  const size = cube.to.map((value, axis) => Math.abs(value - cube.from[axis]));
  const closeTo = (value: number, expected: number) => Math.abs(value - expected) <= 1e-3;
  if (part === "head") return closeTo(size[0], 8) && closeTo(size[1], 8) && closeTo(size[2], 8);
  if (part === "body") return closeTo(size[0], 8) && closeTo(size[1], 12) && closeTo(size[2], 4);
  return (closeTo(size[0], 3) || closeTo(size[0], 4)) && closeTo(size[1], 12) && closeTo(size[2], 4);
}

function inferSkinPart(bone: BoneEntry): ImportedSkinPart["part"] | undefined {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    const name = normalizeBoneName(current.group.name) ?? "";
    if (name.includes("left") && (name.includes("arm") || name.includes("hand") || name.includes("wing"))) return "left_arm";
    if (name.includes("right") && (name.includes("arm") || name.includes("hand") || name.includes("wing"))) return "right_arm";
    if (name.includes("left") && (name.includes("leg") || name.includes("foot"))) return "left_leg";
    if (name.includes("right") && (name.includes("leg") || name.includes("foot"))) return "right_leg";
    if (name.includes("head") || name.includes("face") || name.includes("skull")) return "head";
    if (name.includes("body") || name.includes("torso") || name.includes("chest") || name.includes("waist")) return "body";
  }
  return undefined;
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
): Matrix4 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const animator = boneAnimators.get(bone.uuid);
  const animationPath = `animations[${animationIndex}].animators.${bone.uuid}`;
  const position = evaluateGeckoChannel(animator?.keyframes ?? [], "position", time, [0, 0, 0], animationPath).map((value) => value / 16);
  const rotationDelta = evaluateGeckoChannel(animator?.keyframes ?? [], "rotation", time, [0, 0, 0], animationPath);
  const scale = evaluateGeckoChannel(animator?.keyframes ?? [], "scale", time, [1, 1, 1], animationPath);
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  const basePosition = bone.group.origin.map((value, index) => (value - parentOrigin[index]) / 16);
  const baseRotation = bone.group.rotation.map((value, index) => value + rotationDelta[index]);
  const local = composeTransform(basePosition.map((value, index) => value + position[index]), baseRotation, scale);
  const world = bone.parent
    ? animatedWorldMatrix(bone.parent, animation, boneAnimators, time, cache, animationIndex).clone().multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  cache.set(bone.uuid, world);
  return world;
}

function composeTransform(position: number[], rotation: number[], scale: number[]): Matrix4 {
  return new Matrix4().compose(
    new Vector3(position[0], position[1], position[2]),
    new Quaternion().setFromEuler(new Euler(
      MathUtils.degToRad(rotation[0]),
      MathUtils.degToRad(rotation[1]),
      MathUtils.degToRad(rotation[2]),
      "ZYX",
    )),
    new Vector3(scale[0], scale[1], scale[2]),
  );
}

function writeCubeResources(
  project: BbmodelProject,
  bone: BoneEntry,
  cube: BbCube,
  namespace: string,
  modelPath: string,
  resources: Map<string, Uint8Array>,
): void {
  const model = {
    textures: { layer0: `${namespace}:item/${modelPath.split("/").slice(0, -1).join("/")}/texture` },
    elements: [cubeModelElement(cube, bone.group.origin, project.resolution)],
  };
  resources.set(`assets/${namespace}/models/item/${modelPath}.json`, jsonBytes(model));
  resources.set(`assets/${namespace}/items/${modelPath}.json`, jsonBytes({ model: { type: "minecraft:model", model: `${namespace}:item/${modelPath}` } }));
}

function cubePlayerHeadMatrix(cube: BbCube, bone: BoneEntry): Matrix16 | undefined {
  const inflate = cube.inflate ?? 0;
  const from = cube.from.map((value, axis) => (value - bone.group.origin[axis] - inflate) / 16);
  const to = cube.to.map((value, axis) => (value - bone.group.origin[axis] + inflate) / 16);
  const size = to.map((value, axis) => value - from[axis]);
  if (size.some((value) => value <= 0)) return undefined;

  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  return matrix4ToRowMajor(fit, `GeckoLib cube ${cube.name ?? cube.uuid} player head conversion`);
}

function cubeLocalMatrix(cube: BbCube, bone: BoneEntry): Matrix4 {
  const rotation = cube.rotation ?? [0, 0, 0];
  if (rotation.every((value) => Math.abs(value) <= 1e-7)) return new Matrix4();
  const origin = (cube.origin ?? bone.group.origin).map((value, axis) => (value - bone.group.origin[axis]) / 16);
  return composeTransform(origin, rotation, [1, 1, 1])
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
}

function locatorLocalMatrix(locator: BbLocator, bone: BoneEntry): Matrix4 {
  return composeTransform(
    locator.position.map((value, axis) => (value - bone.group.origin[axis]) / 16),
    locator.rotation,
    [1, 1, 1],
  );
}

function matrixWithoutScale(matrix: Matrix4): Matrix4 {
  const position = new Vector3();
  const rotation = new Quaternion();
  matrix.decompose(position, rotation, new Vector3());
  return new Matrix4().compose(position, rotation, new Vector3(1, 1, 1));
}

function cubeModelElement(cube: BbCube, boneOrigin: number[], resolution: { width: number; height: number }): Record<string, unknown> {
  const inflate = cube.inflate ?? 0;
  const offset = (value: number, axis: number, direction: -1 | 1) => value - boneOrigin[axis] + 8 + inflate * direction;
  const faces = Object.fromEntries(Object.entries(cube.faces).flatMap(([direction, face]) => {
    if (!SUPPORTED_FACES.has(direction) || face.enabled === false || face.texture === null || face.uv == null) return [];
    return [[direction, {
      uv: [
        face.uv[0] * 16 / resolution.width,
        face.uv[1] * 16 / resolution.height,
        face.uv[2] * 16 / resolution.width,
        face.uv[3] * 16 / resolution.height,
      ],
      texture: "#layer0",
      ...(face.rotation == null || face.rotation === 0 ? {} : { rotation: face.rotation }),
    }]];
  }));
  return {
    from: cube.from.map((value, axis) => offset(value, axis, -1)),
    to: cube.to.map((value, axis) => offset(value, axis, 1)),
    faces,
  };
}

function requireEmbeddedTexture(textures: BbTexture[]): BbTexture {
  if (textures.length !== 1) throw new Error(`GeckoLib bbmodel must contain exactly one texture; found ${textures.length}.`);
  const texture = textures[0];
  if (!texture.source?.startsWith("data:image/png;base64,")) {
    throw new ConversionError("geckolib_external_texture", "GeckoLib texture must be embedded in the bbmodel as PNG data.", "textures[0].source");
  }
  return texture;
}

function decodeTexture(texture: BbTexture): Uint8Array {
  const base64 = texture.source!.slice("data:image/png;base64,".length);
  try {
    return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
  } catch (error) {
    throw new ConversionError("invalid_geckolib_texture", "GeckoLib embedded texture is not valid base64.", "textures[0].source", { cause: error });
  }
}

function animatedTextureMetadata(texture: BbTexture, bytes: Uint8Array): Record<string, unknown> | undefined {
  const configured = texture.frame_time !== undefined
    || texture.frame_interpolate !== undefined
    || texture.frame_order_type !== undefined
    || texture.frame_order !== undefined;
  if (!configured) return undefined;
  const frameTime = texture.frame_time ?? 1;
  if (!Number.isInteger(frameTime) || frameTime < 1) throw new ConversionError("invalid_geckolib_texture_animation", "GeckoLib texture frame time must be a positive integer.");
  const orderType = texture.frame_order_type ?? "loop";
  const frames = textureFrames(orderType, texture.frame_order, pngFrameCount(bytes));
  return {
    animation: {
      frametime: frameTime,
      ...(texture.frame_interpolate ? { interpolate: true } : {}),
      ...(frames ? { frames } : {}),
    },
  };
}

function textureFrames(orderType: NonNullable<BbTexture["frame_order_type"]>, frameOrder: string | undefined, frameCount: number | undefined): number[] | undefined {
  if (orderType === "loop") return undefined;
  if (orderType === "custom") {
    const frames = (frameOrder ?? "").trim().split(/\s+/).filter(Boolean).map(Number);
    if (frames.length === 0 || frames.some((frame) => !Number.isInteger(frame) || frame < 0)) {
      throw new ConversionError("invalid_geckolib_texture_animation", "GeckoLib custom texture frame order must contain non-negative frame numbers.");
    }
    return frames;
  }
  if (frameCount === undefined || frameCount < 2) {
    throw new ConversionError("invalid_geckolib_texture_animation", `GeckoLib ${orderType} texture animation requires a vertical PNG sprite sheet.`);
  }
  const forward = Array.from({ length: frameCount }, (_, index) => index);
  if (orderType === "backwards") return forward.reverse();
  return [...forward, ...forward.slice(1, -1).reverse()];
}

function pngFrameCount(bytes: Uint8Array): number | undefined {
  if (bytes.length < 24 || bytes[0] !== 0x89 || bytes[1] !== 0x50 || bytes[2] !== 0x4e || bytes[3] !== 0x47) return undefined;
  const width = readUint32(bytes, 16);
  const height = readUint32(bytes, 20);
  return width > 0 && height >= width && height % width === 0 ? height / width : undefined;
}

function readUint32(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function numericValue(value: string | number, path: string): number {
  const number = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(number)) throw new ConversionError("unsupported_geckolib_molang", `GeckoLib expression ${String(value)} is not a numeric constant.`, path);
  return number;
}

function validNamespace(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return /^[a-z0-9_.-]+$/.test(value) ? value : undefined;
}

function jsonBytes(value: unknown): Uint8Array {
  return encoder.encode(`${JSON.stringify(value, null, 2)}\n`);
}
