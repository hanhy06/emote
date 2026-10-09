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
  generatedResourceReferences?: string[];
}

export interface DisplayNbtPatch {
  blockState?: Partial<BlockStateData>;
  itemStack?: Partial<ItemStackData>;
  rawFields: RawNbtField[];
}
