/**
 * EPUB 解析冒烟测试：用 JSZip 现场构造一个最小 EPUB，再用 linkedom 提供 DOMParser。
 * 运行：npx tsx test/epub-sanity.ts
 */
import assert from 'node:assert'
import JSZip from 'jszip'
import { DOMParser as LinkedDOMParser } from 'linkedom'

// 为 Node 环境补齐浏览器全局
;(globalThis as any).DOMParser = LinkedDOMParser
;(globalThis as any).Node = { TEXT_NODE: 3, ELEMENT_NODE: 1 }

const { parseEpub } = await import('../src/lib/epub')

async function buildTestEpub(): Promise<ArrayBuffer> {
  const zip = new JSZip()
  zip.file(
    'META-INF/container.xml',
    `<?xml version="1.0" encoding="UTF-8"?>
     <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
       <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
     </container>`
  )
  zip.file(
    'OEBPS/content.opf',
    `<?xml version="1.0" encoding="UTF-8"?>
     <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
       <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
         <dc:identifier id="bid">test-001</dc:identifier>
         <dc:title>测试之书</dc:title>
         <dc:creator>作者甲</dc:creator>
         <dc:language>zh</dc:language>
       </metadata>
       <manifest>
         <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
         <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
         <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
       </manifest>
       <spine>
         <itemref idref="c1"/>
         <itemref idref="c2"/>
       </spine>
     </package>`
  )
  zip.file(
    'OEBPS/nav.xhtml',
    `<html xmlns="http://www.w3.org/1999/xhtml"><body>
       <nav epub:type="toc"><ol>
         <li><a href="text/ch1.xhtml">第一章 启程</a></li>
         <li><a href="text/ch2.xhtml">第二章 风暴</a></li>
       </ol></nav>
     </body></html>`
  )
  zip.file(
    'OEBPS/text/ch1.xhtml',
    `<html xmlns="http://www.w3.org/1999/xhtml"><body>
       <h1>第一章 启程</h1>
       <p>他推开那扇门。</p>
       <p>门外是漫天的<br/>星光。</p>
       <script>var x = 1;</script>
     </body></html>`
  )
  zip.file(
    'OEBPS/text/ch2.xhtml',
    `<html xmlns="http://www.w3.org/1999/xhtml"><body>
       <h2>第二章 风暴</h2>
       <div><p>风来了。</p><blockquote>「跑！」有人喊。</blockquote></div>
       <table><tr><td>甲</td><td>乙</td></tr></table>
     </body></html>`
  )
  return zip.generateAsync({ type: 'arraybuffer' })
}

const buf = await buildTestEpub()
const book = await parseEpub(buf, 'fallback-name')

assert.equal(book.title, '测试之书')
assert.equal(book.author, '作者甲')
assert.equal(book.chapters.length, 2, JSON.stringify(book.chapters.map(c => c.title)))
assert.equal(book.chapters[0].title, '第一章 启程')
assert.equal(book.chapters[1].title, '第二章 风暴')

// 正文段落抽取：<br> 拆段、script 剔除、嵌套 div 不重复
assert.deepEqual(book.chapters[0].paragraphs, ['他推开那扇门。', '门外是漫天的', '星光。'])
assert.ok(book.chapters[1].paragraphs.includes('风来了。'))
assert.ok(book.chapters[1].paragraphs.includes('「跑！」有人喊。'))
// 表格单元格分段提取（不连字）
assert.ok(book.chapters[1].paragraphs.includes('甲') && book.chapters[1].paragraphs.includes('乙'))
assert.ok(!book.chapters[1].paragraphs.includes('甲乙'), 'td 不应连字')
const all = book.chapters.flatMap(c => c.paragraphs).join('|')
assert.ok(!all.includes('var x'), 'script 内容不应出现')

console.log('✓ EPUB OPF/元数据解析')
console.log('✓ EPUB spine 章节抽取与标题映射')
console.log('✓ EPUB 段落序列化（br/嵌套/script）')
console.log('\nEPUB 冒烟测试通过')
