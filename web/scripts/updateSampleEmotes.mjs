import { randomUUID } from "node:crypto";
import { readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { isDeepStrictEqual } from "node:util";
import { createServer } from "vite";

const sourceIds = new Map([
  ["emote:victory_trophy", "emote:victory"],
  ["emote:idle.juggling", "emote:juggling"],
  ["emote:idle.exercise", "emote:exercise"],
  ["handstand:handstand_fail", "emote:handstand_fail"],
  ["handstand:handstand_success", "emote:handstand_success"],
]);

const [referenceDir, sampleDir] = process.argv.slice(2);
const vite = await createServer({ configFile: false, server: { middlewareMode: true, watch: null } });
try {
  const [
    { detectInputFormat, readInput, INPUT_FORMATS },
    { animationEntries, createConversionDocument },
    { compileAnimation },
    { referencedItemModelResources },
    { MAX_ANIMATION_DURATION_TICKS, parseMinecraftTime },
  ] = await Promise.all([
    vite.ssrLoadModule("/src/import/formats.ts"),
    vite.ssrLoadModule("/src/domain/conversionDocument.ts"),
    vite.ssrLoadModule("/src/compiler/animationCompiler.ts"),
    vite.ssrLoadModule("/src/domain/generatedResource.ts"),
    vite.ssrLoadModule("/src/format/time.ts"),
  ]);

  const sources = new Map();
  for (const path of (await readdir(referenceDir, { recursive: true })).sort()) {
    if (!/\.(ajblueprint|bbmodel)$/i.test(path)) continue;
    const input = { name: basename(path), bytes: await readFile(join(referenceDir, path)) };
    const format = await detectInputFormat(input);
    const document = createConversionDocument(await readInput(format, input), INPUT_FORMATS[format].label);
    const errors = document.diagnostics.filter((issue) => issue.severity === "error");
    if (errors.length) throw new Error(`${path}: ${errors.map((issue) => issue.message).join("\n")}`);
    for (const entry of animationEntries(document)) {
      if (sources.has(entry.id)) throw new Error(`${path}: duplicate source ID ${entry.id}`);
      sources.set(entry.id, { document, entry });
    }
  }

  const matched = new Set();
  const unmatched = [];
  const updates = [];
  for (const path of (await readdir(sampleDir, { recursive: true })).sort()) {
    if (!path.endsWith(".json")) continue;
    const file = join(sampleDir, path);
    const previous = JSON.parse(await readFile(file, "utf8"));
    if (previous.type !== "animation" || previous.id === "emote:dance") continue;

    const sourceId = sourceIds.get(previous.id) ?? previous.id;
    const source = sources.get(sourceId);
    if (!source) {
      unmatched.push(previous.id);
      continue;
    }
    if (matched.has(sourceId)) throw new Error(`${path}: more than one sample for ${sourceId}`);
    matched.add(sourceId);

    const document = { ...source.document, targetMinecraftVersion: previous.target_minecraft_version ?? source.document.targetMinecraftVersion };
    const { animation: generated, generatedResourceReferences } = compileAnimation(document, source.entry);
    if (previous.schema_version !== generated.schema_version) {
      throw new Error(`${path}: expected Animation schema ${generated.schema_version}`);
    }

    const { nodes, animation: { duration } } = generated;
    let { tracks } = generated.animation;
    if (previous.id === "sit:idle_butterfly" && nodes.butterfly?.attachments?.display) {
      // The sample's callback spawns an Allay at this node.
      delete nodes.butterfly.attachments.display;
      if (!Object.keys(nodes.butterfly.attachments).length) delete nodes.butterfly.attachments;
      tracks = tracks.filter(({ target }) => target.node !== "butterfly" || target.attachment !== "display");
    }
    const updated = { ...previous, nodes, animation: { ...previous.animation, duration, tracks } };
    if (generatedResourceReferences.size) {
      const resources = [...referencedItemModelResources(updated)].filter((resource) => generatedResourceReferences.has(resource));
      if (resources.length) throw new Error(`${path}: requires generated resource-pack files: ${resources.join(", ")}`);
    }

    const ticks = parseMinecraftTime(duration, 1);
    if (ticks > MAX_ANIMATION_DURATION_TICKS || parseMinecraftTime(previous.animation.playback?.loop_start ?? "0t") >= ticks) {
      throw new Error(`${path}: duration ${duration} is incompatible with the sample's playback settings`);
    }
    for (const callback of previous.callbacks ?? []) {
      if (Object.hasOwn(previous.nodes, callback.payload) && !Object.hasOwn(nodes, callback.payload)) {
        throw new Error(`${path}: the ${callback.name} callback references missing node ${callback.payload}`);
      }
    }
    for (const event of Object.values(previous.animation.events ?? {}).flat()) {
      if (event.time && parseMinecraftTime(event.time) > ticks) throw new Error(`${path}: event at ${event.time} exceeds duration ${duration}`);
      if (event.origin.type === "node" && !Object.hasOwn(nodes, event.origin.node)) {
        throw new Error(`${path}: event references missing origin node ${event.origin.node}`);
      }
      if (event.source.type === "node" && !nodes[event.source.node]?.attachments?.[event.source.attachment]) {
        throw new Error(`${path}: event references missing attachment ${event.source.node}/${event.source.attachment}`);
      }
    }
    const output = JSON.stringify(updated);
    if (!isDeepStrictEqual(previous, JSON.parse(output))) updates.push({ file, output });
  }

  if (!matched.size) throw new Error("No sample animations matched the Blockbench projects.");
  for (const { file, output } of updates) {
    const temporary = `${file}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporary, `${output}\n`);
      await rename(temporary, file);
    } finally {
      await rm(temporary, { force: true });
    }
  }
  if (unmatched.length) console.log(`Skipped samples without a matching source: ${unmatched.join(", ")}`);
  console.log(`Checked ${matched.size} source-backed sample animations, updated ${updates.length}.`);
} finally {
  await vite.close();
}
