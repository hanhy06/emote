import { readFile } from "node:fs/promises";
import { expect, it } from "vitest";
import { combineConversionDocuments } from "../domain/conversionBatch";
import { assignSkinPart, createConversionDocument, replaceTimelineEvents } from "../domain/conversionDocument";
import { exportAnimation, exportAnimations } from "../export/projectExporter";
import type { AnimationJson } from "../format/animation";
import { readInput } from "../import/formats";
import MolangParser from "molangjs/dist/molang.esm.js";
import { evaluatePose } from "../domain/animationIRPose";
import { attachmentTargetId, type NodeIR, type TrackIR } from "../domain/animationIR";
import { evaluateEasing, type ScalarEvaluatorIR } from "../domain/animationIRCurves";
import { PreviewMolangEvaluator } from "../preview/molangEvaluator";
import { createPreviewModel } from "../preview/previewModel";
import { INITIAL_WORKSPACE, sequenceReferenceWarnings, workspaceReducer } from "../workspace";

const animation: AnimationJson = {
  type: "animation", schema_version: 5, target_minecraft_version: "26.3", id: "sit:sit_down",
  metadata: { name: "Runtime preservation", description: "Fixed authored v5 runtime contract", author: "test", license: "Apache-2.0" },
  settings: {
    standalone: true, cooldown: "0.25s", rotation_deadzone: 13,
    player: { hidden: false, stop_conditions: { movement_distance: 0.1, jump: false, submerge: true, ride: false, damage: true, attack: false, game_mode_change: true } },
  },
  callbacks: [{ name: "example:finish", payload: "keep this payload" }],
  source: { format: "authored", custom: { value: 7 } },
  resources: { accessory: { kind: "external", uri: "example:accessory" } },
  nodes: {
    root: { transform: [{ id: "scale", op: "scale", value: [1, 1, 1] }] },
    arm: {
      parent: "root", inherit: { rotation: "entity", scale: false, visibility: true },
      transform: [{ id: "position", op: "translate", value: [0, 1.5, 0] }, { id: "spin", op: "rotate_euler", order: "ZYX", value: [0, 0, 0] }],
      attachments: {
        prop: { type: "item_display", item_stack_snbt: '{id:"minecraft:paper",count:1}', item_display: "fixed", entity_nbt: "{glowing:1b}" },
        custom: { type: "external", key: "example:accessory", data: { variant: "keep" } },
      },
    },
  },
  animation: {
    duration: "2s", clock: { type: "molang", expression: "q.anim_time + q.delta_time * v.speed" },
    playback: { mode: "loop", loop_start: "5t", start_delay: "0.15s", loop_delay: { molang: "v.pause * 20" } },
    molang: { initialize: "v.speed = 1; v.pause = 0.5;", tick: "v.speed = q.is_sneaking ? 0.5 : 1;" },
    tracks: [
      {
        target: { node: "arm", operation: "position" }, channel: "value",
        driver: {
          type: "curve", before: "first_pre",
          keys: [
            { time: "0t", value: [0, 1.5, { molang: "math.sin(q.anim_time * 180) * 0.1" }] },
            { time: "0.25s", pre: [0.1, 1.5, 0], post: [0.2, 1.5, { molang: "v.speed * 0.1" }] },
            { time: "40t", value: [0, 1.5, 0] },
          ],
          segments: [
            { interpolation: "linear", easing: { kernel: "power", direction: "in_out", exponent: 2 } },
            {
              interpolation: "bezier", easing: { kernel: "sine", direction: "out" },
              handles: [
                { out: { time: 0.3, value: 0.4 }, in: { time: 0.7, value: 0.2 } },
                { out: { time: 0.3, value: 1.8 }, in: { time: 0.7, value: 1.8 } },
                { out: { time: 0.3, value: { molang: "v.speed * 0.1" } }, in: { time: 0.7, value: 0 } },
              ],
            },
          ],
        },
      },
      { target: { node: "arm", operation: "spin" }, channel: "value", driver: { type: "expression", value: [0, { molang: "q.target_y_rotation" }, 0] } },
      { target: { node: "root" }, channel: "visible", driver: { type: "state", keys: [{ time: "0t", value: true }, { time: "1.9s", value: { molang: "q.is_sneaking" } }] } },
      {
        target: { node: "arm", attachment: "prop" }, channel: "nbt",
        driver: { type: "state", keys: [{ time: "0t", value: { merge: "{glowing:1b}", remove: [] } }, { time: "1s", value: { merge: { molang: "v.patch" }, remove: ["glowing"] } }] },
      },
    ],
    events: {
      start: [{ source: { type: "server" }, origin: { type: "root" }, action: { type: "commands", commands: ["say start"] } }],
      timeline: [{ time: "0.15s", direction: "both", source: { type: "node", node: "arm", attachment: "prop" }, origin: { type: "node", node: "arm", offset: [0, 0.5, 0], rotation: "origin" }, action: { type: "commands", commands: ["say first", "say second"] } }],
      loop: [{ source: { type: "player" }, origin: { type: "root" }, action: { type: "external", key: "example:loop", data: { payload: "keep" } } }],
      stop: [{ source: { type: "server" }, origin: { type: "root" }, action: { type: "commands", commands: ["say stop"] } }],
    },
  },
};

const nbtTrack = animation.animation.tracks.find((track): track is Extract<TrackIR, { channel: "nbt" }> => track.channel === "nbt")!;

it("preserves authored v5 animation runtime data through import and export", async () => {
  const project = await readInput("emote_json", { name: "runtime.json", bytes: new TextEncoder().encode(JSON.stringify(animation)) });
  const [output] = await exportAnimation(createConversionDocument(project, "Emote JSON"), 0);
  expect(JSON.parse(await output.blob.text())).toEqual(animation);
});

it("prunes only proven static nodes in output while preserving curves, event origins and the editable document", async () => {
  const authored = structuredClone(animation);
  const hidden: NodeIR = {
    transform: [{ id: "scale", op: "scale" as const, value: [0, 0, 0] }],
    attachments: { display: structuredClone(animation.nodes.arm.attachments!.prop) },
  };
  authored.nodes.tiny = structuredClone(hidden);
  authored.nodes.tiny.attachments!.display = { type: "item_display", item_stack_snbt: '{id:"minecraft:paper",components:{"minecraft:item_model":"probe:unused"}}', item_display: "fixed" };
  authored.nodes.spline = structuredClone(hidden);
  authored.nodes.dynamic = structuredClone(hidden);
  authored.nodes.origin = structuredClone(hidden);
  authored.animation.tracks.push({ target: { node: "spline", operation: "scale" }, channel: "value", driver: {
    type: "curve", keys: [{ time: "0t", value: [0, 0, 0] }, { time: "20t", value: [0, 0, 0] }],
    segments: [{ interpolation: "hermite", out_tangent: [8, 8, 8], in_tangent: [-8, -8, -8] }],
  } }, { target: { node: "dynamic", operation: "scale" }, channel: "value", driver: {
    type: "expression", value: [{ molang: "q.anim_time" }, 0, 0],
  } });
  authored.animation.events!.start!.push({ source: { type: "server" }, origin: { type: "node", node: "origin" }, action: { type: "commands", commands: ["say protected"] } });
  const project = await readInput("emote_json", { name: "pruning.json", bytes: new TextEncoder().encode(JSON.stringify(authored)) });
  project.resources.set("assets/probe/items/unused.json", { kind: "item_model", model: "probe:unused" });
  const document = createConversionDocument(project, "Emote JSON");
  const snapshot = structuredClone(document);
  expect(document.nodes.tiny).toBeDefined();
  const outputs = await exportAnimation(document, 0);
  expect(outputs).toHaveLength(1);
  const actual: AnimationJson = JSON.parse(await outputs[0].blob.text());
  expect(actual.nodes.tiny).toBeUndefined();
  expect(actual.nodes.spline).toEqual(authored.nodes.spline);
  expect(actual.nodes.dynamic).toEqual(authored.nodes.dynamic);
  expect(actual.nodes.origin).toEqual(authored.nodes.origin);
  expect(actual.animation.tracks.filter((track) => ["spline", "dynamic"].includes(track.target.node)))
    .toEqual(authored.animation.tracks.filter((track) => ["spline", "dynamic"].includes(track.target.node)));
  expect(actual.animation.events).toEqual(authored.animation.events);
  expect(evaluatePose({ ...actual, animation: { ...actual.animation, tracks: actual.animation.tracks.filter((track) => track.target.node === "spline") } }, 0.5, Number).spline.matrix.elements[0]).toBe(2);
  expect(document).toEqual(snapshot);
});

it("prunes zero scales under animated ancestors while preserving inheritance breaks and event references", async () => {
  const authored = structuredClone(animation);
  const prop: NodeIR = {
    parent: "arm", transform: [{ id: "scale", op: "scale", value: [1, 1, 1] }],
    attachments: { display: structuredClone(animation.nodes.arm.attachments!.prop) },
  };
  authored.nodes.zeroGroup = { parent: "arm", transform: [{ id: "scale", op: "scale", value: [1, 1, 1] }] };
  authored.nodes.inherited = { ...structuredClone(prop), parent: "zeroGroup" };
  authored.nodes.ownZero = { ...structuredClone(prop), transform: [
    { id: "scale", op: "scale", value: [0, 0, 0] }, { id: "rotation", op: "rotate_euler", order: "XYZ", value: [0, 0, 0] },
  ] };
  authored.nodes.retainedGroup = { parent: "arm", transform: [{ id: "scale", op: "scale", value: [0, 0, 0] }] };
  authored.nodes.hiddenSibling = { ...structuredClone(prop), parent: "retainedGroup" };
  authored.nodes.independent = { ...structuredClone(prop), parent: "retainedGroup", inherit: { scale: false } };
  authored.nodes.resumed = { ...structuredClone(prop), parent: "independent" };
  authored.nodes.independentZero = { ...structuredClone(prop), parent: "retainedGroup", inherit: { scale: false },
    transform: [{ id: "scale", op: "scale", value: [0, 0, 0] }],
  };
  authored.nodes.originZero = { ...structuredClone(prop), parent: "retainedGroup" };
  authored.nodes.sourceZero = { ...structuredClone(prop), parent: "retainedGroup" };
  authored.animation.tracks.push(
    { target: { node: "zeroGroup", operation: "scale" }, channel: "value", driver: { type: "curve", keys: [{ time: "0t", value: [0, 0, 0] }], segments: [] } },
    { target: { node: "inherited", operation: "scale" }, channel: "value", driver: { type: "expression", value: [{ molang: "q.anim_time" }, 1, 1] } },
    { target: { node: "ownZero", operation: "rotation" }, channel: "value", driver: { type: "expression", value: [0, { molang: "q.anim_time * 180" }, 0] } },
  );
  authored.animation.events!.start!.push(
    { source: { type: "server" }, origin: { type: "node", node: "originZero" }, action: { type: "commands", commands: ["say origin"] } },
    { source: { type: "node", node: "sourceZero", attachment: "display" }, origin: { type: "root" }, action: { type: "commands", commands: ["say source"] } },
  );
  const project = await readInput("emote_json", { name: "zero-scale.json", bytes: new TextEncoder().encode(JSON.stringify(authored)) });
  const document = createConversionDocument(project, "Emote JSON");
  const snapshot = structuredClone(document);
  const [output] = await exportAnimation(document, 0);
  const actual: AnimationJson = JSON.parse(await output.blob.text());
  const removed = ["zeroGroup", "inherited", "ownZero", "hiddenSibling", "independentZero"];
  for (const id of removed) expect(actual.nodes[id], id).toBeUndefined();
  for (const id of ["retainedGroup", "independent", "resumed", "originZero", "sourceZero"]) expect(actual.nodes[id], id).toEqual(authored.nodes[id]);
  expect(actual.animation.tracks.some((track) => removed.includes(track.target.node))).toBe(false);
  expect(actual.animation.events).toEqual(authored.animation.events);
  expect(document).toEqual(snapshot);
});

it("preserves scales that are only initially zero, uncertain, or tiny under animated ancestors", async () => {
  const authored = structuredClone(animation);
  const prop: NodeIR = {
    parent: "arm", transform: [{ id: "scale", op: "scale", value: [1, 1, 1] }],
    attachments: { display: structuredClone(animation.nodes.arm.attachments!.prop) },
  };
  for (const id of ["growing", "lateZero", "blended", "spline", "dynamic", "tiny"]) authored.nodes[id] = structuredClone(prop);
  authored.nodes.spline.transform![0].value = [0, 0, 0];
  authored.nodes.dynamic.transform![0].value = [0, 0, 0];
  authored.nodes.tiny.transform![0].value = [0.0001, 0.0001, 0.0001];
  authored.animation.tracks.push(
    { target: { node: "growing", operation: "scale" }, channel: "value", driver: {
      type: "curve", keys: [{ time: "0t", value: [0, 0, 0] }, { time: "20t", value: [1, 1, 1] }], segments: [{ interpolation: "linear" }],
    } },
    { target: { node: "lateZero", operation: "scale" }, channel: "value", driver: { type: "curve", keys: [{ time: "20t", value: [0, 0, 0] }], segments: [] } },
    { target: { node: "blended", operation: "scale" }, channel: "value", blend: { base: [1, 1, 1], weight: 0.5 },
      driver: { type: "curve", keys: [{ time: "0t", value: [0, 0, 0] }], segments: [] },
    },
    { target: { node: "spline", operation: "scale" }, channel: "value", driver: {
      type: "curve", keys: [{ time: "0t", value: [0, 0, 0] }, { time: "20t", value: [0, 0, 0] }],
      segments: [{ interpolation: "hermite", out_tangent: [8, 8, 8], in_tangent: [-8, -8, -8] }],
    } },
    { target: { node: "dynamic", operation: "scale" }, channel: "value", driver: { type: "expression", value: [{ molang: "q.anim_time" }, 0, 0] } },
  );
  const project = await readInput("emote_json", { name: "preserved-scales.json", bytes: new TextEncoder().encode(JSON.stringify(authored)) });
  const [output] = await exportAnimation(createConversionDocument(project, "Emote JSON"), 0);
  const actual: AnimationJson = JSON.parse(await output.blob.text());
  for (const id of ["growing", "lateZero", "blended", "spline", "dynamic", "tiny"]) expect(actual.nodes[id], id).toEqual(authored.nodes[id]);
  expect(actual.animation.tracks).toEqual(authored.animation.tracks);
});

it("edits animation ticks independently of preview delays and keeps generated events within the authored duration", async () => {
  for (const startDelay of ["10t", { molang: "q.scoreboard('pause')" }]) {
    const authored = structuredClone(animation);
    authored.nodes.arm.attachments!.prop = { type: "item_display", item_stack_snbt: '{id:"minecraft:player_head",count:1}', item_display: "none" };
    authored.animation = { duration: "20t", playback: { start_delay: startDelay }, tracks: [
      { target: { node: "arm", operation: "position" }, channel: "value", driver: { type: "expression", value: [{ molang: "q.anim_time" }, 1.5, 0] } },
    ] };
    const project = await readInput("emote_json", { name: "delay.json", bytes: new TextEncoder().encode(JSON.stringify(authored)) });
    let state = workspaceReducer(INITIAL_WORKSPACE, { type: "documents_open_succeeded", document: createConversionDocument(project, "Emote JSON") });
    expect(state.session!.selectedFrame).toEqual({ type: "create" });
    state = workspaceReducer(state, { type: "frame_selected", frame: { type: "timeline", tick: 5 } });
    let session = state.session!;
    const preview = createPreviewModel(session.document, session.document.animations[0], session.selectedFrame);
    expect(preview.parts[0].matrix[3]).toBe(0.25);
    expect(preview.availability).toEqual({ status: "full" });
    const event = { source: { type: "server" as const }, origin: { type: "root" as const }, action: { type: "commands" as const, commands: ["say frame"] } };
    state = workspaceReducer(state, { type: "timeline_events_changed", tick: 5, events: [event] });
    state = workspaceReducer(state, { type: "frame_selected", frame: { type: "timeline", tick: 30 } });
    expect(state.session!.selectedFrame).toEqual({ type: "timeline", tick: 20 });
    state = workspaceReducer(state, { type: "timeline_events_changed", tick: 20, events: [event] });
    session = state.session!;
    const [output] = await exportAnimation(session.document, 0);
    const actual: AnimationJson = JSON.parse(await output.blob.text());
    expect(actual.animation.duration).toBe("20t");
    expect(actual.animation.playback!.start_delay).toEqual(startDelay);
    expect(actual.animation.events!.timeline).toEqual([{ ...event, time: "5t" }, { ...event, time: "20t" }]);
    expect(() => replaceTimelineEvents(session.document, 0, 30, [event])).toThrow(/within the animation duration/);
  }
});

it("omits skin NBT only in output and restores original display data when unassigned", async () => {
  const authored = structuredClone(animation);
  authored.nodes.arm.attachments!.prop = {
    type: "item_display", item_stack_snbt: '{id:"minecraft:player_head",count:1}', item_display: "none", visible: true, entity_nbt: "{Glowing:1b}",
  };
  authored.nodes.arm.attachments!.other = structuredClone(animation.nodes.arm.attachments!.prop);
  authored.animation.tracks.push({ ...structuredClone(nbtTrack), target: { node: "arm", attachment: "other" } });
  authored.animation.tracks.push({ target: { node: "arm", attachment: "prop" }, channel: "visible", driver: { type: "state", keys: [{ time: "0t", value: true }, { time: "1s", value: false }] } });
  const project = await readInput("emote_json", { name: "skin.json", bytes: new TextEncoder().encode(JSON.stringify(authored)) });
  const original = createConversionDocument(project, "Emote JSON");
  const document = assignSkinPart(original, new Set([attachmentTargetId("arm", "prop")]), "head");
  const snapshot = structuredClone(document);
  const expected = structuredClone(authored);
  expected.nodes.arm.attachments!.prop = { type: "player_skin", part: "head", region: { from: 0, to: 1 }, visible: true };
  expected.animation.tracks.splice(3, 1);
  for (const files of [await exportAnimation(document, 0), await exportAnimations(document, false)]) {
    expect(files).toHaveLength(1);
    expect(JSON.parse(await files[0].blob.text())).toEqual(expected);
  }
  expect(document).toEqual(snapshot);
  const restored = assignSkinPart(document, new Set([attachmentTargetId("arm", "prop")]), null);
  const [output] = await exportAnimation(restored, 0);
  expect(JSON.parse(await output.blob.text())).toEqual(authored);
});

it("fits attachments independently without duplicating node expressions and restores their authored tracks and data", async () => {
  const authored = structuredClone(animation);
  authored.nodes.arm.attachments!.prop = { type: "item_display", item_stack_snbt: '{id:"minecraft:player_head",count:1}', item_display: "none", entity_nbt: "{Glowing:1b}" };
  authored.nodes.arm.attachments!.other = structuredClone(authored.nodes.arm.attachments!.prop);
  authored.nodes.arm.attachments!.decoration = structuredClone(animation.nodes.arm.attachments!.prop);
  authored.nodes.locator = { parent: "arm", transform: [{ id: "position", op: "translate", value: [0, 1, 0] }] };
  authored.animation.tracks = [
    { target: { node: "arm", operation: "position" }, channel: "value", driver: { type: "expression", value: [{ molang: "q.scoreboard('motion')" }, 1.5, 0] } },
    structuredClone(nbtTrack),
    { ...structuredClone(nbtTrack), target: { node: "arm", attachment: "other" } },
    { target: { node: "arm", attachment: "prop" }, channel: "visible", driver: { type: "state", keys: [{ time: "0t", value: true }, { time: "1s", value: false }] } },
  ];
  const project = await readInput("emote_json", { name: "attachments.json", bytes: new TextEncoder().encode(JSON.stringify(authored)) });
  project.nodeHints.arm.skinCandidates!.prop.fittingMatrix = [2, 0, 0, 1, 0, 2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1];
  project.nodeHints.arm.skinCandidates!.other.fittingMatrix = [3, 0, 0, -1, 0, 3, 0, 0, 0, 0, 3, 0, 0, 0, 0, 1];
  const original = createConversionDocument(project, "Emote JSON");
  expect(Object.keys(original.skinCandidates)).toEqual([attachmentTargetId("arm", "prop"), attachmentTargetId("arm", "other")]);
  let document = assignSkinPart(original, new Set([attachmentTargetId("arm", "prop")]), "head");
  const [output] = await exportAnimation(document, 0);
  const actual: AnimationJson = JSON.parse(await output.blob.text());
  const childId = Object.keys(actual.nodes).find((id) => id !== "arm" && actual.nodes[id].attachments?.prop)!;
  expect(actual.nodes[childId].parent).toBe("arm");
  expect(actual.nodes[childId].attachments!.prop).toEqual({ type: "player_skin", part: "head", region: { from: 0, to: 1 } });
  expect(actual.nodes.arm.attachments!.other).toEqual(authored.nodes.arm.attachments!.other);
  expect(actual.nodes.arm.attachments!.decoration).toEqual(authored.nodes.arm.attachments!.decoration);
  expect(actual.animation.tracks.filter((track) => track.channel === "value")).toEqual([authored.animation.tracks[0]]);
  expect(actual.animation.tracks.find((track) => track.channel === "visible")!.target).toEqual({ node: childId, attachment: "prop" });
  expect(actual.animation.tracks.filter((track) => track.channel === "nbt")).toEqual([authored.animation.tracks[2]]);
  const event = actual.animation.events!.timeline![0];
  expect(event.source).toEqual({ type: "node", node: childId, attachment: "prop" });
  expect(event.origin).toEqual(authored.animation.events!.timeline![0].origin);
  let calls = 0;
  const poses = evaluatePose(actual, 0.5, (value) => {
    if (typeof value === "number") return value;
    calls++;
    return 7;
  });
  expect(calls).toBe(1);
  expect(poses.arm.matrix.elements[12]).toBe(7);
  expect(poses[childId].matrix.elements[12]).toBe(8);
  expect(poses[childId].matrix.elements[0]).toBe(2);
  expect(poses.locator.matrix.elements[13]).toBe(2.5);
  document = assignSkinPart(document, new Set([attachmentTargetId("arm", "other")]), "body");
  const [both] = await exportAnimation(document, 0);
  const bothOutput: AnimationJson = JSON.parse(await both.blob.text());
  expect(Object.values(bothOutput.nodes).flatMap((node) => Object.values(node.attachments ?? {})).filter((attachment) => attachment.type === "player_skin").map((attachment) => attachment.part).sort()).toEqual(["body", "head"]);
  const restored = assignSkinPart(document, new Set(Object.keys(document.skinCandidates)), null);
  const [restoredOutput] = await exportAnimation(restored, 0);
  expect(JSON.parse(await restoredOutput.blob.text())).toEqual(authored);
  const batch = combineConversionDocuments([original, original]);
  expect(batch.skinCandidates[attachmentTargetId("input_2__arm", "other")].nodeId).toBe("input_2__arm");
  const assignedBatch = assignSkinPart(batch, new Set([attachmentTargetId("input_2__arm", "other")]), "body");
  expect(assignedBatch.nodes.input_1__arm.attachments!.other.type).toBe("item_display");
  expect(assignedBatch.nodes.input_2__arm.attachments!.other.type).toBe("player_skin");
});

it("preserves and evaluates shared v5 curves, dynamic arguments and blend in track order", async () => {
  const bytes = await readFile(new URL("../../../src/test/resources/common-curves.json", import.meta.url));
  const authored: AnimationJson = JSON.parse(bytes.toString("utf8"));
  const project = await readInput("emote_json", { name: "common-curves.json", bytes });
  const [output] = await exportAnimation(createConversionDocument(project, "Emote JSON"), 0);
  const actual: AnimationJson = JSON.parse(await output.blob.text());
  expect(actual).toEqual(authored);
  const parser = new MolangParser();
  for (const tick of [0, 10, 15, 30]) {
    const calls = new Map<string, number>();
    const scoreboard = (id: string) => {
      const count = (calls.get(id) ?? 0) + 1;
      calls.set(id, count);
      if (id === "unused") throw new Error("Unused curve data was evaluated.");
      return id === "weight" ? 0.5 : id === "power" ? 2 : id === "out_time" ? 1 / 3 : id === "in_time" ? 2 / 3 : count;
    };
    const evaluate: ScalarEvaluatorIR = (value, progress, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
      "query.key_frame_lerp_time": progress, "query.value": resolveValue, "query.scoreboard": scoreboard,
    });
    const pose = evaluatePose(actual, tick / 20, evaluate);
    expect(calls.get("weight")).toBe(1);
    expect(calls.get("random")).toBe(2);
    expect(calls.get("order")).toBe(2);
    expect(pose.z.matrix.elements[13]).toBe(0.5);
    expect(pose.z.matrix.elements[14]).toBe(1.5);
    expect(pose.c.matrix.elements[12]).toBeGreaterThan(2.1);
    expect(pose.c.matrix.elements[12]).toBeLessThan(2.9);
    expect(pose.s.matrix.elements[12]).toBe(1);
    expect(calls.has("unused")).toBe(false);
    if (tick === 0) {
      expect(calls.has("power")).toBe(false);
      expect(calls.has("out_time")).toBe(false);
      expect(pose.a.matrix.elements[12]).toBe(0);
    } else {
      expect(pose.a.matrix.elements[12]).toBeCloseTo(pose.z.matrix.elements[12], 10);
      expect(calls.get("out_time")).toBe(3);
      expect(calls.get("in_time")).toBe(3);
      expect(calls.get("power") ?? 0).toBe(tick === 15 ? 1 : 0);
    }
    if (tick === 15) {
      expect(pose.a.matrix.elements[13]).toBeCloseTo(2.3125, 10);
      expect(pose.a.matrix.elements[14]).toBeCloseTo(0.875, 10);
      expect(pose.b.matrix.elements[0]).toBeCloseTo(2.8828125, 10);
    }
  }
  expect(evaluateEasing({ kernel: "blockbench_expo", direction: "in" }, 0)).toBe(1 / 1024);
  expect(evaluateEasing({ kernel: "blockbench_steps", direction: "in", count: 5 }, 1)).toBe(0.8);
  const preview = new PreviewMolangEvaluator();
  expect(preview.evaluate("q.value('bend', 0) + q.key_frame_lerp_time", { animationTime: 0.75, keyframeLerpTime: 0.25, resolveValue: () => 1.5 })).toBe(1.75);
});

it("preserves sequence timing, choices, controls, callbacks and external references", async () => {
  const bytes = await readFile(new URL("../../../docs/reference/sequence.json", import.meta.url));
  const expected = JSON.parse(bytes.toString("utf8"));
  const sequence = await readInput("emote_sequence", { name: "sequence.json", bytes });
  const project = await readInput("emote_json", { name: "runtime.json", bytes: new TextEncoder().encode(JSON.stringify(animation)) });
  const document = combineConversionDocuments([createConversionDocument(project, "Emote JSON")], [sequence]);
  const outputs = await exportAnimations(document, true);
  const exported = await Promise.all(outputs.filter((output) => output.fileName.endsWith(".json")).map(async (output) => JSON.parse(await output.blob.text())));
  expect(exported.find((output) => output.type === "sequence")).toEqual(expected);
  expect(exported.find((output) => output.type === "animation")).toEqual({ ...animation, settings: { ...animation.settings, standalone: false } });
});

it("reserves authored IDs and preserves ambiguous sequence references instead of selecting the last input", async () => {
  const documents = await Promise.all(["probe:a", "probe:a", "probe:a_2", "probe:unique"].map(async (id, index) => {
    const authored = { ...structuredClone(animation), id };
    const project = await readInput("emote_json", { name: `input_${index}.json`, bytes: new TextEncoder().encode(JSON.stringify(authored)) });
    return createConversionDocument(project, "Emote JSON");
  }));
  const document = combineConversionDocuments(documents);
  expect(document.animations.map((entry) => entry.id)).toEqual(["probe:a", "probe:a_3", "probe:a_2", "probe:unique"]);
  document.sequence.steps = [
    { emote: "probe:a" },
    { emote: [{ id: "probe:a_2", chance: 3 }, { id: "probe:a", chance: 1 }] },
    { emote: "probe:unique" }, { emote: "external:unknown" }, { emote: "emote:break" }, { emote: "emote:empty" },
  ];
  let outputs = await exportAnimations(document, true);
  let exported = await Promise.all(outputs.map(async (output) => JSON.parse(await output.blob.text())));
  expect(exported.find((entry) => entry.type === "sequence").steps).toEqual([
    { emote: "probe:a" }, { emote: ["probe:a_2", 3, "probe:a", 1] },
    { emote: "probe:unique" }, { emote: "external:unknown" }, { emote: "emote:break" }, { emote: "emote:empty" },
  ]);
  expect(sequenceReferenceWarnings(document).map((issue) => issue.code)).toEqual(["sequence_external_reference"]);
  document.animations[0].id = "probe:first";
  document.animations[1].id = "probe:second";
  document.animations[3].id = "probe:renamed";
  const snapshot = structuredClone(document);
  outputs = await exportAnimations(document, true);
  exported = await Promise.all(outputs.map(async (output) => JSON.parse(await output.blob.text())));
  expect(exported.find((entry) => entry.type === "sequence").steps).toEqual([
    { emote: "probe:a" }, { emote: ["probe:a_2", 3, "probe:a", 1] },
    { emote: "probe:renamed" }, { emote: "external:unknown" }, { emote: "emote:break" }, { emote: "emote:empty" },
  ]);
  expect(sequenceReferenceWarnings(document).map((issue) => issue.code)).toEqual([
    "sequence_ambiguous_reference", "sequence_ambiguous_reference", "sequence_external_reference",
  ]);
  expect(document).toEqual(snapshot);
});

it("rejects invalid shared dependencies and dynamic curve parameters", async () => {
  const bytes = await readFile(new URL("../../../src/test/resources/common-curves.json", import.meta.url));
  const authored: AnimationJson = JSON.parse(bytes.toString("utf8"));
  const parser = new MolangParser();
  const evaluate: ScalarEvaluatorIR = (value, progress, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
    "query.key_frame_lerp_time": progress, "query.value": resolveValue,
    "query.scoreboard": (id: string) => id === "weight" ? 0.5 : id === "out_time" ? 1 / 3 : id === "in_time" ? 2 / 3 : 2,
  });
  for (const expression of ["q.value('random', 0)", "q.value('missing', 0)", "q.value('bend', 1)"]) {
    const invalid = structuredClone(authored);
    invalid.animation.values!.random = { type: "expression", base: [0], value: [{ molang: expression }] };
    expect(() => evaluatePose(invalid, 0.75, evaluate)).toThrow(/shared value/);
  }
  for (const field of ["exponent", "time"]) {
    const invalid = structuredClone(authored);
    const curve = invalid.animation.tracks[field === "exponent" ? 2 : 3].driver;
    if (curve.type !== "curve") throw new Error("Expected the fixture curve.");
    if (field === "exponent") curve.segments[0].easing!.exponent = { molang: "-1" };
    else curve.segments[0].handles![0].in.time = { molang: "-1" };
    expect(() => evaluatePose(invalid, 0.75, evaluate)).toThrow(field === "exponent" ? /easing parameter/ : /time handles/);
  }
});
