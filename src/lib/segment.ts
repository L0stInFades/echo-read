import type { Range } from '../types'

/** 句子切分：优先 Intl.Segmenter（中文支持好），否则用标点正则兜底。返回纯偏移区间。 */
function splitSentences(text: string): Range[] {
  const out: Range[] = []

  if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
    const seg = new (Intl as any).Segmenter('zh', { granularity: 'sentence' })
    for (const s of seg.segment(text)) {
      out.push({ start: s.index, end: s.index + s.segment.length })
    }
  } else {
    // 兜底：按中英文句末标点切分（保留标点）
    const re = /[^。！？!?；;…\n]+[。！？!?；;…]*["'”’）)\]]*\s*|[^\n]+\n|.+$/g
    let m: RegExpExecArray | null
    while ((m = re.exec(text)) !== null) {
      out.push({ start: m.index, end: m.index + m[0].length })
      if (m.index === re.lastIndex) re.lastIndex++
    }
  }

  // 过滤纯空白句
  return out.filter(s => text.slice(s.start, s.end).trim().length > 0)
}

/**
 * 将章节文本切分为 TTS 合成片段（纯偏移，不持有文本副本）：
 * 句子按顺序合并，尽量接近 maxChunkChars（减少 API 调用次数，
 * 同时保留较细的朗读高亮粒度）。超长单句硬切。
 */
export function segmentChapter(text: string, maxChunkChars: number): Range[] {
  const segments: Range[] = []

  let start = -1
  let end = -1
  const flush = () => {
    if (start < 0) return
    if (text.slice(start, end).trim()) segments.push({ start, end })
    start = -1
  }

  for (const s of splitSentences(text)) {
    const sLen = s.end - s.start
    if (sLen > maxChunkChars) {
      flush()
      for (let cur = s.start; cur < s.end; cur += maxChunkChars) {
        const hardEnd = Math.min(cur + maxChunkChars, s.end)
        // 硬切也要跳过纯空白片段（劣质排版的长空格段不送合成）
        if (text.slice(cur, hardEnd).trim()) segments.push({ start: cur, end: hardEnd })
      }
      continue
    }
    if (start < 0) {
      start = s.start
      end = s.end
    } else if (end - start + sLen > maxChunkChars) {
      flush()
      start = s.start
      end = s.end
    } else {
      end = s.end
    }
  }
  flush()
  return segments
}

/** 二分查找：包含 offset 的片段索引；落在空隙时取下一片段；越界时取边界 */
export function segmentIndexAt(segments: Range[], offset: number): number {
  if (segments.length === 0) return 0
  let lo = 0
  let hi = segments.length - 1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const s = segments[mid]
    if (offset < s.start) hi = mid - 1
    else if (offset >= s.end) lo = mid + 1
    else return mid
  }
  return Math.min(Math.max(lo, 0), segments.length - 1)
}
