import { expect, it } from "vitest";
import MolangParser from "molangjs/dist/molang.esm.js";
import type { ImportedProject } from "../domain/conversionSeed";
import type { AnimationJson } from "../format/animation";
import { evaluatePose } from "../domain/animationIRPose";
import { createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation } from "../export/projectExporter";
import { readInput } from "../import/formats";
import type { BedrockAnimation } from "../import/bedrockAnimation/bedrockAnimationSchema";
import type { PalAnimation } from "../import/emotecraft/emotecraftBinary";
import { importEmotecraftFile } from "../import/emotecraft/emotecraftImporter";
import palSource from "./fixtures/emotecraft-curves.json";

function bedrock(animations: Record<string, BedrockAnimation>) {
  return readInput("bedrock_animation_json", {
    name: "policy.animation.json",
    bytes: new TextEncoder().encode(JSON.stringify({ format_version: "1.8.0", animations })),
  });
}

async function exported(project: ImportedProject, index = 0): Promise<AnimationJson> {
  const [file] = await exportAnimation(createConversionDocument(project, "Player animation"), index);
  return JSON.parse(await file.blob.text());
}

it("keeps an explicit Bedrock duration and later interpolation keys while omitting unreachable events", async () => {
  const project = await bedrock({ test: {
    animation_length: 1, loop: true,
    bones: { root: { position: { "0": [0, 0, 0], "2": [16, 0, 0] } } },
    timeline: { "0.5": ["/say inside"], "1": ["/say boundary"], "3": ["/say outside"] },
    sound_effects: { "3": { effect: "minecraft:test" } },
    particle_effects: { "3": { effect: "minecraft:test" } },
  } });
  const animation = await exported(project);
  expect(animation.animation.duration).toBe("20t");
  expect(animation.animation.playback?.mode).toBe("loop");
  const curve = animation.animation.tracks.find((track) => track.target.node === "bone:root" && track.target.operation === "position")!.driver;
  expect(curve.type === "curve" && curve.keys.map((key) => key.time)).toEqual(["0t", "40t"]);
  const parser = new MolangParser();
  expect(evaluatePose(animation, 0.5, (value) => typeof value === "number" ? value : parser.parse(value.molang))["bone:root"].matrix.elements[12]).toBeCloseTo(-0.234375, 12);
  expect(animation.animation.events?.timeline?.map((event) => event.time)).toEqual(["10t", "20t"]);
  expect(project.diagnostics.filter((issue) => issue.code === "bedrock_event_outside_duration")).toHaveLength(3);
});

it("infers Bedrock duration from every event kind and preserves time-dependent expressions when timing exists", async () => {
  for (const property of ["timeline", "sound_effects", "particle_effects"] as const) {
    const project = await bedrock({ test: {
      blend_weight: "q.anim_time", bones: { root: { position: ["q.anim_time * 16", 0, 0] } },
      [property]: { "3": property === "timeline" ? ["/say end"] : { effect: "minecraft:test" } },
    } });
    const animation = await exported(project);
    expect(animation.animation.duration).toBe("60t");
    expect(animation.animation.values?.blend).toEqual({ type: "expression", base: [1], value: [{ molang: "q.anim_time" }] });
    expect(animation.animation.events?.timeline?.map((event) => event.time)).toEqual(["60t"]);
    expect(project.diagnostics.some((issue) => issue.code === "animation_skipped")).toBe(false);
  }
  const keyed = await exported(await bedrock({ test: { bones: { root: { rotation: { "0": [0, 0, 0], "2": [45, 0, 0] } } } } }));
  expect(keyed.animation.duration).toBe("40t");
});

it("requires timing equally for bone, blend and clock expressions and leaves static animations at one tick", async () => {
  const project = await bedrock({
    static: { bones: { root: { position: [16, 0, 0] } } },
    bone: { bones: { root: { position: ["q.anim_time * 16", 0, 0] } } },
    blend: { blend_weight: "q.anim_time", bones: { root: { position: [16, 0, 0] } } },
    clock: { anim_time_update: "query.anim_time + query.delta_time" },
    lifetime: { blend_weight: "query.life_time" },
    comment: { bones: { root: { position: ["16 /* q.anim_time */", 0, 0] } } },
  });
  expect(project.animations.map((animation) => animation.name)).toEqual(["static", "comment"]);
  expect((await exported(project)).animation.duration).toBe("1t");
  expect(project.diagnostics.filter((issue) => issue.code === "animation_skipped").map((issue) => issue.sourcePath)).toEqual([
    "animations.bone.animation_length", "animations.blend.animation_length", "animations.clock.animation_length", "animations.lifetime.animation_length",
  ]);
  await expect(bedrock({ only: { blend_weight: "q.anim_time" } })).rejects.toThrow("requires animation_length");
  const explicit = await exported(await bedrock({ test: { animation_length: 2, blend_weight: "q.anim_time", bones: { root: { position: [16, 0, 0] } } } }));
  expect(explicit.animation.duration).toBe("40t");
});

it("keeps the original PAL length and interpolation while omitting effects at or beyond the end", async () => {
  const source = structuredClone(palSource as unknown as PalAnimation);
  source.lengthTicks = 20;
  source.loop = "once";
  source.parents = {};
  source.applyBendToOtherBones = false;
  source.bones = { body: { rotation: [[], [], []], scale: [[], [], []], bend: [], position: [[{
    startTick: 0, endTick: 40, start: 0, end: 16, easing: "linear", easingArgs: [],
  }], [], []] } };
  source.effects = {
    sounds: [{ tick: 10, sound: "minecraft:test" }, { tick: 20, sound: "minecraft:test" }, { tick: 40, sound: "minecraft:test" }],
    particles: [{ tick: 40, effect: "minecraft:test", locator: "", script: "" }],
    instructions: [{ tick: 40, instruction: "/say outside" }],
  };
  const project = importEmotecraftFile({ animation: source, metadata: { badges: [] } }, "policy.emotecraft");
  const animation = await exported(project);
  expect(animation.animation.duration).toBe("20t");
  expect(animation.animation.events?.timeline?.map((event) => event.time)).toEqual(["10t"]);
  const parser = new MolangParser();
  expect(evaluatePose(animation, 0.5, (value, _u, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, { "query.value": resolveValue })).pal_body.matrix.elements[12]).toBeCloseTo(-0.234375, 12);
  expect(project.diagnostics.filter((issue) => issue.code === "emotecraft_event_outside_duration")).toHaveLength(4);
});

it("applies the same final tick limit to both formats without clamping their source duration", async () => {
  const source = structuredClone(palSource as unknown as PalAnimation);
  source.lengthTicks = 12000;
  expect((await exported(importEmotecraftFile({ animation: source, metadata: { badges: [] } }, "limit.emotecraft"))).animation.duration).toBe("12000t");
  const project = await bedrock({ limit: { animation_length: 600.01 }, tooLong: { animation_length: 600.03 } });
  expect(project.animations.map((animation) => animation.name)).toEqual(["limit"]);
  expect((await exported(project)).animation.duration).toBe("12000t");
  expect(project.diagnostics[0].message).toContain("12000 ticks");
  source.lengthTicks = 12001;
  expect(() => importEmotecraftFile({ animation: source, metadata: { badges: [] } }, "limit.emotecraft")).toThrow("12000 ticks");
  await expect(bedrock({ tooLong: { animation_length: 601 } })).rejects.toThrow("12000 ticks");
});

it("merges distinct channels of Bedrock aliases independently of their input order", async () => {
  const aliases = { rightArm: { rotation: [45, 0, 0] }, right_arm: { position: [16, 0, 0] } };
  const project = await bedrock({ test: { animation_length: 1, bones: aliases } });
  const animation = await exported(project);
  const reversed = await exported(await bedrock({ test: { animation_length: 1, bones: Object.fromEntries(Object.entries(aliases).reverse()) } }));
  expect(reversed).toEqual(animation);
  expect(animation.animation.tracks.filter((track) => track.target.node === "bone:right_arm").map((track) => track.target.operation)).toEqual(["position", "rotation"]);
  const parser = new MolangParser();
  const pose = evaluatePose(animation, 0.5, (value) => typeof value === "number" ? value : parser.parse(value.molang));
  expect(pose["bone:right_arm"].matrix.elements[12]).toBeCloseTo((5 - 16) / 16 * 0.9375, 12);
  expect(pose["bone:right_arm"].matrix.elements[6]).toBeCloseTo(-Math.SQRT1_2 * 0.9375, 12);
  expect(project.diagnostics).toEqual([]);
});

it("skips only ambiguous Bedrock animations instead of choosing an alias channel or relative rotation", async () => {
  const project = await bedrock({
    valid: { bones: { rightArm: { rotation: [45, 0, 0] }, right_arm: { relative_to: { rotation: "entity" }, position: [16, 0, 0] } } },
    duplicate: { bones: { rightArm: { rotation: [45, 0, 0] }, right_arm: { rotation: [0, 0, 0] } } },
    relative: { bones: { rightArm: { relative_to: { rotation: "entity" }, rotation: [45, 0, 0] }, right_arm: { relative_to: {}, position: [16, 0, 0] } } },
  });
  expect(project.animations.map((animation) => animation.name)).toEqual(["valid"]);
  expect((await exported(project)).nodes["bone:right_arm"].inherit).toEqual({ rotation: "entity" });
  expect(project.diagnostics.filter((issue) => issue.code === "animation_skipped").map((issue) => issue.sourcePath)).toEqual([
    "animations.duplicate.bones.right_arm.rotation", "animations.relative.bones.right_arm.relative_to",
  ]);
  await expect(bedrock({ only: { bones: { rightArm: { position: [1, 0, 0] }, right_arm: { position: [2, 0, 0] } } } })).rejects.toThrow("define position more than once");
});
