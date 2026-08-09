import type { Range } from '../types'

/** 单章内存上限：章节是渲染/合成/缓存的统一工作单元，限长即限内存 */
export const CHAPTER_MAX_CHARS = 8000

/** 段落数组 → 规范章节文本（入库唯一形态） */
export const joinParagraphs = (paragraphs: string[]): string => paragraphs.join('\n')

/** 规范文本 → 段落区间序列（O(n) 单趟，零字符串副本） */
export function paraRanges(text: string): Range[] {
  const out: Range[] = []
  let start = 0
  for (let i = 0; i <= text.length; i++) {
    if (i === text.length || text.charCodeAt(i) === 10 /* \n */) {
      out.push({ start, end: i })
      start = i + 1
    }
  }
  return out
}

/**
 * 渲染布局合并：段落区间 × 片段区间均按偏移有序，
 * 双指针一趟归并出每段重叠的片段列表 —— O(P+S)。
 */
export function layoutBlocks(paras: Range[], segments: Range[]): Range[][] {
  const blocks: Range[][] = []
  let s = 0
  for (const p of paras) {
    const spans: Range[] = []
    while (s < segments.length && segments[s].end <= p.start) s++
    for (let j = s; j < segments.length && segments[j].start < p.end; j++) {
      spans.push(segments[j])
    }
    blocks.push(spans)
  }
  return blocks
}

/** 片段区间裁剪到段落内并取文本（渲染切片在用到时才发生） */
export const fragText = (text: string, seg: Range, para: Range): string =>
  text.slice(Math.max(seg.start, para.start), Math.min(seg.end, para.end))

/**
 * 章节限长归一化：把任意解析产物切到 CHAPTER_MAX_CHARS 以内（段落边界处断开），
 * 超长章自动拆分并追加序号后缀。TXT 的兜底分章与 EPUB 的单文件大章共用此守卫。
 */
export function boundChapters(
  chapters: { title: string; paragraphs: string[] }[]
): { title: string; text: string }[] {
  const out: { title: string; text: string }[] = []
  for (const c of chapters) {
    const parts: string[] = []
    let buf: string[] = []
    let len = 0
    const flush = () => {
      if (buf.length) parts.push(joinParagraphs(buf))
      buf = []
      len = 0
    }
    for (const p of c.paragraphs) {
      if (len + p.length > CHAPTER_MAX_CHARS) flush()
      if (p.length > CHAPTER_MAX_CHARS) {
        // 无换行的巨型段落：硬切
        for (let i = 0; i < p.length; i += CHAPTER_MAX_CHARS) {
          parts.push(p.slice(i, i + CHAPTER_MAX_CHARS))
        }
        continue
      }
      buf.push(p)
      len += p.length + 1
    }
    flush()
    for (let i = 0; i < parts.length; i++) {
      out.push({ title: parts.length > 1 ? `${c.title}（${i + 1}）` : c.title, text: parts[i] })
    }
  }
  return out
}
