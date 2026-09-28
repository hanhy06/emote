export interface ImportInput {
  name: string;
  bytes: Uint8Array;
}

export interface ProbeResult {
  confidence: number;
  reason: string;
}
