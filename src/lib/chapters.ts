import type { Range } from '../types'
import { getChapter } from './db'
import { paraRanges } from './text'
import { segmentChapter } from './segment'

/**
 * 派生章节缓存 —— 全书任一时刻只有极少数章节驻留内存，
 * 且同一章的 文本/段落区间/合成片段 只有一份实例，
 * 由朗读引擎与阅读视图共享（引擎播放的章 = 视图显示的章是常态）。
 */

export interface DerivedChapter {
  title: string
  /** 规范纯文本（本章唯一文本驻留） */
  text: string
  /** 段落区间（渲染分块用） */
  paras: Range[]
  /** 合成片段区间（朗读与高亮用） */
  segments: Range[]
}

const LRU_MAX = 4
const cache = new Map<string, DerivedChapter>()

const keyOf = (bookId: string, index: number, maxChunk: number) => `${bookId}:${index}:${maxChunk}`

export async function getDerivedChapter(
  bookId: string,
  index: number,
  maxChunk: number
): Promise<DerivedChapter | null> {
  const key = keyOf(bookId, index, maxChunk)
  const hit = cache.get(key)
  if (hit) {
    // 触碰刷新热度
    cache.delete(key)
    cache.set(key, hit)
    return hit
  }
  const row = await getChapter(bookId, index)
  if (!row) return null
  const derived: DerivedChapter = {
    title: row.title,
    text: row.text,
    paras: paraRanges(row.text),
    segments: segmentChapter(row.text, maxChunk)
  }
  cache.set(key, derived)
  if (cache.size > LRU_MAX) {
    cache.delete(cache.keys().next().value!)
  }
  return derived
}

export function invalidateDerived(bookId: string) {
  for (const k of cache.keys()) {
    if (k.startsWith(bookId + ':')) cache.delete(k)
  }
}
