import { test } from 'node:test';
import assert from 'node:assert/strict';
import { directoryArchive } from '../src/components/skillDirectory.ts';
function file(path: string, text: string) {
  const item = new File([text], path.split('/').at(-1)!);
  Object.defineProperty(item, 'webkitRelativePath', { value: path });
  return item;
}
test('目录打包保留嵌套中文路径、内容及 ZIP 校验值', async () => {
  const zip = await directoryArchive([file('demo/SKILL.md', '123456789'), file('demo/references/资料.md', '中文资料')]);
  const bytes = new Uint8Array(await zip.arrayBuffer());
  const view = new DataView(bytes.buffer);
  assert.equal(view.getUint32(0, true), 0x04034b50);
  assert.equal(view.getUint32(14, true), 0xcbf43926);
  assert.equal(new TextDecoder().decode(bytes.slice(30, 38)), 'SKILL.md');
  assert.equal(new TextDecoder().decode(bytes.slice(38, 47)), '123456789');
  const end = bytes.length - 22;
  assert.equal(view.getUint32(end, true), 0x06054b50);
  assert.equal(view.getUint16(end + 10, true), 2);
  assert.equal(view.getUint32(view.getUint32(end + 16, true), true), 0x02014b50);
  assert.ok(new TextDecoder().decode(bytes).includes('references/资料.md'));
});
test('拒绝路径越界、过多文件及超限内容', async () => {
  await assert.rejects(directoryArchive([file('demo/../SKILL.md', '')]), /路径无效/);
  await assert.rejects(directoryArchive(Array.from({ length: 51 }, (_, i) => file(`demo/${i}.md`, ''))), /50/);
  await assert.rejects(directoryArchive([file('demo/SKILL.md', 'x'.repeat(2097153))]), /2MB/);
});
