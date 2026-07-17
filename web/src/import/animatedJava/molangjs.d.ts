declare module "molangjs/dist/molang.esm.js" {
  export default class MolangParser {
    parse(expression: string, variables?: object): number;
    variableHandler: null | ((key: string, variables: object, arguments?: []) => number);
  }
}
