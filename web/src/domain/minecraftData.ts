import type { EmoteNode, EmoteNodeTracks, EmoteTimeline } from "../format/emoteAnimation";

export interface RawNbtField {
  name: string;
  value: string;
}

export interface BlockStateData {
  id: string;
  properties?: Record<string, string>;
  extraFields?: RawNbtField[];
}

export interface ItemStackData {
  id: string;
  count?: number;
  components?: RawNbtField[];
  extraFields?: RawNbtField[];
}

export interface DisplayNbtPatch {
  blockState?: Partial<BlockStateData>;
  itemStack?: Partial<ItemStackData>;
  rawFields: RawNbtField[];
}

export type DisplayNbtValue = DisplayNbtPatch | { select: string; options: DisplayNbtPatch[] };

export type RuntimeNode =
  | Exclude<EmoteNode, { type: "block_display" | "item_display" }>
  | (Omit<Extract<EmoteNode, { type: "block_display" }>, "block_state_snbt"> & { blockState: BlockStateData })
  | (Omit<Extract<EmoteNode, { type: "item_display" }>, "item_stack_snbt"> & { itemStack?: ItemStackData });

export type RuntimeNodeTracks = Omit<EmoteNodeTracks, "nbt"> & { nbt?: { time: string; value: DisplayNbtValue }[] };
export type RuntimeTimeline = Omit<EmoteTimeline, "tracks"> & { tracks: Record<string, RuntimeNodeTracks> };
