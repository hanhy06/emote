import type { BlockStateData, DisplayNbtPatch, ItemStackData } from "../../domain/minecraftData";
import { readDisplayNbt } from "../../format/minecraftData";
import { Matrix4 } from "three";
import { createDefaultPlayerBehavior, type Matrix16 } from "../../format/emoteAnimation";
import { composeDegreesTransform, matrix4ToRowMajor } from "../../format/matrix";
import { normalizeResourceLocation, sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { isRecord } from "../../format/runtimeValue";
import { parseSnbtCompound, serializeSnbtCompound, serializeSnbtString, splitSnbtPair, splitSnbtTopLevel } from "../../format/snbt";
import { requireAnimationDurationTicks, secondsToTicks } from "../../format/time";
import type { ImportInput } from "../adapter";
import { ConversionError } from "../../foundation/diagnostics";
import { importBlockbenchCubeContent, PLAYER_RENDER_SCALE, type ImportedCubeProjectContent } from "../common/blockbenchCubeImporter";
import { blockbenchIntervalIsStep } from "../common/animationEasing";
import { requireBlockbenchCubeProject, type BbKeyframe } from "../common/blockbenchCubeSchema";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import type {
  AjProject,
  AjProjectAnimation,
  AjProjectDisplayElement,
  AjProjectGroup,
  AjProjectLocator,
  AjProjectOutlinerEntry,
  AjProjectKeyframe,
} from "./animatedJavaProjectSchema";
import { ajRuntimeRootId, createAjProjectRuntime, type AjRuntimeHierarchy } from "./animatedJavaAnimationOutput";
import { createAnimatedJavaCubeRuntime } from "./animatedJavaCubeAnimationOutput";
import { ANIMATED_JAVA_CHANNELS } from "./animatedJavaAnimationPolicy";
import { ANIMATED_JAVA_BLUEPRINT_TRANSFORMS } from "./animatedJavaCubeTransform";

interface ProjectTransformGraph {
  groups: ReadonlyMap<string, AjProjectGroup>;
  groupParents: ReadonlyMap<string, string | undefined>;
  elementParents: ReadonlyMap<string, string | undefined>;
}

interface ProjectNodeStateFrame {
  nodeId: string;
  tick: number;
  visible?: boolean;
  nbt?: DisplayNbtPatch;
}

export function importAnimatedJavaProject(input: ImportInput, project: AjProject): ImportedProject {
  if (!["animated-java:format/blueprint", "animated_java_blueprint"].includes(project.meta.format)) {
    throw new Error(`Unsupported Animated Java project format: ${project.meta.format}`);
  }
  if (!project.meta.format_version.startsWith("1.")) {
    throw new Error(`Unsupported Animated Java project version: ${project.meta.format_version}`);
  }
  const sourceStem = input.name.replace(/\.ajblueprint$/i, "").trim() || project.name?.trim() || "Animated Java";
  const sourceAnimations = project.animations.length > 0 ? project.animations : [staticProjectAnimation()];
  const transformGraph = buildProjectTransformGraph(project);
  const cubeContent = importAnimatedJavaCubeGraph(project, sourceAnimations, sourceStem);
  const sceneScale = cubeContent ? PLAYER_RENDER_SCALE : 1;
  const runtimeHierarchy: AjRuntimeHierarchy = {
    sceneId: cubeContent?.runtimeSceneId,
    runtimeParentByGroupUuid: cubeContent?.runtimeParentByGroupUuid ?? {},
    parentGroupByElementUuid: transformGraph.elementParents,
    groupOrigins: new Map([...transformGraph.groups].map(([id, group]) => [id, group.origin])),
  };
  const displayElements = project.elements.filter((element): element is AjProjectDisplayElement => isDirectDisplay(element.type));
  const locatorElements = project.elements.filter((element): element is AjProjectLocator => element.type === "camera");
  const nodes: Record<string, ImportedNode> = { ...(cubeContent?.nodes ?? {}) };
  for (const element of displayElements) {
    const node = importProjectElement(element, projectElementMatrix(element, undefined, 0, transformGraph, 1, sceneScale));
    addProjectNode(nodes, element.uuid, {
      ...node,
      binding: { ...node.binding, spaceGroupId: cubeContent?.runtimeSceneId ?? ajRuntimeRootId(element.uuid) },
      ...(cubeContent ? { space: "initiator" as const } : {}),
    });
  }
  for (const element of locatorElements) addProjectNode(nodes, element.uuid, importProjectAnchor(element, projectElementMatrix(element, undefined, 0, transformGraph, 1, sceneScale)));
  applyGroupDefaultConfigs(nodes, project, transformGraph);
  if (Object.keys(nodes).length === 0) throw new Error("Animated Java project does not contain importable nodes.");

  const diagnostics: ImportDiagnostic[] = [...(cubeContent?.diagnostics ?? [])];
  appendProjectCapabilityDiagnostics(project, diagnostics);
  const displayAnimations = sourceAnimations.map((animation, index) => {
    try {
      return importProjectAnimation(animation, index, displayElements, nodes, transformGraph, sceneScale, runtimeHierarchy);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_animated_java_molang") throw reason;
      const message = `${animation.name}: preview uses the Create pose; runtime Molang is preserved.`;
      diagnostics.push({
        severity: "warning",
        code: "animated_java_animation_molang_unavailable",
        message,
        sourcePath: reason.sourcePath ?? `animations[${index}]`,
      });
      return createPreviewOnlyProjectAnimation(animation, index, message, displayElements, nodes, runtimeHierarchy);
    }
  });
  const animations = displayAnimations.map((animation, index) => enrichProjectAnimation(
    mergeProjectAnimation(cubeContent?.animations[index], animation),
    sourceAnimations[index],
    project,
    nodes,
    transformGraph,
    diagnostics,
    index,
  ));
  const name = prettify(sourceStem);
  return {
    source: "animated_java_blueprint",
    sourceName: input.name,
    suggestedMetadata: { name, description: `${name} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodes,
    animations,
    diagnostics,
    resources: cubeContent?.resources ?? new Map(),
    ...(cubeContent?.namespace ? { suggestedNamespace: cubeContent.namespace } : {}),
  };
}

function staticProjectAnimation(): AjProjectAnimation {
  return { name: "idle", length: 0.05, loop: "once", animators: {} };
}

function buildProjectTransformGraph(project: AjProject): ProjectTransformGraph {
  const savedGroups = new Map(project.groups.map((group) => [group.uuid, group]));
  const groups = new Map<string, AjProjectGroup>();
  const groupParents = new Map<string, string | undefined>();
  const elementParents = new Map<string, string | undefined>();
  const visit = (entry: AjProjectOutlinerEntry, parent: string | undefined): void => {
    if (typeof entry === "string") {
      elementParents.set(entry, parent);
      return;
    }
    const saved = savedGroups.get(entry.uuid);
    const name = entry.name ?? saved?.name;
    const origin = entry.origin ?? saved?.origin ?? [0, 0, 0];
    const rotation = entry.rotation ?? saved?.rotation ?? [0, 0, 0];
    if (!name) throw new Error(`Animated Java group ${entry.uuid} is missing its name.`);
    groups.set(entry.uuid, { ...saved, ...entry, uuid: entry.uuid, name, origin, rotation });
    groupParents.set(entry.uuid, parent);
    entry.children.forEach((child) => visit(child, entry.uuid));
  };
  project.outliner.forEach((entry) => visit(entry, undefined));
  return { groups, groupParents, elementParents };
}

function addProjectNode(nodes: Record<string, ImportedNode>, id: string, node: ImportedNode): void {
  if (nodes[id]) throw new ConversionError("animated_java_node_collision", `Animated Java project produces more than one node named ${id}.`, `elements.${id}`);
  nodes[id] = node;
}

function importAnimatedJavaCubeGraph(project: AjProject, animations: AjProjectAnimation[], sourceStem: string): ImportedCubeProjectContent | undefined {
  const supportedIds = new Set(project.elements
    .filter((element) => element.type === "cube" || element.type === "locator")
    .map((element) => element.uuid));
  if (supportedIds.size === 0 && project.outliner.every((entry) => typeof entry === "string")) return undefined;
  const groupIds = new Set(project.groups.map((group) => group.uuid));
  const collectGroupIds = (entry: AjProjectOutlinerEntry): void => {
    if (typeof entry === "string") return;
    groupIds.add(entry.uuid);
    entry.children.forEach(collectGroupIds);
  };
  project.outliner.forEach(collectGroupIds);
  const cubeProject = requireBlockbenchCubeProject({
    ...project,
    meta: { format_version: project.meta.format_version, model_format: "geckolib_model" },
    name: project.name?.trim() || sourceStem,
    geckolib_modid: sanitizeNamespace(sourceStem, "animated_java"),
    elements: project.elements.filter((element) => supportedIds.has(element.uuid)),
    outliner: project.outliner.flatMap((entry) => filterCubeOutlinerEntry(entry, supportedIds)),
    animations: animations.map((animation) => ({
      ...animation,
      animators: Object.fromEntries(Object.entries(animation.animators).flatMap(([id, animator]) => {
        if (id === "effects" || animator.type === "effect") return [[id, { ...animator, keyframes: (animator.keyframes ?? []).filter((frame) => ["sound", "particle", "timeline"].includes(frame.channel)) }]];
        if (groupIds.has(id)) return [[id, { ...animator, keyframes: (animator.keyframes ?? []).filter((frame) => ["position", "rotation", "scale"].includes(frame.channel)) }]];
        return [];
      })),
    })),
  });
  return importBlockbenchCubeContent(cubeProject, `${sourceStem}.bbmodel`, {
    transforms: ANIMATED_JAVA_BLUEPRINT_TRANSFORMS,
    formatLabel: "Animated Java",
    diagnosticPrefix: "animated_java",
    channels: ANIMATED_JAVA_CHANNELS,
    runtimeOutput: "native",
    createNativeRuntime: createAnimatedJavaCubeRuntime,
  });
}

function filterCubeOutlinerEntry(entry: AjProjectOutlinerEntry, supportedIds: ReadonlySet<string>): AjProjectOutlinerEntry[] {
  if (typeof entry === "string") return supportedIds.has(entry) ? [entry] : [];
  return [{ ...entry, children: entry.children.flatMap((child) => filterCubeOutlinerEntry(child, supportedIds)) }];
}

function mergeProjectAnimation(base: ImportedAnimation | undefined, display: ImportedAnimation): ImportedAnimation {
  if (!base) return display;
  const runtime = mergeProjectRuntime(base.runtime, display.runtime);
  return {
    ...base,
    durationTicks: Math.max(base.durationTicks, display.durationTicks),
    playbackMode: display.playbackMode,
    loopDelayTicks: display.loopDelayTicks,
    preview: {
      durationTicks: Math.max(base.preview.durationTicks, display.preview.durationTicks),
      tracks: { ...base.preview.tracks, ...display.preview.tracks },
      availability: base.preview.availability.preview === "full" ? display.preview.availability : base.preview.availability,
    },
    exportAvailability: !base.exportAvailability.exportable ? base.exportAvailability : display.exportAvailability,
    events: {
      start: [...base.events.start, ...display.events.start],
      timeline: [...base.events.timeline, ...display.events.timeline].sort((first, second) => first.tick - second.tick),
      loop: [...base.events.loop, ...display.events.loop],
      stop: [...base.events.stop, ...display.events.stop],
    },
    runtime,
  };
}

function mergeProjectRuntime(
  base: ImportedAnimation["runtime"],
  display: ImportedAnimation["runtime"],
): ImportedAnimation["runtime"] {
  if (base.kind !== "native" || display.kind !== "native") throw new Error("Animated Java runtime merge requires native animation output.");
  const initialize = [base.molang?.initialize, display.molang?.initialize].filter((value): value is string => Boolean(value)).join("\n");
  const tick = [base.molang?.tick, display.molang?.tick].filter((value): value is string => Boolean(value)).join("\n");
  return {
    kind: "native",
    ...((initialize || tick) ? { molang: { ...(initialize ? { initialize } : {}), ...(tick ? { tick } : {}) } } : {}),
    nodes: { ...base.nodes, ...display.nodes },
    tracks: { ...base.tracks, ...display.tracks },
    bindings: {
      editorNodeByRuntimeNode: { ...base.bindings.editorNodeByRuntimeNode, ...display.bindings.editorNodeByRuntimeNode },
      editorSpaceGroupByRuntimeRoot: { ...base.bindings.editorSpaceGroupByRuntimeRoot, ...display.bindings.editorSpaceGroupByRuntimeRoot },
    },
  };
}

function enrichProjectAnimation(
  imported: ImportedAnimation,
  source: AjProjectAnimation,
  project: AjProject,
  nodes: Record<string, ImportedNode>,
  graph: ProjectTransformGraph,
  diagnostics: ImportDiagnostic[],
  animationIndex: number,
): ImportedAnimation {
  const startDelayTicks = secondsToTicks(projectOptionalNumeric(source.start_delay, 0, `animations[${animationIndex}].start_delay`), `${source.name}.start_delay`);
  const timeline = [...imported.events.timeline, ...nativeFunctionEvents(source, startDelayTicks, diagnostics, animationIndex)]
    .sort((first, second) => first.tick - second.tick);
  const start = [...imported.events.start, ...nativeStartEvents(project, nodes, graph)];
  const variants = nativeVariants(project);
  const stateFrames: ProjectNodeStateFrame[] = [];
  for (const [animatorId, animator] of Object.entries(source.animators)) {
    for (const [keyframeIndex, frame] of (animator.keyframes ?? []).entries()) {
      if (frame.channel !== "variant") continue;
      const tick = startDelayTicks + Math.round(frame.time * 20);
      for (const point of frame.data_points) {
        const variantId = point.variant?.trim();
        if (!variantId) continue;
        const variant = variants.get(variantId);
        if (!variant) {
          diagnostics.push({ severity: "warning", code: "animated_java_unknown_variant", message: `Animation ${source.name} references unknown variant ${variantId}.`, sourcePath: `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]` });
          continue;
        }
        if (isRecord(variant.texture_map) && Object.keys(variant.texture_map).length > 0) diagnostics.push({
          severity: "warning",
          code: "animated_java_variant_texture_map_ignored",
          message: `Variant ${variantId} changes cube textures, which cannot yet be represented by the native importer.`,
          sourcePath: `variants.${variantId}.texture_map`,
        });
        collectVariantFrames(stateFrames, project, nodes, graph, variantId, variant, tick);
        const onApply = stringField(variant, "on_apply_function") ?? stringField(variant, "onApplyFunction");
        if (onApply) timeline.push({ tick, source: { type: "player" }, origin: { type: "root" }, commands: functionCommands(onApply) });
      }
    }
  }
  const enriched = { ...imported, events: { ...imported.events, start, timeline: timeline.sort((first, second) => first.tick - second.tick) } };
  return applyProjectStateFrames(enriched, stateFrames);
}

function nativeStartEvents(project: AjProject, nodes: Record<string, ImportedNode>, graph: ProjectTransformGraph): ImportedAnimation["events"]["start"] {
  const events: ImportedAnimation["events"]["start"] = [];
  const settings = project.blueprint_settings;
  const rootFunction = settings ? stringField(settings, "custom_summon_commands") ?? stringField(settings, "on_summon_function") : undefined;
  if (rootFunction) events.push({ source: { type: "player" }, origin: { type: "root" }, commands: functionCommands(rootFunction) });
  for (const element of project.elements) {
    const value = isRecord(element) ? stringField(element, "onSummonFunction") ?? stringField(element, "on_summon_function") : undefined;
    if (value && nodes[element.uuid]) events.push({ source: { type: "player" }, origin: { type: "node", node: element.uuid }, commands: functionCommands(value) });
  }
  for (const group of project.groups) {
    const value = group.onSummonFunction?.trim();
    if (!value) continue;
    const nodeId = projectOutputNodeIds(group.uuid, nodes, graph, false)[0];
    events.push({ source: { type: "player" }, origin: nodeId ? { type: "node", node: nodeId } : { type: "root" }, commands: functionCommands(value) });
  }
  return events;
}

function appendProjectCapabilityDiagnostics(project: AjProject, diagnostics: ImportDiagnostic[]): void {
  const supported = new Set(["cube", "locator", "camera", "animated_java:vanilla_block_display", "animated_java:vanilla_item_display", "animated_java:vanilla_text_display", "animated_java:text_display"]);
  for (const [index, element] of project.elements.entries()) {
    if (supported.has(element.type)) continue;
    diagnostics.push({
      severity: "warning",
      code: element.type.includes("interaction") ? "unsupported_animated_java_interaction" : "unsupported_animated_java_element",
      message: `Animated Java element ${element.name} (${element.type}) was not imported because the Emote format has no matching node type.`,
      sourcePath: `elements[${index}]`,
    });
  }
  if ((project.animation_controllers?.length ?? 0) > 0) diagnostics.push({
    severity: "warning",
    code: "unsupported_animated_java_animation_controllers",
    message: "Animated Java animation controllers were not imported; individual animations remain available.",
    sourcePath: "animation_controllers",
  });
  if ((project.collections?.length ?? 0) > 0) diagnostics.push({
    severity: "warning",
    code: "unsupported_animated_java_collections",
    message: "Animated Java collection metadata was not imported.",
    sourcePath: "collections",
  });
  collectContinuousFunctionDiagnostics(project, "", diagnostics);
}

function collectContinuousFunctionDiagnostics(value: unknown, path: string, diagnostics: ImportDiagnostic[]): void {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => collectContinuousFunctionDiagnostics(entry, `${path}[${index}]`, diagnostics));
    return;
  }
  if (!isRecord(value)) return;
  for (const [key, child] of Object.entries(value)) {
    const childPath = path ? `${path}.${key}` : key;
    if (/^(?:on_?(?:pre_?|post_?)?tick_?function|onTickFunction)$/i.test(key) && typeof child === "string" && child.trim()) {
      diagnostics.push({ severity: "warning", code: "unsupported_animated_java_tick_function", message: "Continuous Animated Java tick functions cannot be represented by an Emote timeline.", sourcePath: childPath });
      continue;
    }
    collectContinuousFunctionDiagnostics(child, childPath, diagnostics);
  }
}

function functionCommands(value: string): string[] {
  return value.split(/\r?\n/).map((line) => line.trim().replace(/^\//, "")).filter(Boolean);
}

function stringField(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function nativeFunctionEvents(source: AjProjectAnimation, startDelayTicks: number, diagnostics: ImportDiagnostic[], animationIndex: number): ImportedAnimation["events"]["timeline"] {
  const result: ImportedAnimation["events"]["timeline"] = [];
  for (const [animatorId, animator] of Object.entries(source.animators)) {
    for (const [keyframeIndex, frame] of (animator.keyframes ?? []).entries()) {
      if (frame.channel !== "function" && frame.channel !== "commands") continue;
      const sourcePath = `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]`;
      for (const point of frame.data_points) {
        const text = point.function ?? point.commands ?? "";
        const commands = text.split(/\r?\n/).map((line) => line.trim().replace(/^\//, "")).filter(Boolean);
        if (commands.length === 0) continue;
        if (point.execute_condition?.trim()) diagnostics.push({ severity: "warning", code: "animated_java_execute_condition_ignored", message: `Execute condition was not converted: ${point.execute_condition.trim()}`, sourcePath });
        if (point.repeat) diagnostics.push({ severity: "warning", code: "animated_java_repeating_function_ignored", message: "Repeating function behavior was converted to a single timeline event.", sourcePath });
        result.push({ tick: startDelayTicks + Math.round(frame.time * 20), source: { type: "player" }, origin: { type: "root" }, commands });
      }
    }
  }
  return result;
}

function nativeVariants(project: AjProject): Map<string, Record<string, unknown>> {
  const variants = new Map<string, Record<string, unknown>>();
  if (!isRecord(project.variants)) return variants;
  const entries = Array.isArray(project.variants.list) ? project.variants.list : [];
  if (isRecord(project.variants.default)) entries.unshift(project.variants.default);
  for (const value of entries) {
    if (!isRecord(value)) continue;
    const uuid = typeof value.uuid === "string" ? value.uuid : undefined;
    const name = typeof value.name === "string" ? value.name : undefined;
    if (uuid) variants.set(uuid, value);
    if (name) variants.set(name, value);
  }
  return variants;
}

function collectVariantFrames(
  frames: ProjectNodeStateFrame[],
  project: AjProject,
  nodes: Record<string, ImportedNode>,
  graph: ProjectTransformGraph,
  variantId: string,
  variant: Record<string, unknown>,
  tick: number,
): void {
  const excluded = new Set(Array.isArray(variant.excluded_nodes) ? variant.excluded_nodes.filter((value): value is string => typeof value === "string") : []);
  for (const sourceId of excluded) {
    for (const nodeId of projectOutputNodeIds(sourceId, nodes, graph)) frames.push({ nodeId, tick, visible: false });
  }
  for (const element of project.elements) {
    if (!isDirectDisplay(element.type)) continue;
    const display = element as AjProjectDisplayElement;
    const config = display.configs?.variants?.[variantId];
    if (isRecord(config)) collectVariantConfigFrame(frames, display.uuid, config, tick);
  }
  for (const group of project.groups) {
    const config = group.configs?.variants?.[variantId];
    if (!isRecord(config)) continue;
    for (const nodeId of projectOutputNodeIds(group.uuid, nodes, graph, false)) collectVariantConfigFrame(frames, nodeId, config, tick);
  }
}

function collectVariantConfigFrame(frames: ProjectNodeStateFrame[], nodeId: string, config: Record<string, unknown>, tick: number): void {
  const visible = typeof config.invisible === "boolean" ? !config.invisible : undefined;
  const nbt = nativeDisplayConfigNbt(config);
  if (visible !== undefined || nbt) frames.push({ nodeId, tick, ...(visible === undefined ? {} : { visible }), ...(nbt ? { nbt: readDisplayNbt(nbt) } : {}) });
}

function applyProjectStateFrames(animation: ImportedAnimation, frames: ProjectNodeStateFrame[]): ImportedAnimation {
  if (frames.length === 0) return animation;
  if (animation.runtime.kind !== "native") throw new Error("Animated Java state frames require native animation output.");
  const previewTracks = { ...animation.preview.tracks };
  const runtimeTracks = { ...animation.runtime.tracks };
  const framesByNode = new Map<string, ProjectNodeStateFrame[]>();
  for (const frame of frames) {
    const nodeFrames = framesByNode.get(frame.nodeId) ?? [];
    nodeFrames.push(frame);
    framesByNode.set(frame.nodeId, nodeFrames);
  }
  for (const [nodeId, nodeFrames] of framesByNode) {
    const preview = previewTracks[nodeId] ?? { transforms: [], visibility: [], nbt: [] };
    previewTracks[nodeId] = {
      ...preview,
      visibility: [...preview.visibility, ...nodeFrames.flatMap((frame) => frame.visible === undefined ? [] : [{ tick: frame.tick, visible: frame.visible }])]
        .sort((first, second) => first.tick - second.tick),
      nbt: [...preview.nbt, ...nodeFrames.flatMap((frame) => frame.nbt ? [{ tick: frame.tick, value: frame.nbt }] : [])]
        .sort((first, second) => first.tick - second.tick),
    };
    const runtime = runtimeTracks[nodeId] ?? {};
    const visible = nodeFrames.flatMap((frame) => frame.visible === undefined ? [] : [{ tick: frame.tick, value: frame.visible }]);
    const nbt = nodeFrames.flatMap((frame) => frame.nbt ? [{ tick: frame.tick, value: frame.nbt }] : []);
    runtimeTracks[nodeId] = {
      ...runtime,
      ...(visible.length ? { visible: [...(runtime.visible ?? []), ...visible].sort((first, second) => first.tick - second.tick) } : {}),
      ...(nbt.length ? { nbt: [...(runtime.nbt ?? []), ...nbt].sort((first, second) => first.tick - second.tick) } : {}),
    };
  }
  return {
    ...animation,
    preview: { ...animation.preview, tracks: previewTracks },
    runtime: { ...animation.runtime, tracks: runtimeTracks },
  };
}

function projectOutputNodeIds(sourceId: string, nodes: Record<string, ImportedNode>, graph: ProjectTransformGraph, includeDescendants = true): string[] {
  const result = new Set<string>();
  if (nodes[sourceId]) result.add(sourceId);
  const groupIds = [sourceId, ...(includeDescendants ? [...graph.groups.keys()].filter((id) => projectGroupDescendsFrom(id, sourceId, graph)) : [])];
  for (const groupId of groupIds) {
    const group = graph.groups.get(groupId);
    if (!group) continue;
    const boneId = sanitizeResourcePath(group.name, "bone").replaceAll("/", "_");
    for (const nodeId of Object.keys(nodes)) if (nodeId === boneId || nodeId.startsWith(`${boneId}_`)) result.add(nodeId);
    for (const [elementId, parentId] of graph.elementParents) if (parentId === groupId && nodes[elementId]) result.add(elementId);
  }
  return [...result];
}

function projectGroupDescendsFrom(id: string, ancestorId: string, graph: ProjectTransformGraph): boolean {
  for (let current = graph.groupParents.get(id); current; current = graph.groupParents.get(current)) if (current === ancestorId) return true;
  return false;
}

function applyGroupDefaultConfigs(nodes: Record<string, ImportedNode>, project: AjProject, graph: ProjectTransformGraph): void {
  for (const group of project.groups) {
    const config = group.configs?.default;
    if (!isRecord(config)) continue;
    const nbt = nativeDisplayConfigNbt(config);
    for (const nodeId of projectOutputNodeIds(group.uuid, nodes, graph, false)) {
      const node = nodes[nodeId];
      if (!node || node.type === "anchor") continue;
      if (config.invisible === true) node.visible = false;
      if (nbt) node.entityNbt = mergeSnbt(node.entityNbt, nbt);
    }
  }
}

function nativeDefaultConfig(element: AjProjectDisplayElement): Record<string, unknown> | undefined {
  const config = { ...(isRecord(element.config) ? element.config : {}), ...(isRecord(element.configs?.default) ? element.configs.default : {}) };
  return Object.keys(config).length ? config : undefined;
}

function nativeDisplayConfigNbt(config: Record<string, unknown> | undefined): string | undefined {
  if (!config) return undefined;
  const fields = new Map<string, string>();
  if (typeof config.billboard === "string") fields.set("billboard", serializeSnbtString(config.billboard));
  for (const key of ["shadow_radius", "shadow_strength"] as const) if (typeof config[key] === "number" && Number.isFinite(config[key])) fields.set(key, String(config[key]));
  if (typeof config.glowing === "boolean") fields.set("Glowing", config.glowing ? "1b" : "0b");
  if (typeof config.glow_color === "string" && /^#[0-9a-f]{6}$/i.test(config.glow_color)) fields.set("glow_color_override", String(Number.parseInt(config.glow_color.slice(1), 16)));
  const brightness = typeof config.brightness_override === "number" ? config.brightness_override : undefined;
  if ((config.override_brightness === true || brightness !== undefined) && brightness !== undefined) {
    fields.set("brightness", serializeSnbtCompound([["sky", String(brightness)], ["block", String(brightness)]]));
  }
  if (config.use_nbt === true && typeof config.nbt === "string" && config.nbt.trim()) {
    for (const field of parseSnbtCompound(config.nbt, "Animated Java display config NBT")) fields.set(field.name, field.value);
  }
  return fields.size ? serializeSnbtCompound(fields) : undefined;
}

function mergeSnbt(first: string | undefined, second: string): string {
  const fields = new Map<string, string>();
  if (first) for (const field of parseSnbtCompound(first)) fields.set(field.name, field.value);
  for (const field of parseSnbtCompound(second)) fields.set(field.name, field.value);
  return serializeSnbtCompound(fields);
}

function createPreviewOnlyProjectAnimation(
  animation: AjProjectAnimation,
  index: number,
  reason: string,
  elements: AjProjectDisplayElement[],
  nodes: Record<string, ImportedNode>,
  runtimeHierarchy: AjRuntimeHierarchy,
): ImportedAnimation {
  const durationTicks = Number.isFinite(animation.length) && animation.length > 0 ? Math.max(1, Math.round(animation.length * 20)) : 20;
  const startDelayTicks = secondsToTicks(projectOptionalNumeric(animation.start_delay, 0, `animations[${index}].start_delay`), `${animation.name}.start_delay`);
  const blendWeight = projectOptionalNumeric(animation.blend_weight, 1, `animations[${index}].blend_weight`);
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: prettify(animation.name),
    durationTicks,
    playbackMode: animation.loop === "loop" ? "loop" : "once",
    loopDelayTicks: 0,
    events: { start: [], timeline: [], loop: [], stop: [] },
    preview: {
      durationTicks: 20,
      tracks: {},
      availability: { preview: "create_pose", reason },
    },
    exportAvailability: { exportable: true },
    runtime: { kind: "native", ...createAjProjectRuntime(animation, elements, nodes, runtimeHierarchy, blendWeight, startDelayTicks, durationTicks) },
  };
}

function isDirectDisplay(type: string): type is AjProjectDisplayElement["type"] {
  return [
    "animated_java:vanilla_block_display",
    "animated_java:vanilla_item_display",
    "animated_java:vanilla_text_display",
    "animated_java:text_display",
  ].includes(type);
}

function importProjectAnchor(element: AjProjectLocator, defaultMatrix: Matrix16): ImportedNode {
  return {
    binding: { sourceNodeId: element.uuid },
    type: "anchor",
    defaultMatrix,
  };
}

function importProjectElement(element: AjProjectDisplayElement, defaultMatrix: Matrix16): ImportedNode {
  const config = nativeDefaultConfig(element);
  const entityNbt = nativeDisplayConfigNbt(config);
  const visible = element.visibility !== false && config?.invisible !== true;
  if (element.type === "animated_java:vanilla_block_display") {
    return {
      binding: { sourceNodeId: element.uuid },
      type: "block_display",
      defaultMatrix,
      visible,
      ...(entityNbt ? { entityNbt } : {}),
      blockState: blockArgumentToData(element.block ?? "minecraft:air"),
    };
  }
  if (element.type === "animated_java:vanilla_item_display") {
    return {
      binding: { sourceNodeId: element.uuid },
      type: "item_display",
      defaultMatrix,
      visible,
      ...(entityNbt ? { entityNbt } : {}),
      itemDisplay: element.itemDisplay ?? element.item_display ?? "none",
      itemStack: itemArgumentToData(element.item ?? "minecraft:air"),
    };
  }
  return {
    binding: { sourceNodeId: element.uuid },
    type: "text_display",
    defaultMatrix,
    visible,
    ...(entityNbt ? { entityNbt } : {}),
    text: element.text ?? { text: element.name },
  };
}

function importProjectAnimation(
  animation: AjProjectAnimation,
  animationIndex: number,
  elements: AjProjectDisplayElement[],
  nodes: Record<string, ImportedNode>,
  graph: ProjectTransformGraph,
  sceneScale: number,
  runtimeHierarchy: AjRuntimeHierarchy,
): ImportedAnimation {
  const playbackMode = animation.loop === "hold_on_last_frame" ? "hold" : animation.loop;
  if (playbackMode !== "once" && playbackMode !== "hold" && playbackMode !== "loop") throw new Error(`Animated Java animation ${animation.name} has unsupported loop mode ${animation.loop}.`);
  const startDelaySeconds = projectOptionalNumeric(animation.start_delay, 0, `animations[${animationIndex}].start_delay`);
  const startDelayTicks = secondsToTicks(startDelaySeconds, `${animation.name}.start_delay`);
  const blendWeight = projectOptionalNumeric(animation.blend_weight, 1, `animations[${animationIndex}].blend_weight`);
  const durationTicks = requireAnimationDurationTicks(
    secondsToTicks(animation.length, `${animation.name}.length`) + startDelayTicks,
    `${animation.name}.length`,
  );
  const tracks: ImportedAnimation["preview"]["tracks"] = {};
  const stateFrames: ProjectNodeStateFrame[] = [];
  const previewTicks = approximateProjectPreviewTicks(animation, durationTicks, startDelayTicks);
  for (const element of elements) {
    validateProjectKeyframes(animation.animators[element.uuid]?.keyframes ?? [], animationIndex, element.uuid);
    const transforms: ImportedTransformKeyframe[] = [];
    for (const [tickIndex, tick] of previewTicks.entries()) {
      const sourceTime = tick / 20 - startDelaySeconds;
      const previousTick = previewTicks[tickIndex - 1];
      transforms.push({
        tick,
        matrix: projectElementMatrix(element, animation, sourceTime, graph, blendWeight, sceneScale),
        interpolation: tick === 0 || projectStepBetween(animation, previousTick / 20 - startDelaySeconds, sourceTime)
          ? { type: "step" }
          : { type: "linear", durationTicks: Math.max(1, tick - previousTick) },
      });
    }
    const visibility = projectVisibilityFrames(animation, element, startDelayTicks);
    stateFrames.push(...visibility.map((frame) => ({ nodeId: element.uuid, ...frame })));
    tracks[element.uuid] = { transforms, visibility: [], nbt: [] };
  }
  return applyProjectStateFrames({
    id: sanitizeResourcePath(animation.name, `animation_${animationIndex + 1}`),
    name: prettify(animation.name),
    durationTicks,
    playbackMode,
    loopDelayTicks: playbackMode === "loop"
      ? secondsToTicks(projectOptionalNumeric(animation.loop_delay, 0, `animations[${animationIndex}].loop_delay`), `${animation.name}.loop_delay`)
      : 0,
    events: { start: [], timeline: [], loop: [], stop: [] },
    preview: { durationTicks, tracks, availability: { preview: "full" } },
    exportAvailability: { exportable: true },
    runtime: { kind: "native", ...createAjProjectRuntime(animation, elements, nodes, runtimeHierarchy, blendWeight, startDelayTicks, durationTicks) },
  }, stateFrames);
}

function validateProjectKeyframes(keyframes: AjProjectKeyframe[], animationIndex: number, animatorId: string): void {
  for (const [index, keyframe] of keyframes.entries()) {
    if (!["position", "rotation", "scale", "visibility"].includes(keyframe.channel)) {
      throw new ConversionError(
        "unsupported_animated_java_channel",
        `Animated Java project uses unsupported channel ${keyframe.channel}.`,
        `animations[${animationIndex}].animators.${animatorId}.keyframes[${index}]`,
      );
    }
    if (keyframe.channel !== "visibility" && (keyframe.data_points.length < 1 || keyframe.data_points.length > 2)) {
      throw new ConversionError("unsupported_animated_java_keyframe", "Animated Java transform keyframes must contain one value or a pre/post pair.", `animations[${animationIndex}].animators.${animatorId}.keyframes[${index}]`);
    }
  }
}

function projectElementMatrix(
  element: AjProjectDisplayElement | AjProjectLocator,
  animation: AjProjectAnimation | undefined,
  sourceTime: number,
  graph: ProjectTransformGraph,
  blendWeight: number,
  sceneScale: number,
): Matrix16 {
  const parentId = graph.elementParents.get(element.uuid);
  const parent = parentId ? graph.groups.get(parentId) : undefined;
  const animator = animation?.animators[element.uuid];
  const path = `Animated Java ${animation?.name ?? "default"}/${element.name}`;
  const positionOffset = evaluateProjectTransformChannel(animator?.keyframes ?? [], "position", sourceTime, [0, 0, 0], `${path}/position`)
    .map((value) => value * blendWeight);
  const rotationOffset = evaluateProjectTransformChannel(animator?.keyframes ?? [], "rotation", sourceTime, [0, 0, 0], `${path}/rotation`)
    .map((value) => value * blendWeight);
  const baseScale = "scale" in element ? element.scale : [1, 1, 1];
  // AJ item displays replace scale; block and text displays multiply their base scale.
  const absoluteScale = element.type === "animated_java:vanilla_item_display";
  const scaleKeyframes = (animator?.keyframes ?? []).filter((frame) => frame.channel === "scale");
  const scale = scaleKeyframes.some((frame) => frame.time <= sourceTime + 1e-9)
    ? evaluateProjectTransformChannel(scaleKeyframes, "scale", sourceTime, [1, 1, 1], `${path}/scale`)
        .map((value, axis) => absoluteScale
          ? 1 + (value - 1) * blendWeight
          : baseScale[axis] * (1 + (value - 1) * blendWeight))
    : [...baseScale];
  const basePosition = element.position.map((value, axis) => value - (parent?.origin[axis] ?? 0));
  const local = composeDegreesTransform(
    basePosition.map((value, axis) => (value + (axis === 0 ? -positionOffset[axis] : positionOffset[axis])) / 16),
    element.rotation.map((value, axis) => value + (axis < 2 ? -rotationOffset[axis] : rotationOffset[axis])),
    scale,
    // AJ display updateTransform copies the mesh's default XYZ order into fix_rotation.
    isDirectDisplay(element.type) ? "XYZ" : "ZYX",
  );
  const world = parentId ? projectGroupMatrix(parentId, animation, sourceTime, graph, blendWeight, new Map()) : new Matrix4();
  const result = new Matrix4().makeScale(sceneScale, sceneScale, sceneScale).multiply(world).multiply(local);
  if (element.type === "animated_java:vanilla_text_display" || element.type === "animated_java:text_display") result.multiply(new Matrix4().makeRotationY(Math.PI));
  return matrix4ToRowMajor(result, path);
}

function projectGroupMatrix(
  id: string,
  animation: AjProjectAnimation | undefined,
  sourceTime: number,
  graph: ProjectTransformGraph,
  blendWeight: number,
  cache: Map<string, Matrix4>,
): Matrix4 {
  const cached = cache.get(id);
  if (cached) return cached.clone();
  const group = graph.groups.get(id);
  if (!group) throw new Error(`Animated Java outliner references unknown group ${id}.`);
  const parentId = graph.groupParents.get(id);
  const parent = parentId ? graph.groups.get(parentId) : undefined;
  const animator = animation?.animators[id];
  const path = `Animated Java ${animation?.name ?? "default"}/${group.name}`;
  const positionOffset = evaluateProjectTransformChannel(animator?.keyframes ?? [], "position", sourceTime, [0, 0, 0], `${path}/position`)
    .map((value) => value * blendWeight);
  const rotationOffset = evaluateProjectTransformChannel(animator?.keyframes ?? [], "rotation", sourceTime, [0, 0, 0], `${path}/rotation`)
    .map((value) => value * blendWeight);
  const scale = evaluateProjectTransformChannel(animator?.keyframes ?? [], "scale", sourceTime, [1, 1, 1], `${path}/scale`)
    .map((value) => 1 + (value - 1) * blendWeight);
  const local = composeDegreesTransform(
    group.origin.map((value, axis) => (value - (parent?.origin[axis] ?? 0) + positionOffset[axis]) / 16),
    group.rotation.map((value, axis) => value + rotationOffset[axis]),
    scale,
  );
  const world = parentId ? projectGroupMatrix(parentId, animation, sourceTime, graph, blendWeight, cache).multiply(local) : local;
  cache.set(id, world.clone());
  return world;
}

function evaluateProjectTransformChannel(keyframes: AjProjectKeyframe[], channel: string, sourceTime: number, fallback: number[], path: string): number[] {
  if (sourceTime < 0) return [...fallback];
  return ANIMATED_JAVA_CHANNELS.evaluateApproximate(keyframes as unknown as BbKeyframe[], channel, sourceTime, fallback, path);
}

function approximateProjectPreviewTicks(animation: AjProjectAnimation, durationTicks: number, startDelayTicks: number): number[] {
  const ticks = new Set<number>([0, durationTicks]);
  const stride = Math.max(1, Math.ceil(durationTicks / 199));
  for (let tick = 0; tick <= durationTicks; tick += stride) ticks.add(tick);
  for (const animator of Object.values(animation.animators)) {
    for (const frame of animator.keyframes ?? []) {
      if (!["position", "rotation", "scale"].includes(frame.channel)) continue;
      ticks.add(Math.max(0, Math.min(durationTicks, startDelayTicks + Math.round(frame.time * 20))));
    }
  }
  return [...ticks].sort((first, second) => first - second);
}

function projectStepBetween(animation: AjProjectAnimation, fromTime: number, toTime: number): boolean {
  if (toTime <= 0) return true;
  for (const animator of Object.values(animation.animators)) {
    for (const channel of ["position", "rotation", "scale"]) {
      const frames = (animator.keyframes ?? []).filter((frame) => frame.channel === channel) as BbKeyframe[];
      if (blockbenchIntervalIsStep(frames, fromTime, toTime)) return true;
    }
  }
  return false;
}

function projectVisibilityFrames(animation: AjProjectAnimation, element: AjProjectDisplayElement, startDelayTicks: number): ImportedAnimation["preview"]["tracks"][string]["visibility"] {
  const frames = (animation.animators[element.uuid]?.keyframes ?? [])
    .filter((frame) => frame.channel === "visibility")
    .map((frame) => ({ tick: startDelayTicks + Math.round(frame.time * 20), visible: projectVisibility(frame, element) }))
    .sort((first, second) => first.tick - second.tick);
  return frames;
}

function projectVisibility(frame: AjProjectKeyframe, element: AjProjectDisplayElement): boolean {
  const value = frame.data_points.at(-1)?.x;
  if (value === undefined) throw new ConversionError("invalid_animated_java_visibility", `Animated Java visibility keyframe for ${element.name} has no value.`);
  if (typeof value === "number") return value !== 0;
  if (value === "true" || value === "1") return true;
  if (value === "false" || value === "0") return false;
  throw new ConversionError("invalid_animated_java_visibility", `Animated Java visibility value ${value} is not boolean.`);
}

function projectNumeric(value: string | number, path: string): number {
  const parsed = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(parsed)) {
    throw new ConversionError("unsupported_animated_java_molang", `Animated Java expression ${String(value)} is not a numeric constant.`, path);
  }
  return parsed;
}

function projectOptionalNumeric(value: string | number | undefined, fallback: number, path: string): number {
  if (value === undefined || (typeof value === "string" && value.trim() === "")) return fallback;
  return projectNumeric(value, path);
}

export function itemArgumentToData(value: string): ItemStackData {
  const match = /^([^\[]+)(?:\[(.*)\])?$/.exec(value.trim());
  const id = normalizeResourceLocation(match?.[1] ?? "air");
  const components = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((component) => {
    const pair = splitSnbtPair(component, "=");
    if (!pair?.[0] || !pair[1]) return [];
    return [{ name: normalizeResourceLocation(pair[0]), value: pair[1] }];
  }) : [];
  return { id, count: 1, ...(components.length ? { components } : {}) };
}

export function blockArgumentToData(value: string): BlockStateData {
  const match = /^([^\[]+)(?:\[(.*)\])?$/.exec(value.trim());
  const id = normalizeResourceLocation(match?.[1] ?? "air");
  const properties = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((property): [string, string][] => {
    const pair = splitSnbtPair(property, "=");
    if (!pair?.[0] || !pair[1]) return [];
    return [[pair[0], pair[1]]];
  }) : [];
  return { id, ...(properties.length ? { properties: Object.fromEntries(properties) } : {}) };
}

function prettify(value: string): string {
  const result = value.replaceAll("_", " ").replaceAll("-", " ").trim();
  return result ? result[0].toUpperCase() + result.slice(1) : "Emote";
}
