import type { EmoteMetadata, EmotePlayerBehavior } from "./emoteDefinition";
import type { Matrix16 } from "./matrix";
import type { PlayerSkinPart } from "./player";
import type { BlockStateData, ItemStackData } from "./minecraftData";
import type { GeneratedResource } from "./generatedResource";
import type { ConversionIssue } from "../foundation/diagnostics";
import { normalizeResourceLocation } from "../format/resourceLocation";
import type { AnimationIR } from "./animationIR";

export type ImportSource = "bd_datapack" | "animated_java_blueprint" | "geckolib_bbmodel" | "bedrock_animation_json" | "emotecraft_binary" | "emote_json" | "emote_sequence";

export interface ImportedProject {
  source: ImportSource;
  sourceName: string;
  suggestedMetadata: EmoteMetadata;
  suggestedPlayer: EmotePlayerBehavior;
  suggestedMinecraftVersion?: string;
  suggestedNamespace?: string;
  suggestedStandalone?: boolean;
  suggestedCooldown?: string;
  suggestedRotationDeadzone?: number;
  suggestedDisplayInterpolation?: string;
  nodeHints: Record<string, ImportedNodeHint>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  resources: Map<string, GeneratedResource>;
}

export interface ImportedNodeBase {
  binding: { sourceNodeId: string; skinGroupId?: string };
  visible: boolean;
  entityNbt?: string;
}

export type ImportedNode =
  | (ImportedNodeBase & {
    type: "item_display";
    itemStack: ItemStackData;
    itemDisplay: string;
    skin?: ImportedSkinPart;
    suggestedSkin?: ImportedSkinPart;
    playerHeadConversionMatrix?: Matrix16;
  })
  | (ImportedNodeBase & { type: "block_display"; blockState: BlockStateData })
  | (ImportedNodeBase & { type: "text_display"; text: unknown })
  | (Omit<ImportedNodeBase, "visible" | "entityNbt"> & { type: "anchor" });

export interface ImportedSkinPart {
  part: PlayerSkinPart;
  order: number;
}

export interface ImportedAnimation {
  ir: AnimationIR;
  id: string;
  sourceReferenceId?: string;
  name: string;
  suggestedMetadata?: EmoteMetadata;
}

export interface ImportedNodeHint {
  sourceNodeId: string;
  skinCandidate?: {
    groupId: string;
    fittingMatrix?: Matrix16;
    suggestion?: ImportedSkinPart;
  };
}

export function importedNodeHints(nodes: Record<string, ImportedNode>): Record<string, ImportedNodeHint> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => [id, {
    sourceNodeId: node.binding.sourceNodeId,
    ...(node.type === "item_display" && (node.skin || node.suggestedSkin || node.playerHeadConversionMatrix || normalizeResourceLocation(node.itemStack.id) === "minecraft:player_head") ? {
      skinCandidate: {
        groupId: node.binding.skinGroupId ?? id,
        ...(node.playerHeadConversionMatrix ? { fittingMatrix: node.playerHeadConversionMatrix } : {}),
        ...(node.suggestedSkin ?? node.skin ? { suggestion: node.suggestedSkin ?? node.skin } : {}),
      },
    } : {}),
  }]));
}

export type ImportDiagnostic = ConversionIssue;
