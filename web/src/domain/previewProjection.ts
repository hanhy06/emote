export type PreviewAvailability =
  | { status: "full" }
  | { status: "approximate"; reason: string }
  | { status: "unavailable"; reason: string };
