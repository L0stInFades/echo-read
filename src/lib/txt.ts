import jschardet from 'jschardet'
import type { ParsedBook } from '../types'
import { CHAPTER_MAX_CHARS } from './text'

/* ---------------- 编码探测与解码 ---------------- */

/** 浏览器原生 TextDecoder 支持 gbk/gb18030/big5 等中文编码 */
export function decodeText(buffer: ArrayBuffer | Uint8Array): { text: string; encoding: string } {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)

  // BOM 快速通道
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return { text: new TextDecoder('utf-8').decode(bytes), encoding: 'utf-8' }
  }

  // UTF-8 严格解码试探
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    return { text, encoding: 'utf-8' }
  } catch {
    /* 非 UTF-8，继续探测 */
  }

  // jschardet 探测（中文小说常见 GBK/GB18030/Big5）
  // 注：jschardet 需要二进制字符串；采样前 64KB 即可保证探测准确率
  let detected = ''
  try {
    const sample = bytes.subarray(0, 65536)
    let bin = ''
    for (let i = 0; i < sample.length; i += 8192) {
      bin += String.fromCharCode(...sample.subarray(i, i + 8192))
    }
    detected = jschardet.detect(bin)?.encoding?.toLowerCase() ?? ''
  } catch {
    /* 探测失败走默认候选 */
  }
  // 能走到这里说明严格 UTF-8 已失败、必含非 ASCII 字节；
  // jschardet 在纯 ASCII 采样下可能误判 'ascii'（=windows-1252 单字节解码，永不报错），必须排除
  const candidates = [detected, 'gb18030', 'big5', 'utf-8'].filter(
    e => e && e !== 'ascii' && e !== 'us-ascii'
  )
  for (const enc of candidates) {
    try {
      const label = enc === 'gbk' ? 'gb18030' : enc
      const text = new TextDecoder(label as any).decode(bytes)
      return { text, encoding: label }
    } catch {
      continue
    }
  }
  return { text: new TextDecoder('utf-8').decode(bytes), encoding: 'utf-8' }
}

/* ---------------- 章节切分 ---------------- */

/** 候选章节标题正则（借鉴 read-cat 的内置规则思路，取匹配数最多者）。
 *  标题行不允许以句末标点结尾（排除“第二章正文。”这类正文误匹配）。
 *  min：采纳所需的最少匹配数——编号列表模式幻影风险高，要求更多佐证 */
const CHAPTER_PATTERNS: { re: RegExp; min: number }[] = [
  { re: /^[ \t]*第[0-9零〇一二三四五六七八九十百千万两\d]+[章节卷回部集篇幕][^\n。！？!?；;…]{0,30}[ \t]*$/gm, min: 1 },
  { re: /^[ \t]*(?:Chapter|CHAPTER|chap)\s*[0-9零〇一二三四五六七八九十]+[^\n。！？!?；;…]{0,30}[ \t]*$/gim, min: 1 },
  { re: /^[ \t]*(?:楔子|序言?|前言|序章|引子|终章|尾声|后记|番外篇?)[^\n。！？!?；;…]{0,30}[ \t]*$/gm, min: 1 },
  { re: /^[ \t]*[0-9]{1,5}[、.．][ \t]?[^\n。！？!?；;…]{1,30}[ \t]*$/gm, min: 3 },
  { re: /^[ \t]*卷[0-9零〇一二三四五六七八九十]+[^\n。！？!?；;…]{0,30}[ \t]*$/gm, min: 1 }
]

function toParagraphs(text: string): string[] {
  return text
    .split(/\r?\n+/)
    .map(s => s.trim())
    .filter(s => s.length > 0)
}

export function splitChapters(fullText: string): { title: string; paragraphs: string[] }[] {
  const content = fullText.replace(/\r\n?/g, '\n')

  let best: { matches: { index: number; rawLen: number; title: string }[] } | null = null
  for (const { re, min } of CHAPTER_PATTERNS) {
    re.lastIndex = 0
    const matches: { index: number; rawLen: number; title: string }[] = []
    let m: RegExpExecArray | null
    while ((m = re.exec(content)) !== null) {
      matches.push({ index: m.index, rawLen: m[0].length, title: m[0].trim() })
      if (m.index === re.lastIndex) re.lastIndex++
    }
    if (matches.length >= min && (!best || matches.length > best.matches.length)) {
      best = { matches }
    }
  }

  const chapters: { title: string; paragraphs: string[] }[] = []

  if (best) {
    const { matches } = best
    // 第一章之前的内容（封面/简介等），再短也不丢字
    const head = content.slice(0, matches[0].index).trim()
    if (head.length > 0) {
      chapters.push({ title: '开篇', paragraphs: toParagraphs(head) })
    }
    for (let i = 0; i < matches.length; i++) {
      const start = matches[i].index + matches[i].rawLen
      const end = i + 1 < matches.length ? matches[i + 1].index : content.length
      const paragraphs = toParagraphs(content.slice(start, end))
      if (paragraphs.length > 0) {
        chapters.push({ title: matches[i].title, paragraphs })
      } else if (chapters.length > 0) {
        // 空体“标题”（编号正文等幻影匹配）：降级为上一章的正文行，内容不丢
        chapters[chapters.length - 1].paragraphs.push(matches[i].title)
      } else {
        chapters.push({ title: '开篇', paragraphs: [matches[i].title] })
      }
    }
  }

  // 无章节结构：按字数在段落边界切块
  if (chapters.length === 0) {
    const paragraphs = toParagraphs(content)
    let buf: string[] = []
    let len = 0
    let part = 1
    for (const p of paragraphs) {
      if (len + p.length > CHAPTER_MAX_CHARS && buf.length > 0) {
        chapters.push({ title: `第 ${part} 节`, paragraphs: buf })
        buf = []
        len = 0
        part++
      }
      buf.push(p)
      len += p.length
    }
    if (buf.length > 0) chapters.push({ title: `第 ${part} 节`, paragraphs: buf })
  }

  return chapters
}

/** 从 TXT 文件构建书籍 */
export function parseTxt(fileName: string, buffer: ArrayBuffer): ParsedBook {
  const { text } = decodeText(buffer)
  const chapters = splitChapters(text)
  const title = fileName.replace(/\.(txt|text)$/i, '').trim() || '未命名'
  // TXT 常见约定：首行即书名，去掉以免重复出现在正文
  const first = chapters[0]
  if (first && first.paragraphs.length > 0 && first.paragraphs[0].trim() === title) {
    first.paragraphs.shift()
    if (first.paragraphs.length === 0) chapters.shift()
  }
  return { title, author: '', chapters }
}
