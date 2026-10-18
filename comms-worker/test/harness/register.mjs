// Loaded with `node --import` before the tests: lets Node load the Worker's own
// sources (extensionless imports, the cloudflare:workers module).
import { register } from "node:module";

register("./hooks.mjs", import.meta.url);
