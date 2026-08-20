import type { ConverterSession } from "./converterSession";
import { animationAvailability } from "./domain/conversionSeed";

export type WorkspacePage = 0 | 1 | 2;

export interface WorkspaceState {
  session: ConverterSession | null;
  page: WorkspacePage;
  openError: string;
  exportError: string;
  operation:
    | { type: "idle" }
    | { type: "opening" | "exporting"; message: string };
}

export type WorkspaceAction =
  | { type: "begin_open"; message: string }
  | { type: "finish_open"; session: ConverterSession }
  | { type: "fail_open"; message: string }
  | { type: "begin_export"; message: string }
  | { type: "fail_export"; message: string }
  | { type: "finish_operation" }
  | { type: "set_page"; page: WorkspacePage }
  | { type: "update_session"; update: (session: ConverterSession) => ConverterSession };

export const INITIAL_WORKSPACE: WorkspaceState = {
  session: null,
  page: 0,
  openError: "",
  exportError: "",
  operation: { type: "idle" },
};

export function workspaceReducer(state: WorkspaceState, action: WorkspaceAction): WorkspaceState {
  switch (action.type) {
    case "begin_open":
      return { ...state, session: null, page: 0, openError: "", exportError: "", operation: { type: "opening", message: action.message } };
    case "finish_open": {
      const animation = action.session.document.animations[action.session.animationIndex]?.source;
      const page = animation && animationAvailability(animation).preview === "unavailable" ? 1 : 0;
      return { ...state, session: action.session, page, operation: { type: "idle" } };
    }
    case "fail_open":
      return { ...state, openError: action.message, operation: { type: "idle" } };
    case "begin_export":
      return { ...state, exportError: "", operation: { type: "exporting", message: action.message } };
    case "fail_export":
      return { ...state, exportError: action.message, operation: { type: "idle" } };
    case "finish_operation":
      return { ...state, operation: { type: "idle" } };
    case "set_page":
      return { ...state, page: action.page };
    case "update_session":
      return state.session ? { ...state, session: action.update(state.session) } : state;
  }
}
