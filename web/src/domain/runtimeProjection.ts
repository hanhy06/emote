import type { BakedRuntimeNodeTracks, RuntimeMolangPrograms, RuntimeNode, RuntimeNodeTracks } from "./minecraftData";
import type { NativeRuntimeBindings } from "./nodeBindings";

export type AnimationRuntimeData =
  | {
    kind: "baked";
    tracks: Record<string, BakedRuntimeNodeTracks>;
  }
  | {
    kind: "native";
    molang?: RuntimeMolangPrograms;
    nodes: Record<string, RuntimeNode>;
    tracks: Record<string, RuntimeNodeTracks>;
    bindings: NativeRuntimeBindings;
  };

export interface RuntimeExportAvailability {
  exportable: boolean;
  reason?: string;
}

export interface AnimationRuntimeProjection {
  id: string;
  sourceName: string;
  durationTicks: number;
  sourcePlaybackMode: "once" | "hold" | "loop" | "server_sync";
  availability: RuntimeExportAvailability;
  data: AnimationRuntimeData;
}
