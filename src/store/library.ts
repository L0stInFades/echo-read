import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { BookMeta, ChapterContent, ParsedBook } from '../types'
import * as db from '../lib/db'
import { nanoid } from '../lib/hash'
import { boundChapters } from '../lib/text'
import { invalidateDerived } from '../lib/chapters'
import { parseTxt } from '../lib/txt'
import { parseEpub } from '../lib/epub'

export const useLibraryStore = defineStore('library', () => {
  const books = ref<BookMeta[]>([])
  const importing = ref(false)
  const importError = ref('')

  async function refresh() {
    books.value = await db.listBooks()
  }

  async function importFile(file: File): Promise<BookMeta | null> {
    importing.value = true
    importError.value = ''
    try {
      const buffer = await file.arrayBuffer()
      const ext = file.name.split('.').pop()?.toLowerCase()
      let parsed: ParsedBook
      if (ext === 'epub') {
        parsed = await parseEpub(buffer, file.name)
      } else if (ext === 'txt') {
        parsed = parseTxt(file.name, buffer)
      } else {
        throw new Error('仅支持 TXT / EPUB 文件')
      }

      // 统一守卫：章节限长归一化 + 段落合成为规范纯文本（内存上限由此锁定）
      const normalized = boundChapters(parsed.chapters)
      if (normalized.length === 0) throw new Error('未能解析出任何章节')

      const bookId = nanoid()
      const totalChars = normalized.reduce((s, c) => s + c.text.length, 0)
      const meta: BookMeta = {
        id: bookId,
        title: parsed.title,
        author: parsed.author || '佚名',
        format: ext === 'epub' ? 'epub' : 'txt',
        cover: parsed.cover,
        intro: parsed.intro,
        chapterCount: normalized.length,
        totalChars,
        createdAt: Date.now(),
        progress: { chapterIndex: 0, offset: 0 }
      }
      const titles = normalized.map(c => c.title)
      const chapters: ChapterContent[] = normalized.map((c, i) => ({
        bookId,
        index: i,
        title: c.title,
        text: c.text
      }))
      await db.putBook(meta, titles, chapters)
      await refresh()
      return meta
    } catch (e: any) {
      importError.value = e?.message ?? String(e)
      return null
    } finally {
      importing.value = false
    }
  }

  async function remove(bookId: string) {
    await db.deleteBook(bookId)
    invalidateDerived(bookId)
    await refresh()
  }

  async function saveProgress(bookId: string, chapterIndex: number, offset: number) {
    await db.updateProgress(bookId, chapterIndex, offset)
    const b = books.value.find(x => x.id === bookId)
    if (b) {
      b.progress = { chapterIndex, offset }
      b.lastReadAt = Date.now()
    }
  }

  return { books, importing, importError, refresh, importFile, remove, saveProgress }
})
