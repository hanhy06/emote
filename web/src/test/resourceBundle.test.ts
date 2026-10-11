import { readFile } from "node:fs/promises";
import { unzipSync } from "fflate";
import { expect, it } from "vitest";
import { assignSkinPart, createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation } from "../export/projectExporter";
import { readInput } from "../import/formats";

it("packages the generated models and textures referenced by exported AJ and bbmodel animations", async () => {
  for (const [path, format, name] of [
    ["docs/reference/aj/emote.ajblueprint", "animated_java_blueprint", "bat"],
    ["docs/reference/bbmodel/emote.bbmodel", "geckolib_bbmodel", "hello"],
  ] as const) {
    const project = await readInput(format, { name: path.split("/").at(-1)!, bytes: await readFile(new URL(`../../../${path}`, import.meta.url)) });
    const imported = createConversionDocument(project, format);
    const document = assignSkinPart(imported, new Set(Object.keys(imported.skinCandidates)), null);
    const index = document.animations.findIndex((animation) => animation.sourceName === name);
    expect(index, path).toBeGreaterThanOrEqual(0);
    const entry = document.animations[index];
    const expectedReferences = itemReferences({ nodes: entry.nodeIds.map((id) => document.nodes[id]), animation: entry.clip });
    expect(expectedReferences.size, `${path}: native cubes must require generated models`).toBeGreaterThan(0);
    const outputs = await exportAnimation(document, index);
    const json = outputs.find((output) => output.fileName.endsWith(".json"))!;
    const zip = outputs.find((output) => output.fileName.endsWith(".zip"));
    expect(zip, `${path}: referenced resources require a ZIP`).toBeDefined();
    const actualReferences = itemReferences(JSON.parse(await json.blob.text()));
    expect([...actualReferences].sort(), `${path}: output item references`).toEqual([...expectedReferences].sort());
    const files = unzipSync(new Uint8Array(await zip!.blob.arrayBuffer()));
    const readResource = (resourcePath: string): Uint8Array => {
      const directory = resourcePath.includes("/textures/") ? "textures" : "models";
      const key = `${directory}/${resourcePath.split("/").slice(1).join("]")}`;
      expect(files[key], `${path}: missing ${resourcePath} (${key})`).toBeDefined();
      return files[key];
    };
    const resourcePath = (id: string, directory: string, extension: string) => {
      const [namespace, location] = id.split(":");
      return `assets/${namespace}/${directory}/${location}.${extension}`;
    };
    let generatedModels = 0;
    const textures = new Set<string>();
    for (const itemPath of actualReferences) {
      if (itemPath.startsWith("assets/minecraft/")) continue;
      expect(document.resources.has(itemPath), `${path}: generated item ${itemPath}`).toBe(true);
      generatedModels++;
      const item = JSON.parse(new TextDecoder().decode(readResource(itemPath)));
      const sourceItem = document.resources.get(itemPath);
      expect(sourceItem, itemPath).toMatchObject({ kind: "item_model", model: item.model.model });
      const modelPath = resourcePath(item.model.model, "models", "json");
      const model = JSON.parse(new TextDecoder().decode(readResource(modelPath)));
      expect(model.elements.length, modelPath).toBeGreaterThan(0);
      expect(document.resources.get(modelPath), modelPath).toMatchObject({ kind: "cuboid_model", elements: model.elements, textures: model.textures });
      for (const texture of Object.values(model.textures) as string[]) {
        const texturePath = resourcePath(texture, "textures", "png");
        if (!texture.startsWith(`${itemPath.split("/")[1]}:`)) continue;
        textures.add(texturePath);
        expect(readResource(texturePath), texturePath).toEqual(document.resources.get(texturePath));
        if (document.resources.has(`${texturePath}.mcmeta`)) {
          expect(document.resources.get(`${texturePath}.mcmeta`), `${texturePath}.mcmeta`).toEqual({ kind: "json", value: JSON.parse(new TextDecoder().decode(readResource(`${texturePath}.mcmeta`))) });
        }
      }
    }
    expect(generatedModels, `${path}: must inspect generated item/model chains`).toBeGreaterThan(0);
    expect(textures.size, `${path}: must inspect generated textures`).toBeGreaterThan(0);
  }
}, 30_000);

function itemReferences(value: unknown): Set<string> {
  const references = new Set<string>();
  const visit = (value: unknown) => {
    if (typeof value === "string") {
      for (const match of value.matchAll(/(?:"minecraft:item_model"|minecraft:item_model)\s*:\s*"([a-z0-9_.-]+):([a-z0-9_./-]+)"/g)) {
        references.add(`assets/${match[1]}/items/${match[2]}.json`);
      }
    } else if (value && typeof value === "object") {
      for (const child of Object.values(value)) visit(child);
    }
  };
  visit(value);
  return references;
}
