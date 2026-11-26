import type { Page } from "playwright-core";
import { z } from "zod";

const TEXT_BOXES_SCRIPT = `(() => {
  const boxes = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const text = (node.textContent || "").trim();
    const element = node.parentElement;
    if (!text || !element) continue;
    const style = getComputedStyle(element);
    if (style.visibility === "hidden" || style.display === "none" || Number(style.opacity) === 0) continue;
    let clipLeft = -Infinity;
    let clipTop = -Infinity;
    let clipRight = Infinity;
    let clipBottom = Infinity;
    for (let ancestor = element; ancestor && ancestor !== document.body; ancestor = ancestor.parentElement) {
      const ancestorStyle = getComputedStyle(ancestor);
      if (ancestorStyle.overflowX !== "visible") {
        const clip = ancestor.getBoundingClientRect();
        clipLeft = Math.max(clipLeft, clip.left);
        clipRight = Math.min(clipRight, clip.right);
      }
      if (ancestorStyle.overflowY !== "visible") {
        const clip = ancestor.getBoundingClientRect();
        clipTop = Math.max(clipTop, clip.top);
        clipBottom = Math.min(clipBottom, clip.bottom);
      }
    }
    const range = document.createRange();
    range.selectNodeContents(node);
    for (const box of range.getClientRects()) {
      const left = Math.max(box.left, clipLeft);
      const right = Math.min(box.right, clipRight);
      const top = Math.max(box.top, clipTop);
      const bottom = Math.min(box.bottom, clipBottom);
      if (right - left < 1 || bottom - top < 1) continue;
      boxes.push({ x: left, y: top, width: right - left, height: bottom - top, text: text.slice(0, 40) });
    }
  }
  return boxes;
})()`;

const TEXT_BOXES = z.array(z.object({ x: z.number(), y: z.number(), width: z.number(), height: z.number(), text: z.string() }));

export type TextBox = z.infer<typeof TEXT_BOXES>[number];

export async function visibleTextBoxes(page: Page): Promise<TextBox[]> {
  return TEXT_BOXES.parse(await page.evaluate(TEXT_BOXES_SCRIPT));
}

export function crossesHorizontalLine(box: TextBox, line: number): boolean {
  return box.y < line - 1 && box.y + box.height > line + 1;
}

export function crossesVerticalLine(box: TextBox, line: number): boolean {
  return box.x < line - 1 && box.x + box.width > line + 1;
}

export async function requireNothingCut(page: Page, moment: string): Promise<void> {
  const limit: unknown = await page.evaluate("window.innerHeight");
  if (typeof limit !== "number") throw new Error("the page reported no viewport height");
  const cut = (await visibleTextBoxes(page)).filter((box) => box.y < limit - 1 && crossesHorizontalLine(box, limit));
  if (cut.length > 0) throw new Error(`text is cut by the bottom edge ${moment}: ${[...new Set(cut.map((box) => `"${box.text}"`))].join(", ")}`);
}
