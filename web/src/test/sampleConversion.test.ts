import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, it } from "vitest";
import type { EmoteAnimation, Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../domain/conversionSeed";
import { animatedJavaBlueprintAdapter } from "../import/animatedJava/animatedJavaBlueprintAdapter";
import type { ImportAdapter } from "../import/adapter";
import { emoteJsonAdapter } from "../import/emoteJson/emoteJsonAdapter";
import { geckoLibBbmodelAdapter } from "../import/geckoLib/geckoLibBbmodelAdapter";
import { compileImportedProject } from "./compileImportedFixture";

const REPOSITORY_ROOT = fileURLToPath(new URL("../../../", import.meta.url));
const DIRECT_SAMPLES = ["anvil", "clap", "cry", "indicate", "no", "yes"];
const SIT_MATRIX_SAMPLES = {
  sit_down: "emote.1.sit_down.json",
  idle_sky: "emote.2.idle_sky.json",
  idle_flower: "emote.4.idle_flower.json",
  stand_up1: "emote.5.stand_up1.json",
  stand_up2: "emote.6.stand_up2.json",
} as const;
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

  it.each(DIRECT_SAMPLES)("preserves the %s sample transform matrices", async (name) => {
    const actual = requireAnimation(directAnimations, name);
    const expected = await readJson(`docs/sample/emote.${name}.json`) as EmoteAnimation;

    if (name === "anvil") {
      expect(Object.values(actual.nodes).some((node) => node.type === "block_display"), "anvil must exercise Animated Java block-display conversion").toBe(true);
    }
    await expectAnimationMatricesEquivalent(actual, expected);
  });

  it.each(Object.entries(SIT_MATRIX_SAMPLES))("preserves the %s sample transform matrices", async (name, fileName) => {
    const generated = requireAnimation(sitAnimations, name);
    const expected = await readJson(`docs/sample/sit/${fileName}`) as EmoteAnimation;

    if (name === "idle_flower") {
      const actualPoppy = findPoppyDisplay(generated);
      const expectedPoppy = findPoppyDisplay(expected);
      expect(actualPoppy, "idle_flower must exercise Animated Java item-display conversion").toBeDefined();
      expect(expectedPoppy, "idle_flower sample must retain its poppy item display").toBeDefined();
      expect(actualPoppy?.item_display).toBe(expectedPoppy?.item_display);
    }
    await expectAnimationMatricesEquivalent(generated, expected);
  });
});

function requireAnimation(animations: ReadonlyMap<string, EmoteAnimation>, name: string): EmoteAnimation {
  const animation = animations.get(name);
  if (!animation) throw new Error(`${name} must exist in a reference project`);
  return animation;
}

function findPoppyDisplay(animation: EmoteAnimation): Extract<EmoteAnimation["nodes"][string], { type: "item_display" }> | undefined {
  return Object.values(animation.nodes).find((node): node is Extract<typeof node, { type: "item_display" }> =>
    node.type === "item_display" && node.item_stack_snbt?.includes("minecraft:poppy") === true);
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

async function expectAnimationMatricesEquivalent(actual: EmoteAnimation, expected: EmoteAnimation): Promise<void> {
  const [actualProject, expectedProject] = await Promise.all([
    importEmote(actual, "actual.json"),
    importEmote(expected, "expected.json"),
  ]);
  const actualAnimation = actualProject.animations[0];
  const expectedAnimation = expectedProject.animations[0];

  for (const [nodeId, expectedNode] of Object.entries(expectedProject.nodes)) {
    const actualNode = actualProject.nodes[nodeId];
    expect(actualNode, `nodes.${nodeId} must exist`).toBeDefined();
    expectMatrix(actualNode.defaultMatrix, expectedNode.defaultMatrix, `nodes.${nodeId}`);
  }

  for (const [nodeId, expectedTrack] of Object.entries(expectedAnimation.tracks)) {
    const actualTrack = actualAnimation.tracks[nodeId];
    expect(actualTrack, `tracks.${nodeId} must exist`).toBeDefined();
    expect(actualTrack.transforms.length, `${nodeId} transform count`).toBe(expectedTrack.transforms.length);
    expectedTrack.transforms.forEach((expectedFrame, index) => {
      const actualFrame = actualTrack.transforms[index];
      expect(actualFrame.tick, `${nodeId}[${index}].tick`).toBe(expectedFrame.tick);
      expect(actualFrame.interpolation, `${nodeId}[${index}].interpolation`).toEqual(expectedFrame.interpolation);
      expectMatrix(actualFrame.matrix, expectedFrame.matrix, `${nodeId}[${index}].matrix`);
    });
  }
}

async function importEmote(animation: EmoteAnimation, name: string) {
  return emoteJsonAdapter.import({ name, bytes: new TextEncoder().encode(JSON.stringify(animation)) });
}

function expectMatrix(actual: Matrix16, expected: Matrix16, path: string): void {
  actual.forEach((value, index) => expect(value, `${path}[${index}]`).toBeCloseTo(expected[index], 9));
}
