import fs from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const workspace = 'C:/Users/14426/Desktop/hnust-selection'
const modules = 'C:/Users/14426/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
const output = path.join(workspace, '.pptx-build', 'final-render')
process.env.RUNTIME_NODE_MODULES = modules
process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES = modules
await fs.mkdir(output, { recursive: true })
const { FileBlob, PresentationFile } = await import(pathToFileURL(path.join(modules, '@oai/artifact-tool/dist/artifact_tool.mjs')).href)
const input = path.join(workspace, 'presentations-output', '湖南科技大学师生互选系统-项目介绍-实际页面展示版-终稿.pptx')
const presentation = await PresentationFile.importPptx(await FileBlob.load(input))
for (const [index, slide] of presentation.slides.items.entries()) {
  const png = await presentation.export({ slide, format: 'png', scale: 1 })
  await fs.writeFile(path.join(output, `slide-${String(index + 1).padStart(2, '0')}.png`), new Uint8Array(await png.arrayBuffer()))
}
console.log(JSON.stringify({ count: presentation.slides.items.length, output }, null, 2))
