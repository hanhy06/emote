import { readFile } from "node:fs/promises";
import MolangParser from "molangjs/dist/molang.esm.js";
import { expect, it } from "vitest";
import type { AnimationIR } from "../domain/animationIR";
import { evaluatePose } from "../domain/animationIRPose";
import { createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation } from "../export/projectExporter";
import type { PalAnimation } from "../import/emotecraft/emotecraftBinary";
import { importEmotecraftFile } from "../import/emotecraft/emotecraftImporter";
import loopSource from "./fixtures/emotecraft-loop-source.json";

async function exported(source: PalAnimation, name: string): Promise<AnimationIR> {
  const project = importEmotecraftFile({ animation: source, metadata: { name, badges: [] } }, `${name}.emotecraft`);
  const [file] = await exportAnimation(createConversionDocument(project, "Emotecraft"), 0);
  const animation = JSON.parse(await file.blob.text());
  expect(animation).toEqual(JSON.parse(await readFile(new URL(`../../../src/test/resources/${name}.json`, import.meta.url), "utf8")));
  return animation;
}

function pose(animation: AnimationIR, tick: number, loop = 0) {
  const parser = new MolangParser();
  return evaluatePose(animation, tick / 20, (value, progress, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
    "query.anim_time": tick / 20, "query.anim_time_ticks": tick, "query.loop_count": loop,
    "query.key_frame_lerp_time": progress, "query.value": resolveValue,
  }));
}

it("preserves the PAL seam on both sides of the wrap and its easing selection", async () => {
  const source = structuredClone(loopSource as unknown as PalAnimation);
  const animation = await exported(source, "emotecraft-loop");
  for (const [tick, loop, x] of [[5, 0, 0], [30, 0, -0.625], [39, 0, -0.34375], [0, 1, -0.3125], [5, 1, -0.15625], [10, 1, 0]]) {
    expect(pose(animation, tick, loop).pal_body.matrix.elements[12]).toBeCloseTo(x, 10);
  }
  expect(animation.animation.values!.pal_body_position_x_loop_start.time_offset).toBe("40t");
  source.easeBeforeKeyframe = false;
  const after = await exported(source, "emotecraft-loop-after");
  expect(pose(after, 30).pal_body.matrix.elements[12]).toBeCloseTo(-5 / 6, 10);
  expect(pose(after, 0, 1).pal_body.matrix.elements[12]).toBeCloseTo(-25 / 48, 10);
  source.easeBeforeKeyframe = true;
  source.loopStartTick = 5;
  const returned = await exported(source, "emotecraft-loop-return");
  expect(pose(returned, 30).pal_body.matrix.elements[12]).toBeCloseTo(-0.5625, 10);
  expect(pose(returned, 5, 1).pal_body.matrix.elements[12]).toBeCloseTo(-0.1875, 10);
  expect(returned.animation.playback?.loop_start).toBe("5t");
});

it("keeps GeckoLib hold behavior and samples dynamic seam endpoints with the wrapped progress", async () => {
  const source = structuredClone(loopSource as unknown as PalAnimation);
  source.format = "geckolib";
  const gecko = await exported(source, "emotecraft-loop-gecko");
  expect(pose(gecko, 30).pal_body.matrix.elements[12]).toBeCloseTo(-0.9375, 10);
  expect(pose(gecko, 5, 1).pal_body.matrix.elements[12]).toBe(0);
  source.format = "player_animator";
  source.bones.body.position[0][1].end = "16 + global.key_frame_lerp_time * 8";
  const dynamic = await exported(source, "emotecraft-loop-dynamic");
  expect(pose(dynamic, 30).pal_body.matrix.elements[12]).toBeCloseTo(-35 / 48, 10);
  expect(pose(dynamic, 0, 1).pal_body.matrix.elements[12]).toBeCloseTo(-5 / 12, 10);
});
