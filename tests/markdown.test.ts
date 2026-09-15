// Markdown subset renderer: safe HTML, markers never smuggle markup,
// code spans stay literal, checklist lines become glyphs.
import { describe, expect, test } from "bun:test";
import { renderBody } from "../web/components/markdown.js";

describe("renderBody", () => {
  test("plain text passes through (escaped)", () => {
    expect(renderBody("hello")).toBe("hello");
    expect(renderBody("<b>hi</b>")).toBe("&lt;b&gt;hi&lt;/b&gt;");
    expect(renderBody("<img src=x onerror=y>")).not.toContain("<img");
  });

  test("bold, italic, strike", () => {
    expect(renderBody("a **b** c")).toBe("a <strong>b</strong> c");
    expect(renderBody("a *b* c")).toBe("a <em>b</em> c");
    expect(renderBody("a ~~b~~ c")).toBe("a <del>b</del> c");
  });

  test("unclosed markers stay literal", () => {
    expect(renderBody("a **b")).toBe("a **b");
    expect(renderBody("a *b")).toBe("a *b");
  });

  test("code spans format nothing inside", () => {
    expect(renderBody("`<b>`")).toBe("<code>&lt;b&gt;</code>");
    expect(renderBody("`**x**`")).toBe("<code>**x**</code>");
  });

  test("checklist lines become glyphs; rest still formats", () => {
    expect(renderBody("- [ ] milk")).toContain("☐");
    expect(renderBody("- [x] milk")).toContain("☑");
    expect(renderBody("- [x] **milk**")).toContain("<strong>milk</strong>");
    expect(renderBody("-not a list")).toBe("-not a list");
  });

  test("drawing markers become canvases with the id", () => {
    const out = renderBody("hi\n![drawing](abc-123)");
    expect(out).toContain('<canvas class="drawing" data-drawing="abc-123"');
    expect(out).toContain("hi");
    expect(renderBody("see ![drawing](abc) here")).not.toContain("<canvas");
  });

  test("multiline bodies keep every line", () => {
    const out = renderBody("one\n- [ ] two\n**three**");
    expect(out).toContain("one");
    expect(out).toContain("☐");
    expect(out).toContain("<strong>three</strong>");
  });
});
