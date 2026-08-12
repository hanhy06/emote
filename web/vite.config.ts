import { defineConfig } from "vite";
import preact from "@preact/preset-vite";

export default defineConfig(({ command }) => ({
  base: command === "build" ? "/emote/" : "/",
  plugins: [preact({ reactAliasesEnabled: false })],
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: "three",
              test: /node_modules[\\/]three[\\/]/,
              priority: 1,
              maxSize: 400_000,
            },
            {
              name: "vendor",
              test: /node_modules[\\/]/,
            },
          ],
        },
      },
    },
  },
}));
