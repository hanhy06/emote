import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, it } from "vitest";
import type { EmoteAnimation } from "../format/emoteAnimation";
import { requireEmoteAnimation } from "../format/emoteAnimationRuntime";
import type { ImportedProject } from "../domain/conversionSeed";
import { animatedJavaBlueprintAdapter } from "../import/animatedJava/animatedJavaBlueprintAdapter";
import type { ImportAdapter } from "../import/adapter";
import { geckoLibBbmodelAdapter } from "../import/geckoLib/geckoLibBbmodelAdapter";
import { removeRedundantKeyframes } from "../format/keyframeCleanup";
import { bakeSchema4Preview } from "../import/emoteJson/schema4PreviewBaker";
import { emoteJsonAdapter } from "../import/emoteJson/emoteJsonAdapter";
import { sequenceJsonAdapter } from "../import/emoteJson/sequenceJsonAdapter";
import { createConversionDocument } from "../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import { emoteFileName, createDocumentAnimationDownload, createDocumentAnimationBundleDownload } from "../export/projectExporter";
import { compileImportedProject } from "./compileImportedFixture";

const REPOSITORY_ROOT = fileURLToPath(new URL("../../../", import.meta.url));
const DIRECT_SAMPLES = ["anvil", "clap", "cry", "indicate", "no", "yes"];
const SIT_MATRIX_SAMPLES = ["sit_down", "idle_sky", "idle_flower", "stand_up1", "stand_up2"];
let directAnimations: Map<string, EmoteAnimation>;
let sitAnimations: Map<string, EmoteAnimation>;

describe("documentation sample conversion", () => {
  beforeAll(async () => {
    const [animatedJavaProject, geckoLibProject, sitProject] = await Promise.all([
      importFixture("docs/reference/aj/emote.ajblueprint", animatedJavaBlueprintAdapter),
      importFixture("docs/reference/bbmodel/emote.bbmodel", geckoLibBbmodelAdapter),
      importFixture("docs/reference/aj/sit.ajblueprint", animatedJavaBlueprintAdapter),
    ]);
    const direct = [
      ...compileImportedProject(animatedJavaProject, {}),
      ...compileImportedProject(geckoLibProject, { rotationDeadzoneByAnimation: { cry: 0 } }),
    ];

    directAnimations = new Map(direct.map((animation) => [animation.metadata.name, animation]));
    sitAnimations = new Map(compileImportedProject(sitProject, { standalone: false }).map((animation) => [animation.metadata.name, animation]));
  });

  it.each(DIRECT_SAMPLES)("matches the existing %s sample", async (name) => {
    const actual = requireAnimation(directAnimations, name);
    expect(() => requireEmoteAnimation(actual)).not.toThrow();
    const expected = await readJson(`docs/sample/${emoteFileName(actual.id)}`) as EmoteAnimation;


    const preservedTimeExpressions = name === "no" || name === "yes";
    if (preservedTimeExpressions) {
      expect(JSON.stringify(actual.timeline.tracks)).toContain("math.sin(q.anim_time * 120)");
    }
    expectMatchingMatrices(removeRedundantKeyframes(actual), expected, preservedTimeExpressions ? ["left_arm", "right_arm"] : []);
  });

  it.each(SIT_MATRIX_SAMPLES)("matches the existing %s sample", async (name) => {
    const actual = requireAnimation(sitAnimations, name);
    expect(() => requireEmoteAnimation(actual)).not.toThrow();
    const expected = await readJson(`docs/sample/sit/${emoteFileName(actual.id)}`) as EmoteAnimation;


    expectMatchingMatrices(removeRedundantKeyframes(actual), expected);
  });
});

describe("lifecycle callback sample JSON round trips", () => {
  it.each([
    "docs/sample/emote.bat.json",
    "docs/sample/sit/sit.idle_butterfly.json",
    "docs/sample/music/music.trumpet_can_can.json",
  ])("preserves callbacks and command events in %s", async (path) => {
    const expected = await readJson(path) as EmoteAnimation;
    const imported = await importFixture(path, emoteJsonAdapter);
    const [actual] = compileImportedProject(imported, {});
    expect(actual.id).toBe(expected.id);
    expect(actual.callbacks).toEqual(expected.callbacks);
    for (const phase of ["start", "timeline", "loop", "stop"] as const) {
      expect(actual.timeline.events?.[phase] ?? []).toEqual(expected.timeline.events?.[phase] ?? []);
    }
  });
});

describe("sample export identity", () => {
  it("uses the same collision-free filenames for single and bundle exports", async () => {
    const imported = await importFixture("docs/sample/emote.indicate.json", emoteJsonAdapter);
    const document = createConversionDocument(imported, "Sample");
    const original = document.animations[0];
    document.animations = ["music/song", "music.song", "music.song.1"].map((id) => ({
      ...original,
      runtime: { ...original.runtime, id },
      output: { ...original.output, namespace: "emote" },
    }));
    document.sequence = { ...document.sequence, namespace: "emote", idPath: "music/song" };
    const bundle = await createDocumentAnimationBundleDownload(document, true);
    expect(bundle.map((file) => file.fileName)).toEqual([
      "emote.music.song.json", "emote.music.song.2.json", "emote.music.song.1.json", "emote.music.song.1.1.json",
    ]);
    for (let index = 0; index < document.animations.length; index++) {
      const [single] = await createDocumentAnimationDownload(document, index);
      expect(single.fileName).toBe(bundle[index].fileName);
      const actual = JSON.parse(await single.blob.text()) as EmoteAnimation;
      expect(actual.id).toBe(`emote:${document.animations[index].runtime.id}`);
      expectMatchingMatrices(actual, await readJson("docs/sample/emote.indicate.json") as EmoteAnimation);
    }
    const overridden = compileConversionAnimationArtifact(document, 0, { namespace: "Custom Namespace" }).animation;
    expect(overridden.id).toBe("custom_namespace:music/song");
    const sequence = JSON.parse(await bundle[3].blob.text());
    expect(sequence.id).toBe("emote:music/song.1");
    expect(sequence.steps.map((step: { emote: string }) => step.emote)).toEqual([
      "emote:music/song", "emote:music.song", "emote:music.song.1",
    ]);
  });
});

describe("legacy loop end sample JSON round trips", () => {
  it.each(["1t", "not a time"])("ignores loop_end %s without changing the sample timeline", async (loopEnd) => {
    const path = "docs/sample/emote.indicate.json";
    const expected = await readJson(path) as EmoteAnimation;
    const input = structuredClone(expected);
    Object.assign(input.settings.playback, { loop_end: loopEnd });
    const imported = await emoteJsonAdapter.import({ name: "emote.indicate.json", bytes: new TextEncoder().encode(JSON.stringify(input)) });
    const [actual] = compileImportedProject(imported, {});

    expect(actual.settings.playback).not.toHaveProperty("loop_end");
    expect(actual.settings.playback.mode).toBe(expected.settings.playback.mode);
    expect(actual.timeline.duration).toBe(expected.timeline.duration);
    expectMatchingMatrices(actual, expected);
  });
});

describe("current schema JSON samples", () => {
  it.each([1, 3])("rejects animation schema %s instead of migrating it", async (schemaVersion) => {
    const sample = await readJson("docs/sample/emote.bat.json") as EmoteAnimation;
    const input = { name: "emote.bat.json", bytes: new TextEncoder().encode(JSON.stringify({ ...sample, schema_version: schemaVersion })) };

    expect((await emoteJsonAdapter.probe(input)).confidence).toBe(0);
    await expect(emoteJsonAdapter.import(input)).rejects.toThrow("schema_version must be 4");
  });

  it("accepts the current sequence sample and rejects its old schema", async () => {
    const sample = await readJson("docs/reference/sequence.json") as Record<string, unknown>;
    const current = { name: "sequence.json", bytes: new TextEncoder().encode(JSON.stringify(sample)) };
    expect((await sequenceJsonAdapter.probe(current)).confidence).toBe(100);
    expect((await sequenceJsonAdapter.import(current)).id).toBe(sample.id);

    const legacy = { name: "sequence.json", bytes: new TextEncoder().encode(JSON.stringify({ ...sample, schema_version: 1 })) };
    expect((await sequenceJsonAdapter.probe(legacy)).confidence).toBe(0);
    await expect(sequenceJsonAdapter.import(legacy)).rejects.toThrow("Unsupported sequence schema: 1");
  });
});

function expectMatchingMatrices(actual: EmoteAnimation, expected: EmoteAnimation, preservedExpressionNodes: readonly string[] = []): void {
  const actualTracks = bakeSchema4Preview(actual, { approximateRuntime: true });
  const expectedTracks = bakeSchema4Preview(expected, { approximateRuntime: true });
  const displayNodeIds = (animation: EmoteAnimation) => Object.entries(animation.nodes)
    .filter(([, node]) => node.type !== "anchor")
    .map(([id]) => id).sort();

  expect(displayNodeIds(actual)).toEqual(displayNodeIds(expected));
  for (const id of displayNodeIds(expected)) {
    if (preservedExpressionNodes.some((nodeId) => id === nodeId || id.startsWith(`${nodeId}_`))) continue;
    const actualFrames = actualTracks[id]?.transforms;
    const expectedFrames = expectedTracks[id]?.transforms;
    expect(actualFrames, `${id} display node must exist`).toBeDefined();
    expect(actualFrames!.length, `${id} must cover the reference animation`).toBeGreaterThanOrEqual(expectedFrames.length);

    for (const [tick, expectedFrame] of expectedFrames.entries()) {
      const actualMatrix = actualFrames![tick].matrix;
      for (let component = 0; component < expectedFrame.matrix.length; component++) {
        expect(actualMatrix[component], `${id} at ${tick}t, matrix[${component}]`).toBeCloseTo(expectedFrame.matrix[component], 4);
      }
    }
  }
}

function requireAnimation(animations: ReadonlyMap<string, EmoteAnimation>, name: string): EmoteAnimation {
  const animation = animations.get(name);
  if (!animation) throw new Error(`${name} must exist in a reference project`);
  return animation;
}

async function importFixture(path: string, adapter: ImportAdapter<ImportedProject>) {
  return adapter.import({ name: path.split("/").at(-1)!, bytes: await readBytes(path) });
}

async function readJson(path: string): Promise<unknown> {
  return JSON.parse(new TextDecoder().decode(await readBytes(path)));
}

async function readBytes(path: string): Promise<Uint8Array> {
  return readFile(resolve(REPOSITORY_ROOT, path));
}
