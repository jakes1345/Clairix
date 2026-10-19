// Module resolution for the Worker sources under Node: "cloudflare:workers" is
// served by a small stand-in, and the extensionless relative imports the
// Workers bundler accepts get their ".ts".
const STUB = new URL("./cloudflare-workers.mjs", import.meta.url).href;

export async function resolve(specifier, context, nextResolve) {
  if (specifier === "cloudflare:workers") return { url: STUB, shortCircuit: true };
  const relative = specifier.startsWith("./") || specifier.startsWith("../");
  if (relative && context.parentURL?.endsWith(".ts") && !/\.[cm]?[jt]s$/.test(specifier)) {
    return nextResolve(`${specifier}.ts`, context);
  }
  return nextResolve(specifier, context);
}
