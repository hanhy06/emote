import { createTestDocument, importTestFileBatch, TEST_MINECRAFT_VERSION } from "./testProject";
import { expect, it } from "vitest";
import { skinPartOrders } from "../domain/conversionDocument";
import { evaluatePose } from "../domain/animationIRPose";
import { exportAnimation, exportAnimations } from "../export/projectExporter";
import { createDefaultPlayerBehavior } from "../domain/emoteDefinition";
import type { AnimationJson } from "../format/animation";
import { detectInputFormat, readInput } from "../import/formats";

const legacy = {
  type: "animation", schema_version: 4, id: "example:legacy", target_minecraft_version: TEST_MINECRAFT_VERSION,
  metadata: { name: "Legacy", description: "Migration fixture", author: "Author" },
  callbacks: [{ name: "example:ready", payload: "original" }],
  settings: { standalone: false, cooldown: "0.25s", rotation_deadzone: 25, display_interpolation: "2t", playback: { mode: "loop", loop_start: "2t" } },
  molang: { initialize: "v.size = 1;", tick: "v.size = q.is_sneaking ? 0.5 : 1;" },
  nodes: {
    root: { type: "anchor", transform: { position: [0, 1, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } },
    prop: { type: "item_display", parent: "root", visible: false, item_stack_snbt: '{id:"minecraft:paper",count:1}', item_display: "fixed",
      entity_nbt: "{glowing:1b}", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } },
    arm: { type: "item_display", item_stack_snbt: '{id:"minecraft:player_head",count:1}', skin: { part: "left_arm", order: 9 } },
    lower: { type: "item_display", item_stack_snbt: '{id:"minecraft:player_head",count:1}', skin: { part: "left_arm", order: 15 } },
    duplicate: { type: "item_display", item_stack_snbt: '{id:"minecraft:player_head",count:1}', skin: { part: "left_arm", order: 9 } },
  },
  timeline: { duration: "2s", tracks: {
    prop: {
      position: [{ time: "0t", value: [0, 0, 0] }, { time: "1s", pre: [2, 0, 0], post: [4, 0, 0] }],
      rotation: [{ time: "0t", value: [0, 0, 0] }, { time: "1s", value: [0, 0, 90] }],
      visible: [{ time: "5t", value: "q.is_sneaking" }, { time: "1s", value: true }],
      nbt: [{ time: "0t", value: "{glowing:0b}" }, { time: "1s", value: { molang: "v.patch" } }],
    },
    root: { scale: [{ time: "0t", value: ["v.size", 1, 1], easing: "ease_in_quad" }, { time: "1s", value: [1, 1, 1] }] },
  }, events: {
    start: [{ source: { type: "player" }, origin: { type: "root" }, commands: ["say original start"] }],
    timeline: [{ time: "0.75s", source: { type: "node", node: "prop" }, origin: { type: "node", node: "root", offset: [0, 1, 0] }, commands: ["say first", "say second"] }],
    loop: [{ source: { type: "server" }, origin: { type: "root" }, commands: ["say loop"] }],
    stop: [{ source: { type: "server" }, origin: { type: "root" }, commands: ["say stop"] }],
  } },
};

it("imports schema 4 into editable IR and exports schema 5 with transforms, skin, state and events", async () => {
  const input = { name: "legacy.json", bytes: new TextEncoder().encode(JSON.stringify(legacy)) };
  expect(await detectInputFormat(input)).toBe("emote_json");
  const project = await readInput("emote_json", input);
  const document = createTestDocument(project, "Emote JSON");
  expect(skinPartOrders(document)).toEqual({ '["arm","display"]': 9, '["lower","display"]': 15, '["duplicate","display"]': 9 });
  expect(document.nodes.arm.attachments!.display).toMatchObject({ type: "player_skin", part: "left_arm", region: { from: 0, to: 0.5 } });
  expect(document.nodes.duplicate.attachments!.display).toEqual(document.nodes.arm.attachments!.display);
  expect(document.nodes.lower.attachments!.display).toMatchObject({ region: { from: 0.5, to: 1 } });
  const [output] = await exportAnimation(document, 0);
  const actual: AnimationJson = JSON.parse(await output.blob.text());
  expect(actual).toMatchObject({ type: "animation", schema_version: 5, id: legacy.id, metadata: legacy.metadata, callbacks: legacy.callbacks,
    settings: { standalone: false, cooldown: "0.25s", rotation_deadzone: 25 },
    animation: { duration: "2s", playback: legacy.settings.playback, molang: legacy.molang } });
  expect(actual).not.toHaveProperty("timeline");
  expect(actual.settings).not.toHaveProperty("playback");
  expect(actual.settings).not.toHaveProperty("display_interpolation");
  expect(actual.nodes.prop).toMatchObject({ parent: "root", attachments: { display: { type: "item_display", visible: false, entity_nbt: "{glowing:1b}" } } });
  expect(actual.animation.tracks.find((track) => track.channel === "nbt")!.driver).toMatchObject({ keys: [
    { time: "0t", value: { merge: "{glowing:0b}" } }, { time: "1s", value: { merge: { molang: "v.patch" } } },
  ] });
  expect(actual.animation.tracks.find((track) => track.channel === "value" && track.target.node === "root")!.driver).toMatchObject({
    keys: [{ time: "0t", value: [{ molang: "v.size" }, 1, 1] }, { time: "1s", value: [1, 1, 1] }],
    segments: [{ interpolation: "linear", easing: { kernel: "power", exponent: 2, direction: "in" } }],
  });
  expect(actual.animation.events!.timeline![0]).toEqual({ time: "0.75s", source: { type: "node", node: "prop", attachment: "display" },
    origin: legacy.timeline.events.timeline[0].origin, action: { type: "commands", commands: ["say first", "say second"] } });
  for (const phase of ["start", "loop", "stop"] as const) expect(actual.animation.events![phase]![0].action).toEqual({ type: "commands", commands: legacy.timeline.events[phase][0].commands });
  const half = evaluatePose(actual, 0.5, (value) => typeof value === "number" ? value : 1).prop.matrix.elements;
  expect(half[12]).toBeCloseTo(1);
  expect(half[13]).toBeCloseTo(1);
  expect(half[0]).toBeCloseTo(Math.SQRT1_2);
  expect(half[1]).toBeCloseTo(Math.SQRT1_2);
  expect(evaluatePose(actual, 1, (value) => typeof value === "number" ? value : 1).prop.matrix.elements[12]).toBeCloseTo(4);
  const reopened = await readInput("emote_json", { name: "migrated.json", bytes: new TextEncoder().encode(JSON.stringify(actual)) });
  const [reexported] = await exportAnimation(createTestDocument(reopened, "Emote JSON"), 0);
  expect(JSON.parse(await reexported.blob.text())).toEqual(actual);
});

it("keeps Molang Euler rotation and skips unsupported fields without losing the animation", async () => {
  const source = {
    ...legacy, nodes: { ...legacy.nodes, unknown: { type: "custom_display", parent: "root" } },
    timeline: { ...legacy.timeline, tracks: { ...legacy.timeline.tracks,
      prop: { ...legacy.timeline.tracks.prop, rotation: [{ time: "5t", value: [0, "q.anim_time * 90", 0], easing: "unknown" }, { time: "1s", value: [0, 90, 0] }], unsupported: [{ time: "0t" }] },
      missing: { position: [{ time: "0t", value: [0, 0, 0] }] },
    }, events: { ...legacy.timeline.events, start: [
      ...legacy.timeline.events.start,
      { source: { type: "node", node: "unknown" }, origin: { type: "root" }, commands: ["say skipped"] },
    ] } },
  };
  const project = await readInput("emote_json", { name: "partial.json", bytes: new TextEncoder().encode(JSON.stringify(source)) });
  const [output] = await exportAnimation(createTestDocument(project, "Emote JSON"), 0);
  const actual: AnimationJson = JSON.parse(await output.blob.text());
  expect(actual.nodes.prop.transform![1]).toMatchObject({ op: "rotate_euler", order: "XYZ" });
  expect(actual.nodes.unknown).toMatchObject({ parent: "root" });
  const rotation = actual.animation.tracks.find((track) => track.channel === "value" && track.target.node === "prop" && track.target.operation === "rotation")!;
  expect(rotation.driver).toMatchObject({ keys: [{ time: "0t", value: [0, { molang: "q.anim_time * 90" }, 0] },
    { time: "5t", value: [0, { molang: "q.anim_time * 90" }, 0] }, { time: "1s", value: [0, 90, 0] }],
    segments: [{ interpolation: "step" }, { interpolation: "linear" }] });
  expect(actual.animation.events!.start).toHaveLength(1);
  expect(project.diagnostics.map((issue) => issue.sourcePath)).toEqual(expect.arrayContaining([
    "timeline.tracks.prop.rotation", "timeline.tracks.prop.unsupported", "timeline.tracks.missing", "timeline.events.start[1]", "nodes.unknown",
  ]));
});

it("imports a schema 4 sequence with animations and exports its steps and references as schema 5", async () => {
  const sequence = {
    type: "sequence", schema_version: 4, id: "example:sequence", target_minecraft_version: TEST_MINECRAFT_VERSION,
    metadata: { name: "Sequence", description: "Keep steps", author: "Author" },
    settings: { cooldown: "0.5s", player: createDefaultPlayerBehavior() }, callbacks: [{ name: "example:sequence" }],
    steps: [
      { emote: "example:legacy", repeat: 2, transition: "3t" }, { wait: "0.25s" },
      { emote: ["example:legacy", 25, "example:unresolved", 75] }, { emote: "emote:empty" },
    ],
  };
  const input = { name: "sequence.json", bytes: new TextEncoder().encode(JSON.stringify(sequence)) };
  expect(await detectInputFormat(input)).toBe("emote_sequence");
  const document = await importTestFileBatch([legacy, sequence].map((value, index) => ({ name: `${index}.json`,
    arrayBuffer: async () => new TextEncoder().encode(JSON.stringify(value)).buffer })));
  const outputs = await exportAnimations(document, true);
  const actual = JSON.parse(await outputs.at(-1)!.blob.text());
  expect(actual).toEqual({ ...sequence, schema_version: 5 });
  const animation: AnimationJson = JSON.parse(await outputs[0].blob.text());
  expect(animation).toMatchObject({ id: "example:legacy", schema_version: 5 });
  const reopened = await readInput("emote_sequence", { name: "sequence.json", bytes: new TextEncoder().encode(JSON.stringify(actual)) });
  expect(reopened.steps).toEqual(document.sequence.steps);
});
