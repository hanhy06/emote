import { itemModelResourcePath, type GeneratedResource } from "../../domain/generatedResource";
import { Matrix4, Quaternion, Vector3 } from "three";
import type { EmoteEvent, Matrix16, MolangScalar } from "../../format/emoteAnimation";
import { composeDegreesTransform, matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtString } from "../../format/snbt";
import { formatMinecraftTime, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError, PreviewUnavailableError } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedTimelineEvent, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import type { BakedRuntimeNodeTracks, BakedRuntimeTransformKeyframe } from "../../domain/minecraftData";
import type { PreviewNodeTrack, PreviewProjection } from "../../domain/previewProjection";
import type { AnimationRuntimeData } from "../../domain/runtimeProjection";
import {
  type BbAnimation,
  type BbAnimator,
  type BbCube,
  type BbGroup,
  type BbKeyframe,
  type BbLocator,
  type BbOutlinerEntry,
  type BbOutlinerGroup,
  type BlockbenchCubeProject,
} from "./blockbenchCubeSchema";
import type { BlockbenchChannelEvaluator } from "./blockbenchKeyframeEvaluator";
import { blockbenchIntervalIsStep } from "./animationEasing";
import {
  uniqueCubeNodeId,
  writeCubeResources,
  writeSourceCubeResources,
} from "./blockbenchCubeResources";
import {
  cubePlayerHeadMatrix,
  isHiddenAccessoryBone,
  isHiddenAccessoryName,
  normalizeBlockbenchName,
  prepareCubeModels,
} from "./blockbenchCubeSkin";
import { ZERO_VECTOR } from "./runtimeOutput";
import type { CubeProjectTransformConvention } from "./blockbenchCubeTransform";
import { planAnimationSamples } from "./blockbenchAnimationSampling";
import type { AnimationSamplePlan } from "./animationSampling";
import type { BoneEntry } from "./blockbenchCubeModel";
import { usesRuntimeMolangState } from "../../format/molang/runtimeAnalysis";
import type { BlockbenchAnimationSource, BlockbenchTransformChannel } from "./blockbenchAnimationSource";
import {
  PLAYER_RENDER_SCALE,
  type BlockbenchNativeRuntimeFactory,
} from "./blockbenchNativeRuntime";
import { createMolangPreviewFallback } from "./previewFallback";

export interface CubeProjectImportOptions {
  transforms: CubeProjectTransformConvention;
  formatLabel: string;
  diagnosticPrefix: string;
  runtimeSceneId: string;
  channels: BlockbenchChannelEvaluator;
  runtimeOutput?: "auto" | "native";
  createNativeRuntime: BlockbenchNativeRuntimeFactory;
  namespace?: string;
}

export interface ImportedCubeProjectContent {
  sourceStem: string;
  namespace: string;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  resources: Map<string, GeneratedResource>;
  runtimeSceneId: string;
  runtimeParentByGroupUuid: Readonly<Record<string, string>>;
  editorNodeIdsBySourceUuid: Readonly<Record<string, readonly string[]>>;
}

export function importBlockbenchCubeContent(
  project: BlockbenchCubeProject,
  sourceName: string,
  options: CubeProjectImportOptions,
): ImportedCubeProjectContent {
  const formatLabel = options.formatLabel;
  const sourceStem = sourceName.replace(/\.[^.]+$/i, "").trim() || project.name?.trim() || `${formatLabel} Model`;
  const namespace = validNamespace(options.namespace) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "model");
  const resources = new Map<string, GeneratedResource>();
  const transforms = options.transforms;
  const bones = buildBoneEntries(project, formatLabel);
  if (bones.length === 0) throw new Error(`${formatLabel} cube project does not contain bones.`);
  writeSourceCubeResources(project, bones, namespace, projectPath, resources, transforms);
  const { playableCubesByBone, skinAssignments } = prepareCubeModels(bones);
  const diagnostics: ImportDiagnostic[] = [];
  const nodes: Record<string, ImportedNode> = {};
  const editorNodeIdsBySourceUuid = new Map<string, string[]>();
  const bindEditorNode = (sourceId: string, nodeId: string) => editorNodeIdsBySourceUuid.set(sourceId, [...(editorNodeIdsBySourceUuid.get(sourceId) ?? []), nodeId]);
  const nodeIds = new Set(bones.map((bone) => bone.id));
  for (const bone of bones) {
    const boneMatrix = new Matrix4().set(...boneWorldMatrix(bone, new Map(), transforms, formatLabel));
    const playableCubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (playableCubes.length === 0) {
      nodes[bone.id] = {
        binding: { sourceNodeId: bone.id, spaceGroupId: options.runtimeSceneId },
        type: "anchor",
        defaultMatrix: matrix4ToRowMajor(boneMatrix, `${formatLabel} bone ${bone.id}`),
        space: "initiator",
      };
      bone.nodes.push({ id: bone.id, localMatrix: new Matrix4() });
      bindEditorNode(bone.uuid, bone.id);
    } else for (const [cubeIndex, cube] of playableCubes.entries()) {
      const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, nodeIds);
      const hiddenAccessory = isHiddenAccessoryBone(bone);
      const conversionMatrix = hiddenAccessory ? undefined : cubePlayerHeadMatrix(cube, bone, transforms);
      if (!hiddenAccessory && !conversionMatrix) throw new ConversionError(`invalid_${options.diagnosticPrefix}_cube`, `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const skin = hiddenAccessory ? undefined : skinAssignments.get(cube.uuid);
      const localMatrix = cubeLocalMatrix(cube, bone, transforms);
      bone.nodes.push({ id: nodeId, localMatrix });
      const modelPath = `${projectPath}/${nodeId}`;
      writeCubeResources(project, bone, cube, namespace, modelPath, resources, transforms);
      nodes[nodeId] = {
        binding: {
          sourceNodeId: nodeId,
          spaceGroupId: options.runtimeSceneId,
          ...(skin ? { skinGroupId: `${skin.part}_${skin.order}` } : {}),
        },
        type: "item_display",
        defaultMatrix: matrix4ToRowMajor(boneMatrix.clone().multiply(localMatrix), `${formatLabel} cube ${nodeId}`),
        visible: true,
        space: "initiator",
        itemDisplay: "none",
        itemStack: {
          id: "minecraft:paper",
          count: 1,
          components: [{ name: "minecraft:item_model", value: serializeSnbtString(`${namespace}:${modelPath}`) }],
          generatedResourceReferences: [itemModelResourcePath(namespace, modelPath)],
        },
        ...(conversionMatrix ? { playerHeadConversion: { matrix: conversionMatrix } } : {}),
        ...(skin ? { suggestedSkin: skin } : {}),
      };
      bindEditorNode(bone.uuid, nodeId);
      bindEditorNode(cube.uuid, nodeId);
    }
    for (const [locatorIndex, locator] of bone.locators.entries()) {
      const nodeId = uniqueLocatorNodeId(bone, locator, locatorIndex, nodeIds);
      const localMatrix = locatorLocalMatrix(locator, bone, transforms);
      const locatorBoneMatrix = locator.ignore_inherited_scale ? matrixWithoutScale(boneMatrix) : boneMatrix;
      bone.nodes.push({ id: nodeId, localMatrix, ignoreInheritedScale: locator.ignore_inherited_scale, locatorName: locator.name });
      nodes[nodeId] = {
        binding: { sourceNodeId: nodeId, spaceGroupId: options.runtimeSceneId },
        type: "anchor",
        defaultMatrix: matrix4ToRowMajor(locatorBoneMatrix.clone().multiply(localMatrix), `${formatLabel} locator ${nodeId}`),
        space: "initiator",
      };
      bindEditorNode(bone.uuid, nodeId);
      bindEditorNode(locator.uuid, nodeId);
    }
  }

  if (project.animations.length === 0) throw new Error(`${formatLabel} cube project does not contain animations.`);
  const animations = project.animations.map((animation, index) =>
    importAnimation(animation, index, bones, nodes, diagnostics, options));
  return {
    sourceStem,
    namespace,
    nodes,
    animations,
    diagnostics,
    resources,
    runtimeSceneId: options.runtimeSceneId,
    runtimeParentByGroupUuid: Object.fromEntries(bones.map((bone) => [bone.uuid, `${bone.id}_x`])),
    editorNodeIdsBySourceUuid: Object.fromEntries(editorNodeIdsBySourceUuid),
  };
}

function buildBoneEntries(project: BlockbenchCubeProject, formatLabel: string): BoneEntry[] {
  const groups = new Map(project.groups.map((group) => [group.uuid, group]));
  const elements = new Map(project.elements.map((element) => [element.uuid, element]));
  const entries: BoneEntry[] = [];
  const ids = new Set<string>();
  const visit = (entry: BbOutlinerEntry, parent?: BoneEntry) => {
    if (typeof entry === "string") {
      if (!parent) throw new Error(`${formatLabel} cube ${entry} is not parented to a bone.`);
      const element = elements.get(entry);
      if (!element) throw new Error(`${formatLabel} outliner references unknown element ${entry}.`);
      if (isLocator(element)) parent.locators.push(element);
      else parent.cubes.push(element);
      return;
    }
    const saved = groups.get(entry.uuid);
    const group = mergeGroup(saved, entry, formatLabel);
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

function isLocator(element: BlockbenchCubeProject["elements"][number]): element is BbLocator {
  return element.type === "locator";
}

function mergeGroup(saved: BbGroup | undefined, outliner: BbOutlinerGroup, formatLabel: string): BbGroup {
  const name = outliner.name ?? saved?.name;
  const origin = outliner.origin ?? saved?.origin;
  const rotation = outliner.rotation ?? saved?.rotation ?? [0, 0, 0];
  if (!name || !origin) throw new Error(`${formatLabel} bone ${outliner.uuid} is missing its saved group data.`);
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

function boneWorldMatrix(bone: BoneEntry, cache: Map<string, Matrix16>, convention: CubeProjectTransformConvention, formatLabel: string): Matrix16 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const local = bindLocalMatrix(bone, convention);
  const world = bone.parent
    ? new Matrix4().set(...boneWorldMatrix(bone.parent, cache, convention, formatLabel)).multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  const result = matrix4ToRowMajor(world, `${formatLabel} bone ${bone.id}`);
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

function importAnimation(
  animation: BbAnimation,
  index: number,
  bones: BoneEntry[],
  nodes: Record<string, ImportedNode>,
  diagnostics: ImportDiagnostic[],
  options: CubeProjectImportOptions,
): ImportedAnimation {
  const { channels, createNativeRuntime, diagnosticPrefix, formatLabel, transforms: convention } = options;
  const source = resolveBlockbenchAnimationSource(animation, index, bones, diagnostics, options);
  const runtime = projectBlockbenchRuntime(source, bones, nodes, convention, channels, createNativeRuntime, formatLabel, diagnosticPrefix);
  let preview: PreviewProjection;
  try {
    preview = projectBlockbenchPreview(source, bones, convention, channels, formatLabel, diagnosticPrefix);
  } catch (reason) {
    if (!(reason instanceof PreviewUnavailableError)) throw reason;
    const fallback = createMolangPreviewFallback(animation.name, source.durationTicks, reason);
    preview = fallback.preview;
    diagnostics.push(fallback.diagnostic);
  }
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks: source.durationTicks,
    playbackMode: source.playbackMode,
    loopDelayTicks: source.loopDelayTicks,
    events: { start: [], timeline: source.events, loop: [], stop: [] },
    preview,
    exportAvailability: { exportable: true },
    runtime,
  };
}

function resolveBlockbenchAnimationSource(
  animation: BbAnimation,
  index: number,
  bones: BoneEntry[],
  diagnostics: ImportDiagnostic[],
  options: CubeProjectImportOptions,
): BlockbenchAnimationSource {
  const { channels, diagnosticPrefix, formatLabel, transforms: convention } = options;
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  if (playbackMode !== "once" && playbackMode !== "hold" && playbackMode !== "loop") throw new Error(`${formatLabel} animation ${animation.name} has unsupported loop mode ${loop}.`);
  if (!Number.isFinite(animation.length) || animation.length < 0) throw new Error(`${formatLabel} animation ${animation.name} has an invalid length.`);
  const startDelaySeconds = optionalNumericValue(animation.start_delay, 0, `animations[${index}].start_delay`, formatLabel, diagnosticPrefix);
  const blendWeight = optionalNumericValue(animation.blend_weight, 1, `animations[${index}].blend_weight`, formatLabel, diagnosticPrefix);
  const effectEvents = importEffectEvents(animation, index, bones, diagnostics, formatLabel, diagnosticPrefix);
  const durationTicks = requireAnimationDurationTicks(
    Math.max(1, Math.round((animation.length + startDelaySeconds) * TICKS_PER_SECOND), ...effectEvents.map((event) => event.tick + 1)),
    `${animation.name}.length`,
  );
  const boneAnimators = resolveBoneAnimators(animation, index, bones, formatLabel, diagnosticPrefix);
  let nativeRuntime = options.runtimeOutput === "native" || blockbenchAnimationUsesRuntimeState(animation);
  let samplePlan: AnimationSamplePlan | undefined;
  if (!blockbenchAnimationUsesRuntimeState(animation)) {
    const snapshots = new Map<string, Matrix4[]>();
    const snapshotAt = (time: number) => {
      const key = time.toFixed(9);
      const cached = snapshots.get(key);
      if (cached) return cached;
      const cache = new Map<string, Matrix4>();
      const result = bones.map((bone) => animatedWorldMatrix(bone, animation, boneAnimators, time, cache, index, 1, convention, channels.evaluate).clone());
      snapshots.set(key, result);
      return result;
    };
    try {
      samplePlan = planAnimationSamples(animation, index, durationTicks, bones.length, snapshotAt, channels);
    } catch (reason) {
      if (!channels.isBakeFallbackError(reason)) throw reason;
      nativeRuntime = true;
    }
  }
  const startDelayTicks = Math.round(startDelaySeconds * TICKS_PER_SECOND);
  return {
    animation,
    animationIndex: index,
    animators: boneAnimators,
    durationTicks,
    startDelaySeconds,
    startDelayTicks,
    blendWeight,
    playbackMode,
    loopDelayTicks: playbackMode === "loop"
      ? Math.round(numericValue(animation.loop_delay ?? 0, `animations[${index}].loop_delay`, formatLabel, diagnosticPrefix) * TICKS_PER_SECOND)
      : 0,
    events: effectEvents,
    requiresNativeRuntime: nativeRuntime,
    channelSampling: createChannelSampling(bones, samplePlan),
  };
}

function createChannelSampling(
  bones: readonly BoneEntry[],
  samplePlan: AnimationSamplePlan | undefined,
): BlockbenchAnimationSource["channelSampling"] {
  const sourceTimes = samplePlan?.sourceTimes ?? new Map<number, number>();
  const stepTicks = samplePlan?.stepTicks ?? new Set<number>();
  const result = new Map<string, Partial<Record<BlockbenchTransformChannel, { sourceTimes: ReadonlyMap<number, number>; stepTicks: ReadonlySet<number> }>>>();
  for (const bone of bones) {
    const channels: Partial<Record<BlockbenchTransformChannel, { sourceTimes: ReadonlyMap<number, number>; stepTicks: ReadonlySet<number> }>> = {};
    for (const channel of ["position", "rotation", "scale"] as const) {
      channels[channel] = { sourceTimes: new Map(sourceTimes), stepTicks: new Set(stepTicks) };
    }
    result.set(bone.uuid, channels);
  }
  return result;
}

function projectBlockbenchPreview(
  source: BlockbenchAnimationSource,
  bones: BoneEntry[],
  convention: CubeProjectTransformConvention,
  channels: BlockbenchChannelEvaluator,
  formatLabel: string,
  diagnosticPrefix: string,
): PreviewProjection {
  const tracks: PreviewProjection["tracks"] = {};
  const ticks = approximatePreviewTicks(source.animation, source.durationTicks, source.startDelayTicks);
  for (const bone of bones) {
    const animator = source.animators.get(bone.uuid);
    validateBoneAnimator(source.animation, source.animationIndex, bone, animator, formatLabel, diagnosticPrefix);
    const transforms: ImportedTransformKeyframe[] = [];
    for (const [tickIndex, tick] of ticks.entries()) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = tick / TICKS_PER_SECOND - source.startDelaySeconds;
      const previousTick = ticks[tickIndex - 1];
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, source.animation, source.animators, sourceTime, cache, source.animationIndex, source.blendWeight, convention, channels.evaluateApproximate), `${source.animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || approximatePreviewStepAt(source.animators, previousTick / TICKS_PER_SECOND - source.startDelaySeconds, sourceTime)
          ? { type: "step" }
          : { type: "linear", durationTicks: Math.max(1, tick - previousTick) },
      });
    }
    Object.assign(tracks, createBonePreviewTracks(bone, transforms, source.animation.name));
  }
  return { durationTicks: source.durationTicks, tracks, availability: { status: "full" } };
}

function projectBlockbenchRuntime(
  source: BlockbenchAnimationSource,
  bones: BoneEntry[],
  nodes: Record<string, ImportedNode>,
  convention: CubeProjectTransformConvention,
  channels: BlockbenchChannelEvaluator,
  createNativeRuntime: BlockbenchNativeRuntimeFactory,
  formatLabel: string,
  diagnosticPrefix: string,
): AnimationRuntimeData {
  if (source.requiresNativeRuntime) return { kind: "native", ...createNativeRuntime({ source, bones, importedNodes: nodes }) };
  const tracks: Record<string, BakedRuntimeNodeTracks> = {};
  for (const bone of bones) {
    validateBoneAnimator(source.animation, source.animationIndex, bone, source.animators.get(bone.uuid), formatLabel, diagnosticPrefix);
    const boneSampling = Object.values(source.channelSampling.get(bone.uuid) ?? {}).filter((sampling) => sampling !== undefined);
    const sourceTimes = boneSampling[0]?.sourceTimes;
    const transforms: BakedRuntimeTransformKeyframe[] = [];
    for (let tick = 0; tick <= source.durationTicks; tick++) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = source.startDelaySeconds > 0
        ? tick / TICKS_PER_SECOND - source.startDelaySeconds
        : sourceTimes?.get(tick) ?? tick / TICKS_PER_SECOND;
      const step = boneSampling.some((sampling) => sampling.stepTicks.has(tick));
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, source.animation, source.animators, sourceTime, cache, source.animationIndex, source.blendWeight, convention, channels.evaluate), `${source.animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || step ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
    for (const [nodeId, track] of Object.entries(createBonePreviewTracks(bone, transforms, source.animation.name))) {
      tracks[nodeId] = { ...track, nbt: [] };
    }
  }
  return { kind: "baked", tracks };
}

function createBonePreviewTracks(
  bone: BoneEntry,
  transforms: ImportedTransformKeyframe[],
  animationName: string,
): Record<string, PreviewNodeTrack> {
  const tracks: Record<string, PreviewNodeTrack> = {};
  for (const node of bone.nodes) {
    tracks[node.id] = {
      transforms: transforms.map((transform) => ({
        ...transform,
        matrix: matrix4ToRowMajor(
          (node.ignoreInheritedScale ? matrixWithoutScale(new Matrix4().set(...transform.matrix)) : new Matrix4().set(...transform.matrix)).multiply(node.localMatrix),
          `${animationName}/${node.id}/${transform.tick}`,
        ),
      })),
      visibility: [],
    };
  }
  return tracks;
}

function approximatePreviewTicks(animation: BbAnimation, durationTicks: number, startDelayTicks: number): number[] {
  const ticks = new Set<number>([0, durationTicks]);
  const stride = Math.max(1, Math.ceil(durationTicks / 199));
  for (let tick = 0; tick <= durationTicks; tick += stride) ticks.add(tick);
  for (const animator of Object.values(animation.animators)) {
    for (const frame of animator.keyframes ?? []) {
      if (!["position", "rotation", "scale"].includes(frame.channel)) continue;
      ticks.add(Math.max(0, Math.min(durationTicks, startDelayTicks + Math.round(frame.time * TICKS_PER_SECOND))));
    }
  }
  return [...ticks].sort((first, second) => first - second);
}

function approximatePreviewStepAt(
  animators: ReadonlyMap<string, BbAnimator>,
  fromTime: number,
  toTime: number,
): boolean {
  if (toTime <= 0) return true;
  for (const animator of animators.values()) {
    for (const channel of ["position", "rotation", "scale"]) {
      const frames = (animator.keyframes ?? []).filter((frame) => frame.channel === channel);
      if (blockbenchIntervalIsStep(frames, fromTime, toTime)) return true;
    }
  }
  return false;
}

function blockbenchAnimationUsesRuntimeState(animation: BbAnimation): boolean {
  return Object.values(animation.animators).some((animator) => (animator.keyframes ?? []).some((keyframe) => keyframe.data_points.some((point) =>
    usesRuntimeMolangState(point.x) || usesRuntimeMolangState(point.y) || usesRuntimeMolangState(point.z),
  )));
}

function resolveBoneAnimators(animation: BbAnimation, animationIndex: number, bones: BoneEntry[], formatLabel: string, diagnosticPrefix: string): Map<string, BbAnimator> {
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
      `unsupported_${diagnosticPrefix}_animator`,
      `${formatLabel} animation ${animation.name} contains a non-bone animator (${animator.name ?? animatorId}).`,
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
  formatLabel: string,
  diagnosticPrefix: string,
): ImportedTimelineEvent[] {
  const events: ImportedTimelineEvent[] = [];
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (!isEffectAnimator(animatorId, animator)) continue;
    for (const [keyframeIndex, keyframe] of (animator.keyframes ?? []).entries()) {
      const tick = Math.round(keyframe.time * TICKS_PER_SECOND);
      const sourcePath = `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]`;
      if (tick < 0) throw new ConversionError(`invalid_${diagnosticPrefix}_event`, `${formatLabel} effect keyframe time must not be negative.`, sourcePath);
      for (const point of keyframe.data_points) {
        const origin = effectOrigin(point.locator, bones);
        if (keyframe.channel === "sound" && point.effect?.trim()) {
          appendTimelineEvent(events, tick, { source: { type: "player" }, origin, commands: [`playsound ${point.effect.trim()} master @s ~ ~ ~`] });
        } else if (keyframe.channel === "particle" && point.effect?.trim()) {
          appendTimelineEvent(events, tick, { source: { type: "server" }, origin, commands: [`particle ${point.effect.trim()} ~ ~ ~`] });
          if (point.script?.trim()) diagnostics.push({
            severity: "warning",
            code: `${diagnosticPrefix}_particle_script_ignored`,
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
            code: `${diagnosticPrefix}_custom_instruction_ignored`,
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

function validateBoneAnimator(animation: BbAnimation, animationIndex: number, bone: BoneEntry, animator: BbAnimator | undefined, formatLabel: string, diagnosticPrefix: string): void {
  for (const [keyframeIndex, keyframe] of (animator?.keyframes ?? []).entries()) {
    if (!["position", "rotation", "scale"].includes(keyframe.channel)) {
      throw new ConversionError(
        `unsupported_${diagnosticPrefix}_channel`,
        `${formatLabel} bone ${bone.group.name} uses unsupported channel ${keyframe.channel}.`,
        `animations[${animationIndex}].animators.${bone.uuid}.keyframes[${keyframeIndex}].channel`,
      );
    }
  }
}

function animatedWorldMatrix(
  bone: BoneEntry,
  animation: BbAnimation,
  boneAnimators: ReadonlyMap<string, BbAnimator>,
  time: number,
  cache: Map<string, Matrix4>,
  animationIndex: number,
  blendWeight = 1,
  convention: CubeProjectTransformConvention,
  evaluateChannel: BlockbenchChannelEvaluator["evaluate"],
): Matrix4 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const animator = boneAnimators.get(bone.uuid);
  const animationPath = `animations[${animationIndex}].animators.${bone.uuid}`;
  const position = convention.position(
    evaluateChannel(animator?.keyframes ?? [], "position", time, [0, 0, 0], animationPath),
    (value) => -value,
  ).map((value) => value * blendWeight / 16);
  const rotationDelta = evaluateChannel(animator?.keyframes ?? [], "rotation", time, [0, 0, 0], animationPath).map((value) => value * blendWeight);
  const scale = evaluateChannel(animator?.keyframes ?? [], "scale", time, [1, 1, 1], animationPath).map((value) => 1 + (value - 1) * blendWeight);
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  const basePosition = convention.position(bone.group.origin.map((value, index) => value - parentOrigin[index]), (value) => -value).map((value) => value / 16);
  const baseRotation = convention.rotation(
    bone.group.rotation.map((value, index) => value + rotationDelta[index]),
    (value) => -value,
  );
  const local = composeDegreesTransform(basePosition.map((value, index) => value + position[index]), baseRotation, scale);
  const world = bone.parent
    ? animatedWorldMatrix(bone.parent, animation, boneAnimators, time, cache, animationIndex, blendWeight, convention, evaluateChannel).clone().multiply(local)
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

function numericValue(value: string | number, path: string, formatLabel: string, diagnosticPrefix: string): number {
  const number = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(number)) throw new ConversionError(`unsupported_${diagnosticPrefix}_molang`, `${formatLabel} expression ${String(value)} is not a numeric constant.`, path);
  return number;
}

function optionalNumericValue(value: string | number | undefined, fallback: number, path: string, formatLabel: string, diagnosticPrefix: string): number {
  if (value === undefined || (typeof value === "string" && value.trim() === "")) return fallback;
  return numericValue(value, path, formatLabel, diagnosticPrefix);
}

function validNamespace(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return /^[a-z0-9_.-]+$/.test(value) ? value : undefined;
}
