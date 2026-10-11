import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import MolangParser from "molangjs/dist/molang.esm.js";
import { expect, it } from "vitest";
import type { AnimationIR, ScalarIR } from "../domain/animationIR";
import { evaluatePose } from "../domain/animationIRPose";
import { createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation } from "../export/projectExporter";
import { parseMinecraftTime } from "../format/time";
import { readInput } from "../import/formats";

it("preserves the matrices of the 11 documentation samples after reference conversion", async () => {
  const converted = new Map<string, AnimationIR>();
  for (const [path, format, names] of [
    ["docs/reference/aj/emote.ajblueprint", "animated_java_blueprint", ["anvil"]],
    ["docs/reference/bbmodel/emote.bbmodel", "geckolib_bbmodel", ["clap", "cry", "indicate", "no", "yes"]],
    ["docs/reference/aj/sit.ajblueprint", "animated_java_blueprint", ["sit_down", "idle_sky", "idle_flower", "stand_up1", "stand_up2"]],
  ] as const) {
    const project = await readInput(format, { name: path.split("/").at(-1)!, bytes: await readFile(new URL(`../../../${path}`, import.meta.url)) });
    const document = createConversionDocument(project, format);
    for (const name of names) {
      const index = document.animations.findIndex((animation) => animation.sourceName === name);
      expect(index, `${path}: missing ${name}`).toBeGreaterThanOrEqual(0);
      const [output] = await exportAnimation(document, index);
      converted.set(name, JSON.parse(await output.blob.text()));
    }
  }

  const parser = new MolangParser();
  parser.variableHandler = (key) => { throw new Error(`Unbound test query: ${key}`); };
  for (const [name, actual] of converted) {
    const path = `docs/sample/${actual.id.startsWith("sit:") ? "sit/" : ""}${actual.id.replaceAll(":", ".")}.json`;
    const expected: AnimationIR = JSON.parse(await readFile(fileURLToPath(new URL(`../../../${path}`, import.meta.url)), "utf8"));
    const displayIds = (animation: AnimationIR) => Object.keys(animation.nodes).filter((id) => Object.keys(animation.nodes[id].attachments ?? {}).length > 0).sort();
    const ids = displayIds(expected);
    expect(ids.length, path).toBeGreaterThan(0);
    expect(displayIds(actual), path).toEqual(ids);
    const duration = parseMinecraftTime(expected.animation.duration);
    expect(parseMinecraftTime(actual.animation.duration), path).toBe(duration);
    const inputs = name === "indicate"
      ? [{ target_x_rotation: 0, target_y_rotation: 0, loop_count: 0 }, { target_x_rotation: 30, target_y_rotation: -45, loop_count: 1 }]
      : [{ target_x_rotation: 0, target_y_rotation: 0, loop_count: 0 }];
    for (const input of inputs) {
      for (let tick = 0; tick <= duration; tick++) {
        const evaluate = (scalar: ScalarIR, progress: number): number => {
          if (typeof scalar === "number") return scalar;
          const queries = { ...input, anim_time: tick / 20, anim_time_ticks: tick, delta_time: 1 / 20, key_frame_lerp_time: progress };
          const variables = Object.fromEntries(Object.entries(queries).flatMap(([key, value]) => [[`q.${key}`, value], [`query.${key}`, value]]));
          variables["global.key_frame_lerp_time"] = progress;
          const value = parser.parse(scalar.molang, variables);
          if (!Number.isFinite(value)) throw new Error(`${path}: non-finite expression ${scalar.molang}`);
          return value;
        };
        const expectedPose = evaluatePose(expected, tick / 20, evaluate);
        const actualPose = evaluatePose(actual, tick / 20, evaluate);
        for (const id of ids) {
          for (let component = 0; component < 16; component++) {
            const value = expectedPose[id].matrix.elements[component];
            const context = `${path}, queries=${JSON.stringify(input)}, ${tick}t, ${id}, matrix[${component}]`;
            expect(Math.abs(actualPose[id].matrix.elements[component] - value), context).toBeLessThanOrEqual(1e-5 + Math.abs(value) * 1e-5);
          }
        }
      }
    }
  }
}, 30_000);
