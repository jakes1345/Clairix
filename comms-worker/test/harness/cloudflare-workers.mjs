// The part of "cloudflare:workers" the Worker uses: the Durable Object base class.
export class DurableObject {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }
}
