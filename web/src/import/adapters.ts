import type { ImportAdapter } from "./adapter";
import { animatedJavaJsonAdapter } from "./animatedJava/animatedJavaJsonAdapter";
import { bdDatapackAdapter } from "./bdDatapack/bdDatapackAdapter";
import { bdProjectAdapter } from "./bdProject/bdProjectAdapter";
import { emoteJsonAdapter } from "./emoteJson/emoteJsonAdapter";
import { geckoLibBbmodelAdapter } from "./geckoLibBbmodel/geckoLibBbmodelAdapter";

export const IMPORT_ADAPTERS: readonly ImportAdapter[] = [
  bdProjectAdapter,
  bdDatapackAdapter,
  animatedJavaJsonAdapter,
  geckoLibBbmodelAdapter,
  emoteJsonAdapter,
];
