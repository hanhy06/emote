import JSZip from "jszip";
import { compileImportedProject } from "../compiler/animationCompiler";
import type { EmoteMetadata } from "../format/emoteAnimation";
import { serializeEmoteAnimation } from "../format/serializer";
import type { ImportedProject, ImportedSkinPart } from "../import/types";

export interface ExportOptions extends EmoteMetadata {
  minecraftVersion: string;
  namespace: string;
}

export interface ExportResult {
  blob: Blob;
  fileName: string;
  animationCount: number;
}

export async function exportProject(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
): Promise<ExportResult> {
  const prepared: ImportedProject = {
    ...project,
    nodes: Object.fromEntries(Object.entries(project.nodes).map(([id, node]) => {
      if (node.type !== "item_display" || !Object.hasOwn(skinAssignments, id)) return [id, node];
      const skin = skinAssignments[id];
      const { skin: _oldSkin, ...withoutSkin } = node;
      return [id, skin ? { ...withoutSkin, skin } : withoutSkin];
    })),
  };
  const metadata: EmoteMetadata = {
    name: options.name,
    description: options.description,
    command_name: options.command_name,
    hide_player: options.hide_player,
  };
  const animations = compileImportedProject(prepared, {
    minecraftVersion: options.minecraftVersion,
    namespace: options.namespace,
    metadata,
  });
  const serialized = animations.map((animation) => ({ animation, text: serializeEmoteAnimation(animation) }));
  const stem = sanitizeFileName(options.command_name);
  if (serialized.length === 1 && project.artifacts.length === 0) {
    return {
      blob: new Blob([serialized[0].text], { type: "application/json" }),
      fileName: `emote.${stem}.json`,
      animationCount: 1,
    };
  }

  if (project.artifacts.length > 0 && options.minecraftVersion !== "26.2") {
    throw new Error("Animated Java resource-pack export currently supports Minecraft 26.2 only.");
  }
  const zip = new JSZip();
  for (const { animation, text } of serialized) {
    const path = animation.id.slice(animation.id.indexOf(":") + 1);
    zip.file(`emotes/${path}.json`, text);
  }
  if (project.artifacts.length > 0) {
    zip.file("resourcepack/pack.mcmeta", `${JSON.stringify({
      pack: { description: `${options.name} resources`, min_format: [107, 1], max_format: [107, 1] },
    }, null, 2)}\n`);
    for (const artifact of project.artifacts) zip.file(`resourcepack/${artifact.path}`, artifact.data);
  }
  const bytes = await zip.generateAsync({ type: "uint8array", compression: "DEFLATE" });
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  return {
    blob: new Blob([buffer], { type: "application/zip" }),
    fileName: `emote.${stem}.zip`,
    animationCount: serialized.length,
  };
}

export function downloadExport(result: ExportResult): void {
  const url = URL.createObjectURL(result.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = result.fileName;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function sanitizeFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
