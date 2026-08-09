import JSZip from 'jszip'
import type { ParsedBook } from '../types'

/* ---------------- EPUB 解析（zip + OPF + spine，纯文本抽取） ---------------- */

const BLOCK_TAGS = new Set([
  'p', 'div', 'section', 'article', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'li', 'blockquote', 'pre', 'tr', 'td', 'th', 'table', 'br', 'hr'
])

/** 将 XHTML 正文序列化为段落文本（块级元素换行，<br> 换行） */
function extractParagraphs(doc: Document): string[] {
  const out: string[] = []
  let buf = ''
  const flush = () => {
    const t = buf.trim()
    if (t) out.push(t)
    buf = ''
  }
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      buf += node.textContent ?? ''
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return
    const el = node as Element
    const tag = el.tagName.toLowerCase()
    if (tag === 'script' || tag === 'style' || tag === 'img' || tag === 'svg') return
    if (tag === 'br') {
      flush()
      return
    }
    const isBlock = BLOCK_TAGS.has(tag)
    if (isBlock) flush()
    el.childNodes.forEach(walk)
    if (isBlock) flush()
  }
  doc.body ? walk(doc.body) : doc.documentElement && walk(doc.documentElement)
  flush()
  return out
}

function dirname(path: string): string {
  const i = path.lastIndexOf('/')
  return i < 0 ? '' : path.slice(0, i + 1)
}

/** 相对 OPF 所在目录解析 href（处理 ../ 与 ./；/ 开头按包根；坏编码回退原样） */
function resolvePath(base: string, href: string): string {
  const raw = href.split('#')[0]
  let clean = raw
  try {
    clean = decodeURIComponent(raw)
  } catch {
    /* href 含未编码的字面 % 等，按原样使用 */
  }
  const stack = ((clean.startsWith('/') ? '' : base) + clean).split('/')
  const out: string[] = []
  for (const seg of stack) {
    if (seg === '.' || seg === '') continue
    if (seg === '..') out.pop()
    else out.push(seg)
  }
  return out.join('/')
}

async function readText(zip: JSZip, path: string): Promise<string | null> {
  const f = zip.file(path)
  return f ? f.async('text') : null
}

/** 封面图缩放到小尺寸 dataURL，避免 IndexedDB 存大图 */
async function coverToDataUrl(blob: Blob, maxWidth = 360): Promise<string> {
  try {
    const bmp = await createImageBitmap(blob)
    const scale = Math.min(1, maxWidth / bmp.width)
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(bmp.width * scale)
    canvas.height = Math.round(bmp.height * scale)
    const ctx = canvas.getContext('2d')!
    ctx.drawImage(bmp, 0, 0, canvas.width, canvas.height)
    bmp.close()
    return canvas.toDataURL('image/jpeg', 0.72)
  } catch {
    return await new Promise<string>((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(r.result as string)
      r.onerror = reject
      r.readAsDataURL(blob)
    })
  }
}

interface OpfInfo {
  title: string
  author: string
  intro?: string
  coverPath?: string
  base: string
  manifest: Map<string, { href: string; mediaType: string; properties: string }>
  spine: string[] // manifest id 序列
}

function parseOpf(xml: string, opfPath: string): OpfInfo {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  // dc:title / dc:creator 等带命名空间前缀，按 localName 匹配
  const text = (name: string) => {
    const els = doc.querySelectorAll('metadata *')
    for (const el of els) {
      const ln = (el.localName || el.tagName || '').split(':').pop()
      if (ln === name) return el.textContent?.trim() || ''
    }
    return ''
  }
  const title = text('title') || '未命名'
  const author = text('creator')
  const intro = text('description') || undefined

  const manifest = new Map<string, { href: string; mediaType: string; properties: string }>()
  doc.querySelectorAll('manifest > item').forEach(item => {
    const id = item.getAttribute('id')
    const href = item.getAttribute('href')
    if (!id || !href) return
    manifest.set(id, {
      href,
      mediaType: item.getAttribute('media-type') || '',
      properties: item.getAttribute('properties') || ''
    })
  })

  const spine: string[] = []
  doc.querySelectorAll('spine > itemref').forEach(ref => {
    const idref = ref.getAttribute('idref')
    const linear = ref.getAttribute('linear')
    if (idref && linear !== 'no' && manifest.has(idref)) spine.push(idref)
  })

  // 封面：EPUB3 properties="cover-image" 或 EPUB2 <meta name="cover" content="id">
  let coverPath: string | undefined
  for (const [, v] of manifest) {
    if (v.properties.includes('cover-image')) coverPath = v.href
  }
  if (!coverPath) {
    const metaCover = doc.querySelector('metadata > meta[name="cover"]')?.getAttribute('content')
    if (metaCover && manifest.has(metaCover)) coverPath = manifest.get(metaCover)!.href
  }

  return { title, author, intro, coverPath, base: dirname(opfPath), manifest, spine }
}

/** 从 nav / NCX 提取 href → 章节标题 映射（href 相对 nav 文档自身目录解析） */
async function extractNavTitles(
  zip: JSZip,
  opf: OpfInfo
): Promise<Map<string, string>> {
  const map = new Map<string, string>()
  const put = (navBase: string, href: string, label: string) => {
    const full = resolvePath(navBase, href)
    const t = label.trim().replace(/\s+/g, ' ')
    if (t && !map.has(full)) map.set(full, t)
  }

  // EPUB3 nav 文档
  for (const [, v] of opf.manifest) {
    if (!v.properties.includes('nav')) continue
    const navPath = resolvePath(opf.base, v.href)
    const navXml = await readText(zip, navPath)
    if (!navXml) continue
    const navBase = dirname(navPath)
    const doc = new DOMParser().parseFromString(navXml, 'text/html')
    doc.querySelectorAll('nav a[href]').forEach(a => {
      put(navBase, a.getAttribute('href')!, a.textContent ?? '')
    })
    if (map.size > 0) return map
  }
  // EPUB2 NCX
  const ncxItem = [...opf.manifest.values()].find(v => v.mediaType === 'application/x-dtbncx+xml')
  if (ncxItem) {
    const ncxPath = resolvePath(opf.base, ncxItem.href)
    const ncxXml = await readText(zip, ncxPath)
    if (ncxXml) {
      const ncxBase = dirname(ncxPath)
      const doc = new DOMParser().parseFromString(ncxXml, 'application/xml')
      doc.querySelectorAll('navPoint').forEach(np => {
        const label = np.querySelector('navLabel > text')?.textContent ?? ''
        const src = np.querySelector('content')?.getAttribute('src')
        if (src) put(ncxBase, src, label)
      })
    }
  }
  return map
}

export async function parseEpub(buffer: ArrayBuffer, fallbackName: string): Promise<ParsedBook> {
  const zip = await JSZip.loadAsync(buffer)

  const containerXml = await readText(zip, 'META-INF/container.xml')
  if (!containerXml) throw new Error('无效的 EPUB：缺少 container.xml')
  const containerDoc = new DOMParser().parseFromString(containerXml, 'application/xml')
  const opfPath = containerDoc.querySelector('rootfile')?.getAttribute('full-path')
  if (!opfPath) throw new Error('无效的 EPUB：找不到 OPF 路径')

  const opfXml = await readText(zip, opfPath)
  if (!opfXml) throw new Error('无效的 EPUB：缺少 OPF 文件')
  const opf = parseOpf(opfXml, opfPath)
  if (opf.title === '未命名') opf.title = fallbackName.replace(/\.epub$/i, '')

  const navTitles = await extractNavTitles(zip, opf)

  // 封面
  let cover: string | undefined
  if (opf.coverPath) {
    const f = zip.file(resolvePath(opf.base, opf.coverPath))
    if (f) {
      const blob = await f.async('blob')
      cover = await coverToDataUrl(blob)
    }
  }

  // 逐 spine item 抽取正文
  const chapters: { title: string; paragraphs: string[] }[] = []
  for (let i = 0; i < opf.spine.length; i++) {
    const item = opf.manifest.get(opf.spine[i])!
    if (!/xhtml|html/i.test(item.mediaType)) continue
    const path = resolvePath(opf.base, item.href)
    const html = await readText(zip, path)
    if (!html) continue
    const doc = new DOMParser().parseFromString(html, 'text/html')
    const paragraphs = extractParagraphs(doc)
    if (paragraphs.length === 0) continue

    let title = navTitles.get(path)
    if (!title) {
      const heading = doc.querySelector('h1,h2,h3,h4')?.textContent?.trim()
      title = heading && heading.length <= 60 ? heading : `第 ${chapters.length + 1} 节`
    }
    // 去掉标题段与正文重复
    const body = paragraphs[0] === title ? paragraphs.slice(1) : paragraphs
    chapters.push({ title, paragraphs: body.length > 0 ? body : paragraphs })
  }

  if (chapters.length === 0) throw new Error('EPUB 中没有可用的文本章节')

  return { title: opf.title, author: opf.author, intro: opf.intro, cover, chapters }
}
