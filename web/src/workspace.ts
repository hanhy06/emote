import {
  assignDocumentNodeSpace,
  assignDocumentSkinOrder,
  assignDocumentSkinPart,
  createConversionDocument,
  documentPartAssignments,
  editDocumentAnimation,
  updateDocumentAnimationOutput,
  type AnimationOutputSettings,
  type ConversionDocument,
  type ConversionNode,
} from "./domain/conversionDocument";
import { animationAvailability, type ImportedAnimation, type ImportedProject, type ImportedTimelineEvent } from "./domain/conversionSeed";
import type { NodeSpace, PlayerSkinPart } from "./format/emoteAnimation";
import { isVisibleAtTick, type PlayerHeadPart } from "./preview/playerHeadPart";
import { selectPart, selectParts } from "./preview/skinAssignment";

type ConversionItemNode = Extract<ConversionNode, { type: "item_display" }>;

export type WorkspacePage = 0 | 1 | 2;

export interface SkinCandidate {
  nodeId: string;
  partIndex: number;
  node: ConversionItemNode;
}

export interface ConversionSession {
  document: ConversionDocument;
  animationIndex: number;
  previewFrameIndex: number;
  selectedParts: Set<string>;
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
  | { type: "open_succeeded"; project: ImportedProject; adapterLabel: string }
  | { type: "open_failed"; message: string }
  | { type: "export_started"; message: string }
  | { type: "export_failed"; message: string }
  | { type: "operation_finished" }
  | { type: "page_selected"; page: WorkspacePage }
  | { type: "animation_selected"; index: number }
  | { type: "preview_frame_selected"; index: number }
  | { type: "part_selected"; nodeId: string; additive: boolean }
  | { type: "parts_selected"; nodeIds: readonly string[]; additive: boolean }
  | { type: "skin_part_assigned"; part: PlayerSkinPart | null }
  | { type: "node_space_assigned"; space: NodeSpace }
  | { type: "skin_order_assigned"; order: number }
  | { type: "animation_output_changed"; output: AnimationOutputSettings }
  | { type: "minecraft_version_changed"; version: string }
  | { type: "frame_command_added"; tick: number }
  | { type: "frame_command_changed"; eventIndex: number; commandIndex: number; command: string }
  | { type: "frame_command_removed"; eventIndex: number; commandIndex: number };

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
      return { ...state, session: null, page: 0, openError: "", exportError: "", operation: { type: "opening", message: action.message } };
    case "open_succeeded": {
      const session = createConversionSession(action.project, action.adapterLabel);
      const animation = session.document.animations[session.animationIndex]?.source;
      const page = animation && animationAvailability(animation).preview === "unavailable" ? 1 : 0;
      return { ...state, session, page, operation: { type: "idle" } };
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
      return updateSession(state, (session) => selectSessionAnimation(session, action.index), (session) => {
        const animation = session.document.animations[session.animationIndex]?.source;
        return animation && animationAvailability(animation).preview === "unavailable" ? 1 : state.page;
      });
    case "preview_frame_selected":
      return updateSession(state, (session) => ({ ...session, previewFrameIndex: action.index, selectedParts: new Set() }));
    case "part_selected":
      return updateSession(state, (session) => ({
        ...session,
        selectedParts: selectPart(session.selectedParts, action.nodeId, action.additive),
      }));
    case "parts_selected":
      return updateSession(state, (session) => ({
        ...session,
        selectedParts: selectParts(session.selectedParts, action.nodeIds, action.additive),
      }));
    case "skin_part_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignDocumentSkinPart(session.document, session.selectedParts, action.part),
      }));
    case "node_space_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignDocumentNodeSpace(session.document, session.selectedParts, action.space),
      }));
    case "skin_order_assigned":
      return updateSession(state, (session) => ({
        ...session,
        document: assignDocumentSkinOrder(session.document, session.selectedParts, action.order),
      }));
    case "animation_output_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: updateDocumentAnimationOutput(session.document, session.animationIndex, action.output),
      }));
    case "minecraft_version_changed":
      return updateSession(state, (session) => ({
        ...session,
        document: { ...session.document, targetMinecraftVersion: action.version },
      }));
    case "frame_command_added":
      return editCurrentAnimation(state, (animation) => addFrameCommand(animation, action.tick));
    case "frame_command_changed":
      return editCurrentAnimation(state, (animation) => updateFrameCommand(animation, action.eventIndex, action.commandIndex, action.command));
    case "frame_command_removed":
      return editCurrentAnimation(state, (animation) => removeFrameCommand(animation, action.eventIndex, action.commandIndex));
  }
}

export function findSkinCandidates(document: ConversionDocument | null): SkinCandidate[] {
  if (!document) return [];
  const candidates = Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.skinGroupId
    ? [{ nodeId, partIndex: 0, node }]
    : []);
  const partIndexByGroup = new Map<string, number>();
  return candidates.map((candidate) => {
    const group = candidate.node.skinGroupId!;
    if (!partIndexByGroup.has(group)) partIndexByGroup.set(group, partIndexByGroup.size);
    return { ...candidate, partIndex: partIndexByGroup.get(group)! };
  });
}

export function createPreviewParts(
  candidates: SkinCandidate[],
  animation: ImportedAnimation | undefined,
  tick: number | null,
): PlayerHeadPart[] {
  const previewTracks = animation?.preview?.tracks ?? animation?.tracks;
  return candidates.filter((candidate) => isVisibleAtTick(
    candidate.node.visible,
    previewTracks?.[candidate.nodeId],
    tick,
  )).map((candidate) => {
    let sourceMatrix = candidate.node.defaultMatrix;
    if (tick !== null) {
      const transforms = previewTracks?.[candidate.nodeId]?.transforms;
      for (let index = (transforms?.length ?? 0) - 1; index >= 0; index--) {
        const transform = transforms?.[index];
        if (!transform || transform.tick > tick) continue;
        sourceMatrix = transform.matrix;
        break;
      }
    }
    return {
      nodeId: candidate.nodeId,
      partIndex: candidate.partIndex,
      matrix: sourceMatrix,
      ...(candidate.node.playerHeadConversion ? { conversionMatrix: candidate.node.playerHeadConversion.matrix } : {}),
    };
  });
}

export function assignmentSummary(document: ConversionDocument): string {
  const assignments = documentPartAssignments(document);
  const assigned = Object.values(document.skinGroups).filter((group) => group.nodeIds.every((nodeId) => assignments[nodeId])).length;
  const skin = Object.keys(document.skinGroups).length
    ? `${assigned}/${Object.keys(document.skinGroups).length} skin parts assigned`
    : "No skin assignment needed";
  return document.resources.size ? `${skin} · ${document.resources.size} resource files` : skin;
}

function createConversionSession(project: ImportedProject, adapterLabel: string): ConversionSession {
  return {
    document: createConversionDocument(project, adapterLabel),
    animationIndex: 0,
    previewFrameIndex: 0,
    selectedParts: new Set(),
  };
}

function selectSessionAnimation(session: ConversionSession, animationIndex: number): ConversionSession {
  if (!session.document.animations[animationIndex]) return session;
  return { ...session, animationIndex, previewFrameIndex: 0, selectedParts: new Set() };
}

function updateSession(
  state: WorkspaceState,
  edit: (session: ConversionSession) => ConversionSession,
  page: (session: ConversionSession) => WorkspacePage = () => state.page,
): WorkspaceState {
  if (!state.session) return state;
  const session = edit(state.session);
  return { ...state, session, page: page(session) };
}

function editCurrentAnimation(state: WorkspaceState, edit: (animation: ImportedAnimation) => ImportedAnimation): WorkspaceState {
  return updateSession(state, (session) => ({
    ...session,
    document: editDocumentAnimation(session.document, session.animationIndex, edit),
  }));
}

function addFrameCommand(animation: ImportedAnimation, tick: number): ImportedAnimation {
  const event: ImportedTimelineEvent = {
    tick,
    source: { type: "server" },
    origin: { type: "root" },
    commands: [""],
  };
  return withTimelineEvents(animation, [...animation.events.timeline, event]
    .sort((first, second) => first.tick - second.tick));
}

function updateFrameCommand(
  animation: ImportedAnimation,
  eventIndex: number,
  commandIndex: number,
  command: string,
): ImportedAnimation {
  const timeline = animation.events.timeline.map((event, index) => index === eventIndex
    ? { ...event, commands: event.commands.map((current, currentIndex) => currentIndex === commandIndex ? command : current) }
    : event);
  return withTimelineEvents(animation, timeline);
}

function removeFrameCommand(animation: ImportedAnimation, eventIndex: number, commandIndex: number): ImportedAnimation {
  const timeline = animation.events.timeline.flatMap((event, index) => {
    if (index !== eventIndex) return [event];
    const commands = event.commands.filter((_, currentIndex) => currentIndex !== commandIndex);
    return commands.length === 0 ? [] : [{ ...event, commands }];
  });
  return withTimelineEvents(animation, timeline);
}

function withTimelineEvents(animation: ImportedAnimation, timeline: ImportedTimelineEvent[]): ImportedAnimation {
  return { ...animation, events: { ...animation.events, timeline } };
}
