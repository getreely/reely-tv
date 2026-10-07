import { afterEach, describe, expect, it } from "vitest";
import { receive, shellFetch, tell, waitingCount } from "../../src/core/bridge";
import { forgetShellServers, vegaFetch } from "../../src/core/http";
import { actionOf } from "../../src/core/keys";
import { answerParts, deliver, failedPart, parseMessage } from "../../../vega/src/answer";

/*
 * The Fire TV's Vega OS runs the LG app inside a WebView in a small shell: what the page
 * can't do (leave, or ask a server that only answers its own site), the shell does.
 */

const sent: string[] = [];
const g = globalThis as unknown as { window?: { ReactNativeWebView?: { postMessage(m: string): void } } };

function withShell() {
  sent.length = 0;
  g.window = { ReactNativeWebView: { postMessage: (m: string) => void sent.push(m) } };
}

afterEach(() => {
  delete g.window;
  forgetShellServers();
});

describe("the Vega shell", () => {
  it("is told to leave, where there is one", () => {
    expect(tell({ type: "exit" })).toBe(false);
    withShell();
    expect(tell({ type: "exit" })).toBe(true);
    expect(JSON.parse(sent[0])).toEqual({ type: "exit" });
  });

  it("asks a server for the page, the answer coming back in parts", async () => {
    withShell();
    const answer = shellFetch("http://panel.example/player_api.php", { method: "POST", body: new URLSearchParams({ a: "1" }), headers: { "X-Plex-Token": "t" } });
    const asked = JSON.parse(sent[0]);
    expect(asked).toMatchObject({ type: "fetch", url: "http://panel.example/player_api.php", method: "POST", body: "a=1" });
    expect(asked.headers["content-type"]).toContain("x-www-form-urlencoded");
    expect(asked.headers["x-plex-token"]).toBe("t");
    // Out of order, as the shell may send them; the second part's repeat is ignored.
    receive(JSON.stringify({ id: asked.id, part: 1, of: 2, data: "world\"}" }));
    receive({ id: asked.id, part: 1, of: 2, data: "world\"}" });
    receive({ id: asked.id, part: 0, of: 2, data: "{\"hello\":\"", status: 200, headers: { "content-type": "application/json" } });
    const response = await answer;
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ hello: "world" });
    expect(waitingCount()).toBe(0);
  });

  it("an answer with no body (204) is still an answer", async () => {
    withShell();
    const answer = shellFetch("http://plex.example/:/timeline");
    receive({ id: JSON.parse(sent[0]).id, part: 0, of: 1, data: "", status: 204 });
    expect((await answer).status).toBe(204);
  });

  it("a request the shell couldn't make fails as the page's own would", async () => {
    withShell();
    const answer = shellFetch("http://nowhere.example/");
    receive({ id: JSON.parse(sent[0]).id, part: 0, of: 1, data: "", error: "Network request failed" });
    await expect(answer).rejects.toBeInstanceOf(TypeError);
  });

  it("a request given up on is forgotten", async () => {
    withShell();
    const controller = new AbortController();
    const answer = shellFetch("http://slow.example/", { signal: controller.signal });
    controller.abort();
    await expect(answer).rejects.toMatchObject({ name: "AbortError" });
    expect(waitingCount()).toBe(0);
  });
});

describe("asking servers on Vega", () => {
  it("the page asks first; refused, the shell asks, and from then on for that server", async () => {
    const local: string[] = [];
    const remote: string[] = [];
    const ask = vegaFetch(
      async (input) => {
        local.push(String(input));
        if (String(input).startsWith("http://panel.example")) throw new TypeError("Failed to fetch");
        return new Response("page");
      },
      async (url) => {
        remote.push(url);
        return new Response("shell");
      },
    );
    expect(await (await ask("http://plex.example/library")).text()).toBe("page");
    expect(await (await ask("http://panel.example/a")).text()).toBe("shell");
    expect(await (await ask("http://panel.example/b")).text()).toBe("shell");
    expect(local).toEqual(["http://plex.example/library", "http://panel.example/a"]);
    expect(remote).toEqual(["http://panel.example/a", "http://panel.example/b"]);
  });

  it("a request cancelled for taking too long isn't asked again", async () => {
    const controller = new AbortController();
    let shellAsked = false;
    const ask = vegaFetch(
      async () => {
        controller.abort();
        throw new DOMException("Aborted", "AbortError");
      },
      async () => {
        shellAsked = true;
        return new Response("");
      },
    );
    await expect(ask("http://slow.example/", { signal: controller.signal })).rejects.toBeTruthy();
    expect(shellAsked).toBe(false);
  });
});

describe("the Fire TV remote on Vega", () => {
  it("Back is GoBack, and its media keys are read", () => {
    expect(actionOf({ keyCode: 27, key: "GoBack" })).toBe("back");
    expect(actionOf({ keyCode: 179, key: "MediaPlayPause" })).toBe("playPause");
    expect(actionOf({ keyCode: 227, key: "MediaRewind" })).toBe("rewind");
    expect(actionOf({ keyCode: 228, key: "MediaFastForward" })).toBe("forward");
    expect(actionOf({ keyCode: 13, key: "Enter" })).toBe("ok");
  });
});

describe("the shell and the page, end to end", () => {
  it("a long answer, cut into parts by the shell, comes back whole to the page", async () => {
    withShell();
    const answer = shellFetch("http://panel.example/catalogue");
    const asked = parseMessage(sent[0]);
    expect(asked).toMatchObject({ type: "fetch", url: "http://panel.example/catalogue" });
    const body = JSON.stringify({ films: Array.from({ length: 3000 }, (_, i) => ({ id: i, name: `Film "${i}" \u2028 é` })) });
    const parts = answerParts((asked as { id: number }).id, body, { status: 200, headers: { "content-type": "application/json" } }, 10_000);
    expect(parts.length).toBeGreaterThan(5);
    // Each delivered as the shell would: the script's single argument, read back as the page would.
    for (const part of parts.reverse()) {
      const script = deliver(part);
      expect(script.endsWith("true;")).toBe(true);
      const arg = script.slice(script.indexOf("__reelyShell(", script.indexOf("&&")) + "__reelyShell(".length, script.lastIndexOf(")"));
      receive(JSON.parse(arg));
    }
    expect(await (await answer).text()).toBe(body);
  });

  it("the shell's failure is the page's", async () => {
    withShell();
    const answer = shellFetch("http://nowhere.example/");
    receive(JSON.stringify(failedPart(JSON.parse(sent[0]).id, "Network request failed")));
    await expect(answer).rejects.toThrow("Network request failed");
  });

  it("the shell knows leaving and asking, and nothing else", () => {
    expect(parseMessage(JSON.stringify({ type: "exit" }))).toEqual({ type: "exit" });
    expect(parseMessage("not json")).toBeNull();
    expect(parseMessage(JSON.stringify({ type: "fetch", id: "1", url: "x" }))).toBeNull();
  });

  it("a piece of video comes back as the same bytes, through base64 in parts", async () => {
    withShell();
    const answer = shellFetch("http://panel.example/seg1.ts", {}, true);
    const asked = JSON.parse(sent[0]);
    expect(asked.binary).toBe(true);
    const bytes = Uint8Array.from({ length: 5000 }, (_, i) => (i * 37) % 256);
    const base64 = Buffer.from(bytes).toString("base64");
    for (const part of answerParts(asked.id, base64, { status: 200 }, 1000)) receive(part);
    expect(new Uint8Array(await (await answer).arrayBuffer())).toEqual(bytes);
  });
});
