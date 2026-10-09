import { readFile } from "node:fs/promises";
import { Euler, Quaternion } from "three";
import { expect, it } from "vitest";
import { importEmotecraftFile } from "./emotecraftImporter";
import type { PalAnimation, PalKeyframe } from "./emotecraftBinary";
import { evaluatePoseIR } from "../../domain/animationIRPose";
import { MolangBakeEvaluator } from "../../preview/molangEvaluator";
import { requireAnimation } from "../../format/animation";
import { createConversionDocument } from "../../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../../compiler/animationCompiler";

it("preserves the upstream waving sample's separate yaw and roll timing without tick samples", async () => {
  const sample = JSON.parse(await readFile(new URL("./fixtures/waving.json", import.meta.url), "utf8"));
  const channel = (name: string): PalKeyframe[] => {
    const keys = sample.emote.moves.filter((move: any) => move.rightArm?.[name] !== undefined);
    return keys.map((move: any, index: number) => ({ startTick: index ? keys[index - 1].tick : 0, endTick: move.tick,
      start: (index ? keys[index - 1].rightArm[name] : move.rightArm[name]) * Math.PI / 180,
      end: move.rightArm[name] * Math.PI / 180, easing: "easeinoutsine", easingArgs: [] }));
  };
  const animation: PalAnimation = { uuid: sample.uuid, lengthTicks: sample.emote.stopTick, beginTick: sample.emote.beginTick, endTick: sample.emote.endTick,
    loop: "once", loopStartTick: 0, format: "player_animator", applyBendToOtherBones: false, easeBeforeKeyframe: true,
    bones: { right_arm: { position: [[], [], []], rotation: [[], channel("yaw"), channel("roll")], scale: [[], [], []], bend: [] } },
    effects: { sounds: [], particles: [], instructions: [] }, pivots: {}, parents: {} };
  const project = importEmotecraftFile({ animation, metadata: { name: "Waving", badges: [] } }, "waving.emotecraft");
  const ir = project.animations[0].ir!;
  requireAnimation({ type: "animation", schema_version: 5, ...ir });
  expect(ir.animation.tracks).toHaveLength(2);
  expect(Object.keys(ir.nodes)).not.toContain("pal_right_arm_x");
  const evaluator = new MolangBakeEvaluator({ error: { code: "sample", message: (value) => value } });
  for (const [tick, yaw, roll] of [[0, 0, 0], [15, 90, 95], [17.5, 90, 140], [47.5, 45, 92.5], [50, 0, 0]]) {
    const pose = evaluatePoseIR(ir, tick / 20, (value, progress) => evaluator.evaluate(typeof value === "number" ? value : value.molang, { animationTime: tick / 20, keyframeLerpTime: progress }, "waving"));
    const expected = new Quaternion().setFromEuler(new Euler(0, -yaw * Math.PI / 180, roll * Math.PI / 180, "ZYX"));
    expect(Math.abs(pose.pal_right_arm.orientation.dot(expected))).toBeCloseTo(1, 8);
  }
  const aj = JSON.parse(await readFile(new URL("../../../../docs/reference/aj/emote.ajblueprint", import.meta.url), "utf8"));
  const frames = Object.values(aj.animations.find((animation: any) => animation.name === "anvil").animators)
    .flatMap((animator: any) => animator.keyframes ?? []).filter((frame: any) => frame.channel === "function") as any[];
  const particle = frames.find((frame) => frame.data_points[0].function.startsWith("particle "));
  const sound = frames.find((frame) => frame.data_points[0].function.startsWith("playsound "));
  const effects: PalAnimation["effects"] = {
    sounds: [{ tick: sound.time * 20, sound: sound.data_points[0].function.split(" ")[1] }],
    particles: [{ tick: particle.time * 20, effect: particle.data_points[0].function.split(" ")[1], locator: "right_arm", script: particle.data_points[0].function }],
    instructions: [{ tick: particle.time * 20, instruction: particle.data_points[0].function }],
  };
  const converted = importEmotecraftFile({ animation: { ...animation, effects }, metadata: { name: "Waving", badges: [] }, song: new Uint8Array() }, "waving.emotecraft");
  const output = compileConversionAnimationArtifact(createConversionDocument(converted, "Emotecraft"), 0).animation;
  expect(output.schema_version).toBe(5);
  expect(output.animation.tracks).toEqual(ir.animation.tracks);
  expect(output.animation.events?.timeline).toEqual([
    { time: sound.time, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: [`playsound ${effects.sounds[0].sound} master @s ~ ~ ~`] } },
    { time: particle.time, source: { type: "server" }, origin: { type: "node", node: "pal_right_arm" }, action: { type: "commands", commands: [`particle ${effects.particles[0].effect} ~ ~ ~`] } },
    { time: particle.time, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: [particle.data_points[0].function] } },
  ]);
  expect(converted.diagnostics.map((issue) => issue.code)).toEqual(expect.arrayContaining(["emotecraft_particle_script_ignored", "emotecraft_instruction_approximated", "emotecraft_song_invalid"]));
  for (const issue of converted.diagnostics) expect(issue.message).not.toContain(particle.data_points[0].function);
});
