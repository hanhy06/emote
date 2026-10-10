import { parseAnimationSeconds } from "./format/time";
import {
  assignDocumentSkinOrder,
  assignDocumentSkinPart,
  documentPartAssignments,
  replaceDocumentAnimationTimelineEvents,
  updateDocumentAnimationLifecycleEvents,
  updateDocumentAnimation,
  type ConversionDocument,
} from "./domain/conversionDocument";
import type { EmoteCallback } from "./domain/emoteDefinition";
import type { PlayerSkinPart } from "./domain/player";
import type { Animation, EventIR, TimelineEventIR } from "./domain/animationIR";
import { selectNode, selectNodes } from "./preview/skinParts";
import { TICKS_PER_SECOND } from "./format/time";

export type WorkspacePage = 0 | 1 | 2;

export interface ConversionSession {
  document: ConversionDocument;
  animationIndex: number;
  previewFrameIndex: number;
  selectedNodeIds: Set<string>;
}

export interface WorkspaceState {
  session: ConversionSession | null;
  page: WorkspacePage;
  openError: string;
  exportError: string;
  operation:
    | { type: "idle" }
    | { type: "opening" | "exporting"; message: string };
}

export type WorkspaceAction =
  | { type: "open_started"; message: string }
  | { type: "documents_open_succeeded"; document: ConversionDocument }
  | { type: "open_failed"; message: string }
  | { type: "export_started"; message: string }
  | { type: "export_failed"; message: string }
  | { type: "operation_finished" }
  | { type: "page_selected"; page: WorkspacePage }
  | { type: "animation_selected"; index: number }
  | { type: "preview_frame_selected"; index: number }
  | { type: "node_selected"; nodeId: string; additive: boolean }
  | { type: "nodes_selected"; nodeIds: readonly string[]; additive: boolean }
  | { type: "skin_part_assigned"; part: PlayerSkinPart | null }
  | { type: "skin_order_assigned"; order: number }
  | { type: "animation_changed"; ir: Animation }
  | { type: "minecraft_version_changed"; version: string }
  | { type: "lifecycle_events_changed"; events: { callbacks: EmoteCallback[]; start: EventIR[]; loop: EventIR[]; stop: EventIR[] } }
  | { type: "timeline_events_changed"; events: TimelineEventIR[] };

export const EMPTY_SELECTION = new Set<string>();

export const INITIAL_WORKSPACE: WorkspaceState = {
  session: null,
  page: 0,
  openError: "",
  exportError: "",
  operation: { type: "idle" },
};

export function workspaceReducer(state: WorkspaceState, action: WorkspaceAction): WorkspaceState {
  switch (action.type) {
    case "open_started":
      return { ...state, openError: "", exportError: "", operation: { type: "opening", message: action.message } };
    case "documents_open_succeeded": {
      const session = createConversionSessionFromDocument(action.document);
      return openedSession(state, session);
    }
    case "open_failed":
      return { ...state, openError: action.message, operation: { type: "idle" } };
    case "export_started":
      return { ...state, exportError: "", operation: { type: "exporting", message: action.message } };
    case "export_failed":
      return { ...state, exportError: action.message, operation: { type: "idle" } };
    case "operation_finished":
      return { ...state, operation: { type: "idle" } };
    case "page_selected":
      return { ...state, page: action.page };
    case "animation_selected":
      return updateSession(state, (session) => selectSessionAnimation(session, action.index));
    case "preview_frame_selected":
      return updateSession(state, (session) => ({ ...session, previewFrameIndex: action.index, selectedNodeIds: new Set() }));
    case "node_selected":
      return updateSession(state, (session) => ({
        ...session,
        selectedNodeIds: selectNode(session.selectedNodeIds, action.nodeId, action.additive),
      }));
    case "nodes_selected":
      return updateSession(state, (session) => ({
        ...session,
        selectedNodeIds: selectNodes(session.selectedNodeIds, action.nodeIds, action.additive),
      }));
    case "skin_part_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignDocumentSkinPart(session.document, session.selectedNodeIds, action.part),
      }));
    case "skin_order_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignDocumentSkinOrder(session.document, session.selectedNodeIds, action.order),
      }));
    case "animation_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: updateDocumentAnimation(session.document, session.animationIndex, action.ir),
      }));
    case "minecraft_version_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: { ...session.document, targetMinecraftVersion: action.version },
      }));
    case "lifecycle_events_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: updateDocumentAnimationLifecycleEvents(session.document, session.animationIndex, action.events),
      }));
    case "timeline_events_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: replaceDocumentAnimationTimelineEvents(session.document, session.animationIndex, action.events),
      }));
  }
}

export function assignmentSummary(document: ConversionDocument): string {
  const assignments = documentPartAssignments(document);
  const groups = [...new Set(Object.values(document.skinCandidates).map((candidate) => candidate.groupId))];
  const assigned = groups.filter((group) => Object.entries(document.skinCandidates).filter(([, candidate]) => candidate.groupId === group).every(([id]) => assignments[id])).length;
  const skin = groups.length
    ? `${assigned}/${groups.length} skin parts assigned`
    : "No skin assignment needed";
  return document.resources.size ? `${skin} · ${document.resources.size} resource files` : skin;
}

export function eventReviewLocations(document: ConversionDocument | null): { owner: string; locations: string[] }[] {
  if (!document) return [];
  const review: { owner: string; locations: string[] }[] = [];
  for (const animation of document.animations) {
    const events = animation.clip.events;
    const locations: string[] = [];
    if (events?.start?.some((event) => event.action.type === "commands" && event.action.commands.length > 0)) locations.push("start");
    const times = [...new Set(events?.timeline?.filter((event) => event.action.type === "commands" && event.action.commands.length > 0).map((event) => event.time) ?? [])].sort((first, second) => parseAnimationSeconds(first) - parseAnimationSeconds(second));
    if (times.length) locations.push(`frames: ${times.map((time) => `${Number((parseAnimationSeconds(time) * TICKS_PER_SECOND).toPrecision(12))}t`).join(", ")}`);
    for (const phase of ["loop", "stop"] as const) {
      if (events?.[phase]?.some((event) => event.action.type === "commands" && event.action.commands.length > 0)) locations.push(phase);
    }
    if (animation.callbacks?.length) locations.push("callbacks");
    if (locations.length) review.push({ owner: `Animation ${animation.id}`, locations });
  }
  if (document.sequence.callbacks?.length) review.push({ owner: `Sequence ${document.sequence.displayName}`, locations: ["callbacks"] });
  return review;
}

function createConversionSessionFromDocument(document: ConversionDocument): ConversionSession {
  return {
    document,
    animationIndex: 0,
    previewFrameIndex: 0,
    selectedNodeIds: new Set(),
  };
}

function openedSession(state: WorkspaceState, session: ConversionSession): WorkspaceState {
  return { ...state, session, page: 0, operation: { type: "idle" } };
}

function selectSessionAnimation(session: ConversionSession, animationIndex: number): ConversionSession {
  if (!session.document.animations[animationIndex]) return session;
  return { ...session, animationIndex, previewFrameIndex: 0, selectedNodeIds: new Set() };
}

function updateSession(
  state: WorkspaceState,
  edit: (session: ConversionSession) => ConversionSession,
): WorkspaceState {
  if (!state.session) return state;
  const session = edit(state.session);
  return { ...state, session };
}
