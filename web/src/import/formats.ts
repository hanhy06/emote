import type { ImportedProject, InputFormat } from "../domain/conversionSeed";
import type { ImportedSequence } from "../domain/emoteDefinition";
import type { ImportInput } from "./input";
import { ConversionError } from "../foundation/diagnostics";
import { isRecord } from "../format/runtimeValue";
import { EMOTE_SCHEMA_VERSION } from "../format/emote";
import { parseInputJson, parseInputJsonc } from "./common/inputCache";
import type { AjProject } from "./animatedJava/animatedJavaProjectSchema";
import type { GeckoLibBbmodelProject } from "./geckoLib/geckoLibBbmodelSchema";
import type { BedrockAnimationDocument } from "./bedrockAnimation/bedrockAnimationSchema";

export const INPUT_FORMATS: Record<InputFormat, { extension: string; label: string }> = {
  emote_sequence: { extension: "json", label: "Emote sequence JSON" },
  bd_datapack: { extension: "zip", label: "BD Engine datapack" },
  animated_java_blueprint: { extension: "ajblueprint", label: "Animated Java project" },
  geckolib_bbmodel: { extension: "bbmodel", label: "GeckoLib Blockbench project" },
  bedrock_animation_json: { extension: "json", label: "Bedrock player animation JSON" },
  emotecraft_binary: { extension: "emotecraft", label: "Emotecraft binary" },
  emote_json: { extension: "json", label: "Emote animation JSON" },
};

export async function detectInputFormat(input: ImportInput): Promise<InputFormat> {
  const formats = Object.keys(INPUT_FORMATS) as InputFormat[];
  const extension = input.name.toLowerCase().split(".").at(-1);
  const hints = formats.filter((format) => INPUT_FORMATS[format].extension === extension);
  const probe = async (candidates: InputFormat[]) => {
    const matches = await Promise.all(candidates.map(async (format) => await matchesInputFormat(input, format) ? format : null));
    return matches.filter((format): format is InputFormat => format !== null);
  };
  let matches = await probe(hints.length ? hints : formats);
  if (matches.length === 0 && hints.length) matches = await probe(formats.filter((format) => !hints.includes(format)));
  if (matches.length === 0) throw new ConversionError("unsupported_input", `Unsupported input format: ${input.name}`, input.name);
  if (matches.length > 1) throw new ConversionError("ambiguous_input", `Input format is ambiguous between ${INPUT_FORMATS[matches[0]].label} and ${INPUT_FORMATS[matches[1]].label}.`, input.name);
  return matches[0];
}

async function matchesInputFormat(input: ImportInput, format: InputFormat): Promise<boolean> {
  try {
    switch (format) {
      case "emote_sequence": {
        const value = parseInputJson(input);
        return isRecord(value) && value.type === "sequence" && value.schema_version === EMOTE_SCHEMA_VERSION;
      }
      case "bd_datapack":
        (await import("./bdDatapack/bdDatapackSource")).readBdDatapackSource(input);
        return true;
      case "animated_java_blueprint":
        return (await import("./animatedJava/animatedJavaProjectSchema")).isAnimatedJavaProject(parseInputJson(input));
      case "geckolib_bbmodel": {
        const value = parseInputJson(input);
        return isRecord(value) && isRecord(value.meta) && value.meta.model_format === "geckolib_model";
      }
      case "bedrock_animation_json":
        return (await import("./bedrockAnimation/bedrockAnimationSchema")).isBedrockAnimationDocument(parseInputJsonc(input));
      case "emotecraft_binary":
        return (await import("./emotecraft/emotecraftBinary")).probeEmotecraft(input.bytes);
      case "emote_json": {
        const value = parseInputJson(input);
        return isRecord(value) && value.type === "animation" && value.schema_version === EMOTE_SCHEMA_VERSION && isRecord(value.nodes) && isRecord(value.animation);
      }
    }
  } catch {
    return false;
  }
}

export function readInput(format: "emote_sequence", input: ImportInput): Promise<ImportedSequence>;
export function readInput(format: Exclude<InputFormat, "emote_sequence">, input: ImportInput): Promise<ImportedProject>;
export function readInput(format: InputFormat, input: ImportInput): Promise<ImportedProject | ImportedSequence>;
export async function readInput(format: InputFormat, input: ImportInput): Promise<ImportedProject | ImportedSequence> {
  switch (format) {
    case "emote_sequence":
      return (await import("./emoteJson/sequenceImporter")).importSequence(input);
    case "bd_datapack": {
      const [{ importBdDatapack }, { readBdDatapackSource }] = await Promise.all([import("./bdDatapack/bdDatapackImporter"), import("./bdDatapack/bdDatapackSource")]);
      return importBdDatapack(readBdDatapackSource(input), input.name);
    }
    case "animated_java_blueprint": {
      const { importAnimatedJavaProject } = await import("./animatedJava/animatedJavaProjectImporter");
      return importAnimatedJavaProject(input, parseInputJson(input) as AjProject);
    }
    case "geckolib_bbmodel": {
      const { importGeckoLibProject } = await import("./geckoLib/geckoLibImporter");
      return importGeckoLibProject(parseInputJson(input) as GeckoLibBbmodelProject, input.name);
    }
    case "bedrock_animation_json": {
      const { importBedrockAnimationDocument } = await import("./bedrockAnimation/bedrockAnimationImporter");
      return importBedrockAnimationDocument(parseInputJsonc(input) as BedrockAnimationDocument, input.name);
    }
    case "emotecraft_binary": {
      const [{ importEmotecraftFile }, { decodeEmotecraft }] = await Promise.all([import("./emotecraft/emotecraftImporter"), import("./emotecraft/emotecraftBinary")]);
      return importEmotecraftFile(decodeEmotecraft(input.bytes), input.name);
    }
    case "emote_json":
      return (await import("./emoteJson/animationImporter")).importAnimation(parseInputJson(input), input.name);
  }
}
