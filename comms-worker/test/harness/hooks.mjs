// Module resolution for the Worker sources under Node: "cloudflare:workers" is
// served by a small stand-in, and the extensionless relative imports the
// Workers bundler accepts get their ".ts".
const STUB = new URL("./cloudflare-workers.mjs", import.meta.url).href;

export async function resolve(specifier, context, nextResolve) {
  if (specifier === "cloudflare:workers") return { url: STUB, shortCircuit: true };
  const relative = specifier.startsWith("./") || specifier.startsWith("../");
  if (relative && context.parentURL?.endsWith(".ts") && !/\.([cm]?[jt]s|wasm)$/.test(specifier)) {
    return nextResolve(`${specifier}.ts`, context);
  }
  return nextResolve(specifier, context);
}

// A .wasm import is a compiled module under Wrangler; the same here.
export async function load(url, context, nextLoad) {
  if (url.endsWith(".wasm")) {
    const path = JSON.stringify(new URL(url).pathname);
    return {
      format: "module",
      shortCircuit: true,
      source: `import { readFileSync } from "node:fs"; export default new WebAssembly.Module(readFileSync(${path}));`,
    };
  }
  return nextLoad(url, context);
}
