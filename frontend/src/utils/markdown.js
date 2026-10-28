/* 轻量 Markdown 渲染:先转义 HTML,再解析结构,避免模型输出注入脚本。 */

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ESC[c])

export function renderMarkdown(src) {
  if (!src) return ''
  const blocks = []
  let text = String(src).replace(/\r\n?/g, '\n')

  text = text.replace(/```[^\n]*\n?([\s\S]*?)```/g, (_, code) => {
    blocks.push(`<pre class="md-code"><code>${escapeHtml(code.replace(/\n$/, ''))}</code></pre>`)
    return `\u0002B${blocks.length - 1}\u0002`
  })

  text = escapeHtml(text)

  const inlineCodes = []
  text = text.replace(/`([^`\n]+)`/g, (_, code) => {
    inlineCodes.push(`<code class="md-inline">${code}</code>`)
    return `\u0002I${inlineCodes.length - 1}\u0002`
  })

  text = text
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')

  const lines = text.split('\n')
  const out = []
  let list = null
  let para = []
  let quote = []

  const flushPara = () => {
    if (!para.length) return
    out.push(`<p>${para.join('<br>')}</p>`)
    para = []
  }
  const closeList = () => {
    if (list) { out.push(`</${list}>`); list = null }
  }
  const flushQuote = () => {
    if (!quote.length) return
    out.push(`<blockquote>${quote.join('<br>')}</blockquote>`)
    quote = []
  }
  const flushAll = () => { flushPara(); closeList(); flushQuote() }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const t = line.trim()
    if (!t) { flushAll(); continue }

    const fence = t.match(/^\u0002B(\d+)\u0002$/)
    if (fence) { flushAll(); out.push(blocks[Number(fence[1])]); continue }

    const heading = t.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      flushAll()
      const level = heading[1].length
      out.push(`<h${level}>${heading[2]}</h${level}>`)
      continue
    }

    if (/^(-{3,}|\*{3,}|_{3,})$/.test(t)) { flushAll(); out.push('<hr>'); continue }

    if (t.startsWith('&gt;')) {
      flushPara(); closeList()
      quote.push(t.replace(/^&gt;\s?/, ''))
      continue
    }

    const ul = t.match(/^[-*+]\s+(.*)$/)
    const ol = t.match(/^\d+\.\s+(.*)$/)
    if (ul || ol) {
      flushPara(); flushQuote()
      const want = ul ? 'ul' : 'ol'
      if (list !== want) { closeList(); out.push(`<${want}>`); list = want }
      out.push(`<li>${(ul || ol)[1]}</li>`)
      continue
    }

    if (t.includes('|') && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1])) {
      flushAll()
      const rows = []
      let j = i + 2
      while (j < lines.length && lines[j].includes('|') && lines[j].trim()) {
        rows.push(lines[j])
        j++
      }
      out.push(tableHtml(t, rows))
      i = j - 1
      continue
    }

    flushQuote()
    closeList()
    para.push(t)
  }
  flushAll()

  let html = out.join('')
  html = html.replace(/\u0002I(\d+)\u0002/g, (_, n) => inlineCodes[Number(n)] || '')
  return html
}

function cells(row) {
  let s = row.trim()
  if (s.startsWith('|')) s = s.slice(1)
  if (s.endsWith('|')) s = s.slice(0, -1)
  return s.split('|').map(c => c.trim())
}

function tableHtml(head, rows) {
  const th = cells(head).map(c => `<th>${c}</th>`).join('')
  const body = rows.map(r => `<tr>${cells(r).map(c => `<td>${c}</td>`).join('')}</tr>`).join('')
  return `<div class="md-table-wrap"><table class="md-table"><thead><tr>${th}</tr></thead><tbody>${body}</tbody></table></div>`
}
