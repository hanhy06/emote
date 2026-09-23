import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, it } from "vitest";
import type { EmoteAnimation } from "../format/emoteAnimation";
import type { ImportedProject } from "../domain/conversionSeed";
import { animatedJavaBlueprintAdapter } from "../import/animatedJava/animatedJavaBlueprintAdapter";
import type { ImportAdapter } from "../import/adapter";
import { geckoLibBbmodelAdapter } from "../import/geckoLib/geckoLibBbmodelAdapter";
import { emoteFileName } from "../export/projectExporter";
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
    const expected = await readJson(`docs/sample/${emoteFileName(actual.id)}`) as EmoteAnimation;

    expect(actual).toEqual(expected);
  });

  it.each(SIT_MATRIX_SAMPLES)("matches the existing %s sample", async (name) => {
    const actual = requireAnimation(sitAnimations, name);
    const expected = await readJson(`docs/sample/sit/${emoteFileName(actual.id)}`) as EmoteAnimation;

    expect(actual).toEqual(expected);
  });
});

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
