import { readInput } from "../import/formats";
import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { requireAnimationEvents, requireAnimation, serializeAnimation } from "../format/animation";
import { sampleCurveIR } from "../domain/animationIRCurves";
import { evaluatePoseIR } from "../domain/animationIRPose";
import { MolangBakeEvaluator } from "../preview/molangEvaluator";
import type { CurveIR } from "../domain/animationIR";
import { createConversionDocument, replaceDocumentAnimationTimelineEvents, updateDocumentAnimationLifecycleEvents } from "../domain/conversionDocument";
import { combineConversionDocuments } from "../domain/conversionBatch";
import { eventReviewLocations } from "../workspace";
import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";

const fixture = new URL("../../../docs/design/animation-v5.example.json", import.meta.url);
const load = async () => requireAnimation(JSON.parse(await readFile(fixture, "utf8")));

describe("Animation IR design example", () => {
  it.each([
    ["docs/reference/aj/emote.ajblueprint", "animated_java_blueprint", "function"],
    ["docs/reference/bbmodel/emote.bbmodel", "geckolib_bbmodel", "timeline"],
  ] as const)("preserves sub-tick event times and start delay in the existing %s sample", async (path, format, channel) => {
    const source = JSON.parse(await readFile(new URL(`../../../${path}`, import.meta.url), "utf8"));
    const animation = source.animations.find((a: any) => Object.values(a.animators).some((n: any) => n.keyframes?.filter((f: any) => f.channel === channel).length >= 2));
    source.animations = [animation];
    animation.start_delay = "0.2";
    const frames = Object.values(animation.animators).flatMap((n: any) => n.keyframes ?? []).filter((f: any) => f.channel === channel) as any[];
    frames[0].time = 0.01;
    frames[1].time = 0.02;
    let variantNodeId: string | undefined;
    if (channel === "function") {
      const node = Object.values(animation.animators).find((n: any) => n.type === "bone") as any;
      node.keyframes = [...(node.keyframes ?? []), { channel: "visibility", time: 0.01, data_points: [{ x: 0 }] }, { channel: "visibility", time: 0.02, data_points: [{ x: 1 }] }];
      const display = source.elements.find((element: any) => element.type === "animated_java:vanilla_block_display");
      variantNodeId = display.uuid;
      display.visibility = false;
      animation.animators[display.uuid] = { name: display.name, type: "bone", keyframes: [
        { channel: "visibility", time: 0.01, data_points: [{ x: 0 }] },
        { channel: "visibility", time: 0.02, data_points: [{ x: 1 }] },
      ] };
      display.configs = { default: { shadow_radius: 0.25 }, variants: { state_check: { shadow_radius: 0.5 } } };
      source.variants.list = [{ uuid: "state_check", name: "state_check", excluded_nodes: [], texture_map: {} }];
      animation.animators.effects = { ...animation.animators.effects, type: "effect", keyframes: [
        ...(animation.animators.effects?.keyframes ?? []), { channel: "variant", time: 0.01, data_points: [{ variant: "state_check" }] },
      ] };
    }
    const imported = await readInput(format, { name: path.split("/").at(-1)!, bytes: new TextEncoder().encode(JSON.stringify(source)) });
    const document = createConversionDocument(imported, "Sample");
    const actual = compileConversionAnimationArtifact(document, 0).animation;
    expect(actual.animation.playback!.start_delay).toBe(0.2);
    expect(actual.animation.events!.timeline!.slice(0, 2).map((event) => event.time)).toEqual([0.01, 0.02]);
    expect(eventReviewLocations(document)[0].locations.join(" · ")).toContain("frames: 0.2t, 0.4t");
    if (channel === "function") {
      const states = actual.animation.tracks.filter((t) => t.channel === "visible" && t.driver.type === "state");
      expect(states.length).toBeGreaterThan(0);
      expect(states.some((t) => t.driver.type === "state" && t.driver.keys.some((k) => k.time === 0.01 && k.value === false) && t.driver.keys.some((k) => k.time === 0.02 && k.value === true))).toBe(true);
      expect(states.find((track) => track.target.node === variantNodeId)?.driver).toEqual({ type: "state", keys: [
        { time: 0, value: false }, { time: 0.01, value: false }, { time: 0.02, value: true },
      ] });
      const nbt = actual.animation.tracks.find((track) => track.target.node === variantNodeId && track.channel === "nbt");
      expect(nbt?.driver).toEqual({ type: "state", keys: [
        { time: 0, value: { merge: "{shadow_radius:0.25}" } },
        { time: 0.01, value: { merge: "{shadow_radius:0.5}" } },
      ] });
    }
  });
  it("exports command edits and exact timeline times from the existing v5 example", async () => {
    const expected = await load();
    const imported = await readInput("emote_json", { name: "example.json", bytes: new Uint8Array(await readFile(fixture)) });
    const originalCallbacks = [{ name: "source_callback", payload: "source" }];
    imported.animations[0].ir!.callbacks = structuredClone(originalCallbacks);
    let document = createConversionDocument(imported, "Example");
    const originalDocument = document;
    expect(compileConversionAnimationArtifact(document, 0).animation.callbacks).toEqual(originalCallbacks);
    expect(document.animations[0].clip.events?.timeline).toEqual(expected.animation.events!.timeline);
    expect(document.animations[0].clip.events).toEqual(requireAnimationEvents(expected.animation.events, expected.animation.duration));
    const event = structuredClone(expected.animation.events!.timeline![0]);
    event.action = { type: "commands", commands: ["say schema5_edit_check"] };
    document = replaceDocumentAnimationTimelineEvents(document, 0, [event]);
    const { time: _time, direction: _direction, ...start } = event;
    const callbacks = [{ name: "edited_callback", payload: "edited" }];
    document = updateDocumentAnimationLifecycleEvents(document, 0, { callbacks, start: [start], loop: [], stop: [] });
    const actual = compileConversionAnimationArtifact(document, 0).animation;
    expect(document.animations[0].callbacks).toEqual(callbacks);
    expect(actual.callbacks).toEqual(callbacks);
    expect(actual.animation.events!.timeline).toEqual([event]);
    expect(actual.animation.events!.start).toEqual([start]);
    expect(eventReviewLocations(document)).toEqual([{ owner: `Animation ${document.animations[0].id}`, locations: ["start", `frames: ${event.time * 20}t`, "callbacks"] }]);
    expect(originalDocument.animations[0].clip.events?.timeline).toEqual(expected.animation.events!.timeline);
    expect(originalDocument.animations[0].callbacks).toEqual(originalCallbacks);
    callbacks[0].payload = "changed outside the document";
    expect(document.animations[0].callbacks![0].payload).toBe("edited");
    document = replaceDocumentAnimationTimelineEvents(document, 0, []);
    document = updateDocumentAnimationLifecycleEvents(document, 0, { callbacks: [], start: [], loop: [], stop: [] });
    expect(compileConversionAnimationArtifact(document, 0).animation.animation.events!.timeline).toEqual([]);
    expect(compileConversionAnimationArtifact(document, 0).animation.callbacks ?? []).toEqual([]);
    expect(eventReviewLocations(document)).toEqual([]);
    const external = { ...event, action: { type: "external" as const, key: "example:source_event", data: { retained: true } } };
    document = replaceDocumentAnimationTimelineEvents(document, 0, [external]);
    expect(eventReviewLocations(document)).toEqual([]);
    expect(compileConversionAnimationArtifact(document, 0).animation.animation.events!.timeline).toEqual([external]);
    document = updateDocumentAnimationLifecycleEvents(document, 0, { callbacks: originalCallbacks, start: [], loop: [], stop: [] });
    document = { ...document, sequence: { ...document.sequence, callbacks: originalCallbacks } };
    expect(eventReviewLocations(document)).toEqual([
      { owner: `Animation ${document.animations[0].id}`, locations: ["callbacks"] },
      { owner: `Sequence ${document.sequence.displayName}`, locations: ["callbacks"] },
    ]);
    document = replaceDocumentAnimationTimelineEvents(document, 0, [{ ...event, time: 0.02 }, { ...event, time: 0.01 }, { ...event, time: 0.01 }]);
    document = updateDocumentAnimationLifecycleEvents(document, 0, { callbacks: originalCallbacks, start: [start], loop: [start], stop: [start] });
    expect(eventReviewLocations(document)).toEqual([
      { owner: `Animation ${document.animations[0].id}`, locations: ["start", "frames: 0.2t, 0.4t", "loop", "stop", "callbacks"] },
      { owner: `Sequence ${document.sequence.displayName}`, locations: ["callbacks"] },
    ]);
    expect(JSON.stringify(eventReviewLocations(document))).not.toContain("schema5_edit_check");
    expect(JSON.stringify(eventReviewLocations(document))).not.toContain("source_callback");
    expect(eventReviewLocations(null)).toEqual([]);
  });

  it("preserves the existing v5 and AJ events in both mixed input orders", async () => {
    const v5 = createConversionDocument(await readInput("emote_json", { name: "example.json", bytes: new Uint8Array(await readFile(fixture)) }), "Example");
    const aj = createConversionDocument(await readInput("animated_java_blueprint", { name: "emote.ajblueprint", bytes: new Uint8Array(await readFile(new URL("../../../docs/reference/aj/emote.ajblueprint", import.meta.url))) }), "AJ");
    for (const inputs of [[v5, aj], [aj, v5]]) {
      const document = combineConversionDocuments(inputs);
      for (let index = 0; index < document.animations.length; index++) {
        const output = compileConversionAnimationArtifact(document, index).animation;
        expect(output.animation.events).toEqual(document.animations[index].clip.events);
        for (const phase of Object.values(document.animations[index].clip.events ?? {})) for (const event of phase ?? []) {
          if (event.source.type === "node") expect(output.nodes[event.source.node]).toBeDefined();
          if (event.origin.type === "node") expect(output.nodes[event.origin.node]).toBeDefined();
        }
      }
      expect(document.animations.find((a) => a.sourceReferenceId === "example:ir_design")!.clip.events?.timeline).toHaveLength(1);
      expect(document.animations.find((a) => a.sourceName === "anvil")!.clip.events?.timeline).toHaveLength(2);
    }
  });
  it.each([
    ["docs/reference/aj/sit.ajblueprint", "animated_java_blueprint"],
    ["docs/reference/aj/music.ajblueprint", "animated_java_blueprint"],
    ["docs/reference/bbmodel/hug.bbmodel", "geckolib_bbmodel"],
    ["docs/reference/bbmodel/handshake.bbmodel", "geckolib_bbmodel"],
  ] as const)("exports every existing animation in %s as valid v5", async (path, format) => {
    const bytes = new Uint8Array(await readFile(new URL(`../../../${path}`, import.meta.url)));
    const imported = await readInput(format, { name: path.split("/").at(-1)!, bytes });
    const document = createConversionDocument(imported, "Sample");
    expect(document.animations.length).toBeGreaterThan(0);
    for (let index = 0; index < document.animations.length; index++) {
      const actual = compileConversionAnimationArtifact(document, index).animation;
      expect(() => requireAnimation(JSON.parse(serializeAnimation(actual)))).not.toThrow();
      expect(actual.animation.tracks).toEqual(document.animations[index].clip.tracks);
    }
  }, 60_000);
  it("imports and exports schema 5 without reducing curves or external nodes", async () => {
    const expected = await load();
    const imported = await readInput("emote_json", { name: "example.json", bytes: new Uint8Array(await readFile(fixture)) });
    const document = createConversionDocument(imported, "Example");
    const actual = compileConversionAnimationArtifact(document, 0).animation;
    expect(actual.animation.tracks).toEqual(expected.animation.tracks);
    expect(actual.animation.events).toEqual(requireAnimationEvents(expected.animation.events, expected.animation.duration));
    expect(actual.nodes).toEqual(expected.nodes);
    expect(actual.animation.clock).toEqual(expected.animation.clock);
  });

  it("preserves the original Blockbench key times and expressions in the existing model", async () => {
    const bytes = new Uint8Array(await readFile(new URL("../../../docs/reference/bbmodel/emote.bbmodel", import.meta.url)));
    const imported = await readInput("geckolib_bbmodel", { name: "emote.bbmodel", bytes });
    const document = createConversionDocument(imported, "GeckoLib");
    const index = document.animations.findIndex((a) => a.sourceName === "yes");
    expect(index).toBeGreaterThanOrEqual(0);
    const actual = compileConversionAnimationArtifact(document, index).animation;
    expect(JSON.stringify(actual.animation.tracks)).toContain("math.sin(q.anim_time * 120)");
    expect(Object.values(actual.nodes).some((n) => n.transform?.some((op) => op.op === "rotate_euler" && op.order === "ZYX"))).toBe(true);
    expect(actual.schema_version).toBe(5);
  });

  it("exports the existing Animated Java model through logical IR groups", async () => {
    const bytes = new Uint8Array(await readFile(new URL("../../../docs/reference/aj/emote.ajblueprint", import.meta.url)));
    const imported = await readInput("animated_java_blueprint", { name: "emote.ajblueprint", bytes });
    const document = createConversionDocument(imported, "Animated Java");
    for (let index = 0; index < document.animations.length; index++) {
      const actual = compileConversionAnimationArtifact(document, index).animation;
      expect(actual.schema_version).toBe(5);
      expect(actual.animation.duration).toBeGreaterThan(0);
      expect(Object.keys(actual.nodes).some((id) => id.startsWith("group:"))).toBe(true);
    }
  });
  it("round trips all expressions, sub-tick keys and logical attachments", async () => {
    const input = await load();
    const output = requireAnimation(JSON.parse(serializeAnimation(input)));
    expect(output).toEqual(input);
    expect(output.animation.tracks[1].driver).toEqual(input.animation.tracks[1].driver);
    expect(output.nodes.n2.attachments).toBeUndefined();
  });

  it("preserves the two-turn Euler curve and inherited group visibility", async () => {
    const animation = await load();
    const evaluator = new MolangBakeEvaluator({ rejectNondeterministic: true, error: { code: "example", message: (expression) => expression } });
    const evaluate = (time: number) => (value: number | { molang: string }, progress: number) => evaluator.evaluate(typeof value === "number" ? value : value.molang,
      { animationTime: time, keyframeLerpTime: progress }, "example");
    const spin = animation.animation.tracks[0].driver as CurveIR;
    expect(sampleCurveIR(spin, 0.5, [0, 0, 0], evaluate(0.5))).toEqual([0, 90, 0]);
    expect(sampleCurveIR(spin, 1, [0, 0, 0], evaluate(1))).toEqual([0, 360, 0]);
    expect(sampleCurveIR(spin, 2, [0, 0, 0], evaluate(2))).toEqual([0, 720, 0]);
    const pose = evaluatePoseIR(animation, 1.95, evaluate(1.95));
    expect(pose.n1.attachments.prop).toBe(false);
    expect(pose.n2.visible).toBe(false);
    expect(pose.n3.visible).toBe(false);
  });

  it("rejects hierarchy cycles and colliding key times without deleting keys", async () => {
    const animation = await load();
    animation.nodes.root.parent = "n2";
    expect(() => requireAnimation(animation)).toThrow("cycle");
    delete animation.nodes.root.parent;
    const position = animation.animation.tracks[1].driver as CurveIR;
    position.keys[1].time = position.keys[0].time;
    expect(() => requireAnimation(animation)).toThrow("Key times");
    expect(position.keys).toHaveLength(3);
  });
});
