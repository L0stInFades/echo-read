/** DOM 点读定位：把屏幕坐标换算为章节文本的字符偏移（任意字开始读的前半段） */

/** 点击坐标 → 章节字符偏移；点不到文字上返回 null */
export function offsetFromPoint(x: number, y: number): number | null {
  const doc = document as any
  let node: Node | null = null
  let offset = 0
  if (doc.caretRangeFromPoint) {
    const range: Range | null = doc.caretRangeFromPoint(x, y)
    if (!range) return null
    node = range.startContainer
    offset = range.startOffset
  } else if (doc.caretPositionFromPoint) {
    const pos = doc.caretPositionFromPoint(x, y)
    if (!pos) return null
    node = pos.offsetNode
    offset = pos.offset
  } else {
    return null
  }
  if (!node || node.nodeType !== Node.TEXT_NODE) return null
  const span = (node.parentElement as HTMLElement | null)?.closest<HTMLElement>('[data-start]')
  if (!span) return null
  // 片段末字符右半格：end-1 钳制，保证“点到的字一定被读到”（span 均非空）
  return Math.min(Number(span.dataset.start) + offset, Number(span.dataset.end) - 1)
}

/**
 * 容器内按 data-start 升序的 span 序列中，定位 offset 所在 span。
 * DOM 顺序即偏移顺序，直接二分。
 */
export function spanElAt(container: HTMLElement, offset: number): HTMLElement | null {
  const spans = container.querySelectorAll<HTMLElement>('[data-start]')
  let lo = 0
  let hi = spans.length - 1
  let best: HTMLElement | null = null
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const el = spans[mid]
    const st = Number(el.dataset.start)
    const en = Number(el.dataset.end)
    if (offset >= st && offset < en) return el
    if (offset < st) hi = mid - 1
    else {
      best = el // st <= offset 且不在本片内：记录最近的左侧 span
      lo = mid + 1
    }
  }
  return best
}
