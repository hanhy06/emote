import { parseMinecraftTime } from "./format/time";
import {
  assignSkinOrder,
  assignSkinPart,
  skinPartAssignments,
  replaceTimelineEvents,
  updateLifecycleEvents,
  updateAnimation,
  type ConversionDocument,
} from "./domain/conversionDocument";
import { animationReferenceTargets, isSequenceControlId, type EmoteCallback } from "./domain/emoteDefinition";
import type { ConversionIssue } from "./foundation/diagnostics";
import type { PlayerSkinPart } from "./domain/player";
import type { AnimationEntryIR, EventIR } from "./domain/animationIR";

export type WorkspacePage = 0 | 1;
export type SelectedFrame = { type: "create" } | { type: "timeline"; animationTick: number };

export interface ConversionSession {
  document: ConversionDocument;
  animationIndex: number;
  selectedFrame: SelectedFrame;
  selectedCandidateIds: Set<string>;
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
  | { type: "frame_selected"; frame: SelectedFrame }
  | { type: "candidate_selected"; candidateId: string; additive: boolean }
  | { type: "candidates_selected"; candidateIds: readonly string[]; additive: boolean }
  | { type: "skin_part_assigned"; part: PlayerSkinPart | null }
  | { type: "skin_order_assigned"; order: number }
  | { type: "animation_changed"; ir: AnimationEntryIR }
  | { type: "minecraft_version_changed"; version: string }
  | { type: "lifecycle_events_changed"; events: { callbacks: EmoteCallback[]; start: EventIR[]; loop: EventIR[]; stop: EventIR[] } }
  | { type: "timeline_events_changed"; animationTick: number; events: EventIR[] };

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
    case "documents_open_succeeded":
      return { ...state,
        session: { document: action.document, animationIndex: 0, selectedFrame: { type: "create" }, selectedCandidateIds: new Set() },
        page: 0, operation: { type: "idle" } };
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
      return updateSession(state, (session) => session.document.animations[action.index]
        ? { ...session, animationIndex: action.index, selectedFrame: { type: "create" }, selectedCandidateIds: new Set() }
        : session);
    case "frame_selected":
      return updateSession(state, (session) => ({ ...session,
        selectedFrame: action.frame.type === "create" ? action.frame : { type: "timeline",
          animationTick: Math.max(0, Math.min(action.frame.animationTick, parseMinecraftTime(session.document.animations[session.animationIndex].clip.duration, 1))) },
        selectedCandidateIds: new Set() }));
    case "candidate_selected":
      return updateSession(state, (session) => ({
        ...session,
        selectedCandidateIds: selectCandidate(session.selectedCandidateIds, action.candidateId, action.additive),
      }));
    case "candidates_selected":
      return updateSession(state, (session) => ({
        ...session,
        selectedCandidateIds: selectCandidates(session.selectedCandidateIds, action.candidateIds, action.additive),
      }));
    case "skin_part_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignSkinPart(session.document, session.selectedCandidateIds, action.part),
      }));
    case "skin_order_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignSkinOrder(session.document, session.selectedCandidateIds, action.order),
      }));
    case "animation_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: updateAnimation(session.document, session.animationIndex, action.ir),
      }));
    case "minecraft_version_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: { ...session.document, targetMinecraftVersion: action.version },
      }));
    case "lifecycle_events_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: updateLifecycleEvents(session.document, session.animationIndex, action.events),
      }));
    case "timeline_events_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: replaceTimelineEvents(session.document, session.animationIndex, action.animationTick, action.events),
      }));
  }
}

export function assignmentSummary(document: ConversionDocument): string {
  const assignments = skinPartAssignments(document);
  const groups = [...new Set(Object.values(document.skinCandidates).map((candidate) => candidate.groupId))];
  const assigned = groups.filter((group) => Object.entries(document.skinCandidates).filter(([, candidate]) => candidate.groupId === group).every(([id]) => assignments[id])).length;
  const skin = groups.length
    ? `${assigned}/${groups.length} skin parts assigned`
    : "No skin assignment needed";
  return document.resources.size ? `${skin} · ${document.resources.size} resource files` : skin;
}

export function eventReviewSummary(document: ConversionDocument | null, animationIndex: number) {
  const animation = document?.animations[animationIndex];
  return {
    hasCommands: document?.animations.some(animation => Object.values(animation.clip.events ?? {})
      .some(events => events.some(event => event.action.type === "commands" && event.action.commands.length > 0))) ?? false,
    hasSelectedReview: Boolean(animation?.callbacks?.length) || Object.values(animation?.clip.events ?? {})
      .some(events => events.some(event => event.action.type === "commands" && event.action.commands.length > 0)),
    commandTicks: [...new Set(animation?.clip.events?.timeline?.filter(event => event.action.type === "commands" && event.action.commands.length > 0)
      .map(event => parseMinecraftTime(event.time)) ?? [])].sort((first, second) => first - second),
  };
}

export function sequenceReferenceWarnings(document: ConversionDocument | null): ConversionIssue[] {
  if (!document) return [];
  const references = animationReferenceTargets(document.animations);
  return (document.sequence.steps ?? []).flatMap((step, index) => {
    if ("wait" in step) return [];
    const ids = typeof step.emote === "string" ? [step.emote] : step.emote.map((choice) => choice.id);
    return [...new Set(ids)].flatMap((id) => {
      if (isSequenceControlId(id)) return [];
      const targets = references.get(id);
      if (targets?.length === 1) return [];
      return [{
        severity: "warning" as const,
        code: targets ? "sequence_ambiguous_reference" : "sequence_external_reference",
        message: targets
          ? `Sequence ${document.sequence.name}, step ${index + 1}: ${id} matches multiple imported animations. The reference is preserved; choose an output animation ID.`
          : `Sequence ${document.sequence.name}, step ${index + 1}: ${id} is not included in this export. The reference is preserved; make sure this emote is available on the server.`,
        sourcePath: `$.steps[${index}].emote`,
      }];
    });
  });
}

function selectCandidate(current: ReadonlySet<string>, candidateId: string, additive: boolean): Set<string> {
  if (!additive) return current.has(candidateId) ? new Set() : new Set([candidateId]);
  const next = new Set(current);
  if (next.has(candidateId)) next.delete(candidateId);
  else next.add(candidateId);
  return next;
}

function selectCandidates(current: ReadonlySet<string>, candidateIds: readonly string[], additive: boolean): Set<string> {
  const next = additive ? new Set(current) : new Set<string>();
  candidateIds.forEach((candidateId) => next.add(candidateId));
  return next;
}

function updateSession(
  state: WorkspaceState,
  edit: (session: ConversionSession) => ConversionSession,
): WorkspaceState {
  if (!state.session) return state;
  return { ...state, session: edit(state.session) };
}
