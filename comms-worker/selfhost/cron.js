// Runs the relay's scheduled() handler, as Cloudflare's cron trigger does,
// when the clairix-relay-cron timer calls this worker on its loopback socket.
// workerd has no scheduler of its own.
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("POST only\n", { status: 405 });
    const result = await env.RELAY.scheduled({ cron: "*/10 * * * *", scheduledTime: new Date() });
    return new Response(`${result.outcome}\n`, { status: result.outcome === "ok" ? 200 : 500 });
  },
};
