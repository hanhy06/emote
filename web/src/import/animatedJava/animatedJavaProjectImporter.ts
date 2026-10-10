import { sourceSecondsTime, parseAnimationSeconds } from "../../format/time";
import { importedNodeHints } from "../../domain/conversionSeed";
import { referencedItemModelResources } from "../../domain/generatedResource";
import type { BlockStateData, ItemStackData } from "../../domain/minecraftData";
import { readDisplayNbt } from "../../format/minecraftData";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { normalizeResourceLocation, sanitizeResourcePath } from "../../format/resourceLocation";
import { isRecord } from "../../format/runtimeValue";
import { parseSnbtCompound, serializeSnbtCompound, serializeSnbtString, splitSnbtPair, splitSnbtTopLevel } from "../../format/snbt";
import type { ImportInput } from "../input";
import { ConversionError, skippedAnimationIssue, type ConversionIssue } from "../../foundation/diagnostics";
import { importAnimatedJavaCubes, writeReferencedAnimatedJavaCubeResources, type AnimatedJavaCubes } from "./animatedJavaCubes";
import { PLAYER_RENDER_SCALE } from "../common/blockbenchCubeModel";
import { normalizeBlockbenchName } from "../common/blockbenchCubeSkin";
import { createAnimatedJavaAnimationIR, type ProjectNodeStateFrame } from "./animatedJavaAnimationIR";
import type { EventIR, TimelineEventIR } from "../../domain/animationIR";
import type { ImportedNode, ImportedProject } from "../../domain/conversionSeed";
import type {
  AjProject,
  AjProjectAnimation,
  AjProjectDisplayElement,
  AjProjectGroup,
  AjProjectLocator,
  AjProjectOutlinerEntry,
  AjProjectKeyframe,
  ProjectTransformGraph,
} from "./animatedJavaProjectSchema";

type ProjectNodeBindings = ReadonlyMap<string, readonly string[]>;

interface AnimatedJavaAnimationState {
  startEvents: EventIR[];
  timelineEvents: TimelineEventIR[];
  nodeFrames: ProjectNodeStateFrame[];
}

export function importAnimatedJavaProject(input: ImportInput, project: AjProject): ImportedProject {
  project = { ...project, groups: project.groups ?? [], elements: project.elements.map((element) => {
    const visibility = (element as { visibility?: unknown }).visibility;
    return visibility === "true" || visibility === "false" ? { ...element, visibility: visibility === "true" } : element;
  }) };
  if (!["animated-java:format/blueprint", "animated_java_blueprint"].includes(project.meta.format)) {
    throw new Error(`Unsupported Animated Java project format: ${project.meta.format}`);
  }
  const sourceStem = input.name.replace(/\.ajblueprint$/i, "").trim() || project.name?.trim() || "Animated Java";
  if (project.animations.length === 0 && project.animationDiagnostics?.length) {
    throw new ConversionError("no_importable_animations", `No Animated Java animations could be imported. ${project.animationDiagnostics.map((issue) => issue.message).join(" ")}`);
  }
  const sourceAnimations = project.animations.length > 0 ? project.animations : [staticProjectAnimation()];
  const transformGraph = buildProjectTransformGraph(project);
  const cubeContent = importAnimatedJavaCubes(project, sourceStem, transformGraph);
  const sceneScale = cubeContent ? PLAYER_RENDER_SCALE : 1;
  const displayElements = project.elements.filter((element): element is AjProjectDisplayElement => isDirectDisplay(element.type));
  const logicalElements = project.elements.filter((element) => !["cube", "locator", "camera"].includes(element.type) && !isDirectDisplay(element.type));
  const locatorElements = project.elements.filter((element): element is AjProjectLocator => element.type === "camera");
  const nodes: Record<string, ImportedNode> = { ...(cubeContent?.nodes ?? {}) };
  const nodeBindings = new Map<string, readonly string[]>(cubeContent?.nodeIdsBySourceUuid);
  const bindOutputNode = (sourceId: string, nodeId: string) => nodeBindings.set(sourceId, [...(nodeBindings.get(sourceId) ?? []), nodeId]);
  for (const element of displayElements) {
    const node = importProjectElement(element);
    addProjectNode(nodes, element.uuid, node);
    bindOutputNode(element.uuid, element.uuid);
  }
  for (const element of locatorElements) {
    addProjectNode(nodes, element.uuid, importProjectAnchor(element));
    bindOutputNode(element.uuid, element.uuid);
  }
  for (const element of logicalElements) {
    if (nodes[element.uuid] || nodeBindings.get(element.uuid)?.includes(element.uuid)) throw new ConversionError("animated_java_node_collision", `Animated Java project produces more than one node named ${element.uuid}.`, `elements.${element.uuid}`);
    bindOutputNode(element.uuid, element.uuid);
  }
  applyGroupDefaultConfigs(nodes, project, transformGraph, nodeBindings);
  if (Object.keys(nodes).length === 0 && logicalElements.length === 0) throw new Error("Animated Java project does not contain importable nodes.");

  const diagnostics: ConversionIssue[] = [...(project.animationDiagnostics ?? [])];
  appendProjectCapabilityDiagnostics(project, diagnostics);
  const animations = sourceAnimations.flatMap((animation, index) => {
    const sourceIndex = project.animationSourceIndices?.[index] ?? index;
    const animationDiagnostics: ConversionIssue[] = [];
    try {
      const effects = projectEffectEvents(animation, cubeContent, animationDiagnostics, sourceIndex);
      const state = resolveAnimatedJavaAnimationState(effects, animation, project, nodes, transformGraph, nodeBindings, animationDiagnostics, sourceIndex);
      const ir = createAnimatedJavaAnimationIR(animation, project, transformGraph, nodes, sceneScale, state.nodeFrames, cubeContent);
      ir.animation.events = { start: state.startEvents, timeline: state.timelineEvents, loop: [], stop: [] };
      diagnostics.push(...animationDiagnostics);
      return [{ ir, id: sanitizeResourcePath(animation.name, `animation_${sourceIndex + 1}`), name: cubeContent ? animation.name : prettify(animation.name) }];
    } catch (reason) {
      diagnostics.push(skippedAnimationIssue(animation.name, `animations[${sourceIndex}]`, reason));
      return [];
    }
  });
  if (animations.length === 0) {
    const reasons = diagnostics.filter((issue) => issue.code === "animation_skipped").map((issue) => issue.message).join(" ");
    throw new ConversionError("no_importable_animations", `No Animated Java animations could be imported.${reasons ? ` ${reasons}` : ""}`);
  }
  if (cubeContent) writeReferencedAnimatedJavaCubeResources(cubeContent, project.resolution,
    new Set(animations.flatMap((animation) => [...referencedItemModelResources(animation.ir)])));
  const name = prettify(sourceStem);
  return {
    source: "animated_java_blueprint",
    sourceName: input.name,
    suggestedMetadata: { name, description: `${name} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodeHints: importedNodeHints(nodes),
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

function resolveAnimatedJavaAnimationState(
  effects: readonly TimelineEventIR[],
  source: AjProjectAnimation,
  project: AjProject,
  nodes: Record<string, ImportedNode>,
  graph: ProjectTransformGraph,
  bindings: ProjectNodeBindings,
  diagnostics: ConversionIssue[],
  animationIndex: number,
): AnimatedJavaAnimationState {
  const timeline = [...effects, ...nativeFunctionEvents(source, diagnostics, animationIndex)];
  const start = nativeStartEvents(project, nodes, graph, bindings);
  const variants = nativeVariants(project);
  const stateFrames: ProjectNodeStateFrame[] = [];
  for (const [animatorId, animator] of Object.entries(source.animators)) {
    for (const [keyframeIndex, frame] of (animator.keyframes ?? []).entries()) {
      if (frame.channel === "visibility") {
        const value = frame.data_points.at(-1)?.x;
        if (value !== undefined) {
          const visible = typeof value === "number" ? value !== 0 : value === "true" || value === "1" ? true : value === "false" || value === "0" ? false : value;
          for (const nodeId of projectOutputNodeIds(animatorId, graph, bindings)) stateFrames.push({ nodeId, time: frame.time, visible });
        }
        continue;
      }
      if (frame.channel !== "variant") continue;
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
        collectVariantFrames(stateFrames, project, graph, bindings, variantId, variant, frame.time);
        const onApply = stringField(variant, "on_apply_function") ?? stringField(variant, "onApplyFunction");
        if (onApply) timeline.push({ time: sourceSecondsTime(frame.time), source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: functionCommands(onApply) } });
      }
    }
  }
  return { startEvents: start, timelineEvents: timeline.sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time)), nodeFrames: stateFrames };
}

function nativeStartEvents(project: AjProject, nodes: Record<string, ImportedNode>, graph: ProjectTransformGraph, bindings: ProjectNodeBindings): EventIR[] {
  const events: EventIR[] = [];
  const settings = project.blueprint_settings;
  const rootFunction = settings ? stringField(settings, "custom_summon_commands") ?? stringField(settings, "on_summon_function") : undefined;
  if (rootFunction) events.push({ source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: functionCommands(rootFunction) } });
  for (const element of project.elements) {
    const value = isRecord(element) ? stringField(element, "onSummonFunction") ?? stringField(element, "on_summon_function") : undefined;
    if (value && (nodes[element.uuid] || bindings.get(element.uuid)?.includes(element.uuid))) events.push({ source: { type: "player" }, origin: { type: "node", node: element.uuid }, action: { type: "commands", commands: functionCommands(value) } });
  }
  for (const group of project.groups) {
    const value = group.onSummonFunction?.trim();
    if (!value) continue;
    const nodeId = projectOutputNodeIds(group.uuid, graph, bindings, false)[0];
    events.push({ source: { type: "player" }, origin: nodeId ? { type: "node", node: nodeId } : { type: "root" }, action: { type: "commands", commands: functionCommands(value) } });
  }
  return events;
}

function appendProjectCapabilityDiagnostics(project: AjProject, diagnostics: ConversionIssue[]): void {
  if (project.elements.some((element) => element.type === "camera")) diagnostics.push({ severity: "warning", code: "animated_java_camera_ignored", message: "Animated Java camera control was omitted; camera transforms remain as logical nodes.", sourcePath: "elements" });
  const supported = new Set(["cube", "locator", "camera", "animated_java:vanilla_block_display", "animated_java:vanilla_item_display", "animated_java:vanilla_text_display", "animated_java:text_display"]);
  for (const element of project.elements) {
    if (supported.has(element.type)) continue;
    diagnostics.push({
      severity: "warning",
      code: "animated_java_logical_node",
      message: `${element.name} (${element.type}) is preserved as a logical node in Animation v5; geometry is unavailable.`,
      sourcePath: `elements.${element.uuid}`,
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
  for (const [index, animation] of project.animations.entries()) {
    for (const property of ["start_delay", "loop_delay"] as const) {
      const value = animation[property];
      if (typeof value === "string" && value.trim() && !Number.isFinite(Number(value))) diagnostics.push({ severity: "warning", code: "animated_java_runtime_timing", message: `${animation.name}.${property} is preserved as Molang in Animation v5.`, sourcePath: `animations[${index}].${property}` });
    }
  }
}

function collectContinuousFunctionDiagnostics(value: unknown, path: string, diagnostics: ConversionIssue[]): void {
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

function projectEffectEvents(source: AjProjectAnimation, cubes: AnimatedJavaCubes | undefined, diagnostics: ConversionIssue[], animationIndex: number): TimelineEventIR[] {
  const events: TimelineEventIR[] = [];
  for (const [animatorId, animator] of Object.entries(source.animators)) {
    if (animatorId !== "effects" && animator.type !== "effect") continue;
    for (const [index, frame] of (animator.keyframes ?? []).entries()) {
      const path = `animations[${animationIndex}].animators.${animatorId}.keyframes[${index}]`;
      if (!["sound", "particle", "timeline"].includes(frame.channel)) {
        if (frame.channel !== "function" && frame.channel !== "commands") diagnostics.push({ severity: "warning", code: "animated_java_effect_ignored", message: `${source.name} at ${frame.time * 20}t: an unsupported effect was omitted.`, sourcePath: path });
        continue;
      }
      for (const point of frame.data_points) {
        const data = point as Record<string, unknown>;
        const locator = normalizeBlockbenchName(stringField(data, "locator"));
        const locatorNode = locator ? cubes?.bones.flatMap((bone) => bone.nodes).find((node) => normalizeBlockbenchName(node.locatorName) === locator) : undefined;
        const boneNode = locator ? cubes?.bones.find((bone) => normalizeBlockbenchName(bone.group.name) === locator)?.nodes[0] : undefined;
        const node = locatorNode ?? boneNode;
        const origin = node ? { type: "node" as const, node: node.id } : { type: "root" as const };
        const effect = stringField(data, "effect");
        const script = stringField(data, "script");
        if (frame.channel !== "timeline" && !effect) {
          diagnostics.push({ severity: "warning", code: "animated_java_effect_ignored", message: `${source.name} at ${frame.time * 20}t: an effect without a resource identifier was omitted.`, sourcePath: path });
          continue;
        }
        if (locator && !node) diagnostics.push({ severity: "warning", code: "animated_java_effect_origin_approximated", message: `${source.name} at ${frame.time * 20}t: effect locator could not be resolved; the root position is used. Review and edit the event.`, sourcePath: path });
        if (frame.channel === "sound" && effect) events.push({ time: sourceSecondsTime(frame.time), source: { type: "player" }, origin, action: { type: "commands", commands: [`playsound ${effect} master @s ~ ~ ~`] } });
        else if (frame.channel === "particle" && effect) {
          events.push({ time: sourceSecondsTime(frame.time), source: { type: "server" }, origin, action: { type: "commands", commands: [`particle ${effect} ~ ~ ~`] } });
          if (script) diagnostics.push({ severity: "warning", code: "animated_java_particle_script_ignored", message: `${source.name} at ${frame.time * 20}t: particle pre-effect script was omitted. Review and edit the event.`, sourcePath: path });
        } else if (frame.channel === "timeline") {
          const lines = (script ?? "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
          const commands = lines.map((line) => line.replace(/^\//, "").trim()).filter(Boolean);
          if (commands.length) events.push({ time: sourceSecondsTime(frame.time), source: { type: "player" }, origin, action: { type: "commands", commands } });
          if (lines.some((line) => !line.startsWith("/"))) diagnostics.push({ severity: "warning", code: "animated_java_instruction_approximated", message: `${source.name} at ${frame.time * 20}t: uninterpreted instructions were kept as commands; behavior may differ. Review and edit them.`, sourcePath: path });
        }
      }
    }
  }
  return events.sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time));
}

function nativeFunctionEvents(source: AjProjectAnimation, diagnostics: ConversionIssue[], animationIndex: number): TimelineEventIR[] {
  const result: TimelineEventIR[] = [];
  for (const [animatorId, animator] of Object.entries(source.animators)) {
    for (const [keyframeIndex, frame] of (animator.keyframes ?? []).entries()) {
      if (frame.channel !== "function" && frame.channel !== "commands") continue;
      const sourcePath = `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]`;
      for (const point of frame.data_points) {
        const text = point.function ?? point.commands ?? "";
        const commands = text.split(/\r?\n/).map((line) => line.trim().replace(/^\//, "")).filter(Boolean);
        if (commands.length === 0) continue;
        if (point.execute_condition?.trim()) diagnostics.push({ severity: "warning", code: "animated_java_execute_condition_ignored", message: `${source.name} at ${frame.time * 20}t: execution condition was omitted. Review and edit the event.`, sourcePath });
        if (point.repeat) diagnostics.push({ severity: "warning", code: "animated_java_repeating_function_ignored", message: `${source.name} at ${frame.time * 20}t: repeating function behavior was converted to a single timeline event. Review and edit the event.`, sourcePath });
        result.push({ time: sourceSecondsTime(frame.time), source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands } });
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
  graph: ProjectTransformGraph,
  bindings: ProjectNodeBindings,
  variantId: string,
  variant: Record<string, unknown>,
  time: number,
): void {
  const excluded = new Set(Array.isArray(variant.excluded_nodes) ? variant.excluded_nodes.filter((value): value is string => typeof value === "string") : []);
  for (const sourceId of excluded) {
    for (const nodeId of projectOutputNodeIds(sourceId, graph, bindings)) frames.push({ nodeId, time, visible: false });
  }
  for (const element of project.elements) {
    if (["cube", "locator", "camera"].includes(element.type)) continue;
    const configs = (element as Record<string, unknown>).configs;
    const config = isRecord(configs) && isRecord(configs.variants) ? configs.variants[variantId] : undefined;
    if (isRecord(config)) collectVariantConfigFrame(frames, element.uuid, config, time);
  }
  for (const group of project.groups) {
    const config = group.configs?.variants?.[variantId];
    if (!isRecord(config)) continue;
    for (const nodeId of projectOutputNodeIds(group.uuid, graph, bindings, false)) collectVariantConfigFrame(frames, nodeId, config, time);
  }
}

function collectVariantConfigFrame(frames: ProjectNodeStateFrame[], nodeId: string, config: Record<string, unknown>, time: number): void {
  const visible = typeof config.invisible === "boolean" ? !config.invisible : undefined;
  const nbt = nativeDisplayConfigNbt(config);
  if (visible !== undefined || nbt) frames.push({ nodeId, time, ...(visible === undefined ? {} : { visible }), ...(nbt ? { nbt: readDisplayNbt(nbt) } : {}) });
}

function projectOutputNodeIds(sourceId: string, graph: ProjectTransformGraph, bindings: ProjectNodeBindings, includeDescendants = true): string[] {
  const result = new Set<string>();
  for (const nodeId of bindings.get(sourceId) ?? []) result.add(nodeId);
  const groupIds = [sourceId, ...(includeDescendants ? [...graph.groups.keys()].filter((id) => projectGroupDescendsFrom(id, sourceId, graph)) : [])];
  for (const groupId of groupIds) {
    for (const nodeId of bindings.get(groupId) ?? []) result.add(nodeId);
    for (const [elementId, parentId] of graph.elementParents) {
      if (parentId !== groupId) continue;
      for (const nodeId of bindings.get(elementId) ?? []) result.add(nodeId);
    }
  }
  return [...result];
}

function projectGroupDescendsFrom(id: string, ancestorId: string, graph: ProjectTransformGraph): boolean {
  for (let current = graph.groupParents.get(id); current; current = graph.groupParents.get(current)) if (current === ancestorId) return true;
  return false;
}

function applyGroupDefaultConfigs(nodes: Record<string, ImportedNode>, project: AjProject, graph: ProjectTransformGraph, bindings: ProjectNodeBindings): void {
  for (const group of project.groups) {
    const config = group.configs?.default;
    if (!isRecord(config)) continue;
    const nbt = nativeDisplayConfigNbt(config);
    for (const nodeId of projectOutputNodeIds(group.uuid, graph, bindings, false)) {
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

function isDirectDisplay(type: string): type is AjProjectDisplayElement["type"] {
  return [
    "animated_java:vanilla_block_display",
    "animated_java:vanilla_item_display",
    "animated_java:vanilla_text_display",
    "animated_java:text_display",
  ].includes(type);
}

function importProjectAnchor(element: AjProjectLocator): ImportedNode {
  return {
    binding: { sourceNodeId: element.uuid },
    type: "anchor",
  };
}

function importProjectElement(element: AjProjectDisplayElement): ImportedNode {
  const config = nativeDefaultConfig(element);
  const entityNbt = nativeDisplayConfigNbt(config);
  const visible = element.visibility !== false && config?.invisible !== true;
  if (element.type === "animated_java:vanilla_block_display") {
    return {
      binding: { sourceNodeId: element.uuid },
      type: "block_display",
      visible,
      ...(entityNbt ? { entityNbt } : {}),
      blockState: blockArgumentToData(element.block ?? "minecraft:air"),
    };
  }
  if (element.type === "animated_java:vanilla_item_display") {
    return {
      binding: { sourceNodeId: element.uuid },
      type: "item_display",
      visible,
      ...(entityNbt ? { entityNbt } : {}),
      itemDisplay: element.itemDisplay ?? element.item_display ?? "none",
      itemStack: itemArgumentToData(element.item ?? "minecraft:air"),
    };
  }
  return {
    binding: { sourceNodeId: element.uuid },
    type: "text_display",
    visible,
    ...(entityNbt ? { entityNbt } : {}),
    text: element.text ?? { text: element.name },
  };
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
