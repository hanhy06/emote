import { readFile } from "node:fs/promises";
import { expect, it } from "vitest";
import { combineConversionDocuments } from "../domain/conversionBatch";
import { createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation, exportAnimations } from "../export/projectExporter";
import type { AnimationJson } from "../format/animation";
import { readInput } from "../import/formats";

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
    programs: { initialize: "v.speed = 1; v.pause = 0.5;", update: "v.speed = q.is_sneaking ? 0.5 : 1;" },
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
      timeline: [{ time: "0.15s", direction: "both", source: { type: "node", node: "arm", attachment: "prop" }, origin: { type: "node", node: "arm", offset: [0, 0.5, 0] }, action: { type: "commands", commands: ["say first", "say second"] } }],
      loop: [{ source: { type: "player" }, origin: { type: "root" }, action: { type: "external", key: "example:loop", data: { payload: "keep" } } }],
      stop: [{ source: { type: "server" }, origin: { type: "root" }, action: { type: "commands", commands: ["say stop"] } }],
    },
  },
};

it("preserves authored v5 animation runtime data through import and export", async () => {
  const project = await readInput("emote_json", { name: "runtime.json", bytes: new TextEncoder().encode(JSON.stringify(animation)) });
  const [output] = await exportAnimation(createConversionDocument(project, "Emote JSON"), 0);
  expect(JSON.parse(await output.blob.text())).toEqual(animation);
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
