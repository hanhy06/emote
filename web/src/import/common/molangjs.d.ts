declare module "molangjs/dist/molang.esm.js" {
  export default class MolangParser {
    parse(expression: string, variables?: object): number;
    variables: Record<string, number>;
    variableHandler: null | ((key: string, variables: object, arguments?: (number | string)[]) => number);
  }
}
