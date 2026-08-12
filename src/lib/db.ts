import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import type { BookMeta, ChapterContent, ChapterIndex } from '../types'
import { toast } from './toast'

const DB_NAME = 'echo-read'
const DB_VERSION = 2

interface AudioEntry {
  key: string
  blob: Blob
  size: number
  createdAt: number
}

interface EchoReadDB extends DBSchema {
  books: { key: string; value: BookMeta }
  chapterIndex: { key: string; value: ChapterIndex }
  chapters: {
    key: string
    value: ChapterContent & { key: string }
    indexes: { byBook: string }
  }
  audio: { key: string; value: AudioEntry }
}

let dbp: Promise<IDBPDatabase<EchoReadDB>> | null = null
/** 库已被其它标签页升到更高版本的提示只发一次，重试再失败不刷屏 */
let versionWarned = false

function db() {
  if (!dbp) {
    const p: Promise<IDBPDatabase<EchoReadDB>> = openDB<EchoReadDB>(DB_NAME, DB_VERSION, {
      upgrade(d, oldVersion) {
        // v1 → v2：章节由 paragraphs[] 改为规范纯文本，旧数据不兼容，清空重导
        if (oldVersion >= 1) {
          for (const name of ['books', 'chapterIndex', 'chapters'] as const) {
            if (d.objectStoreNames.contains(name)) d.deleteObjectStore(name)
          }
        }
        d.createObjectStore('books', { keyPath: 'id' })
        d.createObjectStore('chapterIndex', { keyPath: 'bookId' })
        const chapters = d.createObjectStore('chapters', { keyPath: 'key' })
        chapters.createIndex('byBook', 'bookId')
        if (!d.objectStoreNames.contains('audio')) {
          d.createObjectStore('audio', { keyPath: 'key' })
        }
      },
      // 其它标签页持库时让出版本升级：关闭本连接并丢弃缓存，下次调用重新打开
      //（不丢缓存会一直复用已关闭的连接，后续所有读写抛 InvalidStateError）
      blocking() {
        if (dbp === p) dbp = null
        void p.then(d => d.close()).catch(() => {})
      }
    })
    dbp = p
    // 打开失败（配额/隐私模式/被阻塞）不缓存 rejected Promise，下次调用重试
    p.catch(e => {
      if (dbp === p) dbp = null
      // 让位后库已被升到更高版本：本页旧代码无法再打开，只能提示刷新
      if ((e as DOMException)?.name === 'VersionError' && !versionWarned) {
        versionWarned = true
        toast('应用已在其它页面更新，请刷新本页', 'error', 6000)
      }
    })
  }
  return dbp
}

const chapterKey = (bookId: string, index: number) => `${bookId}:${index}`

/* ---------------- 书籍 ---------------- */

export async function putBook(meta: BookMeta, titles: string[], chapters: ChapterContent[]) {
  const d = await db()
  const tx = d.transaction(['books', 'chapterIndex', 'chapters'], 'readwrite')
  await tx.objectStore('books').put(meta)
  await tx.objectStore('chapterIndex').put({ bookId: meta.id, titles } satisfies ChapterIndex)
  const store = tx.objectStore('chapters')
  for (const c of chapters) {
    await store.put({ ...c, key: chapterKey(c.bookId, c.index) })
  }
  await tx.done
}

export async function listBooks(): Promise<BookMeta[]> {
  const d = await db()
  const all = await d.getAll('books')
  return all.sort((a, b) => (b.lastReadAt ?? b.createdAt) - (a.lastReadAt ?? a.createdAt))
}

export async function getBook(id: string): Promise<BookMeta | undefined> {
  const d = await db()
  return d.get('books', id)
}

/** 进度更新：单事务内 read-modify-write，杜绝交错导致旧进度后写 */
export async function updateProgress(bookId: string, chapterIndex: number, offset: number) {
  const d = await db()
  const tx = d.transaction('books', 'readwrite')
  const meta = await tx.store.get(bookId)
  if (meta) {
    meta.progress = { chapterIndex, offset }
    meta.lastReadAt = Date.now()
    await tx.store.put(meta)
  }
  await tx.done
}

export async function deleteBook(bookId: string) {
  const d = await db()
  const tx = d.transaction(['books', 'chapterIndex', 'chapters'], 'readwrite')
  await tx.objectStore('books').delete(bookId)
  await tx.objectStore('chapterIndex').delete(bookId)
  const idx = tx.objectStore('chapters').index('byBook')
  let cursor = await idx.openCursor(IDBKeyRange.only(bookId))
  while (cursor) {
    await cursor.delete()
    cursor = await cursor.continue()
  }
  await tx.done
}

/* ---------------- 章节 ---------------- */

export async function getChapterTitles(bookId: string): Promise<string[]> {
  const d = await db()
  const row = (await d.get('chapterIndex', bookId)) as ChapterIndex | undefined
  return row?.titles ?? []
}

export async function getChapter(bookId: string, index: number): Promise<ChapterContent | undefined> {
  const d = await db()
  return d.get('chapters', chapterKey(bookId, index))
}

/* ---------------- 音频缓存（FIFO 驱逐：按写入时间淘汰最旧） ---------------- */

const AUDIO_MAX_BYTES = 300 * 1024 * 1024 // 300MB
const AUDIO_MAX_ENTRIES = 800

export async function audioGet(key: string): Promise<Blob | undefined> {
  const d = await db()
  const row = await d.get('audio', key)
  return row?.blob
}

let putCount = 0

export async function audioPut(key: string, blob: Blob) {
  const d = await db()
  await d.put('audio', { key, blob, size: blob.size, createdAt: Date.now() } satisfies AudioEntry)
  // 驱逐检查节流：每 10 次写入才全量盘点一次（Blob 在 IDB 中为惰性引用，盘点不占内存）
  if (++putCount % 10 === 0) void evictAudio()
}

let evicting = false

async function evictAudio() {
  if (evicting) return // 并发驱逐守卫：重入直接返回，防止过度删除
  evicting = true
  try {
    const d = await db()
    const all = await d.getAll('audio')
    const total = all.reduce((s, e) => s + e.size, 0)
    if (all.length <= AUDIO_MAX_ENTRIES && total <= AUDIO_MAX_BYTES) return
    all.sort((a, b) => a.createdAt - b.createdAt)
    let bytes = total
    let count = all.length
    const tx = d.transaction('audio', 'readwrite')
    for (const e of all) {
      if (count <= AUDIO_MAX_ENTRIES * 0.8 && bytes <= AUDIO_MAX_BYTES * 0.8) break
      await tx.store.delete(e.key)
      bytes -= e.size
      count--
    }
    await tx.done
  } finally {
    evicting = false
  }
}

export async function audioCacheStats(): Promise<{ count: number; bytes: number }> {
  const d = await db()
  const all = await d.getAll('audio')
  return { count: all.length, bytes: all.reduce((s, e) => s + e.size, 0) }
}

export async function clearAudioCache() {
  const d = await db()
  await d.clear('audio')
}
